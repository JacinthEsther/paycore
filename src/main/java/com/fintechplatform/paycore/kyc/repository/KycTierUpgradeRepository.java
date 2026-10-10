package com.fintechplatform.paycore.kyc.repository;

import com.fintechplatform.paycore.kyc.entity.KycProfile;
import com.fintechplatform.paycore.kyc.entity.KycTierUpgrade;
import com.fintechplatform.paycore.kyc.enums.KycTierUpgradeStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KycTierUpgradeRepository extends JpaRepository<KycTierUpgrade, UUID> {

    Optional<KycTierUpgrade> findFirstByKycProfileOrderByRequestedAtDesc(KycProfile profile);

    boolean existsByKycProfileAndStatus(KycProfile profile, KycTierUpgradeStatus status);

    @EntityGraph(attributePaths = {"kycProfile", "kycProfile.customer", "document"})
    List<KycTierUpgrade> findByStatusOrderByRequestedAtAsc(KycTierUpgradeStatus status);

    boolean existsByKycProfileAndStatusAndDecidedAtAfter(KycProfile profile, KycTierUpgradeStatus status, Instant after);

    @Query("SELECT u.id FROM KycTierUpgrade u WHERE u.status = :status AND u.flags IS NULL AND u.reviewDueAt <= :now ORDER BY u.reviewDueAt")
    List<UUID> findDue(@Param("status") KycTierUpgradeStatus status, @Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM KycTierUpgrade u WHERE u.id = :id")
    Optional<KycTierUpgrade> findByIdForUpdate(@Param("id") UUID id);
}
