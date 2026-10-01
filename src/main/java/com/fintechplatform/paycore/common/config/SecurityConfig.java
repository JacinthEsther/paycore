package com.fintechplatform.paycore.common.config;

import com.fintechplatform.paycore.security.CurrentUserJwtAuthenticationConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Spring Security stays stateless: every API request is authenticated by
 * its bearer access token. The PayCore LoginSession is a separate
 * business/security record and is not a Spring Security HTTP session.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Browser origins allowed to call the API, e.g. the Developer Preview
     * UI on its own domain. Empty (the default) allows none, which is
     * right when the UI shares the API's origin or uses a dev proxy.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${paycore.cors.allowed-origins:}") String allowedOrigins
    ) {

        List<String> origins =
                Arrays.stream(allowedOrigins.split(","))
                        .map(String::trim)
                        .filter(origin -> !origin.isEmpty())
                        .toList();

        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(origins);
        cors.setAllowedMethods(
                List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS")
        );
        cors.setAllowedHeaders(
                List.of("Authorization", "Content-Type", "X-Session-Token")
        );
        cors.setExposedHeaders(List.of("Retry-After"));
        cors.setMaxAge(Duration.ofHours(1));

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();

        // With no configuration registered, requests pass through without
        // CORS processing instead of every cross-origin request being
        // rejected, which would also break same-host dev proxies.
        if (!origins.isEmpty()) {
            source.registerCorsConfiguration("/api/**", cors);
        }

        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            CurrentUserJwtAuthenticationConverter jwtAuthenticationConverter
    ) throws Exception {

        return http
                .csrf(AbstractHttpConfigurer::disable)

                .cors(Customizer.withDefaults())

                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )

                .authorizeHttpRequests(auth -> auth

                        // Needs the caller's identity, so it must be
                        // matched before the permitAll on /auth/**.
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/auth/logout-all"
                        )
                        .authenticated()

                        .requestMatchers(
                                "/api/v1/auth/**",
                                "/actuator/health"
                        )
                        .permitAll()

                        // Spring Boot forwards errors (e.g. failed bean
                        // validation) here; without this an anonymous
                        // caller would get 401 instead of the real 400.
                        .requestMatchers("/error")
                        .permitAll()

                        // API description and Swagger UI. Turn them off
                        // with springdoc.api-docs.enabled=false and
                        // springdoc.swagger-ui.enabled=false.
                        .requestMatchers(
                                "/v3/api-docs/**",
                                "/swagger-ui.html",
                                "/swagger-ui/**"
                        )
                        .permitAll()

                        // Only exists in demo mode (paycore.demo.enabled).
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/v1/demo"
                        )
                        .permitAll()

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/customers"
                        )
                        .permitAll()

                        // The bank rail authenticates with an HMAC signature
                        // over the body, checked by InboundWebhookService.
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/webhooks/bank-rail/inbound"
                        )
                        .permitAll()

                        .anyRequest()
                        .authenticated()
                )

                .oauth2ResourceServer(oauth ->
                        oauth.jwt(jwt ->
                                jwt.jwtAuthenticationConverter(
                                        jwtAuthenticationConverter
                                )
                        )
                )

                .build();
    }
}
