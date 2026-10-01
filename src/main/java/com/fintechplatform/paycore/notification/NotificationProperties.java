package com.fintechplatform.paycore.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "paycore.mail")
public class NotificationProperties {

    /** The From address of every email PayCore sends. */
    private String from = "PayCore <no-reply@paycore.local>";

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }
}
