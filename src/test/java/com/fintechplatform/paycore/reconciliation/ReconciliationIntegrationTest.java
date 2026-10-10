package com.fintechplatform.paycore.reconciliation;

import com.fintechplatform.paycore.banktransfer.simulator.TestBank;
import com.fintechplatform.paycore.ledger.domain.Counterparty;
import com.fintechplatform.paycore.ledger.service.InboundTransfer;
import com.fintechplatform.paycore.ledger.service.LedgerService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = {
        "paycore.rails.provider=simulated",
        "paycore.funding.provider=simulated",
        "paycore.payments.recovery.enabled=false",
        "paycore.reconciliation.scheduled-runs.enabled=false"
})
@AutoConfigureMockMvc
class ReconciliationIntegrationTest {

    private static final String BENEFICIARY = "4123456789";

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("paycore")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static final AtomicInteger PHONES = new AtomicInteger(9_000_000);

    private static final String TEST_DEVICE_ID = "integration-test-device";
    private static final String TEST_PIN = "2580";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LedgerService ledgerService;

    private Holder maker;
    private Holder checker;

    @BeforeEach
    void cleanDatabase() throws Exception {

        jdbcTemplate.execute("TRUNCATE ledger_entries, ledger_transactions, reconciliation_runs CASCADE");
        jdbcTemplate.execute("TRUNCATE simulator.rail_sessions, simulator.card_payments");

        for (String table : new String[]{
                "account_status_events",
                "accounts",
                "kyc_verifications",
                "kyc_documents",
                "kyc_profiles",
                "refresh_tokens",
                "login_sessions",
                "identities",
                "role_assignment_events",
                "customer_roles",
                "email_verification_tokens",
                "customers"
        }) {
            jdbcTemplate.execute("DELETE FROM " + table);
        }

        maker = staff(activeHolder("ada"), "OPERATIONS_OFFICER");
        checker = staff(activeHolder("bola"), "OPERATIONS_SUPERVISOR");
    }

