package com.fintechplatform.paycore.authorization.controller;

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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AdminRoleIntegrationTest {

    private static final String ROLES =
            "/api/v1/admin/customers/{customerId}/roles";

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
    private CustomerRepository customerRepository;

    @Autowired
    private RoleAssignmentService roleAssignmentService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String adminId;
    private String adminToken;
    private String targetId;

    @BeforeEach
    void setUp() throws Exception {

        jdbcTemplate.execute("DELETE FROM kyc_verifications");
        jdbcTemplate.execute("DELETE FROM kyc_documents");
        jdbcTemplate.execute("DELETE FROM kyc_profiles");
        jdbcTemplate.execute("DELETE FROM refresh_tokens");
        jdbcTemplate.execute("DELETE FROM login_sessions");
        jdbcTemplate.execute("DELETE FROM identities");
        jdbcTemplate.execute("DELETE FROM role_assignment_events");
        jdbcTemplate.execute("DELETE FROM customer_roles");
        jdbcTemplate.execute("DELETE FROM customers");

        adminId = register("admin@example.com", "08011111111");
        bootstrapAdmin(UUID.fromString(adminId));
        adminToken = login("admin@example.com");

        targetId = register("target@example.com", "08011111112");
    }

    @Test
    void adminShouldAssignRoleAndRecordWhoDidIt() throws Exception {

        assign(targetId, "SUPPORT", "Joined support team")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(targetId))
                .andExpect(jsonPath("$.roles",
                        contains("CUSTOMER", "SUPPORT")));

        mockMvc.perform(
                        get(ROLES + "/history", targetId)
                                .header("Authorization", "Bearer " + adminToken)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].role").value("CUSTOMER"))
                .andExpect(jsonPath("$[0].action").value("ASSIGNED"))
                .andExpect(jsonPath("$[0].performedBy").value(nullValue()))
                .andExpect(jsonPath("$[1].role").value("SUPPORT"))
                .andExpect(jsonPath("$[1].action").value("ASSIGNED"))
                .andExpect(jsonPath("$[1].performedBy").value(adminId))
                .andExpect(jsonPath("$[1].reason").value("Joined support team"));
    }

    @Test
    void assignedRoleShouldTakeEffectOnNextLogin() throws Exception {

        String otherId = register("other@example.com", "08011111113");

        assign(targetId, "support", "Lower-case role names are accepted")
                .andExpect(status().isOk());

        String targetToken = login("target@example.com");

        mockMvc.perform(
                        get("/api/v1/customers/{id}", otherId)
                                .header("Authorization", "Bearer " + targetToken)
                )
                .andExpect(status().isOk());
    }

    @Test
    void adminShouldRevokeRoleAndKeepHistory() throws Exception {

        assign(targetId, "SUPPORT", "Temporary cover")
                .andExpect(status().isOk());

        mockMvc.perform(
                        post(ROLES + "/{role}/revoke", targetId, "SUPPORT")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        { "reason": "Cover ended" }
                                        """)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", contains("CUSTOMER")));

        mockMvc.perform(
                        get(ROLES + "/history", targetId)
                                .header("Authorization", "Bearer " + adminToken)
                )
                .andExpect(jsonPath("$[*].action",
                        contains("ASSIGNED", "ASSIGNED", "REVOKED")))
                .andExpect(jsonPath("$[2].reason").value("Cover ended"))
                .andExpect(jsonPath("$[2].performedBy").value(adminId));
    }

    @Test
    void adminShouldReadCurrentRoles() throws Exception {

        mockMvc.perform(
                        get(ROLES, targetId)
                                .header("Authorization", "Bearer " + adminToken)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", contains("CUSTOMER")));
    }

    @Test
    void adminShouldNotChangeOwnRoles() throws Exception {

        assign(adminId, "SUPPORT", "Granting myself more")
                .andExpect(status().isForbidden());

        mockMvc.perform(
                        post(ROLES + "/{role}/revoke", adminId, "ADMIN")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        { "reason": "Oops" }
                                        """)
                )
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldRejectDuplicateAssignment() throws Exception {

        assign(targetId, "CUSTOMER", "Already has it")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ROLE_ALREADY_ASSIGNED"));
    }

    @Test
    void shouldRejectRevokingRoleNotHeld() throws Exception {

        mockMvc.perform(
                        post(ROLES + "/{role}/revoke", targetId, "ADMIN")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        { "reason": "Not held" }
                                        """)
                )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ROLE_NOT_ASSIGNED"));
    }

    @Test
    void shouldRejectUnknownRole() throws Exception {

        assign(targetId, "SUPERUSER", "No such role")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ROLE_NOT_FOUND"));
    }

    @Test
    void shouldRejectUnknownCustomer() throws Exception {

        assign(UUID.randomUUID().toString(), "SUPPORT", "Nobody")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CUSTOMER_NOT_FOUND"));
    }

    @Test
    void shouldRequireReason() throws Exception {

        assign(targetId, "SUPPORT", " ")
                .andExpect(status().isBadRequest());
    }

    @Test
    void customerAndSupportShouldBeForbidden() throws Exception {

        String customerToken = login("target@example.com");

        String supportId = register("support@example.com", "08011111114");
        bootstrapRole(UUID.fromString(supportId), RoleName.SUPPORT);
        String supportToken = login("support@example.com");

        for (String token : new String[]{customerToken, supportToken}) {

            mockMvc.perform(
                            post(ROLES, supportId)
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("""
                                            { "role": "ADMIN", "reason": "Escalate" }
                                            """)
                    )
                    .andExpect(status().isForbidden());

            mockMvc.perform(
                            get(ROLES + "/history", targetId)
                                    .header("Authorization", "Bearer " + token)
                    )
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void shouldRequireAuthentication() throws Exception {

        mockMvc.perform(get(ROLES, targetId))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions assign(
            String customerId,
            String role,
            String reason
    ) throws Exception {

        return mockMvc.perform(
                post(ROLES, customerId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "role": "%s", "reason": "%s" }
                                """.formatted(role, reason))
        );
    }

    /**
     * The first admin has to be created outside the API, the same way an
     * operator would seed it.
     */
    private void bootstrapAdmin(UUID customerId) {
        bootstrapRole(customerId, RoleName.ADMIN);
    }

    private void bootstrapRole(UUID customerId, String roleName) {

        transactionTemplate.executeWithoutResult(status -> {
            Customer customer =
                    customerRepository.findById(customerId).orElseThrow();
            roleAssignmentService.assignRole(
                    customer,
                    roleName,
                    null,
                    "Bootstrapped by AdminRoleIntegrationTest"
            );
        });
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

    private String login(String email) throws Exception {

        String response =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("""
                                                {
                                                    "email": "%s",
                                                    "password": "Password123"
                                                }
                                                """.formatted(email))
                        )
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        return JsonPath.read(response, "$.accessToken");
    }
}
