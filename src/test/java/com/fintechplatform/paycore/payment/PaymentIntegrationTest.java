package com.fintechplatform.paycore.payment;

import com.fintechplatform.paycore.banktransfer.rail.BankRailException;
import com.fintechplatform.paycore.banktransfer.rail.RailPayment;
import com.fintechplatform.paycore.banktransfer.simulator.SimulatedBankRail;
import com.fintechplatform.paycore.banktransfer.simulator.TestBank;
import com.fintechplatform.paycore.funding.provider.FundingCollection;
import com.fintechplatform.paycore.funding.provider.SimulatedFundingProvider;
import com.fintechplatform.paycore.funding.service.FundingService;
import com.fintechplatform.paycore.ledger.domain.Counterparty;
import com.fintechplatform.paycore.ledger.domain.Currency;
import com.fintechplatform.paycore.ledger.domain.Money;
import com.fintechplatform.paycore.ledger.dto.request.FundAccountRequest;
import com.fintechplatform.paycore.ledger.service.InboundTransfer;
import com.fintechplatform.paycore.ledger.service.LedgerService;
import com.fintechplatform.paycore.ledger.service.OutboundTransfer;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
        "paycore.payments.not-found-grace=0s",
        "paycore.payments.max-checks=2"
})
@AutoConfigureMockMvc
class PaymentIntegrationTest {

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

    private static final AtomicInteger PHONES = new AtomicInteger(7_000_000);

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
    private FundingService fundingService;

    @Autowired
    private SimulatedFundingProvider fundingProvider;

    @MockitoSpyBean
    private SimulatedBankRail rail;

    @BeforeEach
    void cleanDatabase() {

        jdbcTemplate.execute("TRUNCATE ledger_entries, ledger_transactions CASCADE");

        for (String table : new String[]{
                "operations_requests",
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

    @Test
    void aSuccessfulTransferShouldBeTrackedFromCreatedToSucceeded() throws Exception {

        Holder esther = funded("esther", "1000");

        String body =
                sendOut(esther, "250", "pay-out-0001")
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.transferStatus").value("SUCCESSFUL"))
                        .andExpect(jsonPath("$.paymentStatus").value("SUCCEEDED"))
                        .andExpect(jsonPath("$.paymentReference").value(matchesPattern("PAY-\\d{8}-[0-9A-F]{16}")))
                        .andReturn().getResponse().getContentAsString();

        Payment payment = paymentService.get(UUID.fromString(JsonPath.read(body, "$.paymentId")));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getLedgerTransactionId().toString()).isEqualTo(JsonPath.read(body, "$.id"));
        assertThat(payment.getExternalReference()).isEqualTo(JsonPath.read(body, "$.providerReference"));
        assertThat(payment.getCompletedAt()).isNotNull();
        assertThat(history(payment)).containsExactly("null>CREATED", "CREATED>PROCESSING", "PROCESSING>SUCCEEDED");

        assertThat(balance(esther)).isEqualByComparingTo("750");
    }

    @Test
    void aRejectedTransferShouldFailAndReturnTheMoneyInOneStep() throws Exception {

        Holder esther = funded("esther", "1000");

        String body =
                sendOut(esther, "100.99", "pay-out-0002")
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.transferStatus").value("FAILED"))
                        .andExpect(jsonPath("$.paymentStatus").value("FAILED"))
                        .andExpect(jsonPath("$.reversalReference").value(startsWith("TXN-")))
                        .andReturn().getResponse().getContentAsString();

        Payment payment = paymentService.get(UUID.fromString(JsonPath.read(body, "$.paymentId")));

        assertThat(payment.getReversalTransactionId()).isNotNull();
        assertThat(payment.getFailureReason()).startsWith("Beneficiary bank rejected");
        assertThat(history(payment)).containsExactly("null>CREATED", "CREATED>PROCESSING", "PROCESSING>FAILED");
        assertThat(balance(esther)).isEqualByComparingTo("1000");
    }

    @Test
    void aTimeoutAfterTheRailPaidShouldStayDebitedUntilAStatusCheckConfirmsIt() throws Exception {

        Holder esther = funded("esther", "1000");

        String body =
                sendOut(esther, "200.98", "pay-out-0003")
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.transferStatus").value("PENDING"))
                        .andExpect(jsonPath("$.paymentStatus").value("UNKNOWN"))
                        .andReturn().getResponse().getContentAsString();

        UUID paymentId = UUID.fromString(JsonPath.read(body, "$.paymentId"));
        assertThat(balance(esther)).isEqualByComparingTo("799.02");

        sendOut(esther, "200.98", "pay-out-0003")
                .andExpect(jsonPath("$.paymentId").value(paymentId.toString()))
                .andExpect(jsonPath("$.transferStatus").value("PENDING"));
        assertThat(balance(esther)).isEqualByComparingTo("799.02");

        assertThat(recoveryService.recoverDue().checked()).isEqualTo(1);

        Payment payment = paymentService.get(paymentId);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getReversalTransactionId()).isNull();
        assertThat(payment.getStatusChecks()).isEqualTo(1);
        assertThat(history(payment))
                .containsExactly("null>CREATED", "CREATED>PROCESSING", "PROCESSING>UNKNOWN", "UNKNOWN>SUCCEEDED");
        assertThat(balance(esther)).isEqualByComparingTo("799.02");

        sendOut(esther, "200.98", "pay-out-0003")
                .andExpect(jsonPath("$.transferStatus").value("SUCCESSFUL"));
    }

    @Test
    void aTimeoutBeforeTheRailShouldBeReversedOnceTheRailHasNoRecordOfIt() throws Exception {

        Holder esther = funded("esther", "1000");

        String body =
                sendOut(esther, "300.97", "pay-out-0004")
                        .andExpect(jsonPath("$.transferStatus").value("PENDING"))
                        .andReturn().getResponse().getContentAsString();

        assertThat(balance(esther)).isEqualByComparingTo("699.03");

        recoveryService.recoverDue();

        Payment payment = paymentService.get(UUID.fromString(JsonPath.read(body, "$.paymentId")));
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getReversalTransactionId()).isNotNull();
        assertThat(payment.getFailureReason()).contains("did not reach");
        assertThat(balance(esther)).isEqualByComparingTo("1000");
        assertThat(settlementBalance()).isEqualByComparingTo("1000");
    }

