package com.fintechplatform.paycore.security;

import com.fintechplatform.paycore.authorization.entity.Permission;
import com.fintechplatform.paycore.authorization.entity.Role;
import com.fintechplatform.paycore.authorization.entity.RoleName;
import com.fintechplatform.paycore.authorization.service.AuthorizationService;
import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.customer.enums.CustomerStatus;
import com.fintechplatform.paycore.identity.dto.SessionAccess;
import com.fintechplatform.paycore.identity.repository.LoginSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccessTokenServiceTest {

    private static final String SECRET =
            "test-secret-that-is-at-least-32-bytes-long!";

    private final JwtConfiguration jwtConfiguration =
            new JwtConfiguration(new MockEnvironment());

    private final LoginSessionRepository loginSessionRepository =
            mock(LoginSessionRepository.class);

    private final CurrentUserJwtAuthenticationConverter converter =
            new CurrentUserJwtAuthenticationConverter(loginSessionRepository);

    private final UUID sessionId = UUID.randomUUID();

    private AccessTokenService accessTokenService;
    private JwtDecoder jwtDecoder;
    private Customer customer;
    private UUID customerId;

    @BeforeEach
    void setUp() {

        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        properties.setAccessTokenExpiration(Duration.ofMinutes(15));

        SecretKey key = jwtConfiguration.jwtSigningKey(properties);

        accessTokenService =
                new AccessTokenService(
                        jwtConfiguration.jwtEncoder(key),
                        properties,
                        new AuthorizationService()
                );

        jwtDecoder = jwtConfiguration.jwtDecoder(key);

        Role role = new Role(RoleName.CUSTOMER);
        role.grant(new Permission("PROFILE_READ"));

        customer = Customer.create(
                "Esther",
                "Agboniro",
                "esther@example.com",
                "+2348012345678"
        );
        customer.assignRole(role);

        customerId = UUID.randomUUID();
        ReflectionTestUtils.setField(customer, "id", customerId);
    }

    @Test
    void shouldIssueShortLivedTokenWithAuthorizationClaimsOnly() {

        AccessToken token = accessTokenService.issue(customer, sessionId);

        Jwt jwt = jwtDecoder.decode(token.value());

        assertThat(jwt.getSubject())
                .isEqualTo(customerId.toString());

        assertThat(jwt.getClaimAsString("sid"))
                .isEqualTo(sessionId.toString());

        assertThat(jwt.getClaimAsStringList("roles"))
                .containsExactly("CUSTOMER");

        assertThat(jwt.getClaimAsStringList("permissions"))
                .containsExactly("PROFILE_READ");

        assertThat(jwt.getIssuedAt()).isNotNull();

        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()))
                .isEqualTo(Duration.ofMinutes(15));

        assertThat(jwt.getExpiresAt())
                .isEqualTo(token.expiresAt().truncatedTo(
                        java.time.temporal.ChronoUnit.SECONDS
                ));

        assertThat(jwt.getClaims().keySet())
                .containsExactlyInAnyOrder(
                        "sub", "sid", "roles", "permissions", "iat", "exp"
                );

        assertThat(token.value())
                .doesNotContain("esther@example.com")
                .doesNotContain("+2348012345678");
    }

    @Test
    void shouldConvertTokenIntoCurrentUserWithAuthorities() {

        Jwt jwt = issuedJwt();

        givenSession(new SessionAccess(
                customerId, CustomerStatus.ACTIVE, inOneHour(), null
        ));

        Authentication authentication = converter.convert(jwt);

        assertThat(authentication.getPrincipal())
                .isEqualTo(new CurrentUser(customerId));

        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_CUSTOMER", "PROFILE_READ");
    }

    @ParameterizedTest
    @EnumSource(value = CustomerStatus.class, names = {"SUSPENDED", "CLOSED"})
    void shouldRejectValidTokenOfCustomerWhoCannotAuthenticate(
            CustomerStatus status
    ) {

        Jwt jwt = issuedJwt();

        givenSession(new SessionAccess(
                customerId, status, inOneHour(), null
        ));

        assertThatThrownBy(() -> converter.convert(jwt))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void shouldRejectValidTokenOfRevokedSession() {

        Jwt jwt = issuedJwt();

        givenSession(new SessionAccess(
                customerId, CustomerStatus.ACTIVE, inOneHour(), Instant.now()
        ));

        assertThatThrownBy(() -> converter.convert(jwt))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void shouldRejectValidTokenOfExpiredSession() {

        Jwt jwt = issuedJwt();

        givenSession(new SessionAccess(
                customerId,
                CustomerStatus.ACTIVE,
                Instant.now().minusSeconds(1),
                null
        ));

        assertThatThrownBy(() -> converter.convert(jwt))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void shouldRejectValidTokenNamingAnotherCustomersSession() {

        Jwt jwt = issuedJwt();

        givenSession(new SessionAccess(
                UUID.randomUUID(), CustomerStatus.ACTIVE, inOneHour(), null
        ));

        assertThatThrownBy(() -> converter.convert(jwt))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void shouldRejectValidTokenOfUnknownSession() {

        Jwt jwt = issuedJwt();

        when(loginSessionRepository.findAccessById(sessionId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> converter.convert(jwt))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void shouldRejectValidTokenWithoutSession() {

        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(customerId.toString())
                .build();

        assertThatThrownBy(() -> converter.convert(jwt))
                .isInstanceOf(InvalidBearerTokenException.class)
                .hasMessage("Access token has no session");
    }

    private Jwt issuedJwt() {
        return jwtDecoder.decode(
                accessTokenService.issue(customer, sessionId).value()
        );
    }

    private void givenSession(SessionAccess access) {
        when(loginSessionRepository.findAccessById(sessionId))
                .thenReturn(Optional.of(access));
    }

    private static Instant inOneHour() {
        return Instant.now().plus(Duration.ofHours(1));
    }

    @Test
    void shouldRejectTokenSignedWithAnotherKey() {

        JwtProperties other = new JwtProperties();
        other.setSecret("another-secret-that-is-at-least-32-bytes!!");

        JwtDecoder otherDecoder =
                jwtConfiguration.jwtDecoder(
                        jwtConfiguration.jwtSigningKey(other)
                );

        String token = accessTokenService.issue(customer, sessionId).value();

        assertThatThrownBy(() -> otherDecoder.decode(token))
                .isInstanceOf(
                        org.springframework.security.oauth2.jwt.JwtException.class
                );
    }

    @Test
    void shouldRejectShortSigningSecret() {

        JwtProperties properties = new JwtProperties();
        properties.setSecret("too-short");

        assertThatThrownBy(() -> jwtConfiguration.jwtSigningKey(properties))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldRejectExpiredToken() {

        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);

        Instant issuedAt = Instant.now().minus(Duration.ofMinutes(30));

        String token =
                jwtConfiguration
                        .jwtEncoder(jwtConfiguration.jwtSigningKey(properties))
                        .encode(JwtEncoderParameters.from(
                                JwsHeader.with(MacAlgorithm.HS256).build(),
                                JwtClaimsSet.builder()
                                        .subject(customerId.toString())
                                        .issuedAt(issuedAt)
                                        .expiresAt(issuedAt.plus(
                                                Duration.ofMinutes(15)
                                        ))
                                        .build()
                        ))
                        .getTokenValue();

        assertThatThrownBy(() -> jwtDecoder.decode(token))
                .isInstanceOf(
                        org.springframework.security.oauth2.jwt.JwtException.class
                );
    }
}
