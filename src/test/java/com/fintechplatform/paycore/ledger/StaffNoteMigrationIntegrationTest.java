package com.fintechplatform.paycore.ledger;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Applies migrations up to V20, when staff reasons were stored as the
 * customer-facing description, then applies V21 and checks every reason
 * moved to staff_note and customers are left with a neutral description.
 * Runs Flyway directly (no Spring context) so the database can be
 * inspected between versions.
 */
@Testcontainers
class StaffNoteMigrationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("paycore")
                    .withUsername("postgres")
                    .withPassword("postgres");

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {

        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        ));

        jdbc.execute("DROP SCHEMA public CASCADE");
        jdbc.execute("CREATE SCHEMA public");
    }

    @Test
    void shouldMoveStaffReasonsOutOfCustomerDescriptions() {

        migrateTo("20");

        UUID staffId = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO customers (id, first_name, last_name, email,
                    phone_number, status, email_verified, phone_verified,
                    created_at, updated_at, version)
                VALUES (?, 'Ada', 'Admin', 'ada@example.com',
                    '+2348012345678', 'ACTIVE', true, true, now(), now(), 0)
                """, staffId);

        UUID deposit = transaction("TXN-DEP", "DEPOSIT", "REVERSED", "Cash received at Ikeja branch", null);
        UUID withdrawal = transaction("TXN-WDL", "WITHDRAWAL", "POSTED", null, null);
        UUID reversal = transaction("TXN-REV", "REVERSAL", "POSTED", "Fraud confirmed by compliance", deposit);
        UUID transfer = transaction("TXN-TRF", "TRANSFER", "POSTED", "Rent share", null);

        migrateTo("21");

        assertThat(row(deposit))
                .containsEntry("description", "Deposit")
                .containsEntry("staff_note", "Cash received at Ikeja branch");

        // A reason that was never recorded still satisfies the new rule.
        assertThat(row(withdrawal))
                .containsEntry("description", "Withdrawal")
                .containsEntry("staff_note", "Recorded before staff notes were separated");

        assertThat(row(reversal))
                .containsEntry("description", "Reversal of TXN-DEP")
                .containsEntry("staff_note", "Fraud confirmed by compliance");

        // Customer transfers are the customer's own words: untouched.
        assertThat(row(transfer))
                .containsEntry("description", "Rent share")
                .containsEntry("staff_note", null);
    }

    private UUID transaction(String reference, String type, String status, String description, UUID reverses) {

        UUID id = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO ledger_transactions
                    (id, reference, type, status, currency, initiated_by, idempotency_key,
                     description, created_at, posted_at, reversed_at, reverses_transaction_id)
                VALUES
                    (?, ?, ?, ?, 'NGN', (SELECT id FROM customers LIMIT 1), ?,
                     ?, now(), now(), CASE WHEN ? = 'REVERSED' THEN now() END, ?)
                """, id, reference, type, status, "key-" + reference, description, status, reverses);

        return id;
    }

    private Map<String, Object> row(UUID transactionId) {

        return jdbc.queryForMap(
                "SELECT description, staff_note FROM ledger_transactions WHERE id = ?",
                transactionId
        );
    }

    private void migrateTo(String version) {

        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .target(version)
                .load()
                .migrate();
    }
}
