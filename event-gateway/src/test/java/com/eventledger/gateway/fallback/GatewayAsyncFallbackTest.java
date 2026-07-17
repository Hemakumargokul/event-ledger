package com.eventledger.gateway.fallback;

import com.eventledger.gateway.queue.QueueProcessor;
import com.eventledger.gateway.repository.EventRepository;
import com.eventledger.gateway.repository.PendingEventRepository;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Async fallback end to end with WireMock standing in for the Account
 * Service: while it is down, writes are accepted with 202 and queued
 * locally; queued events behave like stored events (duplicate replay,
 * listings); once the downstream recovers, a queue sweep applies them and
 * clears the queue.
 *
 * The scheduled sweep is effectively disabled (1h interval) so each test
 * drives the QueueProcessor deterministically.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "resilience4j.retry.instances.accountService.wait-duration=50ms",
        "account-service.read-timeout=500ms",
        "event-queue.sweep-interval=1h",
        // Pact puts Apache HttpClient on the test classpath; pin the JDK
        // client so TestRestTemplate doesn't re-send requests and skew the
        // downstream attempt counts asserted here.
        "spring.http.clients.imperative.factory=jdk"
})
@AutoConfigureTestRestTemplate
class GatewayAsyncFallbackTest {

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
    private EventRepository eventRepository;

    @Autowired
    private PendingEventRepository pendingEventRepository;

    @Autowired
    private QueueProcessor queueProcessor;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void resetState() {
        WIREMOCK.resetAll();
        circuitBreakerRegistry.circuitBreaker("accountService").reset();
        pendingEventRepository.deleteAll();
        eventRepository.deleteAll();
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

    private void stubDownstreamDown(String accountId) {
        WIREMOCK.stubFor(post(urlEqualTo(transactionsPath(accountId)))
                .willReturn(aResponse().withStatus(503)));
    }

    private void stubDownstreamHealthy(String accountId) {
        WIREMOCK.stubFor(post(urlEqualTo(transactionsPath(accountId)))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"replayed\":false}")));
    }

    @Test
    void postWhileDownReturns202AndQueuesAfterRetriesAreExhausted() {
        stubDownstreamDown("acct-q1");

        ResponseEntity<String> response = postEvent("evt-q1", "acct-q1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).contains("\"eventId\":\"evt-q1\"");
        assertThat(eventRepository.findById("evt-q1")).isPresent();
        assertThat(pendingEventRepository.findById("evt-q1")).isPresent();
        // queueing happens only after the normal retry policy gave up
        WIREMOCK.verify(exactly(3), postRequestedFor(urlEqualTo(transactionsPath("acct-q1"))));
    }

    @Test
    void duplicateOfQueuedEventReturns200WithoutCallingDownstream() {
        stubDownstreamDown("acct-q2");
        assertThat(postEvent("evt-q2", "acct-q2").getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        WIREMOCK.resetRequests();

        ResponseEntity<String> replay = postEvent("evt-q2", "acct-q2");

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).contains("\"eventId\":\"evt-q2\"");
        WIREMOCK.verify(exactly(0), postRequestedFor(urlEqualTo(transactionsPath("acct-q2"))));
    }

    @Test
    void queuedEventIsVisibleInReadsLikeAnyStoredEvent() {
        stubDownstreamDown("acct-q3");
        assertThat(postEvent("evt-q3", "acct-q3").getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        ResponseEntity<String> byId = restTemplate.getForEntity("/events/evt-q3", String.class);
        ResponseEntity<String> listing =
                restTemplate.getForEntity("/events?account=acct-q3", String.class);

        assertThat(byId.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listing.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listing.getBody()).contains("\"eventId\":\"evt-q3\"");
    }

    @Test
    void sweepAfterRecoveryAppliesTheQueuedEventDownstreamAndClearsTheQueue() {
        stubDownstreamDown("acct-q4");
        assertThat(postEvent("evt-q4", "acct-q4").getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(pendingEventRepository.findById("evt-q4")).isPresent();

        WIREMOCK.resetAll();
        stubDownstreamHealthy("acct-q4");
        circuitBreakerRegistry.circuitBreaker("accountService").reset();

        queueProcessor.sweep();

        WIREMOCK.verify(exactly(1), postRequestedFor(urlEqualTo(transactionsPath("acct-q4"))));
        assertThat(pendingEventRepository.findById("evt-q4")).isEmpty();
        assertThat(eventRepository.findById("evt-q4")).isPresent();
        // the event stays a duplicate after being applied
        assertThat(postEvent("evt-q4", "acct-q4").getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
