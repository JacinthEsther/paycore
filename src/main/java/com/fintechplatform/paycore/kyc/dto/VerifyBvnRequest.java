package com.fintechplatform.paycore.kyc.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record VerifyBvnRequest(

        @NotBlank(message = "BVN is required")
        @Pattern(
                regexp = "\\d{11}",
                message = "BVN must contain exactly 11 digits"
        )
        String bvn,

        @NotBlank(message = "First name is required")
        @Size(max = 100, message = "First name must not exceed 100 characters")
        String firstName,

        @NotBlank(message = "Last name is required")
        @Size(max = 100, message = "Last name must not exceed 100 characters")
        String lastName,

        @NotBlank(message = "Date of birth is required")
        @Pattern(
                regexp = "\\d{4}-\\d{2}-\\d{2}",
                message = "Date of birth must use yyyy-MM-dd"
        )
        String dateOfBirth
) {

    @Override
    public String toString() {
        return "VerifyBvnRequest[bvn=***, firstName=***, "
                + "lastName=***, dateOfBirth=***]";
    }
}
