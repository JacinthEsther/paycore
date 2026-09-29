package com.fintechplatform.paycore.identity.repository;

import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.identity.entity.LoginSession;
import com.fintechplatform.paycore.identity.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository
        extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findByFamilyIdAndRevokedAtIsNull(UUID familyId);

    List<RefreshToken> findBySessionAndRevokedAtIsNull(LoginSession session);

    List<RefreshToken> findByCustomerAndRevokedAtIsNull(Customer customer);
}
