package com.fintechplatform.paycore.bills.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fintechplatform.paycore.bills.catalog.BillCatalog;
import com.fintechplatform.paycore.bills.catalog.BillCategory;
import com.fintechplatform.paycore.bills.config.BillProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
@ConditionalOnProperty(name = "paycore.bills.provider", havingValue = "vtpass")
public class VtpassBillProvider implements BillProvider {

    static final String PROVIDER_NAME = "VTPASS";

    private static final Logger log = LoggerFactory.getLogger(VtpassBillProvider.class);

    private static final Set<String> REFUSED = Set.of(
            "011", "012", "013", "017", "018", "019", "021", "022", "023", "024", "025", "026", "027", "028",
            "030", "031", "032", "034", "035", "085", "087", "091"
    );

    private static final Set<String> PROCESSING = Set.of("099", "089", "014");

    private static final Set<String> FAILED = Set.of("016", "040");

    private final RestClient restClient;
    private final BillProperties.Vtpass properties;

    public VtpassBillProvider(RestClient vtpassRestClient, BillProperties properties) {
        this.restClient = vtpassRestClient;
        this.properties = properties.getVtpass();
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public String newRequestId() {
        return RequestIds.next();
    }

    @Override
    public List<Product> products(String billerId) {

        JsonNode body = get("/service-variations?serviceID={id}", billerId);

        JsonNode content = body.path("content");
        JsonNode variations = content.has("variations") ? content.path("variations") : content.path("varations");

        List<Product> products = new ArrayList<>();

        for (JsonNode variation : variations) {
            String code = variation.path("variation_code").asText(null);
            String amount = variation.path("variation_amount").asText(null);

            if (code == null || amount == null || amount.isBlank()) {
                continue;
            }

            products.add(new Product(code, variation.path("name").asText(code), new BigDecimal(amount)));
        }

        return products;
    }

    @Override
    public Optional<Customer> verify(String billerId, String productCode, String customerNumber) {

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("billersCode", customerNumber);
        request.put("serviceID", billerId);

        if (productCode != null && isElectricity(billerId)) {
            request.put("type", productCode);
        }

        JsonNode body = post("/merchant-verify", request);
        JsonNode content = body.path("content");

        if (!"000".equals(body.path("code").asText()) || content.has("error")
                || content.path("WrongBillersCode").asBoolean(false)) {
            return Optional.empty();
        }

        String name = text(content, "Customer_Name");

        return name == null ? Optional.empty() : Optional.of(new Customer(name, text(content, "Address")));
    }

    @Override
    public Result pay(Purchase purchase) {

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("request_id", purchase.requestId());
        request.put("serviceID", purchase.billerId());
        request.put("billersCode", purchase.customerNumber());
        request.put("amount", purchase.amount().toPlainString());
        request.put("phone", purchase.phone());

        BillCategory category = BillCatalog.require(purchase.billerId()).category();

        if (purchase.productCode() != null) {
            request.put("variation_code", purchase.productCode());
        }

        if (category == BillCategory.TV) {
            request.put("subscription_type", "change");
            request.put("quantity", 1);
        }

        return result(post("/pay", request), false);
    }

    @Override
    public Result status(String requestId) {
        return result(post("/requery", Map.of("request_id", requestId)), true);
    }

    private Result result(JsonNode body, boolean requery) {

        String code = body.path("code").asText("");
        JsonNode transaction = body.path("content").path("transactions");

        if ("000".equals(code)) {
            String status = transaction.path("status").asText("").toLowerCase();

            return switch (status) {
                case "delivered" -> Result.delivered(token(body), text(transaction, "transactionId"));
                case "failed", "reversed" -> Result.failed("The biller could not complete this payment");
                default -> Result.pending();
            };
        }

        if (requery && "015".equals(code)) {
            return Result.notFound();
        }

        if (FAILED.contains(code) || REFUSED.contains(code) || "015".equals(code)) {
            if ("018".equals(code)) {
                log.error("VTpass wallet balance is too low to pay bills; top it up");
            } else {
                log.warn("VTpass refused a bill payment: {} {}", code, body.path("response_description").asText());
            }
            return Result.failed("The biller could not complete this payment");
        }

        if (!PROCESSING.contains(code)) {
            log.warn("Unexpected VTpass answer {}; treating the payment as pending", code);
        }

        return Result.pending();
    }

    private static String token(JsonNode body) {

        String main = text(body, "mainToken");

        if (main != null) {
            return main;
        }

        String purchased = text(body, "purchased_code");

        return purchased == null ? null : purchased.replaceFirst("(?i)^\\s*token\\s*:\\s*", "").trim();
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean isElectricity(String billerId) {
        return BillCatalog.find(billerId).map(b -> b.category() == BillCategory.ELECTRICITY).orElse(false);
    }

    private JsonNode get(String path, Object... variables) {
        try {
            JsonNode body =
                    restClient.get()
                            .uri(path, variables)
                            .header("api-key", properties.getApiKey())
                            .header("public-key", properties.getPublicKey())
                            .retrieve()
                            .body(JsonNode.class);
            return requireBody(body);
        } catch (RestClientException exception) {
            throw new BillProviderException("VTpass GET " + path + " failed: " + exception.getMessage(), exception);
        }
    }

    private JsonNode post(String path, Object request) {
        try {
            JsonNode body =
                    restClient.post()
                            .uri(path)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("api-key", properties.getApiKey())
                            .header("secret-key", properties.getSecretKey())
                            .body(request)
                            .retrieve()
                            .body(JsonNode.class);
            return requireBody(body);
        } catch (RestClientException exception) {
            throw new BillProviderException("VTpass POST " + path + " failed: " + exception.getMessage(), exception);
        }
    }

    private static JsonNode requireBody(JsonNode body) {
        if (body == null) {
            throw new BillProviderException("VTpass returned no body");
        }
        return body;
    }
}
