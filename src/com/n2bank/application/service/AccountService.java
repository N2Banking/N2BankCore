package com.n2bank.application.service;

import com.n2bank.application.port.AccountRepository;
import com.n2bank.domain.model.Account;
import java.util.Objects;

/** Coordinates account use cases. */
public final class AccountService {
  private final AccountRepository accounts;

  public AccountService(AccountRepository accounts) {
    this.accounts = Objects.requireNonNull(accounts, "Account repository cannot be null");
  }

  /**
   * Creates the account if its ID is unused, or returns the matching existing account.
   *
   * @throws IllegalStateException when the ID already belongs to different account data
   */
  public Account ensureExists(Account account) {
    Objects.requireNonNull(account, "Account cannot be null");
    Account stored = accounts.createIfAbsent(account);
    if (!stored.equals(account)) {
      throw new IllegalStateException(
          "Account " + account.accountId() + " already exists with different data");
    }
    return stored;
  }
}
