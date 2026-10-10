package com.fintechplatform.paycore.bills.dto;

import java.math.BigDecimal;

public record BillProductResponse(String code, String name, BigDecimal amount) {
}
