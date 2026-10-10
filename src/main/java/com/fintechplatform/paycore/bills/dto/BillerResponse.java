package com.fintechplatform.paycore.bills.dto;

import com.fintechplatform.paycore.bills.catalog.BillCategory;
import com.fintechplatform.paycore.bills.catalog.Biller;

import java.math.BigDecimal;
import java.util.List;

public record BillerResponse(
        String id,
        String name,
        BillCategory category,
        String numberLabel,
        boolean products,
        boolean verified,
        List<String> meterTypes,
        BigDecimal minAmount,
        BigDecimal maxAmount
) {

    public static BillerResponse of(Biller biller, List<String> meterTypes) {
        return new BillerResponse(
                biller.id(),
                biller.name(),
                biller.category(),
                biller.category().numberLabel(),
                biller.products(),
                biller.verified(),
                meterTypes,
                biller.minAmount(),
                biller.maxAmount()
        );
    }
}