    @Test
    void aTransferDebitedButNeverSentShouldBeReturned() throws Exception {

        Holder esther = funded("esther", "1000");
        String sessionId = "100999" + "0".repeat(18) + "000001";

        Payment payment = paymentService.initiateOutbound(
                UUID.fromString(esther.customerId()), UUID.fromString(esther.accountId()),
                outbound("150", "pay-out-0005", sessionId), TestBank.BANK.code()
        );
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CREATED);
        assertThat(balance(esther)).isEqualByComparingTo("850");

        assertThat(recoveryService.recoverDue().abandoned()).isEqualTo(1);

        payment = paymentService.get(payment.getId());
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(history(payment)).containsExactly("null>CREATED", "CREATED>FAILED");
        assertThat(rail.status(sessionId).outcome().name()).isEqualTo("NOT_FOUND");
        assertThat(balance(esther)).isEqualByComparingTo("1000");
    }

    @Test
    void aTransferLeftProcessingShouldBeSettledByAskingTheRail() throws Exception {

        Holder esther = funded("esther", "1000");
        String sessionId = "100999" + "0".repeat(18) + "000002";

        Payment payment = paymentService.initiateOutbound(
                UUID.fromString(esther.customerId()), UUID.fromString(esther.accountId()),
                outbound("400", "pay-out-0006", sessionId), TestBank.BANK.code()
        );
        paymentService.markProcessing(payment.getId());
        rail.send(new RailPayment(sessionId, TestBank.BANK.code(), BENEFICIARY, "Ibrahim Musa",
                payment.amount(), "Rent", "Test Holder"));

        RecoverySweep sweep = RecoverySweep.of(recoveryService.recoverDue());
        assertThat(sweep.markedUnknown()).isEqualTo(1);
        assertThat(sweep.checked()).isEqualTo(1);

        payment = paymentService.get(payment.getId());
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(history(payment))
                .containsExactly("null>CREATED", "CREATED>PROCESSING", "PROCESSING>UNKNOWN", "UNKNOWN>SUCCEEDED");
        assertThat(balance(esther)).isEqualByComparingTo("600");
    }

    @Test
    void checksThatNeverGetAnAnswerShouldEndInReviewAndOperationsCanCheckAgain() throws Exception {

        Holder esther = funded("esther", "1000");
        Holder officer = staff(activeHolder("officer"), "OPERATIONS_OFFICER");

        doThrow(new BankRailException("TSQ unavailable", null)).when(rail).status(anyString());

        String body = sendOut(esther, "100.97", "pay-out-0007").andReturn().getResponse().getContentAsString();
        UUID paymentId = UUID.fromString(JsonPath.read(body, "$.paymentId"));

        recoveryService.recoverDue();
        assertThat(paymentService.get(paymentId).getStatus()).isEqualTo(PaymentStatus.UNKNOWN);

        recoveryService.recoverDue();
        Payment payment = paymentService.get(paymentId);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.NEEDS_REVIEW);
        assertThat(payment.getStatusChecks()).isEqualTo(2);
        assertThat(balance(esther)).isEqualByComparingTo("899.03");

        assertThat(recoveryService.recoverDue().checked()).isZero();

        mockMvc.perform(authorized(get("/api/v1/admin/payments?status=NEEDS_REVIEW"), officer.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payments[0].id").value(paymentId.toString()))
                .andExpect(jsonPath("$.page.totalElements").value(1));

        doCallRealMethod().when(rail).status(anyString());

        mockMvc.perform(authorized(post("/api/v1/admin/payments/{id}/status-check", paymentId), officer.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.reversalTransactionId").isNotEmpty());

        assertThat(balance(esther)).isEqualByComparingTo("1000");

        mockMvc.perform(authorized(get("/api/v1/admin/payments/{id}/events", paymentId), officer.token()))
                .andExpect(jsonPath("$[*].toStatus").value(org.hamcrest.Matchers.contains(
                        "CREATED", "PROCESSING", "UNKNOWN", "NEEDS_REVIEW", "FAILED"
                )));
    }

    @Test
    void aTopUpShouldBeTrackedAndLinkedToItsCredit() throws Exception {

        Holder esther = activeHolder("esther");

        String body =
                topUp(esther, "500", "pay-fund-0001")
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString();

        Payment payment = onlyPayment();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getLedgerTransactionId().toString()).isEqualTo(JsonPath.read(body, "$.id"));
        assertThat(payment.getProviderReference()).isEqualTo(JsonPath.read(body, "$.providerReference"));
        assertThat(payment.getExternalReference()).matches("PC-[0-9A-F]{32}");
        assertThat(history(payment)).containsExactly("null>PROCESSING", "PROCESSING>SUCCEEDED");
    }

    @Test
    void aTopUpChargedButUnansweredShouldBeCreditedByAStatusCheck() throws Exception {

        Holder esther = activeHolder("esther");

        String body =
                topUp(esther, "300.98", "pay-fund-0002")
                        .andExpect(status().isAccepted())
                        .andExpect(header().string("Location", startsWith("/api/v1/payments/")))
                        .andExpect(jsonPath("$.status").value("UNKNOWN"))
                        .andExpect(jsonPath("$.type").value("CARD_FUNDING"))
                        .andReturn().getResponse().getContentAsString();

        String paymentId = JsonPath.read(body, "$.id");
        assertThat(balance(esther)).isEqualByComparingTo("0");

        mockMvc.perform(authorized(get("/api/v1/payments/{id}", paymentId), esther.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNKNOWN"));

        topUp(esther, "300.98", "pay-fund-0002").andExpect(status().isAccepted());

        recoveryService.recoverDue();

        assertThat(balance(esther)).isEqualByComparingTo("300.98");
        mockMvc.perform(authorized(get("/api/v1/payments/{id}", paymentId), esther.token()))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.ledgerTransactionId").isNotEmpty());

        topUp(esther, "300.98", "pay-fund-0002").andExpect(status().isCreated());
        recoveryService.recoverDue();
        assertThat(balance(esther)).isEqualByComparingTo("300.98");
        assertThat(depositCount()).isEqualTo(1);
    }

    @Test
    void aTopUpThatNeverReachedTheProviderShouldFailWithoutCredit() throws Exception {

        Holder esther = activeHolder("esther");

        topUp(esther, "300.97", "pay-fund-0003").andExpect(status().isAccepted());

        recoveryService.recoverDue();

        Payment payment = onlyPayment();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).contains("not charged");
        assertThat(balance(esther)).isEqualByComparingTo("0");

        topUp(esther, "300.97", "pay-fund-0003")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("PAYMENT_DECLINED"));
    }

    @Test
    void aTopUpChargedBeforePayCoreStoppedShouldBeCreditedOnRecovery() throws Exception {

        Holder esther = activeHolder("esther");
        UUID customerId = UUID.fromString(esther.customerId());
        Money amount = Money.ofMajor(new BigDecimal("700"), Currency.of("NGN"));
        String merchantReference = FundingService.merchantReference(customerId, "pay-fund-0004");

        Payment payment = paymentService.startFunding(
                customerId, UUID.fromString(esther.accountId()), amount, "pay-fund-0004",
                "SIMULATED", merchantReference, "Top-up by card (simulated)"
        );
        fundingProvider.collect(new FundingCollection(customerId, payment.getAccountId(), amount, merchantReference));

        recoveryService.recoverDue();

        assertThat(paymentService.get(payment.getId()).getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(balance(esther)).isEqualByComparingTo("700");
    }

    @Test
    void aChargeThatCannotBeCreditedShouldGoToReview() throws Exception {

        Holder esther = activeHolder("esther");

        topUp(esther, "250.98", "pay-fund-0005").andExpect(status().isAccepted());
        jdbcTemplate.update("UPDATE accounts SET status = 'FROZEN' WHERE id = ?::uuid", esther.accountId());

        recoveryService.recoverDue();

        Payment payment = onlyPayment();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.NEEDS_REVIEW);
        assertThat(payment.getFailureReason()).contains("charged but");
        assertThat(depositCount()).isZero();
    }

    @Test
    void concurrentTopUpsWithOneKeyShouldChargeAndCreditOnce() throws Exception {

        Holder esther = activeHolder("esther");
        FundAccountRequest request = new FundAccountRequest(new BigDecimal("1000"), "NGN", "pay-fund-0006");

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> fundingService.fund(
                    UUID.fromString(esther.customerId()), UUID.fromString(esther.accountId()), request
            ));
        }

        assertThat(runTogether(tasks)).noneMatch(o -> o instanceof Throwable);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payments", Integer.class)).isEqualTo(1);
        assertThat(depositCount()).isEqualTo(1);
        assertThat(balance(esther)).isEqualByComparingTo("1000");
    }

    @Test
    void customersShouldOnlySeeTheirOwnPaymentsAndNeverCheckThem() throws Exception {

        Holder esther = funded("esther", "1000");
        Holder john = activeHolder("john");

        String body = sendOut(esther, "10", "pay-out-0008").andReturn().getResponse().getContentAsString();
        String paymentId = JsonPath.read(body, "$.paymentId");
        String reference = JsonPath.read(body, "$.paymentReference");

        mockMvc.perform(authorized(get("/api/v1/payments/{id}", paymentId), esther.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reference").value(reference))
                .andExpect(jsonPath("$.counterparty.accountNumber").value(BENEFICIARY));

        mockMvc.perform(authorized(get("/api/v1/payments/reference/{reference}", reference), esther.token()))
                .andExpect(status().isOk());

        mockMvc.perform(authorized(get("/api/v1/payments/{id}", paymentId), john.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PAYMENT_NOT_FOUND"));

        mockMvc.perform(authorized(get("/api/v1/admin/payments"), esther.token()))
                .andExpect(status().isForbidden());

        mockMvc.perform(authorized(post("/api/v1/admin/payments/{id}/status-check", paymentId), esther.token()))
                .andExpect(status().isForbidden());
    }

    @Test
    void aSettledPaymentCannotBeCheckedAgainAndAnInFlightOneIsRefused() throws Exception {

        Holder esther = funded("esther", "1000");
        Holder officer = staff(activeHolder("officer"), "OPERATIONS_OFFICER");

        String body = sendOut(esther, "10", "pay-out-0009").andReturn().getResponse().getContentAsString();
        String paymentId = JsonPath.read(body, "$.paymentId");

        mockMvc.perform(authorized(post("/api/v1/admin/payments/{id}/status-check", paymentId), officer.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.statusChecks").value(0));

        Payment created = paymentService.initiateOutbound(
                UUID.fromString(esther.customerId()), UUID.fromString(esther.accountId()),
                outbound("10", "pay-out-0010", "100999" + "0".repeat(18) + "000003"), TestBank.BANK.code()
        );

        mockMvc.perform(authorized(post("/api/v1/admin/payments/{id}/status-check", created.getId()), officer.token()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PAYMENT_STATE_CONFLICT"));
    }

    @Test
    void databaseShouldEnforceThePaymentStateMachine() throws Exception {

        Holder esther = funded("esther", "1000");
        String body = sendOut(esther, "10", "pay-out-0011").andReturn().getResponse().getContentAsString();
        String paymentId = JsonPath.read(body, "$.paymentId");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE payments SET status = 'FAILED', failure_reason = 'x', completed_at = now() WHERE id = ?::uuid",
                paymentId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("cannot change");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE payments SET amount_minor = 1 WHERE id = ?::uuid", paymentId))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM payments WHERE id = ?::uuid", paymentId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("cannot be deleted");

        Payment created = paymentService.initiateOutbound(
                UUID.fromString(esther.customerId()), UUID.fromString(esther.accountId()),
                outbound("10", "pay-out-0012", "100999" + "0".repeat(18) + "000004"), TestBank.BANK.code()
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE payments SET status = 'SUCCEEDED', completed_at = now() WHERE id = ?::uuid", created.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("cannot move from CREATED to SUCCEEDED");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO payments (id, reference, type, status, customer_id, account_id, amount_minor, currency, "
                        + "idempotency_key, provider, external_reference, created_at, updated_at) "
                        + "VALUES (paycore_uuid_v7(), 'PAY-DUP', 'CARD_FUNDING', 'PROCESSING', ?::uuid, ?::uuid, 100, "
                        + "'NGN', 'pay-out-0011', 'SIMULATED', 'PC-DUP', now(), now())",
                esther.customerId(), esther.accountId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_payments_customer_idempotency_key");
    }

    private record Holder(String email, String customerId, String accountId, String accountNumber, String token) {

        Holder withToken(String newToken) {
            return new Holder(email, customerId, accountId, accountNumber, newToken);
        }
    }

    private record RecoverySweep(int abandoned, int markedUnknown, int checked) {

        static RecoverySweep of(PaymentRecoveryService.Sweep sweep) {
            return new RecoverySweep(sweep.abandoned(), sweep.markedUnknown(), sweep.checked());
        }
    }

    private static OutboundTransfer outbound(String amount, String key, String sessionId) {

        return new OutboundTransfer(
                new BigDecimal(amount), "NGN", key, null, SimulatedBankRail.PROVIDER_NAME, sessionId,
                new Counterparty("Ibrahim Musa", TestBank.BANK.name(), BENEFICIARY)
        );
    }

    private List<String> history(Payment payment) {

        return paymentService.events(payment.getId()).stream()
                .map(event -> event.fromStatus() + ">" + event.toStatus())
                .toList();
    }

    private Payment onlyPayment() {

        return paymentService.get(jdbcTemplate.queryForObject("SELECT id FROM payments", UUID.class));
    }

    private Integer depositCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_transactions WHERE type = 'DEPOSIT'", Integer.class);
    }

    private Holder activeHolder(String name) throws Exception {

        String email = name + "@example.com";
        String customerId = register(email, "0806" + PHONES.incrementAndGet());

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

    private ResultActions sendOut(Holder holder, String amount, String key) throws Exception {

        return mockMvc.perform(authorized(post("/api/v1/ledger/accounts/{id}/bank-transfers", holder.accountId()), holder.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "bankCode": "%s", "accountNumber": "%s", "amount": %s, "currency": "NGN",
                          "idempotencyKey": "%s" }
                        """.formatted(TestBank.BANK.code(), BENEFICIARY, amount, key)));
    }

    private ResultActions topUp(Holder holder, String amount, String key) throws Exception {

        return mockMvc.perform(authorized(post("/api/v1/ledger/accounts/{id}/fundings", holder.accountId()), holder.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "amount": %s, "currency": "NGN", "idempotencyKey": "%s" }
                        """.formatted(amount, key)));
    }

    private BigDecimal balance(Holder holder) throws Exception {

        String body =
                mockMvc.perform(authorized(get("/api/v1/ledger/accounts/{id}/balance", holder.accountId()), holder.token()))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString();

        return new BigDecimal(String.valueOf((Object) JsonPath.read(body, "$.balance")));
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
