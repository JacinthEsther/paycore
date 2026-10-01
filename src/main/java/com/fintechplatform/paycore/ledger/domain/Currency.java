package com.fintechplatform.paycore.ledger.domain;

import java.util.Locale;
import java.util.Objects;

/**
 * An ISO 4217 currency and how many minor units it has (2 for NGN: 100
 * kobo to the naira). Which currencies accounts may hold is decided when
 * the account is opened (paycore.accounts.supported-currencies); the ledger
 * only needs to know how to count them.
 */
public record Currency(
        String code,
        int minorUnit
) {

    public Currency {
        Objects.requireNonNull(code, "Currency code is required");

        if (!code.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException(
                    "Currency must be a 3-letter upper-case ISO 4217 code"
            );
        }

        if (minorUnit < 0) {
            throw new IllegalArgumentException("Minor unit cannot be negative");
        }
    }

    /**
     * The minor unit comes from the JDK's ISO 4217 data, so it is never
     * hand-maintained here.
     */
    public static Currency of(String code) {

        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Currency code is required");
        }

        String normalized = code.trim().toUpperCase(Locale.ROOT);

        java.util.Currency iso;

        try {
            iso = java.util.Currency.getInstance(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown currency: " + normalized);
        }

        // -1 for things like gold (XAU) that have no minor unit.
        if (iso.getDefaultFractionDigits() < 0) {
            throw new IllegalArgumentException("Unsupported currency: " + normalized);
        }

        return new Currency(normalized, iso.getDefaultFractionDigits());
    }
}
