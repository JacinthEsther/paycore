package com.fintechplatform.paycore.kyc.repository;

import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
import com.fintechplatform.paycore.kyc.entity.KycProfile;
import com.fintechplatform.paycore.kyc.enums.KycStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
@Transactional
class KycProfileRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("paycore")
                    .withUsername("postgres")
                    .withPassword("postgres");

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

    @Autowired
    private KycProfileRepository kycProfileRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private EntityManager entityManager;

    private Customer customer;

    @BeforeEach
    void setUp() {

        customer =
                customerRepository.saveAndFlush(
                        Customer.create(
                                "Esther",
                                "Test",
                                "esther@example.com",
                                "+2348012345678"
                        )
                );
    }

    @Test
    void shouldSaveAndFindProfileByCustomer() {

        KycProfile saved =
                kycProfileRepository.saveAndFlush(
                        KycProfile.create(customer)
                );

        entityManager.clear();

        Optional<KycProfile> result =
                kycProfileRepository.findByCustomer(
                        customerRepository.getReferenceById(customer.getId())
                );

        assertThat(result).isPresent();

        assertThat(saved.getId().version()).isEqualTo(7);

        assertThat(result.get().getId()).isEqualTo(saved.getId());

        assertThat(result.get().getCustomer().getId())
                .isEqualTo(customer.getId());

        assertThat(result.get().getStatus())
                .isEqualTo(KycStatus.NOT_STARTED);
    }

    @Test
    void shouldPersistStatusTransitions() {

        KycProfile profile =
                kycProfileRepository.saveAndFlush(
                        KycProfile.create(customer)
                );

        profile.start();
        profile.submit();
        profile.startReview();

        kycProfileRepository.saveAndFlush(profile);

        entityManager.clear();

        assertThat(kycProfileRepository.findById(profile.getId()))
                .get()
                .extracting(KycProfile::getStatus)
                .isEqualTo(KycStatus.UNDER_REVIEW);
    }

    @Test
    void shouldPersistReviewDecision() {

        KycProfile profile =
                kycProfileRepository.saveAndFlush(
                        KycProfile.create(customer)
                );

        profile.start();
        profile.submit();
        profile.startReview();
        profile.requestAdditionalInformation(
                customer.getId(),
                "Please provide a clearer proof of address"
        );

        kycProfileRepository.saveAndFlush(profile);

        entityManager.clear();

        KycProfile found =
                kycProfileRepository.findById(profile.getId()).orElseThrow();

        assertThat(found.getStatus())
                .isEqualTo(KycStatus.ADDITIONAL_INFO_REQUIRED);
        assertThat(found.getReviewReason())
                .isEqualTo("Please provide a clearer proof of address");
        assertThat(found.getReviewedBy()).isEqualTo(customer.getId());
        assertThat(found.getReviewedAt()).isNotNull();
    }

    @Test
    void shouldReportWhetherCustomerHasProfile() {

        assertThat(kycProfileRepository.existsByCustomer(customer))
                .isFalse();

        kycProfileRepository.saveAndFlush(KycProfile.create(customer));

        assertThat(kycProfileRepository.existsByCustomer(customer))
                .isTrue();
    }

    @Test
    void shouldAllowOnlyOneProfilePerCustomer() {

        kycProfileRepository.saveAndFlush(KycProfile.create(customer));

        assertThatThrownBy(() ->
                kycProfileRepository.saveAndFlush(KycProfile.create(customer))
        )
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
