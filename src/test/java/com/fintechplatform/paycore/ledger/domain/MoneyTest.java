package com.fintechplatform.paycore.ledger.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    private static final Currency NGN = Currency.of("NGN");
    private static final Currency USD = Currency.of("USD");

    @Test
    void shouldConvertNairaToKobo() {

        assertThat(Money.ofMajor(new BigDecimal("20000.50"), NGN).amountMinor())
                .isEqualTo(2_000_050L);

        assertThat(Money.ofMajor(new BigDecimal("20000"), NGN).amountMinor())
                .isEqualTo(2_000_000L);

        assertThat(Money.ofMajor(new BigDecimal("0.01"), NGN).amountMinor())
                .isEqualTo(1L);
    }

    @Test
    void shouldAcceptTrailingZerosBeyondMinorUnit() {

        assertThat(Money.ofMajor(new BigDecimal("1.500"), NGN).amountMinor())
                .isEqualTo(150L);
    }

    @Test
    void shouldRejectMoreDecimalPlacesThanTheCurrencyHasInsteadOfRounding() {

        assertThatThrownBy(() -> Money.ofMajor(new BigDecimal("20000.505"), NGN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decimal places");
    }

    @Test
    void shouldConvertBackToMajorUnits() {

        assertThat(Money.ofMinor(2_000_050L, NGN).toMajor())
                .isEqualByComparingTo("20000.50");
    }

    @Test
    void shouldHandleZero() {

        Money zero = Money.ofMajor(BigDecimal.ZERO, NGN);

        assertThat(zero.isZero()).isTrue();
        assertThat(zero.isPositive()).isFalse();
    }

    @Test
    void shouldRejectNegativeAmounts() {

        assertThatThrownBy(() -> Money.ofMinor(-1, NGN))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> Money.ofMajor(new BigDecimal("-0.01"), NGN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldAddAndSubtract() {

        Money hundred = Money.ofMinor(10_000, NGN);
        Money thirty = Money.ofMinor(3_000, NGN);

        assertThat(hundred.add(thirty)).isEqualTo(Money.ofMinor(13_000, NGN));
        assertThat(hundred.subtract(thirty)).isEqualTo(Money.ofMinor(7_000, NGN));
        assertThat(hundred.isGreaterThan(thirty)).isTrue();
        assertThat(thirty.isGreaterThanOrEqualTo(Money.ofMinor(3_000, NGN))).isTrue();
    }

    @Test
    void shouldNotSubtractBelowZero() {

        assertThatThrownBy(() -> Money.ofMinor(1, NGN).subtract(Money.ofMinor(2, NGN)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectCurrencyMismatch() {

        assertThatThrownBy(() -> Money.ofMinor(1, NGN).add(Money.ofMinor(1, USD)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Currency mismatch");

        assertThatThrownBy(() -> Money.ofMinor(1, NGN).isGreaterThan(Money.ofMinor(1, USD)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldFailOnOverflowInsteadOfWrappingAround() {

        assertThatThrownBy(() -> Money.ofMinor(Long.MAX_VALUE, NGN).add(Money.ofMinor(1, NGN)))
                .isInstanceOf(ArithmeticException.class);

        assertThatThrownBy(() -> Money.ofMajor(new BigDecimal("1e30"), NGN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too large");
    }
}
