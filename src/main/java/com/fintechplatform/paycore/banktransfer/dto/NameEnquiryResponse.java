package com.fintechplatform.paycore.banktransfer.dto;

/** Who holds the account, for the customer to confirm before paying. */
public record NameEnquiryResponse(
        String bankCode,
        String bankName,
        String accountNumber,
        String accountName
) {
}
