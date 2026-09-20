package com.n2bank.bootstrap;

import com.n2bank.application.port.AccountRepository;
import com.n2bank.application.port.BalanceCache;
import com.n2bank.application.port.BalanceRepository;
import com.n2bank.application.port.JournalEntryRepository;
import com.n2bank.application.service.AccountService;
import com.n2bank.application.service.BalanceService;
import com.n2bank.application.service.CustomerService;
import com.n2bank.application.service.PostingService;
import com.n2bank.infrastructure.database.DBConfig;
import com.n2bank.infrastructure.database.DBHandler;
import com.n2bank.infrastructure.postgres.PostgresAccountRepository;
import com.n2bank.infrastructure.postgres.PostgresBalanceRepository;
import com.n2bank.infrastructure.postgres.PostgresCustomerRepository;
import com.n2bank.infrastructure.postgres.PostgresJournalEntryRepository;
import com.n2bank.infrastructure.redis.RedisBalanceCache;

public abstract class DbApplication implements Runnable {

  @Override
  public final void run() {
    try (DBHandler db = new DBHandler(DBConfig.fromEnvironment())) {
      AccountRepository accounts = new PostgresAccountRepository(db.postgres());
      JournalEntryRepository journal = new PostgresJournalEntryRepository(db.postgres());
      BalanceRepository balances = new PostgresBalanceRepository(db.postgres());
      BalanceCache cache = new RedisBalanceCache(db.redis());
      AccountService accountService = new AccountService(accounts);
      BalanceService balanceService = new BalanceService(balances, cache);
      CustomerService customerService =
          new CustomerService(new PostgresCustomerRepository(db.postgres()));
      PostingService postingService = new PostingService(journal, cache);
      application(postingService, accountService, customerService, balanceService);
    }
  }

  /**
   * Runs within the database clients' lifetime. Implementations that start a server or background
   * workers must wait for their shutdown before returning; returning closes the clients.
   */
  protected abstract void application(
      PostingService postingService,
      AccountService accountService,
      CustomerService customerService,
      BalanceService balanceService);
}
