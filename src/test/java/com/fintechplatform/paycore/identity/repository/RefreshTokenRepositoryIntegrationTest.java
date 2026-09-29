package com.fintechplatform.paycore.identity.repository;

import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
import com.fintechplatform.paycore.identity.entity.LoginSession;
import com.fintechplatform.paycore.identity.entity.RefreshToken;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
@Transactional
class RefreshTokenRepositoryIntegrationTest {

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
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private LoginSessionRepository loginSessionRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private EntityManager entityManager;

    private Customer customer;
    private LoginSession session;

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

        session =
                loginSessionRepository.saveAndFlush(
                        LoginSession.create(
                                customer,
                                "session-hash",
                                Instant.now().plus(Duration.ofDays(30)),
                                "127.0.0.1",
                                "Mozilla/5.0"
                        )
                );
    }

    @Test
    void shouldSaveAndFindByTokenHash() {

        UUID familyId = UUID.randomUUID();

        RefreshToken saved =
                refreshTokenRepository.saveAndFlush(
                        newToken("hash-1", familyId)
                );

        entityManager.clear();

        Optional<RefreshToken> result =
                refreshTokenRepository.findByTokenHash("hash-1");

        assertThat(result).isPresent();

        assertThat(saved.getId().version())
                .isEqualTo(7);

        assertThat(result.get().getId())
                .isEqualTo(saved.getId());

        assertThat(result.get().getFamilyId())
                .isEqualTo(familyId);

        assertThat(result.get().getCustomer().getId())
                .isEqualTo(customer.getId());

        assertThat(result.get().getSession().getId())
                .isEqualTo(session.getId());

        assertThat(result.get().isActive())
                .isTrue();
    }

    @Test
    void shouldEnforceUniqueTokenHash() {

        refreshTokenRepository.saveAndFlush(
                newToken("duplicate", UUID.randomUUID())
        );

        assertThatThrownBy(() ->
                refreshTokenRepository.saveAndFlush(
                        newToken("duplicate", UUID.randomUUID())
                )
        )
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldFindOnlyActiveTokensInFamily() {

        UUID familyId = UUID.randomUUID();

        RefreshToken rotated = newToken("rotated", familyId);
        RefreshToken current = newToken("current", familyId);
        RefreshToken otherFamily = newToken("other", UUID.randomUUID());

        refreshTokenRepository.saveAndFlush(current);
        rotated.replaceWith(current);
        refreshTokenRepository.saveAndFlush(rotated);
        refreshTokenRepository.saveAndFlush(otherFamily);

        assertThat(
                refreshTokenRepository
                        .findByFamilyIdAndRevokedAtIsNull(familyId)
        )
                .extracting(RefreshToken::getId)
                .containsExactly(current.getId());

        assertThat(rotated.getReplacedBy())
                .isEqualTo(current.getId());
    }

    @Test
    void shouldFindActiveTokensBySessionAndCustomer() {

        RefreshToken active = newToken("active", UUID.randomUUID());
        RefreshToken revoked = newToken("revoked", UUID.randomUUID());
        revoked.revoke();

        refreshTokenRepository.saveAndFlush(active);
        refreshTokenRepository.saveAndFlush(revoked);

        assertThat(refreshTokenRepository.findBySessionAndRevokedAtIsNull(session))
                .extracting(RefreshToken::getId)
                .containsExactly(active.getId());

        assertThat(refreshTokenRepository.findByCustomerAndRevokedAtIsNull(customer))
                .extracting(RefreshToken::getId)
                .containsExactly(active.getId());
    }

    private RefreshToken newToken(String hash, UUID familyId) {

        return RefreshToken.create(
                customer,
                session,
                hash,
                familyId,
                Instant.now().plus(Duration.ofDays(30))
        );
    }
}
