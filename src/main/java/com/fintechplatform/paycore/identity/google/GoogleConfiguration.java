package com.fintechplatform.paycore.identity.google;

import com.fintechplatform.paycore.identity.exception.GoogleSignInDisabledException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GoogleProperties.class)
public class GoogleConfiguration {

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
