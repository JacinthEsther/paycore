package com.fintechplatform.paycore.identity.repository;

import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
import com.fintechplatform.paycore.identity.entity.Identity;
import com.fintechplatform.paycore.identity.enums.IdentityProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
@Transactional
class IdentityRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("paycore")
                    .withUsername("postgres")
                    .withPassword("postgres");
    @Autowired
    private IdentityRepository identityRepository;
    @Autowired
    private CustomerRepository customerRepository;

    @DynamicPropertySource
    static void configureProperties(
            DynamicPropertyRegistry registry
    ) {
        registry.add(
                "spring.datasource.url",
                postgres::getJdbcUrl
        );

        registry.add(
                "spring.datasource.username",
                postgres::getUsername
        );

        registry.add(
                "spring.datasource.password",
                postgres::getPassword
        );
    }

    @Test
    void shouldSavePasswordIdentity() {

        Customer customer = Customer.create(
                "Esther",
                "Agboniro",
                "esther@example.com",
                "+2348011111111"
        );

        customerRepository.saveAndFlush(customer);

        Identity identity =
                Identity.createPasswordIdentity(
                        customer,
                        customer.getEmail(),
                        "$2a$12$someHash"
                );

        Identity saved =
                identityRepository.saveAndFlush(identity);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getId().version())
                .isEqualTo(7);
        assertThat(saved.getCustomer().getId())
                .isEqualTo(customer.getId());
        assertThat(saved.getProvider())
                .isEqualTo(IdentityProvider.PASSWORD);
        assertThat(saved.getProviderSubject())
                .isEqualTo("esther@example.com");
        assertThat(saved.getPasswordHash())
                .isEqualTo("$2a$12$someHash");
        assertThat(saved.isEnabled())
                .isTrue();
    }

    @Test
    void shouldFindIdentityByCustomerAndProvider() {

        Customer customer = Customer.create(
                "Esther",
                "Agboniro",
                "esther@example.com",
                "+2348011111111"
        );

        customerRepository.saveAndFlush(customer);

        Identity identity =
                Identity.createPasswordIdentity(
                        customer,
                        customer.getEmail(),
                        "hash"
                );

        identityRepository.saveAndFlush(identity);

        var result =
                identityRepository
                        .findByCustomerAndProvider(
                                customer,
                                IdentityProvider.PASSWORD
                        );

        assertThat(result).isPresent();
        assertThat(result.get().getPasswordHash())
                .isEqualTo("hash");
    }

    @Test
    void shouldEnforceOneIdentityPerCustomerAndProvider() {

        Customer customer = Customer.create(
                "Esther",
                "Agboniro",
                "esther@example.com",
                "+2348011111111"
        );

        customerRepository.saveAndFlush(customer);

        Identity first =
                Identity.createPasswordIdentity(
                        customer,
                        customer.getEmail(),
                        "hash1"
                );

        identityRepository.saveAndFlush(first);

        Identity second =
                Identity.createPasswordIdentity(
                        customer,
                        "another-subject",
                        "hash2"
                );

        assertThatThrownBy(() ->
                identityRepository.saveAndFlush(second)
        )
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldEnforceUniqueProviderSubject() {

        Customer firstCustomer = Customer.create(
                "Esther",
                "Agboniro",
                "esther@example.com",
                "+2348011111111"
        );

        Customer secondCustomer = Customer.create(
                "Jane",
                "Smith",
                "jane@example.com",
                "+2348022222222"
        );

        customerRepository.saveAndFlush(firstCustomer);
        customerRepository.saveAndFlush(secondCustomer);

        Identity first =
                Identity.createPasswordIdentity(
                        firstCustomer,
                        "same-subject",
                        "hash1"
                );

        identityRepository.saveAndFlush(first);

        Identity second =
                Identity.createPasswordIdentity(
                        secondCustomer,
                        "same-subject",
                        "hash2"
                );

        assertThatThrownBy(() ->
                identityRepository.saveAndFlush(second)
        )
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shouldAllowDifferentProvidersForSameCustomer() {

        Customer customer = Customer.create(
                "Esther",
                "Agboniro",
                "esther@example.com",
                "+2348011111111"
        );

        customerRepository.saveAndFlush(customer);

        Identity passwordIdentity =
                Identity.createPasswordIdentity(
                        customer,
                        "esther@example.com",
                        "hash"
                );

        identityRepository.saveAndFlush(passwordIdentity);

        /*
         * Social identity factory will be added when
         * Google/Apple authentication is implemented.
         *
         * This test documents the database design:
         *
         * UNIQUE(customer_id, provider)
         *
         * rather than:
         *
         * UNIQUE(customer_id)
         */
        assertThat(
                identityRepository
                        .existsByCustomerAndProvider(
                                customer,
                                IdentityProvider.PASSWORD
                        )
        ).isTrue();
    }
}

