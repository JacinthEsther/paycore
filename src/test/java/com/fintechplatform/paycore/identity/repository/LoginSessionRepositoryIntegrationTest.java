package com.fintechplatform.paycore.identity.repository;

import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
import com.fintechplatform.paycore.identity.entity.LoginSession;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
@Transactional
class LoginSessionRepositoryIntegrationTest {

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
    private LoginSessionRepository loginSessionRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private EntityManager entityManager;

    private Customer customer;

    @BeforeEach
    void setUp() {

        customer =
                customerRepository.saveAndFlush(
                        Customer.create(
                                "Esther",
                                "Agboniro",
                                "esther@example.com",
                                "+2348012345678"
                        )
                );
    }

    @Test
    void shouldSaveAndRetrieveSession() {

        LoginSession saved =
                loginSessionRepository.saveAndFlush(
                        newSession("hash-1", "Mozilla/5.0")
                );

        entityManager.clear();

        Optional<LoginSession> result =
                loginSessionRepository
                        .findBySessionTokenHash("hash-1");

        assertThat(result)
                .isPresent();

        assertThat(result.get().getId())
                .isEqualTo(saved.getId());

        assertThat(saved.getId().version())
                .isEqualTo(7);

        assertThat(result.get().getCustomer().getId())
                .isEqualTo(customer.getId());

        assertThat(result.get().getIpAddress())
                .isEqualTo("127.0.0.1");

        assertThat(result.get().getUserAgent())
                .isEqualTo("Mozilla/5.0");

        assertThat(result.get().getExpiresAt())
                .isNotNull();

        assertThat(result.get().isActive())
                .isTrue();
    }

    @Test
    void shouldEnforceUniqueSessionTokenHash() {

        loginSessionRepository.saveAndFlush(
                newSession("duplicate-hash", "Chrome")
        );

        assertThatThrownBy(() ->
                loginSessionRepository.saveAndFlush(
                        newSession("duplicate-hash", "Chrome")
                )
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                );
    }

    @Test
    void shouldFindOnlyActiveCustomerSessions() {

        LoginSession activeSession =
                newSession("active-hash", "Chrome");

        LoginSession revokedSession =
                newSession("revoked-hash", "Safari");

        revokedSession.revoke();

        loginSessionRepository.saveAndFlush(activeSession);
        loginSessionRepository.saveAndFlush(revokedSession);

        List<LoginSession> sessions =
                loginSessionRepository
                        .findByCustomerAndRevokedAtIsNull(
                                customer
                        );

        assertThat(sessions)
                .extracting(LoginSession::getId)
                .containsExactly(activeSession.getId());
    }

    private LoginSession newSession(
            String tokenHash,
            String userAgent
    ) {

        return LoginSession.create(
                customer,
                tokenHash,
                Instant.now().plus(Duration.ofDays(30)),
                "127.0.0.1",
                userAgent
        );
    }
}
