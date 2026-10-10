package com.fintechplatform.paycore.payment.dto;

import com.fintechplatform.paycore.kyc.dto.PageInfo;
import org.springframework.data.domain.Page;

import java.util.List;

public record PaymentPageResponse(
        List<PaymentResponse> payments,
        PageInfo page
) {

    public static PaymentPageResponse of(Page<PaymentResponse> page) {
        return new PaymentPageResponse(page.getContent(), PageInfo.of(page));
    }
}
