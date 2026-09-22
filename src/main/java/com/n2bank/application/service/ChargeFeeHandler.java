package com.n2bank.application.service;

import com.n2bank.application.command.*;
import com.n2bank.application.fee.FeeContext;
import com.n2bank.domain.model.*;
import java.util.*;
import java.util.function.Function;

/** Applies chargefee rules inside the shared operation transaction. */
public final class ChargeFeeHandler {
  private final OperationExecutor executor;
  private final Function<Currency, UUID> revenueAccounts;

  public ChargeFeeHandler(OperationExecutor executor, Function<Currency, UUID> revenueAccounts) {
    this.executor = Objects.requireNonNull(executor);
    this.revenueAccounts = Objects.requireNonNull(revenueAccounts);
  }

  public OperationResult handle(ChargeFeeCommand command) {
    Objects.requireNonNull(command);
    return executor.execute(command, accounts -> {
      Currency currency = command.amount().currency();
      Account source = HandlerSupport.require(accounts.apply(command.accountId()),
          AccountType.LIABILITY, currency, true);
      List<Posting> lines = new ArrayList<>();
      lines.add(new Posting(source.accountId(), command.amount(), Direction.DEBIT));
      HandlerSupport.addFee(lines, command.amount(), accounts, revenueAccounts);
      return HandlerSupport.entry(command, command.description(), lines);
    });
  }
}
