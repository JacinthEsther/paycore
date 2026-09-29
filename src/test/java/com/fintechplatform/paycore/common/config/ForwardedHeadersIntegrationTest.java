package com.fintechplatform.paycore.common.config;

import com.fintechplatform.paycore.kyc.enums.VerificationResult;
import com.fintechplatform.paycore.kyc.provider.KycProvider;
import com.fintechplatform.paycore.kyc.provider.KycProviderResult;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Runs the prod profile on a real Tomcat (MockMvc bypasses Tomcat, so it
 * cannot exercise RemoteIpValve). The test client connects over loopback,
 * which is inside the default trusted-proxy range, so it plays the role of
 * the load balancer.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prod")
@TestPropertySource(properties =
        "PAYCORE_JWT_SECRET=test-only-prod-profile-secret-0123456789abcdef")
class ForwardedHeadersIntegrationTest {

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
    private TestRestTemplate restTemplate;

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

        register("proxy@example.com", "08011111171");
    }

    @Test
    void shouldUseClientAddressForwardedByTrustedProxy() {

        String sessionId =
                loginSessionId(forwardedFor("203.0.113.9"));

        assertThat(sessionIp(sessionId)).isEqualTo("203.0.113.9");
    }

    @Test
    void shouldIgnoreAddressesForgedByTheClient() {

        // The client sent "X-Forwarded-For: 6.6.6.6"; the proxy appended the
        // address it actually saw. The right-most untrusted entry wins.
        String sessionId =
                loginSessionId(forwardedFor("6.6.6.6, 203.0.113.9"));

        assertThat(sessionIp(sessionId)).isEqualTo("203.0.113.9");
    }

    @Test
    void shouldSkipTrustedProxiesInTheChain() {

        // client -> edge proxy (10.0.0.5) -> load balancer (loopback)
        String sessionId =
                loginSessionId(forwardedFor("203.0.113.9, 10.0.0.5"));

        assertThat(sessionIp(sessionId)).isEqualTo("203.0.113.9");
    }

    @Test
    void shouldFallBackToPeerAddressWithoutForwardedHeader() {

        String sessionId = loginSessionId(new HttpHeaders());

        assertThat(sessionIp(sessionId)).isIn("127.0.0.1", "0:0:0:0:0:0:0:1");
    }

    @Test
    void shouldRateLimitBvnChecksOnTheForwardedClientAddress() {

        when(kycProvider.verifyBvn(any()))
                .thenReturn(new KycProviderResult(
                        "DOJAH", null, VerificationResult.FAILED, "mismatch"
                ));

        String accessToken =
                JsonPath.read(login(new HttpHeaders()).getBody(), "$.accessToken");

        HttpHeaders auth = new HttpHeaders();
        auth.setBearerAuth(accessToken);

        // The KYC profile was created at registration, so it only needs starting.
        assertThat(restTemplate.exchange(
                "/api/v1/kyc/start",
                org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(auth),
                String.class
        ).getStatusCode()).isEqualTo(HttpStatus.OK);

        HttpHeaders headers = forwardedFor("6.6.6.6, 2001:db8:1:2::99");
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response =
                restTemplate.postForEntity(
                        "/api/v1/kyc/bvn",
                        new HttpEntity<>("""
                                {
                                  "bvn": "22222222222",
                                  "firstName": "Esther",
                                  "lastName": "Test",
                                  "dateOfBirth": "1995-01-01"
                                }
                                """, headers),
                        String.class
                );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Keyed on the real client's /64, not the proxy and not the forgery.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT ip_address FROM kyc_verifications", String.class
        )).isEqualTo("2001:db8:1:2::/64");
    }

    private HttpHeaders forwardedFor(String value) {

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", value);
        return headers;
    }

    private String loginSessionId(HttpHeaders headers) {

        ResponseEntity<String> response = login(headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        return JsonPath.read(response.getBody(), "$.sessionId");
    }

    private ResponseEntity<String> login(HttpHeaders headers) {

        headers.setContentType(MediaType.APPLICATION_JSON);

        return restTemplate.postForEntity(
                "/api/v1/auth/login",
                new HttpEntity<>("""
                        {
                            "email": "proxy@example.com",
                            "password": "Password123"
                        }
                        """, headers),
                String.class
        );
    }

    private String sessionIp(String sessionId) {

        return jdbcTemplate.queryForObject(
                "SELECT ip_address FROM login_sessions WHERE id = ?::uuid",
                String.class,
                sessionId
        );
    }

    private void register(String email, String phone) {

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response =
                restTemplate.postForEntity(
                        "/api/v1/customers",
                        new HttpEntity<>("""
                                {
                                    "firstName": "Esther",
                                    "lastName": "Test",
                                    "email": "%s",
                                    "countryCode": "NG",
                                    "phoneNumber": "%s",
                                    "password": "Password123"
                                }
                                """.formatted(email, phone), headers),
                        String.class
                );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
