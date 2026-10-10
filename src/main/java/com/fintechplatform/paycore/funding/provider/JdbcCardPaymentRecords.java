package com.fintechplatform.paycore.funding.provider;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
@Transactional(propagation = Propagation.REQUIRES_NEW)
@ConditionalOnProperty(name = "paycore.funding.provider", havingValue = "simulated")
public class JdbcCardPaymentRecords implements CardPaymentRecords {

    private static final RowMapper<CardPaymentRecord> ROW = (rs, n) -> new CardPaymentRecord(
            rs.getString("merchant_reference"),
            rs.getString("provider_reference"),
            rs.getLong("amount_minor"),
            rs.getString("currency"),
            rs.getString("status"),
            rs.getString("reason"),
            rs.getString("refund_reference"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("refunded_at") == null ? null : rs.getTimestamp("refunded_at").toInstant(),
            rs.getBoolean("settled")
    );

    private final JdbcTemplate jdbcTemplate;

    public JdbcCardPaymentRecords(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public CardPaymentRecord saveIfAbsent(CardPaymentRecord record) {

        jdbcTemplate.update(
                """
                INSERT INTO simulator.card_payments
                    (merchant_reference, provider_reference, amount_minor, currency, status, reason, created_at, settled)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (merchant_reference) DO NOTHING
                """,
                record.merchantReference(), record.providerReference(), record.amountMinor(),
                record.currency(), record.status(), record.reason(), Timestamp.from(record.createdAt()), record.settled()
        );

        return find(record.merchantReference()).orElseThrow();
    }

    @Override
    public Optional<CardPaymentRecord> find(String merchantReference) {

        return jdbcTemplate
                .query("SELECT * FROM simulator.card_payments WHERE merchant_reference = ?", ROW, merchantReference)
                .stream()
                .findFirst();
    }

    @Override
    public CardPaymentRecord markRefunded(String merchantReference, String refundReference, Instant at) {

        jdbcTemplate.update(
                """
                UPDATE simulator.card_payments
                SET status = 'REFUNDED', refund_reference = ?, refunded_at = ?
                WHERE merchant_reference = ? AND status = 'CONFIRMED'
                """,
                refundReference, Timestamp.from(at), merchantReference
        );

        return find(merchantReference).orElseThrow();
    }

    @Override
    public List<CardPaymentRecord> between(Instant from, Instant to) {

        return jdbcTemplate.query(
                "SELECT * FROM simulator.card_payments WHERE created_at >= ? AND created_at < ? ORDER BY created_at",
                ROW, Timestamp.from(from), Timestamp.from(to)
        );
    }
}
