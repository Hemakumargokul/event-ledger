package com.eventledger.gateway.service;

import com.eventledger.gateway.model.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;

/** A validated event submission; metadata arrives pre-serialized as JSON. */
public record EventSubmission(
        String eventId,
        String accountId,
        TransactionType type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp,
        String metadataJson) {
}
