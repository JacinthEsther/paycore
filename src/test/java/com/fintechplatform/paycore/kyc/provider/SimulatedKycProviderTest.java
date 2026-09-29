package com.fintechplatform.paycore.kyc.provider;

import com.fintechplatform.paycore.kyc.enums.VerificationResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatedKycProviderTest {

    private final SimulatedKycProvider provider = new SimulatedKycProvider();

    @Test
    void shouldPassTheTestBvnWhenEveryDetailMatches() {

        KycProviderResult result =
                provider.verifyBvn(request(SimulatedKycProvider.TEST_BVN, "John", "Doe", "1990-01-01"));

        assertThat(result.result()).isEqualTo(VerificationResult.PASSED);
        assertThat(result.provider()).isEqualTo("SIMULATED");
        assertThat(result.reason()).isNull();
        assertThat(result.providerReference()).startsWith("SIM-");
    }

    @Test
    void shouldPassTheTestNinIgnoringNameCaseAndSpaces() {

        KycProviderResult result =
                provider.verifyNin(request(SimulatedKycProvider.TEST_NIN, " john ", "DOE", "1990-01-01"));

        assertThat(result.result()).isEqualTo(VerificationResult.PASSED);
    }

    @Test
    void shouldFailAnUnknownNumber() {

        KycProviderResult result =
                provider.verifyBvn(request("12345678901", "John", "Doe", "1990-01-01"));

        assertThat(result.result()).isEqualTo(VerificationResult.FAILED);
        assertThat(result.reason()).isEqualTo("BVN could not be found");
    }

    @Test
    void shouldNotAcceptABvnAsANin() {

        KycProviderResult result =
                provider.verifyNin(request(SimulatedKycProvider.TEST_BVN, "John", "Doe", "1990-01-01"));

        assertThat(result.result()).isEqualTo(VerificationResult.FAILED);
        assertThat(result.reason()).isEqualTo("NIN could not be found");
    }

    @Test
    void shouldListEveryMismatchedField() {

        KycProviderResult result =
                provider.verifyNin(request(SimulatedKycProvider.TEST_NIN, "Jane", "Doe", "1991-02-03"));

        assertThat(result.result()).isEqualTo(VerificationResult.FAILED);
        assertThat(result.reason())
                .isEqualTo("Details do not match NIN record: first_name, dob");
    }

    private KycVerificationRequest request(
            String number,
            String firstName,
            String lastName,
            String dateOfBirth
    ) {
        return new KycVerificationRequest(number, firstName, lastName, dateOfBirth);
    }
}
