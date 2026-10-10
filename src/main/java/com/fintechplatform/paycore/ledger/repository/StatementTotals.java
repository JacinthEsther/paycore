package com.fintechplatform.paycore.ledger.repository;

public record StatementTotals(
        long creditsMinor,
        long debitsMinor,
        long lines
) {
}
