package com.fintechplatform.paycore.identity.service;

import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
import com.fintechplatform.paycore.identity.dto.AuthenticationContext;
import com.fintechplatform.paycore.identity.dto.LoginRequest;
import com.fintechplatform.paycore.identity.dto.LoginResult;
import com.fintechplatform.paycore.identity.entity.Identity;
import com.fintechplatform.paycore.identity.entity.LoginSession;
import com.fintechplatform.paycore.identity.exception.IdentityDisabledException;
import com.fintechplatform.paycore.identity.exception.InvalidCredentialsException;
import com.fintechplatform.paycore.identity.exception.PasswordIdentityNotFoundException;
import com.fintechplatform.paycore.identity.dto.IssuedRefreshToken;
import com.fintechplatform.paycore.identity.dto.RefreshResult;
import com.fintechplatform.paycore.identity.entity.RefreshToken;
import com.fintechplatform.paycore.identity.exception.InvalidRefreshTokenException;
import com.fintechplatform.paycore.identity.exception.LoginSessionNotFoundException;
import com.fintechplatform.paycore.security.AccessToken;
import com.fintechplatform.paycore.security.AccessTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthenticationServiceTest {

    private static final AuthenticationContext CONTEXT =
            new AuthenticationContext(
                    "127.0.0.1",
                    "Mozilla/5.0"
            );

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private IdentityService identityService;

    @Mock
    private LoginSessionService loginSessionService;

    @Mock
    private SessionTokenService sessionTokenService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private AccessTokenService accessTokenService;

    @Mock
    private RefreshTokenService refreshTokenService;

    private AuthenticationService authenticationService;

    @BeforeEach
    void setUp() {
        authenticationService =
                new AuthenticationService(
                        customerRepository,
                        identityService,
                        loginSessionService,
                        sessionTokenService,
                        passwordEncoder,
                        accessTokenService,
                        refreshTokenService
                );
    }

    @Test
    void shouldLoginWithValidCredentials() {

        Customer customer = newCustomer();

        Identity identity = mock(Identity.class);

        LoginSession session = mock(LoginSession.class);

        when(customerRepository.findByEmail("esther@example.com"))
                .thenReturn(Optional.of(customer));

        when(identityService.findPasswordIdentity(customer))
                .thenReturn(identity);

        when(identity.isEnabled())
                .thenReturn(true);

        when(identity.getPasswordHash())
                .thenReturn("$2a$12$hashedPassword");

        when(passwordEncoder.matches(
                "Password123",
                "$2a$12$hashedPassword"
        )).thenReturn(true);

        when(sessionTokenService.generate())
                .thenReturn("raw-token");

        when(sessionTokenService.hash("raw-token"))
                .thenReturn("token-hash");

        when(loginSessionService.createSession(
                eq(customer),
                eq("token-hash"),
                eq("127.0.0.1"),
                eq("Mozilla/5.0")
        )).thenReturn(session);

        AccessToken accessToken =
                new AccessToken("jwt", Instant.now().plusSeconds(900));

        IssuedRefreshToken refreshToken =
                new IssuedRefreshToken(
                        "raw-refresh",
                        mock(RefreshToken.class)
                );

        when(accessTokenService.issue(customer))
                .thenReturn(accessToken);

        when(refreshTokenService.issue(customer, session))
                .thenReturn(refreshToken);

        LoginRequest request =
                new LoginRequest(
                        "esther@example.com",
                        "Password123"
                );

        AuthenticationContext context =
                new AuthenticationContext(
                        "127.0.0.1",
                        "Mozilla/5.0"
                );

        LoginResult result =
                authenticationService.login(
                        request,
                        context
                );

        assertThat(result.customer())
                .isSameAs(customer);

        assertThat(result.session())
                .isSameAs(session);

        assertThat(result.sessionToken())
                .isEqualTo("raw-token");

        assertThat(result.accessToken())
                .isSameAs(accessToken);

        assertThat(result.refreshToken())
                .isSameAs(refreshToken);

        verify(sessionTokenService)
                .generate();

        verify(sessionTokenService)
                .hash("raw-token");

        verify(loginSessionService)
                .createSession(
                        eq(customer),
                        eq("token-hash"),
                        eq("127.0.0.1"),
                        eq("Mozilla/5.0")
                );
    }

    @Test
    void shouldNormalizeEmailBeforeLookup() {

        when(customerRepository.findByEmail("esther@example.com"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                authenticationService.login(
                        new LoginRequest(
                                "  ESTHER@EXAMPLE.COM  ",
                                "Password123"
                        ),
                        CONTEXT
                )
        )
                .isInstanceOf(InvalidCredentialsException.class);

        verify(customerRepository)
                .findByEmail("esther@example.com");
    }

    @Test
    void shouldRejectUnknownEmail() {

        when(customerRepository.findByEmail("unknown@example.com"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                authenticationService.login(
                        new LoginRequest(
                                "unknown@example.com",
                                "Password123"
                        ),
                        CONTEXT
                )
        )
                .isInstanceOf(InvalidCredentialsException.class);

        verifyNoInteractions(identityService);
        verifyNoInteractions(passwordEncoder);
        verifyNoInteractions(sessionTokenService);
        verifyNoInteractions(loginSessionService);
    }

    @Test
    void shouldRejectMissingPasswordIdentity() {

        Customer customer = newCustomer();

        when(customerRepository.findByEmail("esther@example.com"))
                .thenReturn(Optional.of(customer));

        when(identityService.findPasswordIdentity(customer))
                .thenThrow(new PasswordIdentityNotFoundException());

        assertThatThrownBy(() ->
                authenticationService.login(
                        new LoginRequest(
                                "esther@example.com",
                                "Password123"
                        ),
                        CONTEXT
                )
        )
                .isInstanceOf(InvalidCredentialsException.class);

        verifyNoInteractions(passwordEncoder);
        verifyNoInteractions(sessionTokenService);
        verifyNoInteractions(loginSessionService);
    }

    @Test
    void shouldRejectDisabledIdentity() {

        Customer customer = newCustomer();

        Identity identity = mock(Identity.class);

        when(customerRepository.findByEmail("esther@example.com"))
                .thenReturn(Optional.of(customer));

        when(identityService.findPasswordIdentity(customer))
                .thenReturn(identity);

        when(identity.isEnabled())
                .thenReturn(false);

        assertThatThrownBy(() ->
                authenticationService.login(
                        new LoginRequest(
                                "esther@example.com",
                                "Password123"
                        ),
                        CONTEXT
                )
        )
                .isInstanceOf(IdentityDisabledException.class);

        verifyNoInteractions(passwordEncoder);
        verifyNoInteractions(sessionTokenService);
        verifyNoInteractions(loginSessionService);
    }

    @Test
    void shouldRejectIncorrectPassword() {

        Customer customer = newCustomer();

        stubValidIdentity(customer, "WrongPassword", false);

        assertThatThrownBy(() ->
                authenticationService.login(
                        new LoginRequest(
                                "esther@example.com",
                                "WrongPassword"
                        ),
                        CONTEXT
                )
        )
                .isInstanceOf(InvalidCredentialsException.class);

        verifyNoInteractions(sessionTokenService);
        verifyNoInteractions(loginSessionService);
    }

    @Test
    void shouldRejectSuspendedCustomer() {

        Customer customer = newCustomer();

        customer.verifyEmail();
        customer.activate();
        customer.suspend();

        stubValidIdentity(customer, "Password123", true);

        assertThatThrownBy(() ->
                authenticationService.login(
                        new LoginRequest(
                                "esther@example.com",
                                "Password123"
                        ),
                        CONTEXT
                )
        )
                .isInstanceOf(InvalidCredentialsException.class);

        verifyNoInteractions(sessionTokenService);
        verifyNoInteractions(loginSessionService);
    }

    @Test
    void shouldRejectClosedCustomer() {

        Customer customer = newCustomer();

        customer.close();

        stubValidIdentity(customer, "Password123", true);

        assertThatThrownBy(() ->
                authenticationService.login(
                        new LoginRequest(
                                "esther@example.com",
                                "Password123"
                        ),
                        CONTEXT
                )
        )
                .isInstanceOf(InvalidCredentialsException.class);

        verifyNoInteractions(sessionTokenService);
        verifyNoInteractions(loginSessionService);
    }

    @Test
    void shouldCreateLoginSession() {

        Customer customer = newCustomer();

        stubSuccessfulLogin(customer);

        authenticationService.login(
                new LoginRequest("esther@example.com", "Password123"),
                CONTEXT
        );

        verify(loginSessionService)
                .createSession(
                        customer,
                        "token-hash",
                        "127.0.0.1",
                        "Mozilla/5.0"
                );

        verify(refreshTokenService)
                .issue(eq(customer), any(LoginSession.class));
    }

    @Test
    void shouldNeverStoreRawSessionToken() {

        Customer customer = newCustomer();

        stubSuccessfulLogin(customer);

        authenticationService.login(
                new LoginRequest("esther@example.com", "Password123"),
                CONTEXT
        );

        ArgumentCaptor<String> storedHash =
                ArgumentCaptor.forClass(String.class);

        verify(loginSessionService)
                .createSession(
                        eq(customer),
                        storedHash.capture(),
                        anyString(),
                        anyString()
                );

        assertThat(storedHash.getValue())
                .isEqualTo("token-hash")
                .isNotEqualTo("raw-token");

        verify(loginSessionService, never())
                .createSession(
                        any(Customer.class),
                        eq("raw-token"),
                        anyString(),
                        anyString()
                );
    }

    // ============================================================
    // REFRESH
    // ============================================================

    @Test
    void shouldIssueNewAccessTokenOnRefresh() {

        Customer customer = newCustomer();

        RefreshToken rotated = mock(RefreshToken.class);
        when(rotated.getCustomer()).thenReturn(customer);

        IssuedRefreshToken issued =
                new IssuedRefreshToken("new-raw-refresh", rotated);

        AccessToken accessToken =
                new AccessToken("jwt", Instant.now().plusSeconds(900));

        when(refreshTokenService.rotate("old-raw-refresh"))
                .thenReturn(issued);

        when(accessTokenService.issue(customer))
                .thenReturn(accessToken);

        RefreshResult result =
                authenticationService.refresh("old-raw-refresh");

        assertThat(result.accessToken())
                .isSameAs(accessToken);

        assertThat(result.refreshToken())
                .isSameAs(issued);
    }

    @Test
    void shouldRejectRefreshForSuspendedCustomer() {

        Customer customer = newCustomer();
        customer.verifyEmail();
        customer.activate();
        customer.suspend();

        RefreshToken rotated = mock(RefreshToken.class);
        when(rotated.getCustomer()).thenReturn(customer);

        when(refreshTokenService.rotate("raw-refresh"))
                .thenReturn(new IssuedRefreshToken("new", rotated));

        assertThatThrownBy(() ->
                authenticationService.refresh("raw-refresh")
        )
                .isInstanceOf(InvalidRefreshTokenException.class);

        verifyNoInteractions(accessTokenService);
    }

    // ============================================================
    // LOGOUT
    // ============================================================

    @Test
    void shouldRevokeSessionAndRefreshTokensOnLogout() {

        LoginSession session = mock(LoginSession.class);

        when(sessionTokenService.hash("raw-token"))
                .thenReturn("token-hash");

        when(loginSessionService.findBySessionTokenHash("token-hash"))
                .thenReturn(session);

        authenticationService.logout("raw-token");

        verify(loginSessionService).revoke(session);
        verify(refreshTokenService).revokeAllForSession(session);
    }

    @Test
    void shouldIgnoreLogoutWithoutSessionToken() {

        authenticationService.logout(null);
        authenticationService.logout("  ");

        verifyNoInteractions(sessionTokenService);
        verifyNoInteractions(loginSessionService);
        verifyNoInteractions(refreshTokenService);
    }

    @Test
    void shouldRejectLogoutWithUnknownSessionToken() {

        when(sessionTokenService.hash("unknown"))
                .thenReturn("unknown-hash");

        when(loginSessionService.findBySessionTokenHash("unknown-hash"))
                .thenThrow(new LoginSessionNotFoundException());

        assertThatThrownBy(() ->
                authenticationService.logout("unknown")
        )
                .isInstanceOf(LoginSessionNotFoundException.class);

        verify(loginSessionService, never())
                .revoke(any(LoginSession.class));
    }

    @Test
    void shouldRevokeEverythingOnLogoutAll() {

        Customer customer = newCustomer();
        UUID customerId = UUID.randomUUID();

        when(customerRepository.findById(customerId))
                .thenReturn(Optional.of(customer));

        authenticationService.logoutAll(customerId);

        verify(loginSessionService).revokeAll(customer);
        verify(refreshTokenService).revokeAllForCustomer(customer);
    }

    private void stubSuccessfulLogin(Customer customer) {

        stubValidIdentity(customer, "Password123", true);

        when(sessionTokenService.generate())
                .thenReturn("raw-token");

        when(sessionTokenService.hash("raw-token"))
                .thenReturn("token-hash");

        when(loginSessionService.createSession(
                any(Customer.class),
                anyString(),
                anyString(),
                anyString()
        )).thenReturn(mock(LoginSession.class));
    }

    private Customer newCustomer() {

        return Customer.create(
                "Esther",
                "Agboniro",
                "esther@example.com",
                "+2348011111111"
        );
    }

    private void stubValidIdentity(
            Customer customer,
            String rawPassword,
            boolean passwordMatches
    ) {

        Identity identity = mock(Identity.class);

        when(customerRepository.findByEmail("esther@example.com"))
                .thenReturn(Optional.of(customer));

        when(identityService.findPasswordIdentity(customer))
                .thenReturn(identity);

        when(identity.isEnabled())
                .thenReturn(true);

        when(identity.getPasswordHash())
                .thenReturn("storedHash");

        when(passwordEncoder.matches(
                rawPassword,
                "storedHash"
        )).thenReturn(passwordMatches);
    }
}
