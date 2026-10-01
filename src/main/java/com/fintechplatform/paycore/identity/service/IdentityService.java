package com.fintechplatform.paycore.identity.service;

import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.identity.entity.Identity;
import com.fintechplatform.paycore.identity.enums.IdentityProvider;
import com.fintechplatform.paycore.identity.exception.PasswordIdentityNotFoundException;
import com.fintechplatform.paycore.identity.repository.IdentityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class IdentityService {
    private final IdentityRepository identityRepository;

    public IdentityService(IdentityRepository identityRepository) {
        this.identityRepository = identityRepository;
    }


    /**
     * One password identity per customer is enforced by the unique index
     * uk_identity_customer_provider (V3), not by a query before the insert.
     */
    @Transactional
    public Identity createPasswordIdentity(
            Customer customer,
            String passwordHash
    ) {
        Identity identity =
                Identity.createPasswordIdentity(
                        customer,
                        customer.getEmail(),
                        passwordHash
                );

        return identityRepository.save(identity);
    }



    @Transactional(readOnly = true)
    public Identity findPasswordIdentity(Customer customer) {
        return identityRepository.findByCustomerAndProvider(customer, IdentityProvider.PASSWORD).orElseThrow(PasswordIdentityNotFoundException::new);
    }

    /**
     * Keeps a password identity's subject equal to the customer's email.
     * A customer without one (Google only) has nothing to update.
     */
    @Transactional
    public void changePasswordIdentitySubject(Customer customer, String email) {
        identityRepository
                .findByCustomerAndProvider(customer, IdentityProvider.PASSWORD)
                .ifPresent(identity -> identity.changeProviderSubject(email));
    }

    @Transactional(readOnly = true)
    public Optional<Identity> findGoogleIdentity(String googleSubject) {
        return identityRepository.findByProviderAndProviderSubject(IdentityProvider.GOOGLE, googleSubject);
    }

    @Transactional(readOnly = true)
    public Optional<Identity> findIdentity(Customer customer, IdentityProvider provider) {
        return identityRepository.findByCustomerAndProvider(customer, provider);
    }

    @Transactional
    public Identity createGoogleIdentity(Customer customer, String googleSubject) {
        return identityRepository.save(Identity.createGoogleIdentity(customer, googleSubject));
    }

    @Transactional
    public void disable(Identity identity) {
        identity.disable();
        identityRepository.save(identity);
    }

    @Transactional
    public void enable(Identity identity) {
        identity.enable();
        identityRepository.save(identity);
    }
}