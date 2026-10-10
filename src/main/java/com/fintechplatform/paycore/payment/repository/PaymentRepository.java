package com.fintechplatform.paycore.payment.repository;

import com.fintechplatform.paycore.payment.entity.Payment;
import com.fintechplatform.paycore.payment.enums.PaymentStatus;
import com.fintechplatform.paycore.payment.enums.PaymentType;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByCustomerIdAndIdempotencyKey(UUID customerId, String idempotencyKey);

    Optional<Payment> findByIdAndCustomerId(UUID id, UUID customerId);

    Optional<Payment> findByReferenceAndCustomerId(String reference, UUID customerId);

    Optional<Payment> findByReference(String reference);

    Page<Payment> findByStatus(PaymentStatus status, Pageable pageable);

    Optional<Payment> findByProviderAndExternalReference(String provider, String externalReference);

    List<Payment> findByTypeAndProviderAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            PaymentType type, String provider, Instant from, Instant to
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.id = :paymentId")
    Optional<Payment> findByIdForUpdate(@Param("paymentId") UUID paymentId);

    @Query("""
            SELECT p.id FROM Payment p
            WHERE p.status IN :statuses AND p.updatedAt < :before
            ORDER BY p.createdAt
            """)
    List<UUID> findStale(
            @Param("statuses") Collection<PaymentStatus> statuses,
            @Param("before") Instant before,
            Limit limit
    );

    @Query("""
            SELECT p.id FROM Payment p
            WHERE p.status = com.fintechplatform.paycore.payment.enums.PaymentStatus.UNKNOWN
              AND p.nextCheckAt <= :now
            ORDER BY p.nextCheckAt
            """)
    List<UUID> findDueStatusChecks(@Param("now") Instant now, Limit limit);
}
