package com.eventledger.gateway.controller;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.client.BalanceSnapshot;
import com.eventledger.gateway.dto.EventListResponse;
import com.eventledger.gateway.dto.EventRequest;
import com.eventledger.gateway.dto.EventResponse;
import com.eventledger.gateway.exception.EventNotFoundException;
import com.eventledger.gateway.service.EventService;
import com.eventledger.gateway.service.SubmissionResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@RestController
public class EventController {

    private final EventService eventService;
    private final AccountServiceClient accountServiceClient;
    private final ObjectMapper objectMapper;

    public EventController(EventService eventService, AccountServiceClient accountServiceClient,
                           ObjectMapper objectMapper) {
        this.eventService = eventService;
        this.accountServiceClient = accountServiceClient;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/events")
    public ResponseEntity<EventResponse> submit(@Valid @RequestBody EventRequest request) {
        SubmissionResult result = eventService.submit(request.toSubmission());
        HttpStatus status = result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(EventResponse.from(result.event(), objectMapper));
    }

    @GetMapping("/events/{eventId}")
    public EventResponse getEvent(@PathVariable String eventId) {
        return eventService.findEvent(eventId)
                .map(event -> EventResponse.from(event, objectMapper))
                .orElseThrow(() -> new EventNotFoundException(eventId));
    }

    @GetMapping("/events")
    public EventListResponse listEvents(@RequestParam("account") String accountId) {
        List<EventResponse> events = eventService.listEvents(accountId).stream()
                .map(event -> EventResponse.from(event, objectMapper))
                .toList();
        return new EventListResponse(accountId, events);
    }

    /** Balance proxy: the Gateway holds no balances, so this is a passthrough. */
    @GetMapping("/accounts/{accountId}/balance")
    public BalanceSnapshot getBalance(@PathVariable String accountId) {
        return accountServiceClient.getBalance(accountId);
    }
}
