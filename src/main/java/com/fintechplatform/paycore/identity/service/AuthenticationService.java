package com.fintechplatform.paycore.identity.service;

import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.customer.exception.CustomerNotFoundException;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
import com.fintechplatform.paycore.identity.dto.AuthenticationContext;
import com.fintechplatform.paycore.identity.dto.IssuedRefreshToken;
import com.fintechplatform.paycore.identity.dto.LoginRequest;
import com.fintechplatform.paycore.identity.dto.LoginResult;
import com.fintechplatform.paycore.identity.dto.RefreshResult;
import com.fintechplatform.paycore.identity.entity.Identity;
import com.fintechplatform.paycore.identity.exception.InvalidCredentialsException;
import com.fintechplatform.paycore.identity.exception.IdentityDisabledException;
import com.fintechplatform.paycore.identity.entity.LoginSession;
import com.fintechplatform.paycore.identity.exception.InvalidRefreshTokenException;
import com.fintechplatform.paycore.identity.exception.PasswordIdentityNotFoundException;
import com.fintechplatform.paycore.identity.exception.RefreshTokenReuseException;
import com.fintechplatform.paycore.security.AccessToken;
import com.fintechplatform.paycore.security.AccessTokenService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;
import java.util.UUID;

@Service
public class AuthenticationService {

    private final CustomerRepository customerRepository;
    private final IdentityService identityService;
    private final LoginSessionService loginSessionService;
    private final SessionTokenService sessionTokenService;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenService accessTokenService;
    private final RefreshTokenService refreshTokenService;
    private final TransactionTemplate transactionTemplate;

    public AuthenticationService(
            CustomerRepository customerRepository,
            IdentityService identityService,
            LoginSessionService loginSessionService,
            SessionTokenService sessionTokenService,
            PasswordEncoder passwordEncoder,
            AccessTokenService accessTokenService,
            RefreshTokenService refreshTokenService,
            TransactionTemplate transactionTemplate
    ) {
        this.transactionTemplate = transactionTemplate;
        this.customerRepository = customerRepository;
        this.identityService = identityService;
        this.loginSessionService = loginSessionService;
        this.sessionTokenService = sessionTokenService;
        this.passwordEncoder = passwordEncoder;
        this.accessTokenService = accessTokenService;
        this.refreshTokenService = refreshTokenService;
    }

    /**
     * Built for heavy login traffic:
     *
     * - The customer is read together with their roles and permissions in
     *   one query, so issuing the access token needs no further reads.
     * - The BCrypt check runs outside any transaction. It is deliberately
     *   slow CPU work; inside a transaction it would keep a pooled
     *   database connection idle for the whole comparison.
     * - Only the writes (session and refresh token) run in a transaction,
     *   and only once the password is known to be right.
     *
     * Not @Transactional: the transaction is opened explicitly after the
     * password check.
     */
    public LoginResult login(
            LoginRequest request,
            AuthenticationContext context
    ) {

        String email =
                normalizeEmail(request.email());

        Customer customer =
                customerRepository
                        .findWithAuthoritiesByEmail(email)
                        .orElseThrow(
                                InvalidCredentialsException::new
                        );

        Identity identity;

        try {

            identity =
                    identityService
                            .findPasswordIdentity(customer);

        } catch (PasswordIdentityNotFoundException exception) {

            throw new InvalidCredentialsException();
        }

        if (!identity.isEnabled()) {
            throw new IdentityDisabledException();
        }

        if (!passwordEncoder.matches(
                request.password(),
                identity.getPasswordHash()
        )) {
            throw new InvalidCredentialsException();
        }

        validateCustomerCanLogin(customer);

        return transactionTemplate.execute(status ->
                startSession(customer, context)
        );
    }

    private LoginResult startSession(
            Customer customer,
            AuthenticationContext context
    ) {

        String sessionToken =
                sessionTokenService.generate();

        String sessionTokenHash =
                sessionTokenService.hash(sessionToken);

        LoginSession session =
                loginSessionService.createSession(
                        customer,
                        sessionTokenHash,
                        context.ipAddress(),
                        context.userAgent()
                );

        AccessToken accessToken =
                accessTokenService.issue(customer, session.getId());

        IssuedRefreshToken refreshToken =
                refreshTokenService.issue(customer, session);

        return new LoginResult(
                customer,
                session,
                sessionToken,
                accessToken,
                refreshToken
        );
    }

    @Transactional(noRollbackFor = RefreshTokenReuseException.class)
    public RefreshResult refresh(String rawRefreshToken) {

        IssuedRefreshToken refreshToken =
                refreshTokenService.rotate(rawRefreshToken);

        // Refresh runs every few minutes for every active user, so the
        // customer, roles and permissions come back in one query rather
        // than one per role.
        Customer customer =
                customerRepository
                        .findWithAuthoritiesById(
                                refreshToken.refreshToken().getCustomer().getId()
                        )
                        .orElseThrow(InvalidRefreshTokenException::new);

        if (!canLogin(customer)) {
            // Rolls back the rotation; the customer simply cannot refresh.
            throw new InvalidRefreshTokenException();
        }

        return new RefreshResult(
                accessTokenService.issue(
                        customer,
                        refreshToken.refreshToken().getSession().getId()
                ),
                refreshToken
        );
    }

    /**
     * Revokes the session identified by the raw session token together
     * with its refresh tokens. A missing token is a no-op so logout stays
     * idempotent for clients that already discarded it.
     */
    @Transactional
    public void logout(String rawToken) {

        if (rawToken == null || rawToken.isBlank()) {
            return;
        }

        String hash =
                sessionTokenService.hash(rawToken);

        LoginSession session =
                loginSessionService
                        .findBySessionTokenHash(hash);

        loginSessionService.revoke(session);

        refreshTokenService.revokeAllForSession(session);
    }

    @Transactional
    public void logoutAll(UUID customerId) {

        Customer customer =
                customerRepository
                        .findById(customerId)
                        .orElseThrow(() ->
                                new CustomerNotFoundException(customerId)
                        );

        loginSessionService.revokeAll(customer);

        refreshTokenService.revokeAllForCustomer(customer);
    }

    private void validateCustomerCanLogin(
            Customer customer
    ) {

        if (!canLogin(customer)) {

            throw new InvalidCredentialsException();
        }
    }

    private boolean canLogin(Customer customer) {

        return customer.getStatus().canAuthenticate();
    }

    private String normalizeEmail(String email) {

        return email
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
