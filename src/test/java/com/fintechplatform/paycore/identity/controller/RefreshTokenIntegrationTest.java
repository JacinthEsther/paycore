package com.fintechplatform.paycore.identity.controller;

import com.fintechplatform.paycore.identity.entity.RefreshToken;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class RefreshTokenIntegrationTest {

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
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private LoginSessionRepository loginSessionRepository;

    @Autowired
    private SessionTokenService sessionTokenService;

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
    void shouldRotateRefreshTokenAndIssueNewAccessToken()
            throws Exception {

        String login = registerAndLogin();

        String originalRefresh = JsonPath.read(login, "$.refreshToken");

        String refreshed = refresh(originalRefresh)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessToken").exists())
                .andExpect(jsonPath("$.refreshToken").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String newRefresh = JsonPath.read(refreshed, "$.refreshToken");
        String newAccess = JsonPath.read(refreshed, "$.accessToken");

        assertThat(newRefresh).isNotEqualTo(originalRefresh);

        RefreshToken old = stored(originalRefresh);
        RefreshToken current = stored(newRefresh);

        assertThat(old.isRevoked()).isTrue();
        assertThat(old.getReplacedBy()).isEqualTo(current.getId());
        assertThat(current.getFamilyId()).isEqualTo(old.getFamilyId());
        assertThat(current.isActive()).isTrue();

        mockMvc.perform(
                        get("/api/v1/customers/me")
                                .header("Authorization", "Bearer " + newAccess)
                )
                .andExpect(status().isOk());
    }

    @Test
    void shouldDetectReuseAndRevokeWholeFamilyAndSession()
            throws Exception {

        String login = registerAndLogin();

        String tokenA = JsonPath.read(login, "$.refreshToken");

        String refreshed = refresh(tokenA)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String tokenB = JsonPath.read(refreshed, "$.refreshToken");

        // Replaying A is treated as theft.
        refresh(tokenA)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error")
                        .value("REFRESH_TOKEN_REUSED"));

        // The revocation is committed despite the error response.
        assertThat(refreshTokenRepository
                .findByFamilyIdAndRevokedAtIsNull(stored(tokenA).getFamilyId()))
                .isEmpty();

        assertThat(loginSessionRepository.findAll())
                .allSatisfy(session ->
                        assertThat(session.isRevoked()).isTrue()
                );

        // B was legitimately issued but is now unusable: re-authenticate.
        refresh(tokenB)
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectUnknownRefreshToken() throws Exception {

        refresh("not-a-real-refresh-token")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error")
                        .value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void shouldRejectRefreshAfterLogout() throws Exception {

        String login = registerAndLogin();

        String sessionToken = JsonPath.read(login, "$.sessionToken");
        String refreshToken = JsonPath.read(login, "$.refreshToken");

        mockMvc.perform(
                        post("/api/v1/auth/logout")
                                .header("X-Session-Token", sessionToken)
                )
                .andExpect(status().isNoContent());

        refresh(refreshToken)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error")
                        .value("INVALID_REFRESH_TOKEN"));
    }

    private org.springframework.test.web.servlet.ResultActions refresh(
            String refreshToken
    ) throws Exception {

        return mockMvc.perform(
                post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "refreshToken": "%s" }
                                """.formatted(refreshToken))
        );
    }

    private RefreshToken stored(String rawToken) {

        return refreshTokenRepository
                .findByTokenHash(sessionTokenService.hash(rawToken))
                .orElseThrow();
    }

    private String registerAndLogin() throws Exception {

        mockMvc.perform(
                        post("/api/v1/customers")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                            "firstName": "Esther",
                                            "lastName": "Agboniro",
                                            "email": "refresh@example.com",
                                            "countryCode": "NG",
                                            "phoneNumber": "08011111111",
                                            "password": "Password123"
                                        }
                                        """)
                )
                .andExpect(status().isCreated());

        return mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                            "email": "refresh@example.com",
                                            "password": "Password123"
                                        }
                                        """)
                )
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
