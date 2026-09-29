package com.fintechplatform.paycore.identity.controller;

import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.authorization.repository.RoleAssignmentEventRepository;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
import com.fintechplatform.paycore.kyc.repository.KycProfileRepository;
import com.fintechplatform.paycore.identity.entity.Identity;
import com.fintechplatform.paycore.identity.entity.LoginSession;
import com.fintechplatform.paycore.identity.repository.IdentityRepository;
import com.fintechplatform.paycore.identity.repository.LoginSessionRepository;
import com.fintechplatform.paycore.identity.repository.RefreshTokenRepository;
import com.fintechplatform.paycore.identity.service.SessionTokenService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class AuthenticationIntegrationTest {

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
    private IdentityRepository identityRepository;

    @Autowired
    private LoginSessionRepository loginSessionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SessionTokenService sessionTokenService;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RoleAssignmentEventRepository roleAssignmentEventRepository;

    @Autowired
    private KycProfileRepository kycProfileRepository;

    @BeforeEach
    void cleanDatabase() {

        // Registration creates a KYC profile for every customer.
        kycProfileRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        loginSessionRepository.deleteAll();
        identityRepository.deleteAll();
        roleAssignmentEventRepository.deleteAll();
        customerRepository.deleteAll();
    }

    @Test
    void shouldLoginSuccessfullyAndCreateSession()
            throws Exception {

        Customer customer =
                createCustomerWithPassword(
                        "esther@example.com",
                        "+2348012345678",
                        "Password123!"
                );

        String request =
                """
                {
                    "email": "ESTHER@EXAMPLE.COM",
                    "password": "Password123!"
                }
                """;

        String response =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .header("User-Agent", "PayCore-Test-Client")
                                        .content(request)
                        )
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.customerId")
                                .value(customer.getId().toString()))
                        .andExpect(jsonPath("$.email")
                                .value("esther@example.com"))
                        .andExpect(jsonPath("$.status")
                                .value("PENDING_VERIFICATION"))
                        .andExpect(jsonPath("$.sessionId")
                                .exists())
                        .andExpect(jsonPath("$.sessionToken")
                                .exists())
                        .andExpect(jsonPath("$.sessionExpiresAt")
                                .exists())
                        .andExpect(jsonPath("$.message")
                                .value("Login successful"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        String sessionId =
                JsonPath.read(response, "$.sessionId");

        String sessionToken =
                JsonPath.read(response, "$.sessionToken");

        List<LoginSession> sessions =
                loginSessionRepository
                        .findByCustomerAndRevokedAtIsNull(customer);

        assertThat(sessions)
                .hasSize(1);

        LoginSession session =
                sessions.get(0);

        assertThat(session.getId().toString())
                .isEqualTo(sessionId);

        assertThat(session.getSessionTokenHash())
                .isEqualTo(sessionTokenService.hash(sessionToken))
                .isNotEqualTo(sessionToken);

        assertThat(session.getIpAddress())
                .isEqualTo("127.0.0.1");

        assertThat(session.getUserAgent())
                .isEqualTo("PayCore-Test-Client");

        assertThat(session.isActive())
                .isTrue();
    }

    @Test
    void shouldIssueAccessAndRefreshTokensOnLogin()
            throws Exception {

        createCustomerWithPassword(
                "tokens@example.com",
                "+2348012345670",
                "Password123!"
        );

        String response =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("""
                                                {
                                                    "email": "tokens@example.com",
                                                    "password": "Password123!"
                                                }
                                                """)
                        )
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.tokenType").value("Bearer"))
                        .andExpect(jsonPath("$.accessToken").exists())
                        .andExpect(jsonPath("$.accessTokenExpiresAt").exists())
                        .andExpect(jsonPath("$.refreshToken").exists())
                        .andExpect(jsonPath("$.refreshTokenExpiresAt").exists())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        String refreshToken =
                JsonPath.read(response, "$.refreshToken");

        assertThat(refreshTokenRepository.findAll())
                .singleElement()
                .satisfies(stored -> {
                    assertThat(stored.getTokenHash())
                            .isEqualTo(sessionTokenService.hash(refreshToken))
                            .isNotEqualTo(refreshToken);
                    assertThat(stored.isActive()).isTrue();
                });
    }

    @Test
    void shouldRevokeSessionAndRefreshTokensOnLogout()
            throws Exception {

        Customer customer =
                createCustomerWithPassword(
                        "logout@example.com",
                        "+2348012345671",
                        "Password123!"
                );

        String response =
                mockMvc.perform(
                                post("/api/v1/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("""
                                                {
                                                    "email": "logout@example.com",
                                                    "password": "Password123!"
                                                }
                                                """)
                        )
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        String sessionToken =
                JsonPath.read(response, "$.sessionToken");

        mockMvc.perform(
                        post("/api/v1/auth/logout")
                                .header("X-Session-Token", sessionToken)
                )
                .andExpect(status().isNoContent());

        assertThat(
                loginSessionRepository
                        .findByCustomerAndRevokedAtIsNull(customer)
        )
                .isEmpty();

        assertThat(
                refreshTokenRepository
                        .findByCustomerAndRevokedAtIsNull(customer)
        )
                .isEmpty();
    }

    @Test
    void shouldRejectLogoutWithUnknownSessionToken()
            throws Exception {

        mockMvc.perform(
                        post("/api/v1/auth/logout")
                                .header("X-Session-Token", "not-a-real-token")
                )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error")
                        .value("INVALID_SESSION"));
    }

    @Test
    void shouldRejectDisabledIdentity()
            throws Exception {

        Customer customer =
                createCustomerWithPassword(
                        "disabled@example.com",
                        "+2348012345672",
                        "Password123!"
                );

        identityRepository.findAll()
                .stream()
                .filter(identity -> identity.getProviderSubject()
                        .equals(customer.getEmail()))
                .forEach(identity -> {
                    identity.disable();
                    identityRepository.saveAndFlush(identity);
                });

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                            "email": "disabled@example.com",
                                            "password": "Password123!"
                                        }
                                        """)
                )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error")
                        .value("IDENTITY_DISABLED"));
    }

    @Test
    void shouldRegisterAndLoginCustomer()
            throws Exception {

        String registration =
                """
                {
                    "firstName": "Esther",
                    "lastName": "Agboniro",
                    "email": "esther@example.com",
                    "countryCode": "NG",
                    "phoneNumber": "08011111111",
                    "password": "Password123"
                }
                """;

        String registrationResponse =
                mockMvc.perform(
                                post("/api/v1/customers")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(registration)
                        )
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        String customerId =
                JsonPath.read(registrationResponse, "$.id");

        String login =
                """
                {
                    "email": "esther@example.com",
                    "password": "Password123"
                }
                """;

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(login)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId")
                        .value(customerId))
                .andExpect(jsonPath("$.email")
                        .value("esther@example.com"))
                .andExpect(jsonPath("$.sessionToken")
                        .exists())
                .andExpect(jsonPath("$.message")
                        .value("Login successful"));
    }

    @Test
    void shouldRejectInvalidPassword()
            throws Exception {

        Customer customer =
                createCustomerWithPassword(
                        "invalid-password@example.com",
                        "+2348012345679",
                        "CorrectPassword123!"
                );

        String request =
                """
                {
                    "email": "invalid-password@example.com",
                    "password": "WrongPassword123!"
                }
                """;

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(request)
                )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status")
                        .value(401))
                .andExpect(jsonPath("$.error")
                        .value("INVALID_CREDENTIALS"));

        assertThat(
                loginSessionRepository
                        .findByCustomerAndRevokedAtIsNull(customer)
        )
                .isEmpty();
    }

    private Customer createCustomerWithPassword(
            String email,
            String phoneNumber,
            String rawPassword
    ) {

        Customer customer =
                customerRepository.saveAndFlush(
                        Customer.create(
                                "Esther",
                                "Agboniro",
                                email,
                                phoneNumber
                        )
                );

        identityRepository.saveAndFlush(
                Identity.createPasswordIdentity(
                        customer,
                        customer.getEmail(),
                        passwordEncoder.encode(rawPassword)
                )
        );

        return customer;
    }
}
