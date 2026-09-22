package com.n2bank.application.service;

import com.n2bank.application.command.BankCommand;
import com.n2bank.domain.model.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;

/** Shared posting construction helpers; no transaction or operation dispatch logic. */
final class HandlerSupport {
  private HandlerSupport() {}
  static Account require(Account account, AccountType type, Currency currency, boolean owned) {
    if (account.type() != type || account.owner().isPresent() != owned
        || !account.currency().equals(currency)) {
      throw new IllegalArgumentException("Invalid operation account: " + account.accountId());
    }
    return account;
  }
  static void addFee(List<Posting> lines, Money fee, Function<UUID, Account> accounts,
      Function<Currency, UUID> revenueAccounts) {
    if (!fee.isZero()) {
      Account revenue = require(accounts.apply(revenueAccounts.apply(fee.currency())),
          AccountType.REVENUE, fee.currency(), false);
      lines.add(new Posting(revenue.accountId(), fee, Direction.CREDIT));
    }
  }
  static JournalEntry entry(BankCommand command, String description, List<Posting> lines) {
    return new JournalEntry(UUID.randomUUID(), Instant.now().truncatedTo(ChronoUnit.MICROS),
        description, command.externalReference(), lines);
  }
}
