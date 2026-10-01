package com.fintechplatform.paycore.customer.entity;

import com.fintechplatform.paycore.authorization.entity.Role;
import com.fintechplatform.paycore.customer.enums.CustomerStatus;
import com.fintechplatform.paycore.common.persistence.UuidV7;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(
        name = "customers",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_customers_email",
                        columnNames = "email"
                ),
                @UniqueConstraint(
                        name = "uk_customers_phone",
                        columnNames = "phone_number"
                )
        }
)
public class Customer {

    @Id
    @UuidV7
    private UUID id;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(nullable = false, length = 255)
    private String email;

    /** Null for a customer created through Google sign-in until they add one. */
    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CustomerStatus status;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "phone_verified", nullable = false)
    private boolean phoneVerified;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "customer_roles",
            joinColumns = @JoinColumn(name = "customer_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    private Set<Role> roles = new HashSet<>();

    protected Customer() {
    }

    public static Customer create(
            String firstName,
            String lastName,
            String email,
            String phoneNumber
    ) {
        Customer customer = new Customer();

        customer.firstName = firstName;
        customer.lastName = lastName;
        customer.email = email;
        customer.phoneNumber = phoneNumber;
        customer.status = CustomerStatus.PENDING_VERIFICATION;
        customer.emailVerified = false;
        customer.phoneVerified = false;
        customer.createdAt = Instant.now();
        customer.updatedAt = customer.createdAt;

        return customer;
    }

    public UUID getId() {
        return id;
    }

    public void updateProfile(
            String firstName,
            String lastName
    ) {

        if (status == CustomerStatus.CLOSED) {
            throw new IllegalStateException(
                    "Closed customers cannot update their profile"
            );
        }

        this.firstName = firstName;
        this.lastName = lastName;
        touch();
    }

    public void changeEmail(String email) {

        if (status == CustomerStatus.CLOSED) {
            throw new IllegalStateException(
                    "Closed customers cannot change their email"
            );
        }

        this.email = email;

        this.emailVerified = false;

        touch();
    }

    public void changePhoneNumber(String phoneNumber) {

        if (status == CustomerStatus.CLOSED) {
            throw new IllegalStateException(
                    "Closed customers cannot change their phone number"
            );
        }

        this.phoneNumber = phoneNumber;

        this.phoneVerified = false;

        touch();
    }

    public void verifyEmail() {

        if (status == CustomerStatus.CLOSED) {
            throw new IllegalStateException(
                    "Closed customers cannot verify email"
            );
        }

        this.emailVerified = true;
        touch();
    }

    public void verifyPhone() {

        if (status == CustomerStatus.CLOSED) {
            throw new IllegalStateException(
                    "Closed customers cannot verify phone"
            );
        }

        this.phoneVerified = true;
        touch();
    }

    public void activate() {

        if (status != CustomerStatus.PENDING_VERIFICATION) {
            throw new IllegalStateException(
                    "Only pending customers can be activated"
            );
        }

        if (!emailVerified) {
            throw new IllegalStateException(
                    "Email must be verified"
            );
        }

        this.status = CustomerStatus.ACTIVE;
        touch();
    }

    public void suspend() {

        if (status != CustomerStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Only active customers can be suspended"
            );
        }

        this.status = CustomerStatus.SUSPENDED;
        touch();
    }

    public void reactivate() {

        if (status != CustomerStatus.SUSPENDED) {
            throw new IllegalStateException(
                    "Only suspended customers can be reactivated"
            );
        }

        this.status = CustomerStatus.ACTIVE;
        touch();
    }

    public void close() {

        if (status == CustomerStatus.CLOSED) {
            throw new IllegalStateException(
                    "Customer account is already closed"
            );
        }

        this.status = CustomerStatus.CLOSED;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getEmail() {
        return email;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public CustomerStatus getStatus() {
        return status;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public boolean isPhoneVerified() {
        return phoneVerified;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Low-level state change only. Application code must go through
     * RoleAssignmentService so the change is recorded in the audit trail.
     */
    public void assignRole(Role role) {
        roles.add(role);
    }

    public void removeRole(Role role) {
        roles.remove(role);
    }

    public Set<Role> getRoles() {
        return Collections.unmodifiableSet(roles);
    }
}
