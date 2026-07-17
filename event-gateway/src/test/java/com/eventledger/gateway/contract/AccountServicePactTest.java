package com.eventledger.gateway.contract;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.RequestResponsePact;
import au.com.dius.pact.core.model.annotations.Pact;
import com.eventledger.gateway.client.BalanceSnapshot;
import com.eventledger.gateway.client.RestAccountServiceClient;
import com.eventledger.gateway.config.LedgerMetrics;
import com.eventledger.gateway.exception.AccountNotFoundException;
import com.eventledger.gateway.model.TransactionType;
import com.eventledger.gateway.service.EventSubmission;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Consumer side of the contract (bonus: Pact). Defines every interaction the
 * Gateway relies on and proves the real RestAccountServiceClient produces
 * exactly those requests. Running this test (re)generates
 * pacts/event-gateway-account-service.json, which the Account Service
 * verifies in its own suite (GatewayContractVerificationTest) — if either
 * side drifts from the contract, that side's build goes red.
 *
 * The POST interactions deliberately pin only the response *status*: the
 * Gateway ignores the transaction response body (201 vs 200 signals
 * new-vs-replay), and a consumer contract must not over-constrain the
 * provider beyond what the consumer actually uses.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "account-service", pactVersion = PactSpecVersion.V3)
class AccountServicePactTest {

    static {
        // Write the pact where the Account Service's verification test reads it
        System.setProperty("pact.rootDir", "../pacts");
        System.setProperty("pact_do_not_track", "true");
    }

    private static final Instant TS = Instant.parse("2026-05-15T14:02:11Z");
    private static final Map<String, String> JSON = Map.of("Content-Type", "application/json");

    private static final String TRANSACTION_REQUEST = """
            {
              "transactionId": "evt-pact-1",
              "type": "CREDIT",
              "amount": 150.00,
              "currency": "USD",
              "eventTimestamp": "2026-05-15T14:02:11Z"
            }""";

    /** Same construction as production, minus resilience aspects: the raw wire contract. */
    private static RestAccountServiceClient clientFor(MockServer mockServer) {
        // The JDK HttpClient's default cleartext HTTP/2 upgrade confuses mock
        // servers (same lesson as WireMock); the real service is HTTP/1.1.
        HttpClient http11 = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        RestClient restClient = RestClient.builder()
                .baseUrl(mockServer.getUrl())
                .requestFactory(new JdkClientHttpRequestFactory(http11))
                .build();
        return new RestAccountServiceClient(restClient, new LedgerMetrics(new SimpleMeterRegistry()));
    }

    private static EventSubmission submission() {
        return new EventSubmission("evt-pact-1", "acct-pact", TransactionType.CREDIT,
                new BigDecimal("150.00"), "USD", TS, null);
    }

    @Pact(consumer = "event-gateway")
    RequestResponsePact newTransactionIsCreated(PactDslWithProvider builder) {
        return builder
                .given("transaction evt-pact-1 has not been applied")
                .uponReceiving("a new credit transaction")
                .method("POST")
                .path("/accounts/acct-pact/transactions")
                .headers(JSON)
                .body(TRANSACTION_REQUEST)
                .willRespondWith()
                .status(201)
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "newTransactionIsCreated")
    void appliesANewTransaction(MockServer mockServer) {
        clientFor(mockServer).applyTransaction(submission());
    }

    @Pact(consumer = "event-gateway")
    RequestResponsePact duplicateTransactionIsReplayed(PactDslWithProvider builder) {
        return builder
                .given("transaction evt-pact-1 was already applied to account acct-pact")
                .uponReceiving("the same transaction submitted again")
                .method("POST")
                .path("/accounts/acct-pact/transactions")
                .headers(JSON)
                .body(TRANSACTION_REQUEST)
                .willRespondWith()
                .status(200)
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "duplicateTransactionIsReplayed")
    void replaysADuplicateTransaction(MockServer mockServer) {
        clientFor(mockServer).applyTransaction(submission());
    }

    @Pact(consumer = "event-gateway")
    RequestResponsePact balanceOfExistingAccount(PactDslWithProvider builder) {
        return builder
                .given("account acct-pact exists with balance 150.00 USD")
                .uponReceiving("a balance query for an existing account")
                .method("GET")
                .path("/accounts/acct-pact/balance")
                .willRespondWith()
                .status(200)
                .headers(JSON)
                .body(new PactDslJsonBody()
                        .stringType("accountId", "acct-pact")
                        .decimalType("balance", 150.00)
                        .stringType("currency", "USD"))
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "balanceOfExistingAccount")
    void parsesTheBalanceOfAnExistingAccount(MockServer mockServer) {
        BalanceSnapshot snapshot = clientFor(mockServer).getBalance("acct-pact");

        assertThat(snapshot.accountId()).isEqualTo("acct-pact");
        assertThat(snapshot.balance()).isNotNull();
        assertThat(snapshot.currency()).isEqualTo("USD");
    }

    @Pact(consumer = "event-gateway")
    RequestResponsePact balanceOfUnknownAccount(PactDslWithProvider builder) {
        return builder
                .given("account acct-missing does not exist")
                .uponReceiving("a balance query for an unknown account")
                .method("GET")
                .path("/accounts/acct-missing/balance")
                .willRespondWith()
                .status(404)
                .toPact();
    }

    @Test
    @PactTestFor(pactMethod = "balanceOfUnknownAccount")
    void translatesAnUnknownAccountTo404(MockServer mockServer) {
        assertThatThrownBy(() -> clientFor(mockServer).getBalance("acct-missing"))
                .isInstanceOf(AccountNotFoundException.class);
    }
}
