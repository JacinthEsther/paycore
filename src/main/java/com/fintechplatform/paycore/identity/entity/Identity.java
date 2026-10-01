package com.fintechplatform.paycore.identity.entity;

import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.identity.enums.IdentityProvider;
import com.fintechplatform.paycore.common.persistence.UuidV7;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "identities",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_identity_provider_subject",
                        columnNames = {
                                "provider",
                                "provider_subject"
                        }
                )
        }
)
public class Identity {

    @Id
    @UuidV7
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "customer_id",
            nullable = false
    )
    private Customer customer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private IdentityProvider provider;

    @Column(
            name = "provider_subject",
            nullable = false,
            length = 255
    )
    private String providerSubject;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Identity() {
    }


    public static Identity createPasswordIdentity(
            Customer customer,
            String providerSubject,
            String passwordHash
    ) {
        Identity identity = new Identity();

        identity.customer = customer;
        identity.provider = IdentityProvider.PASSWORD;
        identity.providerSubject = providerSubject;
        identity.passwordHash = passwordHash;
        identity.enabled = true;

        identity.createdAt = Instant.now();
        identity.updatedAt = identity.createdAt;

        return identity;
    }



    /**
     * Signs in with Google. The subject is Google's stable account id
     * ("sub"), never the email, which a Google user can change.
     */
    public static Identity createGoogleIdentity(
            Customer customer,
            String googleSubject
    ) {
        Identity identity = new Identity();

        identity.customer = customer;
        identity.provider = IdentityProvider.GOOGLE;
        identity.providerSubject = googleSubject;
        identity.enabled = true;

        identity.createdAt = Instant.now();
        identity.updatedAt = identity.createdAt;

        return identity;
    }

    /** For a password identity, whose subject is the customer's email. */
    public void changeProviderSubject(String providerSubject) {
        this.providerSubject = providerSubject;
        touch();
    }

    public void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
        touch();
    }

    public void disable() {
        this.enabled = false;
        touch();
    }

    public void enable() {
        this.enabled = true;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Customer getCustomer() {
        return customer;
    }

    public IdentityProvider getProvider() {
        return provider;
    }

    public String getProviderSubject() {
        return providerSubject;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}