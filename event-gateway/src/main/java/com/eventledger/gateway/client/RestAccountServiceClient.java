package com.eventledger.gateway.client;

import com.eventledger.gateway.config.LedgerMetrics;
import com.eventledger.gateway.exception.AccountNotFoundException;
import com.eventledger.gateway.service.EventSubmission;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * RestClient-based implementation. The client bean is built from the
 * auto-configured builder (see AccountClientConfig) so W3C traceparent
 * propagates, with SPEC §8.2 per-attempt timeouts.
 *
 * Resiliency (SPEC §8.1, aspect order fixed as Retry outside CircuitBreaker):
 * both policies react only to {@link AccountServiceUnavailableException},
 * which this class throws for connection failures, timeouts, and downstream
 * 5xx. A balance 404 becomes the Gateway's {@link AccountNotFoundException};
 * any other 4xx propagates untranslated (a Gateway bug, not unavailability)
 * and is therefore never retried nor counted by the circuit breaker.
 */
@Component
public class RestAccountServiceClient implements AccountServiceClient {

    static final String ACCOUNT_SERVICE = "accountService";

    private final RestClient restClient;
    private final LedgerMetrics metrics;

    public RestAccountServiceClient(RestClient accountServiceRestClient, LedgerMetrics metrics) {
        this.restClient = accountServiceRestClient;
        this.metrics = metrics;
    }

    record DownstreamTransactionRequest(
            String transactionId,
            String type,
            BigDecimal amount,
            String currency,
            Instant eventTimestamp) {
    }

    @Override
    @Retry(name = ACCOUNT_SERVICE)
    @CircuitBreaker(name = ACCOUNT_SERVICE)
    public void applyTransaction(EventSubmission submission) {
        metrics.timeAccountCall(() -> {
            try {
                restClient.post()
                        .uri("/accounts/{accountId}/transactions", submission.accountId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(new DownstreamTransactionRequest(submission.eventId(),
                                submission.type().name(), submission.amount(),
                                submission.currency(), submission.eventTimestamp()))
                        .retrieve()
                        .toBodilessEntity();
                return null;
            } catch (ResourceAccessException e) {
                throw new AccountServiceUnavailableException("Account Service is unreachable", e);
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().is5xxServerError()) {
                    throw new AccountServiceUnavailableException("Account Service is unreachable", e);
                }
                throw e;
            }
        });
    }

    @Override
    @Retry(name = ACCOUNT_SERVICE)
    @CircuitBreaker(name = ACCOUNT_SERVICE)
    public BalanceSnapshot getBalance(String accountId) {
        return metrics.timeAccountCall(() -> {
            try {
                return restClient.get()
                        .uri("/accounts/{accountId}/balance", accountId)
                        .retrieve()
                        .body(BalanceSnapshot.class);
            } catch (ResourceAccessException e) {
                throw new AccountServiceUnavailableException("Account Service is unreachable", e);
            } catch (RestClientResponseException e) {
                if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                    throw new AccountNotFoundException(accountId);
                }
                if (e.getStatusCode().is5xxServerError()) {
                    throw new AccountServiceUnavailableException("Account Service is unreachable", e);
                }
                throw e;
            }
        });
    }
}
