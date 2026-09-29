package com.fintechplatform.paycore.customer.dto.response;

import com.fintechplatform.paycore.kyc.dto.PageInfo;

import java.util.List;

public record CustomerPageResponse(
        List<CustomerSummaryResponse> customers,
        PageInfo page
) {
}
