package com.fintechplatform.paycore.bills.simulator;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Component
@Transactional(propagation = Propagation.REQUIRES_NEW)
@ConditionalOnProperty(name = "paycore.bills.provider", havingValue = "simulated")
public class BillRecords {

    public record BillRecord(
            String requestId,
            String billerId,
            String productCode,
            String customerNumber,
            long amountMinor,
            String currency,
            String status,
            String reason,
            String token,
            Instant createdAt
    ) {
    }

    private static final RowMapper<BillRecord> ROW = (rs, n) -> new BillRecord(
            rs.getString("request_id"),
            rs.getString("biller_id"),
            rs.getString("product_code"),
            rs.getString("customer_number"),
            rs.getLong("amount_minor"),
            rs.getString("currency"),
            rs.getString("status"),
            rs.getString("reason"),
            rs.getString("token"),
            rs.getTimestamp("created_at").toInstant()
    );

    private final JdbcTemplate jdbcTemplate;

    public BillRecords(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public BillRecord saveIfAbsent(BillRecord record) {

        jdbcTemplate.update("""
                        INSERT INTO simulator.bill_payments
                            (request_id, biller_id, product_code, customer_number, amount_minor, currency,
                             status, reason, token, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (request_id) DO NOTHING
                        """,
                record.requestId(), record.billerId(), record.productCode(), record.customerNumber(),
                record.amountMinor(), record.currency(), record.status(), record.reason(), record.token(),
                Timestamp.from(record.createdAt())
        );

        return find(record.requestId()).orElseThrow();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<BillRecord> find(String requestId) {
        return jdbcTemplate
                .query("SELECT * FROM simulator.bill_payments WHERE request_id = ?", ROW, requestId)
                .stream()
                .findFirst();
    }
}
