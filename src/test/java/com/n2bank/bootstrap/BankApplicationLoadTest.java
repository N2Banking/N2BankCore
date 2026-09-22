package com.n2bank.bootstrap;

import com.n2bank.application.command.*;
import com.n2bank.application.fee.NoFeePolicy;
import com.n2bank.domain.model.*;
import com.n2bank.infrastructure.database.DBConfig;
import com.n2bank.infrastructure.redis.RedisBalanceCache;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import redis.clients.jedis.RedisClient;
import static org.junit.jupiter.api.Assertions.*;

/** Public-library integration tests. JDBC is used only to install/remove the test schema. */
class BankApplicationLoadTest {
  private final Currency eur = Currency.getInstance("EUR");
  private final List<UUID> accountIds = new ArrayList<>();
  private BankApplication bank;
  private Connection admin;
  private RedisClient redis;
  private String schema;
  private DBConfig config;
  private UUID cash, revenue;

  @BeforeEach
  void start() throws Exception {
    assertFalse(BankApplication.isInitialized(), "This test requires exclusive use of the application singleton");
    String url = System.getenv().getOrDefault("N2BANK_TEST_URL", "jdbc:postgresql://localhost:5432/n2bank");
    String user = System.getenv().getOrDefault("N2BANK_TEST_USER", "n2bank");
    String password = System.getenv().getOrDefault("N2BANK_TEST_PASSWORD", "n2bank_local");
    assertNull(org.postgresql.Driver.parseURL(url, new Properties()).getProperty("currentSchema"),
        "Omit currentSchema: the test assigns its own disposable schema");
    admin = DriverManager.getConnection(url, user, password);
    schema = "facade_test_" + UUID.randomUUID().toString().replace("-", "");
    try (var sql = admin.createStatement()) {
      sql.execute("CREATE SCHEMA " + schema);
      admin.setSchema(schema);
      sql.execute(Files.readString(Path.of("database/schema.sql")));
    }
    String redisUri = System.getenv().getOrDefault("N2BANK_TEST_REDIS_URI", "redis://localhost:6379");
    redis = RedisClient.create(redisUri);
    assertEquals("PONG", redis.ping(), "A live Redis instance is required for this integration test");
    config = new DBConfig(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema
        + "&options=-c%20statement_timeout%3D15000", user, password, positive("n2bank.load.pool", 8), redisUri);
    initialize();
    cash = UUID.randomUUID(); revenue = UUID.randomUUID();
    accountIds.add(cash); accountIds.add(revenue);
    registerSystemAccounts();
    bank.ensureSystemAccounts(eur, "Test cash", "Test revenue");
  }

  private void initialize() {
    bank = BankApplication.initialize(config, Arrays.stream(FeeType.values()).map(NoFeePolicy::new).toList());
    assertSame(bank, BankApplication.getInstance());
  }

  private void registerSystemAccounts() {
    bank.registerSystemAccounts(eur, cash, revenue);
  }

  @AfterEach
  void stop() throws Exception {
    try {
      if (bank != null) bank.close();
    } finally {
      try {
        if (redis != null) {
          try {
            var cache = new RedisBalanceCache(redis);
            for (UUID id : accountIds) cache.invalidate(id);
          } finally { redis.close(); }
        }
      } finally {
        if (admin != null) {
          try (var connection = admin; var sql = connection.createStatement()) {
            if (schema != null) sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
          }
        }
      }
    }
  }

  @Test
  void customerJourneyAndReplayAfterReinitialization() {
    UUID alice = customerAccount("Alice"), bob = customerAccount("Bob");
    var deposit = new DepositCommand(IdempotencyKey.create(), alice, money("100"), "cash-in");
    var deposited = bank.deposit(deposit);
    assertFalse(deposited.replayed());
    assertEquals(new OperationResult(deposited.journalEntry(), true), bank.deposit(deposit));
    balance(alice, "100"); // Populate Redis before the next write.
    var transfer = new TransferCommand(IdempotencyKey.create(), alice, bob, money("30"), "payment");
    var transferred = bank.transfer(transfer);
    balance(alice, "70"); balance(bob, "30");
    var withdrawal = new WithdrawalCommand(IdempotencyKey.create(), bob, money("10"), "cash-out");
    var withdrawn = bank.withdraw(withdrawal);
    assertEquals(new OperationResult(withdrawn.journalEntry(), true), bank.withdraw(withdrawal));
    var fee = new ChargeFeeCommand(IdempotencyKey.create(), alice, money("2"), "Service fee", null);
    var charged = bank.chargeFee(fee);
    assertEquals(new OperationResult(charged.journalEntry(), true), bank.chargeFee(fee));
    balance(alice, "68");
    var reversal = new ReversalCommand(IdempotencyKey.create(), charged.journalEntry().id(), null);
    var reversed = bank.reverse(reversal);
    assertEquals(new OperationResult(reversed.journalEntry(), true), bank.reverse(reversal));
    balance(alice, "70"); balance(bob, "20"); balance(cash, "90"); balance(revenue, "0");
    assertThrows(IdempotencyConflictException.class, () -> bank.transfer(
        new TransferCommand(transfer.idempotencyKey(), alice, bob, money("31"), "payment")));
    assertEquals(4, statement(alice).size());
    assertEquals(2, statement(bob).size());
    var closed = bank;
    bank.close();
    assertFalse(BankApplication.isInitialized());
    assertThrows(IllegalStateException.class, () -> closed.transfer(transfer));
    initialize(); registerSystemAccounts();
    assertEquals(new OperationResult(transferred.journalEntry(), true), bank.transfer(transfer));
    balance(alice, "70"); balance(bob, "20");
    assertEquals(4, statement(alice).size());
    assertEquals(0, bank.trialBalance().get("ASSET:EUR").amount()
        .compareTo(bank.trialBalance().get("LIABILITY:EUR").amount()));
  }

