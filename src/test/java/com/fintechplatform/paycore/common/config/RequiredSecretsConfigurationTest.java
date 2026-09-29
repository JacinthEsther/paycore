package com.fintechplatform.paycore.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Sets spring.datasource.password directly (without loading the project's
 * config files), so the result does not depend on whether this machine has
 * a paycore-local.properties.
 */
class RequiredSecretsConfigurationTest {

    private static final String MESSAGE =
            "spring.datasource.password is not configured: set the "
                    + "PAYCORE_DB_PASSWORD environment variable, or add "
                    + "it to paycore-local.properties for local development";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(RequiredSecretsConfiguration.class);

    @Test
    void shouldFailClearlyWhenPasswordVariableIsMissing() {

        assumeTrue(
                System.getenv("PAYCORE_DB_PASSWORD") == null,
                "PAYCORE_DB_PASSWORD is set in this environment"
        );

        runner.withPropertyValues("spring.datasource.password=${PAYCORE_DB_PASSWORD}")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessage(MESSAGE));
    }

    @Test
    void shouldFailClearlyWhenPasswordIsBlankOrAbsent() {

        runner.withPropertyValues("spring.datasource.password=")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessage(MESSAGE));

        runner.run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .hasMessage(MESSAGE));
    }

    @Test
    void shouldStartWhenPasswordComesFromVariable() {

        runner.withPropertyValues(
                        "spring.datasource.password=${PAYCORE_DB_PASSWORD}",
                        "PAYCORE_DB_PASSWORD=from-the-environment"
                )
                .run(context -> assertThat(context).hasNotFailed());
    }
}
