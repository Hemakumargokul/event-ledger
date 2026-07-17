package com.eventledger.gateway.dto;

import java.util.List;

public record EventListResponse(
        String accountId,
        List<EventResponse> events) {
}
