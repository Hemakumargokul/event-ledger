package com.eventledger.gateway.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Queue marker for the async fallback (SPEC §8.3 variant): the referenced
 * event was accepted while the Account Service was unavailable and has not
 * been applied downstream yet. A row exists only while unapplied — the
 * QueueProcessor deletes it once the downstream apply succeeds. The event
 * itself lives in the events table like any other accepted event.
 */
@Entity
@Table(name = "pending_events")
public class PendingEvent {

    // Derived from the association via @MapsId at persist time. Left unset
    // in the constructor so Spring Data sees a new entity (persist, not
    // merge) — merging a pre-assigned @MapsId id trips Hibernate's
    // "null identifier" assertion.
    @Id
    @Column(name = "event_id", length = 64)
    private String eventId;

    @OneToOne(optional = false)
    @MapsId
    @JoinColumn(name = "event_id", foreignKey = @ForeignKey(name = "fk_pending_event"))
    private EventRecord event;

    @Column(name = "queued_at", nullable = false)
    private Instant queuedAt;

    protected PendingEvent() {
        // for JPA
    }

    public PendingEvent(EventRecord event, Instant queuedAt) {
        this.event = event;
        this.queuedAt = queuedAt;
    }

    public String getEventId() {
        return event.getEventId();
    }

    public EventRecord getEvent() {
        return event;
    }

    public Instant getQueuedAt() {
        return queuedAt;
    }
}
