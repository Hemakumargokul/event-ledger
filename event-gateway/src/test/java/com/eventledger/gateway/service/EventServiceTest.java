package com.eventledger.gateway.service;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.client.AccountServiceUnavailableException;
import com.eventledger.gateway.model.EventRecord;
import com.eventledger.gateway.model.TransactionType;
import com.eventledger.gateway.repository.EventRepository;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Write-flow contract per SPEC §7.5: validate → duplicate check → call
 * Account Service → persist → created. Client failures leave no local state;
 * duplicates never reach the Account Service.
 */
@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    private static final Instant TS = Instant.parse("2026-05-15T14:02:11Z");

    @Mock
    private EventRepository eventRepository;

    @Mock
    private AccountServiceClient accountServiceClient;

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

        assertThat(result.duplicate()).isFalse();
        assertThat(result.event().getEventId()).isEqualTo("evt-1");
        verify(accountServiceClient, times(1)).applyTransaction(any(EventSubmission.class));
        verify(eventRepository, times(1)).save(any(EventRecord.class));
    }

    @Test
    void duplicateEventNeverCallsAccountServiceAndReturnsOriginal() {
        EventRecord original = storedRecord();
        when(eventRepository.findById("evt-1")).thenReturn(Optional.of(original));

        SubmissionResult result = eventService.submit(submission());

        assertThat(result.duplicate()).isTrue();
        assertThat(result.event()).isSameAs(original);
        verify(accountServiceClient, never()).applyTransaction(any());
        verify(eventRepository, never()).save(any());
    }

    @Test
    void accountServiceFailurePersistsNothingAndPropagates() {
        when(eventRepository.findById("evt-1")).thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new AccountServiceUnavailableException("down", null))
                .when(accountServiceClient).applyTransaction(any(EventSubmission.class));

        assertThatThrownBy(() -> eventService.submit(submission()))
                .isInstanceOf(AccountServiceUnavailableException.class);

        verify(eventRepository, never()).save(any());
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

        assertThat(result.duplicate()).isTrue();
        assertThat(result.event()).isSameAs(winner);
    }
}
