package com.fintechplatform.paycore.customer.repository;

import com.fintechplatform.paycore.customer.entity.EmailVerificationToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface EmailVerificationTokenRepository
        extends JpaRepository<EmailVerificationToken, UUID> {

    Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

    long countByCustomerIdAndCreatedAtAfter(UUID customerId, Instant after);

    Optional<EmailVerificationToken> findFirstByCustomerIdOrderByCreatedAtDesc(UUID customerId);

    /**
     * Retires every link still open for the customer, so only the newest
     * one ever works.
     */
    @Modifying
    @Query("""
            UPDATE EmailVerificationToken t
            SET t.usedAt = :now
            WHERE t.customerId = :customerId
              AND t.usedAt IS NULL
            """)
    int retireOpenTokens(@Param("customerId") UUID customerId, @Param("now") Instant now);
}
