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

    Page<KycVerification> findByKycProfileAndVerificationType(
            KycProfile kycProfile,
            KycVerificationType verificationType,
            Pageable pageable
    );

    long countByVerificationTypeAndIpAddressAndCreatedAtAfter(
            KycVerificationType verificationType,
            String ipAddress,
            Instant since
    );

    Optional<KycVerification>
    findFirstByVerificationTypeAndIpAddressAndCreatedAtAfterOrderByCreatedAtAscIdAsc(
            KycVerificationType verificationType,
            String ipAddress,
            Instant since
    );

    @Query(
            value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(7201, hashtext(:networkKey))) AS l",
            nativeQuery = true
    )
    Integer lockClientNetwork(@Param("networkKey") String networkKey);

    List<KycVerification>
    findByKycProfileAndVerificationTypeAndResultAndCreatedAtAfterOrderByCreatedAtAscIdAsc(
            KycProfile kycProfile,
            KycVerificationType verificationType,
            VerificationResult result,
            Instant since
    );
}
