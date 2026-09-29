package com.fintechplatform.paycore.account.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of a staff freeze, unfreeze or close. The reason is kept in the
 * account's audit history.
 */
public record AccountStatusChangeRequest(

        @NotBlank(message = "Reason is required")
        @Size(max = 500, message = "Reason must not exceed 500 characters")
        String reason
) {
}
