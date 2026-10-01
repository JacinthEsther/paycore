package com.fintechplatform.paycore.ledger.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * A non-negative amount in whole minor units: ₦20,000.50 is 2,000,050
 * kobo. No floating point anywhere, and arithmetic fails loudly on
 * overflow or a currency mismatch instead of producing a wrong number.
 */
public record Money(
        long amountMinor,
        Currency currency
) {

    public Money {
        Objects.requireNonNull(currency, "Currency is required");

        if (amountMinor < 0) {
            throw new IllegalArgumentException("Money amount cannot be negative");
        }
    }

    public static Money ofMinor(long amountMinor, Currency currency) {
        return new Money(amountMinor, currency);
    }

    /**
     * Converts an amount as people write it (20000.50) into minor units.
     * Never rounds: 20000.505 NGN is rejected, not silently changed.
     */
    public static Money ofMajor(BigDecimal amount, Currency currency) {

        Objects.requireNonNull(amount, "Amount is required");
        Objects.requireNonNull(currency, "Currency is required");

        if (amount.signum() < 0) {
            throw new IllegalArgumentException("Money amount cannot be negative");
        }

        BigDecimal scaled;

        try {
            scaled = amount.setScale(currency.minorUnit(), RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "Amount has more than " + currency.minorUnit()
                            + " decimal places, which " + currency.code() + " does not allow"
            );
        }

        try {
            return new Money(
                    scaled.movePointRight(currency.minorUnit()).longValueExact(),
                    currency
            );
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Amount is too large");
        }
    }

    public BigDecimal toMajor() {
        return BigDecimal.valueOf(amountMinor, currency.minorUnit());
    }

    public Money add(Money other) {
        requireSameCurrency(other);

        return new Money(Math.addExact(amountMinor, other.amountMinor), currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);

        if (other.amountMinor > amountMinor) {
            throw new IllegalArgumentException("Money cannot become negative");
        }

        return new Money(amountMinor - other.amountMinor, currency);
    }

    public boolean isZero() {
        return amountMinor == 0;
    }

    public boolean isPositive() {
        return amountMinor > 0;
    }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);

        return amountMinor > other.amountMinor;
    }

    public boolean isGreaterThanOrEqualTo(Money other) {
        requireSameCurrency(other);

        return amountMinor >= other.amountMinor;
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "Money is required");

        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Currency mismatch: " + currency.code() + " vs " + other.currency.code()
            );
        }
    }
}
