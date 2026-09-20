package com.n2bank.bootstrap;

import com.n2bank.application.port.AccountRepository;
import com.n2bank.application.service.PostingService;

public class BankApplication extends DbApplication {

  @Override
  protected void application(PostingService postingService, AccountRepository accounts) {

  }
}
