package com.fintechplatform.paycore.pin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public final class PinRequests {

    private static final String FOUR_DIGITS = "\\d{4}";
    private static final String FOUR_DIGITS_MESSAGE = "PIN must be 4 digits";

    private PinRequests() {
    }

    public record SetPinRequest(

            @NotBlank(message = "PIN is required")
            @Pattern(regexp = FOUR_DIGITS, message = FOUR_DIGITS_MESSAGE)
            String pin
    ) {
    }

    public record ChangePinRequest(

            @NotBlank(message = "Current PIN is required")
            @Pattern(regexp = FOUR_DIGITS, message = FOUR_DIGITS_MESSAGE)
            String currentPin,

            @NotBlank(message = "New PIN is required")
            @Pattern(regexp = FOUR_DIGITS, message = FOUR_DIGITS_MESSAGE)
            String newPin
    ) {
    }

    public record ResetPinRequest(

            @NotBlank(message = "Code is required")
            @Pattern(regexp = "\\d{6}", message = "Code must be 6 digits")
            String code,

            @NotBlank(message = "New PIN is required")
            @Pattern(regexp = FOUR_DIGITS, message = FOUR_DIGITS_MESSAGE)
            String newPin
    ) {
    }
}
