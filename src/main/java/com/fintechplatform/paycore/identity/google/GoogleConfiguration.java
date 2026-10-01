package com.fintechplatform.paycore.identity.google;

import com.fintechplatform.paycore.identity.exception.GoogleSignInDisabledException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GoogleProperties.class)
public class GoogleConfiguration {

    /**
     * Deliberately not a JwtDecoder bean: the application's own JwtDecoder
     * (for PayCore access tokens) must stay the only one.
     */
    @Bean
    public GoogleIdTokenVerifier googleIdTokenVerifier(GoogleProperties properties) {

        if (!properties.isEnabled()) {
            return idToken -> {
                throw new GoogleSignInDisabledException();
            };
        }

        return JwtGoogleIdTokenVerifier.forGoogle(properties);
    }
}
