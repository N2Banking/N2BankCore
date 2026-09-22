package com.n2bank.infrastructure.postgres;

import com.n2bank.application.command.*;
import com.n2bank.application.fee.NoFeePolicy;
import com.n2bank.application.port.RepositoryException;
import com.n2bank.application.service.*;
import com.n2bank.domain.model.*;
import com.n2bank.infrastructure.redis.NoOpBalanceCache;
import com.zaxxer.hikari.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Repeatable correctness workload, not a production capacity guarantee. */
class BankingLoadTest {
  private final Currency eur = Currency.getInstance("EUR");
  private HikariDataSource database;
  private String schema;
  private TransferHandler transfers;
  private PostgresJournalEntryRepository journal;
  private UUID cash;
  private final int workers = positiveProperty("n2bank.load.workers", 16);
  private final int poolSize = positiveProperty("n2bank.load.pool", 8);

  @BeforeEach
  void start() throws Exception {
    var config = new HikariConfig();
    config.setJdbcUrl(System.getenv().getOrDefault("N2BANK_TEST_URL", "jdbc:postgresql://localhost:5432/n2bank"));
    config.setUsername(System.getenv().getOrDefault("N2BANK_TEST_USER", "n2bank"));
    config.setPassword(System.getenv().getOrDefault("N2BANK_TEST_PASSWORD", "n2bank_local"));
    config.setMaximumPoolSize(poolSize);
    config.setConnectionInitSql("SET statement_timeout = '15s'");
    schema = "load_test_" + UUID.randomUUID().toString().replace("-", "");
    config.setSchema(schema);
    database = new HikariDataSource(config);
    try (var connection = database.getConnection(); var sql = connection.createStatement()) {
      sql.execute("CREATE SCHEMA " + schema);
      connection.setSchema(schema);
      sql.execute(Files.readString(Path.of("database/schema.sql")));
    }
    journal = new PostgresJournalEntryRepository(database);
    transfers = new TransferHandler(new OperationExecutor(new PostgresOperationRepository(database),
        new NoOpBalanceCache()), new FeeService(List.of(new NoFeePolicy(FeeType.TRANSFER))),
        currency -> { throw new AssertionError("Unexpected fee"); });
    cash = account(AccountType.ASSET);
  }

  @AfterEach
  void stop() throws Exception {
    if (database == null) return;
    try (var connection = database.getConnection(); var sql = connection.createStatement()) {
      sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    } finally {
      database.close();
    }
  }

  @ParameterizedTest(name = "concurrent transfers across {0} account pairs")
  @ValueSource(ints = {1, 16})
  void transfersAndDuplicatesPreserveEveryBalance(int pairs) throws Exception {
    int unique = positiveProperty("n2bank.load.transfers", 1000);
    int copies = positiveProperty("n2bank.load.copies", 3);
    var expected = new HashMap<UUID, BigDecimal>();
    var senders = new ArrayList<UUID>();
    var receivers = new ArrayList<UUID>();
    BigDecimal initial = BigDecimal.valueOf(unique + 100L);
    for (int i = 0; i < pairs; i++) {
      UUID left = fundedAccount(initial), right = fundedAccount(initial);
      senders.add(left); receivers.add(right);
      expected.put(left, initial); expected.put(right, initial);
    }
    var requests = new ArrayList<TransferCommand>();
    for (int i = 0; i < unique; i++) {
      int pair = i % pairs;
      boolean forward = (i / pairs) % 2 == 0;
      UUID from = forward ? senders.get(pair) : receivers.get(pair);
      UUID to = forward ? receivers.get(pair) : senders.get(pair);
      var request = new TransferCommand(IdempotencyKey.create(), from, to, new Money("1", eur), null);
      expected.compute(from, (key, balance) -> balance.subtract(BigDecimal.ONE));
      expected.compute(to, (key, balance) -> balance.add(BigDecimal.ONE));
      for (int copy = 0; copy < copies; copy++) requests.add(request);
    }
    Collections.shuffle(requests, new Random(42));
    List<Callable<Sample>> tasks = requests.stream().<Callable<Sample>>map(request -> () -> {
      long start = System.nanoTime();
      var result = transfers.handle(request);
      return new Sample(request.idempotencyKey(), result, System.nanoTime() - start);
    }).toList();
    long start = System.nanoTime();
    List<Sample> samples = run(tasks);
    double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
    assertEquals(unique, samples.stream().filter(sample -> !sample.result().replayed()).count());
    assertEquals((long) unique * (copies - 1), samples.stream().filter(sample -> sample.result().replayed()).count());
    var entryByKey = new HashMap<IdempotencyKey, UUID>();
    for (Sample sample : samples) {
      UUID previous = entryByKey.putIfAbsent(sample.key(), sample.result().journalEntry().id());
      if (previous != null) assertEquals(previous, sample.result().journalEntry().id());
    }
    assertEquals(unique, entryByKey.size());
    assertEquals(unique, new HashSet<>(entryByKey.values()).size());
    for (var entry : expected.entrySet()) assertBalance(entry.getKey(), entry.getValue());
    assertLedger(unique, unique + pairs * 2L);
    long[] latency = samples.stream().mapToLong(Sample::nanos).sorted().toArray();
    String report = String.format(Locale.ROOT,
        "pairs=%d workers=%d pool=%d calls=%d committed=%d replays=%d seconds=%.3f calls/s=%.1f commits/s=%.1f p50_ms=%.2f p95_ms=%.2f p99_ms=%.2f%n",
        pairs, workers, poolSize, samples.size(), unique, samples.size() - unique, seconds,
        samples.size() / seconds, unique / seconds, percentile(latency, .50), percentile(latency, .95), percentile(latency, .99));
    System.out.print("BANKING_LOAD " + report);
    Files.createDirectories(Path.of("target"));
    Files.writeString(Path.of("target", "banking-load-" + pairs + "-pairs.txt"), report);
  }

