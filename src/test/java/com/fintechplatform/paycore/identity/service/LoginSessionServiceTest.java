package com.fintechplatform.paycore.identity.service;

import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.identity.config.SessionProperties;
import com.fintechplatform.paycore.identity.entity.LoginSession;
import com.fintechplatform.paycore.identity.exception.LoginSessionNotFoundException;
import com.fintechplatform.paycore.identity.repository.LoginSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoginSessionServiceTest {

    @Mock
    private LoginSessionRepository loginSessionRepository;

    @Mock
    private SessionProperties sessionProperties;

    @InjectMocks
    private LoginSessionService loginSessionService;

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
    void shouldCreateSession() {

        stubSessionCreation();

        LoginSession session =
                loginSessionService.createSession(
                        customer,
                        "hashed-session-token",
                        "127.0.0.1",
                        "Mozilla/5.0"
                );

        assertThat(session).isNotNull();

        assertThat(session.getCustomer())
                .isSameAs(customer);

        assertThat(session.getSessionTokenHash())
                .isEqualTo("hashed-session-token");

        assertThat(session.getIpAddress())
                .isEqualTo("127.0.0.1");

        assertThat(session.getUserAgent())
                .isEqualTo("Mozilla/5.0");

        assertThat(session.getCreatedAt())
                .isNotNull();

        assertThat(session.getExpiresAt())
                .isAfter(session.getCreatedAt());

        assertThat(session.getLastUsedAt())
                .isEqualTo(session.getCreatedAt());

        verify(sessionProperties)
                .getExpiration();

        verify(loginSessionRepository)
                .save(any(LoginSession.class));
    }

    @Test
    void shouldCalculateExpirationFromConfiguredDuration() {

        stubSessionCreation();

        Instant before = Instant.now();

        LoginSession session =
                loginSessionService.createSession(
                        customer,
                        "hashed-session-token",
                        "127.0.0.1",
                        "Mozilla/5.0"
                );

        Instant after = Instant.now();

        assertThat(session.getExpiresAt())
                .isBetween(
                        before.plus(Duration.ofDays(30)),
                        after.plus(Duration.ofDays(30))
                );
    }

    @Test
    void shouldFindSessionByTokenHash() {

        LoginSession session = newSession("hashed-session-token");

        when(loginSessionRepository.findBySessionTokenHash(
                "hashed-session-token"
        )).thenReturn(Optional.of(session));

        LoginSession result =
                loginSessionService.findBySessionTokenHash(
                        "hashed-session-token"
                );

        assertThat(result)
                .isSameAs(session);
    }

    @Test
    void shouldThrowWhenSessionDoesNotExist() {

        when(loginSessionRepository.findBySessionTokenHash(
                "unknown-token"
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                loginSessionService.findBySessionTokenHash(
                        "unknown-token"
                )
        )
                .isInstanceOf(
                        LoginSessionNotFoundException.class
                );
    }

    @Test
    void shouldRevokeSession() {

        LoginSession session = newSession("hashed-session-token");

        loginSessionService.revoke(session);

        assertThat(session.isRevoked())
                .isTrue();

        assertThat(session.getRevokedAt())
                .isNotNull();

        verify(loginSessionRepository)
                .save(session);
    }

    @Test
    void shouldRevokeAllCustomerSessions() {

        LoginSession firstSession = newSession("hash-1");
        LoginSession secondSession = newSession("hash-2");

        when(loginSessionRepository
                .findByCustomerAndRevokedAtIsNull(customer))
                .thenReturn(
                        List.of(
                                firstSession,
                                secondSession
                        )
                );

        loginSessionService.revokeAll(customer);

        assertThat(firstSession.isRevoked())
                .isTrue();

        assertThat(secondSession.isRevoked())
                .isTrue();

        verify(loginSessionRepository)
                .saveAll(
                        List.of(
                                firstSession,
                                secondSession
                        )
                );
    }

    @Test
    void shouldMarkSessionAsUsed() {

        LoginSession session = newSession("hashed-session-token");

        Instant previousLastUsedAt =
                session.getLastUsedAt();

        loginSessionService.markUsed(session);

        assertThat(session.getLastUsedAt())
                .isAfterOrEqualTo(previousLastUsedAt);

        verify(loginSessionRepository)
                .save(session);
    }

    private void stubSessionCreation() {

        when(sessionProperties.getExpiration())
                .thenReturn(Duration.ofDays(30));

        when(loginSessionRepository.save(
                any(LoginSession.class)
        )).thenAnswer(
                invocation -> invocation.getArgument(0)
        );
    }

    private LoginSession newSession(String tokenHash) {

        return LoginSession.create(
                customer,
                tokenHash,
                Instant.now().plus(Duration.ofDays(30)),
                "127.0.0.1",
                "Mozilla/5.0"
        );
    }
}
