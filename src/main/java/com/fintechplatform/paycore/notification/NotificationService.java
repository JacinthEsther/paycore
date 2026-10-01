package com.fintechplatform.paycore.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;

/**
 * Sends email for the rest of the application. A delivery failure is
 * logged, not thrown: the action that triggered the email (a registration,
 * say) has already happened, and the customer can ask for another one.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final EmailSender emailSender;
    private final ApplicationEventPublisher eventPublisher;

    public NotificationService(EmailSender emailSender, ApplicationEventPublisher eventPublisher) {
        this.emailSender = emailSender;
        this.eventPublisher = eventPublisher;
    }

    /**
     * @return whether the email was handed over for delivery
     */
    public boolean send(OutgoingEmail email) {

        try {
            emailSender.send(email);
        } catch (MailException exception) {
            log.warn("Could not send \"{}\" to {}: {}", email.subject(), email.to(), exception.getMessage());
            return false;
        }

        eventPublisher.publishEvent(new EmailSentEvent(email));
        return true;
    }
}
