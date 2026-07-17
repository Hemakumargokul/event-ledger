package com.eventledger.gateway.degradation;

import com.eventledger.gateway.model.EventRecord;
import com.eventledger.gateway.model.TransactionType;
import com.eventledger.gateway.repository.EventRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
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

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SPEC §8.3: graceful degradation with the Account Service completely down
 * (base URL points at a closed port). Writes fail with 503 and leave no
 * state; every read that only needs local data keeps working; the Gateway
 * reports itself healthy because its own dependencies are fine.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "account-service.base-url=http://localhost:1",
        "resilience4j.retry.instances.accountService.wait-duration=50ms"
})
@AutoConfigureTestRestTemplate
class GatewayDegradationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void resetCircuitBreaker() {
        circuitBreakerRegistry.circuitBreaker("accountService").reset();
    }

    private EventRecord seedEvent(String eventId, String accountId) {
        return eventRepository.save(new EventRecord(eventId, accountId, TransactionType.CREDIT,
                new BigDecimal("25.00"), "USD",
                Instant.parse("2026-05-15T14:02:11Z"), null, Instant.now()));
    }

    @Test
    void postEventReturns503WithErrorContractAndPersistsNothing() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.postForEntity("/events",
                new HttpEntity<>("""
                        {
                          "eventId": "evt-down-1",
                          "accountId": "acct-down",
                          "type": "CREDIT",
                          "amount": 10.00,
                          "currency": "USD",
                          "eventTimestamp": "2026-05-15T14:02:11Z"
                        }
                        """, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).contains("\"status\":503");
        assertThat(response.getBody()).contains("Account Service is unreachable");
        assertThat(eventRepository.findById("evt-down-1")).isEmpty();
    }

    @Test
    void getEventByIdStillWorksFromLocalData() {
        seedEvent("evt-local-1", "acct-local");

        ResponseEntity<String> response =
                restTemplate.getForEntity("/events/evt-local-1", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"eventId\":\"evt-local-1\"");
    }

    @Test
    void eventListingStillWorksFromLocalData() {
        seedEvent("evt-local-2", "acct-list");

        ResponseEntity<String> response =
                restTemplate.getForEntity("/events?account=acct-list", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"eventId\":\"evt-local-2\"");
    }

    @Test
    void balanceProxyReturns503WithClearMessage() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/accounts/acct-down/balance", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).contains("Account Service is unreachable");
    }

    @Test
    void gatewayHealthIsStillUp() {
        ResponseEntity<String> response = restTemplate.getForEntity("/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
