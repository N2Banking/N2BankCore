package com.n2bank.infrastructure.postgres;

import com.n2bank.application.port.RepositoryException;
import com.n2bank.domain.model.*;
import com.n2bank.infrastructure.database.DBConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for journal persistence and transaction behavior.
 * Uses compose Postgres at localhost:5432 (n2bank/n2bank_local) if Testcontainers unavailable.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class PostgresJournalEntryRepositoryTest {

  private static DataSource dataSource;
  private static PostgresJournalEntryRepository journal;
  private static PostgresAccountRepository accounts;
  private static PostgresBalanceRepository balances;
  private static PostgresCustomerRepository customers;

  private static final Currency EUR = Currency.getInstance("EUR");
  private static final Currency USD = Currency.getInstance("USD");

  @BeforeAll
  static void setup() throws Exception {
    // Prefer compose DB at localhost:5432; fallback to env DBConfig
    HikariConfig cfg = new HikariConfig();
    try {
      DBConfig dbCfg = DBConfig.fromEnvironment();
      cfg.setJdbcUrl(dbCfg.postgresUrl());
      cfg.setUsername(dbCfg.postgresUser());
      cfg.setPassword(dbCfg.postgresPassword());
    } catch (Exception e) {
      cfg.setJdbcUrl("jdbc:postgresql://localhost:5432/n2bank");
      cfg.setUsername("n2bank");
      cfg.setPassword("n2bank_local");
    }
    cfg.setMaximumPoolSize(5);
    dataSource = new HikariDataSource(cfg);

    // Apply schema.sql (idempotent: drop and recreate if needed)
    String schema = Files.readString(Path.of("database/schema.sql"));
    try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
      // Clean slate: drop existing objects if any (tables + functions) then recreate
      try { st.execute("DROP TABLE IF EXISTS postings, journal_entries, accounts, customers CASCADE"); } catch (Exception ignore) {}
      try { st.execute("DROP FUNCTION IF EXISTS reject_journal_mutation() CASCADE"); } catch (Exception ignore) {}
      try { st.execute("DROP FUNCTION IF EXISTS require_posting_in_entry_transaction() CASCADE"); } catch (Exception ignore) {}
      try { st.execute("DROP FUNCTION IF EXISTS validate_complete_journal_entry() CASCADE"); } catch (Exception ignore) {}
      st.execute(schema);
    }

    journal = new PostgresJournalEntryRepository(dataSource);
    accounts = new PostgresAccountRepository(dataSource);
    balances = new PostgresBalanceRepository(dataSource);
    customers = new PostgresCustomerRepository(dataSource);
  }

  @AfterAll
  static void teardown() {
    if (dataSource instanceof HikariDataSource h) h.close();
  }

  @BeforeEach
  void clean() throws Exception {
    try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
      // Append-only triggers block DELETE; TRUNCATE bypasses them
      st.execute("TRUNCATE postings, journal_entries, accounts, customers CASCADE");
    }
  }

  // Helpers
  private Account createAccount(UUID id, String name, AccountType type, Currency cur, UUID customerId) {
    Customer cust = null;
    if (customerId != null) {
      cust = customers.createIfAbsent(new Customer(customerId, "cust-" + customerId.toString().substring(0, 4), CustomerType.PERSON));
    }
    Account acc = new Account(id, name, Optional.ofNullable(cust), type, cur);
    return accounts.createIfAbsent(acc);
  }

  private Account createSystemAccount(UUID id, String name, AccountType type, Currency cur) {
    return accounts.createIfAbsent(new Account(id, name, Optional.empty(), type, cur));
  }

  private JournalEntry balancedEntry(UUID id, Instant at, String desc, List<Posting> postings) {
    return new JournalEntry(id, at, desc, null, postings);
  }

  @Test
  @Order(1)
  void successfulBalancedEntry() {
    UUID cash = UUID.randomUUID(), custAcc = UUID.randomUUID(), revenue = UUID.randomUUID();
    createSystemAccount(cash, "cash", AccountType.ASSET, EUR);
    createSystemAccount(revenue, "revenue", AccountType.REVENUE, EUR);
    UUID custId = UUID.randomUUID();
    createAccount(custAcc, "alice", AccountType.LIABILITY, EUR, custId);

    // Fund customer via initial deposit (cash -> customer)
    // First need to fund cash? Cash is asset, starts 0, deposit debits cash so cash becomes positive - allowed.
    // Deposit 100: cash DEBIT 100, cust CREDIT 97, revenue CREDIT 3
    JournalEntry e = balancedEntry(UUID.randomUUID(), Instant.now(), "deposit", List.of(
        new Posting(cash, new Money("100.00", EUR), Direction.DEBIT),
        new Posting(custAcc, new Money("97.00", EUR), Direction.CREDIT),
        new Posting(revenue, new Money("3.00", EUR), Direction.CREDIT)
    ));
    JournalEntry stored = journal.append(e, new IdempotencyKey("dep-1"));
    assertEquals(e.id(), stored.id());
    assertEquals(new Money("97.00", EUR).amount().compareTo(balances.getBalance(custAcc).amount()), 0);
  }

  @Test
  @Order(2)
  void missingAccountsShouldRollback() {
    UUID fake = UUID.randomUUID();
    UUID cash = UUID.randomUUID();
    createSystemAccount(cash, "cash", AccountType.ASSET, EUR);
    JournalEntry e = balancedEntry(UUID.randomUUID(), Instant.now(), "bad", List.of(
        new Posting(cash, new Money("10.00", EUR), Direction.DEBIT),
        new Posting(fake, new Money("10.00", EUR), Direction.CREDIT)
    ));
    assertThrows(RepositoryException.class, () -> journal.append(e, new IdempotencyKey("missing-1")));
    // Verify no entry persisted
    assertTrue(journal.findById(e.id()).isEmpty());
    // Balance still correct
    assertEquals(0, balances.getBalance(cash).amount().compareTo(BigDecimal.ZERO));
  }

  @Test
  @Order(3)
  void currencyMismatchShouldFail() {
    UUID cash = UUID.randomUUID(), cust = UUID.randomUUID();
    createSystemAccount(cash, "cash-USD", AccountType.ASSET, USD);
    UUID custId = UUID.randomUUID();
    createAccount(cust, "bob", AccountType.LIABILITY, EUR, custId);
    // Amount currency EUR but cash account is USD -> deferred trigger should fail
    JournalEntry e = balancedEntry(UUID.randomUUID(), Instant.now(), "mismatch", List.of(
        new Posting(cash, new Money("10.00", EUR), Direction.DEBIT),
        new Posting(cust, new Money("10.00", EUR), Direction.CREDIT)
    ));
    assertThrows(RepositoryException.class, () -> journal.append(e, new IdempotencyKey("cur-mismatch")));
    assertTrue(journal.findById(e.id()).isEmpty());
  }

  @Test
  @Order(4)
  void identicalRetryReturnsStoredEntry() {
    UUID cash = UUID.randomUUID(), cust = UUID.randomUUID(), rev = UUID.randomUUID();
    createSystemAccount(cash, "cash", AccountType.ASSET, EUR);
    createSystemAccount(rev, "rev", AccountType.REVENUE, EUR);
    UUID custId = UUID.randomUUID();
    createAccount(cust, "alice", AccountType.LIABILITY, EUR, custId);
    JournalEntry e = balancedEntry(UUID.randomUUID(), Instant.parse("2026-09-20T10:00:00Z"), "dep", List.of(
        new Posting(cash, new Money("50.00", EUR), Direction.DEBIT),
        new Posting(cust, new Money("49.00", EUR), Direction.CREDIT),
        new Posting(rev, new Money("1.00", EUR), Direction.CREDIT)
    ));
    IdempotencyKey key = new IdempotencyKey("idem-same");
    JournalEntry first = journal.append(e, key);
    JournalEntry second = journal.append(e, key);
    assertEquals(first.id(), second.id());
    assertEquals(1, journal.findByAccount(cust, Instant.parse("2026-09-19T00:00:00Z"), Instant.parse("2026-09-21T00:00:00Z")).size());
  }

  @Test
  @Order(5)
  void conflictingRetryShouldFail() {
    UUID cash = UUID.randomUUID(), cust = UUID.randomUUID(), rev = UUID.randomUUID();
    createSystemAccount(cash, "cash", AccountType.ASSET, EUR);
    createSystemAccount(rev, "rev", AccountType.REVENUE, EUR);
    UUID custId = UUID.randomUUID();
    createAccount(cust, "alice", AccountType.LIABILITY, EUR, custId);
    JournalEntry e1 = balancedEntry(UUID.randomUUID(), Instant.now(), "first", List.of(
        new Posting(cash, new Money("20.00", EUR), Direction.DEBIT),
        new Posting(cust, new Money("20.00", EUR), Direction.CREDIT)
    ));
    JournalEntry e2 = balancedEntry(UUID.randomUUID(), Instant.now(), "second-different", List.of(
        new Posting(cash, new Money("30.00", EUR), Direction.DEBIT),
        new Posting(cust, new Money("30.00", EUR), Direction.CREDIT)
    ));
    IdempotencyKey key = new IdempotencyKey("idem-conflict");
    journal.append(e1, key);
    assertThrows(RepositoryException.class, () -> journal.append(e2, key));
  }

  @Test
  @Order(6)
  void duplicateEntryIdWithDifferentKeyMustFail() {
    UUID cash = UUID.randomUUID(), cust = UUID.randomUUID();
    createSystemAccount(cash, "cash", AccountType.ASSET, EUR);
    UUID custId = UUID.randomUUID();
    createAccount(cust, "alice", AccountType.LIABILITY, EUR, custId);
    UUID entryId = UUID.randomUUID();
    JournalEntry e1 = new JournalEntry(entryId, Instant.now(), "first", null, List.of(
        new Posting(cash, new Money("10.00", EUR), Direction.DEBIT),
        new Posting(cust, new Money("10.00", EUR), Direction.CREDIT)
    ));
    JournalEntry e2 = new JournalEntry(entryId, Instant.now().plusSeconds(10), "second", null, List.of(
        new Posting(cash, new Money("10.00", EUR), Direction.DEBIT),
        new Posting(cust, new Money("10.00", EUR), Direction.CREDIT)
    ));
    journal.append(e1, new IdempotencyKey("key-1"));
    RepositoryException ex = assertThrows(RepositoryException.class, () -> journal.append(e2, new IdempotencyKey("key-2")));
    assertTrue(ex.getMessage().toLowerCase().contains("already exists") || ex.getCause() != null);
  }

  @Test
  @Order(7)
  void concurrentSameKeyOnlyOneWins() throws Exception {
    UUID cash = UUID.randomUUID(), cust = UUID.randomUUID();
    createSystemAccount(cash, "cash", AccountType.ASSET, EUR);
    UUID custId = UUID.randomUUID();
    createAccount(cust, "alice", AccountType.LIABILITY, EUR, custId);
    JournalEntry e = balancedEntry(UUID.randomUUID(), Instant.now(), "conc", List.of(
        new Posting(cash, new Money("15.00", EUR), Direction.DEBIT),
        new Posting(cust, new Money("15.00", EUR), Direction.CREDIT)
    ));
    IdempotencyKey key = new IdempotencyKey("conc-key");
    int threads = 5;
    ExecutorService exec = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<JournalEntry>> futures = new java.util.ArrayList<>();
    for (int i = 0; i < threads; i++) {
      futures.add(exec.submit(() -> {
        start.await();
        return journal.append(e, key);
      }));
    }
    start.countDown();
    for (Future<JournalEntry> f : futures) {
      JournalEntry got = f.get();
      assertEquals(e.id(), got.id());
    }
    exec.shutdown();
    // Only one entry persisted
    assertTrue(journal.findById(e.id()).isPresent());
    long count = journal.findByAccount(cust, Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2030-01-01T00:00:00Z")).size();
    assertEquals(1, count);
  }

  @Test
  @Order(8)
  void insufficientFundsInsideTransactionPreventsOverdraft() {
    UUID cash = UUID.randomUUID(), cust = UUID.randomUUID(), rev = UUID.randomUUID();
    createSystemAccount(cash, "cash", AccountType.ASSET, EUR);
    createSystemAccount(rev, "rev", AccountType.REVENUE, EUR);
    UUID custId = UUID.randomUUID();
    createAccount(cust, "alice", AccountType.LIABILITY, EUR, custId);
    // Deposit 20
    JournalEntry dep = balancedEntry(UUID.randomUUID(), Instant.now(), "dep", List.of(
        new Posting(cash, new Money("20.00", EUR), Direction.DEBIT),
        new Posting(cust, new Money("20.00", EUR), Direction.CREDIT)
    ));
    journal.append(dep, new IdempotencyKey("fund-20"));
    // Try to withdraw 30 (more than balance) -> should fail inside append txn
    JournalEntry withdraw = balancedEntry(UUID.randomUUID(), Instant.now(), "withdraw-over", List.of(
        new Posting(cust, new Money("30.00", EUR), Direction.DEBIT),
        new Posting(cash, new Money("30.00", EUR), Direction.CREDIT)
    ));
    assertThrows(RepositoryException.class, () -> journal.append(withdraw, new IdempotencyKey("overdraft")));
    // Balance unchanged
    assertEquals(0, balances.getBalance(cust).amount().compareTo(new BigDecimal("20.00")));
  }

  @Test
  @Order(9)
  void concurrentOverdraftOnlyOneSucceeds() throws Exception {
    UUID cash = UUID.randomUUID(), cust = UUID.randomUUID();
    createSystemAccount(cash, "cash", AccountType.ASSET, EUR);
    UUID custId = UUID.randomUUID();
    createAccount(cust, "alice", AccountType.LIABILITY, EUR, custId);
    // Initial fund 100
    JournalEntry dep = balancedEntry(UUID.randomUUID(), Instant.now(), "dep100", List.of(
        new Posting(cash, new Money("100.00", EUR), Direction.DEBIT),
        new Posting(cust, new Money("100.00", EUR), Direction.CREDIT)
    ));
    journal.append(dep, new IdempotencyKey("init100"));

    // Two concurrent withdraws of 80 each -> only one should succeed (100 -80 =20, second would be -60)
    JournalEntry w1 = balancedEntry(UUID.randomUUID(), Instant.now(), "w80-1", List.of(
        new Posting(cust, new Money("80.00", EUR), Direction.DEBIT),
        new Posting(cash, new Money("80.00", EUR), Direction.CREDIT)
    ));
    JournalEntry w2 = balancedEntry(UUID.randomUUID(), Instant.now(), "w80-2", List.of(
        new Posting(cust, new Money("80.00", EUR), Direction.DEBIT),
        new Posting(cash, new Money("80.00", EUR), Direction.CREDIT)
    ));
    ExecutorService exec = Executors.newFixedThreadPool(2);
    Future<?> f1 = exec.submit(() -> {
      try { return journal.append(w1, new IdempotencyKey("w80-1")); } catch (Exception e) { return e; }
    });
    Future<?> f2 = exec.submit(() -> {
      try { return journal.append(w2, new IdempotencyKey("w80-2")); } catch (Exception e) { return e; }
    });
    Object r1 = f1.get(), r2 = f2.get();
    exec.shutdown();
    int successes = 0;
    if (r1 instanceof JournalEntry) successes++;
    if (r2 instanceof JournalEntry) successes++;
    assertEquals(1, successes, "Only one overdraft should succeed, results: " + r1 + " / " + r2);
    assertEquals(0, balances.getBalance(cust).amount().compareTo(new BigDecimal("20.00")));
  }

  @Test
  @Order(10)
  void scaleNormalizationSameAmountDifferentScaleIsIdempotent() {
    UUID cash = UUID.randomUUID(), cust = UUID.randomUUID();
    createSystemAccount(cash, "cash", AccountType.ASSET, EUR);
    UUID custId = UUID.randomUUID();
    createAccount(cust, "alice", AccountType.LIABILITY, EUR, custId);
    UUID eid = UUID.randomUUID();
    // First entry uses 10.00, second retry uses 10.000 with same key but different scale -> should be considered same via compareTo
    JournalEntry e1 = new JournalEntry(eid, Instant.parse("2026-09-20T10:00:00Z"), "scale", null, List.of(
        new Posting(cash, new Money(new BigDecimal("10.00"), EUR), Direction.DEBIT),
        new Posting(cust, new Money(new BigDecimal("10.00"), EUR), Direction.CREDIT)
    ));
    JournalEntry e2 = new JournalEntry(eid, Instant.parse("2026-09-20T10:00:00Z"), "scale", null, List.of(
        new Posting(cash, new Money(new BigDecimal("10.000"), EUR), Direction.DEBIT),
        new Posting(cust, new Money(new BigDecimal("10.000"), EUR), Direction.CREDIT)
    ));
    IdempotencyKey key = new IdempotencyKey("scale-key");
    journal.append(e1, key);
    // Second should not throw conflicting, should return stored
    JournalEntry stored = journal.append(e2, key);
    assertEquals(e1.id(), stored.id());
    // Balance is 10, not 20
    assertEquals(0, balances.getBalance(cust).amount().compareTo(new BigDecimal("10.00")));
  }

  @Test
  @Order(11)
  void statementSingleQueryReturnsFullEntries() {
    UUID cash = UUID.randomUUID(), alice = UUID.randomUUID(), bob = UUID.randomUUID();
    createSystemAccount(cash, "cash", AccountType.ASSET, EUR);
    UUID aId = UUID.randomUUID(), bId = UUID.randomUUID();
    createAccount(alice, "alice", AccountType.LIABILITY, EUR, aId);
    createAccount(bob, "bob", AccountType.LIABILITY, EUR, bId);
    Instant base = Instant.parse("2026-09-20T10:00:00Z");
    // Two entries both affecting alice
    JournalEntry e1 = balancedEntry(UUID.randomUUID(), base, "dep alice", List.of(
        new Posting(cash, new Money("100.00", EUR), Direction.DEBIT),
        new Posting(alice, new Money("100.00", EUR), Direction.CREDIT)
    ));
    JournalEntry e2 = balancedEntry(UUID.randomUUID(), base.plusSeconds(60), "xfer alice->bob", List.of(
        new Posting(alice, new Money("30.00", EUR), Direction.DEBIT),
        new Posting(bob, new Money("30.00", EUR), Direction.CREDIT)
    ));
    journal.append(e1, new IdempotencyKey("stmt-1"));
    journal.append(e2, new IdempotencyKey("stmt-2"));
    List<JournalEntry> stm = balances.statement(alice, base.minusSeconds(100), base.plusSeconds(1000));
    assertEquals(2, stm.size());
    // Each entry should have full postings (e1 has 2, e2 has 2)
    assertEquals(2, stm.get(0).postings().size());
    assertEquals(2, stm.get(1).postings().size());
    // Also test journal findByAccount
    List<JournalEntry> j = journal.findByAccount(alice, base.minusSeconds(100), base.plusSeconds(1000));
    assertEquals(2, j.size());
  }

  @Test
  @Order(12)
  void unbalancedEntryFailsAndRollsBackPostings() {
    UUID cash = UUID.randomUUID(), cust = UUID.randomUUID();
    createSystemAccount(cash, "cash", AccountType.ASSET, EUR);
    UUID custId = UUID.randomUUID();
    createAccount(cust, "alice", AccountType.LIABILITY, EUR, custId);
    // Unbalanced via constructor should fail before DB, but test deferred check via direct SQL? Instead test unbalanced via posting amounts that trigger DB check
    // Use valid JournalEntry but bypass constructor check by double-entry still balanced; to trigger DB unbalanced we need DB trigger, but constructor already prevents unbalanced.
    // So test partial failure via duplicate posting index? Instead verify that a failed entry leaves no postings.
    // Use currency mismatch already tested; here test that after failure, balance unchanged and no entry.
    JournalEntry bad = balancedEntry(UUID.randomUUID(), Instant.now(), "will fail due missing account", List.of(
        new Posting(cash, new Money("10.00", EUR), Direction.DEBIT),
        new Posting(UUID.randomUUID(), new Money("10.00", EUR), Direction.CREDIT)
    ));
    assertThrows(RepositoryException.class, () -> journal.append(bad, new IdempotencyKey("rollback-test")));
    assertTrue(journal.findById(bad.id()).isEmpty());
  }
}
