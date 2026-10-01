package com.fintechplatform.paycore.identity.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.customer.enums.CustomerStatus;
import com.fintechplatform.paycore.identity.google.GoogleProperties;
import com.fintechplatform.paycore.identity.google.GoogleSignInService;
import com.fintechplatform.paycore.identity.config.IdentityConfiguration;
import com.fintechplatform.paycore.identity.dto.AuthenticationContext;
import com.fintechplatform.paycore.identity.dto.LoginRequest;
import com.fintechplatform.paycore.identity.dto.LoginResult;
import com.fintechplatform.paycore.identity.dto.IssuedRefreshToken;
import com.fintechplatform.paycore.identity.dto.RefreshResult;
import com.fintechplatform.paycore.identity.entity.LoginSession;
import com.fintechplatform.paycore.identity.entity.RefreshToken;
import com.fintechplatform.paycore.identity.exception.IdentityDisabledException;
import com.fintechplatform.paycore.identity.exception.InvalidCredentialsException;
import com.fintechplatform.paycore.identity.exception.LoginSessionNotFoundException;
import com.fintechplatform.paycore.identity.exception.RefreshTokenReuseException;
import com.fintechplatform.paycore.identity.service.AuthenticationService;
import com.fintechplatform.paycore.security.AccessToken;
import com.fintechplatform.paycore.security.CurrentUser;
import com.fintechplatform.paycore.identity.repository.LoginSessionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthenticationController.class)
@Import(IdentityConfiguration.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthenticationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthenticationService authenticationService;

    // Google sign-in is covered by GoogleSignInIntegrationTest.
    @MockitoBean
    private GoogleSignInService googleSignInService;

    @MockitoBean
    private GoogleProperties googleProperties;

    // Needed by CurrentUserJwtAuthenticationConverter, which this slice loads.
    @MockitoBean
    private LoginSessionRepository loginSessionRepository;

    @Test
    void shouldLoginSuccessfully() throws Exception {

        UUID customerId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Instant expiresAt = Instant.parse("2026-10-23T12:00:00Z");

        Customer customer = mock(Customer.class);
        when(customer.getId()).thenReturn(customerId);
        when(customer.getEmail()).thenReturn("esther@example.com");
        when(customer.getStatus()).thenReturn(CustomerStatus.PENDING_VERIFICATION);

        LoginSession session = mock(LoginSession.class);
        when(session.getId()).thenReturn(sessionId);
        when(session.getExpiresAt()).thenReturn(expiresAt);

        RefreshToken refreshToken = refreshToken(expiresAt);

        when(authenticationService.login(
                any(LoginRequest.class),
                any(AuthenticationContext.class)
        )).thenReturn(new LoginResult(
                customer,
                session,
                "raw-token",
                new AccessToken("access-jwt", expiresAt),
                new IssuedRefreshToken("raw-refresh", refreshToken)
        ));

        LoginRequest request =
                new LoginRequest(
                        "esther@example.com",
                        "Password123"
                );

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("User-Agent", "JUnit")
                                .with(httpRequest -> {
                                    httpRequest.setRemoteAddr("10.0.0.1");
                                    return httpRequest;
                                })
                                .content(objectMapper.writeValueAsString(request))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId")
                        .value(customerId.toString()))
                .andExpect(jsonPath("$.email")
                        .value("esther@example.com"))
                .andExpect(jsonPath("$.status")
                        .value("PENDING_VERIFICATION"))
                .andExpect(jsonPath("$.sessionId")
                        .value(sessionId.toString()))
                .andExpect(jsonPath("$.sessionToken")
                        .value("raw-token"))
                .andExpect(jsonPath("$.sessionExpiresAt")
                        .exists())
                .andExpect(jsonPath("$.tokenType")
                        .value("Bearer"))
                .andExpect(jsonPath("$.accessToken")
                        .value("access-jwt"))
                .andExpect(jsonPath("$.accessTokenExpiresAt")
                        .exists())
                .andExpect(jsonPath("$.refreshToken")
                        .value("raw-refresh"))
                .andExpect(jsonPath("$.refreshTokenExpiresAt")
                        .exists())
                .andExpect(jsonPath("$.message")
                        .value("Login successful"));

        ArgumentCaptor<AuthenticationContext> contextCaptor =
                ArgumentCaptor.forClass(AuthenticationContext.class);

        verify(authenticationService)
                .login(any(LoginRequest.class), contextCaptor.capture());

        assertThat(contextCaptor.getValue())
                .isEqualTo(new AuthenticationContext("10.0.0.1", "JUnit"));
    }

    @Test
    void shouldRejectInvalidRequest() throws Exception {

        LoginRequest request =
                new LoginRequest(
                        "",
                        "123"
                );

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturnUnauthorizedForInvalidCredentials()
            throws Exception {

        when(authenticationService.login(
                any(LoginRequest.class),
                any(AuthenticationContext.class)
        )).thenThrow(new InvalidCredentialsException());

        LoginRequest request =
                new LoginRequest(
                        "esther@example.com",
                        "WrongPassword"
                );

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status")
                        .value(401))
                .andExpect(jsonPath("$.error")
                        .value("INVALID_CREDENTIALS"));
    }

    @Test
    void shouldReturnUnauthorizedForDisabledIdentity()
            throws Exception {

        when(authenticationService.login(
                any(LoginRequest.class),
                any(AuthenticationContext.class)
        )).thenThrow(new IdentityDisabledException());

        LoginRequest request =
                new LoginRequest(
                        "esther@example.com",
                        "Password123"
                );

        mockMvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request))
                )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status")
                        .value(401))
                .andExpect(jsonPath("$.error")
                        .value("IDENTITY_DISABLED"));
    }

    @Test
    void shouldRefreshTokens() throws Exception {

        Instant expiresAt = Instant.parse("2026-10-23T12:00:00Z");

        RefreshToken refreshToken = refreshToken(expiresAt);

        when(authenticationService.refresh("old-refresh"))
                .thenReturn(new RefreshResult(
                        new AccessToken("new-access", expiresAt),
                        new IssuedRefreshToken(
                                "new-refresh",
                                refreshToken
                        )
                ));

        mockMvc.perform(
                        post("/api/v1/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        { "refreshToken": "old-refresh" }
                                        """)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessToken").value("new-access"))
                .andExpect(jsonPath("$.refreshToken").value("new-refresh"))
                .andExpect(jsonPath("$.refreshTokenExpiresAt").exists());
    }

    @Test
    void shouldRejectBlankRefreshToken() throws Exception {

        mockMvc.perform(
                        post("/api/v1/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        { "refreshToken": "" }
                                        """)
                )
                .andExpect(status().isBadRequest());

        verifyNoInteractions(authenticationService);
    }

    @Test
    void shouldReturnUnauthorizedWhenRefreshTokenIsReused()
            throws Exception {

        when(authenticationService.refresh("reused"))
                .thenThrow(new RefreshTokenReuseException());

        mockMvc.perform(
                        post("/api/v1/auth/refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        { "refreshToken": "reused" }
                                        """)
                )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error")
                        .value("REFRESH_TOKEN_REUSED"));
    }

    @Test
    void shouldLogout() throws Exception {

        mockMvc.perform(
                        post("/api/v1/auth/logout")
                                .header("X-Session-Token", "raw-token")
                )
                .andExpect(status().isNoContent());

        verify(authenticationService).logout("raw-token");
    }

    @Test
    void shouldReturnUnauthorizedForUnknownSessionOnLogout()
            throws Exception {

        doThrow(new LoginSessionNotFoundException())
                .when(authenticationService)
                .logout("unknown");

        mockMvc.perform(
                        post("/api/v1/auth/logout")
                                .header("X-Session-Token", "unknown")
                )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error")
                        .value("INVALID_SESSION"));
    }

    @Test
    void shouldLogoutAllSessions() throws Exception {

        UUID customerId = UUID.randomUUID();

        // Filters are disabled in this slice, so populate the context
        // that @AuthenticationPrincipal reads from directly.
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        new CurrentUser(customerId),
                        null,
                        List.of()
                )
        );

        try {
            mockMvc.perform(
                            post("/api/v1/auth/logout-all")
                    )
                    .andExpect(status().isNoContent());
        } finally {
            SecurityContextHolder.clearContext();
        }

        verify(authenticationService).logoutAll(customerId);
    }

    private RefreshToken refreshToken(Instant expiresAt) {

        RefreshToken refreshToken = mock(RefreshToken.class);
        when(refreshToken.getExpiresAt()).thenReturn(expiresAt);
        return refreshToken;
    }
}

