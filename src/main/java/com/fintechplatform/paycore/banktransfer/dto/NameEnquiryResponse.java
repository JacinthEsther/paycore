package com.fintechplatform.paycore.banktransfer.dto;

public record NameEnquiryResponse(
        String bankCode,
        String bankName,
        String accountNumber,
        String accountName
) {
}
