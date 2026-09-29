package com.fintechplatform.paycore.customer.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterCustomerRequest(

        @NotBlank(message = "First name is required")
        @Size(max = 100, message = "First name must not exceed 100 characters")
        String firstName,

        @NotBlank(message = "Last name is required")
        @Size(max = 100, message = "Last name must not exceed 100 characters")
        String lastName,

        @NotBlank(message = "Email is required")
        @Email(message = "Invalid email address")
        @Size(max = 255, message = "Email must not exceed 255 characters")
        String email,

        @NotBlank(message = "Country code is required")
        @Size(min = 2, max = 2, message = "Country code must be a 2-letter ISO country code")
        String countryCode,

        @NotBlank(message = "Phone number is required")
        @Size(max = 30, message = "Phone number is too long")
        String phoneNumber,

        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 128,
                message = "Password must be between 8 and 128 characters")
        String password
) {
}