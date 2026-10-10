package com.fintechplatform.paycore.pin.repository;

import com.fintechplatform.paycore.pin.entity.TransactionPin;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface TransactionPinRepository extends JpaRepository<TransactionPin, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM TransactionPin p WHERE p.customerId = :customerId")
    Optional<TransactionPin> findForUpdate(@Param("customerId") UUID customerId);
}
