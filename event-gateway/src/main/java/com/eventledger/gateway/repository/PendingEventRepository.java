package com.eventledger.gateway.repository;

import com.eventledger.gateway.model.PendingEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PendingEventRepository extends JpaRepository<PendingEvent, String> {

    /** Oldest first: drain in arrival order. */
    List<PendingEvent> findAllByOrderByQueuedAtAsc();
}
