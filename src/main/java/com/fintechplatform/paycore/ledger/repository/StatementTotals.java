package com.fintechplatform.paycore.ledger.repository;

/**
 * A statement period's totals in minor units, computed in one query.
 */
public record StatementTotals(
        long creditsMinor,
        long debitsMinor,
        long lines
) {
}
