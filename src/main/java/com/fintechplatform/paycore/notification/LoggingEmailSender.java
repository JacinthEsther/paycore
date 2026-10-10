package com.fintechplatform.paycore.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void send(OutgoingEmail email) {
        log.info(
                "No mail server configured (spring.mail.host); email to {}: {}\n{}",
                email.to(),
                email.subject(),
                email.body()
        );
    }
}
