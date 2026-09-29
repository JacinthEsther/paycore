package com.fintechplatform.paycore.kyc.repository;

import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
import com.fintechplatform.paycore.kyc.entity.KycProfile;
import com.fintechplatform.paycore.kyc.entity.KycVerification;
import com.fintechplatform.paycore.kyc.enums.KycVerificationType;
import com.fintechplatform.paycore.kyc.enums.VerificationResult;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest
@Transactional
class KycVerificationRepositoryIntegrationTest {

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
    private KycVerificationRepository kycVerificationRepository;

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
    void shouldFindOnlyRecentFailedBvnAttempts() {

        KycVerification oldFailure =
                attempt(KycVerificationType.BVN, VerificationResult.FAILED);
        ReflectionTestUtils.setField(
                oldFailure,
                "createdAt",
                Instant.now().minus(Duration.ofHours(25))
        );
        kycVerificationRepository.saveAndFlush(oldFailure);

        KycVerification recentFailure =
                kycVerificationRepository.saveAndFlush(
                        attempt(KycVerificationType.BVN, VerificationResult.FAILED)
                );

        kycVerificationRepository.saveAndFlush(
                attempt(KycVerificationType.BVN, VerificationResult.PASSED)
        );

        kycVerificationRepository.saveAndFlush(
                attempt(KycVerificationType.NIN, VerificationResult.FAILED)
        );

        entityManager.clear();

        assertThat(
                kycVerificationRepository
                        .findByKycProfileAndVerificationTypeAndResultAndCreatedAtAfterOrderByCreatedAtAscIdAsc(
                                profile,
                                KycVerificationType.BVN,
                                VerificationResult.FAILED,
                                Instant.now().minus(Duration.ofHours(24))
                        )
        )
                .extracting(KycVerification::getId)
                .containsExactly(recentFailure.getId());
    }

    @Test
    void shouldPageBvnHistoryNewestFirst() {

        for (int hoursAgo = 5; hoursAgo >= 1; hoursAgo--) {
            KycVerification attempt =
                    attempt(KycVerificationType.BVN, VerificationResult.FAILED);
            ReflectionTestUtils.setField(
                    attempt,
                    "createdAt",
                    Instant.now().minus(Duration.ofHours(hoursAgo))
            );
            kycVerificationRepository.saveAndFlush(attempt);
        }

        // Other verification types are not part of BVN history.
        kycVerificationRepository.saveAndFlush(
                attempt(KycVerificationType.NIN, VerificationResult.FAILED)
        );

        entityManager.clear();

        Sort newestFirst =
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

        Page<KycVerification> first =
                kycVerificationRepository.findByKycProfileAndVerificationType(
                        profile,
                        KycVerificationType.BVN,
                        PageRequest.of(0, 2, newestFirst)
                );

        Page<KycVerification> last =
                kycVerificationRepository.findByKycProfileAndVerificationType(
                        profile,
                        KycVerificationType.BVN,
                        PageRequest.of(2, 2, newestFirst)
                );

        assertThat(first.getTotalElements()).isEqualTo(5);
        assertThat(first.getTotalPages()).isEqualTo(3);
        assertThat(first.hasNext()).isTrue();

        assertThat(first.getContent())
                .extracting(KycVerification::getCreatedAt)
                .isSortedAccordingTo(java.util.Comparator.reverseOrder())
                .hasSize(2);

        assertThat(last.getContent()).hasSize(1);
        assertThat(last.hasNext()).isFalse();

        // The last page holds the oldest attempt.
        assertThat(last.getContent().getFirst().getCreatedAt())
                .isBefore(first.getContent().getLast().getCreatedAt());
    }

