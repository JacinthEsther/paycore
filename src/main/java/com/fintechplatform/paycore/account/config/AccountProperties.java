package com.fintechplatform.paycore.account.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@ConfigurationProperties(prefix = "paycore.accounts")
public class AccountProperties {

    /**
     * ISO 4217 codes customers can open accounts in.
     */
    private Set<String> supportedCurrencies = Set.of("NGN");

    /**
     * Three-digit institution code that seeds the NUBAN check digit of
     * every account number. Changing it invalidates existing numbers.
     */
    private String institutionCode = "999";

    public Set<String> getSupportedCurrencies() {
        return supportedCurrencies;
    }

    public void setSupportedCurrencies(Set<String> supportedCurrencies) {
        this.supportedCurrencies =
                supportedCurrencies.stream()
                        .map(code -> code.trim().toUpperCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
    }

    public String getInstitutionCode() {
        return institutionCode;
    }

    public void setInstitutionCode(String institutionCode) {
        this.institutionCode = institutionCode;
    }
}
