package com.fintechplatform.paycore.identity.service;

import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.identity.entity.Identity;
import com.fintechplatform.paycore.identity.enums.IdentityProvider;
import com.fintechplatform.paycore.identity.exception.PasswordIdentityNotFoundException;
import com.fintechplatform.paycore.identity.repository.IdentityRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdentityServiceTest {

    @Mock
    private IdentityRepository identityRepository;

    @InjectMocks
    private IdentityService identityService;

    @Test
    void shouldCreatePasswordIdentity() {

        Customer customer = Customer.create(
                "Esther",
                "Agboniro",
                "esther@example.com",
                "+2348011111111"
        );

        Identity identity =
                Identity.createPasswordIdentity(
                        customer,
                        customer.getEmail(),
                        "hashed-password"
                );

        when(identityRepository.save(any(Identity.class)))
                .thenReturn(identity);

        Identity result =
                identityService.createPasswordIdentity(
                        customer,
                        "hashed-password"
                );

        assertThat(result)
                .isNotNull();

        assertThat(result.getProvider())
                .isEqualTo(IdentityProvider.PASSWORD);

        assertThat(result.getPasswordHash())
                .isEqualTo("hashed-password");

        verify(identityRepository)
                .save(any(Identity.class));
    }

    @Test
    void shouldFindPasswordIdentity() {

        Customer customer = Customer.create(
                "Esther",
                "Agboniro",
                "esther@example.com",
                "+2348011111111"
        );

        Identity identity =
                Identity.createPasswordIdentity(
                        customer,
                        customer.getEmail(),
                        "hashed-password"
                );

        when(identityRepository
                .findByCustomerAndProvider(
                        customer,
                        IdentityProvider.PASSWORD
                ))
                .thenReturn(Optional.of(identity));

        Identity result =
                identityService.findPasswordIdentity(
                        customer
                );

        assertThat(result)
                .isSameAs(identity);
    }

    @Test
    void shouldThrowWhenPasswordIdentityDoesNotExist() {

        Customer customer = Customer.create(
                "Unknown",
                "User",
                "unknown@example.com",
                "+2348022222222"
        );

        when(identityRepository
                .findByCustomerAndProvider(
                        customer,
                        IdentityProvider.PASSWORD
                ))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                identityService.findPasswordIdentity(
                        customer
                )
        )
                .isInstanceOf(PasswordIdentityNotFoundException.class)
                .hasMessage("Password identity not found");
    }

    @Test
    void shouldDisableIdentity() {

        Customer customer = Customer.create(
                "Esther",
                "Agboniro",
                "esther@example.com",
                "+2348011111111"
        );

        Identity identity =
                Identity.createPasswordIdentity(
                        customer,
                        customer.getEmail(),
                        "hashed-password"
                );

        identityService.disable(identity);

        assertThat(identity.isEnabled())
                .isFalse();

        verify(identityRepository)
                .save(identity);
    }

    @Test
    void shouldEnableIdentity() {

        Customer customer = Customer.create(
                "Esther",
                "Agboniro",
                "esther@example.com",
                "+2348011111111"
        );

        Identity identity =
                Identity.createPasswordIdentity(
                        customer,
                        customer.getEmail(),
                        "hashed-password"
                );

        identity.disable();

        identityService.enable(identity);

        assertThat(identity.isEnabled())
                .isTrue();

        verify(identityRepository)
                .save(identity);
    }
}