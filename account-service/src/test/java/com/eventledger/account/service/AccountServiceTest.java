package com.eventledger.account.service;

import com.eventledger.account.model.Account;
import com.eventledger.account.model.TransactionType;
import com.eventledger.account.repository.AccountRepository;
import com.eventledger.account.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain tests against real H2 repositories. Test-managed transactions are
 * disabled (NOT_SUPPORTED) so the service's own transaction boundaries and
 * the duplicate-PK race behavior can be exercised for real.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(AccountService.class)
class AccountServiceTest {

    private static final Instant TS = Instant.parse("2026-05-15T14:02:11Z");

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @BeforeEach
    void cleanDatabase() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
    }

    private TransactionCommand credit(String txnId, BigDecimal amount, Instant eventTimestamp) {
        return new TransactionCommand(txnId, TransactionType.CREDIT, amount, "USD", eventTimestamp);
    }

    private TransactionCommand debit(String txnId, BigDecimal amount, Instant eventTimestamp) {
        return new TransactionCommand(txnId, TransactionType.DEBIT, amount, "USD", eventTimestamp);
    }

    @Test
    void creditToUnknownAccountCreatesItWithThatBalance() {
        ApplyResult result = accountService.apply("acct-1", credit("txn-1", new BigDecimal("150.00"), TS));

        assertThat(result.replayed()).isFalse();
        assertThat(result.accountId()).isEqualTo("acct-1");
        assertThat(result.balance()).isEqualByComparingTo("150.00");

        Account stored = accountRepository.findById("acct-1").orElseThrow();
        assertThat(stored.getBalance()).isEqualByComparingTo("150.00");
        assertThat(stored.getCurrency()).isEqualTo("USD");
        assertThat(transactionRepository.findById("txn-1")).isPresent();
    }

    @Test
    void creditAndDebitMixSumsCorrectly() {
        accountService.apply("acct-1", credit("txn-1", new BigDecimal("150.00"), TS));
        accountService.apply("acct-1", debit("txn-2", new BigDecimal("40.25"), TS.plusSeconds(60)));
        accountService.apply("acct-1", credit("txn-3", new BigDecimal("10.00"), TS.plusSeconds(120)));

        Account stored = accountRepository.findById("acct-1").orElseThrow();
        assertThat(stored.getBalance()).isEqualByComparingTo("119.75");
    }

    @Test
    void balanceMayGoNegativeWhenDebitArrivesBeforeItsCredit() {
        ApplyResult result = accountService.apply("acct-1", debit("txn-1", new BigDecimal("50.00"), TS));

        assertThat(result.balance()).isEqualByComparingTo("-50.00");
    }

    @Test
    void duplicateTransactionIdIsReplayedWithoutChangingBalance() {
        ApplyResult first = accountService.apply("acct-1", credit("txn-1", new BigDecimal("150.00"), TS));
        ApplyResult second = accountService.apply("acct-1", credit("txn-1", new BigDecimal("150.00"), TS));

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.balance()).isEqualByComparingTo("150.00");
        assertThat(accountRepository.findById("acct-1").orElseThrow().getBalance())
                .isEqualByComparingTo("150.00");
        assertThat(transactionRepository.count()).isEqualTo(1);
    }

    @Test
    void outOfOrderArrivalYieldsSameBalanceAsInOrder() {
        // Same logical events, applied chronologically to acct-a and shuffled to acct-b
        accountService.apply("acct-a", credit("a-1", new BigDecimal("100.00"), TS));
        accountService.apply("acct-a", debit("a-2", new BigDecimal("30.00"), TS.plusSeconds(60)));
        accountService.apply("acct-a", credit("a-3", new BigDecimal("5.50"), TS.plusSeconds(120)));

        accountService.apply("acct-b", credit("b-3", new BigDecimal("5.50"), TS.plusSeconds(120)));
        accountService.apply("acct-b", debit("b-2", new BigDecimal("30.00"), TS.plusSeconds(60)));
        accountService.apply("acct-b", credit("b-1", new BigDecimal("100.00"), TS));

        BigDecimal balanceA = accountRepository.findById("acct-a").orElseThrow().getBalance();
        BigDecimal balanceB = accountRepository.findById("acct-b").orElseThrow().getBalance();
        assertThat(balanceA).isEqualByComparingTo(balanceB);
        assertThat(balanceA).isEqualByComparingTo("75.50");
    }

    @Test
    void concurrentDuplicateResolvesToReplayAndAppliesExactlyOnce() throws Exception {
        // Pre-existing account so both racers contend on the same rows
        accountService.apply("acct-1", credit("txn-seed", new BigDecimal("10.00"), TS));

        int racers = 2;
        ExecutorService executor = Executors.newFixedThreadPool(racers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<ApplyResult>> futures =
                    java.util.stream.IntStream.range(0, racers)
                            .mapToObj(i -> executor.submit(() -> {
                                start.await();
                                return accountService.apply("acct-1",
                                        credit("txn-race", new BigDecimal("25.00"), TS.plusSeconds(30)));
                            }))
                            .toList();
            start.countDown();

            for (var future : futures) {
                ApplyResult result = future.get(10, TimeUnit.SECONDS);
                assertThat(result.balance()).isEqualByComparingTo("35.00");
            }
        } finally {
            executor.shutdownNow();
        }

        // Applied exactly once regardless of the race
        assertThat(accountRepository.findById("acct-1").orElseThrow().getBalance())
                .isEqualByComparingTo("35.00");
        assertThat(transactionRepository.count()).isEqualTo(2);
    }
}
