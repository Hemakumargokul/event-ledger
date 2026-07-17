package com.eventledger.gateway.client;

import java.math.BigDecimal;

/** Balance as reported by the Account Service. */
public record BalanceSnapshot(
        String accountId,
        BigDecimal balance,
        String currency) {
}