    @Test
    void shouldCountBvnChecksPerNetworkAcrossCustomers() {

        KycProfile otherProfile =
                kycProfileRepository.saveAndFlush(
                        KycProfile.create(
                                customerRepository.saveAndFlush(
                                        Customer.create(
                                                "Other",
                                                "Customer",
                                                "other@example.com",
                                                "+2348098765432"
                                        )
                                )
                        )
                );

        String network = "203.0.113.7";

        // Two customers, same network, any result: all count.
        kycVerificationRepository.saveAndFlush(KycVerification.create(
                profile, KycVerificationType.BVN, "DOJAH", null,
                VerificationResult.FAILED, null, network));
        kycVerificationRepository.saveAndFlush(KycVerification.create(
                otherProfile, KycVerificationType.BVN, "DOJAH", null,
                VerificationResult.PASSED, null, network));

        // Excluded: too old, another network, another verification type.
        KycVerification old = KycVerification.create(
                profile, KycVerificationType.BVN, "DOJAH", null,
                VerificationResult.FAILED, null, network);
        ReflectionTestUtils.setField(
                old, "createdAt", Instant.now().minus(Duration.ofHours(2)));
        kycVerificationRepository.saveAndFlush(old);

        kycVerificationRepository.saveAndFlush(KycVerification.create(
                profile, KycVerificationType.BVN, "DOJAH", null,
                VerificationResult.FAILED, null, "198.51.100.1"));
        kycVerificationRepository.saveAndFlush(KycVerification.create(
                profile, KycVerificationType.NIN, "DOJAH", null,
                VerificationResult.FAILED, null, network));

        Instant since = Instant.now().minus(Duration.ofHours(1));

        assertThat(kycVerificationRepository
                .countByVerificationTypeAndIpAddressAndCreatedAtAfter(
                        KycVerificationType.BVN, network, since))
                .isEqualTo(2);

        assertThat(kycVerificationRepository
                .findFirstByVerificationTypeAndIpAddressAndCreatedAtAfterOrderByCreatedAtAscIdAsc(
                        KycVerificationType.BVN, network, since))
                .get()
                .extracting(KycVerification::getResult)
                .isEqualTo(VerificationResult.FAILED);
    }

    @Test
    void shouldTakeNetworkAdvisoryLockInsideTransaction() {

        // Re-entrant within the same transaction; released at rollback.
        assertThat(kycVerificationRepository.lockClientNetwork("203.0.113.7"))
                .isEqualTo(1);
        assertThat(kycVerificationRepository.lockClientNetwork("203.0.113.7"))
                .isEqualTo(1);
    }

    @Test
    void shouldLockProfileRowForBvnChecks() {

        assertThat(
                kycProfileRepository.findByCustomerForUpdate(profile.getCustomer())
        )
                .get()
                .extracting(KycProfile::getId)
                .isEqualTo(profile.getId());
    }

    private KycVerification attempt(
            KycVerificationType type,
            VerificationResult result
    ) {
        return KycVerification.create(profile, type, "DOJAH", null, result, null);
    }

    @Test
    void shouldKeepEveryVerificationAttemptInOrder() {

        KycVerification failed =
                kycVerificationRepository.saveAndFlush(
                        KycVerification.create(
                                profile,
                                KycVerificationType.BVN,
                                "DOJAH",
                                null,
                                VerificationResult.FAILED,
                                "Details do not match BVN record: last_name"
                        )
                );

        kycVerificationRepository.saveAndFlush(
                KycVerification.create(
                        profile,
                        KycVerificationType.BVN,
                        "DOJAH",
                        "dojah-ref-2",
                        VerificationResult.PASSED,
                        null
                )
        );

        entityManager.clear();

        List<KycVerification> history =
                kycVerificationRepository
                        .findByKycProfileOrderByCreatedAtAscIdAsc(profile);

        assertThat(failed.getId().version()).isEqualTo(7);

        assertThat(history)
                .extracting(KycVerification::getResult)
                .containsExactly(
                        VerificationResult.FAILED,
                        VerificationResult.PASSED
                );

        assertThat(history.get(0).getReason())
                .isEqualTo("Details do not match BVN record: last_name");

        assertThat(history.get(1).getProviderReference())
                .isEqualTo("dojah-ref-2");

        assertThat(history)
                .allSatisfy(verification -> {
                    assertThat(verification.getVerificationType())
                            .isEqualTo(KycVerificationType.BVN);
                    assertThat(verification.getProvider())
                            .isEqualTo("DOJAH");
                });
    }
}
