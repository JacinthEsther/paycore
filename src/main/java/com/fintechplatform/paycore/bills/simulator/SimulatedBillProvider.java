package com.fintechplatform.paycore.bills.simulator;

import com.fintechplatform.paycore.bills.catalog.BillCatalog;
import com.fintechplatform.paycore.bills.catalog.BillCategory;
import com.fintechplatform.paycore.bills.catalog.Biller;
import com.fintechplatform.paycore.bills.provider.BillProvider;
import com.fintechplatform.paycore.bills.provider.BillProviderException;
import com.fintechplatform.paycore.bills.provider.RequestIds;
import com.fintechplatform.paycore.bills.simulator.BillRecords.BillRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "paycore.bills.provider", havingValue = "simulated")
public class SimulatedBillProvider implements BillProvider {

    static final String PROVIDER_NAME = "SIMULATED";

    private static final Logger log = LoggerFactory.getLogger(SimulatedBillProvider.class);

    private static final Map<String, List<Product>> PRODUCTS = Map.of(
            "mtn-data", dataBundles("mtn"),
            "glo-data", dataBundles("glo"),
            "airtel-data", dataBundles("airtel"),
            "etisalat-data", dataBundles("9mobile"),
            "dstv", List.of(
                    product("dstv-padi", "DStv Padi", "4400"),
                    product("dstv-yanga", "DStv Yanga", "6000"),
                    product("dstv-confam", "DStv Confam", "11000")),
            "gotv", List.of(
                    product("gotv-smallie", "GOtv Smallie", "1900"),
                    product("gotv-jinja", "GOtv Jinja", "3900"),
                    product("gotv-jolli", "GOtv Jolli", "5800")),
            "startimes", List.of(
                    product("nova", "StarTimes Nova", "1900"),
                    product("basic", "StarTimes Basic", "3700"))
    );

    private final BillRecords records;
    private final SecureRandom random = new SecureRandom();

    public SimulatedBillProvider(BillRecords records) {
        this.records = records;
        log.warn("Bill provider is SIMULATED: no biller is really paid. Never use this with real customers.");
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
        return PRODUCTS.getOrDefault(billerId, List.of());
    }

    @Override
    public Optional<Customer> verify(String billerId, String productCode, String customerNumber) {

        Biller biller = BillCatalog.require(billerId);

        if (customerNumber.endsWith("0000")) {
            return Optional.empty();
        }

        return Optional.of(
                biller.category() == BillCategory.ELECTRICITY
                        ? new Customer("Adaeze Okonkwo", "12 Allen Avenue, Ikeja, Lagos")
                        : new Customer("Adaeze Okonkwo", null)
        );
    }

    @Override
    public Result pay(Purchase purchase) {

        String kobo = purchase.amount().setScale(2).toPlainString();

        if (kobo.endsWith(".97")) {
            throw new BillProviderException("Simulated: the request never reached the aggregator");
        }

        boolean declined = kobo.endsWith(".99");
        String token = declined || !isElectricity(purchase.billerId()) ? null : token();

        BillRecord stored =
                records.saveIfAbsent(new BillRecord(
                        purchase.requestId(),
                        purchase.billerId(),
                        purchase.productCode(),
                        purchase.customerNumber(),
                        purchase.amount().movePointRight(2).longValueExact(),
                        "NGN",
                        declined ? "FAILED" : "DELIVERED",
                        declined ? "The biller declined this payment" : null,
                        token,
                        Instant.now()
                ));

        if (kobo.endsWith(".98")) {
            throw new BillProviderException("Simulated: the aggregator delivered but the answer was lost");
        }

        return toResult(stored);
    }

    @Override
    public Result status(String requestId) {
        return records.find(requestId).map(this::toResult).orElseGet(Result::notFound);
    }

    private Result toResult(BillRecord record) {
        return "DELIVERED".equals(record.status())
                ? Result.delivered(record.token(), "SIMBILL-" + record.requestId())
                : Result.failed(record.reason());
    }

    private static boolean isElectricity(String billerId) {
        return BillCatalog.find(billerId).map(b -> b.category() == BillCategory.ELECTRICITY).orElse(false);
    }

    private String token() {
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            if (i > 0 && i % 4 == 0) {
                token.append('-');
            }
            token.append(random.nextInt(10));
        }
        return token.toString();
    }

    private static List<Product> dataBundles(String network) {
        return List.of(
                product(network + "-500mb-1d", "500MB, 1 day", "200"),
                product(network + "-1gb-7d", "1GB, 7 days", "500"),
                product(network + "-3gb-30d", "3GB, 30 days", "1500"),
                product(network + "-10gb-30d", "10GB, 30 days", "4500")
        );
    }

    private static Product product(String code, String name, String amount) {
        return new Product(code, name, new BigDecimal(amount));
    }
}
