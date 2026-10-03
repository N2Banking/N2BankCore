package com.n2bank.application.fee;

import static org.junit.jupiter.api.Assertions.*;

import com.n2bank.domain.model.Account;
import com.n2bank.domain.model.AccountType;
import com.n2bank.domain.model.Customer;
import com.n2bank.domain.model.CustomerType;
import com.n2bank.domain.model.FeeQuote;
import com.n2bank.domain.model.FeeType;
import com.n2bank.domain.model.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FeePolicyTest {
  private static final Currency EUR = Currency.getInstance("EUR");
  private static final Currency USD = Currency.getInstance("USD");
  private static final Currency JPY = Currency.getInstance("JPY");

  private Account eurAccount() {
    Customer owner = new Customer(UUID.randomUUID(), "Alice", CustomerType.PERSON);
    return new Account(UUID.randomUUID(), "Alice", Optional.of(owner), AccountType.LIABILITY, EUR);
  }

  private FeeContext context(String amount, Currency currency) {
    Customer owner = new Customer(UUID.randomUUID(), "Alice", CustomerType.PERSON);
    Account account =
        new Account(UUID.randomUUID(), "Alice", Optional.of(owner), AccountType.LIABILITY, currency);
    return new FeeContext(new Money(amount, currency), account);
  }

  // FixedFeePolicy

  @Test
  void fixedFeeReturnsConfiguredAmount() {
    var policy = new FixedFeePolicy(FeeType.TRANSFER, new Money("1.50", EUR), "Flat transfer fee");
    FeeQuote quote = policy.calculate(context("125.00", EUR));
    assertEquals(FeeType.TRANSFER, quote.type());
    assertEquals(0, new BigDecimal("1.50").compareTo(quote.amount().amount()));
    assertEquals(EUR, quote.amount().currency());
    assertEquals(FeeType.TRANSFER, policy.type());
  }

  @Test
  void fixedFeeRejectsNegativeFeeAndBlankDescription() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new FixedFeePolicy(FeeType.TRANSFER, new Money("-1.00", EUR), "Negative"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FixedFeePolicy(FeeType.TRANSFER, new Money("1.00", EUR), "   "));
    assertThrows(
        NullPointerException.class,
        () -> new FixedFeePolicy(null, new Money("1.00", EUR), "desc"));
    assertThrows(
        NullPointerException.class, () -> new FixedFeePolicy(FeeType.TRANSFER, null, "desc"));
  }

  @Test
  void fixedFeeRejectsCurrencyMismatch() {
    var policy = new FixedFeePolicy(FeeType.TRANSFER, new Money("1.50", EUR), "Flat fee");
    assertThrows(IllegalArgumentException.class, () -> policy.calculate(context("125.00", USD)));
    assertThrows(NullPointerException.class, () -> policy.calculate(null));
  }

  @Test
  void fixedFeeAllowsZeroFee() {
    var policy = new FixedFeePolicy(FeeType.TRANSFER, Money.zero(EUR), "Free");
    FeeQuote quote = policy.calculate(context("125.00", EUR));
    assertTrue(quote.amount().isZero());
  }

  // PercentageFeePolicy

  @Test
  void percentageFeeIsOnePercentOfAmount() {
    var policy =
        new PercentageFeePolicy(
            FeeType.TRANSFER, new BigDecimal("0.01"), RoundingMode.HALF_UP, "One percent");
    FeeQuote quote = policy.calculate(context("125.00", EUR));
    assertEquals(0, new BigDecimal("1.25").compareTo(quote.amount().amount()));
    assertEquals(EUR, quote.amount().currency());
  }

  @Test
  void percentageFeeRoundingModesDifferOnRepeatingDecimals() {
    // 10.00 * 0.333 = 3.330 -> scale 2: HALF_UP gives 3.33, DOWN gives 3.33 too;
    // use a rate that lands exactly halfway: 10.00 * 0.0125 = 0.125.
    var halfUp =
        new PercentageFeePolicy(
            FeeType.TRANSFER, new BigDecimal("0.0125"), RoundingMode.HALF_UP, "fee");
    var down =
        new PercentageFeePolicy(
            FeeType.TRANSFER, new BigDecimal("0.0125"), RoundingMode.DOWN, "fee");
    assertEquals(
        0, new BigDecimal("0.13").compareTo(halfUp.calculate(context("10.00", EUR)).amount().amount()));
    assertEquals(
        0, new BigDecimal("0.12").compareTo(down.calculate(context("10.00", EUR)).amount().amount()));
  }

  @Test
  void percentageFeeUsesZeroScaleForYen() {
    var policy =
        new PercentageFeePolicy(
            FeeType.TRANSFER, new BigDecimal("0.01"), RoundingMode.HALF_UP, "One percent");
    FeeQuote quote = policy.calculate(context("1000", JPY));
    assertEquals(0, new BigDecimal("10").compareTo(quote.amount().amount()));
    assertEquals(0, quote.amount().amount().scale());
  }

  @Test
  void percentageFeeRejectsNegativeRateAndBlankDescription() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PercentageFeePolicy(
                FeeType.TRANSFER, new BigDecimal("-0.01"), RoundingMode.HALF_UP, "Negative"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PercentageFeePolicy(
                FeeType.TRANSFER, new BigDecimal("0.01"), RoundingMode.HALF_UP, "  "));
    assertThrows(
        NullPointerException.class,
        () -> new PercentageFeePolicy(null, BigDecimal.ONE, RoundingMode.HALF_UP, "desc"));
    assertThrows(
        NullPointerException.class,
        () ->
            new PercentageFeePolicy(FeeType.TRANSFER, null, RoundingMode.HALF_UP, "desc"));
  }

  @Test
  void percentageFeeRejectsNullContext() {
    var policy =
        new PercentageFeePolicy(
            FeeType.TRANSFER, new BigDecimal("0.01"), RoundingMode.HALF_UP, "One percent");
    assertThrows(NullPointerException.class, () -> policy.calculate(null));
  }

  // NoFeePolicy

  @Test
  void noFeeReturnsZeroInOperationCurrency() {
    var policy = new NoFeePolicy(FeeType.TRANSFER);
    FeeQuote quote = policy.calculate(context("125.00", EUR));
    assertTrue(quote.amount().isZero());
    assertEquals(EUR, quote.amount().currency());
    assertEquals(FeeType.TRANSFER, policy.type());
  }

  // FeeContext validation

  @Test
  void feeContextRejectsNonPositiveAmountAndCurrencyMismatch() {
    Account eur = eurAccount();
    assertThrows(
        IllegalArgumentException.class,
        () -> new FeeContext(new Money("0.00", EUR), eur));
    assertThrows(
        IllegalArgumentException.class, () -> new FeeContext(new Money("-5.00", EUR), eur));
    Customer owner = new Customer(UUID.randomUUID(), "Bob", CustomerType.PERSON);
    Account usd =
        new Account(UUID.randomUUID(), "Bob", Optional.of(owner), AccountType.LIABILITY, USD);
    assertThrows(
        IllegalArgumentException.class, () -> new FeeContext(new Money("10.00", EUR), usd));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FeeContext(new Money("10.00", EUR), eur, Optional.of(usd)));
    assertThrows(NullPointerException.class, () -> new FeeContext(null, eur));
    assertThrows(
        NullPointerException.class, () -> new FeeContext(new Money("10.00", EUR), null));
  }

  // FeeQuote validation

  @Test
  void feeQuoteRejectsNegativeAmountAndBlankDescription() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new FeeQuote(FeeType.TRANSFER, new Money("-1.00", EUR), "Negative"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new FeeQuote(FeeType.TRANSFER, new Money("1.00", EUR), "  "));
    assertThrows(
        NullPointerException.class,
        () -> new FeeQuote(null, new Money("1.00", EUR), "desc"));
  }
}
