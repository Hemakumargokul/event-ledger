package com.eventledger.account.service;

import com.eventledger.account.exception.AccountNotFoundException;
import com.eventledger.account.model.Account;
import com.eventledger.account.model.AccountTransaction;
import com.eventledger.account.repository.AccountRepository;
import com.eventledger.account.repository.TransactionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionTemplate transactionTemplate;

    public AccountService(AccountRepository accountRepository,
                          TransactionRepository transactionRepository,
                          PlatformTransactionManager transactionManager) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        // Programmatic transaction so duplicate-key failures can be handled
        // after rollback, outside the transaction boundary.
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Applies a transaction idempotently. A transaction ID that was already
     * applied — whether detected up front or by losing a concurrent race on
     * the primary key — is replayed: the original outcome is returned and the
     * balance is not changed.
     */
    public ApplyResult apply(String accountId, TransactionCommand command) {
        Optional<ApplyResult> replay = findReplay(command.transactionId());
        if (replay.isPresent()) {
            return replay.get();
        }
        try {
            return transactionTemplate.execute(status -> applyNew(accountId, command));
        } catch (DataIntegrityViolationException | OptimisticLockingFailureException e) {
            // Lost a race with a concurrent duplicate: resolve to the replay
            // outcome. Anything else is a genuine failure and is rethrown.
            return findReplay(command.transactionId()).orElseThrow(() -> e);
        }
    }

    public Account getAccount(String accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    public List<AccountTransaction> recentTransactions(String accountId) {
        return transactionRepository.findTop10ByAccount_AccountIdOrderByEventTimestampDesc(accountId);
    }

    public long transactionCount(String accountId) {
        return transactionRepository.countByAccount_AccountId(accountId);
    }

    public Account getAccount(String accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    public List<AccountTransaction> recentTransactions(String accountId) {
        return transactionRepository.findTop10ByAccount_AccountIdOrderByEventTimestampDesc(accountId);
    }

    public long transactionCount(String accountId) {
        return transactionRepository.countByAccount_AccountId(accountId);
    }

    public Account getAccount(String accountId) {
        return accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    public List<AccountTransaction> recentTransactions(String accountId) {
        return transactionRepository.findTop10ByAccount_AccountIdOrderByEventTimestampDesc(accountId);
    }

    public long transactionCount(String accountId) {
        return transactionRepository.countByAccount_AccountId(accountId);
    }

    private Optional<ApplyResult> findReplay(String transactionId) {
        return transactionRepository.findById(transactionId).map(txn -> {
            Account account = accountRepository.findById(txn.getAccountId()).orElseThrow();
            return new ApplyResult(true, txn.getTransactionId(), account.getAccountId(),
                    account.getBalance(), account.getCurrency());
        });
    }

    private ApplyResult applyNew(String accountId, TransactionCommand command) {
        Account account = accountRepository.findById(accountId)
                .orElseGet(() -> new Account(accountId, command.currency()));
        account.apply(command.type(), command.amount());
        accountRepository.save(account);
        transactionRepository.save(new AccountTransaction(
                command.transactionId(), account, command.type(), command.amount(),
                command.currency(), command.eventTimestamp(), Instant.now()));
        return new ApplyResult(false, command.transactionId(), accountId,
                account.getBalance(), account.getCurrency());
    }
}
