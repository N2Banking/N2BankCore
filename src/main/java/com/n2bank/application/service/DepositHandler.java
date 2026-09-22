package com.n2bank.application.service;

import com.n2bank.application.command.*;
import com.n2bank.application.fee.FeeContext;
import com.n2bank.domain.model.*;
import java.util.*;
import java.util.function.Function;

/** Applies deposit rules inside the shared operation transaction. */
public final class DepositHandler {
  private final OperationExecutor executor;
  private final FeeService fees;
  private final Function<Currency, UUID> revenueAccounts;
  private final Function<Currency, UUID> cashAccounts;

  public DepositHandler(OperationExecutor executor, FeeService fees, Function<Currency, UUID> revenueAccounts, Function<Currency, UUID> cashAccounts) {
    this.executor = Objects.requireNonNull(executor);
    this.fees = Objects.requireNonNull(fees);
    this.revenueAccounts = Objects.requireNonNull(revenueAccounts);
    this.cashAccounts = Objects.requireNonNull(cashAccounts);
  }

  public OperationResult handle(DepositCommand command) {
    Objects.requireNonNull(command);
    return executor.execute(command, accounts -> {
      Currency currency = command.amount().currency();
      Account source = HandlerSupport.require(accounts.apply(command.accountId()),
          AccountType.LIABILITY, currency, true);
      List<Posting> lines = new ArrayList<>();
      Account cash = HandlerSupport.require(accounts.apply(cashAccounts.apply(currency)),
          AccountType.ASSET, currency, false);
      FeeQuote fee = fees.calculate(FeeType.CASH_DEPOSIT, new FeeContext(command.amount(), source));
      Money credited = command.amount().subtract(fee.amount());
      if (!credited.isPositive()) throw new IllegalArgumentException("Deposit fee must be smaller than amount");
      lines.add(new Posting(cash.accountId(), command.amount(), Direction.DEBIT));
      lines.add(new Posting(source.accountId(), credited, Direction.CREDIT));
      HandlerSupport.addFee(lines, fee.amount(), accounts, revenueAccounts);
      return HandlerSupport.entry(command, "Deposit with " + fee.description(), lines);
    });
  }
}
