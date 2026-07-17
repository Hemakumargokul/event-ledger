package com.eventledger.account.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A transaction applied to an account. The primary key is the upstream
 * event ID, which is what makes replays (Gateway retries, duplicate
 * deliveries) idempotent at this service's boundary.
 */
@Entity
@Table(name = "transactions", indexes = @Index(name = "idx_transactions_account", columnList = "account_id"))
public class AccountTransaction {

    @Id
    @Column(name = "transaction_id", length = 64)
    private String transactionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_transactions_account"))
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 6, nullable = false)
    private TransactionType type;

    @Column(name = "amount", precision = 19, scale = 4, nullable = false)
    private BigDecimal amount;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "event_timestamp", nullable = false)
    private Instant eventTimestamp;

    @Column(name = "applied_at", nullable = false)
    private Instant appliedAt;

    protected AccountTransaction() {
        // for JPA
    }

    public AccountTransaction(String transactionId, Account account, TransactionType type,
                              BigDecimal amount, String currency, Instant eventTimestamp, Instant appliedAt) {
        this.transactionId = transactionId;
        this.account = account;
        this.type = type;
        this.amount = amount;
        this.currency = currency;
        this.eventTimestamp = eventTimestamp;
        this.appliedAt = appliedAt;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getAccountId() {
        return account.getAccountId();
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

    public Instant getAppliedAt() {
        return appliedAt;
    }
}
