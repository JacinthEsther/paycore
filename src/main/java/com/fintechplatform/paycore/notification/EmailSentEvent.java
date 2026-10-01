package com.fintechplatform.paycore.notification;

/**
 * Published after an email has been handed to the EmailSender, so other
 * modules (the demo outbox, tests) can see what was sent.
 */
public record EmailSentEvent(OutgoingEmail email) {
}