  @Test
  void competingTransfersCannotOverspendSharedBalance() throws Exception {
    UUID source = fundedAccount(new BigDecimal("100"));
    UUID destination = account(AccountType.LIABILITY);
    var tasks = new ArrayList<Callable<Boolean>>();
    for (int i = 0; i < 100; i++) {
      var request = new TransferCommand(IdempotencyKey.create(), source, destination, new Money("3", eur), null);
      tasks.add(() -> {
        try {
          assertFalse(transfers.handle(request).replayed());
          return true;
        } catch (RepositoryException failure) {
          assertTrue(failure.getMessage().startsWith("Insufficient funds in account "),
              () -> "Unexpected database failure: " + failure);
          assertTrue(journal.findByIdempotencyKey(request.idempotencyKey()).isEmpty());
          return false;
        }
      });
    }
    var results = run(tasks);
    assertEquals(33, results.stream().filter(Boolean::booleanValue).count());
    assertBalance(source, BigDecimal.ONE);
    assertBalance(destination, new BigDecimal("99"));
    assertLedger(33, 34);
  }

  private <T> List<T> run(List<Callable<T>> tasks) throws Exception {
    var executor = Executors.newFixedThreadPool(workers);
    try {
      // A shared release gate makes the first worker batch contend concurrently.
      var gate = new CountDownLatch(1);
      var ready = new CountDownLatch(Math.min(workers, tasks.size()));
      var futures = new ArrayList<Future<T>>();
      for (var task : tasks) futures.add(executor.submit(() -> {
        ready.countDown();
        if (!gate.await(10, TimeUnit.SECONDS)) throw new TimeoutException("Start gate timed out");
        return task.call();
      }));
      assertTrue(ready.await(10, TimeUnit.SECONDS), "Workers did not become ready");
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(positiveProperty("n2bank.load.timeoutSeconds", 120));
      gate.countDown();
      var results = new ArrayList<T>();
      for (var future : futures) results.add(future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
      return results;
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS), "Workers did not stop");
    }
  }

  private UUID account(AccountType type) {
    UUID id = UUID.randomUUID();
    Optional<Customer> owner = type == AccountType.LIABILITY
        ? Optional.of(new PostgresCustomerRepository(database).createIfAbsent(
            new Customer(UUID.randomUUID(), "Load test customer", CustomerType.PERSON)))
        : Optional.empty();
    new PostgresAccountRepository(database).create(new Account(id, "Load test", owner, type, eur));
    return id;
  }

  private UUID fundedAccount(BigDecimal amount) {
    UUID id = account(AccountType.LIABILITY);
    Money money = new Money(amount, eur);
    journal.append(new JournalEntry(UUID.randomUUID(), Instant.now(), "Funding", null, List.of(
        new Posting(cash, money, Direction.DEBIT), new Posting(id, money, Direction.CREDIT))), IdempotencyKey.create());
    return id;
  }

  private void assertBalance(UUID id, BigDecimal expected) {
    assertEquals(0, expected.compareTo(new PostgresBalanceRepository(database).getBalance(id).amount()),
        "Incorrect balance for " + id);
  }

  private void assertLedger(long operations, long entries) throws SQLException {
    try (var connection = database.getConnection(); var sql = connection.createStatement()) {
      try (var rows = sql.executeQuery("SELECT (SELECT count(*) FROM operations), (SELECT count(*) FROM journal_entries), (SELECT count(*) FROM postings)")) {
        assertTrue(rows.next());
        assertEquals(operations, rows.getLong(1));
        assertEquals(entries, rows.getLong(2));
        assertEquals(entries * 2, rows.getLong(3));
      }
      try (var rows = sql.executeQuery("SELECT journal_entry_id FROM postings GROUP BY journal_entry_id, currency HAVING sum(CASE WHEN direction = 'DEBIT' THEN amount ELSE -amount END) <> 0")) {
        assertFalse(rows.next(), "Unbalanced journal entry");
      }
    }
  }

  private static int positiveProperty(String name, int fallback) {
    int value = Integer.parseInt(System.getProperty(name, Integer.toString(fallback)));
    if (value < 1) throw new IllegalArgumentException(name + " must be positive");
    return value;
  }

  private static double percentile(long[] values, double percentile) {
    return values[(int) Math.ceil(values.length * percentile) - 1] / 1_000_000.0;
  }

  private record Sample(IdempotencyKey key, OperationResult result, long nanos) {}
}
