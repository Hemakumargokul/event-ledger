package com.eventledger.account.service;

import com.eventledger.account.model.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionCommand(
        String transactionId,
        TransactionType type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp) {
}
