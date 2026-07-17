package com.eventledger.account.service;

import java.math.BigDecimal;

/**
 * Outcome of applying a transaction. {@code replayed} is true when the
 * transaction ID had already been applied and the balance was left unchanged.
 */
public record ApplyResult(
        boolean replayed,
        String transactionId,
        String accountId,
        BigDecimal balance,
        String currency) {
}
