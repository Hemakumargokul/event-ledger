package com.eventledger.gateway.resilience;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resiliency behavior per SPEC §8 with WireMock standing in for the Account
 * Service: circuit breaker opens on repeated failure and fails fast, recovers
 * through half-open probes, transient failures are retried (never 4xx), and
 * the custom metrics of SPEC §9.3 are recorded.
 *
 * Test-only overrides shrink SPEC §8.2 timings (10s open-state wait, 2s read
 * timeout, 200ms backoff) so the suite stays fast; counts and thresholds are
 * the real values.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "resilience4j.circuitbreaker.instances.accountService.wait-duration-in-open-state=500ms",
        "resilience4j.retry.instances.accountService.wait-duration=50ms",
        "account-service.read-timeout=500ms",
        // Pact (contract tests) puts Apache HttpClient on the test classpath;
        // without this pin TestRestTemplate silently switches to it, which
        // re-sends requests and breaks the attempt-count/fail-fast assertions.
        "spring.http.clients.imperative.factory=jdk"
})
@AutoConfigureTestRestTemplate
class GatewayResilienceTest {

    private static final WireMockServer WIREMOCK =
            new WireMockServer(wireMockConfig().dynamicPort().http2PlainDisabled(true));

    static {
        WIREMOCK.start();
    }

    @AfterAll
    static void stopServer() {
        WIREMOCK.stop();
    }

    @DynamicPropertySource
    static void accountServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("account-service.base-url", WIREMOCK::baseUrl);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    private BulkheadRegistry bulkheadRegistry;

    @Autowired
    private MeterRegistry meterRegistry;

    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void resetState() {
        WIREMOCK.resetAll();
        circuitBreaker = circuitBreakerRegistry.circuitBreaker("accountService");
        circuitBreaker.reset();
    }

    private String eventJson(String eventId, String accountId) {
        return """
                {
                  "eventId": "%s",
                  "accountId": "%s",
                  "type": "CREDIT",
                  "amount": 150.00,
                  "currency": "USD",
                  "eventTimestamp": "2026-05-15T14:02:11Z"
                }
                """.formatted(eventId, accountId);
    }

    private ResponseEntity<String> postEvent(String eventId, String accountId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.postForEntity("/events",
                new HttpEntity<>(eventJson(eventId, accountId), headers), String.class);
    }

    private static String transactionsPath(String accountId) {
        return "/accounts/" + accountId + "/transactions";
    }

    @Test
    void repeatedServerErrorsOpenCircuitWhichThenFailsFast() {
        WIREMOCK.stubFor(post(urlEqualTo(transactionsPath("acct-cb")))
                .willReturn(aResponse().withStatus(503)));

        // 4 submissions x up to 3 attempts each fills the 10-call window with failures
        for (int i = 1; i <= 4; i++) {
            ResponseEntity<String> response = postEvent("evt-cb-" + i, "acct-cb");
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int requestsWhileOpen = WIREMOCK.countRequestsMatching(
                postRequestedFor(urlEqualTo(transactionsPath("acct-cb"))).build()).getCount();

        long start = System.nanoTime();
        ResponseEntity<String> failFast = postEvent("evt-cb-fast", "acct-cb");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(failFast.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(failFast.getBody()).contains("Account Service is unreachable");
        // fails fast: no downstream attempt, so well under one read timeout
        assertThat(elapsed).isLessThan(Duration.ofMillis(450));
        assertThat(WIREMOCK.countRequestsMatching(
                postRequestedFor(urlEqualTo(transactionsPath("acct-cb"))).build()).getCount())
                .isEqualTo(requestsWhileOpen);
    }

    @Test
    void halfOpenProbesCloseTheCircuitAfterRecovery() throws InterruptedException {
        WIREMOCK.stubFor(post(urlEqualTo(transactionsPath("acct-rec")))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"replayed\":false}")));
        circuitBreaker.transitionToOpenState();

        Thread.sleep(600); // past the (test-shortened) open-state wait

        // 3 permitted half-open probes all succeed -> circuit closes
        for (int i = 1; i <= 3; i++) {
            ResponseEntity<String> response = postEvent("evt-rec-" + i, "acct-rec");
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void transientFailuresAreRetriedToSuccessWithExactlyThreeAttempts() {
        String path = transactionsPath("acct-retry");
        WIREMOCK.stubFor(post(urlEqualTo(path)).inScenario("recovering")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("second-attempt"));
        WIREMOCK.stubFor(post(urlEqualTo(path)).inScenario("recovering")
                .whenScenarioStateIs("second-attempt")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("third-attempt"));
        WIREMOCK.stubFor(post(urlEqualTo(path)).inScenario("recovering")
                .whenScenarioStateIs("third-attempt")
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"replayed\":false}")));

        ResponseEntity<String> response = postEvent("evt-retry-1", "acct-retry");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        WIREMOCK.verify(exactly(3), postRequestedFor(urlEqualTo(path)));
    }

