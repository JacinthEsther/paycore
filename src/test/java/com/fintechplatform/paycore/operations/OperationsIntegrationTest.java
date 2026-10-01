package com.fintechplatform.paycore.operations;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Maker-checker corrections end to end against PostgreSQL: reversals and
 * manual adjustments are requested by one operations officer and posted
 * only when a different officer approves them. Nobody else can move money
 * by hand, and no officer can touch their own account.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class OperationsIntegrationTest {

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

    private static final AtomicInteger PHONES = new AtomicInteger(6_000_000);

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

        maker = staff(activeHolder("ada"), "OPERATIONS");
        checker = staff(activeHolder("bola"), "OPERATIONS");
    }

    // ============================================================
    // REVERSALS
    // ============================================================

    @Test
    void reversalShouldWaitForASecondOfficerThenMoveTheMoneyBack() throws Exception {

        Holder esther = funded("esther", "1000");
        Holder john = activeHolder("john");
        String transfer = transfer(esther, john, "300", "transfer-0001");

        String request =
                requestReversal(maker, transfer, "Sent to the wrong account")
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.type").value("REVERSAL"))
                        .andExpect(jsonPath("$.status").value("PENDING"))
                        .andExpect(jsonPath("$.amount").value(300.0))
                        .andExpect(jsonPath("$.requestedByName").value("Test Holder"))
                        .andReturn().getResponse().getContentAsString();

        String requestId = JsonPath.read(request, "$.id");

        // Nothing has moved yet.
        assertThat(balance(esther)).isEqualByComparingTo("700");
        assertThat(balance(john)).isEqualByComparingTo("300");

        mockMvc.perform(authorized(get("/api/v1/ops/requests"), checker.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].transactionId").value(transfer));

        approve(checker, requestId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decidedBy").value(checker.customerId()))
                .andExpect(jsonPath("$.resultReference").isNotEmpty());

        assertThat(balance(esther)).isEqualByComparingTo("1000");
        assertThat(balance(john)).isEqualByComparingTo("0");

        // The reversal records both officers and the reason.
        assertThat(jdbcTemplate.queryForMap(
                "SELECT initiated_by::text AS maker, approved_by::text AS checker, staff_note "
                        + "FROM ledger_transactions WHERE reverses_transaction_id = ?::uuid",
                transfer
        ))
                .containsEntry("maker", maker.customerId())
                .containsEntry("checker", checker.customerId())
                .containsEntry("staff_note", "Sent to the wrong account");

        mockMvc.perform(authorized(get("/api/v1/ops/requests"), checker.token()))
                .andExpect(jsonPath("$", hasSize(0)));

        mockMvc.perform(authorized(get("/api/v1/ops/requests").param("status", "DECIDED"), checker.token()))
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void theMakerCanNeverApproveTheirOwnRequest() throws Exception {

        Holder esther = funded("esther", "1000");
        String requestId = adjustmentId(maker, esther, "CREDIT", "100", "Fee charged twice");

        approve(maker, requestId)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FOUR_EYES_REQUIRED"));

        reject(maker, requestId, "Never mind")
                .andExpect(status().isForbidden());

        assertThat(balance(esther)).isEqualByComparingTo("1000");

        // The database refuses it as well.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE operations_requests SET status = 'REJECTED', decided_by = requested_by, decided_at = now() "
                        + "WHERE id = ?::uuid",
                requestId
        ))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_operations_requests_four_eyes");
    }

    @Test
    void aRequestIsDecidedOnceAndApprovingTwicePostsOnce() throws Exception {

        Holder esther = funded("esther", "1000");
        Holder third = staff(activeHolder("chidi"), "OPERATIONS");
        String requestId = adjustmentId(maker, esther, "CREDIT", "100", "Goodwill credit");

        // Two checkers approve at the same moment.
        List<Callable<Object>> tasks = List.of(
                () -> approve(checker, requestId).andReturn().getResponse().getStatus(),
                () -> approve(third, requestId).andReturn().getResponse().getStatus()
        );

        assertThat(runTogether(tasks)).containsExactlyInAnyOrder(200, 409);
        assertThat(balance(esther)).isEqualByComparingTo("1100");

        reject(checker, requestId, "Too late")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("REQUEST_ALREADY_DECIDED"));
    }

    @Test
    void rejectionNeedsANoteAndMovesNothing() throws Exception {

        Holder esther = funded("esther", "1000");
        String requestId = adjustmentId(maker, esther, "DEBIT", "100", "Duplicate credit");

        reject(checker, requestId, " ").andExpect(status().isBadRequest());

        reject(checker, requestId, "Not a duplicate: two real payments")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.decisionNote").value("Not a duplicate: two real payments"))
                .andExpect(jsonPath("$.resultTransactionId").isEmpty());

        assertThat(balance(esther)).isEqualByComparingTo("1000");
    }

    @Test
    void onlyOneReversalOfATransactionCanWaitAtATime() throws Exception {

        Holder esther = funded("esther", "1000");
        Holder john = activeHolder("john");
        String transfer = transfer(esther, john, "300", "transfer-0002");

        String requestId = JsonPath.read(
                requestReversal(maker, transfer, "Wrong account").andReturn().getResponse().getContentAsString(),
                "$.id"
        );

        requestReversal(checker, transfer, "Wrong account")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_REVERSAL_REQUEST"));

        approve(checker, requestId).andExpect(status().isOk());

        // Once reversed, it cannot be reversed again.
        requestReversal(maker, transfer, "Again")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("TRANSACTION_NOT_REVERSIBLE"));
    }

    @Test
    void approvalShouldFailWithoutPostingIfTheMoneyIsGone() throws Exception {

        Holder esther = funded("esther", "1000");
        Holder john = activeHolder("john");
        String transfer = transfer(esther, john, "300", "transfer-0003");

        String requestId = JsonPath.read(
                requestReversal(maker, transfer, "Wrong account").andReturn().getResponse().getContentAsString(),
                "$.id"
        );

        // John spends it before the checker gets to it.
        transfer(john, esther, "300", "transfer-0004");

        approve(checker, requestId)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_FUNDS"));

        // Still pending: nothing half-done.
        mockMvc.perform(authorized(get("/api/v1/ops/requests/{id}", requestId), checker.token()))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void frozenAccountsCanBeCorrectedButClosedOnesCannot() throws Exception {

        Holder esther = funded("esther", "1000");
        Holder john = activeHolder("john");
        String transfer = transfer(esther, john, "300", "transfer-0005");

        // Fraud: John's account is frozen and the money pulled back.
        jdbcTemplate.update("UPDATE accounts SET status = 'FROZEN' WHERE id = ?::uuid", john.accountId());

        String requestId = JsonPath.read(
                requestReversal(maker, transfer, "Fraudulent transfer").andReturn().getResponse().getContentAsString(),
                "$.id"
        );
        approve(checker, requestId).andExpect(status().isOk());

        assertThat(balance(esther)).isEqualByComparingTo("1000");

        jdbcTemplate.update(
                "UPDATE accounts SET status = 'CLOSED', closed_at = now() WHERE id = ?::uuid", esther.accountId()
        );

        requestAdjustment(maker, esther, "CREDIT", "10", "Refund")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_ACCOUNT_STATE"));
    }

    // ============================================================
    // ADJUSTMENTS
    // ============================================================

    @Test
    void adjustmentsShouldCreditOrDebitAgainstSettlement() throws Exception {

        Holder esther = funded("esther", "1000");

        approve(checker, adjustmentId(maker, esther, "CREDIT", "50", "Card fee charged twice"))
                .andExpect(status().isOk());

        assertThat(balance(esther)).isEqualByComparingTo("1050");

        approve(checker, adjustmentId(maker, esther, "DEBIT", "20", "Interest paid in error"))
                .andExpect(status().isOk());

        assertThat(balance(esther)).isEqualByComparingTo("1030");
        assertThat(settlementBalance()).isEqualByComparingTo("1030");

        // A debit beyond the balance is refused at approval.
        approve(checker, adjustmentId(maker, esther, "DEBIT", "5000", "Too much"))
                .andExpect(status().isUnprocessableEntity());

        assertThat(balance(esther)).isEqualByComparingTo("1030");
    }

    @Test
    void customersSeeTheDescriptionButNeverTheReason() throws Exception {

        Holder esther = funded("esther", "1000");

        String requestId = JsonPath.read(
                mockMvc.perform(authorized(post("/api/v1/ops/requests/adjustments"), maker.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        { "accountId": "%s", "direction": "CREDIT", "amount": 25, "currency": "NGN",
                                          "reason": "Complaint #4471: charged twice", "customerDescription": "Refund" }
                                        """.formatted(esther.accountId())))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString(),
                "$.id"
        );

        String transactionId = JsonPath.read(
                approve(checker, requestId).andReturn().getResponse().getContentAsString(),
                "$.resultTransactionId"
        );

        String customerView =
                mockMvc.perform(authorized(get("/api/v1/ledger/transactions/{id}", transactionId), esther.token()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.type").value("ADJUSTMENT"))
                        .andExpect(jsonPath("$.description").value("Refund"))
                        .andReturn().getResponse().getContentAsString();

        assertThat(customerView).doesNotContain("Complaint").doesNotContain("staffNote").doesNotContain("approvedBy");

        mockMvc.perform(authorized(get("/api/v1/admin/ledger/transactions/{id}", transactionId), checker.token()))
                .andExpect(jsonPath("$.staffNote").value("Complaint #4471: charged twice"))
                .andExpect(jsonPath("$.initiatedBy").value(maker.customerId()))
                .andExpect(jsonPath("$.approvedBy").value(checker.customerId()));
    }

    // ============================================================
    // WHO MAY DO WHAT
    // ============================================================

    @Test
    void onlyOperationsOfficersCanRequestOrApprove() throws Exception {

        Holder esther = funded("esther", "1000");
        Holder admin = staff(activeHolder("admin"), "ADMIN");
        Holder support = staff(activeHolder("support"), "SUPPORT");

        for (Holder outsider : List.of(esther, admin, support)) {
            requestAdjustment(outsider, esther, "CREDIT", "1000000", "Free money")
                    .andExpect(status().isForbidden());
        }

        String requestId = adjustmentId(maker, esther, "CREDIT", "10", "Refund");

        for (Holder outsider : List.of(esther, admin, support)) {
            approve(outsider, requestId).andExpect(status().isForbidden());
        }

        // The old manual deposit endpoint is gone.
        mockMvc.perform(authorized(post("/api/v1/admin/ledger/accounts/{id}/deposits", esther.accountId()), admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"amount\": 100, \"currency\": \"NGN\", \"idempotencyKey\": \"x-00000001\" }"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(404, 405));

        assertThat(balance(esther)).isEqualByComparingTo("1000");
    }

    @Test
    void officersCannotCorrectTheirOwnAccounts() throws Exception {

        // Officers are customers too, with their own accounts.
        fund(maker, "1000");

        requestAdjustment(maker, maker, "CREDIT", "100", "Bonus")
                .andExpect(status().isForbidden());

        // The maker files one for the checker's account; the checker cannot approve it.
        String requestId = adjustmentId(maker, checker, "CREDIT", "100", "Bonus");

        Holder third = staff(activeHolder("chidi"), "OPERATIONS");
        approve(checker, requestId).andExpect(status().isForbidden());
        approve(third, requestId).andExpect(status().isOk());
    }

    // ============================================================
    // HELPERS
    // ============================================================

    private record Holder(String email, String customerId, String accountId, String accountNumber, String token) {

        Holder withToken(String newToken) {
            return new Holder(email, customerId, accountId, accountNumber, newToken);
        }

        Holder withAccount(String id, String number) {
            return new Holder(email, customerId, id, number, token);
        }
    }

    private Holder activeHolder(String name) throws Exception {

        String email = name + "@example.com";
        String customerId = register(email, "0806" + PHONES.incrementAndGet());

        jdbcTemplate.update("UPDATE kyc_profiles SET status = 'VERIFIED' WHERE customer_id = ?::uuid", customerId);

        return withAccount(new Holder(email, customerId, null, null, login(email)));
    }

    /** Opens and activates an NGN account for the holder. */
    private Holder withAccount(Holder holder) throws Exception {

        String account =
                mockMvc.perform(authorized(post("/api/v1/accounts"), holder.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{ \"type\": \"PERSONAL\", \"currency\": \"NGN\" }"))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString();

        String accountId = JsonPath.read(account, "$.id");
        jdbcTemplate.update("UPDATE accounts SET status = 'ACTIVE' WHERE id = ?::uuid", accountId);

        return holder.withAccount(accountId, JsonPath.read(account, "$.accountNumber"));
    }

    private Holder staff(Holder holder, String role) throws Exception {

        jdbcTemplate.update(
                "INSERT INTO customer_roles (customer_id, role_id) SELECT ?::uuid, id FROM roles WHERE name = ?",
                holder.customerId(),
                role
        );

        return holder.withToken(login(holder.email()));
    }

    private Holder funded(String name, String amount) throws Exception {

        Holder holder = activeHolder(name);
        fund(holder, amount);
        return holder;
    }

    private void fund(Holder holder, String amount) {

        ledgerService.receiveInboundTransfer(new InboundTransfer(
                "TESTRAIL", "session-" + UUID.randomUUID(), holder.accountNumber(), new BigDecimal(amount), "NGN",
                new Counterparty("Chiamaka Obi", "Test Bank", "7000000001"), null
        ));
    }

    private String transfer(Holder from, Holder to, String amount, String key) throws Exception {

        return JsonPath.read(
                mockMvc.perform(authorized(post("/api/v1/ledger/accounts/{id}/transfers", from.accountId()), from.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        { "destinationAccountNumber": "%s", "amount": %s, "currency": "NGN",
                                          "idempotencyKey": "%s" }
                                        """.formatted(to.accountNumber(), amount, key)))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString(),
                "$.id"
        );
    }

    private ResultActions requestReversal(Holder officer, String transactionId, String reason) throws Exception {

        return mockMvc.perform(authorized(post("/api/v1/ops/requests/reversals"), officer.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "transactionId": "%s", "reason": "%s" }
                        """.formatted(transactionId, reason)));
    }

    private ResultActions requestAdjustment(Holder officer, Holder account, String direction, String amount, String reason)
            throws Exception {

        return mockMvc.perform(authorized(post("/api/v1/ops/requests/adjustments"), officer.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "accountId": "%s", "direction": "%s", "amount": %s, "currency": "NGN", "reason": "%s" }
                        """.formatted(account.accountId(), direction, amount, reason)));
    }

    private String adjustmentId(Holder officer, Holder account, String direction, String amount, String reason)
            throws Exception {

        return JsonPath.read(
                requestAdjustment(officer, account, direction, amount, reason)
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString(),
                "$.id"
        );
    }

    private ResultActions approve(Holder officer, String requestId) throws Exception {
        return mockMvc.perform(authorized(post("/api/v1/ops/requests/{id}/approve", requestId), officer.token()));
    }

    private ResultActions reject(Holder officer, String requestId, String note) throws Exception {

        return mockMvc.perform(authorized(post("/api/v1/ops/requests/{id}/reject", requestId), officer.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"note\": \"" + note + "\" }"));
    }

    private BigDecimal balance(Holder holder) {

        Long minor = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(CASE WHEN entry_type = 'CREDIT' THEN amount_minor ELSE -amount_minor END), 0) "
                        + "FROM ledger_entries WHERE account_id = ?::uuid",
                Long.class,
                holder.accountId()
        );

        return BigDecimal.valueOf(minor, 2);
    }

    private BigDecimal settlementBalance() {

        Long minor = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(CASE WHEN e.entry_type = 'DEBIT' THEN e.amount_minor ELSE -e.amount_minor END), 0) "
                        + "FROM ledger_entries e JOIN accounts a ON a.id = e.account_id WHERE a.account_type = 'SETTLEMENT'",
                Long.class
        );

        return BigDecimal.valueOf(minor, 2);
    }

    private List<Object> runTogether(List<Callable<Object>> tasks) throws Exception {

        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<Object>> futures = new ArrayList<>();

            for (Callable<Object> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
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