    @Test
    void aDayWhereBothSidesAgreeShouldHaveNoBreaks() throws Exception {

        Holder esther = activeHolder("esther");
        fromTestBank(esther, "750").andExpect(status().isCreated());
        topUp(esther, "500", "rec-fund-0001").andExpect(status().isCreated());
        sendOut(esther, "100", "rec-out-0001");
        sendOut(esther, "20.99", "rec-out-0002");

        String rail = run("BANK_RAIL")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.providerKind").value("BANK_RAIL"))
                .andExpect(jsonPath("$.provider").value("SIMULATED"))
                .andExpect(jsonPath("$.scheduled").value(false))
                .andExpect(jsonPath("$.providerLines").value(3))
                .andExpect(jsonPath("$.matched").value(3))
                .andExpect(jsonPath("$.breaks").value(0))
                .andExpect(jsonPath("$.providerTotal").value(850.0))
                .andExpect(jsonPath("$.paycoreTotal").value(850.0))
                .andReturn().getResponse().getContentAsString();

        detail(JsonPath.read(rail, "$.id"))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[*].result").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("MATCHED"))))
                .andExpect(jsonPath("$.items[*].resolutionStatus").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("NOT_REQUIRED"))));

        run("CARD")
                .andExpect(jsonPath("$.providerLines").value(1))
                .andExpect(jsonPath("$.matched").value(1))
                .andExpect(jsonPath("$.breaks").value(0));
    }

    @Test
    void aLostInboundNotificationShouldBeFoundAndDeliveredAgain() throws Exception {

        Holder esther = activeHolder("esther");

        fromTestBank(esther, "75.95")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.error").value("NOTIFICATION_LOST"));
        assertThat(balance(esther)).isEqualByComparingTo("0");

        Map<String, Object> item = onlyBreak(run("BANK_RAIL"));
        assertThat(item)
                .containsEntry("kind", "INBOUND")
                .containsEntry("result", "MISSING_LOCAL")
                .containsEntry("providerStatus", "SUCCESSFUL")
                .containsEntry("paycoreStatus", null)
                .containsEntry("accountId", esther.accountId())
                .containsEntry("resolutionStatus", "OPEN");

        mockMvc.perform(authorized(post("/api/v1/ops/reconciliation/items/{id}/redeliver", item.get("id")), esther.token()))
                .andExpect(status().isForbidden());

        mockMvc.perform(authorized(post("/api/v1/ops/reconciliation/items/{id}/redeliver", item.get("id")), maker.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolutionStatus").value("RESOLVED"))
                .andExpect(jsonPath("$.resolutionNote").value(startsWith("Re-delivered by the rail")));

        assertThat(balance(esther)).isEqualByComparingTo("75.95");

        mockMvc.perform(authorized(post("/api/v1/ops/reconciliation/items/{id}/redeliver", item.get("id")), maker.token()))
                .andExpect(status().isBadRequest());
        run("BANK_RAIL").andExpect(jsonPath("$.breaks").value(0)).andExpect(jsonPath("$.matched").value(1));
        assertThat(balance(esther)).isEqualByComparingTo("75.95");
    }

    @Test
    void aTransferReturnedAfterAcceptanceShouldBeFoundAndReversed() throws Exception {

        Holder esther = funded("esther", "1000");
        String body = sendOut(esther, "200.93", "rec-out-0003");
        assertThat((String) JsonPath.read(body, "$.transferStatus")).isEqualTo("SUCCESSFUL");

        Map<String, Object> item = onlyBreak(run("BANK_RAIL"));
        assertThat(item)
                .containsEntry("kind", "OUTBOUND")
                .containsEntry("result", "STATUS_MISMATCH")
                .containsEntry("providerStatus", "FAILED")
                .containsEntry("paycoreStatus", "SUCCEEDED");
        assertThat((String) item.get("detail")).contains("still debited");

        String request = JsonPath.read(
                mockMvc.perform(authorized(post("/api/v1/ops/requests/reversals"), maker.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        { "transactionId": "%s", "reason": "Returned by beneficiary bank (reconciliation)" }
                                        """.formatted(item.get("ledgerTransactionId"))))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString(),
                "$.id"
        );
        mockMvc.perform(authorized(post("/api/v1/ops/requests/{id}/approve", request), checker.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        assertThat(balance(esther)).isEqualByComparingTo("1000");
        run("BANK_RAIL").andExpect(jsonPath("$.breaks").value(0));
    }

    @Test
    void anOverchargedTopUpShouldBeResolvedOnceAndStayResolved() throws Exception {

        Holder esther = activeHolder("esther");
        topUp(esther, "300.94", "rec-fund-0002").andExpect(status().isCreated());

        Map<String, Object> item = onlyBreak(run("CARD"));
        assertThat(item)
                .containsEntry("kind", "CARD")
                .containsEntry("result", "AMOUNT_MISMATCH")
                .containsEntry("providerAmount", 301.94)
                .containsEntry("paycoreAmount", 300.94);

        resolve(esther, item, "x").andExpect(status().isForbidden());
        resolve(checker, item, " ").andExpect(status().isBadRequest());
        resolve(checker, item, "N1.00 refunded to the card by the processor, ticket 7781")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolutionStatus").value("RESOLVED"))
                .andExpect(jsonPath("$.resolvedBy").value(checker.customerId()));
        resolve(checker, item, "again").andExpect(status().isBadRequest());

        Map<String, Object> again = onlyBreak(run("CARD"));
        assertThat(again).containsEntry("result", "AMOUNT_MISMATCH").containsEntry("resolutionStatus", "RESOLVED");
        assertThat((String) again.get("resolutionNote")).startsWith("Resolved in an earlier run: N1.00 refunded");
    }

    @Test
    void aChargeTheProcessorNeverSettledShouldBeMissingOnItsSide() throws Exception {

        Holder esther = activeHolder("esther");
        topUp(esther, "100.92", "rec-fund-0003").andExpect(status().isCreated());

        assertThat(onlyBreak(run("CARD")))
                .containsEntry("result", "MISSING_PROVIDER")
                .containsEntry("providerStatus", null)
                .containsEntry("paycoreStatus", "SUCCEEDED");
    }

    @Test
    void aTopUpPayCoreIsStillWaitingForShouldBeAStatusMismatch() throws Exception {

        Holder esther = activeHolder("esther");
        topUp(esther, "40.96", "rec-fund-0004").andExpect(status().isAccepted());

        Map<String, Object> item = onlyBreak(run("CARD"));
        assertThat(item)
                .containsEntry("result", "STATUS_MISMATCH")
                .containsEntry("providerStatus", "CONFIRMED")
                .containsEntry("paycoreStatus", "UNKNOWN");
        assertThat((String) item.get("detail")).contains("still waiting");
    }

    @Test
    void aRunShouldNeverBeRewritten() throws Exception {

        Holder esther = activeHolder("esther");
        topUp(esther, "100.92", "rec-fund-0005").andExpect(status().isCreated());
        String itemId = (String) onlyBreak(run("CARD")).get("id");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE reconciliation_items SET result = 'MATCHED', resolution_status = 'NOT_REQUIRED' WHERE id = ?::uuid",
                itemId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("evidence");

        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM reconciliation_items WHERE id = ?::uuid", itemId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("cannot be deleted");
    }

    @Test
    void customersShouldNotRunOrReadReconciliation() throws Exception {

        Holder esther = activeHolder("esther");

        mockMvc.perform(authorized(post("/api/v1/ops/reconciliation/runs"), esther.token())
                        .contentType(MediaType.APPLICATION_JSON).content("{ \"provider\": \"CARD\" }"))
                .andExpect(status().isForbidden());
        mockMvc.perform(authorized(get("/api/v1/ops/reconciliation/runs"), esther.token()))
                .andExpect(status().isForbidden());

        mockMvc.perform(authorized(get("/api/v1/ops/reconciliation/runs"), maker.token()))
                .andExpect(status().isOk());
        mockMvc.perform(authorized(get("/api/v1/ops/reconciliation/runs/{id}", UUID.randomUUID()), maker.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("RECONCILIATION_RUN_NOT_FOUND"));
    }

    private record Holder(String email, String customerId, String accountId, String accountNumber, String token) {

        Holder withToken(String newToken) {
            return new Holder(email, customerId, accountId, accountNumber, newToken);
        }
    }

    private ResultActions run(String provider) throws Exception {

        return mockMvc.perform(authorized(post("/api/v1/ops/reconciliation/runs"), maker.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"provider\": \"%s\" }".formatted(provider)));
    }

    private ResultActions detail(String runId) throws Exception {
        return mockMvc.perform(authorized(get("/api/v1/ops/reconciliation/runs/{id}", runId), maker.token()))
                .andExpect(status().isOk());
    }

    private Map<String, Object> onlyBreak(ResultActions run) throws Exception {

        String body = run.andExpect(status().isCreated())
                .andExpect(jsonPath("$.breaks").value(1))
                .andReturn().getResponse().getContentAsString();

        String detail = detail(JsonPath.read(body, "$.id")).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> breaks = JsonPath.read(detail, "$.items[?(@.result != 'MATCHED')]");

        assertThat(breaks).hasSize(1);
        return breaks.getFirst();
    }

    private ResultActions resolve(Holder officer, Map<String, Object> item, String note) throws Exception {

        return mockMvc.perform(authorized(post("/api/v1/ops/reconciliation/items/{id}/resolve", item.get("id")), officer.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"note\": \"%s\" }".formatted(note)));
    }

    private ResultActions fromTestBank(Holder holder, String amount) throws Exception {

        return mockMvc.perform(authorized(post("/api/v1/simulator/test-bank/transfers"), holder.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"destinationAccountNumber\": \"%s\", \"amount\": %s }".formatted(holder.accountNumber(), amount)));
    }

    private ResultActions topUp(Holder holder, String amount, String key) throws Exception {

        return mockMvc.perform(authorized(post("/api/v1/ledger/accounts/{id}/fundings", holder.accountId()), holder.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"amount\": %s, \"currency\": \"NGN\", \"idempotencyKey\": \"%s\" }".formatted(amount, key)));
    }

    private String sendOut(Holder holder, String amount, String key) throws Exception {

        return mockMvc.perform(authorized(post("/api/v1/ledger/accounts/{id}/bank-transfers", holder.accountId()), holder.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "bankCode": "%s", "accountNumber": "%s", "amount": %s, "currency": "NGN",
                                  "idempotencyKey": "%s" }
                                """.formatted(TestBank.BANK.code(), BENEFICIARY, amount, key)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private Holder activeHolder(String name) throws Exception {

        String email = name + "@example.com";
        String customerId = register(email, "0809" + PHONES.incrementAndGet());

        jdbcTemplate.update("UPDATE kyc_profiles SET status = 'VERIFIED', tier = 3 WHERE customer_id = ?::uuid", customerId);

        String token = login(email);

        String account =
                mockMvc.perform(authorized(post("/api/v1/accounts"), token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{ \"type\": \"PERSONAL\", \"currency\": \"NGN\" }"))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString();

        String accountId = JsonPath.read(account, "$.id");

        jdbcTemplate.update("UPDATE accounts SET status = 'ACTIVE' WHERE id = ?::uuid", accountId);

        return new Holder(email, customerId, accountId, JsonPath.read(account, "$.accountNumber"), token);
    }

    private Holder funded(String name, String amount) throws Exception {

        Holder holder = activeHolder(name);

        ledgerService.receiveInboundTransfer(new InboundTransfer(
                "TESTRAIL", "FUND-" + UUID.randomUUID(), holder.accountNumber(), new BigDecimal(amount), "NGN",
                new Counterparty("Chiamaka Obi", "Test Bank", "7000000001"), null
        ));

        return holder;
    }

    private Holder staff(Holder holder, String role) throws Exception {

        jdbcTemplate.update(
                "INSERT INTO customer_roles (customer_id, role_id) SELECT ?::uuid, id FROM roles WHERE name = ?",
                holder.customerId(), role
        );

        return holder.withToken(login(holder.email()));
    }

    private BigDecimal balance(Holder holder) throws Exception {

        String body =
                mockMvc.perform(authorized(get("/api/v1/ledger/accounts/{id}/balance", holder.accountId()), holder.token()))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString();

        return new BigDecimal(String.valueOf((Object) JsonPath.read(body, "$.balance")));
    }

    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request, String token) {
        return request
                .header("Authorization", "Bearer " + token)
                .header("X-Transaction-PIN", TEST_PIN);
    }

    private String register(String email, String phone) throws Exception {

        String response =
                mockMvc.perform(post("/api/v1/customers")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                            "firstName": "Test",
                                            "lastName": "Holder",
                                            "email": "%s",
                                            "countryCode": "NG",
                                            "phoneNumber": "%s",
                                            "password": "Password123"
                                        }
                                        """.formatted(email, phone)))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString();

        return JsonPath.read(response, "$.id");
    }

    private String login(String email) throws Exception {

        String response =
                mockMvc.perform(post("/api/v1/auth/login")
                                .header("X-Device-Id", TEST_DEVICE_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        { "email": "%s", "password": "Password123" }
                                        """.formatted(email)))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString();

        String token = JsonPath.read(response, "$.accessToken");
        withTransactionPin(token);
        return token;
    }

    private void withTransactionPin(String token) throws Exception {

        mockMvc.perform(post("/api/v1/security/pin")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"pin\": \"" + TEST_PIN + "\" }"));
    }
}
