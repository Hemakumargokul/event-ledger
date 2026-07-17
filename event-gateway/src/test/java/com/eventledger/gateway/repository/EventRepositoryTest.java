package com.eventledger.gateway.repository;

import com.eventledger.gateway.model.EventRecord;
import com.eventledger.gateway.model.TransactionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class EventRepositoryTest {

    private static final Instant TS = Instant.parse("2026-05-15T14:00:00Z");

    @Autowired
    private EventRepository eventRepository;

    private EventRecord event(String eventId, Instant eventTimestamp, Instant receivedAt) {
        return new EventRecord(eventId, "acct-1", TransactionType.CREDIT,
                new BigDecimal("10.00"), "USD", eventTimestamp, null, receivedAt);
    }

    @Test
    void listingIsChronologicalByEventTimestampRegardlessOfInsertionOrder() {
        // Inserted out of order on purpose
        eventRepository.save(event("evt-3", TS.plusSeconds(180), TS.plusSeconds(200)));
        eventRepository.save(event("evt-1", TS.plusSeconds(60), TS.plusSeconds(400)));
        eventRepository.save(event("evt-2", TS.plusSeconds(120), TS.plusSeconds(300)));

        List<EventRecord> events =
                eventRepository.findByAccountIdOrderByEventTimestampAscReceivedAtAsc("acct-1");

        assertThat(events).extracting(EventRecord::getEventId)
                .containsExactly("evt-1", "evt-2", "evt-3");
    }

    @Test
    void equalEventTimestampsBreakTiesByReceivedAt() {
        eventRepository.save(event("evt-b", TS, TS.plusSeconds(20)));
        eventRepository.save(event("evt-a", TS, TS.plusSeconds(10)));

        List<EventRecord> events =
                eventRepository.findByAccountIdOrderByEventTimestampAscReceivedAtAsc("acct-1");

        assertThat(events).extracting(EventRecord::getEventId)
                .containsExactly("evt-a", "evt-b");
    }

    @Test
    void listingForUnknownAccountIsEmpty() {
        assertThat(eventRepository.findByAccountIdOrderByEventTimestampAscReceivedAtAsc("acct-x"))
                .isEmpty();
    }
}
