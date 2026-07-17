package com.eventledger.account.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/** Common error contract per SPEC §5.3. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        int status,
        String error,
        String message,
        String traceId,
        Instant timestamp,
        List<FieldError> details) {

    public record FieldError(String field, String message) {
    }
}
