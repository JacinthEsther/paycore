package com.fintechplatform.paycore.security;

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
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class SecurityIntegrationTest {

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
    private JwtEncoder jwtEncoder;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {

        jdbcTemplate.execute("DELETE FROM kyc_verifications");
        jdbcTemplate.execute("DELETE FROM kyc_documents");
        jdbcTemplate.execute("DELETE FROM kyc_profiles");
        jdbcTemplate.execute("DELETE FROM refresh_tokens");
        jdbcTemplate.execute("DELETE FROM login_sessions");
        jdbcTemplate.execute("DELETE FROM identities");
        jdbcTemplate.execute("DELETE FROM role_assignment_events");
        jdbcTemplate.execute("DELETE FROM customer_roles");
        jdbcTemplate.execute("DELETE FROM customers");
    }

    @Test
    void shouldReturnOwnProfileWithValidAccessToken() throws Exception {

        String customerId = register("me@example.com", "08011111111");
        String accessToken = login("me@example.com");

        mockMvc.perform(
                        get("/api/v1/customers/me")
                                .header("Authorization", "Bearer " + accessToken)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(customerId))
                .andExpect(jsonPath("$.email").value("me@example.com"));
    }

    @Test
    void shouldNotApplyCorsWhenNoOriginsAreConfigured() throws Exception {

        // A dev proxy forwards the browser's Origin header to another host.
        register("proxied@example.com", "08011111119", "http://localhost:5173");
    }

    @Test
    void shouldReturnBadRequestNotUnauthorizedForInvalidRegistration() throws Exception {

        mockMvc.perform(
                        post("/api/v1/customers")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}")
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectRequestWithoutAccessToken() throws Exception {

        mockMvc.perform(get("/api/v1/customers/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectTamperedAccessToken() throws Exception {

        register("tamper@example.com", "08011111112");
        String accessToken = login("tamper@example.com");

        String tampered =
                accessToken.substring(0, accessToken.length() - 2) + "xx";

        mockMvc.perform(
                        get("/api/v1/customers/me")
                                .header("Authorization", "Bearer " + tampered)
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customerShouldBeForbiddenFromSuspendingCustomers() throws Exception {

        String customerId = register("normal@example.com", "08011111113");
        String accessToken = login("normal@example.com");

        mockMvc.perform(
                        post("/api/v1/customers/{id}/suspend", customerId)
                                .header("Authorization", "Bearer " + accessToken)
                )
                .andExpect(status().isForbidden());
    }

    @Test
    void customerShouldBeForbiddenFromReadingOtherCustomers() throws Exception {

        String otherId = register("other@example.com", "08011111114");
        register("reader@example.com", "08011111115");
        String accessToken = login("reader@example.com");

        mockMvc.perform(
                        get("/api/v1/customers/{id}", otherId)
                                .header("Authorization", "Bearer " + accessToken)
                )
                .andExpect(status().isForbidden());
    }

    @Test
    void adminShouldBeAllowedToReadCustomers() throws Exception {

        String targetId = register("target@example.com", "08011111116");
        String adminId = register("admin@example.com", "08011111117");

        grantRole(UUID.fromString(adminId), RoleName.ADMIN);

        String accessToken = login("admin@example.com");

        mockMvc.perform(
                        get("/api/v1/customers/{id}", targetId)
                                .header("Authorization", "Bearer " + accessToken)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(targetId));
    }

    @Test
    void supportShouldReadButNotSuspendCustomers() throws Exception {

        String targetId = register("subject@example.com", "08011111119");
        String supportId = register("support@example.com", "08011111120");

        grantRole(UUID.fromString(supportId), RoleName.SUPPORT);

        String accessToken = login("support@example.com");

        mockMvc.perform(
                        get("/api/v1/customers/{id}", targetId)
                                .header("Authorization", "Bearer " + accessToken)
                )
                .andExpect(status().isOk());

        mockMvc.perform(
                        post("/api/v1/customers/{id}/suspend", targetId)
                                .header("Authorization", "Bearer " + accessToken)
                )
                .andExpect(status().isForbidden());
    }

    @Test
    void customerShouldBeAllowedToStartOwnKyc() throws Exception {

        register("kyc@example.com", "08011111121");
        String accessToken = login("kyc@example.com");

        mockMvc.perform(
                        post("/api/v1/kyc/start")
                                .header("Authorization", "Bearer " + accessToken)
                )
                .andExpect(status().isOk());
    }

    @Test
    void callerWithoutKycPermissionsShouldBeForbiddenFromKyc() throws Exception {

        String customerId = register("norole@example.com", "08011111122");

        jdbcTemplate.update(
                "DELETE FROM customer_roles WHERE customer_id = ?",
                UUID.fromString(customerId)
        );

        String accessToken = login("norole@example.com");

        mockMvc.perform(
                        post("/api/v1/kyc")
                                .header("Authorization", "Bearer " + accessToken)
                )
                .andExpect(status().isForbidden());

        mockMvc.perform(
                        get("/api/v1/kyc")
                                .header("Authorization", "Bearer " + accessToken)
                )
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldRejectExpiredAccessToken() throws Exception {

        String customerId = register("expired@example.com", "08011111123");

        Instant now = Instant.now();

        // Signed with the real key, so expiry is the only thing wrong.
        // Ten minutes is well past the decoder's 60 second clock skew.
        String expired =
                signedAccessToken(
                        customerId,
                        now.minus(Duration.ofMinutes(30)),
                        now.minus(Duration.ofMinutes(10))
                );

        String valid =
                signedAccessToken(
                        customerId,
                        now,
                        now.plus(Duration.ofMinutes(10))
                );

        mockMvc.perform(
                        get("/api/v1/customers/me")
                                .header("Authorization", "Bearer " + expired)
                )
                .andExpect(status().isUnauthorized());

        mockMvc.perform(
                        get("/api/v1/customers/me")
                                .header("Authorization", "Bearer " + valid)
                )
                .andExpect(status().isOk());
    }

    @Test
    void shouldRequireAuthenticationForLogoutAll() throws Exception {

        mockMvc.perform(post("/api/v1/auth/logout-all"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldLogoutAllSessionsWithAccessToken() throws Exception {

        register("all@example.com", "08011111118");
        String accessToken = login("all@example.com");

        mockMvc.perform(
                        post("/api/v1/auth/logout-all")
                                .header("Authorization", "Bearer " + accessToken)
                )
                .andExpect(status().isNoContent());
    }

    private void grantRole(UUID customerId, String roleName) {

        transactionTemplate.executeWithoutResult(status -> {
            Customer customer =
                    customerRepository.findById(customerId).orElseThrow();
            roleAssignmentService.assignRole(
                    customer,
                    roleName,
                    null,
                    "Granted by SecurityIntegrationTest"
            );
        });
    }

    private String signedAccessToken(
            String customerId,
            Instant issuedAt,
            Instant expiresAt
    ) {

        JwtClaimsSet claims =
                JwtClaimsSet.builder()
                        .subject(customerId)
                        .claim(
                                AccessTokenService.PERMISSIONS_CLAIM,
                                List.of("PROFILE_READ")
                        )
                        .issuedAt(issuedAt)
                        .expiresAt(expiresAt)
                        .build();

        return jwtEncoder
                .encode(JwtEncoderParameters.from(
                        JwsHeader.with(MacAlgorithm.HS256).build(),
                        claims
                ))
                .getTokenValue();
    }

    private String register(String email, String phone) throws Exception {

        return register(email, phone, null);
    }

    private String register(String email, String phone, String origin) throws Exception {

        MockHttpServletRequestBuilder request = post("/api/v1/customers");

        if (origin != null) {
            request.header("Origin", origin);
        }

        String response =
                mockMvc.perform(
                                request
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
