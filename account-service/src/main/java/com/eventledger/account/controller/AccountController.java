package com.eventledger.account.controller;

import com.eventledger.account.dto.AccountDetailsResponse;
import com.eventledger.account.dto.BalanceResponse;
import com.eventledger.account.dto.TransactionRequest;
import com.eventledger.account.dto.TransactionResponse;
import com.eventledger.account.model.Account;
import com.eventledger.account.service.AccountService;
import com.eventledger.account.service.ApplyResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping("/{accountId}/transactions")
    public ResponseEntity<TransactionResponse> applyTransaction(@PathVariable String accountId,
                                                                @Valid @RequestBody TransactionRequest request) {
        ApplyResult result = accountService.apply(accountId, request.toCommand());
        HttpStatus status = result.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status)
                .body(new TransactionResponse(result.transactionId(), result.accountId(), result.balance()));
    }

    @GetMapping("/{accountId}/balance")
    public BalanceResponse balance(@PathVariable String accountId) {
        Account account = accountService.getAccount(accountId);
        return new BalanceResponse(account.getAccountId(), account.getBalance(), account.getCurrency());
    }

    @GetMapping("/{accountId}")
    public AccountDetailsResponse details(@PathVariable String accountId) {
        Account account = accountService.getAccount(accountId);
        var recent = accountService.recentTransactions(accountId).stream()
                .map(AccountDetailsResponse.TransactionSummary::from)
                .toList();
        return new AccountDetailsResponse(account.getAccountId(), account.getBalance(),
                account.getCurrency(), accountService.transactionCount(accountId), recent);
    }
}
