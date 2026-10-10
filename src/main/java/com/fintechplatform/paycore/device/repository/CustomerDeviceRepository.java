package com.fintechplatform.paycore.device.repository;

import com.fintechplatform.paycore.device.entity.CustomerDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomerDeviceRepository extends JpaRepository<CustomerDevice, UUID> {

    Optional<CustomerDevice> findByCustomerIdAndDeviceHash(UUID customerId, String deviceHash);

    Optional<CustomerDevice> findByIdAndCustomerId(UUID id, UUID customerId);

    List<CustomerDevice> findByCustomerIdOrderByLastSeenAtDesc(UUID customerId);

    boolean existsByCustomerIdAndTrustedAtIsNotNull(UUID customerId);

    @Query("SELECT s.device FROM LoginSession s WHERE s.id = :sessionId")
    Optional<CustomerDevice> findBySessionId(@Param("sessionId") UUID sessionId);

    @Query("""
            SELECT MAX(d.coolingOffUntil) FROM CustomerDevice d
            WHERE d.customerId = :customerId
              AND d.coolingOffUntil > :now
            """)
    Optional<Instant> findCoolingOffUntil(@Param("customerId") UUID customerId, @Param("now") Instant now);

    @Query(value = """
            SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(CAST(:customerId AS text), 0))) AS locked
            """,
            nativeQuery = true)
    int lockCustomerDevices(@Param("customerId") UUID customerId);
}
