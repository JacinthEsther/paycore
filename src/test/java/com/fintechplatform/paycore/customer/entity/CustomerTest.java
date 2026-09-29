package com.fintechplatform.paycore.customer.entity;

import com.fintechplatform.paycore.authorization.entity.Role;
import com.fintechplatform.paycore.authorization.entity.RoleName;
import com.fintechplatform.paycore.customer.enums.CustomerStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerTest {

    private Customer customer;

    @BeforeEach
    void setUp() {

        customer = Customer.create(
                "Esther",
                "Agboniro",
                "esther@example.com",
                "+2348012345678"
        );
    }

    @Test
    void shouldCreatePendingUnverifiedCustomer() {

        assertThat(customer.getStatus())
                .isEqualTo(CustomerStatus.PENDING_VERIFICATION);

        assertThat(customer.isEmailVerified()).isFalse();
        assertThat(customer.isPhoneVerified()).isFalse();

        assertThat(customer.getCreatedAt())
                .isNotNull()
                .isEqualTo(customer.getUpdatedAt());

        assertThat(customer.getRoles()).isEmpty();
    }

    @Test
    void shouldActivateOnlyAfterEmailVerification() {

        assertThatThrownBy(customer::activate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Email must be verified");

        customer.verifyEmail();
        customer.activate();

        assertThat(customer.getStatus())
                .isEqualTo(CustomerStatus.ACTIVE);
    }

    @Test
    void shouldSuspendAndReactivateActiveCustomer() {

        customer.verifyEmail();
        customer.activate();

        customer.suspend();

        assertThat(customer.getStatus())
                .isEqualTo(CustomerStatus.SUSPENDED);

        customer.reactivate();

        assertThat(customer.getStatus())
                .isEqualTo(CustomerStatus.ACTIVE);
    }

    @Test
    void shouldNotSuspendPendingCustomer() {

        assertThatThrownBy(customer::suspend)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Only active customers can be suspended");
    }

    @Test
    void shouldResetEmailVerificationWhenEmailChanges() {

        customer.verifyEmail();

        customer.changeEmail("new@example.com");

        assertThat(customer.getEmail())
                .isEqualTo("new@example.com");

        assertThat(customer.isEmailVerified())
                .isFalse();
    }

    @Test
    void shouldResetPhoneVerificationWhenPhoneChanges() {

        customer.verifyPhone();

        customer.changePhoneNumber("+2348098765432");

        assertThat(customer.isPhoneVerified())
                .isFalse();
    }

    @Test
    void shouldRejectChangesOnceClosed() {

        customer.close();

        assertThat(customer.getStatus())
                .isEqualTo(CustomerStatus.CLOSED);

        assertThatThrownBy(() -> customer.updateProfile("Jane", "Smith"))
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(() -> customer.changeEmail("x@example.com"))
                .isInstanceOf(IllegalStateException.class);

        assertThatThrownBy(customer::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Customer account is already closed");
    }

    @Test
    void shouldAssignAndRemoveRoles() {

        Role role = new Role(RoleName.CUSTOMER);

        customer.assignRole(role);
        customer.assignRole(role);

        assertThat(customer.getRoles())
                .containsExactly(role);

        customer.removeRole(role);

        assertThat(customer.getRoles())
                .isEmpty();
    }

    @Test
    void shouldNotExposeMutableRoles() {

        assertThatThrownBy(() ->
                customer.getRoles().add(new Role(RoleName.ADMIN))
        )
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
