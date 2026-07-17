package com.eventledger.gateway.dto;

import com.eventledger.gateway.model.TransactionType;
import com.eventledger.gateway.service.EventSubmission;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;

public record EventRequest(
        @NotBlank String eventId,
        @NotBlank String accountId,
        @NotBlank @Pattern(regexp = "CREDIT|DEBIT", message = "must be CREDIT or DEBIT") String type,
        @NotNull @Positive BigDecimal amount,
        @NotBlank String currency,
        @NotNull Instant eventTimestamp,
        JsonNode metadata) {

    public EventSubmission toSubmission() {
        String metadataJson = (metadata == null || metadata.isNull()) ? null : metadata.toString();
        return new EventSubmission(eventId, accountId, TransactionType.valueOf(type),
                amount, currency, eventTimestamp, metadataJson);
    }
}
