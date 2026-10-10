package com.fintechplatform.paycore.kyc.provider;

public record KycVerificationRequest(
        String idNumber,
        String firstName,
        String lastName,
        String dateOfBirth
) {

    @Override
    public String toString() {
        return "KycVerificationRequest[idNumber=***, firstName=***, "
                + "lastName=***, dateOfBirth=***]";
    }
}
