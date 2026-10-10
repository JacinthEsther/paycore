package com.fintechplatform.paycore.bills.provider;

import com.fintechplatform.paycore.bills.config.BillProperties;
import com.fintechplatform.paycore.bills.provider.BillProvider.Purchase;
import com.fintechplatform.paycore.bills.provider.BillProvider.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class VtpassBillProviderTest {

    private static final String BASE_URL = "https://sandbox.vtpass.com/api";

    private static final Purchase AIRTIME =
            new Purchase("202610081530ABCDEF0123456789", "mtn", null, "08011111111", new BigDecimal("500"), "08011111111");

    private static final Purchase POWER =
            new Purchase("202610081530ABCDEF0123456790", "ikeja-electric", "prepaid", "1111111111111",
                    new BigDecimal("2000"), "08011111111");

    private MockRestServiceServer server;
    private VtpassBillProvider provider;

    @BeforeEach
    void setUp() {

        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();

        BillProperties properties = new BillProperties();
        properties.getVtpass().setApiKey("test-api-key");
        properties.getVtpass().setPublicKey("PK_test");
        properties.getVtpass().setSecretKey("SK_test");

        provider = new VtpassBillProvider(builder.build(), properties);
    }

    @Test
    void readsShouldSendTheApiKeyAndPublicKey() {

        server.expect(requestTo(BASE_URL + "/service-variations?serviceID=mtn-data"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("api-key", "test-api-key"))
                .andExpect(header("public-key", "PK_test"))
                .andExpect(headerDoesNotExist("secret-key"))
                .andRespond(json("""
                        { "response_description": "000", "content": { "variations": [] } }
                        """));

        provider.products("mtn-data");

        server.verify();
    }

    @Test
    void paymentsShouldSendTheApiKeyAndSecretKeyAndTheRequest() {

        server.expect(requestTo(BASE_URL + "/pay"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("api-key", "test-api-key"))
                .andExpect(header("secret-key", "SK_test"))
                .andExpect(headerDoesNotExist("public-key"))
                .andExpect(jsonPath("$.request_id").value(AIRTIME.requestId()))
                .andExpect(jsonPath("$.serviceID").value("mtn"))
                .andExpect(jsonPath("$.billersCode").value("08011111111"))
                .andExpect(jsonPath("$.amount").value("500"))
                .andExpect(jsonPath("$.phone").value("08011111111"))
                .andExpect(jsonPath("$.variation_code").doesNotExist())
                .andRespond(json(delivered("")));

        provider.pay(AIRTIME);

        server.verify();
    }

    @Test
    void tvShouldBuyTheChosenBouquet() {

        server.expect(requestTo(BASE_URL + "/pay"))
                .andExpect(jsonPath("$.variation_code").value("dstv-padi"))
                .andExpect(jsonPath("$.subscription_type").value("change"))
                .andExpect(jsonPath("$.quantity").value(1))
                .andRespond(json(delivered("")));

        provider.pay(new Purchase("202610081530ABCDEF0123456791", "dstv", "dstv-padi", "1212121212",
                new BigDecimal("4400"), "08011111111"));

        server.verify();
    }

    @Test
    void deliveredShouldCarryTheTransactionId() {

        respond(json(delivered("")));

        Result result = provider.pay(AIRTIME);

        assertThat(result.outcome()).isEqualTo(Result.Outcome.DELIVERED);
        assertThat(result.providerReference()).isEqualTo("17282198800000001");
        assertThat(result.token()).isNull();
    }

    @Test
    void electricityTokenShouldComeFromPurchasedCode() {

        respond(json(delivered("""
                , "purchased_code": "Token : 4210 6730 5821 9083 3417"
                """)));

        assertThat(provider.pay(POWER).token()).isEqualTo("4210 6730 5821 9083 3417");
    }

    @Test
    void mainTokenShouldWinOverPurchasedCode() {

        respond(json(delivered("""
                , "purchased_code": "Token : 1111", "mainToken": "42106730582190833417"
                """)));

        assertThat(provider.pay(POWER).token()).isEqualTo("42106730582190833417");
    }

    @Test
    void failedTransactionShouldBeFailed() {

        respond(json(transaction("000", "failed")));

        assertThat(provider.pay(AIRTIME).outcome()).isEqualTo(Result.Outcome.FAILED);
    }

    @Test
    void pendingTransactionShouldBePending() {

        respond(json(transaction("000", "pending")));

        assertThat(provider.pay(AIRTIME).outcome()).isEqualTo(Result.Outcome.PENDING);
    }

    @Test
    void stillProcessingShouldBePending() {

        respond(json("""
                { "code": "099", "response_description": "TRANSACTION IS PROCESSING" }
                """));

        assertThat(provider.pay(AIRTIME).outcome()).isEqualTo(Result.Outcome.PENDING);
    }

    @Test
    void refusedRequestShouldBeFailedWithoutVtpassWording() {

        respond(json("""
                { "code": "018", "response_description": "LOW WALLET BALANCE" }
                """));

        Result result = provider.pay(AIRTIME);

        assertThat(result.outcome()).isEqualTo(Result.Outcome.FAILED);
        assertThat(result.reason()).doesNotContain("WALLET");
    }

    @Test
    void unexpectedCodeShouldNeverBeTakenForAFailure() {

        respond(json("""
                { "code": "083", "response_description": "SYSTEM ERROR" }
                """));

        assertThat(provider.pay(AIRTIME).outcome()).isEqualTo(Result.Outcome.PENDING);
    }

    @Test
    void requeryOfAnUnknownRequestShouldBeNotFound() {

        server.expect(requestTo(BASE_URL + "/requery"))
                .andExpect(jsonPath("$.request_id").value("202610081530ABCDEF0123456789"))
                .andRespond(json("""
                        { "code": "015", "response_description": "INVALID REQUEST ID" }
                        """));

        assertThat(provider.status("202610081530ABCDEF0123456789").outcome()).isEqualTo(Result.Outcome.NOT_FOUND);
    }

    @Test
    void serverErrorShouldBeAProviderError() {

        respond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> provider.pay(AIRTIME)).isInstanceOf(BillProviderException.class);
    }

    @Test
    void productsShouldBeReadEvenWhenVtpassMisspellsVariations() {

        respond(json("""
                { "response_description": "000", "content": { "varations": [
                    { "variation_code": "dstv-padi", "name": "DStv Padi N4,400", "variation_amount": "4400.00" },
                    { "variation_code": "broken", "name": "No price", "variation_amount": "" }
                ] } }
                """));

        assertThat(provider.products("dstv"))
                .containsExactly(new BillProvider.Product("dstv-padi", "DStv Padi N4,400", new BigDecimal("4400.00")));
    }

    @Test
    void verifiedMeterShouldNameItsOwner() {

        server.expect(requestTo(BASE_URL + "/merchant-verify"))
                .andExpect(jsonPath("$.billersCode").value("1111111111111"))
                .andExpect(jsonPath("$.serviceID").value("ikeja-electric"))
                .andExpect(jsonPath("$.type").value("prepaid"))
                .andRespond(json("""
                        { "code": "000", "content": { "Customer_Name": "TESTMETER1", "Address": "ABULE-EGBA" } }
                        """));

        assertThat(provider.verify("ikeja-electric", "prepaid", "1111111111111"))
                .contains(new BillProvider.Customer("TESTMETER1", "ABULE-EGBA"));
    }

    @Test
    void unknownSmartcardShouldBeEmpty() {

        respond(json("""
                { "code": "000", "content": { "error": "This IUC Number is invalid" } }
                """));

        assertThat(provider.verify("dstv", null, "1212120000")).isEmpty();
    }

    private void respond(ResponseCreator response) {
        server.expect(org.springframework.test.web.client.ExpectedCount.once(),
                        requestTo(org.hamcrest.Matchers.startsWith(BASE_URL)))
                .andRespond(response);
    }

    private static ResponseCreator json(String body) {
        return withSuccess(body, MediaType.APPLICATION_JSON);
    }

    private static String transaction(String code, String status) {
        return """
                { "code": "%s", "content": { "transactions": { "status": "%s", "transactionId": "17282198800000001" } } }
                """.formatted(code, status);
    }

    private static String delivered(String extraFields) {
        return """
                { "code": "000", "content": { "transactions": { "status": "delivered", "transactionId": "17282198800000001" } }
                  %s }
                """.formatted(extraFields);
    }
}
