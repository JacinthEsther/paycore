package com.fintechplatform.paycore.kyc.dto;

import com.fintechplatform.paycore.kyc.enums.KycStatus;

public record KycStatusResponse(
        KycStatus status
) {
}
