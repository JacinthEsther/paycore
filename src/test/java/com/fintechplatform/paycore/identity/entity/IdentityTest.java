package com.fintechplatform.paycore.identity.entity;

import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.identity.enums.IdentityProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityTest {

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
    void shouldCreateEnabledPasswordIdentity() {

        Identity identity =
                Identity.createPasswordIdentity(
                        customer,
                        "esther@example.com",
                        "bcrypt-hash"
                );

        assertThat(identity.getCustomer())
                .isSameAs(customer);

        assertThat(identity.getProvider())
                .isEqualTo(IdentityProvider.PASSWORD);

        assertThat(identity.getProviderSubject())
                .isEqualTo("esther@example.com");

        assertThat(identity.getPasswordHash())
                .isEqualTo("bcrypt-hash");

        assertThat(identity.isEnabled())
                .isTrue();

        assertThat(identity.getCreatedAt())
                .isNotNull()
                .isEqualTo(identity.getUpdatedAt());
    }

    @Test
    void shouldDisableIdentity() {

        Identity identity = newIdentity();

        identity.disable();

        assertThat(identity.isEnabled())
                .isFalse();

        assertThat(identity.getUpdatedAt())
                .isAfterOrEqualTo(identity.getCreatedAt());
    }

    @Test
    void shouldReEnableIdentity() {

        Identity identity = newIdentity();

        identity.disable();
        identity.enable();

        assertThat(identity.isEnabled())
                .isTrue();
    }

    private Identity newIdentity() {

        return Identity.createPasswordIdentity(
                customer,
                "esther@example.com",
                "bcrypt-hash"
        );
    }
}
