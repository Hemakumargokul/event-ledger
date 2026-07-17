package com.eventledger.gateway.controller;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.client.AccountServiceUnavailableException;
import com.eventledger.gateway.client.BalanceSnapshot;
import com.eventledger.gateway.config.LedgerMetrics;
import com.eventledger.gateway.dto.EventListResponse;
import com.eventledger.gateway.dto.EventRequest;
import com.eventledger.gateway.dto.EventResponse;
import com.eventledger.gateway.exception.EventNotFoundException;
import com.eventledger.gateway.service.EventService;
import com.eventledger.gateway.service.SubmissionResult;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
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

/**
 * All public API endpoints share one inbound rate limit (429 when exceeded,
 * mapped by ApiExceptionHandler). Health and actuator endpoints live outside
 * this controller and stay unlimited so probes keep working under load.
 */
@RestController
@RateLimiter(name = EventController.GATEWAY_API)
public class EventController {

    static final String GATEWAY_API = "gatewayApi";

    private final EventService eventService;
    private final AccountServiceClient accountServiceClient;
    private final ObjectMapper objectMapper;
    private final LedgerMetrics metrics;

    public EventController(EventService eventService, AccountServiceClient accountServiceClient,
                           ObjectMapper objectMapper, LedgerMetrics metrics) {
        this.eventService = eventService;
        this.accountServiceClient = accountServiceClient;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    @PostMapping("/events")
    public ResponseEntity<EventResponse> submit(@Valid @RequestBody EventRequest request) {
        SubmissionResult result;
        try {
            result = eventService.submit(request.toSubmission());
        } catch (AccountServiceUnavailableException | CallNotPermittedException e) {
            metrics.eventSubmitted(request.type(), "unavailable");
            throw e;
        }
        metrics.eventSubmitted(request.type(), result.duplicate() ? "duplicate" : "created");
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
