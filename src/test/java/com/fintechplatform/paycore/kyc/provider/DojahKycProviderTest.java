package com.fintechplatform.paycore.kyc.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintechplatform.paycore.kyc.enums.VerificationResult;
import com.fintechplatform.paycore.kyc.exception.KycProviderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Exercises the Dojah adapter against canned HTTP responses; it never
 * calls Dojah. See DojahSandboxIntegrationTest for the real sandbox.
 */
class DojahKycProviderTest {

    private static final String BASE_URL = "https://sandbox.dojah.io";

    private static final KycVerificationRequest REQUEST =
            new KycVerificationRequest(
                    "22222222222",
                    "John",
                    "Doe",
                    "1990-01-01"
            );

    private MockRestServiceServer server;
    private DojahKycProvider provider;

    @BeforeEach
    void setUp() {

        RestClient.Builder builder =
                RestClient.builder().baseUrl(BASE_URL);

        server = MockRestServiceServer.bindTo(builder).build();

        provider =
                new DojahKycProvider(
                        builder.build(),
                        new DojahProperties(BASE_URL, "test-app-id", "test-secret"),
                        new ObjectMapper()
                );
    }

    @Test
    void shouldCallValidateBvnEndpointWithAuthHeaders() {

        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        BASE_URL + "/api/v1/kyc/bvn?")))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("bvn", "22222222222"))
                .andExpect(queryParam("first_name", "John"))
                .andExpect(queryParam("last_name", "Doe"))
                .andExpect(queryParam("dob", "1990-01-01"))
                .andExpect(header("AppId", "test-app-id"))
                .andExpect(header("Authorization", "test-secret"))
                .andRespond(json(allMatch()));

        provider.verifyBvn(REQUEST);

        server.verify();
    }

    @Test
    void shouldPassWhenEveryFieldMatches() {

        respond(json(allMatch()));

        KycProviderResult result = provider.verifyBvn(REQUEST);

        assertThat(result.provider()).isEqualTo("DOJAH");
        assertThat(result.result()).isEqualTo(VerificationResult.PASSED);
        assertThat(result.reason()).isNull();
    }

    @Test
    void shouldFailWhenANameDoesNotMatch() {

        respond(json("""
                {
                  "entity": {
                    "bvn": { "value": "2*****22222", "status": true },
                    "first_name": { "confidence_value": 100, "status": true },
                    "last_name": { "confidence_value": 20, "status": false }
                  }
                }
                """));

        KycProviderResult result = provider.verifyBvn(REQUEST);

        assertThat(result.result()).isEqualTo(VerificationResult.FAILED);
        assertThat(result.reason())
                .isEqualTo("Details do not match BVN record: last_name");
    }

    @Test
    void shouldFailWhenDateOfBirthDoesNotMatch() {

        respond(json("""
                {
                  "entity": {
                    "bvn": { "value": "2*****22222", "status": true },
                    "first_name": { "confidence_value": 100, "status": true },
                    "last_name": { "confidence_value": 100, "status": true },
                    "dob": { "status": false }
                  }
                }
                """));

        KycProviderResult result = provider.verifyBvn(REQUEST);

        assertThat(result.result()).isEqualTo(VerificationResult.FAILED);
        assertThat(result.reason())
                .isEqualTo("Details do not match BVN record: dob");
    }

    @Test
    void shouldFailWhenBvnIsNotFoundWith404() {

        respond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        { "error": "BVN not found" }
                        """));

        KycProviderResult result = provider.verifyBvn(REQUEST);

        assertThat(result.result()).isEqualTo(VerificationResult.FAILED);
        assertThat(result.reason()).isEqualTo("BVN not found");
    }

    @Test
    void shouldTreatRateLimitAsProviderError() {

        respond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> provider.verifyBvn(REQUEST))
                .isInstanceOf(KycProviderException.class)
                .hasMessageContaining("429");
    }

    @Test
    void shouldTreatServerErrorAsProviderError() {

        respond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> provider.verifyBvn(REQUEST))
                .isInstanceOf(KycProviderException.class)
                .hasMessageContaining("500");
    }

    @Test
    void shouldFailWhenBvnStatusIsFalse() {

        respond(json("""
                { "entity": { "bvn": { "value": "2*****22222", "status": false } } }
                """));

        assertThat(provider.verifyBvn(REQUEST).result())
                .isEqualTo(VerificationResult.FAILED);
    }

    @Test
    void shouldFailWhenBvnIsNotFound() {

        respond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        { "error": "BVN not found" }
                        """));

        KycProviderResult result = provider.verifyBvn(REQUEST);

        assertThat(result.result()).isEqualTo(VerificationResult.FAILED);
        assertThat(result.reason()).isEqualTo("BVN not found");
    }

    @Test
    void shouldKeepProviderReferenceWhenPresent() {

        respond(json("""
                {
                  "reference_id": "dojah-ref-123",
                  "entity": { "bvn": { "status": true } }
                }
                """));

        assertThat(provider.verifyBvn(REQUEST).providerReference())
                .isEqualTo("dojah-ref-123");
    }

    @Test
    void shouldTreatInvalidCredentialsAsProviderError() {

        respond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        { "error": "Invalid credentials" }
                        """));

        assertThatThrownBy(() -> provider.verifyBvn(REQUEST))
                .isInstanceOf(KycProviderException.class)
                .hasMessageContaining("401");
    }

    @Test
    void shouldTreatEmptyWalletAsProviderError() {

        respond(withStatus(HttpStatus.PAYMENT_REQUIRED));

        assertThatThrownBy(() -> provider.verifyBvn(REQUEST))
                .isInstanceOf(KycProviderException.class)
                .hasMessageContaining("402");
    }

    @Test
    void shouldTreatUpstreamOutageAsProviderError() {

        respond(withStatus(HttpStatus.FAILED_DEPENDENCY));

        assertThatThrownBy(() -> provider.verifyBvn(REQUEST))
                .isInstanceOf(KycProviderException.class)
                .hasMessageContaining("424");
    }

    @Test
    void shouldRejectResponseWithoutEntity() {

        respond(json("{}"));

        assertThatThrownBy(() -> provider.verifyBvn(REQUEST))
                .isInstanceOf(KycProviderException.class);
    }

    @Test
    void shouldRejectMalformedResponse() {

        respond(json("not json"));

        assertThatThrownBy(() -> provider.verifyBvn(REQUEST))
                .isInstanceOf(KycProviderException.class)
                .hasMessage("Unable to parse Dojah response");
    }

    @Test
    void shouldRefuseToCallDojahWithoutCredentials() {

        DojahKycProvider unconfigured =
                new DojahKycProvider(
                        RestClient.create(BASE_URL),
                        new DojahProperties(BASE_URL, "", ""),
                        new ObjectMapper()
                );

        assertThatThrownBy(() -> unconfigured.verifyBvn(REQUEST))
                .isInstanceOf(KycProviderException.class)
                .hasMessageContaining("DOJAH_APP_ID");
    }

    @Test
    void shouldMaskIdentityDataInToString() {

        assertThat(REQUEST.toString())
                .doesNotContain("22222222222")
                .doesNotContain("John")
                .doesNotContain("1990-01-01");
    }

    private void respond(
            org.springframework.test.web.client.ResponseCreator response
    ) {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE_URL)))
                .andRespond(response);
    }

    private org.springframework.test.web.client.ResponseCreator json(String body) {
        return withSuccess(body, MediaType.APPLICATION_JSON);
    }

    private String allMatch() {
        return """
                {
                  "entity": {
                    "bvn": { "value": "2*****22222", "status": true },
                    "first_name": { "confidence_value": 100, "status": true },
                    "last_name": { "confidence_value": 100, "status": true }
                  }
                }
                """;
    }
}
