package com.fintechplatform.paycore.kyc.service;

import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.customer.exception.CustomerNotFoundException;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
import com.fintechplatform.paycore.kyc.config.KycProperties;
import com.fintechplatform.paycore.kyc.dto.BvnAttemptResponse;
import com.fintechplatform.paycore.kyc.dto.BvnAttemptsResetResponse;
import com.fintechplatform.paycore.kyc.dto.BvnAttemptsResponse;
import com.fintechplatform.paycore.kyc.dto.KycResponse;
import com.fintechplatform.paycore.kyc.dto.KycVerificationResponse;
import com.fintechplatform.paycore.kyc.dto.PageInfo;
import com.fintechplatform.paycore.kyc.dto.VerifyBvnRequest;
import com.fintechplatform.paycore.kyc.entity.KycProfile;
import com.fintechplatform.paycore.kyc.entity.KycVerification;
import com.fintechplatform.paycore.kyc.enums.KycStatus;
import com.fintechplatform.paycore.kyc.enums.KycVerificationType;
import com.fintechplatform.paycore.kyc.enums.VerificationResult;
import com.fintechplatform.paycore.kyc.exception.BvnAttemptLimitExceededException;
import com.fintechplatform.paycore.kyc.exception.BvnIpRateLimitExceededException;
import com.fintechplatform.paycore.kyc.exception.InvalidKycStateException;
import com.fintechplatform.paycore.kyc.exception.KycAlreadyExistsException;
import com.fintechplatform.paycore.kyc.exception.KycIncompleteException;
import com.fintechplatform.paycore.kyc.exception.KycNotFoundException;
import com.fintechplatform.paycore.kyc.exception.KycProviderException;
import com.fintechplatform.paycore.kyc.provider.KycProvider;
import com.fintechplatform.paycore.kyc.provider.KycProviderResult;
import com.fintechplatform.paycore.kyc.provider.KycVerificationRequest;
import com.fintechplatform.paycore.kyc.repository.KycDocumentRepository;
import com.fintechplatform.paycore.kyc.repository.KycProfileRepository;
import com.fintechplatform.paycore.kyc.repository.KycVerificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KycServiceTest {

    private static final String CLIENT_IP = "203.0.113.7";

    private static final VerifyBvnRequest BVN_REQUEST =
            new VerifyBvnRequest(
                    "22222222222",
                    " Esther ",
                    " Test ",
                    "1995-01-01"
            );

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private KycProfileRepository kycProfileRepository;

    @Mock
    private KycDocumentRepository kycDocumentRepository;

    @Mock
    private KycVerificationRepository kycVerificationRepository;

    @Mock
    private KycProvider kycProvider;

    private final KycProperties kycProperties = new KycProperties();

    private KycService kycService;

    private UUID customerId;
    private Customer customer;
    private KycProfile profile;

    @BeforeEach
    void setUp() {

        kycService =
                new KycService(
                        customerRepository,
                        kycProfileRepository,
                        kycDocumentRepository,
                        kycVerificationRepository,
                        kycProvider,
                        kycProperties
                );

        customerId = UUID.randomUUID();

        customer = Customer.create(
                "Esther",
                "Test",
                "esther@example.com",
                "+2348012345678"
        );
        ReflectionTestUtils.setField(customer, "id", customerId);

        profile = KycProfile.create(customer);
        ReflectionTestUtils.setField(profile, "id", UUID.randomUUID());
    }

    // ============================================================
    // CREATE / GET
    // ============================================================

    @Test
    void shouldCreateKycProfile() {

        when(customerRepository.findById(customerId))
                .thenReturn(Optional.of(customer));

        when(kycProfileRepository.existsByCustomer(customer))
                .thenReturn(false);

        when(kycProfileRepository.save(any(KycProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        KycResponse response = kycService.createKyc(customerId);

        assertThat(response.customerId()).isEqualTo(customerId);
        assertThat(response.status()).isEqualTo(KycStatus.NOT_STARTED);
    }

    @Test
    void shouldRejectSecondKycProfile() {

        when(customerRepository.findById(customerId))
                .thenReturn(Optional.of(customer));

        when(kycProfileRepository.existsByCustomer(customer))
                .thenReturn(true);

        assertThatThrownBy(() -> kycService.createKyc(customerId))
                .isInstanceOf(KycAlreadyExistsException.class);

        verify(kycProfileRepository, never()).save(any());
    }

    @Test
    void shouldRejectKycForUnknownCustomer() {

        when(customerRepository.findById(customerId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> kycService.createKyc(customerId))
                .isInstanceOf(CustomerNotFoundException.class);
    }

    @Test
    void shouldThrowWhenKycProfileDoesNotExist() {

        when(customerRepository.findById(customerId))
                .thenReturn(Optional.of(customer));

        when(kycProfileRepository.findByCustomer(customer))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> kycService.getKyc(customerId))
                .isInstanceOf(KycNotFoundException.class);
    }

    // ============================================================
    // START / SUBMIT
    // ============================================================

    @Test
    void shouldStartKyc() {

        stubProfileForUpdate();

        KycResponse response = kycService.startKyc(customerId);

        assertThat(response.status()).isEqualTo(KycStatus.IN_PROGRESS);
        verify(kycProfileRepository).save(profile);
    }

    @Test
    void shouldResumeKycAfterInformationRequest() {

        profile.start();
        profile.submit();
        profile.startReview();
        profile.requestAdditionalInformation(UUID.randomUUID(), "More please");

        stubProfileForUpdate();

        KycResponse response = kycService.startKyc(customerId);

        assertThat(response.status()).isEqualTo(KycStatus.IN_PROGRESS);
        // The customer can still see what the reviewer asked for.
        assertThat(response.reviewReason()).isEqualTo("More please");
    }

    @Test
    void shouldNotStartKycTwice() {

        profile.start();
        stubProfileForUpdate();

        assertThatThrownBy(() -> kycService.startKyc(customerId))
                .isInstanceOf(InvalidKycStateException.class)
                .hasMessage("KYC cannot be started from status IN_PROGRESS");
    }

    @Test
    void shouldSubmitKycWithPassedBvnAndDocument() {

        profile.start();
        stubProfileForUpdate();
        stubSubmissionRequirements(true, true);

        KycResponse response = kycService.submitKyc(customerId);

        assertThat(response.status()).isEqualTo(KycStatus.SUBMITTED);
    }

    @Test
    void shouldNotSubmitWithoutPassedBvn() {

        profile.start();
        stubProfileForUpdate();
        stubSubmissionRequirements(false, true);

        assertThatThrownBy(() -> kycService.submitKyc(customerId))
                .isInstanceOf(KycIncompleteException.class)
                .hasMessage("KYC cannot be submitted yet: passed BVN or NIN verification");

        assertThat(profile.getStatus()).isEqualTo(KycStatus.IN_PROGRESS);
        verify(kycProfileRepository, never()).save(any());
    }

    @Test
    void shouldNotSubmitWithoutDocument() {

        profile.start();
        stubProfileForUpdate();
        stubSubmissionRequirements(true, false);

        assertThatThrownBy(() -> kycService.submitKyc(customerId))
                .isInstanceOf(KycIncompleteException.class)
                .hasMessage("KYC cannot be submitted yet: identity document");

        assertThat(profile.getStatus()).isEqualTo(KycStatus.IN_PROGRESS);
    }

    @Test
    void shouldListEverythingMissingOnSubmit() {

        profile.start();
        stubProfileForUpdate();
        stubSubmissionRequirements(false, false);

        assertThatThrownBy(() -> kycService.submitKyc(customerId))
                .isInstanceOfSatisfying(KycIncompleteException.class, exception ->
                        assertThat(exception.getMissing())
                                .containsExactly(
                                        "passed BVN or NIN verification",
                                        "identity document"
                                )
                );
    }

    @Test
    void shouldReportStateBeforeMissingRequirementsOnSubmit() {

        stubProfileForUpdate();

        assertThatThrownBy(() -> kycService.submitKyc(customerId))
                .isInstanceOf(InvalidKycStateException.class)
                .hasMessage("KYC cannot be submitted from status NOT_STARTED");

        verifyNoInteractions(kycDocumentRepository);
    }

    @Test
    void shouldThrowWhenSubmittingWithoutKycProfile() {

        when(customerRepository.findById(customerId))
                .thenReturn(Optional.of(customer));
        when(kycProfileRepository.findByCustomerForUpdate(customer))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> kycService.submitKyc(customerId))
                .isInstanceOf(KycNotFoundException.class);
    }

    // ============================================================
    // BVN
    // ============================================================

    @Test
    void shouldVerifyBvnSuccessfully() {

        stubProfileInProgress();
        stubProvider(VerificationResult.PASSED, null);

        KycVerificationResponse response =
                kycService.verifyBvn(customerId, BVN_REQUEST, CLIENT_IP);

        assertThat(response.result()).isEqualTo(VerificationResult.PASSED);
        assertThat(response.status()).isEqualTo(KycStatus.IN_PROGRESS);
        assertThat(response.provider()).isEqualTo("DOJAH");
    }

    @Test
    void shouldNeverMarkKycVerifiedFromProviderResultAlone() {

        stubProfileInProgress();
        stubProvider(VerificationResult.PASSED, null);

        kycService.verifyBvn(customerId, BVN_REQUEST, CLIENT_IP);

        assertThat(profile.getStatus())
                .isNotEqualTo(KycStatus.VERIFIED)
                .isEqualTo(KycStatus.IN_PROGRESS);
    }

    @Test
    void shouldSendTrimmedDetailsToProvider() {

        stubProfileInProgress();
        stubProvider(VerificationResult.PASSED, null);

        kycService.verifyBvn(customerId, BVN_REQUEST, CLIENT_IP);

        ArgumentCaptor<KycVerificationRequest> captor =
                ArgumentCaptor.forClass(KycVerificationRequest.class);

        verify(kycProvider).verifyBvn(captor.capture());

        assertThat(captor.getValue())
                .isEqualTo(new KycVerificationRequest(
                        "22222222222",
                        "Esther",
                        "Test",
                        "1995-01-01"
                ));
    }

    @Test
    void shouldRecordVerificationAttemptWithoutBvn() {

        stubProfileInProgress();
        stubProvider(VerificationResult.PASSED, null);

        kycService.verifyBvn(customerId, BVN_REQUEST, CLIENT_IP);

        ArgumentCaptor<KycVerification> captor =
                ArgumentCaptor.forClass(KycVerification.class);

        verify(kycVerificationRepository).save(captor.capture());

        KycVerification recorded = captor.getValue();

        assertThat(recorded.getKycProfile()).isSameAs(profile);
        assertThat(recorded.getVerificationType()).isEqualTo(KycVerificationType.BVN);
        assertThat(recorded.getProvider()).isEqualTo("DOJAH");
        assertThat(recorded.getProviderReference()).isEqualTo("ref-1");
        assertThat(recorded.getResult()).isEqualTo(VerificationResult.PASSED);
    }

    @Test
    void shouldKeepKycInProgressWhenBvnFails() {

        stubProfileInProgress();
        stubProvider(VerificationResult.FAILED, "Details do not match BVN record: last_name");

        KycVerificationResponse response =
                kycService.verifyBvn(customerId, BVN_REQUEST, CLIENT_IP);

        assertThat(response.result()).isEqualTo(VerificationResult.FAILED);
        assertThat(response.status()).isEqualTo(KycStatus.IN_PROGRESS);
        assertThat(response.reason()).contains("last_name");

        verify(kycVerificationRepository).save(any(KycVerification.class));
    }

    @Test
    void shouldAllowRetryAfterFailedBvn() {

        stubProfileInProgress();

        when(kycProvider.verifyBvn(any()))
                .thenReturn(result(VerificationResult.FAILED, "mismatch"))
                .thenReturn(result(VerificationResult.PASSED, null));

        kycService.verifyBvn(customerId, BVN_REQUEST, CLIENT_IP);

        KycVerificationResponse retry =
                kycService.verifyBvn(customerId, BVN_REQUEST, CLIENT_IP);

        assertThat(retry.status()).isEqualTo(KycStatus.IN_PROGRESS);

        verify(kycVerificationRepository, times(2))
                .save(any(KycVerification.class));
    }

    @Test
    void shouldKeepKycInProgressWhenProviderRequiresReview() {

        stubProfileInProgress();
        stubProvider(VerificationResult.REQUIRES_REVIEW, "Low confidence");

        KycVerificationResponse response =
                kycService.verifyBvn(customerId, BVN_REQUEST, CLIENT_IP);

        assertThat(response.status()).isEqualTo(KycStatus.IN_PROGRESS);
    }

    @Test
    void shouldNotRecordAttemptWhenProviderIsUnavailable() {

        stubProfileInProgress();

        when(kycProvider.verifyBvn(any()))
                .thenThrow(new KycProviderException("Dojah returned HTTP 424"));

        assertThatThrownBy(() -> kycService.verifyBvn(customerId, BVN_REQUEST, CLIENT_IP))
                .isInstanceOf(KycProviderException.class);

        verify(kycVerificationRepository, never()).save(any());
    }

    @Test
    void shouldNotVerifyBvnWhileUnderReview() {

        profile.start();
        profile.submit();
        profile.startReview();

        stubProfile();

        assertThatThrownBy(() -> kycService.verifyBvn(customerId, BVN_REQUEST, CLIENT_IP))
                .isInstanceOf(InvalidKycStateException.class);

        verifyNoInteractions(kycProvider);
    }

    @Test
    void shouldNotVerifyBvnBeforeKycIsStarted() {

        stubProfile();

        assertThatThrownBy(() -> kycService.verifyBvn(customerId, BVN_REQUEST, CLIENT_IP))
                .isInstanceOf(InvalidKycStateException.class)
                .hasMessage("BVN verification can only be performed while KYC is in progress");

        verifyNoInteractions(kycProvider);
        verify(kycVerificationRepository, never()).save(any());
    }

    @Test
    void shouldNotVerifyBvnUntilCustomerRestartsAfterInformationRequest() {

        profile.start();
        profile.submit();
        profile.startReview();
        profile.requestAdditionalInformation(UUID.randomUUID(), "More please");

        stubProfile();

        assertThatThrownBy(() -> kycService.verifyBvn(customerId, BVN_REQUEST, CLIENT_IP))
                .isInstanceOf(InvalidKycStateException.class);

        verifyNoInteractions(kycProvider);
    }

    @Test
    void shouldReverifyAfterRestartingFromInformationRequest() {

        profile.start();
        profile.submit();
        profile.startReview();
        profile.requestAdditionalInformation(UUID.randomUUID(), "More please");
        profile.start();

        stubProfile();
        stubProvider(VerificationResult.PASSED, null);

        KycVerificationResponse response =
                kycService.verifyBvn(customerId, BVN_REQUEST, CLIENT_IP);

        assertThat(response.status()).isEqualTo(KycStatus.IN_PROGRESS);
    }

    // ============================================================
    // RETRY LIMIT
    // ============================================================

    @Test
    void shouldReportRemainingAttemptsAfterFailure() {

        stubProfileInProgress();
        stubRecentFailures(1);
        stubProvider(VerificationResult.FAILED, "mismatch");

        KycVerificationResponse response =
                kycService.verifyBvn(customerId, BVN_REQUEST, CLIENT_IP);

        // 1 earlier failure + this one = 2 of 3.
        assertThat(response.remainingAttempts()).isEqualTo(1);
    }

    @Test
    void shouldNotUseUpAnAttemptWhenBvnPasses() {

        stubProfileInProgress();
        stubRecentFailures(2);
        stubProvider(VerificationResult.PASSED, null);

        KycVerificationResponse response =
                kycService.verifyBvn(customerId, BVN_REQUEST, CLIENT_IP);

        assertThat(response.remainingAttempts()).isEqualTo(1);
        assertThat(response.status()).isEqualTo(KycStatus.IN_PROGRESS);
    }

    @Test
    void shouldAllowLastAttemptJustBelowTheLimit() {

        stubProfileInProgress();
        stubRecentFailures(2);
        stubProvider(VerificationResult.FAILED, "mismatch");

        KycVerificationResponse response =
                kycService.verifyBvn(customerId, BVN_REQUEST, CLIENT_IP);

        assertThat(response.remainingAttempts()).isZero();

        verify(kycProvider).verifyBvn(any());
    }

    @Test
    void shouldBlockBvnCheckOnceLimitIsReached() {

        stubProfileInProgress();
        stubRecentFailures(3);

        assertThatThrownBy(() -> kycService.verifyBvn(customerId, BVN_REQUEST, CLIENT_IP))
                .isInstanceOf(BvnAttemptLimitExceededException.class);

        // Refused before contacting the provider or recording anything.
        verifyNoInteractions(kycProvider);
        verify(kycVerificationRepository, never()).save(any());
    }

    @Test
    void shouldRetryAfterOldestFailureLeavesTheWindow() {

        stubProfileInProgress();
        stubRecentFailures(3);

        assertThatThrownBy(() -> kycService.verifyBvn(customerId, BVN_REQUEST, CLIENT_IP))
                .isInstanceOfSatisfying(
                        BvnAttemptLimitExceededException.class,
                        exception -> assertThat(exception.getRetryAfter())
                                .isCloseTo(
                                        Instant.now().plus(Duration.ofHours(24)),
                                        within(5, ChronoUnit.SECONDS)
                                )
                );
    }

    @Test
    void shouldCountOnlyFailuresInsideTheConfiguredWindow() {

        stubProfileInProgress();
        stubProvider(VerificationResult.FAILED, "mismatch");

        Instant before = Instant.now();

        kycService.verifyBvn(customerId, BVN_REQUEST, CLIENT_IP);

        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);

        verify(kycVerificationRepository)
                .findByKycProfileAndVerificationTypeAndResultAndCreatedAtAfterOrderByCreatedAtAscIdAsc(
                        eq(profile),
                        eq(KycVerificationType.BVN),
                        eq(VerificationResult.FAILED),
                        since.capture()
                );

        assertThat(since.getValue())
                .isCloseTo(
                        before.minus(Duration.ofHours(24)),
                        within(5, ChronoUnit.SECONDS)
                );
    }

    @Test
    void shouldCheckStateBeforeRetryLimit() {

        profile.start();
        profile.submit();
        profile.startReview();

        stubProfileInProgress();

        assertThatThrownBy(() -> kycService.verifyBvn(customerId, BVN_REQUEST, CLIENT_IP))
                .isInstanceOf(InvalidKycStateException.class);
    }

    @Test
    void shouldLockProfileForBvnCheck() {

        stubProfileInProgress();
        stubProvider(VerificationResult.PASSED, null);

        kycService.verifyBvn(customerId, BVN_REQUEST, CLIENT_IP);

        verify(kycProfileRepository).findByCustomerForUpdate(customer);
        verify(kycProfileRepository, never()).findByCustomer(any());
    }

    // ============================================================
    // NETWORK (IP) LIMIT
    // ============================================================

    @Test
    void shouldRecordNormalizedNetworkWithAttempt() {

        stubProfileInProgress();
        stubProvider(VerificationResult.FAILED, "mismatch");

        kycService.verifyBvn(customerId, BVN_REQUEST, "2001:db8:1:2::99");

        ArgumentCaptor<KycVerification> captor =
                ArgumentCaptor.forClass(KycVerification.class);

        verify(kycVerificationRepository).save(captor.capture());

        assertThat(captor.getValue().getIpAddress())
                .isEqualTo("2001:db8:1:2::/64");
    }

    @Test
    void shouldLockNetworkBeforeProfile() {

        stubProfileInProgress();
        stubProvider(VerificationResult.PASSED, null);

        kycService.verifyBvn(customerId, BVN_REQUEST, CLIENT_IP);

        var order = inOrder(kycVerificationRepository, kycProfileRepository);

        order.verify(kycVerificationRepository).lockClientNetwork(CLIENT_IP);
        order.verify(kycProfileRepository).findByCustomerForUpdate(customer);
    }

    @Test
    void shouldBlockBvnCheckWhenNetworkLimitIsReached() {

        stubProfileInProgress();

        KycVerification oldest =
                KycVerification.create(
                        profile, KycVerificationType.BVN, "DOJAH", null,
                        VerificationResult.PASSED, null, CLIENT_IP
                );
        ReflectionTestUtils.setField(
                oldest, "createdAt", Instant.now().minus(Duration.ofMinutes(40))
        );

        when(kycVerificationRepository
                .countByVerificationTypeAndIpAddressAndCreatedAtAfter(
                        eq(KycVerificationType.BVN),
                        eq(CLIENT_IP),
                        any(Instant.class)
                ))
                .thenReturn(10L);

        when(kycVerificationRepository
                .findFirstByVerificationTypeAndIpAddressAndCreatedAtAfterOrderByCreatedAtAscIdAsc(
                        eq(KycVerificationType.BVN),
                        eq(CLIENT_IP),
                        any(Instant.class)
                ))
                .thenReturn(Optional.of(oldest));

        assertThatThrownBy(() -> kycService.verifyBvn(customerId, BVN_REQUEST, CLIENT_IP))
                .isInstanceOfSatisfying(
                        BvnIpRateLimitExceededException.class,
                        exception -> assertThat(exception.getRetryAfter())
                                .isEqualTo(oldest.getCreatedAt().plus(Duration.ofHours(1)))
                );

        verifyNoInteractions(kycProvider);
        verify(kycVerificationRepository, never()).save(any());
    }

    @Test
    void shouldAllowCheckJustBelowNetworkLimit() {

        stubProfileInProgress();
        stubProvider(VerificationResult.PASSED, null);

        when(kycVerificationRepository
                .countByVerificationTypeAndIpAddressAndCreatedAtAfter(
                        eq(KycVerificationType.BVN),
                        eq(CLIENT_IP),
                        any(Instant.class)
                ))
                .thenReturn(9L);

        kycService.verifyBvn(customerId, BVN_REQUEST, CLIENT_IP);

        verify(kycProvider).verifyBvn(any());
    }

    @Test
    void shouldCountNetworkChecksInsideTheIpWindow() {

        stubProfileInProgress();
        stubProvider(VerificationResult.PASSED, null);

        Instant before = Instant.now();

        kycService.verifyBvn(customerId, BVN_REQUEST, CLIENT_IP);

        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);

        verify(kycVerificationRepository)
                .countByVerificationTypeAndIpAddressAndCreatedAtAfter(
                        eq(KycVerificationType.BVN),
                        eq(CLIENT_IP),
                        since.capture()
                );

        assertThat(since.getValue())
                .isCloseTo(before.minus(Duration.ofHours(1)), within(5, ChronoUnit.SECONDS));
    }

    @Test
    void shouldRateLimitUnknownNetworkAsOneBucket() {

        stubProfileInProgress();
        stubProvider(VerificationResult.PASSED, null);

        kycService.verifyBvn(customerId, BVN_REQUEST, null);

        verify(kycVerificationRepository).lockClientNetwork("unknown");
        verify(kycVerificationRepository)
                .countByVerificationTypeAndIpAddressAndCreatedAtAfter(
                        eq(KycVerificationType.BVN),
                        eq("unknown"),
                        any(Instant.class)
                );
    }

    @Test
    void shouldReportCustomerLimitBeforeNetworkLimit() {

        stubProfileInProgress();
        stubRecentFailures(3);

        assertThatThrownBy(() -> kycService.verifyBvn(customerId, BVN_REQUEST, CLIENT_IP))
                .isInstanceOf(BvnAttemptLimitExceededException.class);

        verify(kycVerificationRepository, never())
                .countByVerificationTypeAndIpAddressAndCreatedAtAfter(any(), any(), any());
    }

    // ============================================================
    // RETRY LIMIT RESET
    // ============================================================

    @Test
    void shouldResetBvnAttemptsAsAdmin() {

        UUID adminId = UUID.randomUUID();
        profile.start();

        when(kycProfileRepository.findByIdForUpdate(profile.getId()))
                .thenReturn(Optional.of(profile));

        BvnAttemptsResetResponse response =
                kycService.resetBvnAttempts(profile.getId(), adminId);

        assertThat(response.kycId()).isEqualTo(profile.getId());
        assertThat(response.remainingAttempts()).isEqualTo(3);
        assertThat(response.resetBy()).isEqualTo(adminId);
        assertThat(response.resetAt()).isNotNull();

        verify(kycProfileRepository).save(profile);

        // History is untouched: nothing is deleted.
        verifyNoInteractions(kycVerificationRepository);
    }

    @Test
    void shouldNotAllowAdminToResetOwnBvnAttempts() {

        when(kycProfileRepository.findByIdForUpdate(profile.getId()))
                .thenReturn(Optional.of(profile));

        assertThatThrownBy(() ->
                kycService.resetBvnAttempts(profile.getId(), customerId)
        )
                .isInstanceOf(AccessDeniedException.class);

        assertThat(profile.getBvnAttemptsResetAt()).isNull();
        verify(kycProfileRepository, never()).save(any());
    }

    @Test
    void shouldNotResetBvnAttemptsOnceUnderReview() {

        profile.start();
        profile.submit();
        profile.startReview();

        when(kycProfileRepository.findByIdForUpdate(profile.getId()))
                .thenReturn(Optional.of(profile));

        assertThatThrownBy(() ->
                kycService.resetBvnAttempts(profile.getId(), UUID.randomUUID())
        )
                .isInstanceOf(InvalidKycStateException.class);
    }

    @Test
    void shouldThrowWhenResettingUnknownKyc() {

        UUID kycId = UUID.randomUUID();

        when(kycProfileRepository.findByIdForUpdate(kycId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                kycService.resetBvnAttempts(kycId, UUID.randomUUID())
        )
                .isInstanceOf(KycNotFoundException.class);
    }

    @Test
    void shouldCountOnlyFailuresAfterReset() {

        profile.start();
        profile.resetBvnAttempts(UUID.randomUUID());
        Instant resetAt = profile.getBvnAttemptsResetAt();

        stubProfileInProgress();
        stubProvider(VerificationResult.FAILED, "mismatch");

        kycService.verifyBvn(customerId, BVN_REQUEST, CLIENT_IP);

        assertThat(countedSince()).isEqualTo(resetAt);
    }

    @Test
    void shouldIgnoreResetOlderThanTheWindow() {

        profile.start();
        profile.resetBvnAttempts(UUID.randomUUID());
        ReflectionTestUtils.setField(
                profile,
                "bvnAttemptsResetAt",
                Instant.now().minus(Duration.ofDays(3))
        );

        stubProfileInProgress();
        stubProvider(VerificationResult.FAILED, "mismatch");

        Instant before = Instant.now();

        kycService.verifyBvn(customerId, BVN_REQUEST, CLIENT_IP);

        assertThat(countedSince())
                .isCloseTo(
                        before.minus(Duration.ofHours(24)),
                        within(5, ChronoUnit.SECONDS)
                );
    }

    private Instant countedSince() {

        ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);

        verify(kycVerificationRepository)
                .findByKycProfileAndVerificationTypeAndResultAndCreatedAtAfterOrderByCreatedAtAscIdAsc(
                        eq(profile),
                        eq(KycVerificationType.BVN),
                        eq(VerificationResult.FAILED),
                        since.capture()
                );

        return since.getValue();
    }

    // ============================================================
    // ADMIN VIEW OF BVN ATTEMPTS
    // ============================================================

    @Test
    void shouldShowAttemptsForCustomerBelowTheLimit() {

        KycVerification failed = bvnAttempt(VerificationResult.FAILED, hoursAgo(2));
        KycVerification passed = bvnAttempt(VerificationResult.PASSED, hoursAgo(1));

        stubHistory(passed, failed);

        BvnAttemptsResponse view = kycService.getBvnAttempts(profile.getId(), 0, 20);

        assertThat(view.kycId()).isEqualTo(profile.getId());
        assertThat(view.kycStatus()).isEqualTo(KycStatus.NOT_STARTED);
        assertThat(view.maxFailedAttempts()).isEqualTo(3);
        assertThat(view.attemptWindow()).isEqualTo("PT24H");
        assertThat(view.failedAttemptsCounted()).isEqualTo(1);
        assertThat(view.remainingAttempts()).isEqualTo(2);
        assertThat(view.limited()).isFalse();
        assertThat(view.retryAfter()).isNull();

        assertThat(view.attempts())
                .extracting(
                        BvnAttemptResponse::result,
                        BvnAttemptResponse::countsTowardLimit
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(VerificationResult.PASSED, false),
                        org.assertj.core.groups.Tuple.tuple(VerificationResult.FAILED, true)
                );
    }

    @Test
    void shouldShowLimitedCustomerWithRetryTime() {

        KycVerification oldest = bvnAttempt(VerificationResult.FAILED, hoursAgo(5));

        stubHistory(
                bvnAttempt(VerificationResult.FAILED, hoursAgo(1)),
                bvnAttempt(VerificationResult.FAILED, hoursAgo(3)),
                oldest
        );

        BvnAttemptsResponse view = kycService.getBvnAttempts(profile.getId(), 0, 20);

        assertThat(view.limited()).isTrue();
        assertThat(view.failedAttemptsCounted()).isEqualTo(3);
        assertThat(view.remainingAttempts()).isZero();

        // Same rule verifyBvn uses: oldest counted failure + window.
        assertThat(view.retryAfter())
                .isEqualTo(oldest.getCreatedAt().plus(Duration.ofHours(24)));
    }

    @Test
    void shouldNotCountFailuresOutsideTheWindow() {

        stubHistory(
                bvnAttempt(VerificationResult.FAILED, hoursAgo(1)),
                bvnAttempt(VerificationResult.FAILED, hoursAgo(30)),
                bvnAttempt(VerificationResult.FAILED, hoursAgo(40))
        );

        BvnAttemptsResponse view = kycService.getBvnAttempts(profile.getId(), 0, 20);

        assertThat(view.failedAttemptsCounted()).isEqualTo(1);
        assertThat(view.limited()).isFalse();
        assertThat(view.attempts()).hasSize(3);
        assertThat(view.attempts())
                .extracting(BvnAttemptResponse::countsTowardLimit)
                .containsExactly(true, false, false);
    }

    @Test
    void shouldNotCountFailuresBeforeAReset() {

        UUID adminId = UUID.randomUUID();

        stubHistory(
                bvnAttempt(VerificationResult.FAILED, hoursAgo(3)),
                bvnAttempt(VerificationResult.FAILED, hoursAgo(4)),
                bvnAttempt(VerificationResult.FAILED, hoursAgo(5))
        );

        profile.resetBvnAttempts(adminId);

        BvnAttemptsResponse view = kycService.getBvnAttempts(profile.getId(), 0, 20);

        assertThat(view.failedAttemptsCounted()).isZero();
        assertThat(view.remainingAttempts()).isEqualTo(3);
        assertThat(view.limited()).isFalse();
        assertThat(view.countingSince()).isEqualTo(profile.getBvnAttemptsResetAt());
        assertThat(view.resetAt()).isEqualTo(profile.getBvnAttemptsResetAt());
        assertThat(view.resetBy()).isEqualTo(adminId);

        // History is still visible.
        assertThat(view.attempts()).hasSize(3);
    }

    @Test
    void shouldShowEmptyHistory() {

        stubHistory();

        BvnAttemptsResponse view = kycService.getBvnAttempts(profile.getId(), 0, 20);

        assertThat(view.attempts()).isEmpty();
        assertThat(view.remainingAttempts()).isEqualTo(3);
        assertThat(view.resetAt()).isNull();
    }

    @Test
    void shouldThrowWhenViewingAttemptsOfUnknownKyc() {

        UUID kycId = UUID.randomUUID();

        when(kycProfileRepository.findById(kycId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> kycService.getBvnAttempts(kycId, 0, 20))
                .isInstanceOf(KycNotFoundException.class);
    }

    /**
     * Stubs both queries the admin view makes, over the same attempts:
     * the page of history (returned as one page) and the counted-failures
     * query, which applies the real repository predicate (FAILED, after
     * {@code since}, oldest first) so the counting logic is exercised.
     */
    private void stubHistory(KycVerification... newestFirst) {

        List<KycVerification> attempts = List.of(newestFirst);

        when(kycProfileRepository.findById(profile.getId()))
                .thenReturn(Optional.of(profile));

        lenient()
                .when(kycVerificationRepository
                        .findByKycProfileAndVerificationType(
                                eq(profile),
                                eq(KycVerificationType.BVN),
                                any(Pageable.class)
                        ))
                .thenAnswer(invocation -> new PageImpl<>(
                        attempts,
                        invocation.getArgument(2),
                        attempts.size()
                ));

        when(kycVerificationRepository
                .findByKycProfileAndVerificationTypeAndResultAndCreatedAtAfterOrderByCreatedAtAscIdAsc(
                        eq(profile),
                        eq(KycVerificationType.BVN),
                        eq(VerificationResult.FAILED),
                        any(Instant.class)
                ))
                .thenAnswer(invocation -> {
                    Instant since = invocation.getArgument(3);
                    return attempts.stream()
                            .filter(a -> a.getResult() == VerificationResult.FAILED)
                            .filter(a -> a.getCreatedAt().isAfter(since))
                            .sorted(java.util.Comparator.comparing(
                                    KycVerification::getCreatedAt))
                            .toList();
                });
    }

    @Test
    void shouldRequestHistoryPageNewestFirst() {

        stubHistory();

        kycService.getBvnAttempts(profile.getId(), 2, 10);

        ArgumentCaptor<Pageable> pageable =
                ArgumentCaptor.forClass(Pageable.class);

        verify(kycVerificationRepository)
                .findByKycProfileAndVerificationType(
                        eq(profile),
                        eq(KycVerificationType.BVN),
                        pageable.capture()
                );

        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(10);
        assertThat(pageable.getValue().getSort())
                .containsExactly(
                        Sort.Order.desc("createdAt"),
                        Sort.Order.desc("id")
                );
    }

    @Test
    void shouldKeepSummaryIndependentOfRequestedPage() {

        KycVerification oldest = bvnAttempt(VerificationResult.FAILED, hoursAgo(5));
        KycVerification middle = bvnAttempt(VerificationResult.FAILED, hoursAgo(3));
        KycVerification newest = bvnAttempt(VerificationResult.FAILED, hoursAgo(1));

        when(kycProfileRepository.findById(profile.getId()))
                .thenReturn(Optional.of(profile));

        // Page 1 of size 1 holds only the middle attempt...
        when(kycVerificationRepository
                .findByKycProfileAndVerificationType(
                        eq(profile),
                        eq(KycVerificationType.BVN),
                        any(Pageable.class)
                ))
                .thenReturn(new PageImpl<>(
                        List.of(middle),
                        PageRequest.of(1, 1),
                        3
                ));

        // ...but the summary still sees all three counted failures.
        when(kycVerificationRepository
                .findByKycProfileAndVerificationTypeAndResultAndCreatedAtAfterOrderByCreatedAtAscIdAsc(
                        eq(profile),
                        eq(KycVerificationType.BVN),
                        eq(VerificationResult.FAILED),
                        any(Instant.class)
                ))
                .thenReturn(List.of(oldest, middle, newest));

        BvnAttemptsResponse view = kycService.getBvnAttempts(profile.getId(), 1, 1);

        assertThat(view.attempts()).hasSize(1);
        assertThat(view.limited()).isTrue();
        assertThat(view.failedAttemptsCounted()).isEqualTo(3);
        assertThat(view.retryAfter())
                .isEqualTo(oldest.getCreatedAt().plus(Duration.ofHours(24)));

        assertThat(view.page())
                .isEqualTo(new PageInfo(1, 1, 3, 3, true));
    }

    private KycVerification bvnAttempt(VerificationResult result, Instant createdAt) {

        KycVerification attempt =
                KycVerification.create(
                        profile,
                        KycVerificationType.BVN,
                        "DOJAH",
                        null,
                        result,
                        null
                );

        ReflectionTestUtils.setField(attempt, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(attempt, "createdAt", createdAt);

        return attempt;
    }

    private Instant hoursAgo(int hours) {
        return Instant.now().minus(Duration.ofHours(hours));
    }

    // ============================================================
    // REVIEW
    // ============================================================

    @Test
    void shouldApproveKycUnderReview() {

        stubReviewable();

        KycResponse response =
                kycService.approve(profile.getId(), UUID.randomUUID());

        assertThat(response.status()).isEqualTo(KycStatus.VERIFIED);
    }

    @Test
    void shouldRecordReviewerOnApproval() {

        stubReviewable();
        UUID reviewerId = UUID.randomUUID();

        kycService.approve(profile.getId(), reviewerId);

        assertThat(profile.getReviewedBy()).isEqualTo(reviewerId);
        assertThat(profile.getReviewedAt()).isNotNull();
    }

    @Test
    void shouldRejectKycUnderReviewWithReason() {

        stubReviewable();
        UUID reviewerId = UUID.randomUUID();

        KycResponse response =
                kycService.reject(
                        profile.getId(),
                        reviewerId,
                        " Identity document could not be validated "
                );

        assertThat(response.status()).isEqualTo(KycStatus.REJECTED);
        assertThat(response.reviewReason())
                .isEqualTo("Identity document could not be validated");
        assertThat(profile.getReviewedBy()).isEqualTo(reviewerId);
    }

    @Test
    void shouldRequestAdditionalInformationWithReason() {

        stubReviewable();

        KycResponse response =
                kycService.requestAdditionalInformation(
                        profile.getId(),
                        UUID.randomUUID(),
                        "Please provide a clearer proof of address"
                );

        assertThat(response.status())
                .isEqualTo(KycStatus.ADDITIONAL_INFO_REQUIRED);
        assertThat(response.reviewReason())
                .isEqualTo("Please provide a clearer proof of address");
    }

    @Test
    void shouldStartReviewOfSubmittedKyc() {

        profile.start();
        profile.submit();

        when(kycProfileRepository.findById(profile.getId()))
                .thenReturn(Optional.of(profile));
        when(kycProfileRepository.save(profile)).thenReturn(profile);

        KycResponse response =
                kycService.startReview(profile.getId(), UUID.randomUUID());

        assertThat(response.status()).isEqualTo(KycStatus.UNDER_REVIEW);
    }

    @Test
    void shouldNotStartReviewBeforeSubmission() {

        profile.start();

        when(kycProfileRepository.findById(profile.getId()))
                .thenReturn(Optional.of(profile));

        assertThatThrownBy(() ->
                kycService.startReview(profile.getId(), UUID.randomUUID())
        )
                .isInstanceOf(InvalidKycStateException.class)
                .hasMessage("KYC cannot enter review from status IN_PROGRESS");
    }

    @Test
    void shouldNotAllowReviewerToStartReviewOfOwnKyc() {

        profile.start();
        profile.submit();

        when(kycProfileRepository.findById(profile.getId()))
                .thenReturn(Optional.of(profile));

        assertThatThrownBy(() ->
                kycService.startReview(profile.getId(), customerId)
        )
                .isInstanceOf(AccessDeniedException.class);

        assertThat(profile.getStatus()).isEqualTo(KycStatus.SUBMITTED);
    }

    @Test
    void shouldNotAllowReviewerToApproveOwnKyc() {

        profile.start();
        profile.submit();
        profile.startReview();

        when(kycProfileRepository.findById(profile.getId()))
                .thenReturn(Optional.of(profile));

        assertThatThrownBy(() ->
                kycService.approve(profile.getId(), customerId)
        )
                .isInstanceOf(AccessDeniedException.class);

        assertThat(profile.getStatus()).isEqualTo(KycStatus.UNDER_REVIEW);
    }

    @Test
    void shouldNotApproveKycThatIsNotUnderReview() {

        when(kycProfileRepository.findById(profile.getId()))
                .thenReturn(Optional.of(profile));

        assertThatThrownBy(() ->
                kycService.approve(profile.getId(), UUID.randomUUID())
        )
                .isInstanceOf(InvalidKycStateException.class);
    }

    @Test
    void shouldThrowWhenReviewingUnknownKyc() {

        UUID kycId = UUID.randomUUID();

        when(kycProfileRepository.findById(kycId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> kycService.approve(kycId, UUID.randomUUID()))
                .isInstanceOf(KycNotFoundException.class);
    }

    /**
     * Most BVN tests need a profile the customer has already started.
     */
    private void stubProfileInProgress() {

        if (profile.getStatus() == KycStatus.NOT_STARTED) {
            profile.start();
        }

        stubProfile();
    }

    private void stubSubmissionRequirements(boolean bvnPassed, boolean hasDocument) {

        when(kycVerificationRepository.existsByKycProfileAndVerificationTypeAndResult(
                profile,
                KycVerificationType.BVN,
                VerificationResult.PASSED
        ))
                .thenReturn(bvnPassed);

        when(kycDocumentRepository.existsByKycProfile(profile))
                .thenReturn(hasDocument);
    }

    private void stubProfileForUpdate() {

        when(customerRepository.findById(customerId))
                .thenReturn(Optional.of(customer));

        when(kycProfileRepository.findByCustomerForUpdate(customer))
                .thenReturn(Optional.of(profile));

        lenient()
                .when(kycProfileRepository.save(profile))
                .thenReturn(profile);
    }

    /**
     * BVN checks load the profile with a row lock; other operations use
     * the plain lookup. Each test only exercises one of them.
     */
    private void stubProfile() {

        when(customerRepository.findById(customerId))
                .thenReturn(Optional.of(customer));

        lenient()
                .when(kycProfileRepository.findByCustomer(customer))
                .thenReturn(Optional.of(profile));

        lenient()
                .when(kycProfileRepository.findByCustomerForUpdate(customer))
                .thenReturn(Optional.of(profile));
    }

    private void stubRecentFailures(int count) {

        List<KycVerification> failures = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            failures.add(KycVerification.create(
                    profile,
                    KycVerificationType.BVN,
                    "DOJAH",
                    null,
                    VerificationResult.FAILED,
                    "mismatch"
            ));
        }

        when(kycVerificationRepository
                .findByKycProfileAndVerificationTypeAndResultAndCreatedAtAfterOrderByCreatedAtAscIdAsc(
                        eq(profile),
                        eq(KycVerificationType.BVN),
                        eq(VerificationResult.FAILED),
                        any(Instant.class)
                ))
                .thenReturn(failures);
    }

    private void stubReviewable() {

        profile.start();
        profile.submit();
        profile.startReview();

        when(kycProfileRepository.findById(profile.getId()))
                .thenReturn(Optional.of(profile));

        when(kycProfileRepository.save(profile))
                .thenReturn(profile);
    }

    private void stubProvider(VerificationResult verificationResult, String reason) {

        when(kycProvider.verifyBvn(any()))
                .thenReturn(result(verificationResult, reason));
    }

    private KycProviderResult result(VerificationResult verificationResult, String reason) {

        return new KycProviderResult(
                "DOJAH",
                "ref-1",
                verificationResult,
                reason
        );
    }
}
