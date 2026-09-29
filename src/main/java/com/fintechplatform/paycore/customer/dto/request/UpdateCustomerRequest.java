package com.fintechplatform.paycore.customer.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateCustomerRequest(

        @Size(
                max = 100,
                message = "First name must not exceed 100 characters"
        )
        @Pattern(
                regexp = ".*\\S.*",
                message = "First name cannot be blank"
        )
        String firstName,

        @Size(
                max = 100,
                message = "Last name must not exceed 100 characters"
        )
        @Pattern(
                regexp = ".*\\S.*",
                message = "Last name cannot be blank"
        )
        String lastName,

        @Size(
                max = 255,
                message = "Email must not exceed 255 characters"
        )
        @Pattern(
                regexp = ".*\\S.*",
                message = "Email cannot be blank"
        )
        String email,

        @Size(
                min = 2,
                max = 2,
                message = "Country code must be a 2-letter ISO country code"
        )
        String countryCode,

        @Size(
                max = 30,
                message = "Phone number is too long"
        )
        @Pattern(
                regexp = ".*\\S.*",
                message = "Phone number cannot be blank"
        )
        String phoneNumber
) {
}