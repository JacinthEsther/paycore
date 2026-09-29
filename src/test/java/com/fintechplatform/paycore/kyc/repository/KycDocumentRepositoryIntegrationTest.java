package com.fintechplatform.paycore.kyc.repository;

import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
import com.fintechplatform.paycore.kyc.entity.KycDocument;
import com.fintechplatform.paycore.kyc.entity.KycProfile;
import com.fintechplatform.paycore.kyc.enums.KycDocumentType;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@Transactional
class KycDocumentRepositoryIntegrationTest {

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
    private KycDocumentRepository kycDocumentRepository;

    @Autowired
    private KycProfileRepository kycProfileRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private EntityManager entityManager;

    private KycProfile profile;

    @BeforeEach
    void setUp() {

        Customer customer =
                customerRepository.saveAndFlush(
                        Customer.create(
                                "Esther",
                                "Test",
                                "esther@example.com",
                                "+2348012345678"
                        )
                );

        profile =
                kycProfileRepository.saveAndFlush(
                        KycProfile.create(customer)
                );
    }

    @Test
    void shouldSaveDocumentsForProfile() {

        KycDocument passport =
                kycDocumentRepository.saveAndFlush(
                        KycDocument.create(
                                profile,
                                KycDocumentType.PASSPORT,
                                "kyc/customer/passport/front",
                                "A12345678"
                        )
                );

        kycDocumentRepository.saveAndFlush(
                KycDocument.create(
                        profile,
                        KycDocumentType.PROOF_OF_ADDRESS,
                        "kyc/customer/utility-bill",
                        null
                )
        );

        entityManager.clear();

        assertThat(passport.getId().version()).isEqualTo(7);

        assertThat(kycDocumentRepository.findByKycProfileOrderByCreatedAtAscIdAsc(profile))
                .extracting(
                        KycDocument::getDocumentType,
                        KycDocument::getStorageKey
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                KycDocumentType.PASSPORT,
                                "kyc/customer/passport/front"
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                KycDocumentType.PROOF_OF_ADDRESS,
                                "kyc/customer/utility-bill"
                        )
                );
    }

    @Test
    void shouldCheckDocumentTypePresence() {

        kycDocumentRepository.saveAndFlush(
                KycDocument.create(
                        profile,
                        KycDocumentType.NATIONAL_ID,
                        "kyc/customer/nin-slip",
                        null
                )
        );

        assertThat(kycDocumentRepository.existsByKycProfileAndDocumentType(
                profile,
                KycDocumentType.NATIONAL_ID
        )).isTrue();

        assertThat(kycDocumentRepository.existsByKycProfileAndDocumentType(
                profile,
                KycDocumentType.PASSPORT
        )).isFalse();
    }
}
