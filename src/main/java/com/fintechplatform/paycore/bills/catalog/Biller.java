package com.fintechplatform.paycore.bills.catalog;

import java.math.BigDecimal;

public record Biller(
        String id,
        String name,
        BillCategory category,
        boolean products,
        boolean verified,
        BigDecimal minAmount,
        BigDecimal maxAmount
) {
}
