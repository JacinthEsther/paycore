package com.fintechplatform.paycore.common.persistence;

import com.fintechplatform.paycore.banktransfer.simulator.TestBank;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = {
        "paycore.rails.provider=simulated",
        "paycore.funding.provider=simulated",
        "paycore.payments.recovery.enabled=false",
        "paycore.database.require-least-privilege=true"
})
@AutoConfigureMockMvc
class DatabasePrivilegeIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("paycore")
                    .withUsername("postgres")
                    .withPassword("postgres")
                    .withInitScript("db/test-app-role.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "paycore_app");
        registry.add("spring.datasource.password", () -> "app-password");
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabasePrivilegeCheck privilegeCheck;

    @Test
    void theApplicationShouldRunAsALeastPrivilegedUser() {

        assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class)).isEqualTo("paycore_app");
        assertThat(privilegeCheck.problems()).isEmpty();
    }

    @Test
    void theOwnerShouldBeReportedAsTooPowerful() {

        DatabasePrivilegeCheck asOwner = new DatabasePrivilegeCheck(ownerJdbc(), true);

        assertThat(asOwner.problems())
                .anyMatch(problem -> problem.contains("superuser"))
                .anyMatch(problem -> problem.contains("owns"));
    }

    @Test
    void moneyShouldMoveNormallyUnderThosePrivileges() throws Exception {

        String token = customerWithActiveAccount("esther");
        String accountId = jdbcTemplate.queryForObject(
                "SELECT a.id::text FROM accounts a JOIN customers c ON c.id = a.customer_id WHERE c.email = 'esther@example.com'",
                String.class
        );

        mockMvc.perform(authorized(post("/api/v1/simulator/test-bank/transfers"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"destinationAccountNumber\": \"%s\", \"amount\": 500 }".formatted(accountNumber(accountId))))
                .andExpect(status().isCreated());

        mockMvc.perform(authorized(post("/api/v1/ledger/accounts/{id}/fundings", accountId), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"amount\": 100, \"currency\": \"NGN\", \"idempotencyKey\": \"priv-fund-0001\" }"))
                .andExpect(status().isCreated());

        mockMvc.perform(authorized(post("/api/v1/ledger/accounts/{id}/bank-transfers", accountId), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "bankCode": "%s", "accountNumber": "4123456789", "amount": 50.98, "currency": "NGN",
                                  "idempotencyKey": "priv-out-0001" }
                                """.formatted(TestBank.BANK.code())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentStatus").value("UNKNOWN"));

        mockMvc.perform(authorized(get("/api/v1/ledger/accounts/{id}/balance", accountId), token))
                .andExpect(jsonPath("$.balance").value(549.02));

        String customerId = jdbcTemplate.queryForObject(
                "SELECT id::text FROM customers WHERE email = 'esther@example.com'", String.class);
        jdbcTemplate.update(
                "INSERT INTO customer_roles (customer_id, role_id) SELECT ?::uuid, id FROM roles WHERE name = 'SUPPORT'",
                customerId);
        assertThat(jdbcTemplate.update(
                "DELETE FROM customer_roles WHERE customer_id = ?::uuid AND role_id = (SELECT id FROM roles WHERE name = 'SUPPORT')",
                customerId)).isEqualTo(1);
    }

    @Test
    void theApplicationsUserCannotRewriteHistoryOrTheSchema() throws Exception {

        customerWithActiveAccount("john");

        for (String statement : new String[]{
                "DELETE FROM ledger_entries",
                "DELETE FROM ledger_transactions",
                "DELETE FROM payments",
                "DELETE FROM payment_events",
                "DELETE FROM customers",
                "UPDATE ledger_entries SET amount_minor = 1",
                "UPDATE payment_events SET to_status = 'FAILED'",
                "TRUNCATE ledger_entries",
                "TRUNCATE simulator.card_payments",
                "ALTER TABLE ledger_entries DISABLE TRIGGER trg_ledger_entries_append_only",
                "DROP TABLE payments",
                "CREATE TABLE sneaky (id INT)",
                "INSERT INTO flyway_schema_history (installed_rank, description, type, script, installed_by, "
                        + "execution_time, success) VALUES (999, 'x', 'SQL', 'x', 'x', 0, true)"
        }) {
            assertThatThrownBy(() -> jdbcTemplate.execute(statement))
                    .as(statement)
                    .isInstanceOf(DataAccessException.class)
                    .rootCause()
                    .hasMessageMatching("(?s).*(permission denied|must be owner).*");
        }
    }

    private JdbcTemplate ownerJdbc() {
        return new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }

    private String accountNumber(String accountId) {
        return jdbcTemplate.queryForObject("SELECT account_number FROM accounts WHERE id = ?::uuid", String.class, accountId);
    }

    private String customerWithActiveAccount(String name) throws Exception {

        String email = name + "@example.com";

        String customer =
                mockMvc.perform(post("/api/v1/customers")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        { "firstName": "Test", "lastName": "Holder", "email": "%s", "countryCode": "NG",
                                          "phoneNumber": "0808%07d", "password": "Password123" }
                                        """.formatted(email, Math.abs(UUID.randomUUID().hashCode()) % 10_000_000)))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString();

        jdbcTemplate.update("UPDATE kyc_profiles SET status = 'VERIFIED', tier = 3 WHERE customer_id = ?::uuid",
                (String) JsonPath.read(customer, "$.id"));

        String token = JsonPath.read(
                mockMvc.perform(post("/api/v1/auth/login")
                                .header("X-Device-Id", "integration-test-device")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{ \"email\": \"%s\", \"password\": \"Password123\" }".formatted(email)))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(),
                "$.accessToken"
        );

        String account =
                mockMvc.perform(authorized(post("/api/v1/accounts"), token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{ \"type\": \"PERSONAL\", \"currency\": \"NGN\" }"))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString();

        jdbcTemplate.update("UPDATE accounts SET status = 'ACTIVE' WHERE id = ?::uuid", (String) JsonPath.read(account, "$.id"));

        mockMvc.perform(authorized(post("/api/v1/security/pin"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"pin\": \"2580\" }"))
                .andExpect(status().isCreated());

        return token;
    }

    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request, String token) {
        return request
                .header("Authorization", "Bearer " + token)
                .header("X-Transaction-PIN", "2580");
    }
}
