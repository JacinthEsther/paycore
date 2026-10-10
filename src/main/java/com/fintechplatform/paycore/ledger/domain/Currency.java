package com.fintechplatform.paycore.ledger.domain;

import java.util.Locale;
import java.util.Objects;

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

        if (iso.getDefaultFractionDigits() < 0) {
            throw new IllegalArgumentException("Unsupported currency: " + normalized);
        }

        return new Currency(normalized, iso.getDefaultFractionDigits());
    }
}
