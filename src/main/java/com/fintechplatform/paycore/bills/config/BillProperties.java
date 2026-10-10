package com.fintechplatform.paycore.bills.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "paycore.bills")
public class BillProperties {

    private final Vtpass vtpass = new Vtpass();

    public Vtpass getVtpass() {
        return vtpass;
    }

    public static class Vtpass {

        private String baseUrl = "https://sandbox.vtpass.com/api";
        private String apiKey;
        private String publicKey;
        private String secretKey;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl == null ? null : baseUrl.replaceAll("/+$", "");
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getPublicKey() {
            return publicKey;
        }

        public void setPublicKey(String publicKey) {
            this.publicKey = publicKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }
    }
}
