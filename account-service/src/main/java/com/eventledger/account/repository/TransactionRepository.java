package com.eventledger.account.repository;

import com.eventledger.account.model.AccountTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransactionRepository extends JpaRepository<AccountTransaction, String> {

    List<AccountTransaction> findTop10ByAccount_AccountIdOrderByEventTimestampDesc(String accountId);

    long countByAccount_AccountId(String accountId);
}
