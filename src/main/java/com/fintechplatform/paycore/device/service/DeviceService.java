package com.fintechplatform.paycore.device.service;

import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.customer.exception.CustomerNotFoundException;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
import com.fintechplatform.paycore.device.config.DeviceProperties;
import com.fintechplatform.paycore.device.dto.CodeSentResponse;
import com.fintechplatform.paycore.device.dto.DeviceResponse;
import com.fintechplatform.paycore.device.entity.CustomerDevice;
import com.fintechplatform.paycore.device.enums.DeviceStatus;
import com.fintechplatform.paycore.device.exception.DeviceAlreadyTrustedException;
import com.fintechplatform.paycore.device.exception.DeviceNotFoundException;
import com.fintechplatform.paycore.device.exception.DeviceNotTrustedException;
import com.fintechplatform.paycore.device.repository.CustomerDeviceRepository;
import com.fintechplatform.paycore.identity.entity.LoginSession;
import com.fintechplatform.paycore.identity.repository.LoginSessionRepository;
import com.fintechplatform.paycore.identity.service.RefreshTokenService;
import com.fintechplatform.paycore.identity.service.SessionTokenService;
import com.fintechplatform.paycore.verification.enums.OneTimeCodePurpose;
import com.fintechplatform.paycore.verification.exception.InvalidOneTimeCodeException;
import com.fintechplatform.paycore.verification.service.OneTimeCodeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    private static final Pattern DEVICE_ID = Pattern.compile("[A-Za-z0-9_-]{16,128}");

    private final CustomerDeviceRepository deviceRepository;
    private final CustomerRepository customerRepository;
    private final LoginSessionRepository loginSessionRepository;
    private final RefreshTokenService refreshTokenService;
    private final SessionTokenService hasher;
    private final OneTimeCodeService oneTimeCodeService;
    private final DeviceProperties properties;

    public DeviceService(
            CustomerDeviceRepository deviceRepository,
            CustomerRepository customerRepository,
            LoginSessionRepository loginSessionRepository,
            RefreshTokenService refreshTokenService,
            SessionTokenService hasher,
            OneTimeCodeService oneTimeCodeService,
            DeviceProperties properties
    ) {
        this.deviceRepository = deviceRepository;
        this.customerRepository = customerRepository;
        this.loginSessionRepository = loginSessionRepository;
        this.refreshTokenService = refreshTokenService;
        this.hasher = hasher;
        this.oneTimeCodeService = oneTimeCodeService;
        this.properties = properties;
    }

    @Transactional
    public CustomerDevice recognize(UUID customerId, String rawDeviceId, String userAgent) {

        if (rawDeviceId == null || !DEVICE_ID.matcher(rawDeviceId).matches()) {
            return null;
        }

        String deviceHash = hasher.hash(rawDeviceId);
        String label = DeviceLabels.fromUserAgent(userAgent);

        deviceRepository.lockCustomerDevices(customerId);

        Optional<CustomerDevice> known = deviceRepository.findByCustomerIdAndDeviceHash(customerId, deviceHash);

        if (known.isPresent()) {
            CustomerDevice device = known.get();
            device.signedIn(label);
            return deviceRepository.save(device);
        }

        boolean first = !deviceRepository.existsByCustomerIdAndTrustedAtIsNotNull(customerId);

        CustomerDevice device =
                first
                        ? CustomerDevice.firstDevice(customerId, deviceHash, label)
                        : CustomerDevice.pending(customerId, deviceHash, label);

        log.info("Customer {} signed in from a new device ({})", customerId, device.getStatus());

        return deviceRepository.save(device);
    }

    @Transactional(readOnly = true)
    public CustomerDevice requireTrusted(UUID sessionId) {

        return deviceRepository
                .findBySessionId(sessionId)
                .filter(CustomerDevice::isTrusted)
                .orElseThrow(DeviceNotTrustedException::new);
    }

    @Transactional(readOnly = true)
    public List<DeviceResponse> list(UUID customerId, UUID sessionId) {

        UUID currentId = deviceRepository.findBySessionId(sessionId).map(CustomerDevice::getId).orElse(null);
        Instant now = Instant.now();

        return deviceRepository
                .findByCustomerIdOrderByLastSeenAtDesc(customerId)
                .stream()
                .map(device -> DeviceResponse.of(device, device.getId().equals(currentId), now))
                .toList();
    }

    @Transactional(readOnly = true)
    public DeviceResponse current(UUID sessionId) {

        return deviceRepository
                .findBySessionId(sessionId)
                .map(device -> DeviceResponse.of(device, true, Instant.now()))
                .orElseThrow(DeviceNotFoundException::new);
    }

    @Transactional
    public CodeSentResponse sendConfirmationCode(UUID customerId, UUID sessionId) {

        CustomerDevice device = currentDevice(sessionId);

        if (device.getStatus() != DeviceStatus.PENDING) {
            throw new DeviceAlreadyTrustedException();
        }

        Customer customer = customer(customerId);

        Instant expiresAt = oneTimeCodeService.send(customer, OneTimeCodePurpose.DEVICE_BINDING, device.getId());

        return new CodeSentResponse(maskEmail(customer.getEmail()), expiresAt);
    }

    @Transactional(noRollbackFor = InvalidOneTimeCodeException.class)
    public DeviceResponse confirm(UUID customerId, UUID sessionId, String code) {

        CustomerDevice device = currentDevice(sessionId);

        if (device.getStatus() != DeviceStatus.PENDING) {
            throw new DeviceAlreadyTrustedException();
        }

        oneTimeCodeService.consume(customerId, OneTimeCodePurpose.DEVICE_BINDING, device.getId(), code);

        Instant now = Instant.now();
        device.trust(now.plus(properties.getCoolingOff()));

        log.info("Customer {} confirmed device {}; spending capped until {}",
                customerId, device.getId(), device.getCoolingOffUntil());

        return DeviceResponse.of(deviceRepository.save(device), true, now);
    }

    @Transactional
    public void revoke(UUID customerId, UUID sessionId, UUID deviceId) {

        requireTrusted(sessionId);

        CustomerDevice device =
                deviceRepository
                        .findByIdAndCustomerId(deviceId, customerId)
                        .orElseThrow(DeviceNotFoundException::new);

        device.revoke();
        deviceRepository.save(device);

        List<LoginSession> sessions = loginSessionRepository.findByDeviceIdAndRevokedAtIsNull(deviceId);

        for (LoginSession session : sessions) {
            session.revoke();
            refreshTokenService.revokeAllForSession(session);
        }

        loginSessionRepository.saveAll(sessions);

        log.info("Customer {} removed device {} and ended {} session(s)", customerId, deviceId, sessions.size());
    }

    @Transactional(readOnly = true)
    public Optional<Instant> coolingOffUntil(UUID customerId) {
        return deviceRepository.findCoolingOffUntil(customerId, Instant.now());
    }

    private CustomerDevice currentDevice(UUID sessionId) {
        return deviceRepository.findBySessionId(sessionId).orElseThrow(DeviceNotFoundException::new);
    }

    private Customer customer(UUID customerId) {
        return customerRepository
                .findById(customerId)
                .orElseThrow(() -> new CustomerNotFoundException(customerId));
    }

    public static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) {
            return email;
        }
        return email.charAt(0) + "*".repeat(at - 1) + email.substring(at);
    }
}
