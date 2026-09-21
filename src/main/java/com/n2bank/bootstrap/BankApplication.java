package com.n2bank.bootstrap;

import com.n2bank.application.fee.FeeContext;
import com.n2bank.application.fee.FeePolicy;
import com.n2bank.application.fee.NoFeePolicy;
import com.n2bank.application.port.AccountRepository;
import com.n2bank.application.port.BalanceCache;
import com.n2bank.application.port.BalanceRepository;
import com.n2bank.application.port.JournalEntryRepository;
import com.n2bank.application.service.*;
import com.n2bank.domain.model.*;
import com.n2bank.infrastructure.database.DBConfig;
import com.n2bank.infrastructure.database.DBHandler;
import com.n2bank.infrastructure.postgres.PostgresAccountRepository;
import com.n2bank.infrastructure.postgres.PostgresBalanceRepository;
import com.n2bank.infrastructure.postgres.PostgresCustomerRepository;
import com.n2bank.infrastructure.postgres.PostgresJournalEntryRepository;
import com.n2bank.infrastructure.redis.RedisBalanceCache;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, process-wide facade for the bank engine.
 *
 * <p>The hosting API owns its lifecycle: call {@link #initialize(DBConfig, Collection)} once at
 * startup, use {@link #getInstance()} in request handlers, and call {@link #close()} during
 * shutdown.
 */
public final class BankApplication implements AutoCloseable {
  private static final Object LIFECYCLE_LOCK = new Object();
  private static volatile BankApplication instance;

  private final Map<Currency, UUID> cashAccounts = new ConcurrentHashMap<>();
  private final Map<Currency, UUID> revenueAccounts = new ConcurrentHashMap<>();
  private final DBHandler database;
  private final PostingService postings;
  private final AccountService accounts;
  private final CustomerService customers;
  private final BalanceService balances;
  private final FeeService fees;
  private volatile boolean closed;

  private BankApplication(DBConfig config, Collection<? extends FeePolicy> feePolicies) {
    database =
        new DBHandler(Objects.requireNonNull(config, "Database configuration cannot be null"));
    try {
      AccountRepository accountRepository = new PostgresAccountRepository(database.postgres());
      JournalEntryRepository journalRepository =
          new PostgresJournalEntryRepository(database.postgres());
      BalanceRepository balanceRepository = new PostgresBalanceRepository(database.postgres());
      BalanceCache balanceCache = new RedisBalanceCache(database.redis());

      accounts = new AccountService(accountRepository);
      customers = new CustomerService(new PostgresCustomerRepository(database.postgres()));
      balances = new BalanceService(balanceRepository, balanceCache);
      postings = new PostingService(journalRepository, balanceCache);
      fees =
          new FeeService(
              List.copyOf(
                  Objects.requireNonNull(feePolicies, "Fee policies cannot be null")));
    } catch (RuntimeException exception) {
      database.close();
      throw exception;
    }
  }

  /** Initializes and returns the single bank engine for this process. */
  public static BankApplication initialize(
      DBConfig config, Collection<? extends FeePolicy> feePolicies) {
    synchronized (LIFECYCLE_LOCK) {
      if (instance != null) {
        throw new IllegalStateException("Bank application is already initialized");
      }
      instance = new BankApplication(config, feePolicies);
      return instance;
    }
  }

  /** Initializes the bank engine from environment variables with no transaction fees. */
  public static BankApplication initializeFromEnvironment() {
    return initialize(
        DBConfig.fromEnvironment(),
        Arrays.stream(FeeType.values()).map(NoFeePolicy::new).toList());
  }

  /** Returns the initialized process-wide bank engine. */
  public static BankApplication getInstance() {
    BankApplication current = instance;
    if (current == null || current.closed) {
      throw new IllegalStateException("Bank application has not been initialized");
    }
    return current;
  }

  public static boolean isInitialized() {
    BankApplication current = instance;
    return current != null && !current.closed;
  }

  public Customer createCustomer(Customer customer) {
    return customerService().ensureExists(customer);
  }

  public Account openAccount(Account account) {
    return accountService().ensureExists(account);
  }

  public Account getAccount(UUID accountId) {
    return accountService().getRequired(accountId);
  }

  public Money getBalance(UUID accountId) {
    return balanceService().getBalance(accountId);
  }

  public java.util.Map<String, Money> trialBalance() {
    return balanceService().trialBalance();
  }

  public java.util.List<JournalEntry> statement(
      UUID accountId, java.time.Instant from, java.time.Instant to) {
    return balanceService().statement(accountId, from, to);
  }

  public JournalEntry deposit(UUID accountId, Money amount, OperationMetadata metadata) {
    requirePositive(amount);
    Account customer = requireCustomerAccount(accountId, amount.currency());
    Account cash =
        requireSystemAccount(
            bankCashAccountId(amount.currency()), AccountType.ASSET, amount.currency());
    Account revenue =
        requireSystemAccount(
            feeRevenueAccountId(amount.currency()), AccountType.REVENUE, amount.currency());
    FeeQuote fee = feeService().calculate(FeeType.CASH_DEPOSIT, new FeeContext(amount, customer));
    Money credited = amount.subtract(fee.amount());
    if (!credited.isPositive())
      throw new IllegalArgumentException("Deposit fee must be smaller than the amount");

    List<Posting> lines = new ArrayList<>();
    lines.add(new Posting(cash.accountId(), amount, Direction.DEBIT));
    lines.add(new Posting(customer.accountId(), credited, Direction.CREDIT));
    addFee(lines, revenue, fee.amount());
    return post(
        metadata,
        customer.owner().orElseThrow().name() + " deposits cash with a " + fee.description(),
        lines);
  }

  /** The transfer amount is the total sender debit; the receiver obtains amount minus fee. */
  public JournalEntry transfer(
      UUID sourceAccountId, UUID destinationAccountId, Money amount, OperationMetadata metadata) {
    requirePositive(amount);
    if (sourceAccountId.equals(destinationAccountId)) {
      throw new IllegalArgumentException("Source and destination accounts must be different");
    }
    Account source = requireCustomerAccount(sourceAccountId, amount.currency());
    Account destination = requireCustomerAccount(destinationAccountId, amount.currency());
    Account revenue =
        requireSystemAccount(
            feeRevenueAccountId(amount.currency()), AccountType.REVENUE, amount.currency());
    FeeQuote fee =
        feeService()
            .calculate(FeeType.TRANSFER, new FeeContext(amount, source, Optional.of(destination)));
    Money received = amount.subtract(fee.amount());
    if (!received.isPositive())
      throw new IllegalArgumentException("Transfer fee must be smaller than the amount");
    requireFunds(source.accountId(), amount);

    List<Posting> lines = new ArrayList<>();
    lines.add(new Posting(source.accountId(), amount, Direction.DEBIT));
    lines.add(new Posting(destination.accountId(), received, Direction.CREDIT));
    addFee(lines, revenue, fee.amount());
    return post(
        metadata,
        source.owner().orElseThrow().name()
            + " sends money to "
            + destination.owner().orElseThrow().name()
            + " with a "
            + fee.description(),
        lines);
  }

  public JournalEntry withdraw(UUID accountId, Money amount, OperationMetadata metadata) {
    requirePositive(amount);
    Account customer = requireCustomerAccount(accountId, amount.currency());
    Account cash =
        requireSystemAccount(
            bankCashAccountId(amount.currency()), AccountType.ASSET, amount.currency());
    Account revenue =
        requireSystemAccount(
            feeRevenueAccountId(amount.currency()), AccountType.REVENUE, amount.currency());
    FeeQuote fee = feeService().calculate(FeeType.WITHDRAWAL, new FeeContext(amount, customer));
    Money total = amount.add(fee.amount());
    requireFunds(customer.accountId(), total);

    List<Posting> lines = new ArrayList<>();
    lines.add(new Posting(customer.accountId(), total, Direction.DEBIT));
    lines.add(new Posting(cash.accountId(), amount, Direction.CREDIT));
    addFee(lines, revenue, fee.amount());
    return post(metadata, "Cash withdrawal with " + fee.description(), lines);
  }

  public JournalEntry chargeFee(
      UUID accountId, Money fee, String description, OperationMetadata metadata) {
    requirePositive(fee);
    Account customer = requireCustomerAccount(accountId, fee.currency());
    Account revenue =
        requireSystemAccount(
            feeRevenueAccountId(fee.currency()), AccountType.REVENUE, fee.currency());
    requireFunds(customer.accountId(), fee);
    return post(
        metadata,
        description,
        List.of(
            new Posting(customer.accountId(), fee, Direction.DEBIT),
            new Posting(revenue.accountId(), fee, Direction.CREDIT)));
  }

  /**
   * Registers the bank-owned accounts for a currency. The hosting API must do this during startup,
   * before accepting posting requests for that currency.
   */
  public void registerSystemAccounts(
      Currency currency, UUID cashAccountId, UUID revenueAccountId) {
    requireInitialized();
    Objects.requireNonNull(currency, "Currency cannot be null");
    Objects.requireNonNull(cashAccountId, "Cash account ID cannot be null");
    Objects.requireNonNull(revenueAccountId, "Revenue account ID cannot be null");
    cashAccounts.put(currency, cashAccountId);
    revenueAccounts.put(currency, revenueAccountId);
  }

  /**
   * Ensures the bank-owned cash/revenue accounts exist for the currency. Idempotent via {@link
   * AccountService#ensureExists}.
   */
  public void ensureSystemAccounts(
      Currency currency, String cashName, String revenueName) {
    requireInitialized();
    Objects.requireNonNull(currency, "Currency cannot be null");
    UUID cashId = bankCashAccountId(currency);
    UUID revenueId = feeRevenueAccountId(currency);
    openAccount(new Account(cashId, cashName, Optional.empty(), AccountType.ASSET, currency));
    openAccount(
        new Account(revenueId, revenueName, Optional.empty(), AccountType.REVENUE, currency));
  }

  /** Maps a currency to the bank-owned cash asset account. */
  private UUID bankCashAccountId(Currency currency) {
    UUID configured = cashAccounts.get(currency);
    if (configured != null) return configured;
    throw new IllegalStateException(
        "No bank cash account configured for "
            + currency
            + ". Call registerSystemAccounts() in servicesReady().");
  }

  /** Maps a currency to the bank-owned fee revenue account. */
  private UUID feeRevenueAccountId(Currency currency) {
    UUID configured = revenueAccounts.get(currency);
    if (configured != null) return configured;
    throw new IllegalStateException(
        "No bank fee revenue account configured for "
            + currency
            + ". Call registerSystemAccounts() in servicesReady().");
  }

  /** Append-only reversal: creates a new entry with every posting direction flipped. */
  public JournalEntry reverse(JournalEntry original, OperationMetadata metadata) {
    Objects.requireNonNull(original, "Original entry cannot be null");
    List<Posting> reversed =
        original.postings().stream()
            .map(
                p ->
                    new Posting(
                        p.accountId(),
                        p.amount(),
                        p.direction() == Direction.DEBIT ? Direction.CREDIT : Direction.DEBIT))
            .toList();
    return post(
        metadata, "Reversal of " + original.id() + " - " + original.description(), reversed);
  }

  private PostingService postingService() {
    requireInitialized();
    return postings;
  }

  private FeeService feeService() {
    requireInitialized();
    return fees;
  }

  private JournalEntry post(OperationMetadata metadata, String description, List<Posting> lines) {
    Objects.requireNonNull(metadata, "Operation metadata cannot be null");
    JournalEntry entry =
        new JournalEntry(
            metadata.journalEntryId(),
            metadata.effectiveAt(),
            description,
            metadata.externalReference(),
            lines);
    return postingService().post(entry, metadata.idempotencyKey());
  }

  private Account requireCustomerAccount(UUID id, Currency currency) {
    Account account = accountService().getRequired(id);
    if (account.type() != AccountType.LIABILITY || account.owner().isEmpty()) {
      throw new IllegalArgumentException("Account " + id + " is not a customer liability account");
    }
    requireCurrency(account, currency);
    return account;
  }

  private Account requireSystemAccount(UUID id, AccountType type, Currency currency) {
    Account account = accountService().getRequired(id);
    if (account.type() != type || account.owner().isPresent()) {
      throw new IllegalStateException(
          "Configured account " + id + " is not a bank " + type + " account");
    }
    requireCurrency(account, currency);
    return account;
  }

  private void requireCurrency(Account account, Currency currency) {
    if (!account.currency().equals(currency)) {
      throw new IllegalArgumentException(
          "Account " + account.accountId() + " uses another currency");
    }
  }

  private void requireFunds(UUID accountId, Money required) {
    Money available = getBalance(accountId);
    if (!Money.isSameCurrency(available, required)
        || available.amount().compareTo(required.amount()) < 0) {
      throw new IllegalStateException("Insufficient funds in account " + accountId);
    }
  }

  private void addFee(List<Posting> lines, Account revenue, Money fee) {
    if (!fee.isZero()) lines.add(new Posting(revenue.accountId(), fee, Direction.CREDIT));
  }

  private void requirePositive(Money amount) {
    Objects.requireNonNull(amount, "Amount cannot be null");
    if (!amount.isPositive())
      throw new IllegalArgumentException("Amount must be greater than zero");
  }

  private AccountService accountService() {
    requireInitialized();
    return accounts;
  }

  private CustomerService customerService() {
    requireInitialized();
    return customers;
  }

  private BalanceService balanceService() {
    requireInitialized();
    return balances;
  }

  private void requireInitialized() {
    if (closed) throw new IllegalStateException("Bank application is closed");
  }

  /** Releases database/cache clients and allows a later initialization in the same process. */
  @Override
  public void close() {
    synchronized (LIFECYCLE_LOCK) {
      if (closed) return;
      closed = true;
      if (instance == this) instance = null;
      database.close();
    }
  }

  /** Stable identity that callers must reuse unchanged when retrying an operation. */
  public record OperationMetadata(
      UUID journalEntryId,
      Instant effectiveAt,
      String externalReference,
      IdempotencyKey idempotencyKey) {
    public OperationMetadata {
      Objects.requireNonNull(journalEntryId, "Journal entry ID cannot be null");
      Objects.requireNonNull(effectiveAt, "Effective time cannot be null");
      Objects.requireNonNull(idempotencyKey, "Idempotency key cannot be null");
      if (externalReference != null) externalReference = externalReference.strip();
    }
  }
}
