package com.fintechplatform.paycore.demo;

import com.fintechplatform.paycore.authorization.entity.RoleName;
import com.fintechplatform.paycore.authorization.service.RoleAssignmentService;
import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.customer.enums.CustomerStatus;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = {
        "paycore.demo.enabled=true",
        "paycore.demo.admin-email=Admin@PayCore.Demo",
        "paycore.demo.admin-password=DemoAdmin123",
        "paycore.cors.allowed-origins=https://ui.example.com",
        "paycore.kyc.provider=simulated",
        "paycore.rails.provider=simulated",
        "paycore.kyc.documents.storage-dir=target/demo-mode-test-documents"
})
@AutoConfigureMockMvc
class DemoModeIntegrationTest {

    private static final String ADMIN_EMAIL = "admin@paycore.demo";
    private static final String ADMIN_PASSWORD = "DemoAdmin123";

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
        registry.add(
                "spring.datasource.url",
                postgres::getJdbcUrl
        );

        registry.add(
                "spring.datasource.username",
                postgres::getUsername
        );

        registry.add(
                "spring.datasource.password",
                postgres::getPassword
        );
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DemoAdminSeeder demoAdminSeeder;

    @Autowired
    private DemoRecipientSeeder demoRecipientSeeder;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private RoleAssignmentService roleAssignmentService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabaseExceptDemoAdmin() {

        jdbcTemplate.execute("DELETE FROM operations_requests");
        jdbcTemplate.execute("DELETE FROM ledger_entries");
        jdbcTemplate.execute("DELETE FROM ledger_transactions");
        jdbcTemplate.execute("DELETE FROM account_status_events");
        jdbcTemplate.execute("DELETE FROM accounts");
        jdbcTemplate.execute("DELETE FROM kyc_verifications");
        jdbcTemplate.execute("DELETE FROM kyc_documents");
        jdbcTemplate.execute("DELETE FROM kyc_profiles");
        jdbcTemplate.execute("DELETE FROM refresh_tokens");
        jdbcTemplate.execute("DELETE FROM login_sessions");

        String others =
                "(SELECT id FROM customers WHERE email <> '" + ADMIN_EMAIL + "')";

        jdbcTemplate.execute("DELETE FROM identities WHERE customer_id IN " + others);
        jdbcTemplate.execute("DELETE FROM role_assignment_events WHERE customer_id IN " + others);
        jdbcTemplate.execute("DELETE FROM customer_roles WHERE customer_id IN " + others);
        jdbcTemplate.execute("DELETE FROM customers WHERE email <> '" + ADMIN_EMAIL + "'");

        demoAdminSeeder.seed();
        demoRecipientSeeder.seed();
    }

    @Test
    void shouldPublishDemoAdminSignInWithoutAuthentication() throws Exception {

        mockMvc.perform(get("/api/v1/demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.adminEmail").value(ADMIN_EMAIL))
                .andExpect(jsonPath("$.adminPassword").value(ADMIN_PASSWORD));
    }

