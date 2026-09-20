package com.n2bank.bootstrap;

import com.n2bank.application.port.AccountRepository;
import com.n2bank.application.service.PostingService;
import com.n2bank.domain.model.Account;
import com.n2bank.domain.model.AccountType;
import com.n2bank.domain.model.Direction;
import com.n2bank.domain.model.IdempotencyKey;
import com.n2bank.domain.model.JournalEntry;
import com.n2bank.domain.model.Money;
import com.n2bank.domain.model.Posting;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class BankApplication extends DbApplication {

  @Override
  protected void application(PostingService postingService, AccountRepository accounts) {
    Currency euro = Currency.getInstance("EUR");
    UUID cashAccountId = UUID.randomUUID();
    UUID depositsAccountId = UUID.randomUUID();

    Account cashAccount =
        new Account(cashAccountId, "Bank cash", Optional.empty(), AccountType.ASSET, euro);
    Account depositsAccount =
        new Account(
            depositsAccountId,
            "Customer deposits control",
            Optional.empty(),
            AccountType.LIABILITY,
            euro);

    accounts.create(cashAccount);
    accounts.create(depositsAccount);

    JournalEntry deposit =
        new JournalEntry(
            UUID.randomUUID(),
            Instant.now(),
            "Bank application smoke test deposit",
            "smoke-test-" + UUID.randomUUID(),
            List.of(
                new Posting(cashAccountId, new Money("100.00", euro), Direction.DEBIT),
                new Posting(depositsAccountId, new Money("100.00", euro), Direction.CREDIT)));

    JournalEntry storedEntry = postingService.post(deposit, IdempotencyKey.create());

    Account storedCashAccount =
        accounts
            .findById(cashAccountId)
            .orElseThrow(() -> new IllegalStateException("Created cash account was not found"));
    Account storedDepositsAccount =
        accounts
            .findById(depositsAccountId)
            .orElseThrow(() -> new IllegalStateException("Created deposits account was not found"));

    System.out.printf(
        "Smoke test completed: entry=%s, accounts=%s,%s%n",
        storedEntry.id(), storedCashAccount.accountId(), storedDepositsAccount.accountId());
  }
}
