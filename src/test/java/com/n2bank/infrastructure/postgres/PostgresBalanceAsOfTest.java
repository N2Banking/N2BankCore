package com.n2bank.infrastructure.postgres;

import static org.junit.jupiter.api.Assertions.*;

import com.n2bank.application.port.RepositoryException;
import com.n2bank.application.service.BalanceService;
import com.n2bank.domain.model.Account;
import com.n2bank.domain.model.AccountType;
import com.n2bank.domain.model.Customer;
import com.n2bank.domain.model.CustomerType;
import com.n2bank.domain.model.Direction;
import com.n2bank.domain.model.IdempotencyKey;
import com.n2bank.domain.model.JournalEntry;
import com.n2bank.domain.model.Money;
import com.n2bank.domain.model.Posting;
import com.n2bank.infrastructure.redis.NoOpBalanceCache;
import com.n2bank.testsupport.PostgresContainerSupport;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Future-dated entries count in the current balance but are excluded from as-of balances and
 * date-bounded statements.
 */
class PostgresBalanceAsOfTest {
  static HikariDataSource database;
  static String schema;
  final Currency eur = Currency.getInstance("EUR");
  UUID cash;
  UUID alice;
  PostgresJournalEntryRepository journal;
  PostgresBalanceRepository balances;
  BalanceService service;

  @BeforeAll
  static void start() throws Exception {
    var endpoint = PostgresContainerSupport.endpoint();
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(endpoint.jdbcUrl());
    config.setUsername(endpoint.username());
    config.setPassword(endpoint.password());
    config.setMaximumPoolSize(3);
    schema = "asof_test_" + UUID.randomUUID().toString().replace("-", "");
    config.setSchema(schema);
    database = new HikariDataSource(config);
    try (Connection connection = database.getConnection();
        Statement sql = connection.createStatement()) {
      sql.execute("CREATE SCHEMA " + schema);
      connection.setSchema(schema);
      sql.execute(Files.readString(Path.of("database/schema.sql")));
    }
  }

  @AfterAll
  static void stop() throws Exception {
    if (database == null) {
      return;
    }
    try {
      if (schema != null) {
        try (Connection connection = database.getConnection();
            Statement sql = connection.createStatement()) {
          sql.execute("DROP SCHEMA " + schema + " CASCADE");
        }
      }
    } finally {
      database.close();
    }
  }

  @BeforeEach
  void setupAccounts() {
    var accounts = new PostgresAccountRepository(database);
    var customer =
        new PostgresCustomerRepository(database)
            .createIfAbsent(new Customer(UUID.randomUUID(), "Alice", CustomerType.PERSON));
    cash = UUID.randomUUID();
    alice = UUID.randomUUID();
    accounts.create(new Account(cash, "Cash", Optional.empty(), AccountType.ASSET, eur));
    accounts.create(
        new Account(alice, "Alice", Optional.of(customer), AccountType.LIABILITY, eur));
    journal = new PostgresJournalEntryRepository(database);
    balances = new PostgresBalanceRepository(database);
    service = new BalanceService(balances, new NoOpBalanceCache());
    // Fund Alice with EUR 100 effective now.
    append(cash, alice, "100", Instant.now());
  }

  void append(UUID debitAccount, UUID creditAccount, String amount, Instant effectiveAt) {
    Money money = new Money(amount, eur);
    journal.append(
        new JournalEntry(
            UUID.randomUUID(),
            effectiveAt,
            "Test entry",
            null,
            List.of(
                new Posting(debitAccount, money, Direction.DEBIT),
                new Posting(creditAccount, money, Direction.CREDIT))),
        IdempotencyKey.create());
  }

  @Test
  void futureDatedEntryCountsInCurrentBalanceButNotAsOf() {
    Instant now = Instant.now();
    append(cash, alice, "50", now.plus(2, ChronoUnit.DAYS));

    // Current balance includes the future-dated posting.
    assertEquals(
        0, balances.getBalance(alice).amount().compareTo(new BigDecimal("150")));
    // As-of now excludes it.
    assertEquals(
        0, balances.getBalanceAsOf(alice, now).amount().compareTo(new BigDecimal("100")));
    // As-of after the future entry includes it.
    assertEquals(
        0,
        balances
            .getBalanceAsOf(alice, now.plus(3, ChronoUnit.DAYS))
            .amount()
            .compareTo(new BigDecimal("150")));
  }

  @Test
  void serviceAsOfBypassesCacheAndMatchesRepository() {
    Instant now = Instant.now();
    append(cash, alice, "25", now.plus(1, ChronoUnit.DAYS));
    assertEquals(
        0, service.getBalanceAsOf(alice, now).amount().compareTo(new BigDecimal("100")));
    assertEquals(
        0, service.getBalance(alice).amount().compareTo(new BigDecimal("125")));
  }

  @Test
  void statementWindowExcludesFutureEntryWhileBalanceIncludesIt() {
    Instant now = Instant.now();
    Instant future = now.plus(2, ChronoUnit.DAYS);
    append(cash, alice, "10", future);
    assertTrue(balances.statement(alice, now.minus(1, ChronoUnit.HOURS), now).size() >= 1);
    assertTrue(
        balances
            .statement(alice, future.plus(1, ChronoUnit.HOURS), future.plus(2, ChronoUnit.HOURS))
            .isEmpty());
    assertEquals(
        0, balances.getBalance(alice).amount().compareTo(new BigDecimal("110")));
  }

  @Test
  void asOfRejectsNullsAndMissingAccounts() {
    assertThrows(NullPointerException.class, () -> balances.getBalanceAsOf(null, Instant.now()));
    assertThrows(NullPointerException.class, () -> balances.getBalanceAsOf(alice, null));
    assertThrows(
        RepositoryException.class,
        () -> balances.getBalanceAsOf(UUID.randomUUID(), Instant.now()));
  }
}
