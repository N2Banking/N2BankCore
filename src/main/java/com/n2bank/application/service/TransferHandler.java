package com.n2bank.application.service;

import com.n2bank.application.command.*;
import com.n2bank.application.fee.FeeContext;
import com.n2bank.domain.model.*;
import java.util.*;
import java.util.function.Function;

/** Applies transfer rules inside the shared operation transaction. */
public final class TransferHandler {
  private final OperationExecutor executor;
  private final FeeService fees;
  private final Function<Currency, UUID> revenueAccounts;

  public TransferHandler(OperationExecutor executor, FeeService fees, Function<Currency, UUID> revenueAccounts) {
    this.executor = Objects.requireNonNull(executor);
    this.fees = Objects.requireNonNull(fees);
    this.revenueAccounts = Objects.requireNonNull(revenueAccounts);
  }

  public OperationResult handle(TransferCommand command) {
    Objects.requireNonNull(command);
    return executor.execute(command, accounts -> {
      Currency currency = command.amount().currency();
      Account source = HandlerSupport.require(accounts.apply(command.sourceAccountId()),
          AccountType.LIABILITY, currency, true);
      List<Posting> lines = new ArrayList<>();
      Account destination = HandlerSupport.require(accounts.apply(command.destinationAccountId()),
          AccountType.LIABILITY, currency, true);
      FeeQuote fee = fees.calculate(FeeType.TRANSFER, new FeeContext(command.amount(), source, Optional.of(destination)));
      Money received = command.amount().subtract(fee.amount());
      if (!received.isPositive()) throw new IllegalArgumentException("Transfer fee must be smaller than amount");
      lines.add(new Posting(source.accountId(), command.amount(), Direction.DEBIT));
      lines.add(new Posting(destination.accountId(), received, Direction.CREDIT));
      HandlerSupport.addFee(lines, fee.amount(), accounts, revenueAccounts);
      return HandlerSupport.entry(command, "Transfer with " + fee.description(), lines);
    });
  }
}
