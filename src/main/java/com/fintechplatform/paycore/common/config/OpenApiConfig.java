package com.fintechplatform.paycore.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API description served at /v3/api-docs, browsable at /swagger-ui.html.
 * Every operation is documented as needing a bearer access token; the
 * public ones (login, registration, demo info) simply ignore it. In
 * Swagger UI, call /api/v1/auth/login, then paste the accessToken into
 * "Authorize".
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearerAuth";

    @Bean
    public OpenAPI payCoreOpenApi() {

        return new OpenAPI()
                .info(new Info()
                        .title("PayCore API")
                        .version("v1")
                        .description(
                                "Customer onboarding, KYC and accounts. "
                                        + "Developer Preview: sandbox data only."
                        ))
                .components(new Components()
                        .addSecuritySchemes(BEARER, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
