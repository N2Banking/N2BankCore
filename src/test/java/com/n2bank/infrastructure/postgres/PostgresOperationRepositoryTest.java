package com.n2bank.infrastructure.postgres;

import com.n2bank.application.command.*;
import com.n2bank.application.fee.NoFeePolicy;
import com.n2bank.application.port.RepositoryException;
import com.n2bank.application.service.*;
import com.n2bank.domain.model.*;
import com.n2bank.infrastructure.redis.NoOpBalanceCache;
import com.zaxxer.hikari.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Integration checks in a disposable schema, never the application's tables. */
class PostgresOperationRepositoryTest {
  static HikariDataSource database;
  static String schema;
  final Currency eur = Currency.getInstance("EUR");
  UUID source, destination, cash;
  TransferHandler operations;
  PostgresJournalEntryRepository journal;

  @BeforeAll
  static void start() throws Exception {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(System.getenv().getOrDefault("N2BANK_TEST_URL", "jdbc:postgresql://localhost:5432/n2bank"));
    config.setUsername(System.getenv().getOrDefault("N2BANK_TEST_USER", "n2bank"));
    config.setPassword(System.getenv().getOrDefault("N2BANK_TEST_PASSWORD", "n2bank_local"));
    config.setMaximumPoolSize(3);
    schema = "operations_test_" + UUID.randomUUID().toString().replace("-", "");
    config.setSchema(schema);
    database = new HikariDataSource(config);
    try (Connection connection = database.getConnection(); Statement sql = connection.createStatement()) {
      sql.execute("CREATE SCHEMA " + schema);
      connection.setSchema(schema);
      sql.execute(Files.readString(Path.of("database/schema.sql")));
    }
  }

