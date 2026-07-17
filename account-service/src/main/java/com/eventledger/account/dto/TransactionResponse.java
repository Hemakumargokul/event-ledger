package com.eventledger.account.dto;

import java.math.BigDecimal;

public record TransactionResponse(
        String transactionId,
        String accountId,
        BigDecimal balance) {
}
