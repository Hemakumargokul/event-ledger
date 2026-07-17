package com.eventledger.gateway.queue;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.client.AccountServiceUnavailableException;
import com.eventledger.gateway.model.EventRecord;
import com.eventledger.gateway.model.PendingEvent;
import com.eventledger.gateway.model.TransactionType;
import com.eventledger.gateway.repository.PendingEventRepository;
import com.eventledger.gateway.service.EventSubmission;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Queue drain contract: pending events are re-applied oldest first through
 * the resilient client; success clears the marker; unavailability (or an
 * open circuit) aborts the pass so the next sweep retries; anything else is
 * a poison row that is logged and dropped rather than wedging the queue.
 */
@ExtendWith(MockitoExtension.class)
class QueueProcessorTest {

    private static final Instant TS = Instant.parse("2026-05-15T14:02:11Z");

    @Mock
    private PendingEventRepository pendingEventRepository;

    @Mock
    private AccountServiceClient accountServiceClient;

    @Mock
    private PendingQueue pendingQueue;

    @InjectMocks
    private QueueProcessor queueProcessor;

    private PendingEvent pending(String eventId, Instant queuedAt) {
        EventRecord record = new EventRecord(eventId, "acct-1", TransactionType.CREDIT,
                new BigDecimal("10.00"), "USD", TS, null, queuedAt);
        return new PendingEvent(record, queuedAt);
    }

    @Test
    void sweepAppliesPendingEventsOldestFirstAndClearsEachOnSuccess() {
        PendingEvent older = pending("evt-old", TS);
        PendingEvent newer = pending("evt-new", TS.plusSeconds(60));
        when(pendingEventRepository.findAllByOrderByQueuedAtAsc())
                .thenReturn(List.of(older, newer));

        queueProcessor.sweep();

        InOrder inOrder = inOrder(accountServiceClient, pendingQueue);
        inOrder.verify(accountServiceClient)
                .applyTransaction(argThat(s -> s.eventId().equals("evt-old")));
        inOrder.verify(pendingQueue).markApplied("evt-old");
        inOrder.verify(accountServiceClient)
                .applyTransaction(argThat(s -> s.eventId().equals("evt-new")));
        inOrder.verify(pendingQueue).markApplied("evt-new");
    }

    @Test
    void sweepStopsThePassWhenTheAccountServiceIsStillUnavailable() {
        when(pendingEventRepository.findAllByOrderByQueuedAtAsc())
                .thenReturn(List.of(pending("evt-a", TS), pending("evt-b", TS.plusSeconds(1))));
        doThrow(new AccountServiceUnavailableException("still down", null))
                .when(accountServiceClient).applyTransaction(any(EventSubmission.class));

        queueProcessor.sweep();

        verify(accountServiceClient, times(1)).applyTransaction(any(EventSubmission.class));
        verify(pendingQueue, never()).markApplied(any());
    }

    @Test
    void sweepStopsThePassWhenTheCircuitIsOpen() {
        when(pendingEventRepository.findAllByOrderByQueuedAtAsc())
                .thenReturn(List.of(pending("evt-a", TS), pending("evt-b", TS.plusSeconds(1))));
        doThrow(CallNotPermittedException.createCallNotPermittedException(
                CircuitBreaker.ofDefaults("accountService")))
                .when(accountServiceClient).applyTransaction(any(EventSubmission.class));

        queueProcessor.sweep();

        verify(accountServiceClient, times(1)).applyTransaction(any(EventSubmission.class));
        verify(pendingQueue, never()).markApplied(any());
    }

    @Test
    void sweepDropsAPoisonRowAndContinuesWithTheRest() {
        when(pendingEventRepository.findAllByOrderByQueuedAtAsc())
                .thenReturn(List.of(pending("evt-poison", TS), pending("evt-ok", TS.plusSeconds(1))));
        doThrow(new IllegalStateException("downstream rejected the request"))
                .when(accountServiceClient)
                .applyTransaction(argThat(s -> s.eventId().equals("evt-poison")));

        queueProcessor.sweep();

        verify(pendingQueue).markApplied("evt-poison");
        verify(pendingQueue).markApplied("evt-ok");
    }

    @Test
    void sweepWithEmptyQueueCallsNothingDownstream() {
        when(pendingEventRepository.findAllByOrderByQueuedAtAsc()).thenReturn(List.of());

        queueProcessor.sweep();

        verify(accountServiceClient, never()).applyTransaction(any());
        verify(pendingQueue, never()).markApplied(any());
    }
}
