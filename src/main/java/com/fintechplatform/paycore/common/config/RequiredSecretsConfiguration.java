package com.fintechplatform.paycore.common.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class RequiredSecretsConfiguration {

    @Bean
    static BeanFactoryPostProcessor requiredSecretsCheck(Environment environment) {
        return beanFactory -> requireDatabasePassword(environment);
    }

    static void requireDatabasePassword(Environment environment) {

        String password;

        try {
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
