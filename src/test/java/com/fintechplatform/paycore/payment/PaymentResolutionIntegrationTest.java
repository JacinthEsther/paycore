package com.fintechplatform.paycore.payment;

import com.fintechplatform.paycore.banktransfer.rail.RailStatus;
import com.fintechplatform.paycore.banktransfer.simulator.RailSessionRecords;
import com.fintechplatform.paycore.banktransfer.simulator.SimulatedBankRail;
import com.fintechplatform.paycore.banktransfer.simulator.TestBank;
import com.fintechplatform.paycore.funding.provider.CardPaymentRecords;
import com.fintechplatform.paycore.funding.provider.FundingStatus;
import com.fintechplatform.paycore.funding.provider.SimulatedFundingProvider;
import com.fintechplatform.paycore.ledger.domain.Counterparty;
import com.fintechplatform.paycore.ledger.service.InboundTransfer;
import com.fintechplatform.paycore.ledger.service.LedgerService;
import com.fintechplatform.paycore.payment.entity.Payment;
import com.fintechplatform.paycore.payment.enums.PaymentStatus;
import com.fintechplatform.paycore.payment.service.PaymentRecoveryService;
import com.fintechplatform.paycore.payment.service.PaymentService;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = {
        "paycore.rails.provider=simulated",
        "paycore.funding.provider=simulated",
        "paycore.payments.recovery.enabled=false",
        "paycore.payments.stale-after=0s",
        "paycore.payments.first-check-after=0s",
        "paycore.payments.max-backoff=0s",
        "paycore.payments.not-found-grace=0s"
})
@AutoConfigureMockMvc
class PaymentResolutionIntegrationTest {

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

    private static final AtomicInteger PHONES = new AtomicInteger(8_000_000);

    private static final String TEST_DEVICE_ID = "integration-test-device";
    private static final String TEST_PIN = "2580";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRecoveryService recoveryService;

    @Autowired
    private TestBank testBank;

    @Autowired
    private RailSessionRecords railSessions;

    @Autowired
    private CardPaymentRecords cardPayments;

    private Holder maker;
    private Holder checker;

