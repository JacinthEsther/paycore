package com.fintechplatform.paycore.identity.controller;

import com.fintechplatform.paycore.identity.config.SessionProperties;
import com.fintechplatform.paycore.identity.dto.AuthProvidersResponse;
import com.fintechplatform.paycore.identity.dto.AuthenticationContext;
import com.fintechplatform.paycore.identity.dto.GoogleSignInRequest;
import com.fintechplatform.paycore.identity.google.GoogleProperties;
import com.fintechplatform.paycore.identity.google.GoogleSignInService;
import com.fintechplatform.paycore.identity.dto.LoginRequest;
import com.fintechplatform.paycore.identity.dto.LoginResponse;
import com.fintechplatform.paycore.identity.dto.LoginResult;
import com.fintechplatform.paycore.identity.dto.RefreshResult;
import com.fintechplatform.paycore.identity.dto.RefreshTokenRequest;
import com.fintechplatform.paycore.identity.dto.TokenResponse;
import com.fintechplatform.paycore.identity.service.AuthenticationService;
import com.fintechplatform.paycore.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthenticationController {

    private static final String BEARER = "Bearer";

    private final AuthenticationService authenticationService;
    private final GoogleSignInService googleSignInService;
    private final GoogleProperties googleProperties;
    private final SessionProperties sessionProperties;

    public AuthenticationController(
            AuthenticationService authenticationService,
            GoogleSignInService googleSignInService,
            GoogleProperties googleProperties,
            SessionProperties sessionProperties
    ) {
        this.authenticationService = authenticationService;
        this.googleSignInService = googleSignInService;
        this.googleProperties = googleProperties;
        this.sessionProperties = sessionProperties;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest
    ) {

        AuthenticationContext context =
                new AuthenticationContext(
                        httpRequest.getRemoteAddr(),
                        httpRequest.getHeader("User-Agent")
                );

        LoginResult result =
                authenticationService.login(
                        request,
                        context
                );

        return ResponseEntity.ok(toLoginResponse(result));
    }

    /**
     * Sign in, or sign up, with a Google ID token. The same session and
     * tokens as a password login.
     */
    @PostMapping("/google")
    public ResponseEntity<LoginResponse> signInWithGoogle(
            @Valid @RequestBody GoogleSignInRequest request,
            HttpServletRequest httpRequest
    ) {

        AuthenticationContext context =
                new AuthenticationContext(
                        httpRequest.getRemoteAddr(),
                        httpRequest.getHeader("User-Agent")
                );

        return ResponseEntity.ok(
                toLoginResponse(googleSignInService.signIn(request.credential(), context))
        );
    }

    /**
     * Which sign-in methods the UI should offer.
     */
    @GetMapping("/providers")
    public AuthProvidersResponse providers() {

        return new AuthProvidersResponse(
                true,
                new AuthProvidersResponse.Google(
                        googleProperties.isEnabled(),
                        googleProperties.isEnabled() ? googleProperties.getClientId() : null
                )
        );
    }

    private LoginResponse toLoginResponse(LoginResult result) {

        return new LoginResponse(
                result.customer().getId(),
                result.customer().getEmail(),
                result.customer().getStatus(),
                result.session().getId(),
                result.sessionToken(),
                result.session().getExpiresAt(),
                sessionProperties.getIdleTimeout().toSeconds(),
                BEARER,
                result.accessToken().value(),
                result.accessToken().expiresAt(),
                result.refreshToken().rawToken(),
                result.refreshToken().refreshToken().getExpiresAt(),
                "Login successful"
        );
    }

    @PostMapping("/refresh")
    public ResponseEntity<TokenResponse> refresh(
            @Valid @RequestBody RefreshTokenRequest request
    ) {

        RefreshResult result =
                authenticationService.refresh(
                        request.refreshToken()
                );

        return ResponseEntity.ok(
                new TokenResponse(
                        BEARER,
                        result.accessToken().value(),
                        result.accessToken().expiresAt(),
                        result.refreshToken().rawToken(),
                        result.refreshToken().refreshToken().getExpiresAt()
                )
        );
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(
            @RequestHeader(
                    name = "X-Session-Token",
                    required = false
            )
            String sessionToken
    ) {

        authenticationService.logout(sessionToken);
    }

    @PostMapping("/logout-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logoutAll(
            @AuthenticationPrincipal
            CurrentUser currentUser
    ) {

        authenticationService.logoutAll(
                currentUser.customerId()
        );
    }
}
