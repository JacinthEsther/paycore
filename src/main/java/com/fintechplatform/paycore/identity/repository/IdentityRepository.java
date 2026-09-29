package com.fintechplatform.paycore.identity.repository;

import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.identity.entity.Identity;
import com.fintechplatform.paycore.identity.enums.IdentityProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface IdentityRepository
        extends JpaRepository<Identity, UUID> {

    Optional<Identity> findByCustomerAndProvider(
            Customer customer,
            IdentityProvider provider
    );

    boolean existsByCustomerAndProvider(
            Customer customer,
            IdentityProvider provider
    );

    Optional<Identity> findByProviderAndProviderSubject(
            IdentityProvider provider,
            String providerSubject
    );

    boolean existsByProviderAndProviderSubject(
            IdentityProvider provider,
            String providerSubject
    );
}

