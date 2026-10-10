package com.fintechplatform.paycore.banktransfer.simulator;

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
@ConditionalOnProperty(name = "paycore.rails.provider", havingValue = "simulated")
public class RailSessionRecords {

    public record RailSession(
            String sessionId,
            String direction,
            String bankCode,
            String accountNumber,
            String accountName,
            long amountMinor,
            String currency,
            String narration,
            String status,
            String reason,
            String senderName,
            String senderBank,
            String senderAccountNumber,
            boolean notified,
            Instant createdAt
    ) {

        public static RailSession outbound(
                String sessionId, String bankCode, String accountNumber, String accountName,
                long amountMinor, String currency, String narration, String status, String reason
        ) {
            return new RailSession(
                    sessionId, "OUTBOUND", bankCode, accountNumber, accountName, amountMinor, currency,
                    narration, status, reason, null, null, null, true, Instant.now()
            );
        }
    }

    private static final RowMapper<RailSession> ROW = (rs, n) -> new RailSession(
            rs.getString("session_id"),
            rs.getString("direction"),
            rs.getString("bank_code"),
            rs.getString("account_number"),
            rs.getString("account_name"),
            rs.getLong("amount_minor"),
            rs.getString("currency"),
            rs.getString("narration"),
            rs.getString("status"),
            rs.getString("reason"),
            rs.getString("sender_name"),
            rs.getString("sender_bank"),
            rs.getString("sender_account_number"),
            rs.getBoolean("notified"),
            rs.getTimestamp("created_at").toInstant()
    );

    private final JdbcTemplate jdbcTemplate;

    public RailSessionRecords(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public RailSession saveIfAbsent(RailSession session) {

        jdbcTemplate.update(
                """
                INSERT INTO simulator.rail_sessions
                    (session_id, direction, bank_code, account_number, account_name, amount_minor, currency,
                     narration, status, reason, sender_name, sender_bank, sender_account_number, notified, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (session_id) DO NOTHING
                """,
                session.sessionId(), session.direction(), session.bankCode(), session.accountNumber(),
                session.accountName(), session.amountMinor(), session.currency(), session.narration(),
                session.status(), session.reason(), session.senderName(), session.senderBank(),
                session.senderAccountNumber(), session.notified(), Timestamp.from(session.createdAt())
        );

        return find(session.sessionId()).orElseThrow();
    }

    public void markReturned(String sessionId, String reason) {

        jdbcTemplate.update(
                "UPDATE simulator.rail_sessions SET status = 'RETURNED', reason = ? "
                        + "WHERE session_id = ? AND direction = 'INBOUND'",
                reason, sessionId
        );
    }

    public void markNotified(String sessionId) {
        jdbcTemplate.update("UPDATE simulator.rail_sessions SET notified = true WHERE session_id = ?", sessionId);
    }

    public Optional<RailSession> find(String sessionId) {

        return jdbcTemplate
                .query("SELECT * FROM simulator.rail_sessions WHERE session_id = ?", ROW, sessionId)
                .stream()
                .findFirst();
    }

    public List<RailSession> between(Instant from, Instant to) {

        return jdbcTemplate.query(
                "SELECT * FROM simulator.rail_sessions WHERE created_at >= ? AND created_at < ? ORDER BY created_at",
                ROW, Timestamp.from(from), Timestamp.from(to)
        );
    }
}
