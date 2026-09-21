package com.n2bank.bootstrap;

import com.n2bank.application.service.AccountService;
import com.n2bank.application.service.BalanceService;
import com.n2bank.application.service.CustomerService;
import com.n2bank.application.service.PostingService;

/** Production application entry point. Add the bank's real workflow here as it is developed. */
public class BankApplication extends DbApplication {

  @Override
  protected void application(
      PostingService postingService,
      AccountService accountService,
      CustomerService customerService,
      BalanceService balanceService) {
    // Production workflow intentionally starts empty.
  }
}
