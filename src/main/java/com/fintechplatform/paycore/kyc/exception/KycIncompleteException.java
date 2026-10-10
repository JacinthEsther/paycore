package com.fintechplatform.paycore.kyc.exception;

import java.util.List;

public class KycIncompleteException extends RuntimeException {

    private final List<String> missing;

    public KycIncompleteException(List<String> missing) {
        super("KYC cannot be submitted yet: " + String.join(", ", missing));
        this.missing = List.copyOf(missing);
    }

    public List<String> getMissing() {
        return missing;
    }
}
