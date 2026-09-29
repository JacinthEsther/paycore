package com.fintechplatform.paycore.customer.repository;

import com.fintechplatform.paycore.customer.entity.Customer;
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
class CustomerRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("paycore")
                    .withUsername("postgres")
                    .withPassword("postgres");
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
    void shouldSaveCustomer() {

        Customer customer = Customer.create(
                "Esther",
                "Agboniro",
                "esther@example.com",
                "+2348011111111"
        );

        Customer savedCustomer =
                customerRepository.saveAndFlush(customer);

        assertThat(savedCustomer.getId())
                .isNotNull();

        assertThat(savedCustomer.getId().version())
                .isEqualTo(7);

        assertThat(savedCustomer.getFirstName())
                .isEqualTo("Esther");

        assertThat(savedCustomer.getLastName())
                .isEqualTo("Agboniro");

        assertThat(savedCustomer.getEmail())
                .isEqualTo("esther@example.com");

        assertThat(savedCustomer.getPhoneNumber())
                .isEqualTo("+2348011111111");

        assertThat(savedCustomer.getStatus())
                .isNotNull();

        assertThat(savedCustomer.getCreatedAt())
                .isNotNull();

        assertThat(savedCustomer.getUpdatedAt())
                .isNotNull();
    }

    @Test
    void shouldEnforceUniqueEmailConstraint() {

        Customer firstCustomer =
                Customer.create(
                        "Esther",
                        "Agboniro",
                        "esther@example.com",
                        "+2348011111111"
                );

        customerRepository.saveAndFlush(firstCustomer);

        Customer secondCustomer =
                Customer.create(
                        "Jane",
                        "Smith",
                        "esther@example.com",
                        "+2348022222222"
                );

        assertThatThrownBy(() ->
                customerRepository.saveAndFlush(secondCustomer)
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                );
    }

    @Test
    void shouldEnforceUniquePhoneConstraint() {

        Customer firstCustomer =
                Customer.create(
                        "Esther",
                        "Agboniro",
                        "esther1@example.com",
                        "+2348011111111"
                );

        customerRepository.saveAndFlush(firstCustomer);

        Customer secondCustomer =
                Customer.create(
                        "Jane",
                        "Smith",
                        "jane@example.com",
                        "+2348011111111"
                );

        assertThatThrownBy(() ->
                customerRepository.saveAndFlush(secondCustomer)
        )
                .isInstanceOf(
                        DataIntegrityViolationException.class
                );
    }

    @Test
    void shouldFindCustomerByEmail() {

        Customer customer =
                Customer.create(
                        "Esther",
                        "Agboniro",
                        "esther@example.com",
                        "+2348011111111"
                );

        customerRepository.saveAndFlush(customer);

        var result =
                customerRepository.findByEmail(
                        "esther@example.com"
                );

        assertThat(result)
                .isPresent();

        assertThat(result.get().getEmail())
                .isEqualTo("esther@example.com");

        assertThat(result.get().getPhoneNumber())
                .isEqualTo("+2348011111111");
    }

    @Test
    void shouldFindCustomerByPhoneNumber() {

        Customer customer =
                Customer.create(
                        "Esther",
                        "Agboniro",
                        "esther@example.com",
                        "+2348011111111"
                );

        customerRepository.saveAndFlush(customer);

        var result =
                customerRepository.findByPhoneNumber(
                        "+2348011111111"
                );

        assertThat(result)
                .isPresent();

        assertThat(result.get().getEmail())
                .isEqualTo("esther@example.com");

        assertThat(result.get().getPhoneNumber())
                .isEqualTo("+2348011111111");
    }

    @Test
    void shouldReturnFalseWhenEmailDoesNotExist() {

        boolean exists =
                customerRepository.existsByEmail(
                        "unknown@example.com"
                );

        assertThat(exists)
                .isFalse();
    }

    @Test
    void shouldReturnFalseWhenPhoneNumberDoesNotExist() {

        boolean exists =
                customerRepository.existsByPhoneNumber(
                        "+2348099999999"
                );

        assertThat(exists)
                .isFalse();
    }
}

