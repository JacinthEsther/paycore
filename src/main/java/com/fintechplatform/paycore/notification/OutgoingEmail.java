package com.fintechplatform.paycore.notification;

import java.util.Objects;

public record OutgoingEmail(
        String to,
        String subject,
        String body
) {

    public OutgoingEmail {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(body, "body");
    }
}
