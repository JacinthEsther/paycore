package com.fintechplatform.paycore.ledger;

import com.fintechplatform.paycore.ledger.domain.Counterparty;
import com.fintechplatform.paycore.ledger.dto.request.TransferRequest;
import com.fintechplatform.paycore.ledger.dto.response.TransactionResponse;
import com.fintechplatform.paycore.ledger.exception.InsufficientFundsException;
import com.fintechplatform.paycore.ledger.exception.TransactionNotReversibleException;
import com.fintechplatform.paycore.ledger.service.InboundTransfer;
import com.fintechplatform.paycore.ledger.service.LedgerService;
import com.fintechplatform.paycore.ledger.service.OutboundTransfer;
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
import org.springframework.transaction.support.TransactionTemplate;
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
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class LedgerIntegrationTest {

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

    private static final AtomicInteger PHONES = new AtomicInteger(1_000_000);

    private static final String TEST_DEVICE_ID = "integration-test-device";
    private static final String TEST_PIN = "2580";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private Holder admin;

    @BeforeEach
    void cleanDatabase() throws Exception {

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
                "customers"
        }) {
            jdbcTemplate.execute("DELETE FROM " + table);
        }

        admin = staff(activeHolder("admin"), "COMPLIANCE");
    }

    @Test
    void transferShouldPostBalancedEntriesAndMoveTheBalances() throws Exception {

        Holder esther = activeHolder("esther");
        Holder john = activeHolder("john");
        fund(esther, 10_000_000);

        String body =
                transfer(esther, john.accountNumber(), "20000.50", "transfer-0001", "Rent share")
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.type").value("TRANSFER"))
                        .andExpect(jsonPath("$.status").value("POSTED"))
                        .andExpect(jsonPath("$.reference").value(matchesPattern("TXN-\\d{8}-[0-9A-F]{16}")))
                        .andExpect(jsonPath("$.amount").value(20000.5))
                        .andExpect(jsonPath("$.currency").value("NGN"))
                        .andExpect(jsonPath("$.description").value("Rent share"))
                        .andExpect(jsonPath("$.postedAt").isNotEmpty())
                        .andExpect(jsonPath("$.entries", hasSize(2)))
                        .andExpect(jsonPath("$.entries[0].type").value("DEBIT"))
                        .andExpect(jsonPath("$.entries[0].accountId").value(esther.accountId()))
                        .andExpect(jsonPath("$.entries[0].amount").value(20000.5))
                        .andExpect(jsonPath("$.entries[1].type").value("CREDIT"))
                        .andExpect(jsonPath("$.entries[1].accountNumber").value(john.accountNumber()))
                        .andExpect(jsonPath("$.entries[1].amount").value(20000.5))
                        .andReturn().getResponse().getContentAsString();

        String transactionId = JsonPath.read(body, "$.id");

        assertThat(balance(esther)).isEqualByComparingTo("79999.50");
        assertThat(balance(john)).isEqualByComparingTo("20000.50");

        assertThat(jdbcTemplate.queryForList(
                "SELECT entry_type, amount_minor FROM ledger_entries "
                        + "WHERE transaction_id = ?::uuid ORDER BY entry_type",
                transactionId
        )).extracting(row -> row.get("entry_type") + " " + row.get("amount_minor"))
                .containsExactly("CREDIT 2000050", "DEBIT 2000050");
    }

    @Test
    void shouldReturnLocationOfTheTransaction() throws Exception {

        Holder esther = activeHolder("esther");
        Holder john = activeHolder("john");
        fund(esther, 100_000);

        ResultActions result =
                transfer(esther, john.accountNumber(), "10", "transfer-0002", null)
                        .andExpect(status().isCreated());

        String id = JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");

        result.andExpect(header().string("Location", "/api/v1/ledger/transactions/" + id));
    }

    @Test
    void newAccountShouldHaveZeroBalance() throws Exception {

        Holder esther = activeHolder("esther");

        mockMvc.perform(authorized(get("/api/v1/ledger/accounts/{id}/balance", esther.accountId()), esther.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNumber").value(esther.accountNumber()))
                .andExpect(jsonPath("$.currency").value("NGN"));

        assertThat(balance(esther)).isEqualByComparingTo("0.00");
    }

    @Test
    void anonymousCallersShouldBeRejected() throws Exception {

        Holder esther = activeHolder("esther");

        mockMvc.perform(post("/api/v1/ledger/accounts/{id}/transfers", esther.accountId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody("1234567890", "10", "transfer-0003", null)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/ledger/accounts/{id}/balance", esther.accountId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customerWithoutTransactionCreateShouldBeForbidden() throws Exception {

        Holder esther = activeHolder("esther");
        Holder john = activeHolder("john");
        fund(esther, 100_000);

        jdbcTemplate.update("DELETE FROM customer_roles WHERE customer_id = ?::uuid", esther.customerId());
        Holder withoutRoles = esther.withToken(login(esther.email()));

        transfer(withoutRoles, john.accountNumber(), "10", "transfer-0004", null)
                .andExpect(status().isForbidden());

        assertThat(balance(john)).isEqualByComparingTo("0");
    }

    @Test
    void customerCannotTransferFromSomeoneElsesAccount() throws Exception {

        Holder esther = activeHolder("esther");
        Holder mallory = activeHolder("mallory");
        fund(esther, 100_000);

        mockMvc.perform(authorized(post("/api/v1/ledger/accounts/{id}/transfers", esther.accountId()), mallory.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(mallory.accountNumber(), "10", "transfer-0005", null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ACCOUNT_NOT_FOUND"));

        assertThat(balance(esther)).isEqualByComparingTo("1000.00");
        assertThat(balance(mallory)).isEqualByComparingTo("0");
    }

    @Test
    void onlyTheCustomersInvolvedCanReadATransactionOrBalance() throws Exception {

        Holder esther = activeHolder("esther");
        Holder john = activeHolder("john");
        Holder mallory = activeHolder("mallory");
        fund(esther, 100_000);

        String body =
                transfer(esther, john.accountNumber(), "10", "transfer-0006", null)
                        .andReturn().getResponse().getContentAsString();

        String id = JsonPath.read(body, "$.id");
        String reference = JsonPath.read(body, "$.reference");

        for (Holder involved : List.of(esther, john)) {
            mockMvc.perform(authorized(get("/api/v1/ledger/transactions/{id}", id), involved.token()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.reference").value(reference));

            mockMvc.perform(authorized(get("/api/v1/ledger/transactions/reference/{ref}", reference), involved.token()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(id));
        }

        mockMvc.perform(authorized(get("/api/v1/ledger/transactions/{id}", id), mallory.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TRANSACTION_NOT_FOUND"));

        mockMvc.perform(authorized(get("/api/v1/ledger/transactions/reference/{ref}", reference), mallory.token()))
                .andExpect(status().isNotFound());

        mockMvc.perform(authorized(get("/api/v1/ledger/transactions/{id}", UUID.randomUUID()), esther.token()))
                .andExpect(status().isNotFound());

        mockMvc.perform(authorized(get("/api/v1/ledger/accounts/{id}/balance", esther.accountId()), mallory.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ACCOUNT_NOT_FOUND"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "FROZEN", "CLOSED"})
    void sourceAccountMustBeActive(String status) throws Exception {

        Holder esther = activeHolder("esther");
        Holder john = activeHolder("john");
        fund(esther, 100_000);
        setStatus(esther, status);

        transfer(esther, john.accountNumber(), "10", "transfer-0007", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_ACCOUNT_STATE"));

        assertThat(transactionCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "FROZEN", "CLOSED"})
    void destinationAccountMustBeActive(String status) throws Exception {

        Holder esther = activeHolder("esther");
        Holder john = activeHolder("john");
        fund(esther, 100_000);
        setStatus(john, status);

        transfer(esther, john.accountNumber(), "10", "transfer-0008", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_ACCOUNT_STATE"))
                .andExpect(jsonPath("$.message").value("The destination account cannot receive money"));

        assertThat(balance(esther)).isEqualByComparingTo("1000.00");
    }

    @Test
    void transferCurrencyMustMatchTheAccounts() throws Exception {

        Holder esther = activeHolder("esther");
        Holder john = activeHolder("john");
        fund(esther, 100_000);

        mockMvc.perform(authorized(post("/api/v1/ledger/accounts/{id}/transfers", esther.accountId()), esther.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "destinationAccountNumber": "%s",
                                    "amount": 10,
                                    "currency": "USD",
                                    "idempotencyKey": "transfer-0009"
                                }
                                """.formatted(john.accountNumber())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("CURRENCY_MISMATCH"));
    }

    @Test
    void insufficientFundsShouldBeRefusedWithoutMovingMoney() throws Exception {

        Holder esther = activeHolder("esther");
        Holder john = activeHolder("john");
        fund(esther, 100_000);

        transfer(esther, john.accountNumber(), "1000.01", "transfer-0010", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_FUNDS"));

        transfer(esther, john.accountNumber(), "1000.00", "transfer-0011", null)
                .andExpect(status().isCreated());

        assertThat(balance(esther)).isEqualByComparingTo("0");
        assertThat(balance(john)).isEqualByComparingTo("1000.00");
    }

    @Test
    void shouldRejectInvalidTransfers() throws Exception {

        Holder esther = activeHolder("esther");
        fund(esther, 100_000);

        transfer(esther, esther.accountNumber(), "10", "transfer-0012", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_TRANSACTION"));

        transfer(esther, "0000000000", "10", "transfer-0013", null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ACCOUNT_NOT_FOUND"));

        transfer(esther, admin.accountNumber(), "10.005", "transfer-0014", null)
                .andExpect(status().isBadRequest());

        transfer(esther, admin.accountNumber(), "0", "transfer-0015", null)
                .andExpect(status().isBadRequest());
        transfer(esther, admin.accountNumber(), "-5", "transfer-0016", null)
                .andExpect(status().isBadRequest());
        transfer(esther, "12345", "10", "transfer-0017", null)
                .andExpect(status().isBadRequest());
        transfer(esther, admin.accountNumber(), "10", "short", null)
                .andExpect(status().isBadRequest());

        assertThat(balance(esther)).isEqualByComparingTo("1000.00");
    }

    @Test
    void retryWithTheSameKeyShouldReturnTheOriginalAndMoveMoneyOnce() throws Exception {

        Holder esther = activeHolder("esther");
        Holder john = activeHolder("john");
        fund(esther, 100_000);

        String first =
                transfer(esther, john.accountNumber(), "250", "transfer-retry", "Lunch")
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString();

        String retry =
                transfer(esther, john.accountNumber(), "250.00", "transfer-retry", " Lunch ")
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(retry, "$.id")).isEqualTo(JsonPath.read(first, "$.id"));
        assertThat((String) JsonPath.read(retry, "$.reference")).isEqualTo(JsonPath.read(first, "$.reference"));

        assertThat(balance(esther)).isEqualByComparingTo("750.00");
        assertThat(balance(john)).isEqualByComparingTo("250.00");
        assertThat(transactionCount()).isEqualTo(2);
    }

    @Test
    void reusingAKeyForADifferentRequestShouldBeRefused() throws Exception {

        Holder esther = activeHolder("esther");
        Holder john = activeHolder("john");
        Holder ada = activeHolder("ada");
        fund(esther, 100_000);

        transfer(esther, john.accountNumber(), "250", "transfer-reuse", null)
                .andExpect(status().isCreated());

        transfer(esther, john.accountNumber(), "300", "transfer-reuse", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("IDEMPOTENCY_KEY_REUSED"));

        transfer(esther, ada.accountNumber(), "250", "transfer-reuse", null)
                .andExpect(status().isConflict());

        transfer(esther, john.accountNumber(), "250", "transfer-reuse", "Something else")
                .andExpect(status().isConflict());

        assertThat(balance(esther)).isEqualByComparingTo("750.00");
        assertThat(balance(ada)).isEqualByComparingTo("0");
    }

    @Test
    void idempotencyKeysShouldBeScopedToTheCustomer() throws Exception {

        Holder esther = activeHolder("esther");
        Holder john = activeHolder("john");
        fund(esther, 100_000);
        fund(john, 100_000);

        String first =
                transfer(esther, john.accountNumber(), "100", "shared-key-1", null)
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString();

        String second =
                transfer(john, esther.accountNumber(), "40", "shared-key-1", null)
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(second, "$.id")).isNotEqualTo(JsonPath.read(first, "$.id"));
        assertThat(balance(esther)).isEqualByComparingTo("940.00");
        assertThat(balance(john)).isEqualByComparingTo("1060.00");
    }

    @Test
    void databaseShouldEnforceLedgerInvariants() throws Exception {

        Holder esther = activeHolder("esther");
        String transactionId = fund(esther, 100);

        assertThatThrownBy(() -> insertEntry(transactionId, esther.accountId(), "CREDIT", 0, "NGN"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertEntry(transactionId, esther.accountId(), "CREDIT", -100, "NGN"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertEntry(transactionId, esther.accountId(), "REFUND", 100, "NGN"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertEntry(transactionId, esther.accountId(), "CREDIT", 100, "USD"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertEntry(UUID.randomUUID().toString(), esther.accountId(), "CREDIT", 100, "NGN"))
                .isInstanceOf(DataIntegrityViolationException.class);

        String reference = jdbcTemplate.queryForObject(
                "SELECT reference FROM ledger_transactions WHERE id = ?::uuid", String.class, transactionId
        );
        assertThatThrownBy(() -> insertTransaction(reference, "another-key"))
                .isInstanceOf(DataIntegrityViolationException.class);

        insertBalancedTransaction("TXN-UNIQUE-REF-1", "same-key-0001", esther);
        assertThatThrownBy(() -> insertTransaction("TXN-UNIQUE-REF-2", "same-key-0001"))
                .isInstanceOf(DataIntegrityViolationException.class);

        String sessionId = jdbcTemplate.queryForObject(
                "SELECT provider_reference FROM ledger_transactions WHERE id = ?::uuid", String.class, transactionId
        );
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO ledger_transactions (id, reference, type, status, currency, idempotency_key, provider, "
                        + "provider_reference, counterparty_name, counterparty_bank, counterparty_account_number, "
                        + "created_at, posted_at) VALUES (paycore_uuid_v7(), 'TXN-SESSION-2', 'INBOUND_TRANSFER', "
                        + "'POSTED', 'NGN', ?, 'TESTRAIL', ?, 'X', 'Test Bank', '1', now(), now())",
                sessionId, sessionId
        ))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_ledger_transactions_provider_payment");
    }

    @Test
    void concurrentTransfersMustNotSpendTheSameMoneyTwice() throws Exception {

        Holder esther = activeHolder("esther");
        Holder john = activeHolder("john");
        Holder ada = activeHolder("ada");
        fund(esther, 10_000_000);

        List<Callable<Object>> tasks = List.of(
                () -> serviceTransfer(esther, john, "80000", "race-0001"),
                () -> serviceTransfer(esther, ada, "80000", "race-0002")
        );

        List<Object> outcomes = runTogether(tasks);

        assertThat(outcomes).filteredOn(o -> !(o instanceof Throwable)).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o instanceof InsufficientFundsException).hasSize(1);

        assertThat(balance(esther)).isEqualByComparingTo("20000.00");
        assertThat(balance(john).add(balance(ada))).isEqualByComparingTo("80000.00");
    }

    @Test
    void crossingTransfersShouldNotDeadlockAndShouldConserveMoney() throws Exception {

        Holder esther = activeHolder("esther");
        Holder john = activeHolder("john");
        fund(esther, 1_000_000);
        fund(john, 1_000_000);

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String key = "cross-" + String.format("%04d", i);
            tasks.add(i % 2 == 0
                    ? () -> serviceTransfer(esther, john, "100", key)
                    : () -> serviceTransfer(john, esther, "100", key));
        }

        List<Object> outcomes = runTogether(tasks);

        assertThat(outcomes).noneMatch(o -> o instanceof Throwable);
        assertThat(balance(esther)).isEqualByComparingTo("10000.00");
        assertThat(balance(john)).isEqualByComparingTo("10000.00");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT SUM(CASE WHEN entry_type = 'CREDIT' THEN amount_minor ELSE -amount_minor END) "
                        + "FROM ledger_entries",
                Long.class
        )).isZero();
    }

    @Test
    void concurrentRetriesOfOneRequestShouldMoveMoneyOnce() throws Exception {

        Holder esther = activeHolder("esther");
        Holder john = activeHolder("john");
        fund(esther, 1_000_000);

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> serviceTransfer(esther, john, "2500", "retry-storm-0001"));
        }

        List<Object> outcomes = runTogether(tasks);

        assertThat(outcomes).noneMatch(o -> o instanceof Throwable);
        assertThat(outcomes)
                .extracting(o -> ((TransactionResponse) o).id())
                .containsOnly(((TransactionResponse) outcomes.getFirst()).id());

        assertThat(balance(esther)).isEqualByComparingTo("7500.00");
        assertThat(balance(john)).isEqualByComparingTo("2500.00");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_transactions WHERE type = 'TRANSFER'", Integer.class
        )).isEqualTo(1);
    }

    @Test
    void manyConcurrentTransfersShouldNeverOverdrawTheAccount() throws Exception {

        Holder esther = activeHolder("esther");
        List<Holder> recipients = List.of(activeHolder("john"), activeHolder("ada"), activeHolder("bola"));
        fund(esther, 10_000_000);

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            Holder to = recipients.get(i % recipients.size());
            String key = "drain-" + String.format("%04d", i);
            tasks.add(() -> serviceTransfer(esther, to, "3000", key));
        }

        List<Object> outcomes = runTogether(tasks);

        assertThat(outcomes).filteredOn(o -> !(o instanceof Throwable)).hasSize(33);
        assertThat(outcomes).filteredOn(o -> o instanceof InsufficientFundsException).hasSize(7);

        assertThat(balance(esther)).isEqualByComparingTo("1000.00");

        BigDecimal received = BigDecimal.ZERO;
        for (Holder recipient : recipients) {
            received = received.add(balance(recipient));
        }
        assertThat(received).isEqualByComparingTo("99000.00");
    }

    @Test
    void aNotificationDeliveredManyTimesAtOnceShouldBeCreditedOnce() throws Exception {

        Holder esther = activeHolder("esther");
        String sessionId = "session-" + UUID.randomUUID();

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> ledgerService.receiveInboundTransfer(new InboundTransfer(
                    "TESTRAIL", sessionId, esther.accountNumber(), new BigDecimal("5000"), "NGN",
                    new Counterparty("Chiamaka Obi", "Test Bank", "7000000001"), null
            )));
        }

        List<Object> outcomes = runTogether(tasks);

        assertThat(outcomes).noneMatch(o -> o instanceof Throwable);
        assertThat(balance(esther)).isEqualByComparingTo("5000.00");
        assertThat(transactionCount()).isEqualTo(1);
    }

    @Test
    void twoApprovedReversalsOfOneTransactionShouldReverseItOnce() throws Exception {

        Holder esther = activeHolder("esther");
        Holder john = activeHolder("john");
        fund(esther, 100_000);
        String transfer = transferId(esther, john, "400", "to-reverse-0001");

        Holder officer = staff(activeHolder("officer"), "OPERATIONS_OFFICER");
        Holder supervisor = staff(activeHolder("supervisor"), "OPERATIONS_SUPERVISOR");

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String key = "race-reverse-" + i;
            tasks.add(() -> ledgerService.reverse(
                    UUID.fromString(officer.customerId()), UUID.fromString(supervisor.customerId()),
                    UUID.fromString(transfer), "Sent in error", null, key
            ));
        }

        List<Object> outcomes = runTogether(tasks);

        assertThat(outcomes).filteredOn(o -> !(o instanceof Throwable)).hasSize(1);
        assertThat(outcomes).filteredOn(o -> o instanceof TransactionNotReversibleException).hasSize(1);
        assertThat(balance(esther)).isEqualByComparingTo("1000.00");
        assertThat(balance(john)).isEqualByComparingTo("0.00");
    }

    @Test
    void booksShouldBalanceAfterAMixedConcurrentWorkload() throws Exception {

        Holder esther = activeHolder("esther");
        Holder john = activeHolder("john");
        Holder ada = activeHolder("ada");
        fund(esther, 5_000_000);
        fund(john, 5_000_000);

        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String key = "mixed-" + String.format("%04d", i);
            tasks.add(switch (i % 5) {
                case 0 -> () -> fund(ada, 12_345);
                case 1 -> () -> serviceTransfer(esther, john, "1500", key);
                case 2 -> () -> serviceTransfer(john, ada, "700", key);
                case 3 -> () -> sendToOtherBank(esther, "999.99", key);
                default -> () -> serviceTransfer(ada, esther, "50", key);
            });
        }

        runTogether(tasks);

        assertThat(jdbcTemplate.queryForList(
                "SELECT transaction_id FROM ledger_entries GROUP BY transaction_id "
                        + "HAVING SUM(CASE WHEN entry_type = 'DEBIT' THEN amount_minor ELSE -amount_minor END) <> 0"
        )).isEmpty();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT SUM(CASE WHEN entry_type = 'CREDIT' THEN amount_minor ELSE -amount_minor END) "
                        + "FROM ledger_entries",
                Long.class
        )).isZero();

        BigDecimal owed = balance(esther).add(balance(john)).add(balance(ada));
        assertThat(settlementBalance()).isEqualByComparingTo(owed);
        assertThat(balance(esther).signum()).isNotNegative();
        assertThat(balance(john).signum()).isNotNegative();
        assertThat(balance(ada).signum()).isNotNegative();
    }

    @Test
    void postedEntriesShouldNeverChangeEvenWhenReversed() throws Exception {

        Holder esther = activeHolder("esther");
        Holder john = activeHolder("john");
        fund(esther, 100_000);
        String transfer = transferId(esther, john, "250", "immutable-0001");

        String snapshot =
                "SELECT id::text || entry_type || amount_minor || account_id::text || created_at::text "
                        + "FROM ledger_entries WHERE transaction_id = ?::uuid ORDER BY id";
        List<String> before = jdbcTemplate.queryForList(snapshot, String.class, transfer);

        reverseThroughOperations(transfer);

        assertThat(jdbcTemplate.queryForList(snapshot, String.class, transfer)).isEqualTo(before);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM ledger_transactions WHERE id = ?::uuid", String.class, transfer
        )).isEqualTo("REVERSED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries e JOIN ledger_transactions t ON t.id = e.transaction_id "
                        + "WHERE t.reverses_transaction_id = ?::uuid",
                Integer.class, transfer
        )).isEqualTo(2);
    }

    @Test
    void databaseShouldRefuseToRewriteLedgerHistory() throws Exception {

        Holder esther = activeHolder("esther");
        Holder john = activeHolder("john");
        fund(esther, 100_000);
        String transfer = transferId(esther, john, "250", "rewrite-0001");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE ledger_entries SET amount_minor = 1 WHERE transaction_id = ?::uuid", transfer))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("append-only");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM ledger_entries WHERE transaction_id = ?::uuid", transfer))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("append-only");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE ledger_transactions SET description = 'edited' WHERE id = ?::uuid", transfer))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("immutable");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM ledger_transactions WHERE id = ?::uuid", transfer))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("cannot be deleted");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE ledger_transactions SET status = 'INITIATED', posted_at = NULL WHERE id = ?::uuid", transfer))
                .isInstanceOf(DataIntegrityViolationException.class);

        reverseThroughOperations(transfer);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE ledger_transactions SET status = 'POSTED', reversed_at = NULL WHERE id = ?::uuid", transfer))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(balance(esther)).isEqualByComparingTo("1000.00");
    }

    @Test
    void databaseShouldRefuseAnUnbalancedTransaction() throws Exception {

        Holder esther = activeHolder("esther");
        fund(esther, 100);
        int before = transactionCount();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            String transactionId = insertTransaction("TXN-UNBALANCED-1", "unbalanced-0001");
            insertEntry(transactionId, settlementAccountId(), "DEBIT", 100, "NGN");
            insertEntry(transactionId, esther.accountId(), "CREDIT", 99, "NGN");
        }))
                .hasStackTraceContaining("does not balance");

        assertThatThrownBy(() -> insertTransaction("TXN-EMPTY-1", "empty-0001"))
                .hasStackTraceContaining("does not balance");

        assertThat(transactionCount()).isEqualTo(before);
        assertThat(balance(esther)).isEqualByComparingTo("1.00");
    }

    @Test
    void statementShouldShowOpeningClosingTotalsAndRunningBalances() throws Exception {

        Holder esther = activeHolder("esther");
        Holder john = activeHolder("john");

        backdate(fund(esther, 100_000), "2026-08-15T10:00:00Z");
        String transfer = transferId(esther, john, "200", "stmt-transfer");
        backdate(transfer, "2026-09-02T09:00:00Z");
        backdate(fund(esther, 50_000), "2026-09-10T12:00:00Z");
        String withdrawal = sendToOtherBank(esther, "100", "stmt-outbound");
        backdate(withdrawal, "2026-09-20T08:00:00Z");
        backdate(fund(esther, 5_000), "2026-10-01T09:00:00Z");

        statement(esther, "?from=2026-09-01&to=2026-09-30")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNumber").value(esther.accountNumber()))
                .andExpect(jsonPath("$.currency").value("NGN"))
                .andExpect(jsonPath("$.from").value("2026-09-01"))
                .andExpect(jsonPath("$.to").value("2026-09-30"))
                .andExpect(jsonPath("$.timeZone").value("Africa/Lagos"))
                .andExpect(jsonPath("$.openingBalance").value(1000.0))
                .andExpect(jsonPath("$.totalCredits").value(500.0))
                .andExpect(jsonPath("$.totalDebits").value(300.0))
                .andExpect(jsonPath("$.closingBalance").value(1200.0))
                .andExpect(jsonPath("$.lines", hasSize(3)))
                .andExpect(jsonPath("$.lines[0].transactionId").value(transfer))
                .andExpect(jsonPath("$.lines[0].transactionType").value("TRANSFER"))
                .andExpect(jsonPath("$.lines[0].direction").value("DEBIT"))
                .andExpect(jsonPath("$.lines[0].amount").value(200.0))
                .andExpect(jsonPath("$.lines[0].balanceAfter").value(800.0))
                .andExpect(jsonPath("$.lines[0].counterpartyName").value("Test Holder"))
                .andExpect(jsonPath("$.lines[0].counterpartyBank").value("PayCore"))
                .andExpect(jsonPath("$.lines[1].transactionType").value("INBOUND_TRANSFER"))
                .andExpect(jsonPath("$.lines[1].direction").value("CREDIT"))
                .andExpect(jsonPath("$.lines[1].counterpartyName").value("Chiamaka Obi"))
                .andExpect(jsonPath("$.lines[1].counterpartyBank").value("Test Bank"))
                .andExpect(jsonPath("$.lines[1].balanceAfter").value(1300.0))
                .andExpect(jsonPath("$.lines[2].transactionId").value(withdrawal))
                .andExpect(jsonPath("$.lines[2].transactionType").value("OUTBOUND_TRANSFER"))
                .andExpect(jsonPath("$.lines[2].description").value("Transfer to Ibrahim Musa"))
                .andExpect(jsonPath("$.lines[2].balanceAfter").value(1200.0))
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.page.hasNext").value(false));
    }

    @Test
    void runningBalancesShouldCarryAcrossPages() throws Exception {

        Holder esther = activeHolder("esther");
        Holder john = activeHolder("john");

        backdate(fund(esther, 100_000), "2026-08-15T10:00:00Z");
        backdate(transferId(esther, john, "200", "page-transfer"), "2026-09-02T09:00:00Z");
        backdate(fund(esther, 50_000), "2026-09-10T12:00:00Z");
        backdate(transferId(esther, john, "100", "page-transfer-2"), "2026-09-20T08:00:00Z");

        statement(esther, "?from=2026-09-01&to=2026-09-30&size=2&page=0")
                .andExpect(jsonPath("$.lines", hasSize(2)))
                .andExpect(jsonPath("$.lines[0].balanceAfter").value(800.0))
                .andExpect(jsonPath("$.lines[1].balanceAfter").value(1300.0))
                .andExpect(jsonPath("$.page.totalPages").value(2))
                .andExpect(jsonPath("$.page.hasNext").value(true));

        statement(esther, "?from=2026-09-01&to=2026-09-30&size=2&page=1")
                .andExpect(jsonPath("$.lines", hasSize(1)))
                .andExpect(jsonPath("$.lines[0].amount").value(100.0))
                .andExpect(jsonPath("$.lines[0].balanceAfter").value(1200.0))
                .andExpect(jsonPath("$.openingBalance").value(1000.0))
                .andExpect(jsonPath("$.closingBalance").value(1200.0))
                .andExpect(jsonPath("$.page.hasNext").value(false));

        statement(esther, "?from=2026-09-01&to=2026-09-30&size=2&page=5")
                .andExpect(jsonPath("$.lines", hasSize(0)))
                .andExpect(jsonPath("$.closingBalance").value(1200.0));
    }

    @Test
    void statementDaysShouldBeLagosCalendarDays() throws Exception {

        Holder esther = activeHolder("esther");

        backdate(fund(esther, 1_000), "2026-08-31T23:30:00Z");
        backdate(fund(esther, 10_000), "2026-09-30T23:30:00Z");

        statement(esther, "?from=2026-09-01&to=2026-09-30")
                .andExpect(jsonPath("$.openingBalance").value(0.0))
                .andExpect(jsonPath("$.lines", hasSize(1)))
                .andExpect(jsonPath("$.lines[0].amount").value(10.0))
                .andExpect(jsonPath("$.closingBalance").value(10.0));

        statement(esther, "?from=2026-10-01&to=2026-10-01")
                .andExpect(jsonPath("$.openingBalance").value(10.0))
                .andExpect(jsonPath("$.lines", hasSize(1)))
                .andExpect(jsonPath("$.lines[0].amount").value(100.0))
                .andExpect(jsonPath("$.closingBalance").value(110.0));
    }

    @Test
    void statementShouldShowAReversedTransactionAndItsReversal() throws Exception {

        Holder esther = activeHolder("esther");
        Holder john = activeHolder("john");
        fund(esther, 100_000);

        String original = transferId(esther, john, "300", "stmt-reversed");
        reverseThroughOperations(original);

        statement(esther, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines", hasSize(3)))
                .andExpect(jsonPath("$.lines[1].transactionId").value(original))
                .andExpect(jsonPath("$.lines[1].transactionStatus").value("REVERSED"))
                .andExpect(jsonPath("$.lines[1].balanceAfter").value(700.0))
                .andExpect(jsonPath("$.lines[2].transactionType").value("REVERSAL"))
                .andExpect(jsonPath("$.lines[2].direction").value("CREDIT"))
                .andExpect(jsonPath("$.lines[2].balanceAfter").value(1000.0))
                .andExpect(jsonPath("$.closingBalance").value(1000.0));
    }

    @Test
    void onlyTheOwnerAndStaffCanReadAStatement() throws Exception {

        Holder esther = activeHolder("esther");
        Holder mallory = activeHolder("mallory");
        Holder support = staff(activeHolder("support"), "SUPPORT");
        fund(esther, 100_000);

        mockMvc.perform(authorized(get("/api/v1/ledger/accounts/{id}/statement", esther.accountId()), mallory.token()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ACCOUNT_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/ledger/accounts/{id}/statement", esther.accountId()))
                .andExpect(status().isUnauthorized());

        for (Holder staffMember : List.of(admin, support)) {
            mockMvc.perform(authorized(
                            get("/api/v1/admin/ledger/accounts/{id}/statement", esther.accountId()), staffMember.token()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.closingBalance").value(1000.0));
        }

        mockMvc.perform(authorized(
                        get("/api/v1/admin/ledger/accounts/{id}/statement", esther.accountId()), esther.token()))
                .andExpect(status().isForbidden());

        mockMvc.perform(authorized(
                        get("/api/v1/admin/ledger/accounts/{id}/statement", settlementAccountId()), admin.token()))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "?from=2026-09-10&to=2026-09-01",
            "?from=2025-01-01&to=2026-09-30",
            "?from=01-09-2026",
            "?size=0",
            "?size=201",
            "?page=-1"
    })
    void shouldRejectInvalidStatementRequests(String query) throws Exception {

        Holder esther = activeHolder("esther");

        statement(esther, query).andExpect(status().isBadRequest());
    }

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

    private Holder activeHolder(String name) throws Exception {

        String email = name + "@example.com";
        String customerId = register(email, "0802" + PHONES.incrementAndGet());

        jdbcTemplate.update(
                "UPDATE kyc_profiles SET status = 'VERIFIED', tier = 3 WHERE customer_id = ?::uuid",
                customerId
        );

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
                "INSERT INTO customer_roles (customer_id, role_id) "
                        + "SELECT ?::uuid, id FROM roles WHERE name = ?",
                holder.customerId(),
                role
        );

        return holder.withToken(login(holder.email()));
    }

    private String fund(Holder holder, long kobo) {

        return ledgerService.receiveInboundTransfer(new InboundTransfer(
                "TESTRAIL",
                "session-" + UUID.randomUUID(),
                holder.accountNumber(),
                BigDecimal.valueOf(kobo, 2),
                "NGN",
                new Counterparty("Chiamaka Obi", "Test Bank", "7000000001"),
                null
        )).id().toString();
    }

    private String sendToOtherBank(Holder holder, String amount, String key) {

        return ledgerService.postOutbound(
                UUID.fromString(holder.customerId()),
                UUID.fromString(holder.accountId()),
                new OutboundTransfer(
                        new BigDecimal(amount), "NGN", key, null, "TESTRAIL", "out-" + UUID.randomUUID(),
                        new Counterparty("Ibrahim Musa", "Test Bank", "7000000002")
                )
        ).id().toString();
    }

    private void reverseThroughOperations(String transactionId) throws Exception {

        Holder officer = staff(activeHolder("officer"), "OPERATIONS_OFFICER");
        Holder supervisor = staff(activeHolder("supervisor"), "OPERATIONS_SUPERVISOR");

        ledgerService.reverse(
                UUID.fromString(officer.customerId()),
                UUID.fromString(supervisor.customerId()),
                UUID.fromString(transactionId),
                "Wrong account",
                null,
                "reverse-" + transactionId
        );
    }

    private ResultActions statement(Holder holder, String query) throws Exception {

        return mockMvc.perform(authorized(
                get("/api/v1/ledger/accounts/" + holder.accountId() + "/statement" + query), holder.token()));
    }

    private void backdate(String transactionId, String instant) {

        asLedgerMaintenance(() -> jdbcTemplate.update(
                "UPDATE ledger_entries SET created_at = ?::timestamptz WHERE transaction_id = ?::uuid",
                instant,
                transactionId
        ));
    }

    private void asLedgerMaintenance(Runnable work) {

        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.execute("SET LOCAL paycore.ledger_maintenance = 'on'");
            work.run();
        });
    }

    private void insertBalancedTransaction(String reference, String idempotencyKey, Holder holder) {

        transactionTemplate.executeWithoutResult(status -> {
            String transactionId = insertTransaction(reference, idempotencyKey);
            insertEntry(transactionId, settlementAccountId(), "DEBIT", 100, "NGN");
            insertEntry(transactionId, holder.accountId(), "CREDIT", 100, "NGN");
        });
    }

    private String transferId(Holder from, Holder to, String amount, String key) throws Exception {

        return JsonPath.read(
                transfer(from, to.accountNumber(), amount, key, null)
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString(),
                "$.id"
        );
    }

    private BigDecimal settlementBalance() throws Exception {

        String body =
                mockMvc.perform(authorized(get("/api/v1/admin/ledger/system-accounts"), admin.token()))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString();

        List<Object> balances = JsonPath.read(body, "$[?(@.type == 'SETTLEMENT' && @.currency == 'NGN')].balance");

        return balances.isEmpty() ? BigDecimal.ZERO : new BigDecimal(String.valueOf(balances.getFirst()));
    }

    private String settlementAccountId() {

        return jdbcTemplate.queryForObject(
                "SELECT id::text FROM accounts WHERE account_type = 'SETTLEMENT' AND currency = 'NGN'",
                String.class
        );
    }

    private String insertTransaction(String reference, String idempotencyKey) {

        return jdbcTemplate.queryForObject(
                """
                INSERT INTO ledger_transactions
                    (id, reference, type, status, currency, initiated_by, idempotency_key, staff_note, created_at, posted_at)
                VALUES
                    (paycore_uuid_v7(), ?, 'DEPOSIT', 'POSTED', 'NGN', ?::uuid, ?, 'Test fixture', now(), now())
                RETURNING id::text
                """,
                String.class,
                reference,
                admin.customerId(),
                idempotencyKey
        );
    }

    private void insertEntry(String transactionId, String accountId, String type, long amountMinor, String currency) {

        jdbcTemplate.update(
                """
                INSERT INTO ledger_entries
                    (id, transaction_id, account_id, entry_type, amount_minor, currency, created_at)
                VALUES
                    (paycore_uuid_v7(), ?::uuid, ?::uuid, ?, ?, ?, now())
                """,
                transactionId,
                accountId,
                type,
                amountMinor,
                currency
        );
    }

    private void setStatus(Holder holder, String status) {

        jdbcTemplate.update(
                "UPDATE accounts SET status = ?, closed_at = CASE WHEN ? = 'CLOSED' THEN now() END "
                        + "WHERE id = ?::uuid",
                status,
                status,
                holder.accountId()
        );
    }

    private Integer transactionCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ledger_transactions", Integer.class);
    }

    private Object serviceTransfer(Holder from, Holder to, String amount, String key) {

        return ledgerService.transfer(
                UUID.fromString(from.customerId()),
                UUID.fromString(from.accountId()),
                new TransferRequest(to.accountNumber(), new BigDecimal(amount), "NGN", key, null)
        );
    }

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

    private BigDecimal balance(Holder holder) throws Exception {

        String body =
                mockMvc.perform(authorized(
                                get("/api/v1/ledger/accounts/{id}/balance", holder.accountId()), holder.token()))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString();

        return new BigDecimal(String.valueOf((Object) JsonPath.read(body, "$.balance")));
    }

    private ResultActions transfer(
            Holder from,
            String destinationAccountNumber,
            String amount,
            String idempotencyKey,
            String description
    ) throws Exception {

        return mockMvc.perform(authorized(
                        post("/api/v1/ledger/accounts/{id}/transfers", from.accountId()), from.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(transferBody(destinationAccountNumber, amount, idempotencyKey, description)));
    }

    private static String transferBody(
            String destinationAccountNumber,
            String amount,
            String idempotencyKey,
            String description
    ) {
        return """
                {
                    "destinationAccountNumber": "%s",
                    "amount": %s,
                    "currency": "NGN",
                    "idempotencyKey": "%s",
                    "description": %s
                }
                """.formatted(
                destinationAccountNumber,
                amount,
                idempotencyKey,
                description == null ? "null" : "\"" + description + "\""
        );
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
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        return JsonPath.read(response, "$.id");
    }

    private String login(String email) throws Exception {

        String response =
                mockMvc.perform(post("/api/v1/auth/login")
                                .header("X-Device-Id", TEST_DEVICE_ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                            "email": "%s",
                                            "password": "Password123"
                                        }
                                        """.formatted(email)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

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
