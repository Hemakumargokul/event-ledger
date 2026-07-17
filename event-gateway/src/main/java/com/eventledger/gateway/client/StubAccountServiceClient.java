package com.eventledger.gateway.client;

import com.eventledger.gateway.service.EventSubmission;
import org.springframework.stereotype.Component;

/**
 * Placeholder so the context wires up before the real RestClient-based
 * implementation lands (Phase 4). Behaves as if the Account Service were
 * unreachable, which is the honest degraded behavior.
 */
@Component
public class StubAccountServiceClient implements AccountServiceClient {

    @Override
    public void applyTransaction(EventSubmission submission) {
        throw new AccountServiceUnavailableException(
                "Account Service client not configured yet", null);
    }
}
