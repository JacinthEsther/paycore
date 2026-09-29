package com.fintechplatform.paycore.kyc.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintechplatform.paycore.kyc.enums.VerificationResult;
import com.fintechplatform.paycore.kyc.exception.KycProviderException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Dojah "Validate BVN" adapter: GET /api/v1/kyc/bvn with the customer's
 * claimed names and date of birth. Dojah answers with a per-field match,
 * e.g. {"entity":{"bvn":{"status":true},"first_name":{"status":true}}}.
 *
 * <p>A match on every supplied field is PASSED; an unknown BVN or any
 * mismatch is FAILED. Outages, bad credentials, empty wallet and rate
 * limits are provider errors, not verification results.
 */
@Component
@ConditionalOnProperty(
        name = "paycore.kyc.provider",
        havingValue = "dojah",
        matchIfMissing = true
)
public class DojahKycProvider implements KycProvider {

    static final String PROVIDER_NAME = "DOJAH";

    private static final List<String> MATCHED_FIELDS =
            List.of("first_name", "last_name", "dob");

    private final RestClient restClient;
    private final DojahProperties properties;
    private final ObjectMapper objectMapper;

    public DojahKycProvider(
            RestClient dojahRestClient,
            DojahProperties properties,
            ObjectMapper objectMapper
    ) {
        this.restClient = dojahRestClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public KycProviderResult verifyBvn(
            KycVerificationRequest request
    ) {

        if (!properties.hasCredentials()) {
            throw new KycProviderException(
                    "Dojah credentials are not configured; "
                            + "set DOJAH_APP_ID and DOJAH_SECRET_KEY"
            );
        }

        DojahResponse response;

        try {

            response =
                    restClient.get()
                            .uri(uriBuilder ->
                                    uriBuilder
                                            .path("/api/v1/kyc/bvn")
                                            .queryParam("bvn", request.idNumber())
                                            .queryParam("first_name", request.firstName())
                                            .queryParam("last_name", request.lastName())
                                            .queryParam("dob", request.dateOfBirth())
                                            .build()
                            )
                            .header("AppId", properties.appId())
                            .header("Authorization", properties.secretKey())
                            .exchange((httpRequest, httpResponse) ->
                                    new DojahResponse(
                                            httpResponse.getStatusCode().value(),
                                            new String(
                                                    httpResponse.getBody().readAllBytes(),
                                                    StandardCharsets.UTF_8
                                            )
                                    )
                            );

        } catch (RestClientException exception) {

            throw new KycProviderException(
                    "Unable to communicate with Dojah",
                    exception
            );
        }

        return mapResponse(response);
    }

    private KycProviderResult mapResponse(DojahResponse response) {

        JsonNode root = parse(response.body());

        int status = response.status();

        if (status == 400 || status == 404) {
            // Dojah reports an unknown BVN as {"error":"BVN not found"}.
            return failed(root, describeError(root, "BVN could not be found"));
        }

        if (status < 200 || status >= 300) {
            throw new KycProviderException(
                    "Dojah returned HTTP " + status
                            + ": " + describeError(root, "no details")
            );
        }

        JsonNode entity = root.path("entity");

        if (!entity.isObject() || entity.isEmpty()) {
            throw new KycProviderException(
                    "Dojah response did not contain a verification entity"
            );
        }

        if (!entity.path("bvn").path("status").asBoolean(false)) {
            return failed(root, "BVN could not be validated");
        }

        List<String> mismatched = new ArrayList<>();

        for (String field : MATCHED_FIELDS) {
            JsonNode match = entity.path(field);
            if (!match.isMissingNode()
                    && !match.path("status").asBoolean(false)) {
                mismatched.add(field);
            }
        }

        if (!mismatched.isEmpty()) {
            return failed(
                    root,
                    "Details do not match BVN record: "
                            + String.join(", ", mismatched)
            );
        }

        return new KycProviderResult(
                PROVIDER_NAME,
                extractReference(root),
                VerificationResult.PASSED,
                null
        );
    }

    private KycProviderResult failed(JsonNode root, String reason) {

        return new KycProviderResult(
                PROVIDER_NAME,
                extractReference(root),
                VerificationResult.FAILED,
                reason
        );
    }

    private JsonNode parse(String body) {

        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }

        try {
            return objectMapper.readTree(body);
        } catch (IOException exception) {
            throw new KycProviderException(
                    "Unable to parse Dojah response",
                    exception
            );
        }
    }

    private String describeError(JsonNode root, String fallback) {

        JsonNode error = root.path("error");

        return error.isTextual() ? error.asText() : fallback;
    }

    /**
     * The documented Validate BVN response carries no reference; keep
     * whichever one Dojah sends if that changes.
     */
    private String extractReference(JsonNode root) {

        for (String field : List.of("reference", "reference_id", "request_id")) {
            JsonNode value = root.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }

        return null;
    }

    /**
     * Not wired to Dojah yet: its NIN response has not been verified
     * against the sandbox, and guessing a vendor's response format would
     * risk wrong PASSED/FAILED verdicts. Reported as a provider error.
     */
    @Override
    public KycProviderResult verifyNin(
            KycVerificationRequest request
    ) {

        throw new KycProviderException(
                "NIN verification is not implemented for Dojah yet"
        );
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    private record DojahResponse(int status, String body) {
    }
}
