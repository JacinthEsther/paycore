package com.fintechplatform.paycore.verification.repository;

import com.fintechplatform.paycore.verification.entity.OneTimeCode;
import com.fintechplatform.paycore.verification.enums.OneTimeCodePurpose;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface OneTimeCodeRepository extends JpaRepository<OneTimeCode, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT c FROM OneTimeCode c
            WHERE c.customerId = :customerId
              AND c.purpose = :purpose
            ORDER BY c.createdAt DESC
            LIMIT 1
            """)
    Optional<OneTimeCode> findNewestForUpdate(
            @Param("customerId") UUID customerId,
            @Param("purpose") OneTimeCodePurpose purpose
    );

    Optional<OneTimeCode> findFirstByCustomerIdAndPurposeOrderByCreatedAtDesc(
            UUID customerId,
            OneTimeCodePurpose purpose
    );

    long countByCustomerIdAndPurposeAndCreatedAtAfter(
            UUID customerId,
            OneTimeCodePurpose purpose,
            Instant after
    );

    @Modifying
    @Query("""
            UPDATE OneTimeCode c
            SET c.usedAt = :now
            WHERE c.customerId = :customerId
              AND c.purpose = :purpose
              AND c.usedAt IS NULL
            """)
    int retireOpenCodes(
            @Param("customerId") UUID customerId,
            @Param("purpose") OneTimeCodePurpose purpose,
            @Param("now") Instant now
    );
}
