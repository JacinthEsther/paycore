package com.fintechplatform.paycore.common.config;

import com.jayway.jsonpath.JsonPath;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prod profile with the trusted-proxy list narrowed to a single load
 * balancer address (as SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES would do in
 * production). The test client on loopback is now an ordinary, untrusted
 * client, so its X-Forwarded-For must be ignored.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prod")
@TestPropertySource(properties = {
        "server.tomcat.remoteip.internal-proxies=10\\.1\\.2\\.3",
        "PAYCORE_JWT_SECRET=test-only-prod-profile-secret-0123456789abcdef"
})
class UntrustedPeerForwardedHeadersIntegrationTest {

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

    @Test
    void shouldIgnoreForwardedHeaderFromUntrustedPeer() {

        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);

        assertThat(restTemplate.postForEntity(
                "/api/v1/customers",
                new HttpEntity<>("""
                        {
                            "firstName": "Esther",
                            "lastName": "Test",
                            "email": "direct@example.com",
                            "countryCode": "NG",
                            "phoneNumber": "08011111181",
                            "password": "Password123"
                        }
                        """, json),
                String.class
        ).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", "203.0.113.9");

        ResponseEntity<String> login =
                restTemplate.postForEntity(
                        "/api/v1/auth/login",
                        new HttpEntity<>("""
                                {
                                    "email": "direct@example.com",
                                    "password": "Password123"
                                }
                                """, headers),
                        String.class
                );

        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);

        String sessionId = JsonPath.read(login.getBody(), "$.sessionId");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT ip_address FROM login_sessions WHERE id = ?::uuid",
                String.class,
                sessionId
        ))
                .isNotEqualTo("203.0.113.9")
                .isIn("127.0.0.1", "0:0:0:0:0:0:0:1");
    }
}
