package com.eventledger.gateway.client;

import com.eventledger.gateway.exception.AccountNotFoundException;
import com.eventledger.gateway.service.EventSubmission;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * RestClient-based implementation. Built from the auto-configured
 * {@link RestClient.Builder} so Micrometer Tracing instruments it and the
 * W3C traceparent header propagates downstream automatically.
 *
 * Failure translation: connection failures and downstream 5xx become
 * {@link AccountServiceUnavailableException}; a balance 404 becomes the
 * Gateway's {@link AccountNotFoundException}; any other 4xx propagates
 * untranslated (it indicates a Gateway bug, not downstream unavailability).
 */
@Component
public class RestAccountServiceClient implements AccountServiceClient {

    private final RestClient restClient;

    public RestAccountServiceClient(RestClient.Builder builder,
                                    @Value("${account-service.base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    record DownstreamTransactionRequest(
            String transactionId,
            String type,
            BigDecimal amount,
            String currency,
            Instant eventTimestamp) {
    }

    @Override
    public void applyTransaction(EventSubmission submission) {
        try {
            restClient.post()
                    .uri("/accounts/{accountId}/transactions", submission.accountId())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new DownstreamTransactionRequest(submission.eventId(),
                            submission.type().name(), submission.amount(),
                            submission.currency(), submission.eventTimestamp()))
                    .retrieve()
                    .toBodilessEntity();
        } catch (ResourceAccessException e) {
            throw new AccountServiceUnavailableException("Account Service is unreachable", e);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().is5xxServerError()) {
                throw new AccountServiceUnavailableException("Account Service is unreachable", e);
            }
            throw e;
        }
    }

    @Override
    public BalanceSnapshot getBalance(String accountId) {
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
    }
}
