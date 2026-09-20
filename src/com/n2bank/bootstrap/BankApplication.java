package com.n2bank.bootstrap;

import com.n2bank.application.service.AccountService;
import com.n2bank.application.service.BalanceService;
import com.n2bank.application.service.CustomerService;
import com.n2bank.application.service.PostingService;
import com.n2bank.domain.model.Account;
import com.n2bank.domain.model.AccountType;
import com.n2bank.domain.model.Customer;
import com.n2bank.domain.model.CustomerType;
import com.n2bank.domain.model.Direction;
import com.n2bank.domain.model.IdempotencyKey;
import com.n2bank.domain.model.JournalEntry;
import com.n2bank.domain.model.Money;
import com.n2bank.domain.model.Posting;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class BankApplication extends DbApplication {
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
      UUID.fromString("60000000-0000-0000-0000-000000000301");
  private static final UUID TRANSFER_ENTRY_ID =
      UUID.fromString("60000000-0000-0000-0000-000000000302");

  @Override
  protected void application(
      PostingService postingService,
      AccountService accountService,
      CustomerService customerService,
      BalanceService balanceService) {
    Currency euro = Currency.getInstance("EUR");

    Customer firstCustomer =
        customerService.ensureExists(
            new Customer(FIRST_CUSTOMER_ID, "Alice", CustomerType.PERSON));
    Customer secondCustomer =
        customerService.ensureExists(
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

    cashAccount = accountService.ensureExists(cashAccount);
    feeRevenueAccount = accountService.ensureExists(feeRevenueAccount);
    firstDepositAccount = accountService.ensureExists(firstDepositAccount);
    secondDepositAccount = accountService.ensureExists(secondDepositAccount);

    Money depositedCash = new Money("100.00", euro);
    Money depositFee = depositedCash.multiply("0.03");
    Money creditedDeposit = depositedCash.subtract(depositFee);

    JournalEntry deposit =
        new JournalEntry(
            DEPOSIT_ENTRY_ID,
            Instant.parse("2026-09-21T13:00:00Z"),
            "Alice deposits cash with a 3 percent bank fee",
            "customer-workflow-deposit",
            List.of(
                new Posting(cashAccount.accountId(), depositedCash, Direction.DEBIT),
                new Posting(firstDepositAccount.accountId(), creditedDeposit, Direction.CREDIT),
                new Posting(feeRevenueAccount.accountId(), depositFee, Direction.CREDIT)));

    JournalEntry storedDeposit =
        postingService.post(
            deposit, new IdempotencyKey("customer-workflow-deposit-v1"));

    Money transferredAmount = new Money("50.00", euro);
    Money transferFee = transferredAmount.multiply("0.01");
    Money receivedAmount = transferredAmount.subtract(transferFee);

    JournalEntry transfer =
        new JournalEntry(
            TRANSFER_ENTRY_ID,
            Instant.parse("2026-09-21T13:01:00Z"),
            "Alice sends money to Bob with a 1 percent bank fee",
            "customer-workflow-transfer",
            List.of(
                new Posting(
                    firstDepositAccount.accountId(), transferredAmount, Direction.DEBIT),
                new Posting(
                    secondDepositAccount.accountId(), receivedAmount, Direction.CREDIT),
                new Posting(feeRevenueAccount.accountId(), transferFee, Direction.CREDIT)));

    JournalEntry storedTransfer =
        postingService.post(
            transfer, new IdempotencyKey("customer-workflow-transfer-v1"));

    System.out.printf(
        "Workflow completed: deposit=%s, transfer=%s, depositFee=%s, transferFee=%s%n",
        storedDeposit.id(), storedTransfer.id(), depositFee, transferFee);
    System.out.printf(
        "Balances: Alice=%s, Bob=%s, bankCash=%s, feeRevenue=%s%n",
        balanceService.getBalance(firstDepositAccount.accountId()),
        balanceService.getBalance(secondDepositAccount.accountId()),
        balanceService.getBalance(cashAccount.accountId()),
        balanceService.getBalance(feeRevenueAccount.accountId()));
  }

}
