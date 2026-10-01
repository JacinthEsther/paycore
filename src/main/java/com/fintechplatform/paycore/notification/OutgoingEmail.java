package com.fintechplatform.paycore.notification;

import java.util.Objects;

/**
 * A plain-text email. Plain text on purpose: verification links are the
 * only mail PayCore sends, and they need no markup.
 */
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
