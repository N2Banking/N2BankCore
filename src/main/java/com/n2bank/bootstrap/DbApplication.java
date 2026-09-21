package com.n2bank.bootstrap;

import com.n2bank.application.port.AccountRepository;
import com.n2bank.application.port.BalanceCache;
import com.n2bank.application.port.BalanceRepository;
import com.n2bank.application.port.JournalEntryRepository;
import com.n2bank.application.service.AccountService;
import com.n2bank.application.service.BalanceService;
import com.n2bank.application.service.CustomerService;
import com.n2bank.application.service.FeeService;
import com.n2bank.application.service.PostingService;
import com.n2bank.application.fee.FeePolicy;
import com.n2bank.infrastructure.database.DBConfig;
import com.n2bank.infrastructure.database.DBHandler;
import com.n2bank.infrastructure.database.RollbackOnlyDataSource;
import com.n2bank.infrastructure.postgres.PostgresAccountRepository;
import com.n2bank.infrastructure.postgres.PostgresBalanceRepository;
import com.n2bank.infrastructure.postgres.PostgresCustomerRepository;
import com.n2bank.infrastructure.postgres.PostgresJournalEntryRepository;
import com.n2bank.infrastructure.redis.RedisBalanceCache;
import com.n2bank.infrastructure.redis.NoOpBalanceCache;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import java.util.List;

public abstract class DbApplication implements Runnable {

  @Override
  public final void run() {
    try (DBHandler db = new DBHandler(DBConfig.fromEnvironment())) {
      if (rollbackDatabaseChanges()) {
        runRollbackOnly(db);
      } else {
        runApplication(db.postgres(), db);
      }
    }
  }

  private void runRollbackOnly(DBHandler db) {
    try (Connection connection = db.postgresConnection()) {
      RollbackOnlyDataSource testDataSource = new RollbackOnlyDataSource(connection);
      try {
        runApplication(testDataSource, db, true);
      } finally {
        testDataSource.rollback();
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("Could not run the rollback-only database workflow", exception);
    }
  }

  private void runApplication(DataSource postgres, DBHandler db) {
      runApplication(postgres, db, false);
  }

  private void runApplication(DataSource postgres, DBHandler db, boolean rollbackOnly) {
      AccountRepository accounts = new PostgresAccountRepository(postgres);
      JournalEntryRepository journal = new PostgresJournalEntryRepository(postgres);
      BalanceRepository balances = new PostgresBalanceRepository(postgres);
      BalanceCache cache = rollbackOnly ? new NoOpBalanceCache() : new RedisBalanceCache(db.redis());
      AccountService accountService = new AccountService(accounts);
      BalanceService balanceService = new BalanceService(balances, cache);
      CustomerService customerService =
          new CustomerService(new PostgresCustomerRepository(db.postgres()));
      PostingService postingService = new PostingService(journal, cache);
      FeeService feeService = new FeeService(feePolicies());
      servicesReady(postingService, accountService, customerService, balanceService, feeService);
      application(postingService, accountService, customerService, balanceService);
  }

  /** Called after the application services have been constructed and before the workflow starts. */
  protected void servicesReady(
      PostingService postingService,
      AccountService accountService,
      CustomerService customerService,
      BalanceService balanceService,
      FeeService feeService) {}

  /** Override to configure the fee strategies used by this application. */
  protected List<FeePolicy> feePolicies() {
    return List.of();
  }

  /** Override for executable database tests whose changes must be rolled back after the run. */
  protected boolean rollbackDatabaseChanges() {
    return false;
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
