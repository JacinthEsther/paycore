package com.fintechplatform.paycore.identity.repository;

import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.identity.dto.SessionAccess;
import com.fintechplatform.paycore.identity.entity.LoginSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LoginSessionRepository
        extends JpaRepository<LoginSession, UUID> {

    Optional<LoginSession> findBySessionTokenHash(
            String sessionTokenHash
    );

    List<LoginSession> findByCustomerAndRevokedAtIsNull(
            Customer customer
    );

    /**
     * The session and customer state checked on every authenticated
     * request, in one query.
     */
    @Query("""
            select new com.fintechplatform.paycore.identity.dto.SessionAccess(
                c.id, c.status, s.expiresAt, s.revokedAt
            )
            from LoginSession s join s.customer c
            where s.id = :id
            """)
    Optional<SessionAccess> findAccessById(@Param("id") UUID id);
}