    /**
     * The whole walkthrough the demo UI guides visitors through, with the
     * simulated provider and a NIN instead of a BVN.
     */
    @Test
    void customerAndAdminShouldCompleteTheDemoJourney() throws Exception {

        mockMvc.perform(get("/api/v1/demo"))
                .andExpect(jsonPath("$.kycProvider").value("SIMULATED"));

        register("journey@example.com", "08011111113");
        String customer = accessToken("journey@example.com", "Password123");

        mockMvc.perform(get("/api/v1/kyc/status").header("Authorization", "Bearer " + customer))
                .andExpect(jsonPath("$.status").value("NOT_STARTED"));
        mockMvc.perform(post("/api/v1/kyc/start").header("Authorization", "Bearer " + customer))
                .andExpect(status().isOk());

        mockMvc.perform(
                        post("/api/v1/kyc/submit")
                                .header("Authorization", "Bearer " + customer)
                )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.missing[0]").value("passed BVN or NIN verification"));

        mockMvc.perform(
                        post("/api/v1/kyc/nin")
                                .header("Authorization", "Bearer " + customer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"nin": "70123456789", "firstName": "John",
                                         "lastName": "Doe", "dateOfBirth": "1990-01-01"}
                                        """)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("PASSED"))
                .andExpect(jsonPath("$.provider").value("SIMULATED"));

        mockMvc.perform(
                        multipart("/api/v1/kyc/documents")
                                .file(new MockMultipartFile(
                                        "file",
                                        "id.png",
                                        "image/png",
                                        new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0}
                                ))
                                .param("documentType", "NATIONAL_ID")
                                .header("Authorization", "Bearer " + customer)
                )
                .andExpect(status().isCreated());

        String kycId =
                JsonPath.read(
                        mockMvc.perform(
                                        post("/api/v1/kyc/submit")
                                                .header("Authorization", "Bearer " + customer)
                                )
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                                .andReturn()
                                .getResponse()
                                .getContentAsString(),
                        "$.id"
                );

        String admin = accessToken(ADMIN_EMAIL, ADMIN_PASSWORD);

        mockMvc.perform(
                        get("/api/v1/kyc/reviews")
                                .param("status", "SUBMITTED")
                                .header("Authorization", "Bearer " + admin)
                )
                .andExpect(jsonPath("$.profiles[0].kycId").value(kycId))
                .andExpect(jsonPath("$.profiles[0].ninPassed").value(true))
                .andExpect(jsonPath("$.profiles[0].bvnPassed").value(false));

        mockMvc.perform(post("/api/v1/kyc/{id}/start-review", kycId).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/kyc/{id}/approve", kycId).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"));

        mockMvc.perform(get("/api/v1/kyc/status").header("Authorization", "Bearer " + customer))
                .andExpect(jsonPath("$.status").value("VERIFIED"));

        mockMvc.perform(get("/api/v1/kyc/{id}/nin-attempts", kycId).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attempts.length()").value(1));
    }

    @Test
    void failedNinChecksShouldHaveTheirOwnRetryLimit() throws Exception {

        register("ninlimit@example.com", "08011111114");
        String customer = accessToken("ninlimit@example.com", "Password123");

        mockMvc.perform(post("/api/v1/kyc/start").header("Authorization", "Bearer " + customer));

        String wrongNin = """
                {"nin": "70123456789", "firstName": "Wrong",
                 "lastName": "Doe", "dateOfBirth": "1990-01-01"}
                """;

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(
                            post("/api/v1/kyc/nin")
                                    .header("Authorization", "Bearer " + customer)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(wrongNin)
                    )
                    .andExpect(jsonPath("$.result").value("FAILED"));
        }

        mockMvc.perform(
                        post("/api/v1/kyc/nin")
                                .header("Authorization", "Bearer " + customer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(wrongNin)
                )
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("NIN_ATTEMPT_LIMIT_EXCEEDED"));

        // BVN has its own allowance.
        mockMvc.perform(
                        post("/api/v1/kyc/bvn")
                                .header("Authorization", "Bearer " + customer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"bvn": "22222222222", "firstName": "John",
                                         "lastName": "Doe", "dateOfBirth": "1990-01-01"}
                                        """)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("PASSED"));
    }

    @Test
    void shouldAllowTheConfiguredUiOriginOnly() throws Exception {

        mockMvc.perform(
                        options("/api/v1/customers/me")
                                .header("Origin", "https://ui.example.com")
                                .header("Access-Control-Request-Method", "GET")
                                .header("Access-Control-Request-Headers", "authorization")
                )
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://ui.example.com"));

        mockMvc.perform(
                        get("/api/v1/demo")
                                .header("Origin", "https://evil.example.com")
                )
                .andExpect(status().isForbidden());
    }

    @Test
    void seededAdminShouldSignInActiveWithAdminAndCustomerRoles() throws Exception {

        String response = loginResponse(ADMIN_EMAIL, ADMIN_PASSWORD);

        assertThat((String) JsonPath.read(response, "$.status"))
                .isEqualTo("ACTIVE");

        String accessToken = JsonPath.read(response, "$.accessToken");

        mockMvc.perform(
                        get("/api/v1/admin/customers")
                                .header("Authorization", "Bearer " + accessToken)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customers[*].email", hasItem(ADMIN_EMAIL)))
                .andExpect(jsonPath("$.customers[?(@.email == '" + ADMIN_EMAIL + "')].roles[*]", hasItem("ADMIN")));

        mockMvc.perform(
                        get("/api/v1/customers/me")
                                .header("Authorization", "Bearer " + accessToken)
                )
                .andExpect(status().isOk());
    }

    @Test
    void seedingShouldRepairASuspendedAdminWithoutDuplicatingIt() {

        transactionTemplate.executeWithoutResult(status -> {
            Customer admin = customerRepository.findByEmail(ADMIN_EMAIL).orElseThrow();
            admin.suspend();
            roleAssignmentService.revokeRole(admin, RoleName.ADMIN, null, "test");
        });

        demoAdminSeeder.seed();

        transactionTemplate.executeWithoutResult(status -> {
            Customer admin = customerRepository.findByEmail(ADMIN_EMAIL).orElseThrow();
            assertThat(admin.getStatus()).isEqualTo(CustomerStatus.ACTIVE);
            assertThat(admin.getRoles())
                    .extracting(role -> role.getName())
                    .contains(RoleName.ADMIN, RoleName.CUSTOMER);
        });

        assertThat(
                jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM customers WHERE email = ?",
                        Long.class,
                        ADMIN_EMAIL
                )
        ).isEqualTo(1L);
    }

    @Test
    void anotherAdminShouldNotBeAbleToLockOutTheDemoAdmin() throws Exception {

        UUID visitorId = UUID.fromString(register("visitor@example.com", "08011111111"));

        transactionTemplate.executeWithoutResult(status ->
                roleAssignmentService.assignRole(
                        customerRepository.findById(visitorId).orElseThrow(),
                        RoleName.ADMIN,
                        null,
                        "test"
                )
        );

        String visitorToken = accessToken("visitor@example.com", "Password123");
        String demoAdminId = customerRepository.findByEmail(ADMIN_EMAIL).orElseThrow().getId().toString();

        mockMvc.perform(
                        post("/api/v1/customers/{id}/suspend", demoAdminId)
                                .header("Authorization", "Bearer " + visitorToken)
                )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("DEMO_ACCOUNT_PROTECTED"));

        mockMvc.perform(
                        post("/api/v1/admin/customers/{id}/roles/ADMIN/revoke", demoAdminId)
                                .header("Authorization", "Bearer " + visitorToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"reason": "take over"}
                                        """)
                )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("DEMO_ACCOUNT_PROTECTED"));

        mockMvc.perform(
                        patch("/api/v1/customers/{id}", demoAdminId)
                                .header("Authorization", "Bearer " + visitorToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"email": "mine@example.com"}
                                        """)
                )
                .andExpect(status().isForbidden());

        // Admin actions on anyone else still work.
        String adminToken = accessToken(ADMIN_EMAIL, ADMIN_PASSWORD);

        mockMvc.perform(
                        post("/api/v1/admin/customers/{id}/roles/ADMIN/revoke", visitorId)
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"reason": "demo clean-up"}
                                        """)
                )
                .andExpect(status().isOk());
    }

    @Test
    void shouldPublishAnActiveDemoRecipientAccount() throws Exception {

        String body =
                mockMvc.perform(get("/api/v1/demo"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.recipientName").value("Tunde Bakare (demo recipient)"))
                        .andExpect(jsonPath("$.recipientAccountNumber").value(matchesPattern("\\d{10}")))
                        .andReturn().getResponse().getContentAsString();

        String accountNumber = JsonPath.read(body, "$.recipientAccountNumber");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM accounts WHERE account_number = ?", String.class, accountNumber
        )).isEqualTo("ACTIVE");

        // Seeding again reuses the recipient and its account.
        demoRecipientSeeder.seed();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM customers WHERE email = ?", Long.class, DemoRecipientSeeder.EMAIL
        )).isEqualTo(1L);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM accounts a JOIN customers c ON c.id = a.customer_id WHERE c.email = ?",
                Long.class,
                DemoRecipientSeeder.EMAIL
        )).isEqualTo(1L);
    }

    @Test
    void seedingShouldRepairAFrozenRecipientAccountWithAnAuditedEvent() {

        String accountId = recipientAccountId();
        jdbcTemplate.update("UPDATE accounts SET status = 'FROZEN' WHERE id = ?::uuid", accountId);

        demoRecipientSeeder.seed();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM accounts WHERE id = ?::uuid", String.class, accountId
        )).isEqualTo("ACTIVE");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT c.email FROM account_status_events e JOIN customers c ON c.id = e.performed_by "
                        + "WHERE e.account_id = ?::uuid AND e.event_type = 'UNFROZEN'",
                String.class,
                accountId
        )).isEqualTo(ADMIN_EMAIL);
    }

    @Test
    void visitorsCanSendMoneyToTheRecipientButNotLockItOut() throws Exception {

        String visitorId = register("sender@example.com", "08011111112");
        jdbcTemplate.update(
                "UPDATE kyc_profiles SET status = 'VERIFIED' WHERE customer_id = ?::uuid", visitorId
        );

        String visitorToken = accessToken("sender@example.com", "Password123");
        String adminToken = accessToken(ADMIN_EMAIL, ADMIN_PASSWORD);

        String visitorAccount = JsonPath.read(
                mockMvc.perform(post("/api/v1/accounts")
                                .header("Authorization", "Bearer " + visitorToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"type": "PERSONAL", "currency": "NGN"}
                                        """))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString(),
                "$.id"
        );

        mockMvc.perform(post("/api/v1/admin/accounts/{id}/activate", visitorAccount)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "KYC verified"}
                                """))
                .andExpect(status().isOk());

        // Money arrives from another bank; nobody at PayCore can type it in.
        mockMvc.perform(post("/api/v1/simulator/test-bank/transfers")
                        .header("Authorization", "Bearer " + visitorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"destinationAccountNumber": "%s", "amount": 1000}
                                """.formatted(accountNumberOf(visitorAccount))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("INBOUND_TRANSFER"));

        String recipientNumber = JsonPath.read(
                mockMvc.perform(get("/api/v1/demo")).andReturn().getResponse().getContentAsString(),
                "$.recipientAccountNumber"
        );

        String transfer = JsonPath.read(
                mockMvc.perform(post("/api/v1/ledger/accounts/{id}/transfers", visitorAccount)
                                .header("Authorization", "Bearer " + visitorToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"destinationAccountNumber": "%s", "amount": 250, "currency": "NGN",
                                         "idempotencyKey": "demo-transfer-1"}
                                        """.formatted(recipientNumber)))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString(),
                "$.id"
        );

        // Nobody can take the shared recipient out of use for everyone else.
        String recipientAccount = recipientAccountId();
        String recipientId = customerRepository.findByEmail(DemoRecipientSeeder.EMAIL).orElseThrow().getId().toString();

        for (String action : new String[]{"freeze", "close"}) {
            mockMvc.perform(post("/api/v1/admin/accounts/{id}/" + action, recipientAccount)
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"reason": "take it down"}
                                    """))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error").value("DEMO_ACCOUNT_PROTECTED"));
        }

        mockMvc.perform(post("/api/v1/customers/{id}/suspend", recipientId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("DEMO_ACCOUNT_PROTECTED"));

        // The admin cannot move money at all...
        mockMvc.perform(post("/api/v1/ops/requests/reversals")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"transactionId": "%s", "reason": "Demo reversal"}
                                """.formatted(transfer)))
                .andExpect(status().isForbidden());

        // ...but the two shared operations officers can reverse a transfer
        // into the recipient: one requests, the other approves.
        String officer = accessToken(DemoStaff.OFFICER.email(), ADMIN_PASSWORD);
        String supervisor = accessToken(DemoStaff.SUPERVISOR.email(), ADMIN_PASSWORD);

        String request = JsonPath.read(
                mockMvc.perform(post("/api/v1/ops/requests/reversals")
                                .header("Authorization", "Bearer " + officer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"transactionId": "%s", "reason": "Demo reversal"}
                                        """.formatted(transfer)))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString(),
                "$.id"
        );

        mockMvc.perform(post("/api/v1/ops/requests/{id}/approve", request)
                        .header("Authorization", "Bearer " + officer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FOUR_EYES_REQUIRED"));

        mockMvc.perform(post("/api/v1/ops/requests/{id}/approve", request)
                        .header("Authorization", "Bearer " + supervisor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // And freezing an ordinary customer's account is unaffected.
        mockMvc.perform(post("/api/v1/admin/accounts/{id}/freeze", visitorAccount)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "ordinary freeze"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void visitorsShouldReadOnlyTheirOwnVerificationEmail() throws Exception {

        register("reader@example.com", "08011111113");
        register("other@example.com", "08011111114");

        String readerToken = accessToken("reader@example.com", "Password123");

        String outbox =
                mockMvc.perform(get("/api/v1/demo/outbox").header("Authorization", "Bearer " + readerToken))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.length()").value(1))
                        .andExpect(jsonPath("$[0].to").value("reader@example.com"))
                        .andExpect(jsonPath("$[0].subject").value("Verify your PayCore email address"))
                        .andReturn().getResponse().getContentAsString();

        // The link in it verifies the reader.
        String body = JsonPath.read(outbox, "$[0].body");
        String token = body.replaceAll("(?s).*verify-email\\?token=([A-Za-z0-9_-]+).*", "$1");

        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\": \"%s\"}".formatted(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(get("/api/v1/demo/outbox"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void demoAdminShouldNotEditItsOwnProfile() throws Exception {

        mockMvc.perform(patch("/api/v1/customers/me")
                        .header("Authorization", "Bearer " + accessToken(ADMIN_EMAIL, ADMIN_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "taken@example.com"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("DEMO_ACCOUNT_PROTECTED"));
    }

    @Test
    void demoAdminShouldNotSignOutEverySharedSession() throws Exception {

        String adminToken = accessToken(ADMIN_EMAIL, ADMIN_PASSWORD);

        mockMvc.perform(
                        post("/api/v1/auth/logout-all")
                                .header("Authorization", "Bearer " + adminToken)
                )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("DEMO_ACCOUNT_PROTECTED"));
    }

    @Test
    void reviewQueueShouldListSubmittedProfilesForAdminsOnly() throws Exception {

        register("queue@example.com", "08011111112");
        String customerToken = accessToken("queue@example.com", "Password123");

        mockMvc.perform(
                        get("/api/v1/kyc/reviews")
                                .header("Authorization", "Bearer " + customerToken)
                )
                .andExpect(status().isForbidden());

        mockMvc.perform(
                        get("/api/v1/admin/customers")
                                .header("Authorization", "Bearer " + customerToken)
                )
                .andExpect(status().isForbidden());

        String adminToken = accessToken(ADMIN_EMAIL, ADMIN_PASSWORD);

        // The demo recipient never goes through KYC, so it is listed too.
        mockMvc.perform(
                        get("/api/v1/kyc/reviews")
                                .param("status", "NOT_STARTED")
                                .header("Authorization", "Bearer " + adminToken)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profiles.length()").value(2))
                .andExpect(jsonPath("$.profiles[*].customerEmail", hasItem("queue@example.com")))
                .andExpect(jsonPath("$.profiles[*].customerEmail", hasItem(DemoRecipientSeeder.EMAIL)))
                .andExpect(jsonPath("$.profiles[?(@.customerEmail == 'queue@example.com')].bvnPassed", hasItem(false)))
                .andExpect(jsonPath("$.page.totalElements").value(2));

        mockMvc.perform(
                        get("/api/v1/kyc/reviews")
                                .param("status", "SUBMITTED")
                                .header("Authorization", "Bearer " + adminToken)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profiles.length()").value(0));

        mockMvc.perform(
                        get("/api/v1/admin/customers")
                                .param("search", "QUEUE@")
                                .header("Authorization", "Bearer " + adminToken)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customers.length()").value(1))
                .andExpect(jsonPath("$.customers[0].roles[0]").value("CUSTOMER"));
    }

    private String register(String email, String phone) throws Exception {

        String response =
                mockMvc.perform(
                                post("/api/v1/customers")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("""
                                                {
                                                    "firstName": "Esther",
                                                    "lastName": "Agboniro",
                                                    "email": "%s",
                                                    "countryCode": "NG",
                                                    "phoneNumber": "%s",
                                                    "password": "Password123"
                                                }
                                                """.formatted(email, phone))
                        )
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        return JsonPath.read(response, "$.id");
    }

    private String accountNumberOf(String accountId) {

        return jdbcTemplate.queryForObject(
                "SELECT account_number FROM accounts WHERE id = ?::uuid",
                String.class,
                accountId
        );
    }

    private String recipientAccountId() {

        return jdbcTemplate.queryForObject(
                "SELECT a.id::text FROM accounts a JOIN customers c ON c.id = a.customer_id WHERE c.email = ?",
                String.class,
                DemoRecipientSeeder.EMAIL
        );
    }

    private String accessToken(String email, String password) throws Exception {

        return JsonPath.read(loginResponse(email, password), "$.accessToken");
    }

    private String loginResponse(String email, String password) throws Exception {

        return mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                            "email": "%s",
                                            "password": "%s"
                                        }
                                        """.formatted(email, password))
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
