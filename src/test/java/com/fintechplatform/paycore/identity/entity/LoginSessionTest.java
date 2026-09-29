package com.fintechplatform.paycore.identity.entity;

import com.fintechplatform.paycore.customer.entity.Customer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginSessionTest {

    private Customer customer;

    @BeforeEach
    void setUp() {

        customer = Customer.create(
                "Esther",
                "Agboniro",
                "esther@example.com",
                "+2348012345678"
        );
    }

    @Test
    void shouldCreateActiveSession() {

        LoginSession session =
                sessionExpiringAt(
                        Instant.now().plus(Duration.ofDays(30))
                );

        assertThat(session.isRevoked())
                .isFalse();

        assertThat(session.isExpired())
                .isFalse();

        assertThat(session.isActive())
                .isTrue();
    }

    @Test
    void shouldExpireSession() {

        LoginSession session =
                sessionExpiringAt(
                        Instant.now().minus(Duration.ofSeconds(1))
                );

        assertThat(session.isExpired())
                .isTrue();

        assertThat(session.isActive())
                .isFalse();
    }

    @Test
    void shouldRevokeSession() {

        LoginSession session =
                sessionExpiringAt(
                        Instant.now().plus(Duration.ofDays(30))
                );

        session.revoke();

        assertThat(session.isRevoked())
                .isTrue();

        assertThat(session.getRevokedAt())
                .isNotNull();

        assertThat(session.isActive())
                .isFalse();
    }

    @Test
    void shouldNotChangeRevocationTimeWhenRevokedTwice() {

        LoginSession session =
                sessionExpiringAt(
                        Instant.now().plus(Duration.ofDays(30))
                );

        session.revoke();

        Instant firstRevokedAt =
                session.getRevokedAt();

        session.revoke();

        assertThat(session.getRevokedAt())
                .isEqualTo(firstRevokedAt);
    }

    @Test
    void shouldUpdateLastUsedAt() {

        LoginSession session =
                sessionExpiringAt(
                        Instant.now().plus(Duration.ofDays(30))
                );

        Instant original =
                session.getLastUsedAt();

        session.markUsed();

        assertThat(session.getLastUsedAt())
                .isAfterOrEqualTo(original);
    }

    @Test
    void shouldRejectInactiveSession() {

        LoginSession revoked =
                sessionExpiringAt(
                        Instant.now().plus(Duration.ofDays(30))
                );

        revoked.revoke();

        LoginSession expired =
                sessionExpiringAt(
                        Instant.now().minus(Duration.ofSeconds(1))
                );

        assertThatThrownBy(revoked::markUsed)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Cannot use inactive session");

        assertThatThrownBy(expired::markUsed)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Cannot use inactive session");
    }

    private LoginSession sessionExpiringAt(Instant expiresAt) {

        return LoginSession.create(
                customer,
                "hashed-token",
                expiresAt,
                "127.0.0.1",
                "Mozilla/5.0"
        );
    }
}
