package com.fintechplatform.paycore.reconciliation.repository;

import com.fintechplatform.paycore.reconciliation.entity.ReconciliationItem;
import com.fintechplatform.paycore.reconciliation.enums.ItemKind;
import com.fintechplatform.paycore.reconciliation.enums.ReconciliationResult;
import com.fintechplatform.paycore.reconciliation.enums.ResolutionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReconciliationItemRepository extends JpaRepository<ReconciliationItem, UUID> {

    @Query("""
            SELECT i FROM ReconciliationItem i
            WHERE i.runId = :runId
            ORDER BY CASE WHEN i.result = com.fintechplatform.paycore.reconciliation.enums.ReconciliationResult.MATCHED
                          THEN 1 ELSE 0 END,
                     i.result, i.externalReference
            """)
    List<ReconciliationItem> findByRun(@Param("runId") UUID runId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM ReconciliationItem i WHERE i.id = :itemId")
    Optional<ReconciliationItem> findByIdForUpdate(@Param("itemId") UUID itemId);

    Optional<ReconciliationItem> findFirstByKindAndExternalReferenceAndResultAndResolutionStatusOrderByResolvedAtDesc(
            ItemKind kind,
            String externalReference,
            ReconciliationResult result,
            ResolutionStatus resolutionStatus
    );
}
