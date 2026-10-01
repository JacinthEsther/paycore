package com.fintechplatform.paycore.operations.repository;

import com.fintechplatform.paycore.operations.entity.OperationsRequest;
import com.fintechplatform.paycore.operations.enums.OperationsRequestStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OperationsRequestRepository extends JpaRepository<OperationsRequest, UUID> {

    /** The queue: oldest first, so nothing waits forever. */
    List<OperationsRequest> findByStatusOrderByRequestedAtAsc(OperationsRequestStatus status, Pageable pageable);

    /** Recently decided ones, newest first. */
    List<OperationsRequest> findByStatusNotOrderByDecidedAtDesc(OperationsRequestStatus status, Pageable pageable);

    /**
     * SELECT ... FOR UPDATE: two checkers approving the same request at
     * once are served one after the other, and the second finds it decided.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM OperationsRequest r WHERE r.id = :id")
    Optional<OperationsRequest> findByIdForUpdate(@Param("id") UUID id);
}
