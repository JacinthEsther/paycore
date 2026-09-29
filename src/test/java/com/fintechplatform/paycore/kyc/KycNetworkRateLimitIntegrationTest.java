package com.fintechplatform.paycore.kyc;

import com.fintechplatform.paycore.kyc.enums.VerificationResult;
import com.fintechplatform.paycore.kyc.provider.KycProvider;
import com.fintechplatform.paycore.kyc.provider.KycProviderResult;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Per-network BVN rate limit over real HTTP, JWT and PostgreSQL, with a
 * low limit (3 checks per network per hour) so it is reachable without
 * tripping the per-customer limit. Only the KYC provider is mocked.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "paycore.kyc.bvn.ip-max-attempts=3",
        "paycore.kyc.bvn.ip-attempt-window=1h"
})
class KycNetworkRateLimitIntegrationTest {

    private static final String ATTACKER_NETWORK = "203.0.113.7";

    private static final String OTHER_NETWORK = "198.51.100.23";

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
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private KycProvider kycProvider;

    @BeforeEach
    void cleanDatabase() {

        for (String table : List.of(
                "kyc_verifications",
                "kyc_documents",
                "kyc_profiles",
                "refresh_tokens",
                "login_sessions",
                "identities",
                "role_assignment_events",
                "customer_roles",
                "customers"
        )) {
            jdbcTemplate.execute("DELETE FROM " + table);
        }
    }

    @Test
    void shouldBlockOneNetworkSpreadingChecksOverManyAccounts()
            throws Exception {

        when(kycProvider.verifyBvn(any()))
                .thenReturn(result(VerificationResult.FAILED));

        // Three different accounts, one check each: under every
        // per-customer limit, but they use up the network's budget.
        for (int i = 1; i <= 3; i++) {
            String account = customerWithKyc("acct" + i + "@example.com", "0801111113" + i);

            verifyBvn(account, ATTACKER_NETWORK)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.remainingAttempts").value(2));
        }

        // A fourth fresh account from the same network is refused.
        String fourth = customerWithKyc("acct4@example.com", "08011111134");

        String retryAfter =
                verifyBvn(fourth, ATTACKER_NETWORK)
                        .andExpect(status().isTooManyRequests())
                        .andExpect(jsonPath("$.error")
                                .value("BVN_IP_RATE_LIMIT_EXCEEDED"))
                        .andReturn()
                        .getResponse()
                        .getHeader("Retry-After");

        // About an hour until the oldest check leaves the window.
        assertThat(Long.parseLong(retryAfter)).isBetween(3_500L, 3_600L);

        // The blocked request never reached the provider.
        verify(kycProvider, times(3)).verifyBvn(any());

        // Every recorded attempt carries its network.
        assertThat(jdbcTemplate.queryForList(
                "SELECT DISTINCT ip_address FROM kyc_verifications", String.class
        )).containsExactly(ATTACKER_NETWORK);

        // The same account from a different network is unaffected.
        verifyBvn(fourth, OTHER_NETWORK)
                .andExpect(status().isOk());
    }

    @Test
    void shouldCountPassedChecksTowardNetworkLimit() throws Exception {

        when(kycProvider.verifyBvn(any()))
                .thenReturn(result(VerificationResult.PASSED));

        for (int i = 1; i <= 3; i++) {
            String account = customerWithKyc("pass" + i + "@example.com", "0801111114" + i);

            verifyBvn(account, ATTACKER_NETWORK)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        }

        String fourth = customerWithKyc("pass4@example.com", "08011111144");

        verifyBvn(fourth, ATTACKER_NETWORK)
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void shouldGroupIpv6AddressesByPrefix() throws Exception {

        when(kycProvider.verifyBvn(any()))
                .thenReturn(result(VerificationResult.FAILED));

        // Rotating addresses inside one /64 does not escape the limit.
        for (int i = 1; i <= 3; i++) {
            String account = customerWithKyc("v6-" + i + "@example.com", "0801111115" + i);

            verifyBvn(account, "2001:db8:1:2::" + i)
                    .andExpect(status().isOk());
        }

        String fourth = customerWithKyc("v6-4@example.com", "08011111154");

        verifyBvn(fourth, "2001:db8:1:2:ffff::99")
                .andExpect(status().isTooManyRequests());

        assertThat(jdbcTemplate.queryForList(
                "SELECT DISTINCT ip_address FROM kyc_verifications", String.class
        )).containsExactly("2001:db8:1:2::/64");
    }

    @Test
    void concurrentChecksFromOneNetworkShouldNotExceedTheLimit()
            throws Exception {

        // Six different accounts, so the per-customer row lock does not
        // serialize them: only the network lock can keep the count right.
        List<String> accounts = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            accounts.add(customerWithKyc("race" + i + "@example.com", "0801111116" + i));
        }

        when(kycProvider.verifyBvn(any()))
                .thenAnswer(invocation -> {
                    Thread.sleep(200);
                    return result(VerificationResult.FAILED);
                });

        ExecutorService executor = Executors.newFixedThreadPool(6);

        try {
            List<Future<Integer>> responses = new ArrayList<>();

            for (String account : accounts) {
                responses.add(executor.submit(() ->
                        verifyBvn(account, ATTACKER_NETWORK)
                                .andReturn().getResponse().getStatus()
                ));
            }

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> response : responses) {
                statuses.add(response.get(60, TimeUnit.SECONDS));
            }

            assertThat(statuses).filteredOn(code -> code == 200).hasSize(3);
            assertThat(statuses).filteredOn(code -> code == 429).hasSize(3);

        } finally {
            executor.shutdownNow();
        }

        verify(kycProvider, times(3)).verifyBvn(any());
    }

    private ResultActions verifyBvn(String accessToken, String clientIp)
            throws Exception {

        return mockMvc.perform(
                post("/api/v1/kyc/bvn")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "bvn": "22222222222",
                                  "firstName": "Esther",
                                  "lastName": "Test",
                                  "dateOfBirth": "1995-01-01"
                                }
                                """)
                        .with(request -> {
                            request.setRemoteAddr(clientIp);
                            return request;
                        })
        );
    }

    /**
     * Registers, logs in and creates a KYC profile; returns the access token.
     */
    private String customerWithKyc(String email, String phone) throws Exception {

        mockMvc.perform(
                        post("/api/v1/customers")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                            "firstName": "Esther",
                                            "lastName": "Test",
                                            "email": "%s",
                                            "countryCode": "NG",
                                            "phoneNumber": "%s",
                                            "password": "Password123"
                                        }
                                        """.formatted(email, phone))
                )
                .andExpect(status().isCreated());

        String accessToken =
                JsonPath.read(
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
                                .andReturn().getResponse().getContentAsString(),
                        "$.accessToken"
                );

        // The KYC profile was created at registration.
        mockMvc.perform(
                        post("/api/v1/kyc/start")
                                .header("Authorization", "Bearer " + accessToken)
                )
                .andExpect(status().isOk());

        return accessToken;
    }

    private KycProviderResult result(VerificationResult verificationResult) {

        return new KycProviderResult(
                "DOJAH",
                null,
                verificationResult,
                verificationResult == VerificationResult.FAILED ? "mismatch" : null
        );
    }
}
