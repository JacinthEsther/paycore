package com.fintechplatform.paycore.notification;

/**
 * Delivers an email. SMTP when spring.mail.host is configured (Mailpit
 * locally, a real provider in production); otherwise the email is only
 * logged, so development works without a mail server.
 */
public interface EmailSender {

    void send(OutgoingEmail email);
}
