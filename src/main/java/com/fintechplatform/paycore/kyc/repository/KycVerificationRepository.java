package com.fintechplatform.paycore.kyc.repository;

import com.fintechplatform.paycore.kyc.entity.KycProfile;
import com.fintechplatform.paycore.kyc.entity.KycVerification;
import com.fintechplatform.paycore.kyc.enums.KycVerificationType;
import com.fintechplatform.paycore.kyc.enums.VerificationResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KycVerificationRepository
        extends JpaRepository<KycVerification, UUID> {

    List<KycVerification> findByKycProfileOrderByCreatedAtAscIdAsc(
            KycProfile kycProfile
    );

    boolean existsByKycProfileAndVerificationTypeAndResult(
            KycProfile kycProfile,
            KycVerificationType verificationType,
            VerificationResult result
    );

    /**
     * One page of a profile's history for a verification type. Callers
     * pass the sort in the {@link Pageable} (newest first for the admin
     * view).
     */
    Page<KycVerification> findByKycProfileAndVerificationType(
            KycProfile kycProfile,
            KycVerificationType verificationType,
            Pageable pageable
    );

    /**
     * Attempts of one type from a client network after {@code since},
     * across all customers. Backs the per-IP BVN rate limit.
     */
    long countByVerificationTypeAndIpAddressAndCreatedAtAfter(
            KycVerificationType verificationType,
            String ipAddress,
            Instant since
    );

    /**
     * Oldest counted attempt from a client network, which decides when
     * that network may try again.
     */
    Optional<KycVerification>
    findFirstByVerificationTypeAndIpAddressAndCreatedAtAfterOrderByCreatedAtAscIdAsc(
            KycVerificationType verificationType,
            String ipAddress,
            Instant since
    );

    /**
     * Transaction-scoped PostgreSQL advisory lock on a client network, so
     * concurrent BVN checks from one network (even for different
     * customers) are counted one at a time. Released automatically at
     * commit/rollback. The two-int form with its own namespace (7201)
     * cannot collide with Flyway's single-bigint advisory lock.
     */
    @Query(
            value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(7201, hashtext(:networkKey))) AS l",
            nativeQuery = true
    )
    Integer lockClientNetwork(@Param("networkKey") String networkKey);

    /**
     * Attempts of one type and result made after {@code since}, oldest
     * first. Used to count failed BVN checks inside the retry window.
     */
    List<KycVerification>
    findByKycProfileAndVerificationTypeAndResultAndCreatedAtAfterOrderByCreatedAtAscIdAsc(
            KycProfile kycProfile,
            KycVerificationType verificationType,
            VerificationResult result,
            Instant since
    );
}