    @BeforeEach
    void cleanDatabase() throws Exception {

        jdbcTemplate.execute("TRUNCATE ledger_entries, ledger_transactions CASCADE");

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
    void anUnconfirmableTransferShouldBeCompletedByTwoOfficers() throws Exception {

        Holder esther = funded("esther", "1000");
        String paymentId = sendOut(esther, "100.96", "res-out-0001");

        recoveryService.recoverDue();
        assertThat(paymentService.get(UUID.fromString(paymentId)).getStatus()).isEqualTo(PaymentStatus.UNKNOWN);

        requestResolution(maker, paymentId, "COMPLETE").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PAYMENT_STATE_CONFLICT"));

        escalate(maker, paymentId).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NEEDS_REVIEW"));

        String requestId = JsonPath.read(
                requestResolution(maker, paymentId, "COMPLETE")
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.type").value("PAYMENT_RESOLUTION"))
                        .andExpect(jsonPath("$.resolution").value("COMPLETE"))
                        .andExpect(jsonPath("$.paymentType").value("OUTBOUND_TRANSFER"))
                        .andExpect(jsonPath("$.paymentReference").value(containsString("PAY-")))
                        .andExpect(jsonPath("$.amount").value(100.96))
                        .andExpect(jsonPath("$.accountNumber").value(esther.accountNumber()))
                        .andReturn().getResponse().getContentAsString(),
                "$.id"
        );

        assertThat(paymentService.get(UUID.fromString(paymentId)).getStatus()).isEqualTo(PaymentStatus.NEEDS_REVIEW);
        approve(maker, requestId).andExpect(status().isForbidden());

        int transactions = transactionCount();

        approve(checker, requestId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.resultTransactionId").value(nullValue()));

        assertThat(paymentService.get(UUID.fromString(paymentId)).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(transactionCount()).isEqualTo(transactions);
        assertThat(balance(esther)).isEqualByComparingTo("899.04");
    }

    @Test
    void anUnpaidTransferShouldBeReversedWithBothOfficersOnRecord() throws Exception {

        Holder esther = funded("esther", "1000");
        String paymentId = sendOut(esther, "100.96", "res-out-0002");
        escalate(maker, paymentId).andExpect(status().isOk());

        String requestId = JsonPath.read(
                requestResolution(maker, paymentId, "REVERSE").andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString(),
                "$.id"
        );

        String reversal = JsonPath.read(
                approve(checker, requestId)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.resultReference").value(containsString("TXN-")))
                        .andReturn().getResponse().getContentAsString(),
                "$.resultTransactionId"
        );

        Payment payment = paymentService.get(UUID.fromString(paymentId));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getReversalTransactionId().toString()).isEqualTo(reversal);
        assertThat(balance(esther)).isEqualByComparingTo("1000");

        assertThat(jdbcTemplate.queryForMap(
                "SELECT initiated_by::text AS maker, approved_by::text AS checker, staff_note, type "
                        + "FROM ledger_transactions WHERE id = ?::uuid", reversal
        ))
                .containsEntry("maker", maker.customerId())
                .containsEntry("checker", checker.customerId())
                .containsEntry("staff_note", "Rail support confirmed the outcome")
                .containsEntry("type", "REVERSAL");
    }

    @Test
    void aResolutionTheRailContradictsShouldBeRefused() throws Exception {

        Holder esther = funded("esther", "1000");
        String paymentId = sendOut(esther, "100.98", "res-out-0003");
        escalate(maker, paymentId).andExpect(status().isOk());

        requestResolution(maker, paymentId, "REVERSE")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("RESOLUTION_CONTRADICTS_PROVIDER"))
                .andExpect(jsonPath("$.message").value(containsString("SUCCESSFUL")));

        mockMvc.perform(authorized(post("/api/v1/admin/payments/{id}/status-check", paymentId), maker.token()))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        assertThat(balance(esther)).isEqualByComparingTo("899.02");
    }

    @Test
    void aChargeTheProcessorWillNotConfirmShouldBeCreditedByTwoOfficers() throws Exception {

        Holder esther = activeHolder("esther");
        String paymentId = topUp(esther, "250.96", "res-fund-0001");
        escalate(maker, paymentId).andExpect(status().isOk());

        String requestId = JsonPath.read(
                requestResolution(maker, paymentId, "CREDIT").andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString(),
                "$.id"
        );

        approve(checker, requestId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultReference").value(containsString("TXN-")));

        Payment payment = paymentService.get(UUID.fromString(paymentId));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(balance(esther)).isEqualByComparingTo("250.96");
    }

    @Test
    void aChargeThatCannotBeCreditedShouldBeRefundedToTheCard() throws Exception {

        Holder esther = activeHolder("esther");
        String paymentId = topUp(esther, "300.98", "res-fund-0002");
        jdbcTemplate.update("UPDATE accounts SET status = 'FROZEN' WHERE id = ?::uuid", esther.accountId());
        recoveryService.recoverDue();
        assertThat(paymentService.get(UUID.fromString(paymentId)).getStatus()).isEqualTo(PaymentStatus.NEEDS_REVIEW);

        requestResolution(maker, paymentId, "CLOSE")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("RESOLUTION_CONTRADICTS_PROVIDER"));

        String credit = JsonPath.read(
                requestResolution(maker, paymentId, "CREDIT").andReturn().getResponse().getContentAsString(), "$.id"
        );
        approve(checker, credit).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_ACCOUNT_STATE"));
        mockMvc.perform(authorized(get("/api/v1/ops/requests/{id}", credit), checker.token()))
                .andExpect(jsonPath("$.status").value("PENDING"));

        requestResolution(maker, paymentId, "REFUND").andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_RESOLUTION_REQUEST"));

        mockMvc.perform(authorized(post("/api/v1/ops/requests/{id}/reject", credit), checker.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"note\": \"Account is frozen; refund instead\" }"))
                .andExpect(status().isOk());

        String refund = JsonPath.read(
                requestResolution(maker, paymentId, "REFUND").andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString(),
                "$.id"
        );
        approve(checker, refund).andExpect(status().isOk());

        Payment payment = paymentService.get(UUID.fromString(paymentId));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getRefundReference()).startsWith("SIMREF-");
        assertThat(payment.getFailureReason()).contains("refunded");
        assertThat(cardPayments.find(payment.getExternalReference()).orElseThrow().status()).isEqualTo("REFUNDED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_transactions WHERE type = 'DEPOSIT'", Integer.class)).isZero();

