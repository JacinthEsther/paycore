package com.fintechplatform.paycore.identity.service;

import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.identity.dto.IssuedRefreshToken;
import com.fintechplatform.paycore.identity.entity.LoginSession;
import com.fintechplatform.paycore.identity.entity.RefreshToken;
import com.fintechplatform.paycore.identity.exception.InvalidRefreshTokenException;
import com.fintechplatform.paycore.identity.exception.RefreshTokenReuseException;
import com.fintechplatform.paycore.identity.repository.RefreshTokenRepository;
import com.fintechplatform.paycore.security.JwtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private LoginSessionService loginSessionService;

    private final SessionTokenService sessionTokenService =
            new SessionTokenService();

    private RefreshTokenService refreshTokenService;

    private Customer customer;
    private LoginSession session;

    @BeforeEach
    void setUp() {

        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setRefreshTokenExpiration(Duration.ofDays(30));

        refreshTokenService =
                new RefreshTokenService(
                        refreshTokenRepository,
                        sessionTokenService,
                        loginSessionService,
                        jwtProperties
                );

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
    void shouldIssueHashedTokenInNewFamily() {

        stubSaveAssigningId();

        IssuedRefreshToken issued =
                refreshTokenService.issue(customer, session);

        assertThat(issued.rawToken()).isNotBlank();

        assertThat(issued.refreshToken().getTokenHash())
                .isEqualTo(sessionTokenService.hash(issued.rawToken()))
                .isNotEqualTo(issued.rawToken());

        assertThat(issued.refreshToken().getFamilyId()).isNotNull();

        assertThat(issued.refreshToken().getExpiresAt())
                .isAfter(Instant.now().plus(Duration.ofDays(29)));
    }

    @Test
    void shouldRotateRefreshToken() {

        stubSaveAssigningId();

        IssuedRefreshToken original =
                refreshTokenService.issue(customer, session);

        stubLookup(original);

        IssuedRefreshToken rotated =
                refreshTokenService.rotate(original.rawToken());

        assertThat(rotated.rawToken())
                .isNotEqualTo(original.rawToken());

        assertThat(rotated.refreshToken().getFamilyId())
                .isEqualTo(original.refreshToken().getFamilyId());

        assertThat(rotated.refreshToken().isActive()).isTrue();

        verify(loginSessionService).markUsed(session);
    }

    @Test
    void shouldRevokeOldRefreshToken() {

        stubSaveAssigningId();

        IssuedRefreshToken original =
                refreshTokenService.issue(customer, session);

        stubLookup(original);

        IssuedRefreshToken rotated =
                refreshTokenService.rotate(original.rawToken());

        RefreshToken old = original.refreshToken();

        assertThat(old.isRevoked()).isTrue();

        assertThat(old.getReplacedBy())
                .isEqualTo(rotated.refreshToken().getId());
    }

    @Test
    void shouldRejectExpiredRefreshToken() {

        RefreshToken expired =
                RefreshToken.create(
                        customer,
                        session,
                        sessionTokenService.hash("expired-raw"),
                        UUID.randomUUID(),
                        Instant.now().minusSeconds(1)
                );

        when(refreshTokenRepository.findByTokenHash(expired.getTokenHash()))
                .thenReturn(Optional.of(expired));

        assertThatThrownBy(() ->
                refreshTokenService.rotate("expired-raw")
        )
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(refreshTokenRepository, never())
                .save(any(RefreshToken.class));
    }

    @Test
    void shouldRejectUnknownRefreshToken() {

        when(refreshTokenRepository.findByTokenHash(any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                refreshTokenService.rotate("unknown")
        )
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void shouldRejectBlankRefreshToken() {

        assertThatThrownBy(() -> refreshTokenService.rotate(" "))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verifyNoInteractions(refreshTokenRepository);
    }

    @Test
    void shouldRejectRefreshTokenOfRevokedSession() {

        stubSaveAssigningId();

        IssuedRefreshToken original =
                refreshTokenService.issue(customer, session);

        session.revoke();

        stubLookup(original);

        assertThatThrownBy(() ->
                refreshTokenService.rotate(original.rawToken())
        )
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(loginSessionService, never()).markUsed(any());
    }

    @Test
    void shouldDetectRefreshTokenReuse() {

        stubSaveAssigningId();

        IssuedRefreshToken original =
                refreshTokenService.issue(customer, session);

        stubLookup(original);

        refreshTokenService.rotate(original.rawToken());

        assertThatThrownBy(() ->
                refreshTokenService.rotate(original.rawToken())
        )
                .isInstanceOf(RefreshTokenReuseException.class);
    }

    @Test
    void shouldRevokeTokenFamilyWhenReuseIsDetected() {

        stubSaveAssigningId();

        IssuedRefreshToken original =
                refreshTokenService.issue(customer, session);

        stubLookup(original);

        IssuedRefreshToken rotated =
                refreshTokenService.rotate(original.rawToken());

        UUID familyId = original.refreshToken().getFamilyId();

        when(refreshTokenRepository
                .findByFamilyIdAndRevokedAtIsNull(familyId))
                .thenReturn(List.of(rotated.refreshToken()));

        assertThatThrownBy(() ->
                refreshTokenService.rotate(original.rawToken())
        )
                .isInstanceOf(RefreshTokenReuseException.class);

        assertThat(rotated.refreshToken().isRevoked())
                .isTrue();

        verify(loginSessionService).revoke(session);
    }

    @Test
    void shouldTreatLoggedOutTokenAsInvalidNotReuse() {

        RefreshToken loggedOut = newToken();
        loggedOut.revoke();

        when(refreshTokenRepository.findByTokenHash(any()))
                .thenReturn(Optional.of(loggedOut));

        assertThatThrownBy(() ->
                refreshTokenService.rotate("logged-out-raw")
        )
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(loginSessionService, never()).revoke(any());
        verify(refreshTokenRepository, never())
                .findByFamilyIdAndRevokedAtIsNull(any());
    }

    @Test
    void shouldRevokeAllTokensForSession() {

        RefreshToken first = newToken();
        RefreshToken second = newToken();

        when(refreshTokenRepository.findBySessionAndRevokedAtIsNull(session))
                .thenReturn(List.of(first, second));

        refreshTokenService.revokeAllForSession(session);

        assertThat(first.isRevoked()).isTrue();
        assertThat(second.isRevoked()).isTrue();

        verify(refreshTokenRepository).saveAll(List.of(first, second));
    }

    @Test
    void shouldRevokeAllTokensForCustomer() {

        RefreshToken token = newToken();

        when(refreshTokenRepository.findByCustomerAndRevokedAtIsNull(customer))
                .thenReturn(List.of(token));

        refreshTokenService.revokeAllForCustomer(customer);

        assertThat(token.isRevoked()).isTrue();
    }

    private RefreshToken newToken() {

        return RefreshToken.create(
                customer,
                session,
                "hash-" + UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now().plus(Duration.ofDays(30))
        );
    }

    /**
     * Mimics persist: the UUIDv7 generator assigns the id on save.
     */
    private void stubSaveAssigningId() {

        when(refreshTokenRepository.save(any(RefreshToken.class)))
                .thenAnswer(invocation -> {
                    RefreshToken token = invocation.getArgument(0);
                    if (token.getId() == null) {
                        ReflectionTestUtils.setField(
                                token,
                                "id",
                                UUID.randomUUID()
                        );
                    }
                    return token;
                });
    }

    private void stubLookup(IssuedRefreshToken issued) {

        when(refreshTokenRepository.findByTokenHash(
                issued.refreshToken().getTokenHash()
        )).thenReturn(Optional.of(issued.refreshToken()));
    }
}
