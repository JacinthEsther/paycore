package com.fintechplatform.paycore.limits;

import com.fintechplatform.paycore.kyc.enums.KycTierUpgradeStatus;
import com.fintechplatform.paycore.kyc.enums.VerificationResult;
import com.fintechplatform.paycore.kyc.provider.KycProvider;
import com.fintechplatform.paycore.kyc.provider.KycProviderResult;
import com.fintechplatform.paycore.kyc.service.KycTierService;
import com.fintechplatform.paycore.ledger.domain.Counterparty;
import com.fintechplatform.paycore.ledger.exception.InboundTransferRejectedException;
import com.fintechplatform.paycore.ledger.service.InboundTransfer;
import com.fintechplatform.paycore.ledger.service.LedgerService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = {
        "paycore.rails.provider=simulated",
        "paycore.rails.webhook-secret=tier-limits-test-webhook-secret-0123456789"
})
@AutoConfigureMockMvc
class KycTierLimitsIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("paycore")
                    .withUsername("postgres")
                    .withPassword("postgres");

    private static final Path DOCUMENT_DIR = createDocumentDir();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("paycore.kyc.documents.storage-dir", DOCUMENT_DIR::toString);
        registry.add("paycore.kyc.tier-upgrade.auto-approve", () -> "false");
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    private static Path createDocumentDir() {
        try {
            return Files.createTempDirectory("paycore-tier-documents");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static final AtomicInteger PHONES = new AtomicInteger(4_000_000);
    private static final String DEVICE = "tier-test-device-000001";
    private static final String PIN = "2580";
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private KycTierService tierService;

    @MockitoBean
    private KycProvider kycProvider;

    @BeforeEach
    void cleanDatabase() {

        jdbcTemplate.execute("TRUNCATE ledger_entries, ledger_transactions CASCADE");

        for (String table : new String[]{
                "kyc_tier_upgrades",
                "one_time_codes",
                "transaction_pins",
                "operations_requests",
                "account_status_events",
                "accounts",
                "kyc_verifications",
                "kyc_documents",
                "kyc_profiles",
                "refresh_tokens",
                "login_sessions",
                "customer_devices",
                "identities",
                "role_assignment_events",
                "customer_roles",
                "customers"
        }) {
            jdbcTemplate.execute("DELETE FROM " + table);
        }

        KycProviderResult passed = new KycProviderResult("TEST", "ref-" + UUID.randomUUID(), VerificationResult.PASSED, null);
        when(kycProvider.verifyBvn(any())).thenReturn(passed);
        when(kycProvider.verifyNin(any())).thenReturn(passed);
    }

    @Test
    void tierOneShouldSendAtMostFiftyThousandADay() throws Exception {

        Holder esther = holder("esther", 1);
        Holder john = holder("john", 3);
        fund(esther, "200000");

        transfer(esther, john, "30000", "daily-0001").andExpect(status().isCreated());
        transfer(esther, john, "20000", "daily-0002").andExpect(status().isCreated());

        transfer(esther, john, "0.01", "daily-0003")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.limit").value("DAILY"))
                .andExpect(jsonPath("$.remaining").value(0.0))
                .andExpect(jsonPath("$.resetsAt").exists());

        tier(esther)
                .andExpect(jsonPath("$.tier").value(1))
                .andExpect(jsonPath("$.dailySend").value(50000))
                .andExpect(jsonPath("$.sentToday").value(50000.0))
                .andExpect(jsonPath("$.maxBalance").value(300000));

        assertThat(balance(esther)).isEqualByComparingTo("150000");
    }

    @Test
    void reversedTransferShouldGiveTheDailyAllowanceBack() throws Exception {

        Holder esther = holder("esther", 1);
        fund(esther, "100000");

        sendToTestBank(esther, "49999.99", "daily-rev-01").andExpect(status().isCreated())
                .andExpect(jsonPath("$.transferStatus").value("FAILED"));

        Holder john = holder("john", 3);
        transfer(esther, john, "50000", "daily-rev-02").andExpect(status().isCreated());
    }

    @Test
    void tierThreeShouldSendMuchMore() throws Exception {

        Holder esther = holder("esther", 3);
        Holder john = holder("john", 3);
        fund(esther, "1000000");

        transfer(esther, john, "900000", "big-0001").andExpect(status().isCreated());
    }

    @Test
    void transferShouldNotTakeARecipientPastTheirBalanceCap() throws Exception {

        Holder esther = holder("esther", 3);
        Holder john = holder("john", 1);
        fund(esther, "100000");
        fund(john, "290000");

        transfer(esther, john, "20000", "cap-0001")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("BALANCE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.message").value("The recipient's account cannot receive this amount."));

        transfer(esther, john, "10000", "cap-0002").andExpect(status().isCreated());

        assertThat(balance(esther)).isEqualByComparingTo("90000");
        assertThat(balance(john)).isEqualByComparingTo("300000");
    }

    @Test
    void inboundTransferPastTheCapShouldBeReturnedToTheSendingBank() throws Exception {

        Holder john = holder("john", 1);
        fund(john, "300000");

        assertThatThrownBy(() -> fund(john, "1"))
                .isInstanceOf(InboundTransferRejectedException.class)
                .hasMessageContaining("balance limit");

        assertThat(balance(john)).isEqualByComparingTo("300000");
    }

    @Test
    void verifyingTheSecondIdentityNumberShouldEarnTierTwo() throws Exception {

        Holder esther = holder("esther", 1);
        startedWithBvn(esther);

        tier(esther)
                .andExpect(jsonPath("$.tier").value(1))
                .andExpect(jsonPath("$.nextTier").value(2))
                .andExpect(jsonPath("$.nextTierNeeds[0]").value("Verify your NIN"));

        verify(esther, "nin", "70123456789")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("PASSED"));

        tier(esther)
                .andExpect(jsonPath("$.tier").value(2))
                .andExpect(jsonPath("$.dailySend").value(200000))
                .andExpect(jsonPath("$.maxBalance").value(500000));

        verify(esther, "nin", "70123456789").andExpect(status().isConflict());
    }

    @Test
    void proofOfAddressApprovedByComplianceShouldEarnTierThree() throws Exception {

        Holder esther = holder("esther", 2);
        Holder admin = staff(holder("admin", 3));

        requestUpgrade(esther)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.upgrade.status").value("PENDING"))
                .andExpect(jsonPath("$.upgrade.requestedTier").value(3));

        requestUpgrade(esther)
                .andExpect(status().isConflict());

        String upgradeId =
                JsonPath.read(
                        mockMvc.perform(authorized(get("/api/v1/kyc/tier-upgrades"), admin))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$[0].customerEmail").value("esther@example.com"))
                                .andExpect(jsonPath("$[0].documentType").value("PROOF_OF_ADDRESS"))
                                .andReturn().getResponse().getContentAsString(),
                        "$[0].id"
                );

        mockMvc.perform(authorized(post("/api/v1/kyc/tier-upgrades/{id}/approve", upgradeId), esther))
                .andExpect(status().isForbidden());

        mockMvc.perform(authorized(post("/api/v1/kyc/tier-upgrades/{id}/approve", upgradeId), admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.currentTier").value(3));

        tier(esther)
                .andExpect(jsonPath("$.tier").value(3))
                .andExpect(jsonPath("$.maxBalance").doesNotExist())
                .andExpect(jsonPath("$.nextTier").doesNotExist());

        mockMvc.perform(authorized(post("/api/v1/kyc/tier-upgrades/{id}/approve", upgradeId), admin))
                .andExpect(status().isConflict());
    }

    @Test
    void rejectedUpgradeShouldSayWhyAndAllowAnotherTry() throws Exception {

        Holder esther = holder("esther", 2);
        Holder admin = staff(holder("admin", 3));

        requestUpgrade(esther).andExpect(status().isCreated());

        String upgradeId =
                JsonPath.read(
                        mockMvc.perform(authorized(get("/api/v1/kyc/tier-upgrades"), admin))
                                .andReturn().getResponse().getContentAsString(),
                        "$[0].id"
                );

        mockMvc.perform(authorized(post("/api/v1/kyc/tier-upgrades/{id}/reject", upgradeId), admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"reason\": \"The bill is older than three months\" }"))
                .andExpect(status().isOk());

        tier(esther)
                .andExpect(jsonPath("$.tier").value(2))
                .andExpect(jsonPath("$.upgrade.status").value("REJECTED"))
                .andExpect(jsonPath("$.upgrade.reason").value("The bill is older than three months"));

        requestUpgrade(esther)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.upgrade.needsCloserLook").value(true));
    }

    @Test
    void upgradeShouldBeApprovedAutomaticallyOnceTheReviewPeriodPassesCleanly() throws Exception {

        Holder esther = holder("esther", 2);

        requestUpgrade(esther)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reviewWorkingDays").value(3))
                .andExpect(jsonPath("$.upgrade.reviewDueAt").exists())
                .andExpect(jsonPath("$.upgrade.needsCloserLook").value(false))
                .andExpect(jsonPath("$.tiers.length()").value(3));

        assertThat(tierService.dueForAutomaticDecision()).isEmpty();

        jdbcTemplate.update("UPDATE kyc_tier_upgrades SET review_due_at = now() - INTERVAL '1 minute'");

        for (UUID due : tierService.dueForAutomaticDecision()) {
            assertThat(tierService.decideAutomatically(due)).contains(KycTierUpgradeStatus.APPROVED);
        }

        tier(esther)
                .andExpect(jsonPath("$.tier").value(3))
                .andExpect(jsonPath("$.upgrade.status").value("APPROVED"))
                .andExpect(jsonPath("$.upgrade.decidedAutomatically").value(true));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT decided_by IS NULL FROM kyc_tier_upgrades", Boolean.class)).isTrue();
    }

    @Test
    void somethingWrongShouldStopAutomaticApprovalAndLeaveItForCompliance() throws Exception {

        Holder esther = holder("esther", 2);
        Holder compliance = staff(holder("compliance", 3));

        requestUpgrade(esther).andExpect(status().isCreated());

        mockMvc.perform(authorized(post("/api/v1/admin/accounts/{id}/freeze", esther.accountId()), compliance)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"reason\": \"Suspicious credits\" }"))
                .andExpect(status().isOk());

        mockMvc.perform(authorized(get("/api/v1/kyc/tier-upgrades"), compliance))
                .andExpect(jsonPath("$[0].flags[0]").value("Account " + esther.accountNumber() + " is frozen"));

        tier(esther).andExpect(jsonPath("$.upgrade.needsCloserLook").value(true));

        jdbcTemplate.update("UPDATE kyc_tier_upgrades SET review_due_at = now() - INTERVAL '1 minute'");

        for (UUID due : tierService.dueForAutomaticDecision()) {
            assertThat(tierService.decideAutomatically(due)).isEmpty();
        }

        assertThat(tierService.dueForAutomaticDecision()).isEmpty();

        tier(esther)
                .andExpect(jsonPath("$.tier").value(2))
                .andExpect(jsonPath("$.upgrade.status").value("PENDING"))
                .andExpect(jsonPath("$.upgrade.needsCloserLook").value(true));

        mockMvc.perform(authorized(get("/api/v1/kyc/tier-upgrades"), compliance))
                .andExpect(jsonPath("$[0].flags[0]").value("Account " + esther.accountNumber() + " is frozen"));
    }

    @Test
    void tierThreeShouldNeedTierTwoFirst() throws Exception {

        Holder esther = holder("esther", 1);

        requestUpgrade(esther)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Verify both your BVN and NIN (tier 2) before asking for tier 3"));
    }

    @Test
    void customersShouldNotSeeTheUpgradeQueue() throws Exception {

        Holder esther = holder("esther", 2);

        mockMvc.perform(authorized(get("/api/v1/kyc/tier-upgrades"), esther))
                .andExpect(status().isForbidden());
    }

    private record Holder(String email, String customerId, String accountId, String accountNumber, String token) {
        Holder withToken(String newToken) {
            return new Holder(email, customerId, accountId, accountNumber, newToken);
        }
    }

    private Holder holder(String name, int tier) throws Exception {

        String email = name + "@example.com";

        String customer =
                mockMvc.perform(post("/api/v1/customers")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        { "firstName": "John", "lastName": "Doe", "email": "%s",
                                          "countryCode": "NG", "phoneNumber": "0803%d", "password": "Password123" }
                                        """.formatted(email, PHONES.incrementAndGet())))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString();

        String customerId = JsonPath.read(customer, "$.id");

        jdbcTemplate.update(
                "UPDATE kyc_profiles SET status = 'VERIFIED', tier = ? WHERE customer_id = ?::uuid", tier, customerId
        );

        String token = login(email);

        String account =
                mockMvc.perform(post("/api/v1/accounts")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{ \"type\": \"PERSONAL\", \"currency\": \"NGN\" }"))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString();

        String accountId = JsonPath.read(account, "$.id");
        jdbcTemplate.update("UPDATE accounts SET status = 'ACTIVE' WHERE id = ?::uuid", accountId);

        mockMvc.perform(post("/api/v1/security/pin")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"pin\": \"" + PIN + "\" }"))
                .andExpect(status().isCreated());

        return new Holder(email, customerId, accountId, JsonPath.read(account, "$.accountNumber"), token);
    }

    private Holder staff(Holder holder) throws Exception {
        jdbcTemplate.update(
                "INSERT INTO customer_roles (customer_id, role_id) SELECT ?::uuid, id FROM roles WHERE name = 'COMPLIANCE'",
                holder.customerId()
        );
        return holder.withToken(login(holder.email()));
    }

    private void startedWithBvn(Holder holder) {
        jdbcTemplate.update("""
                INSERT INTO kyc_verifications (id, kyc_profile_id, verification_type, provider, result, created_at)
                SELECT paycore_uuid_v7(), id, 'BVN', 'TEST', 'PASSED', now() FROM kyc_profiles WHERE customer_id = ?::uuid
                """, holder.customerId());
    }

    private String login(String email) throws Exception {
        return JsonPath.read(
                mockMvc.perform(post("/api/v1/auth/login")
                                .header("X-Device-Id", DEVICE)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{ \"email\": \"%s\", \"password\": \"Password123\" }".formatted(email)))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString(),
                "$.accessToken"
        );
    }

    private void fund(Holder holder, String naira) {
        ledgerService.receiveInboundTransfer(new InboundTransfer(
                "TESTRAIL",
                "session-" + UUID.randomUUID(),
                holder.accountNumber(),
                new BigDecimal(naira),
                "NGN",
                new Counterparty("Chiamaka Obi", "Test Bank", "7000000001"),
                null
        ));
    }

    private ResultActions transfer(Holder from, Holder to, String amount, String key) throws Exception {
        return mockMvc.perform(authorized(post("/api/v1/ledger/accounts/{id}/transfers", from.accountId()), from)
                .header("X-Transaction-PIN", PIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "destinationAccountNumber": "%s", "amount": %s, "currency": "NGN", "idempotencyKey": "%s" }
                        """.formatted(to.accountNumber(), amount, key)));
    }

    private ResultActions sendToTestBank(Holder from, String amount, String key) throws Exception {
        return mockMvc.perform(authorized(post("/api/v1/ledger/accounts/{id}/bank-transfers", from.accountId()), from)
                .header("X-Transaction-PIN", PIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "bankCode": "999001", "accountNumber": "4123456789", "amount": %s, "currency": "NGN",
                          "idempotencyKey": "%s" }
                        """.formatted(amount, key)));
    }

    private ResultActions verify(Holder holder, String type, String number) throws Exception {
        return mockMvc.perform(authorized(post("/api/v1/kyc/" + type), holder)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "%s": "%s", "firstName": "John", "lastName": "Doe", "dateOfBirth": "1990-01-01" }
                        """.formatted(type, number)));
    }

    private ResultActions requestUpgrade(Holder holder) throws Exception {
        return mockMvc.perform(authorized(
                multipart("/api/v1/kyc/tier/upgrade")
                        .file(new MockMultipartFile("file", "bill.png", "image/png", PNG)),
                holder
        ));
    }

    private ResultActions tier(Holder holder) throws Exception {
        return mockMvc.perform(authorized(get("/api/v1/kyc/tier"), holder)).andExpect(status().isOk());
    }

    private BigDecimal balance(Holder holder) throws Exception {
        String body =
                mockMvc.perform(authorized(get("/api/v1/ledger/accounts/{id}/balance", holder.accountId()), holder))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString();
        return new BigDecimal(String.valueOf((Object) JsonPath.read(body, "$.balance")));
    }

    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request, Holder holder) {
        return request.header("Authorization", "Bearer " + holder.token());
    }
}
