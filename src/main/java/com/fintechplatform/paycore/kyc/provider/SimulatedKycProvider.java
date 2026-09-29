package com.fintechplatform.paycore.kyc.provider;

import com.fintechplatform.paycore.kyc.enums.VerificationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * In-process stand-in for a KYC vendor, for the public Developer Preview
 * and local development without vendor credentials. It knows one test
 * record per number type and never calls out, so it cannot break on a
 * vendor outage, an expired key or an empty sandbox wallet.
 *
 * <p>Only a known test number whose names and date of birth all match
 * the record PASSES; any other number or a mismatch FAILS with a reason,
 * so retry limits and the review workflow behave as they would with a
 * real provider. It verifies nobody's real identity, so it must never be
 * enabled where real customers onboard.
 */
@Component
@ConditionalOnProperty(name = "paycore.kyc.provider", havingValue = "simulated")
public class SimulatedKycProvider implements KycProvider {

    static final String PROVIDER_NAME = "SIMULATED";

    public static final String TEST_BVN = "22222222222";
    public static final String TEST_NIN = "70123456789";

    private static final Record TEST_PERSON =
            new Record("John", "Doe", "1990-01-01");

    private static final Map<String, Record> BVN_RECORDS =
            Map.of(TEST_BVN, TEST_PERSON);

    private static final Map<String, Record> NIN_RECORDS =
            Map.of(TEST_NIN, TEST_PERSON);

    private static final Logger log =
            LoggerFactory.getLogger(SimulatedKycProvider.class);

    public SimulatedKycProvider() {
        log.warn(
                "KYC provider is SIMULATED: identity checks are not real. "
                        + "Never use this with real customers."
        );
    }

    @Override
    public KycProviderResult verifyBvn(KycVerificationRequest request) {
        return verify("BVN", BVN_RECORDS, request);
    }

    @Override
    public KycProviderResult verifyNin(KycVerificationRequest request) {
        return verify("NIN", NIN_RECORDS, request);
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    private KycProviderResult verify(
            String type,
            Map<String, Record> records,
            KycVerificationRequest request
    ) {

        Record record = records.get(request.idNumber());

        if (record == null) {
            return result(
                    VerificationResult.FAILED,
                    type + " could not be found"
            );
        }

        List<String> mismatched = new ArrayList<>();

        if (!sameText(record.firstName(), request.firstName())) {
            mismatched.add("first_name");
        }

        if (!sameText(record.lastName(), request.lastName())) {
            mismatched.add("last_name");
        }

        if (!record.dateOfBirth().equals(request.dateOfBirth())) {
            mismatched.add("dob");
        }

        if (!mismatched.isEmpty()) {
            return result(
                    VerificationResult.FAILED,
                    "Details do not match " + type + " record: "
                            + String.join(", ", mismatched)
            );
        }

        return result(VerificationResult.PASSED, null);
    }

    private boolean sameText(String expected, String actual) {
        return actual != null && expected.equalsIgnoreCase(actual.trim());
    }

    private KycProviderResult result(VerificationResult result, String reason) {
        return new KycProviderResult(
                PROVIDER_NAME,
                "SIM-" + UUID.randomUUID(),
                result,
                reason
        );
    }

    private record Record(
            String firstName,
            String lastName,
            String dateOfBirth
    ) {
    }
}
