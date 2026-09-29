package com.fintechplatform.paycore.kyc.dto;

import java.util.List;

public record KycReviewQueueResponse(
        List<KycReviewItemResponse> profiles,
        PageInfo page
) {
}
