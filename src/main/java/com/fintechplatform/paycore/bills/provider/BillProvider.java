package com.fintechplatform.paycore.bills.provider;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface BillProvider {

    String providerName();

    String newRequestId();

    List<Product> products(String billerId);

    Optional<Customer> verify(String billerId, String productCode, String customerNumber);

    Result pay(Purchase purchase);

    Result status(String requestId);

    record Product(String code, String name, BigDecimal amount) {
    }

    record Customer(String name, String address) {
    }

    record Purchase(
            String requestId,
            String billerId,
            String productCode,
            String customerNumber,
            BigDecimal amount,
            String phone
    ) {
    }

    record Result(Outcome outcome, String reason, String token, String providerReference) {

        public enum Outcome {
            DELIVERED,
            FAILED,
            PENDING,
            NOT_FOUND
        }

        public static Result delivered(String token, String providerReference) {
            return new Result(Outcome.DELIVERED, null, token, providerReference);
        }

        public static Result failed(String reason) {
            return new Result(Outcome.FAILED, reason, null, null);
        }

        public static Result pending() {
            return new Result(Outcome.PENDING, null, null, null);
        }

        public static Result notFound() {
            return new Result(Outcome.NOT_FOUND, null, null, null);
        }
    }
}
