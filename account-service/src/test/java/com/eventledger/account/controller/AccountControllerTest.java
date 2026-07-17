package com.eventledger.account.controller;

import com.eventledger.account.exception.AccountNotFoundException;
import com.eventledger.account.model.Account;
import com.eventledger.account.model.AccountTransaction;
import com.eventledger.account.model.TransactionType;
import com.eventledger.account.service.AccountService;
import com.eventledger.account.service.ApplyResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API contract tests per SPEC §5.2 / §5.3: status codes, body shapes,
 * validation failures with field details, and 404 handling.
 */
@WebMvcTest(AccountController.class)
class AccountControllerTest {

    private static final String TS = "2026-05-15T14:02:11Z";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountService accountService;

    private String requestBody(String transactionId, String type, String amount) {
        return """
                {
                  "transactionId": "%s",
                  "type": "%s",
                  "amount": %s,
                  "currency": "USD",
                  "eventTimestamp": "%s"
                }
                """.formatted(transactionId, type, amount, TS);
    }

    @Test
    void applyingNewTransactionReturns201WithBalance() throws Exception {
        when(accountService.apply(eq("acct-1"), any())).thenReturn(
                new ApplyResult(false, "txn-1", "acct-1", new BigDecimal("150.00"), "USD"));

        mockMvc.perform(post("/accounts/acct-1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("txn-1", "CREDIT", "150.00")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionId").value("txn-1"))
                .andExpect(jsonPath("$.accountId").value("acct-1"))
                .andExpect(jsonPath("$.balance").value(150.00));
    }

    @Test
    void replayedTransactionReturns200WithUnchangedBalance() throws Exception {
        when(accountService.apply(eq("acct-1"), any())).thenReturn(
                new ApplyResult(true, "txn-1", "acct-1", new BigDecimal("150.00"), "USD"));

        mockMvc.perform(post("/accounts/acct-1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("txn-1", "CREDIT", "150.00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value("txn-1"))
                .andExpect(jsonPath("$.balance").value(150.00));
    }

    @Test
    void missingTransactionIdReturns400WithFieldDetail() throws Exception {
        mockMvc.perform(post("/accounts/acct-1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "CREDIT", "amount": 10.00, "currency": "USD", "eventTimestamp": "%s"}
                                """.formatted(TS)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.details[0].field").value("transactionId"));

        verify(accountService, never()).apply(any(), any());
    }

    @Test
    void unknownTypeReturns400WithFieldDetail() throws Exception {
        mockMvc.perform(post("/accounts/acct-1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("txn-1", "TRANSFER", "10.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("type"));
    }

    @Test
    void zeroAmountReturns400WithFieldDetail() throws Exception {
        mockMvc.perform(post("/accounts/acct-1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("txn-1", "CREDIT", "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("amount"));
    }

    @Test
    void negativeAmountReturns400WithFieldDetail() throws Exception {
        mockMvc.perform(post("/accounts/acct-1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody("txn-1", "DEBIT", "-5.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].field").value("amount"));
    }

    @Test
    void malformedTimestampReturns400() throws Exception {
        mockMvc.perform(post("/accounts/acct-1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transactionId": "txn-1", "type": "CREDIT", "amount": 10.00,
                                 "currency": "USD", "eventTimestamp": "not-a-timestamp"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void balanceReturns200ForKnownAccount() throws Exception {
        Account account = new Account("acct-1", "USD");
        account.apply(TransactionType.CREDIT, new BigDecimal("150.00"));
        when(accountService.getAccount("acct-1")).thenReturn(account);

        mockMvc.perform(get("/accounts/acct-1/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("acct-1"))
                .andExpect(jsonPath("$.balance").value(150.00))
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    void balanceReturns404ForUnknownAccount() throws Exception {
        when(accountService.getAccount("acct-x")).thenThrow(new AccountNotFoundException("acct-x"));

        mockMvc.perform(get("/accounts/acct-x/balance"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Account not found: acct-x"));
    }

    @Test
    void accountDetailsReturns200WithRecentTransactions() throws Exception {
        Account account = new Account("acct-1", "USD");
        account.apply(TransactionType.CREDIT, new BigDecimal("150.00"));
        AccountTransaction txn = new AccountTransaction("txn-1", account, TransactionType.CREDIT,
                new BigDecimal("150.00"), "USD", Instant.parse(TS), Instant.parse(TS));
        when(accountService.getAccount("acct-1")).thenReturn(account);
        when(accountService.recentTransactions("acct-1")).thenReturn(List.of(txn));
        when(accountService.transactionCount("acct-1")).thenReturn(1L);

        mockMvc.perform(get("/accounts/acct-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("acct-1"))
                .andExpect(jsonPath("$.balance").value(150.00))
                .andExpect(jsonPath("$.transactionCount").value(1))
                .andExpect(jsonPath("$.recentTransactions[0].transactionId").value("txn-1"))
                .andExpect(jsonPath("$.recentTransactions[0].type").value("CREDIT"));
    }

    @Test
    void accountDetailsReturns404ForUnknownAccount() throws Exception {
        when(accountService.getAccount("acct-x")).thenThrow(new AccountNotFoundException("acct-x"));

        mockMvc.perform(get("/accounts/acct-x"))
                .andExpect(status().isNotFound());
    }
}
