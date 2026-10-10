package com.fintechplatform.paycore.identity.service;

import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.device.entity.CustomerDevice;
import com.fintechplatform.paycore.identity.config.SessionProperties;
import com.fintechplatform.paycore.identity.entity.LoginSession;
import com.fintechplatform.paycore.identity.exception.LoginSessionNotFoundException;
import com.fintechplatform.paycore.identity.repository.LoginSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fintechplatform.paycore.identity.exception.SessionTimedOutException;

import java.time.Instant;
import java.util.List;

@Service
public class LoginSessionService {

    private final LoginSessionRepository loginSessionRepository;
    private final SessionProperties sessionProperties;

    public LoginSessionService(
            LoginSessionRepository loginSessionRepository,
            SessionProperties sessionProperties
    ) {
        this.loginSessionRepository = loginSessionRepository;
        this.sessionProperties = sessionProperties;
    }

    @Transactional
    public LoginSession createSession(
            Customer customer,
            String sessionTokenHash,
            String ipAddress,
            String userAgent
    ) {
        return createSession(customer, sessionTokenHash, ipAddress, userAgent, null);
    }

    @Transactional
    public LoginSession createSession(
            Customer customer,
            String sessionTokenHash,
            String ipAddress,
            String userAgent,
            CustomerDevice device
    ) {
        Instant expiresAt =
                Instant.now()
                        .plus(sessionProperties.getExpiration());

        LoginSession session =
                LoginSession.create(
                        customer,
                        sessionTokenHash,
                        expiresAt,
                        ipAddress,
                        userAgent,
                        device
                );

        return loginSessionRepository.save(session);
    }

    @Transactional(readOnly = true)
    public LoginSession findBySessionTokenHash(
            String sessionTokenHash
    ) {
        return loginSessionRepository
                .findBySessionTokenHash(sessionTokenHash)
                .orElseThrow(
                        LoginSessionNotFoundException::new
                );
    }

    @Transactional
    public void revoke(LoginSession session) {

        session.revoke();

        loginSessionRepository.save(session);
    }

    @Transactional
    public void revokeAll(Customer customer) {

        List<LoginSession> sessions =
                loginSessionRepository
                        .findByCustomerAndRevokedAtIsNull(
                                customer
                        );

        sessions.forEach(LoginSession::revoke);

        loginSessionRepository.saveAll(sessions);
    }

    public void ensureNotTimedOut(LoginSession session) {

        if (session.isExpired()) {
            throw new SessionTimedOutException(SessionTimedOutException.Reason.EXPIRED);
        }

        if (session.isIdle(sessionProperties.getIdleTimeout())) {
            throw new SessionTimedOutException(SessionTimedOutException.Reason.IDLE);
        }
    }

    @Transactional
    public void markUsed(LoginSession session) {

        session.markUsed();

        loginSessionRepository.save(session);
    }
}
