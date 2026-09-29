package com.fintechplatform.paycore.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfiguration {

    private static final int MIN_SECRET_BYTES = 32;

    /**
     * Prefix of the development-only fallback secret in
     * application.properties. It is public in the repository, so it must
     * never sign production tokens.
     */
    static final String DEV_SECRET_PREFIX = "local-dev-only";

    private final Environment environment;

    public JwtConfiguration(Environment environment) {
        this.environment = environment;
    }

    @Bean
    SecretKey jwtSigningKey(JwtProperties properties) {

        String secret = properties.getSecret();

        // @ConfigurationProperties binding leaves an unresolvable
        // placeholder such as ${PAYCORE_JWT_SECRET} as literal text instead
        // of failing, so a missing environment variable must be caught here.
        if (secret != null && secret.startsWith("${") && secret.endsWith("}")) {

            String variable =
                    secret.substring(2, secret.length() - 1).split(":", 2)[0];

            throw new IllegalStateException(
                    "paycore.jwt.secret is not configured: set the "
                            + variable + " environment variable"
            );
        }

        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "paycore.jwt.secret is empty: set PAYCORE_JWT_SECRET"
            );
        }

        if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {

            throw new IllegalStateException(
                    "paycore.jwt.secret must be at least "
                            + MIN_SECRET_BYTES + " bytes"
            );
        }

        if (environment.acceptsProfiles(Profiles.of("prod"))
                && secret.startsWith(DEV_SECRET_PREFIX)) {

            throw new IllegalStateException(
                    "The development JWT secret cannot be used with the prod "
                            + "profile; set PAYCORE_JWT_SECRET to a real secret"
            );
        }

        return new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"
        );
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(
                new ImmutableSecret<>(jwtSigningKey)
        );
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey) {
        return NimbusJwtDecoder
                .withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }
}
