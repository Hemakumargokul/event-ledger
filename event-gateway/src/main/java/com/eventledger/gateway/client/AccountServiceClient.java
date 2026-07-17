package com.eventledger.gateway.client;

import com.eventledger.gateway.service.EventSubmission;

/**
 * Boundary to the internal Account Service. Implementations must be
 * synchronous and throw {@link AccountServiceUnavailableException} when the
 * downstream cannot be reached (connection failure, timeout, 5xx, open
 * circuit) so callers can translate it to a 503.
 */
public interface AccountServiceClient {

    /** Applies the event's transaction to its account, idempotently downstream. */
    void applyTransaction(EventSubmission submission);
}
