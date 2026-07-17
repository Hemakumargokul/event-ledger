package com.eventledger.gateway.service;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.model.EventRecord;
import com.eventledger.gateway.repository.EventRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class EventService {

    private final EventRepository eventRepository;
    private final AccountServiceClient accountServiceClient;

    public EventService(EventRepository eventRepository, AccountServiceClient accountServiceClient) {
        this.eventRepository = eventRepository;
        this.accountServiceClient = accountServiceClient;
    }

    /**
     * SPEC §7.5 write flow: duplicate check → apply downstream → persist.
     * Persisting only after the downstream call succeeds means a failed call
     * leaves no local record, so the client can safely retry the same event.
     * The Account Service is idempotent on the event ID, so a retry whose
     * first attempt actually committed downstream cannot double-apply.
     */
    public SubmissionResult submit(EventSubmission submission) {
        Optional<EventRecord> existing = eventRepository.findById(submission.eventId());
        if (existing.isPresent()) {
            return SubmissionResult.duplicate(existing.get());
        }

        accountServiceClient.applyTransaction(submission);

        EventRecord record = new EventRecord(submission.eventId(), submission.accountId(),
                submission.type(), submission.amount(), submission.currency(),
                submission.eventTimestamp(), submission.metadataJson(), Instant.now());
        try {
            return SubmissionResult.created(eventRepository.save(record));
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent duplicate submission; the
            // downstream apply was idempotent, so return the winner's record.
            return eventRepository.findById(submission.eventId())
                    .map(SubmissionResult::duplicate)
                    .orElseThrow(() -> e);
        }
    }

    public Optional<EventRecord> findEvent(String eventId) {
        return eventRepository.findById(eventId);
    }

    public List<EventRecord> listEvents(String accountId) {
        return eventRepository.findByAccountIdOrderByEventTimestampAscReceivedAtAsc(accountId);
    }
}
