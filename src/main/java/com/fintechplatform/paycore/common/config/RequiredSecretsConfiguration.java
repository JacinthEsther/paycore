package com.fintechplatform.paycore.common.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Fails startup with a clear message when a required secret is missing.
 *
 * <p>Spring Boot's property binding does not fail on an unresolvable
 * placeholder: {@code spring.datasource.password=${PAYCORE_DB_PASSWORD}}
 * silently becomes the literal text "${PAYCORE_DB_PASSWORD}", and startup
 * then dies with a misleading "password authentication failed". This
 * check runs as a BeanFactoryPostProcessor, i.e. before any bean (the
 * connection pool, Flyway) is created.
 */
@Configuration
public class RequiredSecretsConfiguration {

    @Bean
    static BeanFactoryPostProcessor requiredSecretsCheck(Environment environment) {
        return beanFactory -> requireDatabasePassword(environment);
    }

    static void requireDatabasePassword(Environment environment) {

        String password;

        try {
            // Unlike binding, Environment.getProperty resolves nested
            // placeholders strictly and throws when one is missing.
            password = environment.getProperty("spring.datasource.password");
        } catch (IllegalArgumentException unresolved) {
            password = null;
        }

        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "spring.datasource.password is not configured: set the "
                            + "PAYCORE_DB_PASSWORD environment variable, or add "
                            + "it to paycore-local.properties for local development"
            );
        }
    }
}
