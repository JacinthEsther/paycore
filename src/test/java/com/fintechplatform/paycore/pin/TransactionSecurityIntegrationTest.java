package com.fintechplatform.paycore.pin;

import com.fintechplatform.paycore.ledger.domain.Counterparty;
import com.fintechplatform.paycore.ledger.service.InboundTransfer;
import com.fintechplatform.paycore.ledger.service.LedgerService;
import com.fintechplatform.paycore.notification.EmailSentEvent;
import com.fintechplatform.paycore.notification.OutgoingEmail;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
class TransactionSecurityIntegrationTest {

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

    private static final AtomicInteger PHONES = new AtomicInteger(3_000_000);
    private static final Pattern CODE = Pattern.compile("Your PayCore code: (\\d{6})");

    private static final String PHONE = "phone-device-0000000001";
    private static final String LAPTOP = "laptop-device-000000002";
    private static final String PIN = "2580";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private ApplicationEvents events;

    @BeforeEach
    void cleanDatabase() {

        jdbcTemplate.execute("TRUNCATE ledger_entries, ledger_transactions CASCADE");

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
    void transferShouldNeedAPinThatIsSetAndEntered() throws Exception {

        Holder esther = holder("esther", PHONE);
        Holder john = holder("john", PHONE);
        fund(esther, "50000");

        transfer(esther, john, "1000", PIN, "pin-0001")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PIN_NOT_SET"));

        setPin(esther, PIN).andExpect(status().isCreated());

        transfer(esther, john, "1000", null, "pin-0002")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("PIN_REQUIRED"));

        transfer(esther, john, "1000", PIN, "pin-0003")
                .andExpect(status().isCreated());

        assertThat(balance(esther)).isEqualByComparingTo("49000");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0000", "7777", "1234", "6789", "9876", "3210"})
    void guessablePinsShouldBeRefused(String pin) throws Exception {

        Holder esther = holder("esther", PHONE);

        setPin(esther, pin)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("WEAK_PIN"));
    }

    @Test
    void pinShouldBeSetOnlyOnce() throws Exception {

        Holder esther = holder("esther", PHONE);
        setPin(esther, PIN).andExpect(status().isCreated());

        setPin(esther, "1357")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PIN_ALREADY_SET"));
    }

    @Test
    void threeWrongPinsShouldLockItUntilResetByEmail() throws Exception {

        Holder esther = holder("esther", PHONE);
        Holder john = holder("john", PHONE);
        fund(esther, "50000");
        setPin(esther, PIN);

        transfer(esther, john, "100", "1357", "lock-0001")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_PIN"))
                .andExpect(jsonPath("$.attemptsRemaining").value(2));

        transfer(esther, john, "100", "1357", "lock-0002")
                .andExpect(jsonPath("$.attemptsRemaining").value(1));

        transfer(esther, john, "100", "1357", "lock-0003")
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.error").value("PIN_LOCKED"));

        transfer(esther, john, "100", PIN, "lock-0004")
                .andExpect(status().isLocked());

        mockMvc.perform(authorized(post("/api/v1/security/pin/reset-code"), esther))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.sentTo").value("e*****@example.com"));

        mockMvc.perform(authorized(post("/api/v1/security/pin/reset"), esther)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"code\": \"%s\", \"newPin\": \"4826\" }".formatted(latestCode(esther))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(false))
                .andExpect(jsonPath("$.attemptsRemaining").value(3));

        transfer(esther, john, "100", "4826", "lock-0005")
                .andExpect(status().isCreated());

        assertThat(balance(esther)).isEqualByComparingTo("49900");
    }

    @Test
    void aRightPinShouldResetTheCount() throws Exception {

        Holder esther = holder("esther", PHONE);
        Holder john = holder("john", PHONE);
        fund(esther, "50000");
        setPin(esther, PIN);

        transfer(esther, john, "100", "1357", "count-0001").andExpect(status().isUnprocessableEntity());
        transfer(esther, john, "100", "1357", "count-0002").andExpect(status().isUnprocessableEntity());
        transfer(esther, john, "100", PIN, "count-0003").andExpect(status().isCreated());

        mockMvc.perform(authorized(get("/api/v1/security/pin"), esther))
                .andExpect(jsonPath("$.set").value(true))
                .andExpect(jsonPath("$.attemptsRemaining").value(3));
    }

    @Test
    void changingThePinShouldNeedTheCurrentOne() throws Exception {

        Holder esther = holder("esther", PHONE);
        setPin(esther, PIN);

        changePin(esther, "1357", "4826")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INVALID_PIN"));

        changePin(esther, PIN, "4826").andExpect(status().isOk());

        Holder john = holder("john", PHONE);
        fund(esther, "1000");

        transfer(esther, john, "10", PIN, "change-0001").andExpect(status().isUnprocessableEntity());
        transfer(esther, john, "10", "4826", "change-0002").andExpect(status().isCreated());
    }

