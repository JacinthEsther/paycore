package com.fintechplatform.paycore.bills;

import com.fintechplatform.paycore.ledger.domain.Counterparty;
import com.fintechplatform.paycore.ledger.service.InboundTransfer;
import com.fintechplatform.paycore.ledger.service.LedgerService;
import com.fintechplatform.paycore.payment.service.PaymentRecoveryService;
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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = {
        "paycore.bills.provider=simulated",
        "paycore.payments.recovery.enabled=false",
        "paycore.payments.stale-after=0s",
        "paycore.payments.first-check-after=0s",
        "paycore.payments.max-backoff=0s",
        "paycore.payments.not-found-grace=0s"
})
@AutoConfigureMockMvc
class BillPaymentIntegrationTest {

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
    private static final String DEVICE = "bills-test-device-00001";
    private static final String PIN = "2580";
    private static final String METER = "1111111111111";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private PaymentRecoveryService recoveryService;

    @BeforeEach
    void cleanDatabase() {

        jdbcTemplate.execute("TRUNCATE ledger_entries, ledger_transactions CASCADE");
        jdbcTemplate.execute("TRUNCATE simulator.bill_payments");

        for (String table : new String[]{
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
    }

    @Test
    void billersShouldBeListedWithWhatEachNeeds() throws Exception {

        Holder esther = holder("esther");

        mockMvc.perform(authorized(get("/api/v1/bills/billers"), esther))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'mtn')].category").value("AIRTIME"))
                .andExpect(jsonPath("$[?(@.id == 'mtn')].minAmount").value(50))
                .andExpect(jsonPath("$[?(@.id == 'ikeja-electric')].verified").value(true))
                .andExpect(jsonPath("$[?(@.id == 'ikeja-electric')].meterTypes[*]", hasItem("prepaid")))
                .andExpect(jsonPath("$[?(@.id == 'dstv')].products").value(true));

        mockMvc.perform(authorized(get("/api/v1/bills/billers/{id}/products", "dstv"), esther))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("dstv-padi"))
                .andExpect(jsonPath("$[0].amount").value(4400));
    }

    @Test
    void meterShouldBeVerifiedBeforePaying() throws Exception {

        Holder esther = holder("esther");

        verify(esther, "ikeja-electric", "prepaid", METER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Adaeze Okonkwo"))
                .andExpect(jsonPath("$.address").exists());

        verify(esther, "ikeja-electric", "prepaid", "1111111110000")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("BILL_CUSTOMER_NOT_FOUND"));

        verify(esther, "ikeja-electric", null, METER)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_BILL_PAYMENT"));
    }

    @Test
    void airtimeShouldBeDebitedAndDelivered() throws Exception {

        Holder esther = funded("esther", "10000");

        pay(esther, "mtn", null, "08031234567", "1500", "bill-0001")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("BILL_PAYMENT"))
                .andExpect(jsonPath("$.billStatus").value("SUCCESSFUL"))
                .andExpect(jsonPath("$.paymentStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.amount").value(1500.0))
                .andExpect(jsonPath("$.description").value("MTN · 08031234567"))
                .andExpect(jsonPath("$.token").doesNotExist());

        assertThat(balance(esther)).isEqualByComparingTo("8500");
    }

    @Test
    void electricityShouldReturnTheMeterToken() throws Exception {

        Holder esther = funded("esther", "10000");

        String body =
                pay(esther, "ikeja-electric", "prepaid", METER, "5000", "bill-0002")
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.billStatus").value("SUCCESSFUL"))
                        .andExpect(jsonPath("$.token").value(matchesPattern("\\d{4}(-\\d{4}){4}")))
                        .andExpect(jsonPath("$.description").value(
                                "Ikeja Electric (IKEDC) prepaid · 1111111111111 (Adaeze Okonkwo)"))
                        .andReturn().getResponse().getContentAsString();

        String paymentId = JsonPath.read(body, "$.paymentId");

        mockMvc.perform(authorized(get("/api/v1/payments/{id}", paymentId), esther))
                .andExpect(jsonPath("$.bill.token").value(JsonPath.<String>read(body, "$.token")))
                .andExpect(jsonPath("$.counterparty.accountNumber").value(METER));
    }

    @Test
    void bundlePriceShouldComeFromTheBillerNotTheRequest() throws Exception {

        Holder esther = funded("esther", "10000");

        pay(esther, "mtn-data", "mtn-1gb-7d", "08031234567", "1", "bill-0003")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(500.0))
                .andExpect(jsonPath("$.productName").value("1GB, 7 days"));

        assertThat(balance(esther)).isEqualByComparingTo("9500");

        pay(esther, "mtn-data", "mtn-100tb-forever", "08031234567", null, "bill-0004")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("UNKNOWN_BILL_PRODUCT"));
    }

    @Test
    void declinedBillShouldBeReversed() throws Exception {

        Holder esther = funded("esther", "10000");

        pay(esther, "mtn", null, "08031234567", "1000.99", "bill-0005")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.billStatus").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("The biller declined this payment"))
                .andExpect(jsonPath("$.reversalReference").exists());

        assertThat(balance(esther)).isEqualByComparingTo("10000");
    }

    @Test
    void lostAnswerShouldBeSettledByRequery() throws Exception {

        Holder esther = funded("esther", "10000");

        String paymentId =
                JsonPath.read(
                        pay(esther, "ikeja-electric", "prepaid", METER, "2000.98", "bill-0006")
                                .andExpect(jsonPath("$.billStatus").value("PENDING"))
                                .andExpect(jsonPath("$.paymentStatus").value("UNKNOWN"))
                                .andReturn().getResponse().getContentAsString(),
                        "$.paymentId"
                );

        assertThat(balance(esther)).isEqualByComparingTo("7999.02");

        assertThat(recoveryService.recoverDue().checked()).isEqualTo(1);

        mockMvc.perform(authorized(get("/api/v1/payments/{id}", paymentId), esther))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.bill.token").exists());

        assertThat(balance(esther)).isEqualByComparingTo("7999.02");
    }

    @Test
    void billThatNeverArrivedShouldBeReversedOnceTheAggregatorSaysSo() throws Exception {

        Holder esther = funded("esther", "10000");

        String paymentId =
                JsonPath.read(
                        pay(esther, "mtn", null, "08031234567", "500.97", "bill-0007")
                                .andExpect(jsonPath("$.paymentStatus").value("UNKNOWN"))
                                .andReturn().getResponse().getContentAsString(),
                        "$.paymentId"
                );

        recoveryService.recoverDue();

        mockMvc.perform(authorized(get("/api/v1/payments/{id}", paymentId), esther))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.reversalTransactionId").exists());

        assertThat(balance(esther)).isEqualByComparingTo("10000");
    }

    @Test
    void retryShouldReturnTheSamePaymentAndPayOnce() throws Exception {

        Holder esther = funded("esther", "10000");

        String first =
                JsonPath.read(
                        pay(esther, "mtn", null, "08031234567", "1000", "bill-0008")
                                .andReturn().getResponse().getContentAsString(),
                        "$.paymentId"
                );

        pay(esther, "mtn", null, "08031234567", "1000", "bill-0008")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paymentId").value(first));

        pay(esther, "mtn", null, "08031234567", "2000", "bill-0008")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(balance(esther)).isEqualByComparingTo("9000");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM simulator.bill_payments", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void billShouldNeedThePin() throws Exception {

        Holder esther = funded("esther", "10000");

        mockMvc.perform(authorized(post("/api/v1/ledger/accounts/{id}/bill-payments", esther.accountId()), esther)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("mtn", null, "08031234567", "100", "bill-0009")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("PIN_REQUIRED"));

        assertThat(balance(esther)).isEqualByComparingTo("10000");
    }

    @Test
    void amountsOutsideTheBillersRangeShouldBeRefused() throws Exception {

        Holder esther = funded("esther", "10000");

        pay(esther, "mtn", null, "08031234567", "20", "bill-0010")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("MTN payments must be between NGN 50 and NGN 50000"));

        pay(esther, "mtn", null, "12345", "100", "bill-0011")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Enter a valid phone number"));
    }

    @Test
    void billsShouldCountAgainstTheDailyLimit() throws Exception {

        Holder esther = funded("esther", "100000");
        jdbcTemplate.update("UPDATE kyc_profiles SET tier = 1 WHERE customer_id = ?::uuid", esther.customerId());

        pay(esther, "ikeja-electric", "prepaid", METER, "45000", "bill-0012").andExpect(status().isCreated());

        pay(esther, "mtn", null, "08031234567", "5000.01", "bill-0013")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.limit").value("DAILY"));
    }

    @Test
    void insufficientFundsShouldPayNothing() throws Exception {

        Holder esther = funded("esther", "100");

        pay(esther, "mtn", null, "08031234567", "500", "bill-0014")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_FUNDS"));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM simulator.bill_payments", Integer.class))
                .isZero();
    }

    private record Holder(String email, String customerId, String accountId, String accountNumber, String token) {
    }

    private Holder funded(String name, String naira) throws Exception {
        Holder holder = holder(name);
        ledgerService.receiveInboundTransfer(new InboundTransfer(
                "TESTRAIL",
                "session-" + UUID.randomUUID(),
                holder.accountNumber(),
                new BigDecimal(naira),
                "NGN",
                new Counterparty("Chiamaka Obi", "Test Bank", "7000000001"),
                null
        ));
        return holder;
    }

    private Holder holder(String name) throws Exception {

        String email = name + "@example.com";

        String customer =
                mockMvc.perform(post("/api/v1/customers")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        { "firstName": "Test", "lastName": "Holder", "email": "%s",
                                          "countryCode": "NG", "phoneNumber": "0803%d", "password": "Password123" }
                                        """.formatted(email, PHONES.incrementAndGet())))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString();

        String customerId = JsonPath.read(customer, "$.id");
        jdbcTemplate.update("UPDATE kyc_profiles SET status = 'VERIFIED', tier = 3 WHERE customer_id = ?::uuid", customerId);

        String token =
                JsonPath.read(
                        mockMvc.perform(post("/api/v1/auth/login")
                                        .header("X-Device-Id", DEVICE)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{ \"email\": \"%s\", \"password\": \"Password123\" }".formatted(email)))
                                .andExpect(status().isOk())
                                .andReturn().getResponse().getContentAsString(),
                        "$.accessToken"
                );

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

    private ResultActions pay(Holder holder, String billerId, String productCode, String number, String amount, String key)
            throws Exception {
        return mockMvc.perform(authorized(post("/api/v1/ledger/accounts/{id}/bill-payments", holder.accountId()), holder)
                .header("X-Transaction-PIN", PIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(billerId, productCode, number, amount, key)));
    }

    private static String body(String billerId, String productCode, String number, String amount, String key) {
        return """
                { "billerId": "%s", "productCode": %s, "customerNumber": "%s", "amount": %s, "idempotencyKey": "%s" }
                """.formatted(
                billerId,
                productCode == null ? "null" : "\"" + productCode + "\"",
                number,
                amount == null ? "null" : amount,
                key
        );
    }

    private ResultActions verify(Holder holder, String billerId, String productCode, String number) throws Exception {
        return mockMvc.perform(authorized(post("/api/v1/bills/verify"), holder)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "billerId": "%s", "productCode": %s, "customerNumber": "%s" }
                        """.formatted(billerId, productCode == null ? "null" : "\"" + productCode + "\"", number)));
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
