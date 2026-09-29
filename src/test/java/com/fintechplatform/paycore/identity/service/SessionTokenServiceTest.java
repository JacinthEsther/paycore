package com.fintechplatform.paycore.identity.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionTokenServiceTest {

    private final SessionTokenService service =
            new SessionTokenService();

    @Test
    void shouldGenerateToken() {

        String token = service.generate();

        assertThat(token)
                .isNotBlank();
    }

    @Test
    void shouldGenerateDifferentTokens() {

        String firstToken =
                service.generate();

        String secondToken =
                service.generate();

        assertThat(firstToken)
                .isNotEqualTo(secondToken);
    }

    @Test
    void shouldGenerateUrlSafeToken() {

        String token =
                service.generate();

        assertThat(token)
                .doesNotContain("+")
                .doesNotContain("/")
                .doesNotContain("=");
    }

    @Test
    void shouldGenerateConsistentHash() {

        String token =
                service.generate();

        String firstHash =
                service.hash(token);

        String secondHash =
                service.hash(token);

        assertThat(firstHash)
                .isEqualTo(secondHash);
    }

    @Test
    void shouldGenerateDifferentHashesForDifferentTokens() {

        String firstToken =
                service.generate();

        String secondToken =
                service.generate();

        String firstHash =
                service.hash(firstToken);

        String secondHash =
                service.hash(secondToken);

        assertThat(firstHash)
                .isNotEqualTo(secondHash);
    }

    @Test
    void shouldNeverReturnRawTokenAsHash() {

        String token =
                service.generate();

        String hash =
                service.hash(token);

        assertThat(hash)
                .isNotEqualTo(token);
    }
}

