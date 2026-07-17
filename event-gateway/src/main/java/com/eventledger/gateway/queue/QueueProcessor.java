package com.eventledger.gateway.queue;

import com.eventledger.gateway.client.AccountServiceClient;
import com.eventledger.gateway.client.AccountServiceUnavailableException;
import com.eventledger.gateway.model.EventRecord;
import com.eventledger.gateway.model.PendingEvent;
import com.eventledger.gateway.repository.PendingEventRepository;
import com.eventledger.gateway.service.EventSubmission;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains the async fallback queue: re-sends events accepted while the
 * Account Service was down, oldest first, once it recovers. Going through
 * the resilient client means sweep attempts double as circuit-breaker
 * half-open probes, and the Account Service's idempotency on the event ID
 * makes re-sending safe.
 */
@Component
public class QueueProcessor {

    private static final Logger log = LoggerFactory.getLogger(QueueProcessor.class);

    private final PendingEventRepository pendingEventRepository;
    private final AccountServiceClient accountServiceClient;
    private final PendingQueue pendingQueue;

    public QueueProcessor(PendingEventRepository pendingEventRepository,
                          AccountServiceClient accountServiceClient,
                          PendingQueue pendingQueue) {
        this.pendingEventRepository = pendingEventRepository;
        this.accountServiceClient = accountServiceClient;
        this.pendingQueue = pendingQueue;
    }

    @Scheduled(fixedDelayString = "${event-queue.sweep-interval:5s}")
    public void sweep() {
        for (PendingEvent pending : pendingEventRepository.findAllByOrderByQueuedAtAsc()) {
            try {
                accountServiceClient.applyTransaction(toSubmission(pending.getEvent()));
            } catch (AccountServiceUnavailableException | CallNotPermittedException e) {
                // still down (or circuit open): keep the queue intact and
                // let the next sweep retry from the same spot
                log.info("Account Service still unavailable; queued events remain, will retry");
                return;
            } catch (RuntimeException e) {
                // poison row: cannot happen for events validated on intake,
                // but must never wedge the queue behind it
                log.error("Dropping unprocessable queued event {}", pending.getEventId(), e);
                pendingQueue.markApplied(pending.getEventId());
                continue;
            }
            pendingQueue.markApplied(pending.getEventId());
            log.info("Applied queued event {} after recovery", pending.getEventId());
        }
    }

    private static EventSubmission toSubmission(EventRecord event) {
        return new EventSubmission(event.getEventId(), event.getAccountId(), event.getType(),
                event.getAmount(), event.getCurrency(), event.getEventTimestamp(),
                event.getMetadataJson());
    }
}
