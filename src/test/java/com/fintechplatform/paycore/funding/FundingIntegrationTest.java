package com.fintechplatform.paycore.funding;

import com.fintechplatform.paycore.funding.service.FundingService;
import com.fintechplatform.paycore.ledger.dto.request.FundAccountRequest;
import com.fintechplatform.paycore.ledger.domain.Counterparty;
import com.fintechplatform.paycore.ledger.dto.response.TransactionResponse;
import com.fintechplatform.paycore.ledger.service.InboundTransfer;
import com.fintechplatform.paycore.ledger.service.LedgerService;
import com.fintechplatform.paycore.ledger.exception.FundingLimitExceededException;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Customer top-ups through the simulated provider, end to end against
 * PostgreSQL: the double-entry posting, declines, idempotency, the
 * per-account limit (also under concurrency), ownership and account states,
 * and the database's own guarantees for provider deposits.
 *
 * The limit is NGN 1,000 here so it is quick to reach.
 */
@Testcontainers
@SpringBootTest(properties = {
        "paycore.funding.provider=simulated",
        "paycore.funding.max-total-per-account=1000"
})
@AutoConfigureMockMvc
class FundingIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("paycore")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @DynamicPropertySource
    static void configureProperties(
            DynamicPropertyRegistry registry
    ) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static final AtomicInteger PHONES = new AtomicInteger(3_000_000);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FundingService fundingService;

    @Autowired
    private LedgerService ledgerService;

    private Holder admin;

    @BeforeEach
    void cleanDatabase() throws Exception {

        for (String table : new String[]{
                "operations_requests",
                "ledger_entries",
                "ledger_transactions",
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

        admin = staff(activeHolder("admin"), "ADMIN");
    }

    // ============================================================
    // A TOP-UP
    // ============================================================

    @Test
    void topUpShouldCreditTheAccountAgainstSettlement() throws Exception {

        Holder esther = activeHolder("esther");

        ResultActions result =
                topUp(esther, "250.50", "topup-0001")
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.type").value("DEPOSIT"))
                        .andExpect(jsonPath("$.status").value("POSTED"))
                        .andExpect(jsonPath("$.amount").value(250.5))
                        .andExpect(jsonPath("$.description").value("Top-up by card (simulated)"))
                        .andExpect(jsonPath("$.provider").value("SIMULATED"))
                        .andExpect(jsonPath("$.providerReference").value(startsWith("SIMPAY-")))
                        .andExpect(jsonPath("$.entries", hasSize(2)))
                        .andExpect(jsonPath("$.entries[0].type").value("DEBIT"))
                        .andExpect(jsonPath("$.entries[0].accountNumber").isEmpty())
                        .andExpect(jsonPath("$.entries[1].type").value("CREDIT"))
                        .andExpect(jsonPath("$.entries[1].accountId").value(esther.accountId()))
                        .andExpect(jsonPath("$.staffNote").doesNotExist());

        String id = JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
        result.andExpect(header().string("Location", "/api/v1/ledger/transactions/" + id));

        assertThat(balance(esther)).isEqualByComparingTo("250.50");

        // The customer initiated it; no staff note, the provider instead.
        assertThat(jdbcTemplate.queryForMap(
                "SELECT initiated_by::text AS by, staff_note, provider FROM ledger_transactions WHERE id = ?::uuid",
                id
        ))
                .containsEntry("by", esther.customerId())
                .containsEntry("staff_note", null)
                .containsEntry("provider", "SIMULATED");

        // PayCore's settlement account holds the money it now owes Esther.
        assertThat(settlementBalance()).isEqualByComparingTo("250.50");
    }

    @Test
    void staffShouldSeeTheProviderAndItsReference() throws Exception {

        Holder esther = activeHolder("esther");

        String body = topUp(esther, "100", "topup-0002").andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");
        String providerReference = JsonPath.read(body, "$.providerReference");

        mockMvc.perform(authorized(get("/api/v1/admin/ledger/transactions/{id}", id), admin.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("SIMULATED"))
                .andExpect(jsonPath("$.providerReference").value(providerReference))
                .andExpect(jsonPath("$.staffNote").isEmpty())
                .andExpect(jsonPath("$.initiatedBy").value(esther.customerId()));

        // The customer's own statement shows it like any other credit.
        mockMvc.perform(authorized(get("/api/v1/ledger/accounts/{id}/statement", esther.accountId()), esther.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].transactionType").value("DEPOSIT"))
                .andExpect(jsonPath("$.lines[0].description").value("Top-up by card (simulated)"));
    }

    @Test
    void declinedPaymentShouldCreditNothing() throws Exception {

        Holder esther = activeHolder("esther");

        topUp(esther, "100.99", "topup-0003")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("PAYMENT_DECLINED"))
                .andExpect(jsonPath("$.providerReference").value(startsWith("SIMPAY-")));

        assertThat(balance(esther)).isEqualByComparingTo("0");
        assertThat(transactionCount()).isZero();
    }

    // ============================================================
    // IDEMPOTENCY
    // ============================================================

    @Test
    void retryShouldReturnTheSameTopUpAndChargeOnce() throws Exception {

        Holder esther = activeHolder("esther");

        String first = topUp(esther, "300", "topup-0004").andReturn().getResponse().getContentAsString();

        topUp(esther, "300", "topup-0004")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value((String) JsonPath.read(first, "$.id")))
                .andExpect(jsonPath("$.providerReference").value((String) JsonPath.read(first, "$.providerReference")));

        assertThat(balance(esther)).isEqualByComparingTo("300");
        assertThat(transactionCount()).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentDetailsShouldBeRefused() throws Exception {

        Holder esther = activeHolder("esther");
        Holder john = activeHolder("john");
        topUp(esther, "300", "topup-0005").andExpect(status().isCreated());

        topUp(esther, "301", "topup-0005")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_KEY_REUSED"));

        // A transfer key is not a top-up key.
        mockMvc.perform(authorized(post("/api/v1/ledger/accounts/{id}/transfers", esther.accountId()), esther.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "destinationAccountNumber": "%s",
                                    "amount": 10,
                                    "currency": "NGN",
                                    "idempotencyKey": "topup-0005"
                                }
                                """.formatted(john.accountNumber())))
                .andExpect(status().isConflict());

        assertThat(balance(esther)).isEqualByComparingTo("300");
    }

    @Test
    void keysShouldBeScopedToTheCustomer() throws Exception {

        Holder esther = activeHolder("esther");
        Holder john = activeHolder("john");

        topUp(esther, "100", "topup-0006").andExpect(status().isCreated());
        topUp(john, "100", "topup-0006").andExpect(status().isCreated());

        assertThat(balance(esther)).isEqualByComparingTo("100");
        assertThat(balance(john)).isEqualByComparingTo("100");
    }

    // ============================================================
    // THE LIMIT
    // ============================================================

    @Test
    void topUpsShouldStopAtTheLimit() throws Exception {

        Holder esther = activeHolder("esther");

        topUp(esther, "600", "topup-0007").andExpect(status().isCreated());

        topUp(esther, "400.01", "topup-0008")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("FUNDING_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.message").value(
                        "Top-ups to this account are limited to 1000.00 NGN in total; 400.00 NGN can still be added"
                ));

        topUp(esther, "400", "topup-0009").andExpect(status().isCreated());

        allowance(esther)
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.provider").value("SIMULATED"))
                .andExpect(jsonPath("$.currency").value("NGN"))
                .andExpect(jsonPath("$.limit").value(1000.0))
                .andExpect(jsonPath("$.funded").value(1000.0))
                .andExpect(jsonPath("$.remaining").value(0.0));

        assertThat(balance(esther)).isEqualByComparingTo("1000");
    }

    @Test
    void onlyStandingTopUpsShouldCountTowardsTheLimit() throws Exception {

        Holder esther = activeHolder("esther");
        Holder john = activeHolder("john");

        // Transfers in, from other banks or other customers, do not use up the limit.
        receiveFromOtherBank(esther, "5000", "session-0001");
        receiveFromOtherBank(john, "200", "session-0002");
        mockMvc.perform(authorized(post("/api/v1/ledger/accounts/{id}/transfers", john.accountId()), john.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "destinationAccountNumber": "%s",
                                    "amount": 200,
                                    "currency": "NGN",
                                    "idempotencyKey": "transfer-0001"
                                }
                                """.formatted(esther.accountNumber())))
                .andExpect(status().isCreated());

        String topUp = JsonPath.read(
                topUp(esther, "1000", "topup-0010").andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString(),
                "$.id"
        );

        allowance(esther).andExpect(jsonPath("$.remaining").value(0.0));

        // A reversed top-up (a chargeback, maker-checker approved) frees its share again.
        reverseThroughOperations(topUp, "Chargeback");

        allowance(esther)
                .andExpect(jsonPath("$.funded").value(0.0))
                .andExpect(jsonPath("$.remaining").value(1000.0));

        topUp(esther, "1000", "topup-0011").andExpect(status().isCreated());
    }

    @Test
    void concurrentTopUpsShouldNeverPassTheLimit() throws Exception {

        Holder esther = activeHolder("esther");
        UUID customerId = UUID.fromString(esther.customerId());
        UUID accountId = UUID.fromString(esther.accountId());

        List<Callable<Object>> tasks = new ArrayList<>();

        for (int i = 0; i < 6; i++) {
            String key = "concurrent-topup-" + i;
            tasks.add(() -> fundingService.fund(
                    customerId, accountId, new FundAccountRequest(new BigDecimal("300"), "NGN", key)
            ));
        }

        List<Object> outcomes = runTogether(tasks);

        assertThat(outcomes).filteredOn(TransactionResponse.class::isInstance).hasSize(3);
        assertThat(outcomes).filteredOn(FundingLimitExceededException.class::isInstance).hasSize(3);
        assertThat(balance(esther)).isEqualByComparingTo("900");
    }

    // ============================================================
    // OWNERSHIP, STATES AND INPUT
    // ============================================================

    @Test
    void anotherCustomersAccountShouldNotBeFound() throws Exception {

        Holder esther = activeHolder("esther");
        Holder john = activeHolder("john");

        mockMvc.perform(authorized(post("/api/v1/ledger/accounts/{id}/fundings", john.accountId()), esther.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(topUpBody("100", "topup-0012")))
                .andExpect(status().isNotFound());

        mockMvc.perform(authorized(get("/api/v1/ledger/accounts/{id}/funding", john.accountId()), esther.token()))
                .andExpect(status().isNotFound());

        assertThat(transactionCount()).isZero();
    }

    @Test
    void anonymousCallersShouldBeRejected() throws Exception {

        Holder esther = activeHolder("esther");

        mockMvc.perform(post("/api/v1/ledger/accounts/{id}/fundings", esther.accountId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(topUpBody("100", "topup-0013")))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"FROZEN", "CLOSED", "PENDING"})
    void onlyActiveAccountsCanBeToppedUp(String accountStatus) throws Exception {

        Holder esther = activeHolder("esther");
        jdbcTemplate.update(
                "UPDATE accounts SET status = ?, closed_at = CASE WHEN ? = 'CLOSED' THEN now() END WHERE id = ?::uuid",
                accountStatus, accountStatus, esther.accountId()
        );

        topUp(esther, "100", "topup-0014")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_ACCOUNT_STATE"));

        assertThat(transactionCount()).isZero();
    }

    @Test
    void wrongCurrencyAndBadAmountsShouldBeRefused() throws Exception {

        Holder esther = activeHolder("esther");

        mockMvc.perform(authorized(post("/api/v1/ledger/accounts/{id}/fundings", esther.accountId()), esther.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "amount": 100, "currency": "USD", "idempotencyKey": "topup-0015" }
                                """))
                .andExpect(status().isBadRequest());

        topUp(esther, "10.001", "topup-0016").andExpect(status().isBadRequest());
        topUp(esther, "0", "topup-0017").andExpect(status().isBadRequest());

        assertThat(transactionCount()).isZero();
    }

    // ============================================================
    // DATABASE GUARANTEES
    // ============================================================

    @Test
    void oneProviderPaymentCanOnlyBeCreditedOnce() throws Exception {

        Holder esther = activeHolder("esther");
        String body = topUp(esther, "100", "topup-0018").andReturn().getResponse().getContentAsString();
        String providerReference = JsonPath.read(body, "$.providerReference");

        assertThatThrownBy(() -> insertProviderDeposit(esther.customerId(), "dup-key-0001", "SIMULATED", providerReference, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_ledger_transactions_provider_payment");
    }

    @Test
    void providerDepositsShouldBeDepositsWithoutStaffNotes() throws Exception {

        Holder esther = activeHolder("esther");

        assertThatThrownBy(() -> insertProviderDeposit(esther.customerId(), "key-note-0001", "SIMULATED", "SIMPAY-X", "A note"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_ledger_transactions_provider_type");

        assertThatThrownBy(() -> insertProviderDeposit(esther.customerId(), "key-pair-0001", "SIMULATED", null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_ledger_transactions_provider_reference_pair");

        // Staff deposits still need their note.
        assertThatThrownBy(() -> insertProviderDeposit(admin.customerId(), "key-staff-0001", null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_ledger_transactions_staff_note");
    }

    // ============================================================
    // HELPERS
    // ============================================================

    private record Holder(
            String email,
            String customerId,
            String accountId,
            String accountNumber,
            String token
    ) {
        Holder withToken(String newToken) {
            return new Holder(email, customerId, accountId, accountNumber, newToken);
        }
    }

    /**
     * A customer with verified KYC and an ACTIVE NGN account, set directly
     * (KYC review and activation have their own tests).
     */
    private Holder activeHolder(String name) throws Exception {

        String email = name + "@example.com";
        String customerId = register(email, "0803" + PHONES.incrementAndGet());

        jdbcTemplate.update("UPDATE kyc_profiles SET status = 'VERIFIED' WHERE customer_id = ?::uuid", customerId);

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

    private Holder staff(Holder holder, String role) throws Exception {

        jdbcTemplate.update(
                "INSERT INTO customer_roles (customer_id, role_id) SELECT ?::uuid, id FROM roles WHERE name = ?",
                holder.customerId(),
                role
        );

        return holder.withToken(login(holder.email()));
    }

    private ResultActions topUp(Holder holder, String amount, String key) throws Exception {

        return mockMvc.perform(authorized(
                        post("/api/v1/ledger/accounts/{id}/fundings", holder.accountId()), holder.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(topUpBody(amount, key)));
    }

    private static String topUpBody(String amount, String key) {
        return """
                { "amount": %s, "currency": "NGN", "idempotencyKey": "%s" }
                """.formatted(amount, key);
    }

    private ResultActions allowance(Holder holder) throws Exception {

        return mockMvc.perform(authorized(get("/api/v1/ledger/accounts/{id}/funding", holder.accountId()), holder.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(holder.accountId()));
    }

    /** Money in from another bank, as the rail would report it. */
    private void receiveFromOtherBank(Holder holder, String amount, String sessionId) {

        ledgerService.receiveInboundTransfer(new InboundTransfer(
                "TESTRAIL", sessionId, holder.accountNumber(), new BigDecimal(amount), "NGN",
                new Counterparty("Chiamaka Obi", "Test Bank", "7000000001"), null
        ));
    }

    /** One operations officer requests the reversal, another approves it. */
    private void reverseThroughOperations(String transactionId, String reason) throws Exception {

        Holder officer = staff(activeHolder("officer"), "OPERATIONS");
        Holder supervisor = staff(activeHolder("supervisor"), "OPERATIONS");

        String request =
                mockMvc.perform(authorized(post("/api/v1/ops/requests/reversals"), officer.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        { "transactionId": "%s", "reason": "%s" }
                                        """.formatted(transactionId, reason)))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString();

        mockMvc.perform(authorized(
                        post("/api/v1/ops/requests/{id}/approve", (String) JsonPath.read(request, "$.id")),
                        supervisor.token()))
                .andExpect(status().isOk());
    }

    private void insertProviderDeposit(
            String initiatedBy,
            String key,
            String provider,
            String providerReference,
            String staffNote
    ) {

        jdbcTemplate.update(
                """
                INSERT INTO ledger_transactions
                    (id, reference, type, status, currency, initiated_by, idempotency_key,
                     staff_note, provider, provider_reference, created_at, posted_at)
                VALUES
                    (paycore_uuid_v7(), ?, 'DEPOSIT', 'POSTED', 'NGN', ?::uuid, ?, ?, ?, ?, now(), now())
                """,
                "TXN-TEST-" + key,
                initiatedBy,
                key,
                staffNote,
                provider,
                providerReference
        );
    }

    private BigDecimal balance(Holder holder) throws Exception {

        String body =
                mockMvc.perform(authorized(get("/api/v1/ledger/accounts/{id}/balance", holder.accountId()), holder.token()))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString();

        return new BigDecimal(String.valueOf((Object) JsonPath.read(body, "$.balance")));
    }

    private BigDecimal settlementBalance() throws Exception {

        String body =
                mockMvc.perform(authorized(get("/api/v1/admin/ledger/system-accounts"), admin.token()))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString();

        List<Object> balances = JsonPath.read(body, "$[?(@.type == 'SETTLEMENT' && @.currency == 'NGN')].balance");

        return balances.isEmpty() ? BigDecimal.ZERO : new BigDecimal(String.valueOf(balances.getFirst()));
    }

    private Integer transactionCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_transactions", Integer.class);
    }

    /**
     * Starts every task at the same moment and returns each result, or the
     * exception it threw.
     */
    private List<Object> runTogether(List<Callable<Object>> tasks) throws Exception {

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(tasks.size(), 8));
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<Object>> futures = new ArrayList<>();

            for (Callable<Object> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return task.call();
                    } catch (Exception exception) {
                        return exception;
                    }
                }));
            }

            start.countDown();

            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;

        } finally {
            pool.shutdownNow();
        }
    }

    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
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
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        { "email": "%s", "password": "Password123" }
                                        """.formatted(email)))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString();

        return JsonPath.read(response, "$.accessToken");
    }
}
