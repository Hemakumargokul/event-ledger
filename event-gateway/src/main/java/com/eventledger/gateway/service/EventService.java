package com.eventledger.gateway.service;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.client.AccountServiceUnavailableException;
import com.eventledger.gateway.model.EventRecord;
import com.eventledger.gateway.queue.PendingQueue;
import com.eventledger.gateway.repository.EventRepository;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class EventService {

    private static final Logger log = LoggerFactory.getLogger(EventService.class);

    private final EventRepository eventRepository;
    private final AccountServiceClient accountServiceClient;
    private final PendingQueue pendingQueue;

    public EventService(EventRepository eventRepository, AccountServiceClient accountServiceClient,
                        PendingQueue pendingQueue) {
        this.eventRepository = eventRepository;
        this.accountServiceClient = accountServiceClient;
        this.pendingQueue = pendingQueue;
    }

    /**
     * SPEC §7.5 write flow: duplicate check → apply downstream → persist.
     * When the Account Service is unavailable (retries exhausted or circuit
     * open), the event is not rejected: it is stored locally together with a
     * queued marker (async fallback, deviation from SPEC §8.3) and the
     * QueueProcessor replays it after recovery. Any other client failure
     * (e.g. a 4xx — a Gateway bug, not unavailability) still leaves no local
     * record, so the client can safely retry the same event. The Account
     * Service is idempotent on the event ID, so a retry whose first attempt
     * actually committed downstream cannot double-apply.
     */
    public SubmissionResult submit(EventSubmission submission) {
        Optional<EventRecord> existing = eventRepository.findById(submission.eventId());
        if (existing.isPresent()) {
            log.info("Duplicate event {} replayed for account {}",
                    submission.eventId(), submission.accountId());
            return SubmissionResult.duplicate(existing.get());
        }

        EventRecord record = new EventRecord(submission.eventId(), submission.accountId(),
                submission.type(), submission.amount(), submission.currency(),
                submission.eventTimestamp(), submission.metadataJson(), Instant.now());

        try {
            accountServiceClient.applyTransaction(submission);
        } catch (AccountServiceUnavailableException | CallNotPermittedException e) {
            EventRecord queued = pendingQueue.queueRejected(record, Instant.now());
            log.warn("Account Service unavailable; queued event {} for account {}",
                    submission.eventId(), submission.accountId());
            return SubmissionResult.queued(queued);
        }

        try {
            SubmissionResult result = SubmissionResult.created(eventRepository.save(record));
            log.info("Accepted event {} for account {}",
                    submission.eventId(), submission.accountId());
            return result;
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
