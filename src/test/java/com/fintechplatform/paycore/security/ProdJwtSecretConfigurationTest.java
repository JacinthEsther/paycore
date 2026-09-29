package com.fintechplatform.paycore.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Loads the real application.properties / application-prod.properties
 * (through Spring Boot's config-data processing) with only the JWT
 * configuration, so the secret rules are checked without a database.
 */
class ProdJwtSecretConfigurationTest {

    private static final String REAL_SECRET =
            "a-real-production-secret-from-the-vault-0123456789";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withInitializer(new ConfigDataApplicationContextInitializer())
                    .withUserConfiguration(JwtConfiguration.class);

    @Test
    void prodShouldRefuseToStartWithoutJwtSecret() {

        // A PAYCORE_JWT_SECRET exported in the developer's shell would make
        // this case unreachable; skip rather than give a false result.
        assumeTrue(
                System.getenv("PAYCORE_JWT_SECRET") == null,
                "PAYCORE_JWT_SECRET is set in this environment"
        );

        runner.withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessage("paycore.jwt.secret is not configured: "
                                + "set the PAYCORE_JWT_SECRET environment variable"));
    }

    @Test
    void prodShouldRefuseToStartWithEmptyJwtSecret() {

        runner.withPropertyValues(
                        "spring.profiles.active=prod",
                        "PAYCORE_JWT_SECRET="
                )
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("paycore.jwt.secret is empty"));
    }

    @Test
    void shouldReportMissingVariableEvenWhenPlaceholderIsLong() {

        // Guards against relying on the 32-byte check by coincidence: a
        // long unresolved placeholder must still be reported as missing.
        runner.withPropertyValues(
                        "paycore.jwt.secret=${SOME_VERY_LONG_UNSET_SECRET_VARIABLE_NAME_XYZ}"
                )
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("SOME_VERY_LONG_UNSET_SECRET_VARIABLE_NAME_XYZ"));
    }

    @Test
    void prodShouldSignWithSecretFromEnvironment() {

        runner.withPropertyValues(
                        "spring.profiles.active=prod",
                        "PAYCORE_JWT_SECRET=" + REAL_SECRET
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    assertThat(context.getBean(SecretKey.class).getEncoded())
                            .isEqualTo(REAL_SECRET.getBytes(StandardCharsets.UTF_8));
                });
    }

    @Test
    void prodShouldRejectTheDevelopmentSecret() {

        runner.withPropertyValues(
                        "spring.profiles.active=prod",
                        "PAYCORE_JWT_SECRET="
                                + "local-dev-only-paycore-jwt-secret-change-me-0123456789"
                )
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("development JWT secret"));
    }

    @Test
    void prodShouldStillRejectShortSecrets() {

        runner.withPropertyValues(
                        "spring.profiles.active=prod",
                        "PAYCORE_JWT_SECRET=too-short"
                )
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("at least 32 bytes"));
    }

    @Test
    void localDevelopmentShouldKeepTheFallbackSecret() {

        assumeTrue(
                System.getenv("PAYCORE_JWT_SECRET") == null,
                "PAYCORE_JWT_SECRET is set in this environment"
        );

        runner.run(context -> {
            assertThat(context).hasNotFailed();

            assertThat(new String(
                    context.getBean(SecretKey.class).getEncoded(),
                    StandardCharsets.UTF_8
            )).startsWith(JwtConfiguration.DEV_SECRET_PREFIX);
        });
    }
}
