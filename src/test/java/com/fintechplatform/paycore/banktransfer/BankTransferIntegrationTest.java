package com.fintechplatform.paycore.banktransfer;

import com.fintechplatform.paycore.banktransfer.service.InboundWebhookService;
import com.fintechplatform.paycore.banktransfer.simulator.TestBank;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Money in and out of PayCore through the (simulated) interbank rail, end
 * to end against PostgreSQL: the signed inbound webhook, the Test Bank
 * simulator, name enquiry, outbound transfers with automatic reversal of
 * rejected payments, idempotency, concurrency, and the settlement account
 * staying equal to what PayCore owes its customers.
 */
@Testcontainers
@SpringBootTest(properties = {
        "paycore.rails.provider=simulated",
        "paycore.rails.webhook-secret=" + BankTransferIntegrationTest.SECRET,
        "paycore.rails.simulator-inbound-limit-per-account=5000"
})
@AutoConfigureMockMvc
class BankTransferIntegrationTest {

    static final String SECRET = "test-webhook-secret";

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

    private static final AtomicInteger PHONES = new AtomicInteger(5_000_000);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestBank testBank;

    @BeforeEach
    void cleanDatabase() {

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
    }

    // ============================================================
    // INBOUND: THE RAIL WEBHOOK
    // ============================================================

