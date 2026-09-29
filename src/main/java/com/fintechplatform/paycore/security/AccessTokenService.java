package com.fintechplatform.paycore.security;

import com.fintechplatform.paycore.authorization.service.AuthorizationService;
import com.fintechplatform.paycore.customer.entity.Customer;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Issues short-lived access tokens. Claims are limited to what
 * authorization needs: subject, roles, permissions, iat and exp.
 * Never add profile, contact, financial or KYC data here.
 */
@Service
public class AccessTokenService {

    public static final String ROLES_CLAIM = "roles";
    public static final String PERMISSIONS_CLAIM = "permissions";

    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;
    private final AuthorizationService authorizationService;

    public AccessTokenService(
            JwtEncoder jwtEncoder,
            JwtProperties jwtProperties,
            AuthorizationService authorizationService
    ) {
        this.jwtEncoder = jwtEncoder;
        this.jwtProperties = jwtProperties;
        this.authorizationService = authorizationService;
    }

    public AccessToken issue(Customer customer) {

        Instant issuedAt = Instant.now();

        Instant expiresAt =
                issuedAt.plus(
                        jwtProperties.getAccessTokenExpiration()
                );

        JwtClaimsSet claims =
                JwtClaimsSet.builder()
                        .subject(customer.getId().toString())
                        .claim(
                                ROLES_CLAIM,
                                List.copyOf(
                                        authorizationService
                                                .roleNames(customer)
                                )
                        )
                        .claim(
                                PERMISSIONS_CLAIM,
                                List.copyOf(
                                        authorizationService
                                                .permissionNames(customer)
                                )
                        )
                        .issuedAt(issuedAt)
                        .expiresAt(expiresAt)
                        .build();

        JwsHeader header =
                JwsHeader.with(MacAlgorithm.HS256).build();

        String value =
                jwtEncoder
                        .encode(JwtEncoderParameters.from(header, claims))
                        .getTokenValue();

        return new AccessToken(value, expiresAt);
    }
}