  @Test
  void concurrentClientCallsCommitEachTransferOnce() throws Exception {
    int unique = positive("n2bank.load.transfers", 1000);
    int copies = positive("n2bank.load.copies", 3);
    int workers = positive("n2bank.load.workers", 16);
    UUID alice = customerAccount("Alice"), bob = customerAccount("Bob");
    String funding = Long.toString(unique + 100L);
    bank.deposit(new DepositCommand(IdempotencyKey.create(), alice, money(funding), null));
    bank.deposit(new DepositCommand(IdempotencyKey.create(), bob, money(funding), null));
    balance(alice, funding); balance(bob, funding);
    var requests = new ArrayList<TransferCommand>();
    for (int i = 0; i < unique; i++) {
      var request = new TransferCommand(IdempotencyKey.create(), i % 2 == 0 ? alice : bob,
          i % 2 == 0 ? bob : alice, money("1"), "client-payment-" + i);
      for (int copy = 0; copy < copies; copy++) requests.add(request);
    }
    Collections.shuffle(requests, new Random(42));
    var pool = Executors.newFixedThreadPool(workers);
    var gate = new CountDownLatch(1);
    var ready = new CountDownLatch(Math.min(workers, requests.size()));
    var futures = new ArrayList<Future<Sample>>();
    var results = new ArrayList<Sample>();
    double seconds;
    try {
      for (var request : requests) futures.add(pool.submit(() -> {
        ready.countDown();
        if (!gate.await(10, TimeUnit.SECONDS)) throw new TimeoutException("Start gate timed out");
        long start = System.nanoTime();
        var result = BankApplication.getInstance().transfer(request);
        return new Sample(request.idempotencyKey(), result, System.nanoTime() - start);
      }));
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      long start = System.nanoTime();
      long deadline = start + TimeUnit.SECONDS.toNanos(positive("n2bank.load.timeoutSeconds", 120));
      gate.countDown();
      for (var future : futures) results.add(future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
      seconds = (System.nanoTime() - start) / 1_000_000_000.0;
    } finally {
      pool.shutdownNow();
      assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS), "Workers did not stop");
    }
    assertEquals(unique, results.stream().filter(s -> !s.result().replayed()).count());
    assertEquals((long) unique * (copies - 1), results.stream().filter(s -> s.result().replayed()).count());
    var entries = new HashMap<IdempotencyKey, JournalEntry>();
    for (var sample : results) {
      var previous = entries.putIfAbsent(sample.key(), sample.result().journalEntry());
      if (previous != null) assertEquals(previous, sample.result().journalEntry());
    }
    assertEquals(unique, entries.size());
    assertEquals(unique, entries.values().stream().map(JournalEntry::id).distinct().count());
    balance(alice, Long.toString(unique + 100L - unique % 2));
    balance(bob, Long.toString(unique + 100L + unique % 2));
    for (UUID id : List.of(alice, bob)) {
      var statement = statement(id);
      assertEquals(unique + 1, statement.size());
      assertTrue(statement.containsAll(entries.values()), "Statement must contain every committed transfer");
      for (var entry : statement) {
        BigDecimal net = entry.postings().stream().map(p -> p.direction() == Direction.DEBIT
            ? p.amount().amount() : p.amount().amount().negate()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, net.signum(), "Every returned journal entry must balance");
      }
    }
    var trial = bank.trialBalance();
    assertEquals(0, trial.get("ASSET:EUR").amount().compareTo(trial.get("LIABILITY:EUR").amount()));
    long[] latency = results.stream().mapToLong(Sample::nanos).sorted().toArray();
    String report = String.format(Locale.ROOT,
        "BankApplication: workers=%d pool=%d calls=%d commits=%d replays=%d seconds=%.3f calls/s=%.1f commits/s=%.1f p50_ms=%.2f p95_ms=%.2f p99_ms=%.2f%n",
        workers, config.postgresMaximumPoolSize(), results.size(), unique, results.size() - unique,
        seconds, results.size() / seconds, unique / seconds,
        percentile(latency, .50), percentile(latency, .95), percentile(latency, .99));
    System.out.print(report);
    Files.createDirectories(Path.of("target"));
    Files.writeString(Path.of("target/bank-application-load.txt"), report);
  }

  private UUID customerAccount(String name) {
    var customer = bank.createCustomer(new Customer(UUID.randomUUID(), name, CustomerType.PERSON));
    UUID id = UUID.randomUUID();
    accountIds.add(id);
    var account = bank.openAccount(new Account(id, name, Optional.of(customer), AccountType.LIABILITY, eur));
    assertEquals(account, bank.getAccount(id));
    return id;
  }

  private Money money(String amount) { return new Money(amount, eur); }
  private void balance(UUID id, String expected) {
    assertEquals(0, new BigDecimal(expected).compareTo(bank.getBalance(id).amount()), "Balance for " + id);
  }
  private List<JournalEntry> statement(UUID id) { return bank.statement(id, Instant.EPOCH, Instant.now()); }
  private static int positive(String name, int fallback) {
    int value = Integer.parseInt(System.getProperty(name, Integer.toString(fallback)));
    if (value < 1) throw new IllegalArgumentException(name + " must be positive");
    return value;
  }
  private static double percentile(long[] values, double p) {
    return values[(int) Math.ceil(values.length * p) - 1] / 1_000_000.0;
  }
  private record Sample(IdempotencyKey key, OperationResult result, long nanos) {}
}
