package com.fintechplatform.paycore.common.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Applies migrations up to V7, adds data that references the seeded v4
 * ids, then applies V8 and checks every id is v7 and no link was lost.
 * Runs Flyway directly (no Spring context) so the database can be
 * inspected between versions.
 */
@Testcontainers
class UuidV7RekeyMigrationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("paycore")
                    .withUsername("postgres")
                    .withPassword("postgres");

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {

        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(
                        postgres.getJdbcUrl(),
                        postgres.getUsername(),
                        postgres.getPassword()
                );

        jdbc = new JdbcTemplate(dataSource);

        jdbc.execute("DROP SCHEMA public CASCADE");
        jdbc.execute("CREATE SCHEMA public");
    }

    @Test
    void shouldRekeySeededRowsToUuidV7AndKeepEveryReference() {

        migrateTo("7");

        assertThat(versionsOf("SELECT id FROM roles")).containsOnly(4);
        assertThat(versionsOf("SELECT id FROM permissions")).containsOnly(4);

        UUID customerId = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO customers (id, first_name, last_name, email,
                    phone_number, status, email_verified, phone_verified,
                    created_at, updated_at, version)
                VALUES (?, 'Esther', 'Test', 'esther@example.com',
                    '+2348012345678', 'ACTIVE', true, true, now(), now(), 0)
                """, customerId);

        jdbc.update("""
                INSERT INTO customer_roles (customer_id, role_id)
                SELECT ?, id FROM roles WHERE name IN ('CUSTOMER', 'ADMIN')
                """, customerId);

        List<String> linksBefore = rolePermissionLinks();

        migrateTo("8");

        assertThat(versionsOf("SELECT id FROM roles")).containsOnly(7);
        assertThat(versionsOf("SELECT id FROM permissions")).containsOnly(7);

        assertThat(rolePermissionLinks())
                .isNotEmpty()
                .containsExactlyInAnyOrderElementsOf(linksBefore);

        assertThat(jdbc.queryForList("""
                SELECT r.name
                FROM customer_roles cr
                JOIN roles r ON r.id = cr.role_id
                WHERE cr.customer_id = ?
                """, String.class, customerId))
                .containsExactlyInAnyOrder("CUSTOMER", "ADMIN");

        assertThat(versionsOf("SELECT role_id FROM customer_roles"))
                .containsOnly(7);
    }

    @Test
    void shouldRestoreForeignKeys() {

        migrateTo("8");

        assertThat(jdbc.queryForList("""
                SELECT conname FROM pg_constraint
                WHERE contype = 'f'
                  AND conrelid IN ('role_permissions'::regclass,
                                   'customer_roles'::regclass)
                """, String.class))
                .contains(
                        "fk_role_permissions_role",
                        "fk_role_permissions_permission",
                        "fk_customer_roles_role"
                );
    }

    @Test
    void generatorFunctionShouldProduceTimeOrderedUuidV7() {

        migrateTo("8");

        UUID uuid = jdbc.queryForObject(
                "SELECT paycore_uuid_v7()", UUID.class
        );

        assertThat(uuid.version()).isEqualTo(7);
        assertThat(uuid.variant()).isEqualTo(2);

        long embeddedMillis = uuid.getMostSignificantBits() >>> 16;

        assertThat(embeddedMillis)
                .isCloseTo(System.currentTimeMillis(),
                        org.assertj.core.data.Offset.offset(60_000L));
    }

    private void migrateTo(String version) {

        Flyway.configure()
                .dataSource(
                        postgres.getJdbcUrl(),
                        postgres.getUsername(),
                        postgres.getPassword()
                )
                .locations("classpath:db/migration")
                .target(version)
                .load()
                .migrate();
    }

    private List<Integer> versionsOf(String sql) {

        return jdbc.queryForList(sql, UUID.class)
                .stream()
                .map(UUID::version)
                .toList();
    }

    private List<String> rolePermissionLinks() {

        return jdbc.queryForList("""
                SELECT r.name || ':' || p.name
                FROM role_permissions rp
                JOIN roles r ON r.id = rp.role_id
                JOIN permissions p ON p.id = rp.permission_id
                """, String.class);
    }
}
