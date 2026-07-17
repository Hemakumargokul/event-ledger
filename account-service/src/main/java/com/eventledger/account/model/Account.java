package com.eventledger.account.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @Column(name = "account_id", length = 64)
    private String accountId;

    @Column(name = "balance", precision = 19, scale = 4, nullable = false)
    private BigDecimal balance;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    /** Optimistic lock so concurrent transactions on one account cannot lose updates. */
    @Version
    @Column(name = "version")
    private Long version;

    protected Account() {
        // for JPA
    }

    public Account(String accountId, String currency) {
        this.accountId = accountId;
        this.currency = currency;
        this.balance = BigDecimal.ZERO;
    }

    public void apply(TransactionType type, BigDecimal amount) {
        this.balance = switch (type) {
            case CREDIT -> this.balance.add(amount);
            case DEBIT -> this.balance.subtract(amount);
        };
    }

    public String getAccountId() {
        return accountId;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public String getCurrency() {
        return currency;
    }
}
