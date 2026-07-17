package com.eventledger.gateway.dto;

import com.eventledger.gateway.model.EventRecord;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EventResponse(
        String eventId,
        String accountId,
        String type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp,
        JsonNode metadata,
        Instant receivedAt) {

    public static EventResponse from(EventRecord event, ObjectMapper objectMapper) {
        JsonNode metadata = event.getMetadataJson() == null
                ? null
                : objectMapper.readTree(event.getMetadataJson());
        return new EventResponse(event.getEventId(), event.getAccountId(), event.getType().name(),
                event.getAmount(), event.getCurrency(), event.getEventTimestamp(),
                metadata, event.getReceivedAt());
    }
}
