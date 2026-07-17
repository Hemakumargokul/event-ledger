package com.eventledger.gateway.service;

import com.eventledger.gateway.model.EventRecord;

/**
 * Outcome of an event submission. {@code duplicate} is true when the event ID
 * was already stored; {@code event} is then the original record.
 */
public record SubmissionResult(boolean duplicate, EventRecord event) {

    public static SubmissionResult created(EventRecord event) {
        return new SubmissionResult(false, event);
    }

    public static SubmissionResult duplicate(EventRecord event) {
        return new SubmissionResult(true, event);
    }
}
