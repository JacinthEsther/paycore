package com.fintechplatform.paycore.kyc.provider;

import com.fintechplatform.paycore.kyc.enums.VerificationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@Component
@ConditionalOnProperty(name = "paycore.kyc.provider", havingValue = "simulated")
public class SimulatedKycProvider implements KycProvider {

    static final String PROVIDER_NAME = "SIMULATED";

    public static final String TEST_BVN = "22222222222";
    public static final String TEST_NIN = "70123456789";

    private static final Record TEST_PERSON =
            new Record("John", "Doe", "1990-01-01");

    public static final List<SamplePerson> SAMPLE_PEOPLE = List.of(
            new SamplePerson("Chioma", "Eze", "1994-03-12", "22222222301", "70123456301"),
            new SamplePerson("Musa", "Bello", "1988-07-21", "22222222302", "70123456302"),
            new SamplePerson("Ngozi", "Obi", "1991-11-05", "22222222303", "70123456303"),
            new SamplePerson("Emeka", "Nwosu", "1985-02-28", "22222222304", "70123456304"),
            new SamplePerson("Halima", "Yusuf", "1996-09-17", "22222222305", "70123456305"),
            new SamplePerson("Tobi", "Adewale", "1993-06-30", "22222222306", "70123456306")
    );

    private static final Map<String, Record> BVN_RECORDS = records(TEST_BVN, SamplePerson::bvn);

    private static final Map<String, Record> NIN_RECORDS = records(TEST_NIN, SamplePerson::nin);

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

    private static Map<String, Record> records(String testNumber, Function<SamplePerson, String> number) {

        Map<String, Record> records = new HashMap<>();
        records.put(testNumber, TEST_PERSON);
        SAMPLE_PEOPLE.forEach(person -> records.put(
                number.apply(person),
                new Record(person.firstName(), person.lastName(), person.dateOfBirth())
        ));
        return Map.copyOf(records);
    }

    public record SamplePerson(
            String firstName,
            String lastName,
            String dateOfBirth,
            String bvn,
            String nin
    ) {
    }

    private record Record(
            String firstName,
            String lastName,
            String dateOfBirth
    ) {
    }
}
