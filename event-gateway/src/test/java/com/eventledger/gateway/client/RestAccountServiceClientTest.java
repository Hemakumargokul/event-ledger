package com.eventledger.gateway.client;

import com.eventledger.gateway.config.LedgerMetrics;
import com.eventledger.gateway.exception.AccountNotFoundException;
import com.eventledger.gateway.model.TransactionType;
import com.eventledger.gateway.service.EventSubmission;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wire-level contract of the RestClient-based Account Service client:
 * request path/body shape, success handling, and failure translation
 * (IO/5xx → AccountServiceUnavailableException, 404 → AccountNotFoundException).
 */
class RestAccountServiceClientTest {

    private static final Instant TS = Instant.parse("2026-05-15T14:02:11Z");

    private static WireMockServer wireMock;
    private RestAccountServiceClient client;

    @BeforeAll
    static void startServer() {
        // http2PlainDisabled: the JDK HttpClient's h2c upgrade breaks against
        // WireMock; the real Account Service is plain HTTP/1.1 Tomcat anyway.
        wireMock = new WireMockServer(wireMockConfig().dynamicPort().http2PlainDisabled(true));
        wireMock.start();
    }

    @AfterAll
    static void stopServer() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        client = clientFor(wireMock.baseUrl());
    }

    private static RestAccountServiceClient clientFor(String baseUrl) {
        return new RestAccountServiceClient(
                RestClient.builder().baseUrl(baseUrl).build(),
                new LedgerMetrics(new SimpleMeterRegistry()));
    }

    private EventSubmission submission() {
        return new EventSubmission("evt-1", "acct-1", TransactionType.CREDIT,
                new BigDecimal("150.00"), "USD", TS, null);
    }

    @Test
    void appliesTransactionWithExpectedPathAndBody() {
        wireMock.stubFor(post(urlEqualTo("/accounts/acct-1/transactions"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"transactionId\":\"evt-1\",\"accountId\":\"acct-1\",\"balance\":150.00}")));

        client.applyTransaction(submission());

        wireMock.verify(postRequestedFor(urlEqualTo("/accounts/acct-1/transactions"))
                .withRequestBody(equalToJson("""
                        {
                          "transactionId": "evt-1",
                          "type": "CREDIT",
                          "amount": 150.00,
                          "currency": "USD",
                          "eventTimestamp": "2026-05-15T14:02:11Z"
                        }
                        """)));
    }

    @Test
    void serverErrorTranslatesToUnavailable() {
        wireMock.stubFor(post(urlEqualTo("/accounts/acct-1/transactions"))
                .willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> client.applyTransaction(submission()))
                .isInstanceOf(AccountServiceUnavailableException.class);
    }

    @Test
    void connectionFailureTranslatesToUnavailable() {
        RestAccountServiceClient unreachable = clientFor("http://localhost:1");

        assertThatThrownBy(() -> unreachable.applyTransaction(submission()))
                .isInstanceOf(AccountServiceUnavailableException.class);
    }

    @Test
    void downstreamBadRequestIsNotTranslatedToUnavailable() {
        wireMock.stubFor(post(urlEqualTo("/accounts/acct-1/transactions"))
                .willReturn(aResponse().withStatus(400)));

        assertThatThrownBy(() -> client.applyTransaction(submission()))
                .isNotInstanceOf(AccountServiceUnavailableException.class);
    }

    @Test
    void getBalanceParsesDownstreamBody() {
        wireMock.stubFor(get(urlEqualTo("/accounts/acct-1/balance"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"accountId\":\"acct-1\",\"balance\":150.00,\"currency\":\"USD\"}")));

        BalanceSnapshot snapshot = client.getBalance("acct-1");

        assertThat(snapshot.accountId()).isEqualTo("acct-1");
        assertThat(snapshot.balance()).isEqualByComparingTo("150.00");
        assertThat(snapshot.currency()).isEqualTo("USD");
        wireMock.verify(getRequestedFor(urlEqualTo("/accounts/acct-1/balance")));
    }

    @Test
    void getBalance404TranslatesToAccountNotFound() {
        wireMock.stubFor(get(urlEqualTo("/accounts/acct-x/balance"))
                .willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> client.getBalance("acct-x"))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void getBalanceServerErrorTranslatesToUnavailable() {
        wireMock.stubFor(get(urlEqualTo("/accounts/acct-1/balance"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> client.getBalance("acct-1"))
                .isInstanceOf(AccountServiceUnavailableException.class);
    }
}
