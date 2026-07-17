package com.eventledger.gateway.queue;

import com.eventledger.gateway.model.EventRecord;
import com.eventledger.gateway.model.PendingEvent;
import com.eventledger.gateway.repository.EventRepository;
import com.eventledger.gateway.repository.PendingEventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Persistence operations of the async fallback queue: accept an event whose
 * downstream apply was rejected by an unavailable Account Service, and clear
 * the marker once the QueueProcessor has applied it.
 *
 * A separate component (not methods on EventService) so the @Transactional
 * boundary is a real proxy call, never self-invocation.
 */
@Component
public class PendingQueue {

    private final EventRepository eventRepository;
    private final PendingEventRepository pendingEventRepository;

    public PendingQueue(EventRepository eventRepository, PendingEventRepository pendingEventRepository) {
        this.eventRepository = eventRepository;
        this.pendingEventRepository = pendingEventRepository;
    }

    /** Atomically stores the event and its queued marker. */
    @Transactional
    public EventRecord queueRejected(EventRecord record, Instant queuedAt) {
        EventRecord saved = eventRepository.save(record);
        pendingEventRepository.save(new PendingEvent(saved, queuedAt));
        return saved;
    }

    /** The event reached the Account Service; it no longer needs replaying. */
    @Transactional
    public void markApplied(String eventId) {
        pendingEventRepository.deleteById(eventId);
    }
}
