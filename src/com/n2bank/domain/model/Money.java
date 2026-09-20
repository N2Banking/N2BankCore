package com.n2bank.domain.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.NumberFormat;
import java.util.*;

public record Money(BigDecimal amount, Currency currency) {
  public Money {
    Objects.requireNonNull(amount, "Amount cannot be null");
    Objects.requireNonNull(currency, "Currency cannot be null");
  }

  public Money(BigDecimal amount, String currencyCode) {
    this(amount, currency(currencyCode));
  }

  public Money(String amount, String currencyCode) {
    this(decimal(amount), currency(currencyCode));
  }

  public Money(String amount, Currency currency) {
    this(decimal(amount), currency);
  }

  public Money(long amount, String currencyCode) {
    this(BigDecimal.valueOf(amount), currency(currencyCode));
  }

  public Money(long amount, Currency currency) {
    this(BigDecimal.valueOf(amount), currency);
  }

  public static boolean isSameCurrency(Money first, Money second) {
    Objects.requireNonNull(first, "First money cannot be null");
    Objects.requireNonNull(second, "Second money cannot be null");
    return first.currency.equals(second.currency);
  }

  private static Currency currency(String currencyCode) {
    Objects.requireNonNull(currencyCode, "Currency code cannot be null");
    try {
      return Currency.getInstance(currencyCode.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(
          "Unknown currency code: '" + currencyCode + "'", exception);
    }
  }

  private static BigDecimal decimal(String value) {
    Objects.requireNonNull(value, "Decimal value cannot be null");
    try {
      return new BigDecimal(value.trim());
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("Invalid decimal value: '" + value + "'", exception);
    }
  }

  public static Money zero(Currency currency) {
    return new Money(BigDecimal.ZERO, currency);
  }

  public static boolean isZero(Money money) {
    Objects.requireNonNull(money, "Money cannot be null");
    return money.amount.compareTo(BigDecimal.ZERO) == 0;
  }

  public boolean isZero() {
    return isZero(this);
  }

  public Money add(Money other) {
    Objects.requireNonNull(other, "Other money cannot be null");
    if (!isSameCurrency(this, other)) {
      throw new IllegalArgumentException("Cannot add different currencies together");
    }

    return new Money(amount.add(other.amount), currency);
  }

  public Money subtract(Money other) {
    Objects.requireNonNull(other, "Other money cannot be null");
    return add(other.negate());
  }

  public Money multiply(BigDecimal multiplier) {
    Objects.requireNonNull(multiplier, "Multiplier cannot be null");
    return new Money(amount.multiply(multiplier), currency);
  }

  public Money multiply(long multiplier) {
    return multiply(BigDecimal.valueOf(multiplier));
  }

  public Money multiply(String multiplier) {
    return multiply(decimal(multiplier));
  }

  public DivisionResult divideAndRemainder(BigDecimal divisor) {
    Objects.requireNonNull(divisor, "Divisor cannot be null");
    BigInteger wholeDivisor;
    try {
      wholeDivisor = divisor.toBigIntegerExact();
    } catch (ArithmeticException exception) {
      throw new IllegalArgumentException(
          "A monetary allocation requires a whole-number divisor: " + divisor, exception);
    }
    return divideAndRemainder(wholeDivisor);
  }

  public DivisionResult divideAndRemainder(BigInteger divisor) {
    Objects.requireNonNull(divisor, "Divisor cannot be null");
    if (divisor.signum() == 0) {
      throw new ArithmeticException("Cannot divide money by zero");
    }

    int scale = Math.max(Math.max(currency.getDefaultFractionDigits(), 0), amount.scale());
    BigInteger smallestUnits = amount.movePointRight(scale).toBigIntegerExact();
    BigInteger[] result = smallestUnits.divideAndRemainder(divisor);

    return new DivisionResult(
        new Money(new BigDecimal(result[0], scale), currency),
        new Money(new BigDecimal(result[1], scale), currency));
  }

  public DivisionResult divideAndRemainder(long divisor) {
    return divideAndRemainder(BigInteger.valueOf(divisor));
  }

  public DivisionResult divideAndRemainder(String divisor) {
    return divideAndRemainder(decimal(divisor));
  }

  public Money negate() {
    return new Money(amount.negate(), currency);
  }

  public boolean isPositive() {
    return amount.signum() > 0;
  }

  public String format(Locale locale) {
    Objects.requireNonNull(locale, "Locale cannot be null");

    NumberFormat formatter = NumberFormat.getCurrencyInstance(locale);
    formatter.setCurrency(currency);
    int currencyScale = Math.max(currency.getDefaultFractionDigits(), 0);
    formatter.setMinimumFractionDigits(currencyScale);
    formatter.setMaximumFractionDigits(Math.max(currencyScale, Math.max(amount.scale(), 0)));

    return formatter.format(amount);
  }

  @Override
  public String toString() {
    return format(Locale.getDefault(Locale.Category.FORMAT));
  }

  public record DivisionResult(Money quotient, Money remainder) {
    public DivisionResult {
      Objects.requireNonNull(quotient, "Quotient cannot be null");
      Objects.requireNonNull(remainder, "Remainder cannot be null");
    }
  }
}
