package com.fintechplatform.paycore.ledger;

import com.fintechplatform.paycore.ledger.domain.Counterparty;
import com.fintechplatform.paycore.ledger.dto.request.TransferRequest;
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

/**
 * The ledger end to end against PostgreSQL: balanced entries, derived
 * balances, ownership, account states, idempotency, database constraints
 * and concurrent transfers.
 */
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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LedgerService ledgerService;

    /** An ADMIN (with LEDGER_POST) who funds accounts with deposits. */
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
                "customers"
        }) {
            jdbcTemplate.execute("DELETE FROM " + table);
        }

        admin = staff(activeHolder("admin"), "ADMIN");
    }

    // ============================================================
    // A TRANSFER
    // ============================================================

    @Test
    void transferShouldPostBalancedEntriesAndMoveTheBalances() throws Exception {

        Holder esther = activeHolder("esther");
        Holder john = activeHolder("john");
        fund(esther, 10_000_000); // ₦100,000

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

        // Stored in kobo, positive, and debits equal credits.
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

    // ============================================================
    // SECURITY
    // ============================================================

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

        // Mallory names Esther's account as the source.
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

        // Sender and recipient.
        for (Holder involved : List.of(esther, john)) {
            mockMvc.perform(authorized(get("/api/v1/ledger/transactions/{id}", id), involved.token()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.reference").value(reference));

            mockMvc.perform(authorized(get("/api/v1/ledger/transactions/reference/{ref}", reference), involved.token()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(id));
        }

        // Anyone else: indistinguishable from a transaction that does not exist.
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

    // ============================================================
    // ACCOUNT STATE, CURRENCY AND FUNDS
    // ============================================================

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

        assertThat(transactionCount()).isEqualTo(1); // only the funding
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
        fund(esther, 100_000); // ₦1,000

        transfer(esther, john.accountNumber(), "1000.01", "transfer-0010", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("INSUFFICIENT_FUNDS"));

        // Exactly the balance is fine.
        transfer(esther, john.accountNumber(), "1000.00", "transfer-0011", null)
                .andExpect(status().isCreated());

        assertThat(balance(esther)).isEqualByComparingTo("0");
        assertThat(balance(john)).isEqualByComparingTo("1000.00");
    }

    @Test
    void shouldRejectInvalidTransfers() throws Exception {

        Holder esther = activeHolder("esther");
        fund(esther, 100_000);

        // To yourself.
        transfer(esther, esther.accountNumber(), "10", "transfer-0012", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_TRANSACTION"));

        // To an account that does not exist.
        transfer(esther, "0000000000", "10", "transfer-0013", null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ACCOUNT_NOT_FOUND"));

        // More decimal places than kobo: rejected, never rounded.
        transfer(esther, admin.accountNumber(), "10.005", "transfer-0014", null)
                .andExpect(status().isBadRequest());

        // Zero and negative amounts, malformed account number, short key.
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

    // ============================================================
    // IDEMPOTENCY
    // ============================================================

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
        assertThat(transactionCount()).isEqualTo(2); // funding + one transfer
    }

    @Test
    void reusingAKeyForADifferentRequestShouldBeRefused() throws Exception {

        Holder esther = activeHolder("esther");
        Holder john = activeHolder("john");
        Holder ada = activeHolder("ada");
        fund(esther, 100_000);

        transfer(esther, john.accountNumber(), "250", "transfer-reuse", null)
                .andExpect(status().isCreated());

        // Different amount, recipient or description under the same key.
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

        // John's identical key is his own: a new transfer, not Esther's replayed.
        String second =
                transfer(john, esther.accountNumber(), "40", "shared-key-1", null)
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(second, "$.id")).isNotEqualTo(JsonPath.read(first, "$.id"));
        assertThat(balance(esther)).isEqualByComparingTo("940.00");
        assertThat(balance(john)).isEqualByComparingTo("1060.00");
    }

    // ============================================================
    // DATABASE CONSTRAINTS
    // ============================================================

    @Test
    void databaseShouldEnforceLedgerInvariants() throws Exception {

        Holder esther = activeHolder("esther");
        String transactionId = fund(esther, 100);

        // Amounts are positive; direction is the entry type.
        assertThatThrownBy(() -> insertEntry(transactionId, esther.accountId(), "CREDIT", 0, "NGN"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertEntry(transactionId, esther.accountId(), "CREDIT", -100, "NGN"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertEntry(transactionId, esther.accountId(), "REFUND", 100, "NGN"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // An entry's currency must be its transaction's and its account's.
        assertThatThrownBy(() -> insertEntry(transactionId, esther.accountId(), "CREDIT", 100, "USD"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Every entry belongs to a real transaction and account.
        assertThatThrownBy(() -> insertEntry(UUID.randomUUID().toString(), esther.accountId(), "CREDIT", 100, "NGN"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // References are unique.
        String reference = jdbcTemplate.queryForObject(
                "SELECT reference FROM ledger_transactions WHERE id = ?::uuid", String.class, transactionId
        );
        assertThatThrownBy(() -> insertTransaction(reference, "another-key"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Idempotency keys are unique per initiating customer. (Inbound
        // transfers have no initiator; their rail session id is unique instead.)
        insertTransaction("TXN-UNIQUE-REF-1", "same-key-0001");
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

    // ============================================================
    // CONCURRENCY
    // ============================================================

    @Test
    void concurrentTransfersMustNotSpendTheSameMoneyTwice() throws Exception {

        Holder esther = activeHolder("esther");
        Holder john = activeHolder("john");
        Holder ada = activeHolder("ada");
        fund(esther, 10_000_000); // ₦100,000

        // Two ₦80,000 transfers at once: only one can fit.
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

        // A -> B and B -> A at the same time lock the same two rows; the
        // fixed lock order is what keeps this from deadlocking.
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

        // The whole ledger still balances: every debit has its credit.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT SUM(CASE WHEN entry_type = 'CREDIT' THEN amount_minor ELSE -amount_minor END) "
                        + "FROM ledger_entries",
                Long.class
        )).isZero();
    }

    // ============================================================
    // STATEMENTS
    // ============================================================

    @Test
    void statementShouldShowOpeningClosingTotalsAndRunningBalances() throws Exception {

        Holder esther = activeHolder("esther");
        Holder john = activeHolder("john");

        backdate(fund(esther, 100_000), "2026-08-15T10:00:00Z");                  // +1000 before
        String transfer = transferId(esther, john, "200", "stmt-transfer");
        backdate(transfer, "2026-09-02T09:00:00Z");                               // -200
        backdate(fund(esther, 50_000), "2026-09-10T12:00:00Z");                   // +500
        String withdrawal = sendToOtherBank(esther, "100", "stmt-outbound");
        backdate(withdrawal, "2026-09-20T08:00:00Z");                             // -100
        backdate(fund(esther, 5_000), "2026-10-01T09:00:00Z");                    // +50 after

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

        // Page 1 starts from the opening balance plus page 0, without it.
        statement(esther, "?from=2026-09-01&to=2026-09-30&size=2&page=1")
                .andExpect(jsonPath("$.lines", hasSize(1)))
                .andExpect(jsonPath("$.lines[0].amount").value(100.0))
                .andExpect(jsonPath("$.lines[0].balanceAfter").value(1200.0))
                .andExpect(jsonPath("$.openingBalance").value(1000.0))
                .andExpect(jsonPath("$.closingBalance").value(1200.0))
                .andExpect(jsonPath("$.page.hasNext").value(false));

        // Past the end: no lines, same totals.
        statement(esther, "?from=2026-09-01&to=2026-09-30&size=2&page=5")
                .andExpect(jsonPath("$.lines", hasSize(0)))
                .andExpect(jsonPath("$.closingBalance").value(1200.0));
    }

    @Test
    void statementDaysShouldBeLagosCalendarDays() throws Exception {

        Holder esther = activeHolder("esther");

        // 23:30 UTC is 00:30 the next day in Lagos (UTC+1).
        backdate(fund(esther, 1_000), "2026-08-31T23:30:00Z");    // ₦10, 1 Sep in Lagos
        backdate(fund(esther, 10_000), "2026-09-30T23:30:00Z");   // ₦100, 1 Oct in Lagos

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

        // No dates: month to date, which includes everything just posted.
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

        // System accounts have no statement here.
        mockMvc.perform(authorized(
                        get("/api/v1/admin/ledger/accounts/{id}/statement", settlementAccountId()), admin.token()))
                .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "?from=2026-09-10&to=2026-09-01",   // backwards
            "?from=2025-01-01&to=2026-09-30",   // longer than a year
            "?from=01-09-2026",                 // not an ISO date
            "?size=0",
            "?size=201",
            "?page=-1"
    })
    void shouldRejectInvalidStatementRequests(String query) throws Exception {

        Holder esther = activeHolder("esther");

        statement(esther, query).andExpect(status().isBadRequest());
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
     * A customer with verified KYC and an ACTIVE NGN account. KYC review
     * and activation have their own tests; here they are set directly.
     */
    private Holder activeHolder(String name) throws Exception {

        String email = name + "@example.com";
        String customerId = register(email, "0802" + PHONES.incrementAndGet());

        jdbcTemplate.update(
                "UPDATE kyc_profiles SET status = 'VERIFIED' WHERE customer_id = ?::uuid",
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

    /**
     * Grants a role directly (role management has its own tests) and signs
     * in again so the token carries its permissions.
     */
    private Holder staff(Holder holder, String role) throws Exception {

        jdbcTemplate.update(
                "INSERT INTO customer_roles (customer_id, role_id) "
                        + "SELECT ?::uuid, id FROM roles WHERE name = ?",
                holder.customerId(),
                role
        );

        return holder.withToken(login(holder.email()));
    }

    /**
     * Puts money into an account the way it really arrives: a transfer
     * from another bank, reported by the rail. Returns the transaction id.
     */
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

    /** Money out to another bank (the rail is not involved at this level). */
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

    /** A reversal requested by one operations officer and approved by another. */
    private void reverseThroughOperations(String transactionId) throws Exception {

        Holder officer = staff(activeHolder("officer"), "OPERATIONS");
        Holder supervisor = staff(activeHolder("supervisor"), "OPERATIONS");

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

    /** Moves a transaction's entries to a fixed moment, for statements. */
    private void backdate(String transactionId, String instant) {

        jdbcTemplate.update(
                "UPDATE ledger_entries SET created_at = ?::timestamptz WHERE transaction_id = ?::uuid",
                instant,
                transactionId
        );
    }

    /** Transfers and returns the transaction id. */
    private String transferId(Holder from, Holder to, String amount, String key) throws Exception {

        return JsonPath.read(
                transfer(from, to.accountNumber(), amount, key, null)
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString(),
                "$.id"
        );
    }

    /** The NGN settlement account's balance: money PayCore holds. */
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
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        return JsonPath.read(response, "$.id");
    }

    private String login(String email) throws Exception {

        String response =
                mockMvc.perform(post("/api/v1/auth/login")
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

        return JsonPath.read(response, "$.accessToken");
    }
}
