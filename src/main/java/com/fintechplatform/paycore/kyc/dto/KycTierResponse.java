package com.fintechplatform.paycore.kyc.dto;

import com.fintechplatform.paycore.kyc.enums.KycTierUpgradeStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record KycTierResponse(
        int tier,
        String currency,
        BigDecimal dailySend,
        BigDecimal sentToday,
        BigDecimal maxBalance,
        Instant resetsAt,
        Integer nextTier,
        List<String> nextTierNeeds,
        Upgrade upgrade,
        List<TierLevel> tiers,
        int reviewWorkingDays
) {
    public record Upgrade(
            int requestedTier,
            KycTierUpgradeStatus status,
            String reason,
            Instant requestedAt,
            Instant decidedAt,
            Instant reviewDueAt,
            boolean needsCloserLook,
            boolean decidedAutomatically
    ) {
    }

    public record TierLevel(
            int tier,
            BigDecimal dailySend,
            BigDecimal maxBalance,
            String requirement
    ) {
    }
}
