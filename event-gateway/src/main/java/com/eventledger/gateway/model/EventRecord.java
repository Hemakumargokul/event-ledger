package com.eventledger.gateway.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The Gateway's locally stored copy of an accepted event. The primary key is
 * the client-supplied event ID, which is what enforces idempotency. This
 * table is the sole data source for all GET /events* reads, so they keep
 * working when the Account Service is down.
 */
@Entity
@Table(name = "events",
        indexes = @Index(name = "idx_events_account_ts", columnList = "account_id, event_timestamp"))
public class EventRecord {

    @Id
    @Column(name = "event_id", length = 64)
    private String eventId;

    @Column(name = "account_id", length = 64, nullable = false)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 6, nullable = false)
    private TransactionType type;

    @Column(name = "amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal amount;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "event_timestamp", nullable = false)
    private Instant eventTimestamp;

    /** Optional metadata, stored as its original JSON string. */
    @Column(name = "metadata", length = 4000)
    private String metadataJson;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected EventRecord() {
        // for JPA
    }

    public EventRecord(String eventId, String accountId, TransactionType type, BigDecimal amount,
                       String currency, Instant eventTimestamp, String metadataJson, Instant receivedAt) {
        this.eventId = eventId;
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.eventTimestamp = eventTimestamp;
        this.metadataJson = metadataJson;
        this.receivedAt = receivedAt;
    }

    public String getEventId() {
        return eventId;
    }

    public String getAccountId() {
        return accountId;
    }

    public TransactionType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getEventTimestamp() {
        return eventTimestamp;
    }

    public String getMetadataJson() {
        return metadataJson;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
