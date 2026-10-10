package com.fintechplatform.paycore.reconciliation.repository;

import com.fintechplatform.paycore.reconciliation.entity.ReconciliationRun;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, UUID> {

    List<ReconciliationRun> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
