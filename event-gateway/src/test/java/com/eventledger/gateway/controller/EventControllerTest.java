package com.eventledger.gateway.controller;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.client.AccountServiceUnavailableException;
import com.eventledger.gateway.client.BalanceSnapshot;
import com.eventledger.gateway.config.LedgerMetrics;
import com.eventledger.gateway.exception.AccountNotFoundException;
import com.eventledger.gateway.model.EventRecord;
import com.eventledger.gateway.model.TransactionType;
import com.eventledger.gateway.service.EventService;
import com.eventledger.gateway.service.SubmissionResult;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Public API contract per SPEC §5.1 / §5.3: status codes, body shapes,
 * validation with field details, 404s, 503 degradation on the balance proxy,
 * and traceId in every error body.
 */
@WebMvcTest(EventController.class)
@Import(EventControllerTest.TracingTestConfig.class)
class EventControllerTest {

    private static final String TS = "2026-05-15T14:02:11Z";

    @TestConfiguration
    static class TracingTestConfig {
        @Bean
        Tracer tracer() {
            Tracer tracer = mock(Tracer.class, RETURNS_DEEP_STUBS);
            when(tracer.currentSpan().context().traceId()).thenReturn("test-trace-id");
            return tracer;
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EventService eventService;

    @MockitoBean
    private AccountServiceClient accountServiceClient;

    @MockitoBean
    private LedgerMetrics ledgerMetrics;

    private EventRecord storedEvent() {
        return new EventRecord("evt-1", "acct-1", TransactionType.CREDIT,
                new BigDecimal("150.00"), "USD", Instant.parse(TS),
                "{\"source\":\"mainframe-batch\",\"batchId\":\"B-9042\"}",
                Instant.parse("2026-07-16T20:00:00Z"));
    }

    private String fullBody() {
        return """
                {
                  "eventId": "evt-1",
                  "accountId": "acct-1",
                  "type": "CREDIT",
                  "amount": 150.00,
                  "currency": "USD",
                  "eventTimestamp": "%s",
                  "metadata": {"source": "mainframe-batch", "batchId": "B-9042"}
                }
                """.formatted(TS);
    }

    // --- POST /events ---

    @Test
    void newEventReturns201WithStoredEventIncludingMetadata() throws Exception {
        when(eventService.submit(any())).thenReturn(SubmissionResult.created(storedEvent()));

        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(fullBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value("evt-1"))
                .andExpect(jsonPath("$.accountId").value("acct-1"))
                .andExpect(jsonPath("$.type").value("CREDIT"))
                .andExpect(jsonPath("$.amount").value(150.00))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.eventTimestamp").value(TS))
                .andExpect(jsonPath("$.metadata.source").value("mainframe-batch"))
                .andExpect(jsonPath("$.receivedAt").exists());
    }

    @Test
    void duplicateEventReturns200WithOriginal() throws Exception {
        when(eventService.submit(any())).thenReturn(SubmissionResult.duplicate(storedEvent()));

        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(fullBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-1"));
    }

    @Test
    void accountServiceDownReturns503WithErrorContract() throws Exception {
        when(eventService.submit(any()))
                .thenThrow(new AccountServiceUnavailableException("Account Service is unreachable", null));

        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(fullBody()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.error").value("Service Unavailable"))
                .andExpect(jsonPath("$.message").value("Account Service is unreachable"))
                .andExpect(jsonPath("$.traceId").value("test-trace-id"));
    }

    @Test
    void missingRequiredFieldsReturn400WithEachFieldListed() throws Exception {
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.traceId").value("test-trace-id"))
                .andExpect(jsonPath("$.details[?(@.field == 'eventId')]").exists())
                .andExpect(jsonPath("$.details[?(@.field == 'accountId')]").exists())
                .andExpect(jsonPath("$.details[?(@.field == 'type')]").exists())
                .andExpect(jsonPath("$.details[?(@.field == 'amount')]").exists())
                .andExpect(jsonPath("$.details[?(@.field == 'currency')]").exists())
                .andExpect(jsonPath("$.details[?(@.field == 'eventTimestamp')]").exists());

        verify(eventService, never()).submit(any());
    }

    @Test
    void unknownTypeReturns400() throws Exception {
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                        .content(fullBody().replace("CREDIT", "TRANSFER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("type"));
    }

    @Test
    void zeroAmountReturns400() throws Exception {
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                        .content(fullBody().replace("150.00", "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("amount"));
    }

    @Test
    void negativeAmountReturns400() throws Exception {
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                        .content(fullBody().replace("150.00", "-1.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("amount"));
    }

    @Test
    void malformedTimestampReturns400() throws Exception {
        mockMvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
                        .content(fullBody().replace(TS, "yesterday")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    // --- GET /events/{id} ---

    @Test
    void getEventReturns200WhenFound() throws Exception {
        when(eventService.findEvent("evt-1")).thenReturn(Optional.of(storedEvent()));

        mockMvc.perform(get("/events/evt-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-1"))
                .andExpect(jsonPath("$.metadata.batchId").value("B-9042"));
    }

    @Test
    void getEventReturns404WithErrorContractWhenUnknown() throws Exception {
        when(eventService.findEvent("evt-x")).thenReturn(Optional.empty());

        mockMvc.perform(get("/events/evt-x"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Event not found: evt-x"))
                .andExpect(jsonPath("$.traceId").value("test-trace-id"));
    }

    // --- GET /events?account= ---

    @Test
    void listEventsReturnsEventsForAccount() throws Exception {
        when(eventService.listEvents("acct-1")).thenReturn(List.of(storedEvent()));

        mockMvc.perform(get("/events").param("account", "acct-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("acct-1"))
                .andExpect(jsonPath("$.events[0].eventId").value("evt-1"));
    }

    @Test
    void listEventsWithoutAccountParamReturns400() throws Exception {
        mockMvc.perform(get("/events"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    // --- GET /accounts/{id}/balance (proxy) ---

    @Test
    void balanceProxyPassesThroughDownstreamResponse() throws Exception {
        when(accountServiceClient.getBalance("acct-1"))
                .thenReturn(new BalanceSnapshot("acct-1", new BigDecimal("150.00"), "USD"));

        mockMvc.perform(get("/accounts/acct-1/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("acct-1"))
                .andExpect(jsonPath("$.balance").value(150.00))
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    void balanceProxyReturns404ForUnknownAccount() throws Exception {
        when(accountServiceClient.getBalance("acct-x"))
                .thenThrow(new AccountNotFoundException("acct-x"));

        mockMvc.perform(get("/accounts/acct-x/balance"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Account not found: acct-x"));
    }

    @Test
    void balanceProxyReturns503WhenAccountServiceUnreachable() throws Exception {
        when(accountServiceClient.getBalance("acct-1"))
                .thenThrow(new AccountServiceUnavailableException("Account Service is unreachable", null));

        mockMvc.perform(get("/accounts/acct-1/balance"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.message").value("Account Service is unreachable"))
                .andExpect(jsonPath("$.traceId").value("test-trace-id"));
    }
}
