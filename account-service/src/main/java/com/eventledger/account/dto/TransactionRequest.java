package com.eventledger.account.dto;

import com.eventledger.account.model.TransactionType;
import com.eventledger.account.service.TransactionCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionRequest(
        @NotBlank String transactionId,
        @NotBlank @Pattern(regexp = "CREDIT|DEBIT", message = "must be CREDIT or DEBIT") String type,
        @NotNull @Positive BigDecimal amount,
        @NotBlank String currency,
        @NotNull Instant eventTimestamp) {

    public TransactionCommand toCommand() {
        return new TransactionCommand(transactionId, TransactionType.valueOf(type),
                amount, currency, eventTimestamp);
    }
}