  @AfterAll
  static void stop() throws Exception {
    if (database == null) return;
    try {
      if (schema != null) {
        try (Connection connection = database.getConnection(); Statement sql = connection.createStatement()) {
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
    var customer = new PostgresCustomerRepository(database).createIfAbsent(
        new Customer(UUID.randomUUID(), "Test customer", CustomerType.PERSON));
    source = UUID.randomUUID(); destination = UUID.randomUUID(); cash = UUID.randomUUID();
    accounts.create(new Account(source, "Sender", Optional.of(customer), AccountType.LIABILITY, eur));
    accounts.create(new Account(destination, "Receiver", Optional.of(customer), AccountType.LIABILITY, eur));
    accounts.create(new Account(cash, "Cash", Optional.empty(), AccountType.ASSET, eur));
    journal = new PostgresJournalEntryRepository(database);
    operations = new TransferHandler(new OperationExecutor(new PostgresOperationRepository(database), new NoOpBalanceCache()),
        new FeeService(List.of(new NoFeePolicy(FeeType.TRANSFER))),
        currency -> { throw new AssertionError("No revenue lookup for a free transfer"); });
    fund("100");
  }

  void fund(String amount) {
    Money money = new Money(amount, eur);
    journal.append(new JournalEntry(UUID.randomUUID(), Instant.now(), "Funding", null, List.of(
        new Posting(cash, money, Direction.DEBIT), new Posting(source, money, Direction.CREDIT))),
        IdempotencyKey.create());
  }

  TransferCommand command(IdempotencyKey key, String amount) {
    return new TransferCommand(key, source, destination, new Money(amount, eur), "reference");
  }

  @Test
  void fullBalanceReplayReturnsOriginalWithoutRecalculatingFees() {
    IdempotencyKey key = IdempotencyKey.create();
    OperationResult first = operations.handle(command(key, "100"));
    assertFalse(first.replayed());
    var changedConfiguration = new TransferHandler(new OperationExecutor(new PostgresOperationRepository(database), new NoOpBalanceCache()),
        new FeeService(List.of()), currency -> null);
    OperationResult replay = changedConfiguration.handle(command(key, "100.00"));
    assertTrue(replay.replayed());
    assertEquals(first.journalEntry(), replay.journalEntry());
    assertTrue(operations.handle(command(key, "100")).replayed());
    assertEquals(0, new PostgresBalanceRepository(database).getBalance(source).amount().signum());
    assertEquals(0, new PostgresBalanceRepository(database).getBalance(destination).amount()
        .compareTo(new java.math.BigDecimal("100")));
  }

  @Test
  void differentInputWithSameKeyConflicts() {
    IdempotencyKey key = IdempotencyKey.create();
    operations.handle(command(key, "10"));
    assertThrows(IdempotencyConflictException.class, () -> operations.handle(command(key, "11")));
  }

  @Test
  void insufficientFundsRollsBackClaimAndJournal() throws Exception {
    IdempotencyKey key = IdempotencyKey.create();
    assertThrows(RepositoryException.class, () -> operations.handle(command(key, "101")));
    assertTrue(journal.findByIdempotencyKey(key).isEmpty());
    try (Connection connection = database.getConnection();
        PreparedStatement sql = connection.prepareStatement("SELECT count(*) FROM operations WHERE idempotency_key = ?")) {
      sql.setString(1, key.value());
      try (ResultSet rows = sql.executeQuery()) {
        rows.next(); assertEquals(0, rows.getInt(1));
      }
    }
    fund("1");
    assertFalse(operations.handle(command(key, "101")).replayed());
  }

  @Test
  void simultaneousRetriesCommitOneTransfer() throws Exception {
    TransferCommand command = command(IdempotencyKey.create(), "100");
    var start = new java.util.concurrent.CyclicBarrier(3);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(3)) {
      var futures = new ArrayList<java.util.concurrent.Future<OperationResult>>();
      for (int i = 0; i < 3; i++) {
        futures.add(executor.submit(() -> {
          start.await(5, java.util.concurrent.TimeUnit.SECONDS);
          return operations.handle(command);
        }));
      }
      List<OperationResult> results = new ArrayList<>();
      for (var future : futures) results.add(future.get(10, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals(1, results.stream().filter(result -> !result.replayed()).count());
      assertEquals(1, results.stream().map(OperationResult::journalEntry).distinct().count());
      assertEquals(0, new PostgresBalanceRepository(database).getBalance(source).amount().signum());
      assertEquals(0, new PostgresBalanceRepository(database).getBalance(destination).amount()
          .compareTo(new java.math.BigDecimal("100")));
    }
  }

  @Test
  void simultaneousDifferentPayloadsHaveOneWinner() throws Exception {
    IdempotencyKey key = IdempotencyKey.create();
    var start = new java.util.concurrent.CyclicBarrier(2);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
      for (String amount : List.of("10", "20")) {
        futures.add(executor.submit(() -> {
          start.await(5, java.util.concurrent.TimeUnit.SECONDS);
          try {
            assertFalse(operations.handle(command(key, amount)).replayed());
            return true;
          } catch (IdempotencyConflictException expected) {
            return false;
          }
        }));
      }
      int winners = 0;
      for (var future : futures) if (future.get(10, java.util.concurrent.TimeUnit.SECONDS)) winners++;
      assertEquals(1, winners);
    }
  }

  @Test
  void allHandlersPostFeesAndReplayTheirOriginalResults() {
    UUID revenue = UUID.randomUUID();
    new PostgresAccountRepository(database).create(new Account(revenue, "Revenue",
        Optional.empty(), AccountType.REVENUE, eur));
    var executor = new OperationExecutor(new PostgresOperationRepository(database), new NoOpBalanceCache());
    var fees = new FeeService(List.of(
        new com.n2bank.application.fee.FixedFeePolicy(FeeType.CASH_DEPOSIT, new Money("2", eur), "Fixed fee"),
        new com.n2bank.application.fee.FixedFeePolicy(FeeType.WITHDRAWAL, new Money("2", eur), "Fixed fee")));
    var deposit = new DepositHandler(executor, fees, currency -> revenue, currency -> cash);
    var withdraw = new WithdrawalHandler(executor, fees, currency -> revenue, currency -> cash);
    var charge = new ChargeFeeHandler(executor, currency -> revenue);
    var reverse = new ReversalHandler(executor);
    var depositCommand = new DepositCommand(IdempotencyKey.create(), source, new Money("50", eur), null);
    var deposited = deposit.handle(depositCommand);
    assertFalse(deposited.replayed());
    assertEquals(0, new PostgresBalanceRepository(database).getBalance(source).amount()
        .compareTo(new java.math.BigDecimal("148")));
    var withdrawalCommand = new WithdrawalCommand(IdempotencyKey.create(), source, new Money("10", eur), null);
    var withdrawn = withdraw.handle(withdrawalCommand);
    assertFalse(withdrawn.replayed());
    assertEquals(0, new PostgresBalanceRepository(database).getBalance(source).amount()
        .compareTo(new java.math.BigDecimal("136")));
    var feeCommand = new ChargeFeeCommand(IdempotencyKey.create(), source, new Money("3", eur), "Service fee", null);
    var charged = charge.handle(feeCommand);
    assertFalse(charged.replayed());
    assertEquals(0, new PostgresBalanceRepository(database).getBalance(source).amount()
        .compareTo(new java.math.BigDecimal("133")));
    var reversalCommand = new ReversalCommand(IdempotencyKey.create(), charged.journalEntry().id(), null);
    var reversed = reverse.handle(reversalCommand);
    assertFalse(reversed.replayed());
    assertEquals(new OperationResult(deposited.journalEntry(), true), deposit.handle(depositCommand));
    assertEquals(new OperationResult(withdrawn.journalEntry(), true), withdraw.handle(withdrawalCommand));
    assertEquals(new OperationResult(charged.journalEntry(), true), charge.handle(feeCommand));
    assertEquals(new OperationResult(reversed.journalEntry(), true), reverse.handle(reversalCommand));
    assertEquals(0, new PostgresBalanceRepository(database).getBalance(source).amount()
        .compareTo(new java.math.BigDecimal("136")));
    assertThrows(IdempotencyConflictException.class, () -> charge.handle(new ChargeFeeCommand(
        feeCommand.idempotencyKey(), source, new Money("3", eur), "Different fee", null)));
    assertThrows(IdempotencyConflictException.class, () -> withdraw.handle(new WithdrawalCommand(
        depositCommand.idempotencyKey(), source, new Money("50", eur), null)));
    assertThrows(IdempotencyConflictException.class, () -> reverse.handle(new ReversalCommand(
        reversalCommand.idempotencyKey(), deposited.journalEntry().id(), null)));
  }

  @Test
  void missingReversalOriginalDoesNotConsumeKey() {
    var reverse = new ReversalHandler(new OperationExecutor(
        new PostgresOperationRepository(database), new NoOpBalanceCache()));
    IdempotencyKey key = IdempotencyKey.create();
    assertThrows(IllegalArgumentException.class,
        () -> reverse.handle(new ReversalCommand(key, UUID.randomUUID(), null)));
    assertTrue(journal.findByIdempotencyKey(key).isEmpty());
    assertFalse(operations.handle(command(key, "10")).replayed());
  }

  @Test
  void claimCannotCommitWithoutJournal() throws Exception {
    try (Connection connection = database.getConnection(); Statement sql = connection.createStatement()) {
      connection.setAutoCommit(false);
      sql.execute("INSERT INTO operations VALUES ('orphan', 'TRANSFER', repeat('a', 64), now())");
      assertThrows(SQLException.class, connection::commit);
      connection.rollback();
    }
  }
}
