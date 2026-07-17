package com.eventledger.gateway.service;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.client.AccountServiceUnavailableException;
import com.eventledger.gateway.model.EventRecord;
import com.eventledger.gateway.model.TransactionType;
import com.eventledger.gateway.queue.PendingQueue;
import com.eventledger.gateway.repository.EventRepository;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Write-flow contract per SPEC §7.5 with the async fallback variant of §8.3:
 * validate → duplicate check → call Account Service → persist → created.
 * Unavailability (client exhausted / circuit open) queues the event locally
 * instead of failing; any other client failure leaves no local state.
 * Duplicates never reach the Account Service.
 */
@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    private static final Instant TS = Instant.parse("2026-05-15T14:02:11Z");

    @Mock
    private EventRepository eventRepository;

    @Mock
    private AccountServiceClient accountServiceClient;

    @Mock
    private PendingQueue pendingQueue;

    @InjectMocks
    private EventService eventService;

    private EventSubmission submission() {
        return new EventSubmission("evt-1", "acct-1", TransactionType.CREDIT,
                new BigDecimal("150.00"), "USD", TS, null);
    }

    private EventRecord storedRecord() {
        return new EventRecord("evt-1", "acct-1", TransactionType.CREDIT,
                new BigDecimal("150.00"), "USD", TS, null, Instant.now());
    }

    @Test
    void newEventCallsAccountServiceOncePersistsAndReturnsCreated() {
        when(eventRepository.findById("evt-1")).thenReturn(Optional.empty());
        when(eventRepository.save(any(EventRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        SubmissionResult result = eventService.submit(submission());

        assertThat(result.outcome()).isEqualTo(SubmissionResult.Outcome.CREATED);
        assertThat(result.event().getEventId()).isEqualTo("evt-1");
        verify(accountServiceClient, times(1)).applyTransaction(any(EventSubmission.class));
        verify(eventRepository, times(1)).save(any(EventRecord.class));
    }

    @Test
    void duplicateEventNeverCallsAccountServiceAndReturnsOriginal() {
        EventRecord original = storedRecord();
        when(eventRepository.findById("evt-1")).thenReturn(Optional.of(original));

        SubmissionResult result = eventService.submit(submission());

        assertThat(result.outcome()).isEqualTo(SubmissionResult.Outcome.DUPLICATE);
        assertThat(result.event()).isSameAs(original);
        verify(accountServiceClient, never()).applyTransaction(any());
        verify(eventRepository, never()).save(any());
    }

    @Test
    void accountServiceUnavailableQueuesTheEventAndReturnsQueued() {
        when(eventRepository.findById("evt-1")).thenReturn(Optional.empty());
        doThrow(new AccountServiceUnavailableException("down", null))
                .when(accountServiceClient).applyTransaction(any(EventSubmission.class));
        when(pendingQueue.queueRejected(any(EventRecord.class), any(Instant.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SubmissionResult result = eventService.submit(submission());

        assertThat(result.outcome()).isEqualTo(SubmissionResult.Outcome.QUEUED);
        assertThat(result.event().getEventId()).isEqualTo("evt-1");
        verify(pendingQueue).queueRejected(any(EventRecord.class), any(Instant.class));
        // the queue store persists atomically; the service must not save separately
        verify(eventRepository, never()).save(any());
    }

    @Test
    void openCircuitQueuesTheEventAndReturnsQueued() {
        when(eventRepository.findById("evt-1")).thenReturn(Optional.empty());
        doThrow(CallNotPermittedException.createCallNotPermittedException(
                CircuitBreaker.ofDefaults("accountService")))
                .when(accountServiceClient).applyTransaction(any(EventSubmission.class));
        when(pendingQueue.queueRejected(any(EventRecord.class), any(Instant.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SubmissionResult result = eventService.submit(submission());

        assertThat(result.outcome()).isEqualTo(SubmissionResult.Outcome.QUEUED);
        verify(pendingQueue).queueRejected(any(EventRecord.class), any(Instant.class));
        verify(eventRepository, never()).save(any());
    }

    @Test
    void nonUnavailabilityFailurePersistsNothingQueuesNothingAndPropagates() {
        when(eventRepository.findById("evt-1")).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("downstream rejected the request"))
                .when(accountServiceClient).applyTransaction(any(EventSubmission.class));

        assertThatThrownBy(() -> eventService.submit(submission()))
                .isInstanceOf(IllegalStateException.class);

        verify(eventRepository, never()).save(any());
        verify(pendingQueue, never()).queueRejected(any(), any());
    }

    @Test
    void concurrentDuplicateLosingInsertResolvesToDuplicateResult() {
        EventRecord winner = storedRecord();
        when(eventRepository.findById("evt-1"))
                .thenReturn(Optional.empty())      // pre-check misses
                .thenReturn(Optional.of(winner));  // re-read after PK violation
        when(eventRepository.save(any(EventRecord.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        SubmissionResult result = eventService.submit(submission());

        assertThat(result.outcome()).isEqualTo(SubmissionResult.Outcome.DUPLICATE);
        assertThat(result.event()).isSameAs(winner);
    }
}
