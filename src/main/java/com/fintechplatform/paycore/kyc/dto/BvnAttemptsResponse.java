package com.fintechplatform.paycore.kyc.dto;

import com.fintechplatform.paycore.kyc.enums.KycStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Admin view of a customer's BVN retry limit and attempt history.
 *
 * <p>The limit summary (everything except {@code attempts} and
 * {@code page}) always describes the customer's current state and is
 * the same on every page; only the history is paginated.
 *
 * @param attemptWindow        ISO-8601 duration of the rolling window, e.g. "PT24H"
 * @param countingSince        failures after this instant count toward the limit
 *                             (window start, or the last reset if later)
 * @param retryAfter           when the customer may try again; null unless limited
 * @param attempts             one page of BVN checks, newest first
 * @param page                 paging metadata for {@code attempts}
 */
public record BvnAttemptsResponse(
        UUID kycId,
        KycStatus kycStatus,
        int maxFailedAttempts,
        String attemptWindow,
        Instant countingSince,
        int failedAttemptsCounted,
        int remainingAttempts,
        boolean limited,
        Instant retryAfter,
        Instant resetAt,
        UUID resetBy,
        List<BvnAttemptResponse> attempts,
        PageInfo page
) {
}
