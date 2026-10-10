package com.fintechplatform.paycore.common.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class DatabasePrivilegeCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabasePrivilegeCheck.class);

    static final List<String> PROTECTED_TABLES =
            List.of("ledger_entries", "ledger_transactions", "payments", "payment_events", "operations_requests");

    private final JdbcTemplate jdbcTemplate;
    private final boolean required;

    public DatabasePrivilegeCheck(
            JdbcTemplate jdbcTemplate,
            @Value("${paycore.database.require-least-privilege:false}") boolean required
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.required = required;
    }

    @Override
    public void run(ApplicationArguments args) {

        List<String> problems = problems();

        if (problems.isEmpty()) {
            log.info("Database user {} is least-privileged", currentUser());
            return;
        }

        String message = "Database user " + currentUser() + " has more privileges than the API needs: "
                + String.join("; ", problems) + ". See scripts/db/create-app-role.sql.";

        if (required) {
            throw new IllegalStateException(message);
        }

        log.warn(message);
    }

    public List<String> problems() {

        List<String> problems = new ArrayList<>();

        if (Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT rolsuper FROM pg_roles WHERE rolname = current_user", Boolean.class))) {
            problems.add("it is a superuser");
        }

        Integer owned = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_tables WHERE schemaname IN ('public', 'simulator') AND tableowner = current_user",
                Integer.class
        );

        if (owned != null && owned > 0) {
            problems.add("it owns " + owned + " tables");
        }

        for (String table : PROTECTED_TABLES) {
            for (String privilege : List.of("DELETE", "TRUNCATE")) {
                if (has(table, privilege)) {
                    problems.add("it can " + privilege + " " + table);
                }
            }
        }

        if (has("ledger_entries", "UPDATE")) {
            problems.add("it can UPDATE ledger_entries");
        }

        return problems;
    }

    private boolean has(String table, String privilege) {

        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT has_table_privilege(current_user, ?, ?)", Boolean.class, table, privilege
        ));
    }

    private String currentUser() {
        return jdbcTemplate.queryForObject("SELECT current_user", String.class);
    }
}