    @Test
    void signedNotificationShouldCreditTheAccountAgainstSettlement() throws Exception {

        Holder esther = activeHolder("esther");

        String reference =
                JsonPath.read(
                        webhook(notification("SESSION-0001", esther.accountNumber(), "2500.50", "Rent share"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                                .andReturn().getResponse().getContentAsString(),
                        "$.reference"
                );

        assertThat(balance(esther)).isEqualByComparingTo("2500.50");
        assertThat(settlementBalance()).isEqualByComparingTo("2500.50");

        assertThat(jdbcTemplate.queryForMap(
                "SELECT type, initiated_by, provider, provider_reference, counterparty_name, counterparty_bank "
                        + "FROM ledger_transactions WHERE reference = ?",
                reference
        ))
                .containsEntry("type", "INBOUND_TRANSFER")
                .containsEntry("initiated_by", null)
                .containsEntry("provider", "SIMULATED")
                .containsEntry("provider_reference", "SESSION-0001")
                .containsEntry("counterparty_name", "Chiamaka Obi")
                .containsEntry("counterparty_bank", "Test Bank");

        // The customer sees who sent it.
        mockMvc.perform(authorized(get("/api/v1/ledger/accounts/{id}/statement", esther.accountId()), esther.token()))
                .andExpect(jsonPath("$.lines[0].transactionType").value("INBOUND_TRANSFER"))
                .andExpect(jsonPath("$.lines[0].description").value("Rent share"))
                .andExpect(jsonPath("$.lines[0].counterpartyName").value("Chiamaka Obi"))
                .andExpect(jsonPath("$.lines[0].counterpartyBank").value("Test Bank"));
    }

    @Test
    void aSessionDeliveredTwiceShouldBeCreditedOnce() throws Exception {

        Holder esther = activeHolder("esther");
        String body = notification("SESSION-0002", esther.accountNumber(), "1000", null);

        String first = JsonPath.read(webhook(body).andReturn().getResponse().getContentAsString(), "$.reference");

        webhook(body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reference").value(first));

        assertThat(balance(esther)).isEqualByComparingTo("1000");
        assertThat(transactionCount()).isEqualTo(1);

        // The same session with a different amount is not believed.
        webhook(notification("SESSION-0002", esther.accountNumber(), "9000", null))
                .andExpect(status().isConflict());

        assertThat(balance(esther)).isEqualByComparingTo("1000");
    }

    @Test
    void unsignedOrForgedNotificationsShouldCreditNothing() throws Exception {

        Holder esther = activeHolder("esther");
        String body = notification("SESSION-0003", esther.accountNumber(), "1000000", null);

        mockMvc.perform(post("/api/v1/webhooks/bank-rail/inbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_SIGNATURE"));

        mockMvc.perform(post("/api/v1/webhooks/bank-rail/inbound")
                        .header(InboundWebhookService.SIGNATURE_HEADER, InboundWebhookService.sign("wrong-secret", body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());

        // Signed, then tampered with.
        String signature = InboundWebhookService.sign(SECRET, body);

        mockMvc.perform(post("/api/v1/webhooks/bank-rail/inbound")
                        .header(InboundWebhookService.SIGNATURE_HEADER, signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("1000000", "9000000")))
                .andExpect(status().isUnauthorized());

        assertThat(transactionCount()).isZero();
    }

    @Test
    void accountsThatCannotReceiveShouldBeRejectedSoTheMoneyGoesBack() throws Exception {

        Holder esther = activeHolder("esther");
        jdbcTemplate.update("UPDATE accounts SET status = 'FROZEN' WHERE id = ?::uuid", esther.accountId());

        webhook(notification("SESSION-0004", esther.accountNumber(), "100", null))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        webhook(notification("SESSION-0005", "0123456789", "100", null))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reason").value("Account number not found"));

        assertThat(transactionCount()).isZero();
    }

    @Test
    void malformedNotificationsShouldBeRefused() throws Exception {

        Holder esther = activeHolder("esther");

        webhook(notification("SESSION-0006", esther.accountNumber(), "-5", null))
                .andExpect(status().isBadRequest());

        webhook("{ not json")
                .andExpect(status().isBadRequest());

        assertThat(transactionCount()).isZero();
    }

    // ============================================================
    // INBOUND: THE TEST BANK SIMULATOR
    // ============================================================

    @Test
    void customerShouldSendThemselvesMoneyFromTheirTestBankAccount() throws Exception {

        Holder esther = activeHolder("esther");

        mockMvc.perform(authorized(get("/api/v1/simulator/test-bank/account").param("accountId", esther.accountId()),
                        esther.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankName").value("Test Bank"))
                .andExpect(jsonPath("$.accountName").value("Test Holder"))
                .andExpect(jsonPath("$.accountNumber").value(matchesPattern("7\\d{9}")))
                .andExpect(jsonPath("$.remaining").value(5000.0));

        fromTestBank(esther, esther.accountNumber(), "3000")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("INBOUND_TRANSFER"))
                .andExpect(jsonPath("$.counterparty.name").value("Test Holder"))
                .andExpect(jsonPath("$.counterparty.bank").value("Test Bank"))
                .andExpect(jsonPath("$.counterparty.accountNumber").value(testBank.accountNumberFor(UUID.fromString(esther.customerId()))))
                .andExpect(jsonPath("$.providerReference").value(matchesPattern("999001\\d{24}")));

        assertThat(balance(esther)).isEqualByComparingTo("3000");

        // Test Bank only sends pretend money up to its limit per account.
        fromTestBank(esther, esther.accountNumber(), "2000.01")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("SIMULATOR_LIMIT_EXCEEDED"));

        fromTestBank(esther, esther.accountNumber(), "2000").andExpect(status().isCreated());

        assertThat(balance(esther)).isEqualByComparingTo("5000");
    }

    // ============================================================
    // NAME ENQUIRY
    // ============================================================

    @Test
    void nameEnquiryShouldNameTheAccountHolder() throws Exception {

        Holder esther = activeHolder("esther");
        Holder john = activeHolder("john");

        mockMvc.perform(authorized(get("/api/v1/banks"), esther.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("PayCore"))
                .andExpect(jsonPath("$[1].name").value("Test Bank"));

        nameEnquiry(esther, "100999", john.accountNumber())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountName").value("Test Holder"))
                .andExpect(jsonPath("$.bankName").value("PayCore"));

        // Their own Test Bank account carries their own name.
        nameEnquiry(esther, "999001", testBank.accountNumberFor(UUID.fromString(esther.customerId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountName").value("Test Holder"));

        nameEnquiry(esther, "999001", "0001234567")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("BENEFICIARY_NOT_FOUND"));

        nameEnquiry(esther, "123456", "1234567890")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("UNKNOWN_BANK"));
    }

    // ============================================================
    // OUTBOUND
    // ============================================================

    @Test
    void outboundTransferShouldDebitTheCustomerAndPayThroughTheRail() throws Exception {

        Holder esther = funded("esther", "1000");

        sendOut(esther, "4123456789", "250", "out-0001")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transferStatus").value("SUCCESSFUL"))
                .andExpect(jsonPath("$.type").value("OUTBOUND_TRANSFER"))
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.counterparty.bank").value("Test Bank"))
                .andExpect(jsonPath("$.counterparty.accountNumber").value("4123456789"))
                .andExpect(jsonPath("$.providerReference").value(matchesPattern("100999\\d{24}")))
                .andExpect(jsonPath("$.description").value(startsWith("Transfer to ")));

        assertThat(balance(esther)).isEqualByComparingTo("750");
        assertThat(settlementBalance()).isEqualByComparingTo("750");
    }

    @Test
    void rejectedTransferShouldBeReversedAutomatically() throws Exception {

        Holder esther = funded("esther", "1000");

        String body =
                sendOut(esther, "4123456789", "100.99", "out-0002")
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.transferStatus").value("FAILED"))
                        .andExpect(jsonPath("$.status").value("REVERSED"))
                        .andExpect(jsonPath("$.failureReason").value(startsWith("Beneficiary bank rejected")))
                        .andExpect(jsonPath("$.reversalReference").value(startsWith("TXN-")))
                        .andReturn().getResponse().getContentAsString();

        // The customer has their money back; the history shows both.
        assertThat(balance(esther)).isEqualByComparingTo("1000");
        assertThat(settlementBalance()).isEqualByComparingTo("1000");

        mockMvc.perform(authorized(get("/api/v1/ledger/accounts/{id}/statement", esther.accountId()), esther.token()))
                .andExpect(jsonPath("$.lines", hasSize(3)))
                .andExpect(jsonPath("$.lines[1].transactionStatus").value("REVERSED"))
                .andExpect(jsonPath("$.lines[2].transactionType").value("REVERSAL"))
                .andExpect(jsonPath("$.lines[2].direction").value("CREDIT"));

        // The rail made the reversal, not staff: no staff note, no approver.
        assertThat(jdbcTemplate.queryForMap(
                "SELECT initiated_by::text AS by, staff_note, approved_by, provider FROM ledger_transactions "
                        + "WHERE reference = ?",
                (String) JsonPath.read(body, "$.reversalReference")
        ))
                .containsEntry("by", esther.customerId())
                .containsEntry("staff_note", null)
                .containsEntry("approved_by", null)
                .containsEntry("provider", "SIMULATED");

        // A retry reports the same failed transfer and pays nothing.
        sendOut(esther, "4123456789", "100.99", "out-0002")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transferStatus").value("FAILED"))
                .andExpect(jsonPath("$.id").value((String) JsonPath.read(body, "$.id")));

        assertThat(balance(esther)).isEqualByComparingTo("1000");
    }

    @Test
    void retryShouldReturnTheSameTransferAndPayOnce() throws Exception {

        Holder esther = funded("esther", "1000");

        String first = JsonPath.read(
                sendOut(esther, "4123456789", "300", "out-0003").andReturn().getResponse().getContentAsString(),
                "$.id"
        );

        sendOut(esther, "4123456789", "300", "out-0003")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(first))
                .andExpect(jsonPath("$.transferStatus").value("SUCCESSFUL"));

        assertThat(balance(esther)).isEqualByComparingTo("700");
    }

    @Test
    void outboundTransfersShouldNeverSpendTheSameMoneyTwice() throws Exception {

        Holder esther = funded("esther", "1000");

        // Over budget: refused before anything is sent.
        sendOut(esther, "4123456789", "1000.01", "out-0004")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_FUNDS"));

        // Unknown beneficiary: refused before anything is debited.
        sendOut(esther, "0001234567", "10", "out-0005")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("BENEFICIARY_NOT_FOUND"));

        assertThat(balance(esther)).isEqualByComparingTo("1000");

        // Two ₦800 transfers at once: only one fits.
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String key = "out-race-" + i;
            tasks.add(() -> sendOut(esther, "4123456789", "800", key).andReturn().getResponse().getStatus());
        }

        assertThat(runTogether(tasks)).containsExactlyInAnyOrder(201, 422);
        assertThat(balance(esther)).isEqualByComparingTo("200");
    }

    @Test
    void customersCanOnlySendFromTheirOwnActiveAccounts() throws Exception {

        Holder esther = funded("esther", "1000");
        Holder mallory = activeHolder("mallory");

        mockMvc.perform(authorized(post("/api/v1/ledger/accounts/{id}/bank-transfers", esther.accountId()), mallory.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(outboundBody("4123456789", "10", "out-0006")))
                .andExpect(status().isNotFound());

        jdbcTemplate.update("UPDATE accounts SET status = 'FROZEN' WHERE id = ?::uuid", esther.accountId());

        sendOut(esther, "4123456789", "10", "out-0007")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_ACCOUNT_STATE"));

        mockMvc.perform(post("/api/v1/ledger/accounts/{id}/bank-transfers", esther.accountId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(outboundBody("4123456789", "10", "out-0008")))
                .andExpect(status().isUnauthorized());

        assertThat(balance(esther)).isEqualByComparingTo("1000");
    }

    @Test
    void settlementShouldAlwaysEqualWhatPayCoreOwesItsCustomers() throws Exception {

        Holder esther = funded("esther", "1000");
        Holder john = funded("john", "500");

        sendOut(esther, "4123456789", "100", "out-0009");
        sendOut(john, "4123456789", "50.99", "out-0010");   // rejected and reversed
        mockMvc.perform(authorized(post("/api/v1/ledger/accounts/{id}/transfers", esther.accountId()), esther.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "destinationAccountNumber": "%s", "amount": 300, "currency": "NGN",
                                  "idempotencyKey": "internal-0001" }
                                """.formatted(john.accountNumber())))
                .andExpect(status().isCreated());

        assertThat(balance(esther).add(balance(john))).isEqualByComparingTo(settlementBalance());
        assertThat(settlementBalance()).isEqualByComparingTo("1400");

        // And the whole ledger balances.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT SUM(CASE WHEN entry_type = 'CREDIT' THEN amount_minor ELSE -amount_minor END) FROM ledger_entries",
                Long.class
        )).isZero();
    }

    // ============================================================
    // HELPERS
    // ============================================================

    private record Holder(String email, String customerId, String accountId, String accountNumber, String token) {
    }

    private Holder activeHolder(String name) throws Exception {

        String email = name + "@example.com";
        String customerId = register(email, "0805" + PHONES.incrementAndGet());

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

    /** An active customer with money that arrived from another bank. */
    private Holder funded(String name, String amount) throws Exception {

        Holder holder = activeHolder(name);

        webhook(notification("FUND-" + UUID.randomUUID(), holder.accountNumber(), amount, null))
                .andExpect(status().isOk());

        return holder;
    }

    private static String notification(String sessionId, String accountNumber, String amount, String narration) {

        return """
                {
                    "sessionId": "%s",
                    "destinationAccountNumber": "%s",
                    "amount": %s,
                    "currency": "NGN",
                    "senderName": "Chiamaka Obi",
                    "senderBank": "Test Bank",
                    "senderAccountNumber": "7000000001",
                    "narration": %s
                }
                """.formatted(sessionId, accountNumber, amount, narration == null ? "null" : "\"" + narration + "\"");
    }

    private ResultActions webhook(String body) throws Exception {

        return mockMvc.perform(post("/api/v1/webhooks/bank-rail/inbound")
                .header(InboundWebhookService.SIGNATURE_HEADER, InboundWebhookService.sign(SECRET, body))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions fromTestBank(Holder sender, String destination, String amount) throws Exception {

        return mockMvc.perform(authorized(post("/api/v1/simulator/test-bank/transfers"), sender.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "destinationAccountNumber": "%s", "amount": %s }
                        """.formatted(destination, amount)));
    }

    private ResultActions nameEnquiry(Holder holder, String bankCode, String accountNumber) throws Exception {

        return mockMvc.perform(authorized(post("/api/v1/banks/name-enquiry"), holder.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "bankCode": "%s", "accountNumber": "%s" }
                        """.formatted(bankCode, accountNumber)));
    }

    private ResultActions sendOut(Holder holder, String accountNumber, String amount, String key) throws Exception {

        return mockMvc.perform(authorized(post("/api/v1/ledger/accounts/{id}/bank-transfers", holder.accountId()), holder.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(outboundBody(accountNumber, amount, key)));
    }

    private static String outboundBody(String accountNumber, String amount, String key) {
        return """
                { "bankCode": "999001", "accountNumber": "%s", "amount": %s, "currency": "NGN",
                  "idempotencyKey": "%s" }
                """.formatted(accountNumber, amount, key);
    }

    private BigDecimal balance(Holder holder) throws Exception {

        String body =
                mockMvc.perform(authorized(get("/api/v1/ledger/accounts/{id}/balance", holder.accountId()), holder.token()))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString();

        return new BigDecimal(String.valueOf((Object) JsonPath.read(body, "$.balance")));
    }

    /** Money PayCore holds at its bank: debits minus credits on settlement. */
    private BigDecimal settlementBalance() {

        Long minor = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(CASE WHEN e.entry_type = 'DEBIT' THEN e.amount_minor ELSE -e.amount_minor END), 0) "
                        + "FROM ledger_entries e JOIN accounts a ON a.id = e.account_id WHERE a.account_type = 'SETTLEMENT'",
                Long.class
        );

        return BigDecimal.valueOf(minor, 2);
    }

    private Integer transactionCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_transactions", Integer.class);
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
