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

    List<OperationsRequest> findByStatusOrderByRequestedAtAsc(OperationsRequestStatus status, Pageable pageable);

    List<OperationsRequest> findByStatusNotOrderByDecidedAtDesc(OperationsRequestStatus status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM OperationsRequest r WHERE r.id = :id")
    Optional<OperationsRequest> findByIdForUpdate(@Param("id") UUID id);
}
