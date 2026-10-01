package com.fintechplatform.paycore.customer.service;

import com.fintechplatform.paycore.customer.config.EmailVerificationProperties;
import com.fintechplatform.paycore.customer.dto.response.CustomerResponse;
import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.customer.entity.EmailVerificationToken;
import com.fintechplatform.paycore.customer.enums.CustomerStatus;
import com.fintechplatform.paycore.customer.exception.CustomerNotFoundException;
import com.fintechplatform.paycore.customer.exception.EmailAlreadyVerifiedException;
import com.fintechplatform.paycore.customer.exception.InvalidVerificationTokenException;
import com.fintechplatform.paycore.customer.exception.VerificationEmailRateLimitException;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
import com.fintechplatform.paycore.customer.repository.EmailVerificationTokenRepository;
import com.fintechplatform.paycore.identity.service.SessionTokenService;
import com.fintechplatform.paycore.notification.NotificationService;
import com.fintechplatform.paycore.notification.OutgoingEmail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Proves a customer owns their email address, and activates a new customer
 * once they do (PENDING_VERIFICATION -> ACTIVE).
 *
 * A link holds a 256-bit random token; only its SHA-256 hash is stored. It
 * works once, for 24 hours, only for the address it was sent to, and only
 * while it is the customer's newest link.
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

    private static final Duration DAY = Duration.ofDays(1);

    private final EmailVerificationTokenRepository tokenRepository;
    private final CustomerRepository customerRepository;
    private final CustomerService customerService;
    private final SessionTokenService tokens;
    private final NotificationService notificationService;
    private final EmailVerificationProperties properties;

    public EmailVerificationService(
            EmailVerificationTokenRepository tokenRepository,
            CustomerRepository customerRepository,
            CustomerService customerService,
            SessionTokenService tokens,
            NotificationService notificationService,
            EmailVerificationProperties properties
    ) {
        this.tokenRepository = tokenRepository;
        this.customerRepository = customerRepository;
        this.customerService = customerService;
        this.tokens = tokens;
        this.notificationService = notificationService;
        this.properties = properties;
    }

    /**
     * Sends a link after registration or an email change, unless the
     * address is already verified (e.g. a seeded demo customer). Runs in
     * its own transaction: it is called after the registration committed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendLink(UUID customerId) {

        customerRepository
                .findById(customerId)
                .filter(customer -> !customer.isEmailVerified())
                .filter(customer -> customer.getStatus() != CustomerStatus.CLOSED)
                .ifPresent(this::issueAndSend);
    }

    /**
     * The customer asks for another link: at most one a minute and five a
     * day, so the endpoint cannot be used to flood a mailbox.
     */
    @Transactional
    public void resend(UUID customerId) {

        Customer customer =
                customerRepository
                        .findById(customerId)
                        .orElseThrow(() -> new CustomerNotFoundException(customerId));

        if (customer.isEmailVerified()) {
            throw new EmailAlreadyVerifiedException();
        }

        Instant now = Instant.now();

        tokenRepository
                .findFirstByCustomerIdOrderByCreatedAtDesc(customerId)
                .map(latest -> latest.getCreatedAt().plus(properties.getResendCooldown()))
                .filter(allowedAt -> allowedAt.isAfter(now))
                .ifPresent(allowedAt -> {
                    throw new VerificationEmailRateLimitException(allowedAt);
                });

        if (tokenRepository.countByCustomerIdAndCreatedAtAfter(customerId, now.minus(DAY))
                >= properties.getMaxPerDay()) {
            throw new VerificationEmailRateLimitException(now.plus(Duration.ofHours(1)));
        }

        issueAndSend(customer);
    }

    /**
     * Uses a link. Every kind of bad link gets the same answer.
     */
    @Transactional
    public CustomerResponse verify(String rawToken) {

        EmailVerificationToken token =
                tokenRepository
                        .findByTokenHash(tokens.hash(rawToken))
                        .filter(found -> found.isUsable(Instant.now()))
                        .orElseThrow(InvalidVerificationTokenException::new);

        Customer customer =
                customerRepository
                        .findById(token.getCustomerId())
                        .filter(found -> found.getStatus() != CustomerStatus.CLOSED)
                        // Sent before an email change: it proves nothing
                        // about the current address.
                        .filter(found -> found.getEmail().equals(token.getEmail()))
                        .orElseThrow(InvalidVerificationTokenException::new);

        token.markUsed();
        customer.verifyEmail();

        if (customer.getStatus() == CustomerStatus.PENDING_VERIFICATION) {
            customer.activate();
        }

        log.info("Customer {} verified {}", customer.getId(), customer.getEmail());

        return customerService.toResponse(customerRepository.save(customer));
    }

    private void issueAndSend(Customer customer) {

        tokenRepository.retireOpenTokens(customer.getId(), Instant.now());

        String rawToken = tokens.generate();

        tokenRepository.save(
                EmailVerificationToken.issue(
                        customer.getId(),
                        customer.getEmail(),
                        tokens.hash(rawToken),
                        properties.getTokenTtl()
                )
        );

        OutgoingEmail email =
                new OutgoingEmail(
                        customer.getEmail(),
                        "Verify your PayCore email address",
                        """
                        Hi %s,

                        Confirm that this is your email address by opening this link:

                        %s/verify-email?token=%s

                        The link works once and expires in %d hours. If you did not create a
                        PayCore account, ignore this email.

                        PayCore
                        """.formatted(
                                customer.getFirstName(),
                                properties.getPublicUrl(),
                                rawToken,
                                properties.getTokenTtl().toHours()
                        )
                );

        // Only once the token is committed: a rolled-back request sends
        // nothing, and a slow mail server holds no database connection.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                notificationService.send(email);
            }
        });
    }
}
