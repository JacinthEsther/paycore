package com.fintechplatform.paycore.payment;

import com.fintechplatform.paycore.funding.service.FundingService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class PaymentBackfillMigrationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("paycore")
                    .withUsername("postgres")
                    .withPassword("postgres");

    private JdbcTemplate jdbc;
    private TransactionTemplate inTransaction;

    @BeforeEach
    void setUp() {

        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword()
        );

        jdbc = new JdbcTemplate(dataSource);
        inTransaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        jdbc.execute("DROP SCHEMA public CASCADE");
        jdbc.execute("CREATE SCHEMA public");
    }

    @Test
    void everyEarlierProviderPaymentShouldGetItsSettledPayment() {

        migrateTo("25");

        UUID customer = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO customers (id, first_name, last_name, email,
                    phone_number, status, email_verified, phone_verified,
                    created_at, updated_at, version)
                VALUES (?, 'Esther', 'Okafor', 'esther@example.com',
                    '+2348012345678', 'ACTIVE', true, true, now(), now(), 0)
                """, customer);

        UUID account = UUID.randomUUID();
        UUID settlement = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO accounts (id, customer_id, account_number, account_type, status, currency,
                    created_at, updated_at, version)
                VALUES (?, ?, '0123456789', 'PERSONAL', 'ACTIVE', 'NGN', now(), now(), 0)
                """, account, customer);

        jdbc.update("""
                INSERT INTO accounts (id, account_type, status, currency, created_at, updated_at, version)
                VALUES (?, 'SETTLEMENT', 'ACTIVE', 'NGN', now(), now(), 0)
                """, settlement);

        UUID topUp = posted("TXN-TOPUP", "DEPOSIT", customer, "topup-key-0001", "SIMULATED", "SIMPAY-1",
                settlement, account, 500_000);
        UUID paid = posted("TXN-PAID", "OUTBOUND_TRANSFER", customer, "out-key-0001", "SIMULATED", "100999-PAID",
                account, settlement, 100_000);
        UUID rejected = posted("TXN-REJ", "OUTBOUND_TRANSFER", customer, "out-key-0002", "SIMULATED", "100999-REJ",
                account, settlement, 20_099);
        UUID reversal = reversal(rejected, customer, settlement, account, 20_099);
        UUID inbound = posted("TXN-IN", "INBOUND_TRANSFER", null, "100999-IN", "SIMULATED", "100999-IN",
                settlement, account, 7_000);

        migrateTo("26");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payments", Integer.class)).isEqualTo(3);

        assertThat(payment(topUp))
                .containsEntry("type", "CARD_FUNDING")
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("account_id", account)
                .containsEntry("amount_minor", 500_000L)
                .containsEntry("provider_reference", "SIMPAY-1")
                .containsEntry("external_reference", FundingService.merchantReference(customer, "topup-key-0001"));

        assertThat(payment(paid))
                .containsEntry("type", "OUTBOUND_TRANSFER")
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("external_reference", "100999-PAID")
                .containsEntry("counterparty_account_number", "7000000002")
                .containsEntry("reversal_transaction_id", null);

        assertThat(payment(rejected))
                .containsEntry("status", "FAILED")
                .containsEntry("amount_minor", 20_099L)
                .containsEntry("reversal_transaction_id", reversal);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payments WHERE ledger_transaction_id = ?", Integer.class, inbound
        )).isZero();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM payments WHERE reference !~ '^PAY-\\d{8}-[0-9A-F]{16}$' OR completed_at IS NULL",
                Integer.class
        )).isZero();
    }

    private UUID posted(
            String reference,
            String type,
            UUID initiatedBy,
            String key,
            String provider,
            String providerReference,
            UUID debitAccount,
            UUID creditAccount,
            long amountMinor
    ) {

        UUID id = UUID.randomUUID();
        boolean bankTransfer = type.endsWith("_TRANSFER");

        inTransaction.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO ledger_transactions
                        (id, reference, type, status, currency, initiated_by, idempotency_key, description,
                         provider, provider_reference, counterparty_name, counterparty_bank,
                         counterparty_account_number, created_at, posted_at)
                    VALUES (?, ?, ?, 'POSTED', 'NGN', ?, ?, 'Backfill fixture', ?, ?, ?, ?, ?, now(), now())
                    """,
                    id, reference, type, initiatedBy, key, provider, providerReference,
                    bankTransfer ? "Ibrahim Musa" : null,
                    bankTransfer ? "Test Bank" : null,
                    bankTransfer ? "7000000002" : null
            );
            entries(id, debitAccount, creditAccount, amountMinor);
        });

        return id;
    }

    private UUID reversal(UUID original, UUID initiatedBy, UUID debitAccount, UUID creditAccount, long amountMinor) {

        UUID id = UUID.randomUUID();

        inTransaction.executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO ledger_transactions
                        (id, reference, type, status, currency, initiated_by, idempotency_key, description,
                         provider, provider_reference, reverses_transaction_id, created_at, posted_at)
                    VALUES (?, 'TXN-REVERSAL', 'REVERSAL', 'POSTED', 'NGN', ?, ?, 'Reversal', 'SIMULATED',
                            'REV-100999-REJ', ?, now(), now())
                    """,
                    id, initiatedBy, "rail-reversal-" + original, original
            );
            entries(id, debitAccount, creditAccount, amountMinor);
            jdbc.update(
                    "UPDATE ledger_transactions SET status = 'REVERSED', reversed_at = now() WHERE id = ?",
                    original
            );
        });

        return id;
    }

    private void entries(UUID transactionId, UUID debitAccount, UUID creditAccount, long amountMinor) {

        for (Object[] side : new Object[][]{{debitAccount, "DEBIT"}, {creditAccount, "CREDIT"}}) {
            jdbc.update("""
                    INSERT INTO ledger_entries (id, transaction_id, account_id, entry_type, amount_minor, currency, created_at)
                    VALUES (?, ?, ?, ?, ?, 'NGN', now())
                    """, UUID.randomUUID(), transactionId, side[0], side[1], amountMinor);
        }
    }

    private Map<String, Object> payment(UUID ledgerTransactionId) {

        return jdbc.queryForMap(
                "SELECT * FROM payments WHERE ledger_transaction_id = ?",
                ledgerTransactionId
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
