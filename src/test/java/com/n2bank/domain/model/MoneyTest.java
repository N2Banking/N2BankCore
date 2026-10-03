package com.n2bank.domain.model;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Currency;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class MoneyTest {
  private static final Currency EUR = Currency.getInstance("EUR");
  private static final Currency USD = Currency.getInstance("USD");
  private static final Currency JPY = Currency.getInstance("JPY");

  @Test
  void rejectsNullAmountAndCurrency() {
    assertThrows(NullPointerException.class, () -> new Money((BigDecimal) null, EUR));
    assertThrows(NullPointerException.class, () -> new Money(BigDecimal.ONE, (Currency) null));
    assertThrows(NullPointerException.class, () -> new Money((String) null, "EUR"));
    assertThrows(NullPointerException.class, () -> new Money("10.00", (String) null));
    assertThrows(NullPointerException.class, () -> new Money("10.00", (Currency) null));
  }

  @Test
  void rejectsUnknownCurrencyCodeAndInvalidDecimal() {
    assertThrows(IllegalArgumentException.class, () -> new Money("10.00", "XXQ"));
    assertThrows(IllegalArgumentException.class, () -> new Money("not-a-number", "EUR"));
    assertThrows(NullPointerException.class, () -> new Money("10.00", (String) null));
  }

  @Test
  void currencyCodeIsTrimmedAndCaseInsensitive() {
    assertEquals(EUR, new Money("10.00", "eur").currency());
    assertEquals(EUR, new Money("10.00", "  Eur  ").currency());
  }

  @Test
  void additionRequiresSameCurrency() {
    Money eur10 = new Money("10.00", EUR);
    Money usd5 = new Money("5.00", USD);
    assertEquals(new BigDecimal("15.00"), eur10.add(new Money("5.00", EUR)).amount());
    assertThrows(IllegalArgumentException.class, () -> eur10.add(usd5));
    assertThrows(NullPointerException.class, () -> eur10.add(null));
  }

  @Test
  void subtractionRequiresSameCurrency() {
    Money eur10 = new Money("10.00", EUR);
    assertEquals(new BigDecimal("7.50"), eur10.subtract(new Money("2.50", EUR)).amount());
    assertThrows(IllegalArgumentException.class, () -> eur10.subtract(new Money("1.00", USD)));
    assertThrows(NullPointerException.class, () -> eur10.subtract(null));
  }

  @Test
  void zeroAndSignChecks() {
    assertTrue(new Money("0.00", EUR).isZero());
    assertTrue(Money.isZero(new Money("0", EUR)));
    assertFalse(new Money("0.01", EUR).isZero());
    assertTrue(new Money("0.01", EUR).isPositive());
    assertFalse(new Money("0.00", EUR).isPositive());
    assertFalse(new Money("-0.01", EUR).isPositive());
    assertThrows(NullPointerException.class, () -> Money.isZero(null));
  }

  @Test
  void zeroFactoryUsesGivenCurrency() {
    assertEquals(EUR, Money.zero(EUR).currency());
    assertTrue(Money.zero(USD).isZero());
  }

  @Test
  void isSameCurrencyRequiresBothNonNull() {
    assertTrue(Money.isSameCurrency(new Money("1.00", EUR), new Money("2.00", EUR)));
    assertFalse(Money.isSameCurrency(new Money("1.00", EUR), new Money("2.00", USD)));
    assertThrows(NullPointerException.class, () -> Money.isSameCurrency(null, new Money("1", EUR)));
    assertThrows(NullPointerException.class, () -> Money.isSameCurrency(new Money("1", EUR), null));
  }

  @Test
  void multiplyPreservesCurrency() {
    Money result = new Money("12.50", EUR).multiply(new BigDecimal("2"));
    assertEquals(EUR, result.currency());
    assertEquals(0, new BigDecimal("25.00").compareTo(result.amount()));
    assertEquals(0, new BigDecimal("25.00").compareTo(new Money("12.50", EUR).multiply(2).amount()));
    assertThrows(NullPointerException.class, () -> new Money("1.00", EUR).multiply((BigDecimal) null));
  }

  @Test
  void negateFlipsSign() {
    assertEquals(0, new BigDecimal("-10.00").compareTo(new Money("10.00", EUR).negate().amount()));
  }

  @Test
  void divideAndRemainderRequiresWholeNonZeroDivisor() {
    Money ten = new Money("10.00", EUR);
    Money.DivisionResult split = ten.divideAndRemainder(3);
    // 10.00 / 3 = 3.33 remainder 0.01 at scale 2.
    assertEquals(0, new BigDecimal("3.33").compareTo(split.quotient().amount()));
    assertEquals(0, new BigDecimal("0.01").compareTo(split.remainder().amount()));
    // quotient * divisor + remainder == original
    BigDecimal reconstructed =
        split.quotient().amount().multiply(BigDecimal.valueOf(3)).add(split.remainder().amount());
    assertEquals(0, ten.amount().compareTo(reconstructed));

    assertThrows(ArithmeticException.class, () -> ten.divideAndRemainder(0));
    assertThrows(ArithmeticException.class, () -> ten.divideAndRemainder(BigInteger.ZERO));
    assertThrows(
        IllegalArgumentException.class, () -> ten.divideAndRemainder(new BigDecimal("2.5")));
    assertThrows(NullPointerException.class, () -> ten.divideAndRemainder((BigDecimal) null));
    assertThrows(NullPointerException.class, () -> ten.divideAndRemainder((BigInteger) null));
  }

  @Test
  void decimalScaleMattersForRecordEqualityButNotNumericComparison() {
    Money a = new Money("10.0", EUR);
    Money b = new Money("10.00", EUR);
    // BigDecimal.equals is scale-sensitive, so records differ...
    assertNotEquals(a, b);
    // ...while numeric comparison treats them as equal (used by retry comparison).
    assertEquals(0, a.amount().compareTo(b.amount()));
    assertEquals(0, a.subtract(b).amount().compareTo(BigDecimal.ZERO));
  }

  @Test
  void roundingIsExplicitNotAutomatic() {
    // Money stores the amount as given; no minor-unit rounding is applied.
    Money unrounded = new Money(new BigDecimal("10.005"), EUR);
    assertEquals(0, new BigDecimal("10.005").compareTo(unrounded.amount()));
    // JPY has zero default fraction digits but the constructor still keeps scale.
    assertEquals(0, new BigDecimal("10.5").compareTo(new Money("10.5", JPY).amount()));
  }

  @Test
  void formatUsesCurrencyAndLocale() {
    String formatted = new Money("12.50", EUR).format(Locale.GERMANY);
    assertTrue(formatted.contains("12,50"), "Expected German decimal format in: " + formatted);
    assertThrows(NullPointerException.class, () -> new Money("1.00", EUR).format(null));
  }
}
