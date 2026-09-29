package com.fintechplatform.paycore.kyc.repository;

import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.kyc.entity.KycProfile;
import com.fintechplatform.paycore.kyc.enums.KycStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface KycProfileRepository
        extends JpaRepository<KycProfile, UUID> {

    Optional<KycProfile> findByCustomer(Customer customer);

    /**
     * Locks the profile row (SELECT ... FOR UPDATE) until the transaction
     * ends, so concurrent BVN checks for one customer run one at a time
     * and cannot both slip under the retry limit.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM KycProfile p WHERE p.customer = :customer")
    Optional<KycProfile> findByCustomerForUpdate(
            @Param("customer") Customer customer
    );

    /**
     * Same row lock by profile id, used by admin actions that must not
     * interleave with an in-flight BVN check (e.g. resetting attempts).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM KycProfile p WHERE p.id = :id")
    Optional<KycProfile> findByIdForUpdate(@Param("id") UUID id);

    boolean existsByCustomer(Customer customer);

    Page<KycProfile> findByStatus(KycStatus status, Pageable pageable);
}
