package com.n2bank.bootstrap;

import com.n2bank.application.fee.FeePolicy;
import com.n2bank.application.fee.PercentageFeePolicy;
import com.n2bank.application.service.AccountService;
import com.n2bank.application.service.BalanceService;
import com.n2bank.application.service.CustomerService;
import com.n2bank.application.service.PostingService;
import com.n2bank.domain.model.Account;
import com.n2bank.domain.model.AccountType;
import com.n2bank.domain.model.Customer;
import com.n2bank.domain.model.CustomerType;
import com.n2bank.domain.model.FeeType;
import com.n2bank.domain.model.IdempotencyKey;
import com.n2bank.domain.model.JournalEntry;
import com.n2bank.domain.model.Money;
import java.time.Instant;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Executable integration workflow for manually testing the bank engine. */
public final class TestBankApplication extends BankApplication {
  private static final UUID CASH_ACCOUNT_ID =
      UUID.fromString("db644a31-e5f1-4025-8532-31ff78c7add0");
  private static final UUID FEE_REVENUE_ACCOUNT_ID =
      UUID.fromString("60000000-0000-0000-0000-000000000001");
  private static final UUID FIRST_CUSTOMER_ID =
      UUID.fromString("60000000-0000-0000-0000-000000000101");
  private static final UUID SECOND_CUSTOMER_ID =
      UUID.fromString("60000000-0000-0000-0000-000000000102");
  private static final UUID FIRST_DEPOSIT_ACCOUNT_ID =
      UUID.fromString("60000000-0000-0000-0000-000000000201");
  private static final UUID SECOND_DEPOSIT_ACCOUNT_ID =
      UUID.fromString("60000000-0000-0000-0000-000000000202");
  private static final UUID DEPOSIT_ENTRY_ID =
      UUID.fromString("60000000-0000-0000-0000-000000000401");
  private static final UUID TRANSFER_ENTRY_ID =
      UUID.fromString("60000000-0000-0000-0000-000000000402");

  @Override
  protected boolean rollbackDatabaseChanges() {
    return true;
  }

  @Override
  protected List<FeePolicy> feePolicies() {
    return List.of(
        new PercentageFeePolicy(
            FeeType.CASH_DEPOSIT,
            new BigDecimal("0.03"),
            RoundingMode.HALF_EVEN,
            "3 percent bank fee"),
        new PercentageFeePolicy(
            FeeType.TRANSFER,
            new BigDecimal("0.01"),
            RoundingMode.HALF_EVEN,
            "1 percent bank fee"));
  }

  @Override
  protected UUID bankCashAccountId(Currency currency) {
    return CASH_ACCOUNT_ID;
  }

  @Override
  protected UUID feeRevenueAccountId(Currency currency) {
    return FEE_REVENUE_ACCOUNT_ID;
  }

  @Override
  protected void application(
      PostingService postingService,
      AccountService accountService,
      CustomerService customerService,
      BalanceService balanceService) {
    Currency euro = Currency.getInstance("EUR");

    Customer firstCustomer =
        createCustomer(
            new Customer(FIRST_CUSTOMER_ID, "Alice", CustomerType.PERSON));
    Customer secondCustomer =
        createCustomer(
            new Customer(SECOND_CUSTOMER_ID, "Bob", CustomerType.PERSON));

    Account cashAccount =
        new Account(CASH_ACCOUNT_ID, "Bank cash", Optional.empty(), AccountType.ASSET, euro);
    Account feeRevenueAccount =
        new Account(
            FEE_REVENUE_ACCOUNT_ID,
            "Bank fee revenue",
            Optional.empty(),
            AccountType.REVENUE,
            euro);
    Account firstDepositAccount =
        new Account(
            FIRST_DEPOSIT_ACCOUNT_ID,
            "Alice deposit account",
            Optional.of(firstCustomer),
            AccountType.LIABILITY,
            euro);
    Account secondDepositAccount =
        new Account(
            SECOND_DEPOSIT_ACCOUNT_ID,
            "Bob deposit account",
            Optional.of(secondCustomer),
            AccountType.LIABILITY,
            euro);

    cashAccount = openAccount(cashAccount);
    feeRevenueAccount = openAccount(feeRevenueAccount);
    firstDepositAccount = openAccount(firstDepositAccount);
    secondDepositAccount = openAccount(secondDepositAccount);

    Money depositedCash = new Money("100.00", euro);
    JournalEntry storedDeposit =
        deposit(
            firstDepositAccount.accountId(),
            depositedCash,
            new OperationMetadata(
                DEPOSIT_ENTRY_ID,
                Instant.parse("2026-09-21T13:00:00Z"),
                "customer-workflow-deposit",
                new IdempotencyKey("bank-facade-deposit-v1")));

    Money transferredAmount = new Money("50.00", euro);
    JournalEntry storedTransfer =
        transfer(
            firstDepositAccount.accountId(),
            secondDepositAccount.accountId(),
            transferredAmount,
            new OperationMetadata(
                TRANSFER_ENTRY_ID,
                Instant.parse("2026-09-21T13:01:00Z"),
                "customer-workflow-transfer",
                new IdempotencyKey("bank-facade-transfer-v1")));

    Money depositFee = depositedCash.subtract(storedDeposit.postings().get(1).amount());
    Money transferFee = storedTransfer.postings().get(2).amount();

    System.out.printf(
        "Workflow completed: deposit=%s, transfer=%s, depositFee=%s, transferFee=%s%n",
        storedDeposit.id(), storedTransfer.id(), depositFee, transferFee);
    System.out.printf(
        "Balances: Alice=%s, Bob=%s, bankCash=%s, feeRevenue=%s%n",
        getBalance(firstDepositAccount.accountId()),
        getBalance(secondDepositAccount.accountId()),
        getBalance(cashAccount.accountId()),
        getBalance(feeRevenueAccount.accountId()));
    System.out.printf(
        "Cached balance check: Alice=%s%n",
        getBalance(firstDepositAccount.accountId()));
  }
}
