package com.eventledger.account.repository;

import com.eventledger.account.model.AccountTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<AccountTransaction, String> {
}
