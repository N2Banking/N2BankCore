package com.n2bank.bootstrap;

public final class Main {
  private Main() {}

  static void main() {
    BankApplication app = new TestBankApplication();
    app.run();
  }
}
