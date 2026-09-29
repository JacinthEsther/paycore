package com.fintechplatform.paycore.kyc.provider;

/**
 * Provider-neutral identity-number check (a BVN or a NIN). The number is
 * held only for the duration of the provider call and is masked in
 * toString so it cannot leak into logs.
 */
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
