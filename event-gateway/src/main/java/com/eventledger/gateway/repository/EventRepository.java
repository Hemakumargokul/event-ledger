package com.eventledger.gateway.repository;

import com.eventledger.gateway.model.EventRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventRepository extends JpaRepository<EventRecord, String> {

    List<EventRecord> findByAccountIdOrderByEventTimestampAscReceivedAtAsc(String accountId);
}
