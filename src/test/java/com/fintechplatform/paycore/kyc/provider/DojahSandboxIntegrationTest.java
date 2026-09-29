package com.fintechplatform.paycore.kyc.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintechplatform.paycore.kyc.enums.VerificationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calls the real (free) Dojah Sandbox with its documented test BVN.
 * Skipped unless DOJAH_APP_ID and DOJAH_SECRET_KEY are set, so normal
 * builds never touch the network.
 */
@EnabledIfEnvironmentVariable(named = "DOJAH_APP_ID", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DOJAH_SECRET_KEY", matches = ".+")
class DojahSandboxIntegrationTest {

    private static final String SANDBOX_URL = "https://sandbox.dojah.io";

    private static final String SANDBOX_BVN = "22222222222";

    @Test
    void shouldGetAnAnswerFromDojahSandbox() {

        DojahKycProvider provider =
                new DojahKycProvider(
                        org.springframework.web.client.RestClient.create(SANDBOX_URL),
                        new DojahProperties(
                                SANDBOX_URL,
                                System.getenv("DOJAH_APP_ID"),
                                System.getenv("DOJAH_SECRET_KEY")
                        ),
                        new ObjectMapper()
                );

        KycProviderResult result =
                provider.verifyBvn(
                        new KycVerificationRequest(
                                SANDBOX_BVN,
                                "John",
                                "Doe",
                                "1990-01-01"
                        )
                );

        // Sandbox data is mock data, so only assert we got a real verdict.
        assertThat(result.provider()).isEqualTo("DOJAH");
        assertThat(result.result())
                .isIn(VerificationResult.PASSED, VerificationResult.FAILED);
    }
}
