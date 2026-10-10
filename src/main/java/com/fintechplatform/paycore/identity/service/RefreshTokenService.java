package com.fintechplatform.paycore.identity.service;

import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.identity.dto.IssuedRefreshToken;
import com.fintechplatform.paycore.identity.entity.LoginSession;
import com.fintechplatform.paycore.identity.entity.RefreshToken;
import com.fintechplatform.paycore.identity.exception.InvalidRefreshTokenException;
import com.fintechplatform.paycore.identity.exception.RefreshTokenReuseException;
import com.fintechplatform.paycore.identity.repository.RefreshTokenRepository;
import com.fintechplatform.paycore.security.JwtProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final SessionTokenService sessionTokenService;
    private final LoginSessionService loginSessionService;
    private final JwtProperties jwtProperties;

    public RefreshTokenService(
            RefreshTokenRepository refreshTokenRepository,
            SessionTokenService sessionTokenService,
            LoginSessionService loginSessionService,
            JwtProperties jwtProperties
    ) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.sessionTokenService = sessionTokenService;
        this.loginSessionService = loginSessionService;
        this.jwtProperties = jwtProperties;
    }

    @Transactional
    public IssuedRefreshToken issue(
            Customer customer,
            LoginSession session
    ) {
        return issueInFamily(
                customer,
                session,
                UUID.randomUUID()
        );
    }

    @Transactional(noRollbackFor = RefreshTokenReuseException.class)
    public IssuedRefreshToken rotate(String rawToken) {

        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidRefreshTokenException();
        }

        RefreshToken current =
                refreshTokenRepository
                        .findByTokenHash(sessionTokenService.hash(rawToken))
                        .orElseThrow(InvalidRefreshTokenException::new);

        if (current.isRevoked()) {

            if (current.getReplacedBy() != null) {
                revokeFamily(current.getFamilyId());
                loginSessionService.revoke(current.getSession());
                throw new RefreshTokenReuseException();
            }

            throw new InvalidRefreshTokenException();
        }

        LoginSession session = current.getSession();

        if (!session.isRevoked()) {
            loginSessionService.ensureNotTimedOut(session);
        }

        if (current.isExpired()) {
            throw new InvalidRefreshTokenException();
        }

        if (!session.isActive()) {
            current.revoke();
            refreshTokenRepository.save(current);
            throw new InvalidRefreshTokenException();
        }

        IssuedRefreshToken replacement =
                issueInFamily(
                        current.getCustomer(),
                        session,
                        current.getFamilyId()
                );

        current.replaceWith(replacement.refreshToken());
        refreshTokenRepository.save(current);

        loginSessionService.markUsed(session);

        return replacement;
    }

    @Transactional
    public void revokeFamily(UUID familyId) {
        revokeAll(
                refreshTokenRepository
                        .findByFamilyIdAndRevokedAtIsNull(familyId)
        );
    }

    @Transactional
    public void revokeAllForSession(LoginSession session) {
        revokeAll(
                refreshTokenRepository
                        .findBySessionAndRevokedAtIsNull(session)
        );
    }

    @Transactional
    public void revokeAllForCustomer(Customer customer) {
        revokeAll(
                refreshTokenRepository
                        .findByCustomerAndRevokedAtIsNull(customer)
        );
    }

    private IssuedRefreshToken issueInFamily(
            Customer customer,
            LoginSession session,
            UUID familyId
    ) {
        String rawToken = sessionTokenService.generate();

        Instant expiresAt =
                Instant.now().plus(jwtProperties.getRefreshTokenExpiration());

        if (session.getExpiresAt() != null
                && session.getExpiresAt().isBefore(expiresAt)) {
            expiresAt = session.getExpiresAt();
        }

        RefreshToken token =
                RefreshToken.create(
                        customer,
                        session,
                        sessionTokenService.hash(rawToken),
                        familyId,
                        expiresAt
                );

        return new IssuedRefreshToken(
                rawToken,
                refreshTokenRepository.save(token)
        );
    }

    private void revokeAll(List<RefreshToken> tokens) {

        tokens.forEach(RefreshToken::revoke);

        refreshTokenRepository.saveAll(tokens);
    }
}
