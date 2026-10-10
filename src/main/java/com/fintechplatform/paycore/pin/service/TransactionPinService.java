package com.fintechplatform.paycore.pin.service;

import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.customer.exception.CustomerNotFoundException;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
import com.fintechplatform.paycore.device.dto.CodeSentResponse;
import com.fintechplatform.paycore.device.service.DeviceService;
import com.fintechplatform.paycore.pin.config.PinProperties;
import com.fintechplatform.paycore.pin.dto.PinStatusResponse;
import com.fintechplatform.paycore.pin.entity.TransactionPin;
import com.fintechplatform.paycore.pin.exception.InvalidTransactionPinException;
import com.fintechplatform.paycore.pin.exception.TransactionPinAlreadySetException;
import com.fintechplatform.paycore.pin.exception.TransactionPinLockedException;
import com.fintechplatform.paycore.pin.exception.TransactionPinNotSetException;
import com.fintechplatform.paycore.pin.exception.TransactionPinRequiredException;
import com.fintechplatform.paycore.pin.exception.WeakTransactionPinException;
import com.fintechplatform.paycore.pin.repository.TransactionPinRepository;
import com.fintechplatform.paycore.verification.enums.OneTimeCodePurpose;
import com.fintechplatform.paycore.verification.service.OneTimeCodeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
public class TransactionPinService {

    private static final Logger log = LoggerFactory.getLogger(TransactionPinService.class);

    private final TransactionPinRepository pinRepository;
    private final CustomerRepository customerRepository;
    private final DeviceService deviceService;
    private final OneTimeCodeService oneTimeCodeService;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;
    private final PinProperties properties;

    public TransactionPinService(
            TransactionPinRepository pinRepository,
            CustomerRepository customerRepository,
            DeviceService deviceService,
            OneTimeCodeService oneTimeCodeService,
            PasswordEncoder passwordEncoder,
            TransactionTemplate transactionTemplate,
            PinProperties properties
    ) {
        this.pinRepository = pinRepository;
        this.customerRepository = customerRepository;
        this.deviceService = deviceService;
        this.oneTimeCodeService = oneTimeCodeService;
        this.passwordEncoder = passwordEncoder;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public PinStatusResponse status(UUID customerId) {

        int max = properties.getMaxAttempts();

        return pinRepository
                .findById(customerId)
                .map(pin -> new PinStatusResponse(true, pin.isLocked(max), pin.attemptsRemaining(max)))
                .orElse(new PinStatusResponse(false, false, max));
    }

    public PinStatusResponse set(UUID customerId, UUID sessionId, String rawPin) {

        deviceService.requireTrusted(sessionId);
        requireStrong(rawPin);

        if (pinRepository.existsById(customerId)) {
            throw new TransactionPinAlreadySetException();
        }

        String hash = passwordEncoder.encode(rawPin);

        transactionTemplate.executeWithoutResult(status -> {
            if (pinRepository.existsById(customerId)) {
                throw new TransactionPinAlreadySetException();
            }
            pinRepository.saveAndFlush(TransactionPin.create(customerId, hash));
        });

        log.info("Customer {} set a transaction PIN", customerId);

        return status(customerId);
    }

    public PinStatusResponse change(UUID customerId, UUID sessionId, String currentPin, String newPin) {

        deviceService.requireTrusted(sessionId);
        requireStrong(newPin);

        verify(customerId, currentPin);

        String hash = passwordEncoder.encode(newPin);

        transactionTemplate.executeWithoutResult(status ->
                pinRepository.findForUpdate(customerId).ifPresent(pin -> pin.replace(hash))
        );

        log.info("Customer {} changed their transaction PIN", customerId);

        return status(customerId);
    }

    @Transactional
    public CodeSentResponse sendResetCode(UUID customerId, UUID sessionId) {

        deviceService.requireTrusted(sessionId);

        if (!pinRepository.existsById(customerId)) {
            throw new TransactionPinNotSetException();
        }

        Customer customer =
                customerRepository
                        .findById(customerId)
                        .orElseThrow(() -> new CustomerNotFoundException(customerId));

        return new CodeSentResponse(
                DeviceService.maskEmail(customer.getEmail()),
                oneTimeCodeService.send(customer, OneTimeCodePurpose.PIN_RESET, null)
        );
    }

    public PinStatusResponse reset(UUID customerId, UUID sessionId, String code, String newPin) {

        deviceService.requireTrusted(sessionId);
        requireStrong(newPin);

        String hash = passwordEncoder.encode(newPin);

        oneTimeCodeService.consume(customerId, OneTimeCodePurpose.PIN_RESET, null, code);

        transactionTemplate.executeWithoutResult(status ->
                pinRepository
                        .findForUpdate(customerId)
                        .orElseThrow(TransactionPinNotSetException::new)
                        .replace(hash)
        );

        log.info("Customer {} reset their transaction PIN", customerId);

        return status(customerId);
    }

    public void verify(UUID customerId, String rawPin) {

        if (rawPin == null || rawPin.isBlank()) {
            throw new TransactionPinRequiredException();
        }

        int max = properties.getMaxAttempts();

        String hash =
                transactionTemplate.execute(status -> {
                    TransactionPin pin =
                            pinRepository
                                    .findForUpdate(customerId)
                                    .orElseThrow(TransactionPinNotSetException::new);

                    if (pin.isLocked(max)) {
                        throw new TransactionPinLockedException();
                    }

                    pin.reserveAttempt();
                    return pin.getPinHash();
                });

        boolean matches = passwordEncoder.matches(rawPin.trim(), hash);

        int remaining =
                transactionTemplate.execute(status -> {
                    TransactionPin pin =
                            pinRepository
                                    .findForUpdate(customerId)
                                    .orElseThrow(TransactionPinNotSetException::new);

                    if (matches) {
                        pin.attemptSucceeded();
                    } else {
                        pin.attemptFailed(max);
                    }

                    return pin.attemptsRemaining(max);
                });

        if (!matches) {
            log.warn("Wrong transaction PIN for customer {}; {} attempt(s) left", customerId, remaining);

            if (remaining == 0) {
                throw new TransactionPinLockedException();
            }

            throw new InvalidTransactionPinException(remaining);
        }
    }

    static boolean isWeak(String pin) {

        boolean same = true;
        boolean up = true;
        boolean down = true;

        for (int i = 1; i < pin.length(); i++) {
            int step = pin.charAt(i) - pin.charAt(i - 1);
            same &= step == 0;
            up &= step == 1;
            down &= step == -1;
        }

        return same || up || down;
    }

    private static void requireStrong(String pin) {
        if (pin == null || !pin.matches("\\d{4}") || isWeak(pin)) {
            throw new WeakTransactionPinException();
        }
    }
}
