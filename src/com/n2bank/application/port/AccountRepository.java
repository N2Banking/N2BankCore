package com.n2bank.application.port;

import com.n2bank.domain.model.Account;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for accounts. */
public interface AccountRepository {
  Optional<Account> findById(UUID accountId);

  void create(Account account);

  /** Atomically creates the account when its ID is absent, then returns the stored account. */
  Account createIfAbsent(Account account);
}
