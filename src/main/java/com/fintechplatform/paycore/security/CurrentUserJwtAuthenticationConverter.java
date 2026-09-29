package com.fintechplatform.paycore.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Turns a verified JWT into an authentication whose principal is
 * {@link CurrentUser}. Roles become ROLE_* authorities and permissions
 * become plain authorities, so both hasRole and hasAuthority work.
 */
@Component
public class CurrentUserJwtAuthenticationConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {

        CurrentUser currentUser =
                new CurrentUser(parseSubject(jwt.getSubject()));

        List<GrantedAuthority> authorities = new ArrayList<>();

        claimValues(jwt, AccessTokenService.ROLES_CLAIM)
                .forEach(role -> authorities.add(
                        new SimpleGrantedAuthority("ROLE_" + role)
                ));

        claimValues(jwt, AccessTokenService.PERMISSIONS_CLAIM)
                .forEach(permission -> authorities.add(
                        new SimpleGrantedAuthority(permission)
                ));

        return UsernamePasswordAuthenticationToken.authenticated(
                currentUser,
                jwt,
                authorities
        );
    }

    private UUID parseSubject(String subject) {

        if (subject == null) {
            throw new InvalidBearerTokenException(
                    "Access token has no subject"
            );
        }

        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException exception) {
            throw new InvalidBearerTokenException(
                    "Access token subject is not a valid customer id"
            );
        }
    }

    private List<String> claimValues(Jwt jwt, String claim) {

        List<String> values = jwt.getClaimAsStringList(claim);

        return values != null ? values : List.of();
    }
}
