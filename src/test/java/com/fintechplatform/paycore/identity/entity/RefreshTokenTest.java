package com.fintechplatform.paycore.identity.entity;

import com.fintechplatform.paycore.customer.entity.Customer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefreshTokenTest {

    private Customer customer;
    private LoginSession session;

    @BeforeEach
    void setUp() {

        customer = Customer.create(
                "Esther",
                "Agboniro",
                "esther@example.com",
                "+2348012345678"
        );

        session = LoginSession.create(
                customer,
                "session-hash",
                Instant.now().plus(Duration.ofDays(30)),
                "127.0.0.1",
                "Mozilla/5.0"
        );
    }

    @Test
    void shouldCreateActiveToken() {

        UUID familyId = UUID.randomUUID();

        RefreshToken token =
                tokenExpiringAt(
                        familyId,
                        Instant.now().plus(Duration.ofDays(30))
                );

        assertThat(token.getCustomer()).isSameAs(customer);
        assertThat(token.getSession()).isSameAs(session);
        assertThat(token.getFamilyId()).isEqualTo(familyId);
        assertThat(token.getCreatedAt()).isNotNull();
        assertThat(token.getReplacedBy()).isNull();
        assertThat(token.isActive()).isTrue();
    }

    @Test
    void shouldExpireToken() {

        RefreshToken token =
                tokenExpiringAt(
                        UUID.randomUUID(),
                        Instant.now().minus(Duration.ofSeconds(1))
                );

        assertThat(token.isExpired()).isTrue();
        assertThat(token.isActive()).isFalse();
    }

    @Test
    void shouldNotChangeRevocationTimeWhenRevokedTwice() {

        RefreshToken token = activeToken();

        token.revoke();

        Instant firstRevokedAt = token.getRevokedAt();

        token.revoke();

        assertThat(token.getRevokedAt())
                .isEqualTo(firstRevokedAt);
    }

    @Test
    void shouldRecordReplacementWhenRotated() {

        RefreshToken current = activeToken();
        RefreshToken replacement = activeToken();

        UUID replacementId = UUID.randomUUID();
        ReflectionTestUtils.setField(replacement, "id", replacementId);

        current.replaceWith(replacement);

        assertThat(current.isRevoked()).isTrue();
        assertThat(current.getReplacedBy()).isEqualTo(replacementId);
    }

    @Test
    void shouldNotRotateInactiveToken() {

        RefreshToken current = activeToken();
        current.revoke();

        assertThatThrownBy(() -> current.replaceWith(activeToken()))
                .isInstanceOf(IllegalStateException.class);
    }

    private RefreshToken activeToken() {

        return tokenExpiringAt(
                UUID.randomUUID(),
                Instant.now().plus(Duration.ofDays(30))
        );
    }

    private RefreshToken tokenExpiringAt(UUID familyId, Instant expiresAt) {

        return RefreshToken.create(
                customer,
                session,
                "token-hash-" + UUID.randomUUID(),
                familyId,
                expiresAt
        );
    }
}
