package com.fintechplatform.paycore.bills.dto;

public record VerifyBillCustomerResponse(String billerId, String customerNumber, String name, String address) {
}