    @Test
    void parallelGuessesShouldNotGetPastTheAttemptLimit() throws Exception {

        Holder esther = holder("esther", PHONE);
        Holder john = holder("john", PHONE);
        fund(esther, "50000");
        setPin(esther, PIN);

        List<Callable<Integer>> guesses = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String pin = "%04d".formatted(1000 + i * 7 + 3);
            String key = "guess-" + i + "-0000";
            guesses.add(() -> transfer(esther, john, "100", pin, key).andReturn().getResponse().getStatus());
        }

        List<Integer> statuses = runTogether(guesses);

        assertThat(statuses).doesNotContain(201);
        assertThat(statuses.stream().filter(code -> code == 422).count()).isLessThanOrEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT failed_attempts FROM transaction_pins WHERE customer_id = ?::uuid",
                Integer.class,
                esther.customerId()
        )).isEqualTo(3);
        assertThat(balance(esther)).isEqualByComparingTo("50000");
    }

    @Test
    void firstDeviceShouldBeTrustedAtOnce() throws Exception {

        Holder esther = holder("esther", PHONE);

        mockMvc.perform(authorized(get("/api/v1/devices/current"), esther))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRUSTED"))
                .andExpect(jsonPath("$.current").value(true))
                .andExpect(jsonPath("$.coolingOffUntil").doesNotExist());
    }

    @Test
    void newDeviceShouldNotMoveMoneyUntilConfirmed() throws Exception {

        Holder onPhone = holder("esther", PHONE);
        Holder john = holder("john", PHONE);
        fund(onPhone, "50000");
        setPin(onPhone, PIN);

        Holder onLaptop = onPhone.withToken(login(onPhone.email(), LAPTOP));

        mockMvc.perform(authorized(get("/api/v1/devices/current"), onLaptop))
                .andExpect(jsonPath("$.status").value("PENDING"));

        transfer(onLaptop, john, "100", PIN, "laptop-0001")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("DEVICE_NOT_TRUSTED"));

        mockMvc.perform(authorized(post("/api/v1/security/pin/reset-code"), onLaptop))
                .andExpect(status().isForbidden());

        transfer(onPhone, john, "100", PIN, "phone-0001").andExpect(status().isCreated());
    }

    @Test
    void confirmedDeviceShouldCapSpendingDuringCoolingOff() throws Exception {

        Holder onPhone = holder("esther", PHONE);
        Holder john = holder("john", PHONE);
        fund(onPhone, "100000");
        setPin(onPhone, PIN);

        Holder onLaptop = onPhone.withToken(login(onPhone.email(), LAPTOP));

        mockMvc.perform(authorized(post("/api/v1/devices/current/confirmation-code"), onLaptop))
                .andExpect(status().isAccepted());

        confirm(onLaptop, "000000").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_CODE"));

        confirm(onLaptop, latestCode(onPhone))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("TRUSTED"))
                .andExpect(jsonPath("$.coolingOffUntil").exists());

        transfer(onLaptop, john, "25000", PIN, "cool-0001")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.limit").value("NEW_DEVICE"))
                .andExpect(jsonPath("$.remaining").value(20000.0));

        transfer(onLaptop, john, "15000", PIN, "cool-0002").andExpect(status().isCreated());

        transfer(onPhone, john, "6000", PIN, "cool-0003")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.remaining").value(5000.0));

        transfer(onPhone, john, "5000", PIN, "cool-0004").andExpect(status().isCreated());

        jdbcTemplate.update("UPDATE customer_devices SET cooling_off_until = now() - interval '1 minute'");

        transfer(onLaptop, john, "25000", PIN, "cool-0005").andExpect(status().isCreated());
        assertThat(balance(onPhone)).isEqualByComparingTo("55000");
    }

    @Test
    void codeForOneDeviceShouldNotConfirmAnother() throws Exception {

        Holder onPhone = holder("esther", PHONE);
        Holder onLaptop = onPhone.withToken(login(onPhone.email(), LAPTOP));
        Holder onTablet = onPhone.withToken(login(onPhone.email(), "tablet-device-000000003"));

        mockMvc.perform(authorized(post("/api/v1/devices/current/confirmation-code"), onLaptop))
                .andExpect(status().isAccepted());

        confirm(onTablet, latestCode(onPhone)).andExpect(status().isBadRequest());
    }

    @Test
    void sessionWithoutADeviceShouldNotMoveMoney() throws Exception {

        Holder esther = holder("esther", PHONE);
        Holder john = holder("john", PHONE);
        fund(esther, "1000");
        setPin(esther, PIN);

        Holder noDevice = esther.withToken(login(esther.email(), null));

        transfer(noDevice, john, "10", PIN, "nodevice-01")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("DEVICE_NOT_TRUSTED"));
    }

    @Test
    void removingADeviceShouldSignItOut() throws Exception {

        Holder onPhone = holder("esther", PHONE);
        Holder onLaptop = onPhone.withToken(login(onPhone.email(), LAPTOP));

        String laptopId =
                JsonPath.read(
                        mockMvc.perform(authorized(get("/api/v1/devices/current"), onLaptop))
                                .andReturn().getResponse().getContentAsString(),
                        "$.id"
                );

        mockMvc.perform(authorized(delete("/api/v1/devices/{id}", laptopId), onLaptop))
                .andExpect(status().isForbidden());

        mockMvc.perform(authorized(delete("/api/v1/devices/{id}", laptopId), onPhone))
                .andExpect(status().isNoContent());

        mockMvc.perform(authorized(get("/api/v1/devices"), onLaptop))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(authorized(get("/api/v1/devices"), onPhone))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '%s')].status".formatted(laptopId)).value("REVOKED"));
    }

    @Test
    void anotherCustomersDeviceShouldNotBeFound() throws Exception {

        Holder esther = holder("esther", PHONE);
        Holder john = holder("john", PHONE);

        String johnsDevice =
                JsonPath.read(
                        mockMvc.perform(authorized(get("/api/v1/devices/current"), john))
                                .andReturn().getResponse().getContentAsString(),
                        "$.id"
                );

        mockMvc.perform(authorized(delete("/api/v1/devices/{id}", johnsDevice), esther))
                .andExpect(status().isNotFound());
    }

    private record Holder(String email, String customerId, String accountId, String accountNumber, String token) {
        Holder withToken(String newToken) {
            return new Holder(email, customerId, accountId, accountNumber, newToken);
        }
    }

    private Holder holder(String name, String device) throws Exception {

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

        String token = login(email, device);

        String account =
                mockMvc.perform(post("/api/v1/accounts")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{ \"type\": \"PERSONAL\", \"currency\": \"NGN\" }"))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString();

        String accountId = JsonPath.read(account, "$.id");
        jdbcTemplate.update("UPDATE accounts SET status = 'ACTIVE' WHERE id = ?::uuid", accountId);

        return new Holder(email, customerId, accountId, JsonPath.read(account, "$.accountNumber"), token);
    }

    private String login(String email, String device) throws Exception {

        MockHttpServletRequestBuilder request =
                post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"email\": \"%s\", \"password\": \"Password123\" }".formatted(email));

        if (device != null) {
            request.header("X-Device-Id", device);
        }

        return JsonPath.read(
                mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
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

    private ResultActions setPin(Holder holder, String pin) throws Exception {
        return mockMvc.perform(authorized(post("/api/v1/security/pin"), holder)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"pin\": \"%s\" }".formatted(pin)));
    }

    private ResultActions changePin(Holder holder, String currentPin, String newPin) throws Exception {
        return mockMvc.perform(authorized(put("/api/v1/security/pin"), holder)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"currentPin\": \"%s\", \"newPin\": \"%s\" }".formatted(currentPin, newPin)));
    }

    private ResultActions confirm(Holder holder, String code) throws Exception {
        return mockMvc.perform(authorized(post("/api/v1/devices/current/confirm"), holder)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"code\": \"%s\" }".formatted(code)));
    }

    private ResultActions transfer(Holder from, Holder to, String amount, String pin, String key) throws Exception {

        MockHttpServletRequestBuilder request =
                authorized(post("/api/v1/ledger/accounts/{id}/transfers", from.accountId()), from)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "destinationAccountNumber": "%s", "amount": %s, "currency": "NGN",
                                  "idempotencyKey": "%s" }
                                """.formatted(to.accountNumber(), amount, key));

        if (pin != null) {
            request.header("X-Transaction-PIN", pin);
        }

        return mockMvc.perform(request);
    }

    private BigDecimal balance(Holder holder) throws Exception {
        String body =
                mockMvc.perform(authorized(get("/api/v1/ledger/accounts/{id}/balance", holder.accountId()), holder))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString();
        return new BigDecimal(String.valueOf((Object) JsonPath.read(body, "$.balance")));
    }

    private String latestCode(Holder holder) {
        List<OutgoingEmail> emails =
                events.stream(EmailSentEvent.class)
                        .map(EmailSentEvent::email)
                        .filter(email -> email.to().equals(holder.email()))
                        .toList();

        assertThat(emails).isNotEmpty();

        Matcher matcher = CODE.matcher(emails.getLast().subject());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {

        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }

            start.countDown();

            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request, Holder holder) {
        return request.header("Authorization", "Bearer " + holder.token());
    }
}
