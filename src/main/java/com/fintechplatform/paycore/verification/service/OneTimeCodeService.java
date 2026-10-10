package com.fintechplatform.paycore.verification.service;

import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.identity.service.SessionTokenService;
import com.fintechplatform.paycore.notification.NotificationService;
import com.fintechplatform.paycore.notification.OutgoingEmail;
import com.fintechplatform.paycore.verification.config.OneTimeCodeProperties;
import com.fintechplatform.paycore.verification.entity.OneTimeCode;
import com.fintechplatform.paycore.verification.enums.OneTimeCodePurpose;
import com.fintechplatform.paycore.verification.exception.InvalidOneTimeCodeException;
import com.fintechplatform.paycore.verification.exception.OneTimeCodeRateLimitException;
import com.fintechplatform.paycore.verification.repository.OneTimeCodeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Service
public class OneTimeCodeService {

    private static final Duration DAY = Duration.ofDays(1);

    private final OneTimeCodeRepository repository;
    private final SessionTokenService hasher;
    private final NotificationService notificationService;
    private final OneTimeCodeProperties properties;
    private final SecureRandom random = new SecureRandom();

    public OneTimeCodeService(
            OneTimeCodeRepository repository,
            SessionTokenService hasher,
            NotificationService notificationService,
            OneTimeCodeProperties properties
    ) {
        this.repository = repository;
        this.hasher = hasher;
        this.notificationService = notificationService;
        this.properties = properties;
    }

    @Transactional
    public Instant send(Customer customer, OneTimeCodePurpose purpose, UUID subjectId) {

        UUID customerId = customer.getId();
        Instant now = Instant.now();

        repository
                .findFirstByCustomerIdAndPurposeOrderByCreatedAtDesc(customerId, purpose)
                .map(latest -> latest.getCreatedAt().plus(properties.getResendCooldown()))
                .filter(allowedAt -> allowedAt.isAfter(now))
                .ifPresent(allowedAt -> {
                    throw new OneTimeCodeRateLimitException(allowedAt);
                });

        if (repository.countByCustomerIdAndPurposeAndCreatedAtAfter(customerId, purpose, now.minus(DAY))
                >= properties.getMaxPerDay()) {
            throw new OneTimeCodeRateLimitException(now.plus(Duration.ofHours(1)));
        }

        repository.retireOpenCodes(customerId, purpose, now);

        String code = "%06d".formatted(random.nextInt(1_000_000));

        OneTimeCode issued =
                repository.save(OneTimeCode.issue(
                        customerId, purpose, subjectId, hash(customerId, code), properties.getTimeToLive()
                ));

        OutgoingEmail email =
                new OutgoingEmail(
                        customer.getEmail(),
                        "Your PayCore code: " + code,
                        """
                        Hi %s,

                        Use this code to %s:

                        %s

                        It expires in %d minutes. PayCore staff will never ask you for it.
                        If you did not ask for it, change your password.

                        PayCore
                        """.formatted(
                                customer.getFirstName(),
                                purpose.action(),
                                code,
                                properties.getTimeToLive().toMinutes()
                        )
                );

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                notificationService.send(email);
            }
        });

        return issued.getExpiresAt();
    }

    @Transactional(noRollbackFor = InvalidOneTimeCodeException.class)
    public void consume(UUID customerId, OneTimeCodePurpose purpose, UUID subjectId, String rawCode) {

        OneTimeCode code =
                repository
                        .findNewestForUpdate(customerId, purpose)
                        .filter(found -> found.isUsable(Instant.now(), properties.getMaxAttempts()))
                        .orElseThrow(InvalidOneTimeCodeException::new);

        boolean matches =
                rawCode != null
                        && Objects.equals(code.getSubjectId(), subjectId)
                        && MessageDigest.isEqual(
                                code.getCodeHash().getBytes(StandardCharsets.US_ASCII),
                                hash(customerId, rawCode.trim()).getBytes(StandardCharsets.US_ASCII)
                        );

        if (!matches) {
            code.recordFailedAttempt();
            repository.save(code);
            throw new InvalidOneTimeCodeException();
        }

        code.markUsed();
        repository.save(code);
    }

    private String hash(UUID customerId, String code) {
        return hasher.hash(customerId + ":" + code);
    }
}
