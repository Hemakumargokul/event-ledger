package com.eventledger.gateway.service;

import com.eventledger.gateway.model.EventRecord;

/**
 * Outcome of an event submission. CREATED means applied downstream and
 * stored; DUPLICATE means the event ID was already stored ({@code event} is
 * the original record); QUEUED means the Account Service was unavailable, so
 * the event was stored locally with a pending marker for the QueueProcessor
 * to apply after recovery.
 */
public record SubmissionResult(Outcome outcome, EventRecord event) {

    public enum Outcome { CREATED, DUPLICATE, QUEUED }

    public static SubmissionResult created(EventRecord event) {
        return new SubmissionResult(Outcome.CREATED, event);
    }

    public static SubmissionResult duplicate(EventRecord event) {
        return new SubmissionResult(Outcome.DUPLICATE, event);
    }

    public static SubmissionResult queued(EventRecord event) {
        return new SubmissionResult(Outcome.QUEUED, event);
    }
}
