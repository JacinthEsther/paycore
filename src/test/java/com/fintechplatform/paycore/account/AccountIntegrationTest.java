package com.fintechplatform.paycore.account;

import com.fintechplatform.paycore.account.service.NubanAccountNumberGenerator;
import com.fintechplatform.paycore.authorization.entity.RoleName;
import com.fintechplatform.paycore.authorization.service.RoleAssignmentService;
import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The account API end to end: authentication, permissions, ownership, the
 * KYC gate, staff status changes and the audit history.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AccountIntegrationTest {

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

    private static final String OPEN_NGN = """
            { "type": "PERSONAL", "currency": "NGN" }
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private RoleAssignmentService roleAssignmentService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {

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
                "customers"
        }) {
            jdbcTemplate.execute("DELETE FROM " + table);
        }
    }

    // ============================================================
    // AUTHENTICATION AND THE KYC GATE
    // ============================================================

    @Test
    void anonymousCallersShouldBeRejected() throws Exception {

        mockMvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OPEN_NGN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unverifiedCustomerCanApplyButCannotBeActivated() throws Exception {

        register("unverified@example.com", "08011111101");
        String token = login("unverified@example.com");

        String accountId =
                JsonPath.read(
                        openAccount(token, OPEN_NGN)
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.status").value("PENDING"))
                                .andReturn().getResponse().getContentAsString(),
                        "$.id"
                );

        String adminToken = registerStaff("admin@example.com", "08011111119", RoleName.ADMIN);

        statusChange(adminToken, accountId, "activate", "Looks fine")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("KYC_VERIFICATION_REQUIRED"));

        mockMvc.perform(authorized(get("/api/v1/accounts/{id}", accountId), token))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    // ============================================================
    // OPENING AND READING OWN ACCOUNTS
    // ============================================================

    @Test
    void verifiedCustomerShouldOpenAndReadTheirAccount() throws Exception {

        String customerId = registerVerified("owner@example.com", "08011111102");
        String token = login("owner@example.com");

        String body =
                openAccount(token, OPEN_NGN)
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.customerId").value(customerId))
                        .andExpect(jsonPath("$.type").value("PERSONAL"))
                        .andExpect(jsonPath("$.status").value("PENDING"))
                        .andExpect(jsonPath("$.currency").value("NGN"))
                        .andExpect(jsonPath("$.accountNumber").value(matchesPattern("\\d{10}")))
                        .andExpect(jsonPath("$.balance").doesNotExist())
                        .andReturn().getResponse().getContentAsString();

        String accountId = JsonPath.read(body, "$.id");
        String accountNumber = JsonPath.read(body, "$.accountNumber");

        assertThat(NubanAccountNumberGenerator.isValid("999", accountNumber)).isTrue();

        mockMvc.perform(authorized(get("/api/v1/accounts"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(accountId));

        mockMvc.perform(authorized(get("/api/v1/accounts/{id}", accountId), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNumber").value(accountNumber));

        // Opening is audited.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT event_type FROM account_status_events WHERE account_id = ?::uuid",
                String.class,
                accountId
        )).isEqualTo("OPENED");
    }

    @Test
    void shouldReturnLocationHeader() throws Exception {

        registerVerified("loc@example.com", "08011111105");
        String token = login("loc@example.com");

        ResultActions result = openAccount(token, OPEN_NGN).andExpect(status().isCreated());

        String accountId =
                JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");

        result.andExpect(header().string("Location", "/api/v1/accounts/" + accountId));
    }

    @Test
    void customerIdInTheBodyShouldBeIgnored() throws Exception {

        String callerId = registerVerified("caller@example.com", "08011111106");
        String victimId = registerVerified("victim@example.com", "08011111107");
        String token = login("caller@example.com");

        openAccount(token, """
                {
                  "type": "PERSONAL",
                  "currency": "NGN",
                  "customerId": "%s",
                  "status": "FROZEN",
                  "accountNumber": "0000000000"
                }
                """.formatted(victimId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerId").value(callerId))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.accountNumber").value(not("0000000000")));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM accounts WHERE customer_id = ?::uuid",
                Integer.class,
                victimId
        )).isZero();
    }

    @Test
    void shouldRejectSecondOpenAccountInSameCurrency() throws Exception {

        registerVerified("twice@example.com", "08011111108");
        String token = login("twice@example.com");

        openAccount(token, OPEN_NGN).andExpect(status().isCreated());

        openAccount(token, """
                { "type": "PERSONAL", "currency": "ngn" }
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ACCOUNT_ALREADY_EXISTS"));

        assertThat(accountCount()).isEqualTo(1);
    }

    @Test
    void shouldValidateTheRequest() throws Exception {

        registerVerified("invalid@example.com", "08011111109");
        String token = login("invalid@example.com");

        openAccount(token, """
                { "type": "PERSONAL", "currency": "USD" }
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("UNSUPPORTED_CURRENCY"));

        openAccount(token, """
                { "type": "PERSONAL", "currency": "₦" }
                """)
                .andExpect(status().isBadRequest());

        openAccount(token, """
                { "currency": "NGN" }
                """)
                .andExpect(status().isBadRequest());

        openAccount(token, """
                { "type": "SAVINGS", "currency": "NGN" }
                """)
                .andExpect(status().isBadRequest());

        assertThat(accountCount()).isZero();
    }

    @Test
    void customerShouldNotSeeAnotherCustomersAccount() throws Exception {

        String otherAccountId = openAccountWithNewCustomer();

        register("snoop@example.com", "08011111110");
        String token = login("snoop@example.com");

        mockMvc.perform(authorized(get("/api/v1/accounts/{id}", otherAccountId), token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ACCOUNT_NOT_FOUND"));

        mockMvc.perform(authorized(get("/api/v1/accounts"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void customerShouldNotUseStaffEndpoints() throws Exception {

        String accountId = openAccountWithNewCustomer();
        String customerId = jdbcTemplate.queryForObject(
                "SELECT customer_id::text FROM accounts WHERE id = ?::uuid",
                String.class,
                accountId
        );

        String token = login("holder@example.com");

        mockMvc.perform(authorized(get("/api/v1/admin/accounts/{id}", accountId), token))
                .andExpect(status().isForbidden());

        mockMvc.perform(authorized(
                        get("/api/v1/admin/customers/{id}/accounts", customerId), token))
                .andExpect(status().isForbidden());

        statusChange(token, accountId, "freeze", "I want to")
                .andExpect(status().isForbidden());
    }

    // ============================================================
    // STAFF
    // ============================================================

    @Test
    void supportShouldReadButNotChangeAccounts() throws Exception {

        String accountId = openAccountWithNewCustomer();
        String supportToken = registerStaff("support@example.com", "08011111120", RoleName.SUPPORT);

        mockMvc.perform(authorized(get("/api/v1/admin/accounts/{id}", accountId), supportToken))
                .andExpect(status().isOk());

        mockMvc.perform(authorized(
                        get("/api/v1/admin/accounts/{id}/history", accountId), supportToken))
                .andExpect(status().isOk());

        statusChange(supportToken, accountId, "activate", "Just looking")
                .andExpect(status().isForbidden());

        statusChange(supportToken, accountId, "freeze", "Just looking")
                .andExpect(status().isForbidden());
    }

    @Test
    void adminShouldActivateFreezeUnfreezeAndCloseWithFullAuditTrail() throws Exception {

        String accountId = openAccountWithNewCustomer();
        String adminId = register("admin@example.com", "08011111121");
        grantRole(adminId, RoleName.ADMIN);
        String adminToken = login("admin@example.com");

        statusChange(adminToken, accountId, "activate", "KYC verified")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        statusChange(adminToken, accountId, "freeze", "Suspected fraud")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));

        statusChange(adminToken, accountId, "unfreeze", "Customer verified by phone")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        statusChange(adminToken, accountId, "close", "Customer request")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.closedAt").isNotEmpty());

        mockMvc.perform(authorized(
                        get("/api/v1/admin/accounts/{id}/history", accountId), adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)))
                .andExpect(jsonPath("$[0].eventType").value("OPENED"))
                .andExpect(jsonPath("$[0].toStatus").value("PENDING"))
                .andExpect(jsonPath("$[1].eventType").value("ACTIVATED"))
                .andExpect(jsonPath("$[1].fromStatus").value("PENDING"))
                .andExpect(jsonPath("$[1].toStatus").value("ACTIVE"))
                .andExpect(jsonPath("$[1].reason").value("KYC verified"))
                .andExpect(jsonPath("$[2].eventType").value("FROZEN"))
                .andExpect(jsonPath("$[2].fromStatus").value("ACTIVE"))
                .andExpect(jsonPath("$[2].toStatus").value("FROZEN"))
                .andExpect(jsonPath("$[2].performedBy").value(adminId))
                .andExpect(jsonPath("$[2].reason").value("Suspected fraud"))
                .andExpect(jsonPath("$[3].eventType").value("UNFROZEN"))
                .andExpect(jsonPath("$[4].eventType").value("CLOSED"))
                .andExpect(jsonPath("$[4].reason").value("Customer request"));
    }

    @Test
    void adminShouldGetConflictForInvalidTransition() throws Exception {

        String accountId = openAccountWithNewCustomer();
        String adminToken = registerStaff("admin@example.com", "08011111122", RoleName.ADMIN);

        // A pending account cannot be frozen until it is activated.
        statusChange(adminToken, accountId, "freeze", "Too early")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_ACCOUNT_STATE"));

        statusChange(adminToken, accountId, "activate", "Verified")
                .andExpect(status().isOk());

        statusChange(adminToken, accountId, "activate", "Twice")
                .andExpect(status().isConflict());

        statusChange(adminToken, accountId, "unfreeze", "Not frozen")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_ACCOUNT_STATE"));

        statusChange(adminToken, accountId, "close", "Closing")
                .andExpect(status().isOk());

        statusChange(adminToken, accountId, "freeze", "Too late")
                .andExpect(status().isConflict());
    }

    @Test
    void statusChangeShouldRequireReason() throws Exception {

        String accountId = openAccountWithNewCustomer();
        String adminToken = registerStaff("admin@example.com", "08011111123", RoleName.ADMIN);

        mockMvc.perform(authorized(post("/api/v1/admin/accounts/{id}/activate", accountId), adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"reason\": \"   \" }"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(authorized(get("/api/v1/admin/accounts/{id}", accountId), adminToken))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void adminShouldNotChangeTheirOwnAccount() throws Exception {

        String adminId = registerVerified("self@example.com", "08011111124");
        grantRole(adminId, RoleName.ADMIN);
        String adminToken = login("self@example.com");

        String accountId =
                JsonPath.read(
                        openAccount(adminToken, OPEN_NGN)
                                .andExpect(status().isCreated())
                                .andReturn().getResponse().getContentAsString(),
                        "$.id"
                );

        // Segregation of duties: nobody activates their own account.
        statusChange(adminToken, accountId, "activate", "Approving myself")
                .andExpect(status().isForbidden());
    }

    @Test
    void customerShouldOpenNewAccountAfterClosure() throws Exception {

        String accountId = openAccountWithNewCustomer();
        String adminToken = registerStaff("admin@example.com", "08011111125", RoleName.ADMIN);

        statusChange(adminToken, accountId, "close", "Customer request")
                .andExpect(status().isOk());

        String token = login("holder@example.com");

        openAccount(token, OPEN_NGN)
                .andExpect(status().isCreated());

        mockMvc.perform(authorized(get("/api/v1/accounts"), token))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].status").value("CLOSED"))
                .andExpect(jsonPath("$[1].status").value("PENDING"));
    }

    @Test
    void staffShouldGetNotFoundForUnknownIds() throws Exception {

        String adminToken = registerStaff("admin@example.com", "08011111126", RoleName.ADMIN);

        mockMvc.perform(authorized(
                        get("/api/v1/admin/accounts/{id}", UUID.randomUUID()), adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ACCOUNT_NOT_FOUND"));

        mockMvc.perform(authorized(
                        get("/api/v1/admin/customers/{id}/accounts", UUID.randomUUID()), adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CUSTOMER_NOT_FOUND"));
    }

    // ============================================================
    // HELPERS
    // ============================================================

    /**
     * Registers holder@example.com with verified KYC and opens an NGN
     * account for them; returns the account id.
     */
    private String openAccountWithNewCustomer() throws Exception {

        registerVerified("holder@example.com", "08099999999");

        return JsonPath.read(
                openAccount(login("holder@example.com"), OPEN_NGN)
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString(),
                "$.id"
        );
    }

    private ResultActions openAccount(String token, String body) throws Exception {

        return mockMvc.perform(authorized(post("/api/v1/accounts"), token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions statusChange(
            String token,
            String accountId,
            String action,
            String reason
    ) throws Exception {

        return mockMvc.perform(authorized(
                        post("/api/v1/admin/accounts/{id}/" + action, accountId), token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ \"reason\": \"%s\" }".formatted(reason)));
    }

    private MockHttpServletRequestBuilder authorized(
            MockHttpServletRequestBuilder request,
            String token
    ) {
        return request.header("Authorization", "Bearer " + token);
    }

    private String registerStaff(String email, String phone, String role) throws Exception {

        grantRole(register(email, phone), role);

        return login(email);
    }

    private String registerVerified(String email, String phone) throws Exception {

        String customerId = register(email, phone);

        // KYC review itself is covered by KycFlowIntegrationTest.
        jdbcTemplate.update(
                "UPDATE kyc_profiles SET status = 'VERIFIED' WHERE customer_id = ?::uuid",
                customerId
        );

        return customerId;
    }

    private void grantRole(String customerId, String roleName) {

        transactionTemplate.executeWithoutResult(status -> {
            Customer customer =
                    customerRepository.findById(UUID.fromString(customerId)).orElseThrow();
            roleAssignmentService.assignRole(
                    customer,
                    roleName,
                    null,
                    "Granted by AccountIntegrationTest"
            );
        });
    }

    private Integer accountCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM accounts", Integer.class);
    }

    private String register(String email, String phone) throws Exception {

        String response =
                mockMvc.perform(post("/api/v1/customers")
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
