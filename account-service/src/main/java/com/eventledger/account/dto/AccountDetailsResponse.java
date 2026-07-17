package com.eventledger.account.dto;

import com.eventledger.account.model.AccountTransaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record AccountDetailsResponse(
        String accountId,
        BigDecimal balance,
        String currency,
        long transactionCount,
        List<TransactionSummary> recentTransactions) {

    public record TransactionSummary(
            String transactionId,
            String type,
            BigDecimal amount,
            String currency,
            Instant eventTimestamp) {

        public static TransactionSummary from(AccountTransaction txn) {
            return new TransactionSummary(txn.getTransactionId(), txn.getType().name(),
                    txn.getAmount(), txn.getCurrency(), txn.getEventTimestamp());
        }
    }
}
