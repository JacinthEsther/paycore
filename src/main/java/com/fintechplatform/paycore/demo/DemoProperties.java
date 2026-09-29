package com.fintechplatform.paycore.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Locale;

/**
 * Developer Preview mode. When enabled, a shared admin account is seeded
 * so visitors of the public demo can walk through the admin side of the
 * flow. Its credentials are published by {@link DemoController}, so demo
 * mode must never be enabled on an environment holding real customer data.
 */
@ConfigurationProperties(prefix = "paycore.demo")
public class DemoProperties {

    private boolean enabled;

    private String adminEmail = "admin@paycore.demo";

    private String adminPassword;

    private String adminFirstName = "PayCore";

    private String adminLastName = "Admin";

    private String adminCountryCode = "NG";

    private String adminPhoneNumber = "08030000000";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAdminEmail() {
        return adminEmail;
    }

    /**
     * Stored the way registration stores emails.
     */
    public String normalizedAdminEmail() {
        return adminEmail.trim().toLowerCase(Locale.ROOT);
    }

    public void setAdminEmail(String adminEmail) {
        this.adminEmail = adminEmail;
    }

    public String getAdminPassword() {
        return adminPassword;
    }

    public void setAdminPassword(String adminPassword) {
        this.adminPassword = adminPassword;
    }

    public String getAdminFirstName() {
        return adminFirstName;
    }

    public void setAdminFirstName(String adminFirstName) {
        this.adminFirstName = adminFirstName;
    }

    public String getAdminLastName() {
        return adminLastName;
    }

    public void setAdminLastName(String adminLastName) {
        this.adminLastName = adminLastName;
    }

    public String getAdminCountryCode() {
        return adminCountryCode;
    }

    public void setAdminCountryCode(String adminCountryCode) {
        this.adminCountryCode = adminCountryCode;
    }

    public String getAdminPhoneNumber() {
        return adminPhoneNumber;
    }

    public void setAdminPhoneNumber(String adminPhoneNumber) {
        this.adminPhoneNumber = adminPhoneNumber;
    }
}