    @Test
    void slowDownstreamTimesOutOnEveryAttemptAndReturns503() {
        String path = transactionsPath("acct-slow");
        WIREMOCK.stubFor(post(urlEqualTo(path))
                .willReturn(aResponse().withStatus(201).withFixedDelay(1500)));

        ResponseEntity<String> response = postEvent("evt-slow-1", "acct-slow");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        WIREMOCK.verify(exactly(3), postRequestedFor(urlEqualTo(path)));
    }

    @Test
    void downstreamBadRequestIsNeitherRetriedNorRecordedByTheCircuitBreaker() {
        String path = transactionsPath("acct-400");
        WIREMOCK.stubFor(post(urlEqualTo(path)).willReturn(aResponse().withStatus(400)));

        ResponseEntity<String> response = postEvent("evt-400-1", "acct-400");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        WIREMOCK.verify(exactly(1), postRequestedFor(urlEqualTo(path)));
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void exhaustedRetriesPersistNothingAndTheSameEventSucceedsAfterRecovery() {
        String path = transactionsPath("acct-heal");
        WIREMOCK.stubFor(post(urlEqualTo(path)).willReturn(aResponse().withStatus(503)));

        assertThat(postEvent("evt-heal-1", "acct-heal").getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(restTemplate.getForEntity("/events/evt-heal-1", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        WIREMOCK.resetAll();
        WIREMOCK.stubFor(post(urlEqualTo(path))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"replayed\":false}")));

        assertThat(postEvent("evt-heal-1", "acct-heal").getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(restTemplate.getForEntity("/events/evt-heal-1", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    void saturatedBulkheadShedsLoadImmediatelyWithoutCallingDownstream() {
        String path = transactionsPath("acct-bh");
        WIREMOCK.stubFor(post(urlEqualTo(path))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"replayed\":false}")));

        Bulkhead bulkhead = bulkheadRegistry.bulkhead("accountService");
        int acquired = 0;
        while (bulkhead.tryAcquirePermission()) {
            acquired++;
        }
        try {
            // the configured concurrency limit (SPEC-style initial value)
            assertThat(acquired).isEqualTo(10);

            long start = System.nanoTime();
            ResponseEntity<String> shed = postEvent("evt-bh-1", "acct-bh");
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(shed.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(shed.getBody()).contains("Account Service is overloaded");
            // max-wait-duration 0: shed immediately, no retry, no downstream attempt
            assertThat(elapsed).isLessThan(Duration.ofMillis(450));
            WIREMOCK.verify(exactly(0), postRequestedFor(urlEqualTo(path)));
            // a full bulkhead is local back-pressure, not downstream failure
            assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
        } finally {
            for (int i = 0; i < acquired; i++) {
                bulkhead.releasePermission();
            }
        }

        // permits released: the same event now goes through normally
        assertThat(postEvent("evt-bh-1", "acct-bh").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void customMetricsAreRecordedWithExpectedTags() {
        String path = transactionsPath("acct-metrics");
        WIREMOCK.stubFor(post(urlEqualTo(path))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"replayed\":false}")));

        postEvent("evt-metrics-1", "acct-metrics");                    // created
        postEvent("evt-metrics-1", "acct-metrics");                    // duplicate
        restTemplate.postForEntity("/events",
                new HttpEntity<>("{\"eventId\":\"evt-metrics-bad\",\"accountId\":\"acct-metrics\",\"type\":\"CREDIT\"}",
                        jsonHeaders()), String.class);                 // rejected (missing fields)

        WIREMOCK.resetAll();
        WIREMOCK.stubFor(post(urlEqualTo(path)).willReturn(aResponse().withStatus(503)));
        postEvent("evt-metrics-2", "acct-metrics");                    // unavailable

        assertThat(counterValue("created")).isGreaterThanOrEqualTo(1.0);
        assertThat(counterValue("duplicate")).isGreaterThanOrEqualTo(1.0);
        assertThat(counterValue("rejected")).isGreaterThanOrEqualTo(1.0);
        assertThat(counterValue("unavailable")).isGreaterThanOrEqualTo(1.0);

        assertThat(meterRegistry.find("ledger.account.call").tag("outcome", "success").timer())
                .isNotNull();
        assertThat(meterRegistry.find("ledger.account.call").tag("outcome", "failure").timer())
                .isNotNull();
    }

    private double counterValue(String outcome) {
        var counter = meterRegistry.find("ledger.events.submitted")
                .tag("type", "CREDIT").tag("outcome", outcome).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