        mockMvc.perform(authorized(get("/api/v1/payments/{id}", paymentId), esther.token()))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.refundReference").value(payment.getRefundReference()));
    }

    @Test
    void aResolutionMustFitThePaymentAndNotBeTheOfficersOwn() throws Exception {

        Holder esther = activeHolder("esther");
        String paymentId = topUp(esther, "50.96", "res-fund-0003");
        escalate(maker, paymentId).andExpect(status().isOk());

        requestResolution(maker, paymentId, "COMPLETE").andExpect(status().isBadRequest());

        String own = topUp(maker, "20.96", "res-fund-0004");
        escalate(staff(activeHolder("chidi"), "OPERATIONS_OFFICER"), own).andExpect(status().isOk());
        requestResolution(maker, own, "CLOSE").andExpect(status().isForbidden());
    }

    @Test
    void aRestartedSimulatorShouldStillKnowWhatItDid() throws Exception {

        Holder esther = funded("esther", "1000");
        String transfer = sendOut(esther, "100.98", "res-out-0004");
        String topUp = topUp(esther, "70.98", "res-fund-0005");

        Payment transferPayment = paymentService.get(UUID.fromString(transfer));
        Payment topUpPayment = paymentService.get(UUID.fromString(topUp));

        SimulatedBankRail rail = new SimulatedBankRail(testBank, railSessions);
        SimulatedFundingProvider processor = new SimulatedFundingProvider(cardPayments);

        assertThat(rail.status(transferPayment.getExternalReference())).isEqualTo(RailStatus.successful());
        assertThat(processor.status(topUpPayment.getExternalReference()).outcome())
                .isEqualTo(FundingStatus.Outcome.CONFIRMED);

        assertThat(railSessions.find(transferPayment.getExternalReference()).orElseThrow())
                .extracting(RailSessionRecords.RailSession::direction, RailSessionRecords.RailSession::amountMinor)
                .containsExactly("OUTBOUND", 10_098L);
    }

    @Test
    void inboundTestBankTransfersShouldBeOnTheRailsRecords() throws Exception {

        Holder esther = activeHolder("esther");

        mockMvc.perform(authorized(post("/api/v1/simulator/test-bank/transfers"), esther.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"destinationAccountNumber\": \"%s\", \"amount\": 75 }".formatted(esther.accountNumber())))
                .andExpect(status().isCreated());

        String sessionId = jdbcTemplate.queryForObject(
                "SELECT provider_reference FROM ledger_transactions WHERE type = 'INBOUND_TRANSFER'", String.class);

        assertThat(railSessions.find(sessionId).orElseThrow())
                .extracting(RailSessionRecords.RailSession::direction, RailSessionRecords.RailSession::status,
                        RailSessionRecords.RailSession::amountMinor)
                .containsExactly("INBOUND", "SUCCESSFUL", 7_500L);
    }

    private record Holder(String email, String customerId, String accountId, String accountNumber, String token) {

        Holder withToken(String newToken) {
            return new Holder(email, customerId, accountId, accountNumber, newToken);
        }
    }

    private ResultActions requestResolution(Holder officer, String paymentId, String resolution) throws Exception {

        return mockMvc.perform(authorized(post("/api/v1/ops/requests/payment-resolutions"), officer.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "paymentId": "%s", "resolution": "%s", "reason": "Rail support confirmed the outcome" }
                        """.formatted(paymentId, resolution)));
    }

    private ResultActions approve(Holder officer, String requestId) throws Exception {

        return mockMvc.perform(authorized(post("/api/v1/ops/requests/{id}/approve", requestId), officer.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"note\": \"Evidence checked\" }"));
    }

    private ResultActions escalate(Holder officer, String paymentId) throws Exception {
        return mockMvc.perform(authorized(post("/api/v1/admin/payments/{id}/escalate", paymentId), officer.token()));
    }

    private String sendOut(Holder holder, String amount, String key) throws Exception {

        String body =
                mockMvc.perform(authorized(post("/api/v1/ledger/accounts/{id}/bank-transfers", holder.accountId()), holder.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        { "bankCode": "%s", "accountNumber": "%s", "amount": %s, "currency": "NGN",
                                          "idempotencyKey": "%s" }
                                        """.formatted(TestBank.BANK.code(), BENEFICIARY, amount, key)))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString();

        return JsonPath.read(body, "$.paymentId");
    }

    private String topUp(Holder holder, String amount, String key) throws Exception {

        String body =
                mockMvc.perform(authorized(post("/api/v1/ledger/accounts/{id}/fundings", holder.accountId()), holder.token())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        { "amount": %s, "currency": "NGN", "idempotencyKey": "%s" }
                                        """.formatted(amount, key)))
                        .andExpect(status().isAccepted())
                        .andReturn().getResponse().getContentAsString();

        return JsonPath.read(body, "$.id");
    }

    private Integer transactionCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_transactions", Integer.class);
    }

    private Holder activeHolder(String name) throws Exception {

        String email = name + "@example.com";
        String customerId = register(email, "0807" + PHONES.incrementAndGet());

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
