package com.eventledger.gateway.tracing;

import com.github.tomakehurst.wiremock.WireMockServer;
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
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC §9.1: the Gateway must propagate the W3C traceparent downstream so
 * both services share one trace. Proven by sending a request with a known
 * trace ID and asserting the Account Service (WireMock) receives the same
 * trace ID (with a new span ID) in its traceparent header.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class GatewayTracingTest {

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

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

    @BeforeEach
    void resetStubs() {
        WIREMOCK.resetAll();
    }

    @Test
    void downstreamCallCarriesTheCallersTraceId() {
        WIREMOCK.stubFor(post(urlEqualTo("/accounts/acct-trace/transactions"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"replayed\":false}")));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("traceparent", "00-" + TRACE_ID + "-00f067aa0ba902b7-01");
        ResponseEntity<String> response = restTemplate.postForEntity("/events",
                new HttpEntity<>("""
                        {
                          "eventId": "evt-trace-1",
                          "accountId": "acct-trace",
                          "type": "CREDIT",
                          "amount": 10.00,
                          "currency": "USD",
                          "eventTimestamp": "2026-05-15T14:02:11Z"
                        }
                        """, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // same trace ID, new span ID minted by the Gateway
        WIREMOCK.verify(postRequestedFor(urlEqualTo("/accounts/acct-trace/transactions"))
                .withHeader("traceparent", matching("00-" + TRACE_ID + "-(?!00f067aa0ba902b7)[0-9a-f]{16}-0[01]")));
    }
}
