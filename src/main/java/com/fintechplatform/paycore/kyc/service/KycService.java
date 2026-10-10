package com.fintechplatform.paycore.kyc.service;

import com.fintechplatform.paycore.common.net.ClientNetwork;
import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.customer.exception.CustomerNotFoundException;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
import com.fintechplatform.paycore.kyc.dto.BvnAttemptResponse;
import com.fintechplatform.paycore.kyc.dto.BvnAttemptsResetResponse;
import com.fintechplatform.paycore.kyc.dto.BvnAttemptsResponse;
import com.fintechplatform.paycore.kyc.dto.KycResponse;
import com.fintechplatform.paycore.kyc.dto.KycReviewItemResponse;
import com.fintechplatform.paycore.kyc.dto.KycReviewQueueResponse;
import com.fintechplatform.paycore.kyc.dto.KycVerificationResponse;
import com.fintechplatform.paycore.kyc.dto.PageInfo;
import com.fintechplatform.paycore.kyc.dto.VerifyBvnRequest;
import com.fintechplatform.paycore.kyc.dto.VerifyNinRequest;
import com.fintechplatform.paycore.kyc.entity.KycDocument;
import com.fintechplatform.paycore.kyc.entity.KycProfile;
import com.fintechplatform.paycore.kyc.entity.KycVerification;
import com.fintechplatform.paycore.kyc.config.KycProperties;
import com.fintechplatform.paycore.kyc.enums.KycDocumentType;
import com.fintechplatform.paycore.kyc.enums.KycStatus;
import com.fintechplatform.paycore.kyc.enums.KycVerificationType;
import com.fintechplatform.paycore.kyc.enums.VerificationResult;
import com.fintechplatform.paycore.kyc.exception.BvnAttemptLimitExceededException;
import com.fintechplatform.paycore.kyc.exception.BvnIpRateLimitExceededException;
import com.fintechplatform.paycore.kyc.exception.InvalidKycStateException;
import com.fintechplatform.paycore.kyc.exception.KycAlreadyExistsException;
import com.fintechplatform.paycore.kyc.exception.KycIncompleteException;
import com.fintechplatform.paycore.kyc.exception.KycNotFoundException;
import com.fintechplatform.paycore.kyc.provider.KycProvider;
import com.fintechplatform.paycore.kyc.provider.KycProviderResult;
import com.fintechplatform.paycore.kyc.provider.KycVerificationRequest;
import com.fintechplatform.paycore.kyc.repository.KycDocumentRepository;
import com.fintechplatform.paycore.kyc.repository.KycProfileRepository;
import com.fintechplatform.paycore.kyc.repository.KycVerificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class KycService {

    private static final Logger log =
            LoggerFactory.getLogger(KycService.class);

    private static final Sort NEWEST_FIRST =
            Sort.by(
                    Sort.Order.desc("createdAt"),
                    Sort.Order.desc("id")
            );

    private final CustomerRepository customerRepository;
    private final KycProfileRepository kycProfileRepository;
    private final KycDocumentRepository kycDocumentRepository;
    private final KycVerificationRepository kycVerificationRepository;
    private final KycProvider kycProvider;
    private final KycProperties kycProperties;

    public KycService(
            CustomerRepository customerRepository,
            KycProfileRepository kycProfileRepository,
            KycDocumentRepository kycDocumentRepository,
            KycVerificationRepository kycVerificationRepository,
            KycProvider kycProvider,
            KycProperties kycProperties
    ) {
        this.customerRepository = customerRepository;
        this.kycProfileRepository = kycProfileRepository;
        this.kycDocumentRepository = kycDocumentRepository;
        this.kycVerificationRepository = kycVerificationRepository;
        this.kycProvider = kycProvider;
        this.kycProperties = kycProperties;
    }

    @Transactional
    public void createInitialProfile(UUID customerId) {
        kycProfileRepository.save(KycProfile.create(findCustomer(customerId)));
    }

    @Transactional
    public KycResponse createKyc(UUID customerId) {

        Customer customer = findCustomer(customerId);

        if (kycProfileRepository.existsByCustomer(customer)) {
            throw new KycAlreadyExistsException();
        }

        return toResponse(
                kycProfileRepository.save(KycProfile.create(customer))
        );
    }

    @Transactional(readOnly = true)
    public KycResponse getKyc(UUID customerId) {

        return toResponse(findProfileOfCustomer(customerId));
    }

    @Transactional
    public KycResponse startKyc(UUID customerId) {

        KycProfile profile = findProfileOfCustomerForUpdate(customerId);

        transition(profile::start);

        return toResponse(kycProfileRepository.save(profile));
    }

    @Transactional
    public KycResponse submitKyc(UUID customerId) {

        KycProfile profile = findProfileOfCustomerForUpdate(customerId);

        if (!profile.isInProgress()) {
            transition(profile::submit);
        }

        List<String> missing = new ArrayList<>();

        if (!hasPassed(profile, KycVerificationType.BVN)
                && !hasPassed(profile, KycVerificationType.NIN)) {
            missing.add("passed BVN or NIN verification");
        }

        if (!kycDocumentRepository.existsByKycProfile(profile)) {
            missing.add("identity document");
        }

        if (profile.getCustomer().getPhoneNumber() == null) {
            missing.add("phone number on your profile");
        }

        if (!missing.isEmpty()) {
            throw new KycIncompleteException(missing);
        }

        transition(profile::submit);

        return toResponse(kycProfileRepository.save(profile));
    }

    @Transactional
    public KycVerificationResponse verifyBvn(
            UUID customerId,
            VerifyBvnRequest request,
            String clientIp
    ) {

        return verifyIdentityNumber(
                customerId,
                KycVerificationType.BVN,
                new KycVerificationRequest(
                        request.bvn(),
                        request.firstName().trim(),
                        request.lastName().trim(),
                        request.dateOfBirth()
                ),
                clientIp
        );
    }

    @Transactional
    public KycVerificationResponse verifyNin(
            UUID customerId,
            VerifyNinRequest request,
            String clientIp
    ) {

        return verifyIdentityNumber(
                customerId,
                KycVerificationType.NIN,
                new KycVerificationRequest(
                        request.nin(),
                        request.firstName().trim(),
                        request.lastName().trim(),
                        request.dateOfBirth()
                ),
                clientIp
        );
    }

    private KycVerificationResponse verifyIdentityNumber(
            UUID customerId,
            KycVerificationType type,
            KycVerificationRequest request,
            String clientIp
    ) {

        String networkKey = ClientNetwork.key(clientIp);

        kycVerificationRepository.lockClientNetwork(networkKey);

        KycProfile profile =
                kycProfileRepository
                        .findByCustomerForUpdate(findCustomer(customerId))
                        .orElseThrow(KycNotFoundException::new);

        boolean upgrading = profile.isVerified() && !hasPassed(profile, type);

        if (!profile.isInProgress() && !upgrading) {
            throw new InvalidKycStateException(
                    profile.isVerified()
                            ? type + " is already verified"
                            : type + " verification can only be performed while KYC is in progress"
            );
        }

        List<KycVerification> recentFailures =
                recentFailedAttempts(profile, type);

        if (recentFailures.size() >= kycProperties.getMaxFailedAttempts()) {
            throw new BvnAttemptLimitExceededException(
                    retryAfter(recentFailures.getFirst().getCreatedAt()),
                    type
            );
        }

        enforceNetworkLimit(networkKey, type);

        KycProviderResult result =
                type == KycVerificationType.NIN
                        ? kycProvider.verifyNin(request)
                        : kycProvider.verifyBvn(request);

        kycVerificationRepository.save(
                KycVerification.create(
                        profile,
                        type,
                        result.provider(),
                        result.providerReference(),
                        result.result(),
                        result.reason(),
                        networkKey
                )
        );

        if (upgrading && result.result() == VerificationResult.PASSED) {
            profile.raiseTier(tierEarned(profile));
            kycProfileRepository.save(profile);
        }

        int failuresInWindow =
                recentFailures.size()
                        + (result.result() == VerificationResult.FAILED ? 1 : 0);

        return new KycVerificationResponse(
                profile.getId(),
                result.result(),
                profile.getStatus(),
                result.provider(),
                result.reason(),
                Math.max(
                        0,
                        kycProperties.getMaxFailedAttempts() - failuresInWindow
                )
        );
    }

    private int tierEarned(KycProfile profile) {

        boolean bothNumbers =
                hasPassed(profile, KycVerificationType.BVN) && hasPassed(profile, KycVerificationType.NIN);

        if (!bothNumbers) {
            return 1;
        }

        if (profile.isVerified()) {
            return 2;
        }

        boolean proofOfAddress =
                kycDocumentRepository.existsByKycProfileAndDocumentType(profile, KycDocumentType.PROOF_OF_ADDRESS);

        return proofOfAddress ? 3 : 2;
    }

    private boolean hasPassed(KycProfile profile, KycVerificationType type) {

        return kycVerificationRepository.existsByKycProfileAndVerificationTypeAndResult(
                profile,
                type,
                VerificationResult.PASSED
        );
    }

    private List<KycVerification> recentFailedAttempts(
            KycProfile profile,
            KycVerificationType type
    ) {

        return recentFailedAttempts(profile, type, countingSince(profile));
    }

    private List<KycVerification> recentFailedAttempts(
            KycProfile profile,
            KycVerificationType type,
            Instant since
    ) {

        return kycVerificationRepository
                .findByKycProfileAndVerificationTypeAndResultAndCreatedAtAfterOrderByCreatedAtAscIdAsc(
                        profile,
                        type,
                        VerificationResult.FAILED,
                        since
                );
    }

    private void enforceNetworkLimit(
            String networkKey,
            KycVerificationType type
    ) {

        Instant since =
                Instant.now().minus(kycProperties.getIpAttemptWindow());

        long recent =
                kycVerificationRepository
                        .countByVerificationTypeAndIpAddressAndCreatedAtAfter(
                                type,
                                networkKey,
                                since
                        );

        if (recent < kycProperties.getIpMaxAttempts()) {
            return;
        }

        Instant retryAfter =
                kycVerificationRepository
                        .findFirstByVerificationTypeAndIpAddressAndCreatedAtAfterOrderByCreatedAtAscIdAsc(
                                type,
                                networkKey,
                                since
                        )
                        .map(oldest -> oldest.getCreatedAt()
                                .plus(kycProperties.getIpAttemptWindow()))
                        .orElse(Instant.now().plus(kycProperties.getIpAttemptWindow()));

        log.warn(
                "{} checks blocked for network {}: {} checks in {}",
                type,
                networkKey,
                recent,
                kycProperties.getIpAttemptWindow()
        );

        throw new BvnIpRateLimitExceededException(retryAfter, type);
    }

    private Instant countingSince(KycProfile profile) {

        Instant windowStart =
                Instant.now().minus(kycProperties.getAttemptWindow());

        Instant resetAt = profile.getBvnAttemptsResetAt();

        return resetAt != null && resetAt.isAfter(windowStart)
                ? resetAt
                : windowStart;
    }

    private Instant retryAfter(Instant oldestCountedFailure) {

        return oldestCountedFailure.plus(kycProperties.getAttemptWindow());
    }

    @Transactional(readOnly = true)
    public KycReviewQueueResponse listProfiles(
            KycStatus status,
            int page,
            int size
    ) {

        PageRequest pageRequest =
                PageRequest.of(
                        page,
                        size,
                        Sort.by(
                                Sort.Order.desc("updatedAt"),
                                Sort.Order.desc("id")
                        )
                );

        Page<KycProfile> profiles =
                status == null
                        ? kycProfileRepository.findAll(pageRequest)
                        : kycProfileRepository.findByStatus(status, pageRequest);

        return new KycReviewQueueResponse(
                profiles.stream()
                        .map(this::toReviewItem)
                        .toList(),
                PageInfo.of(profiles)
        );
    }

    private KycReviewItemResponse toReviewItem(KycProfile profile) {

        Customer customer = profile.getCustomer();

        return new KycReviewItemResponse(
                profile.getId(),
                customer.getId(),
                customer.getFirstName() + " " + customer.getLastName(),
                customer.getEmail(),
                profile.getStatus(),
                profile.getReviewReason(),
                hasPassed(profile, KycVerificationType.BVN),
                hasPassed(profile, KycVerificationType.NIN),
                kycDocumentRepository
                        .findByKycProfileOrderByCreatedAtAscIdAsc(profile)
                        .stream()
                        .map(KycDocument::getDocumentType)
                        .toList(),
                profile.getCreatedAt(),
                profile.getUpdatedAt()
        );
    }

    @Transactional
    public KycResponse startReview(UUID kycId, UUID reviewerId) {

        KycProfile profile = findReviewableProfile(kycId, reviewerId);

        transition(profile::startReview);

        return toResponse(kycProfileRepository.save(profile));
    }

    @Transactional
    public KycResponse approve(UUID kycId, UUID reviewerId) {

        KycProfile profile = findReviewableProfile(kycId, reviewerId);

        int tier = tierEarned(profile);

        transition(() -> profile.approve(reviewerId, tier));

        return toResponse(kycProfileRepository.save(profile));
    }

    @Transactional
    public KycResponse reject(UUID kycId, UUID reviewerId, String reason) {

        KycProfile profile = findReviewableProfile(kycId, reviewerId);

        transition(() -> profile.reject(reviewerId, reason.trim()));

        return toResponse(kycProfileRepository.save(profile));
    }

    @Transactional
    public KycResponse requestAdditionalInformation(
            UUID kycId,
            UUID reviewerId,
            String reason
    ) {

        KycProfile profile = findReviewableProfile(kycId, reviewerId);

        transition(() -> profile.requestAdditionalInformation(
                reviewerId,
                reason.trim()
        ));

        return toResponse(kycProfileRepository.save(profile));
    }

    @Transactional
    public BvnAttemptsResetResponse resetBvnAttempts(
            UUID kycId,
            UUID adminId
    ) {

        KycProfile profile =
                kycProfileRepository
                        .findByIdForUpdate(kycId)
                        .orElseThrow(KycNotFoundException::new);

        ensureNotOwnProfile(profile, adminId);

        transition(() -> profile.resetBvnAttempts(adminId));

        kycProfileRepository.save(profile);

        log.info(
                "BVN attempt limit reset for KYC {} by admin {}",
                profile.getId(),
                adminId
        );

        return new BvnAttemptsResetResponse(
                profile.getId(),
                kycProperties.getMaxFailedAttempts(),
                profile.getBvnAttemptsResetAt(),
                profile.getBvnAttemptsResetBy()
        );
    }

    @Transactional(readOnly = true)
    public BvnAttemptsResponse getBvnAttempts(UUID kycId, int page, int size) {

        return getAttempts(kycId, KycVerificationType.BVN, page, size);
    }

    @Transactional(readOnly = true)
    public BvnAttemptsResponse getAttempts(
            UUID kycId,
            KycVerificationType type,
            int page,
            int size
    ) {

        KycProfile profile =
                kycProfileRepository
                        .findById(kycId)
                        .orElseThrow(KycNotFoundException::new);

        Instant since = countingSince(profile);

        List<KycVerification> counted = recentFailedAttempts(profile, type, since);

        Page<KycVerification> history =
                kycVerificationRepository
                        .findByKycProfileAndVerificationType(
                                profile,
                                type,
                                PageRequest.of(page, size, NEWEST_FIRST)
                        );

        List<BvnAttemptResponse> attempts =
                history.stream()
                        .map(attempt -> new BvnAttemptResponse(
                                attempt.getId(),
                                attempt.getResult(),
                                attempt.getProvider(),
                                attempt.getProviderReference(),
                                attempt.getReason(),
                                attempt.getIpAddress(),
                                attempt.getCreatedAt(),
                                countsTowardLimit(attempt, since)
                        ))
                        .toList();

        int max = kycProperties.getMaxFailedAttempts();
        boolean limited = counted.size() >= max;

        return new BvnAttemptsResponse(
                profile.getId(),
                profile.getStatus(),
                max,
                kycProperties.getAttemptWindow().toString(),
                since,
                counted.size(),
                Math.max(0, max - counted.size()),
                limited,
                limited ? retryAfter(counted.getFirst().getCreatedAt()) : null,
                profile.getBvnAttemptsResetAt(),
                profile.getBvnAttemptsResetBy(),
                attempts,
                PageInfo.of(history)
        );
    }

    private boolean countsTowardLimit(KycVerification attempt, Instant since) {

        return attempt.getResult() == VerificationResult.FAILED
                && attempt.getCreatedAt().isAfter(since);
    }

    private KycProfile findReviewableProfile(UUID kycId, UUID reviewerId) {

        KycProfile profile =
                kycProfileRepository
                        .findById(kycId)
                        .orElseThrow(KycNotFoundException::new);

        ensureNotOwnProfile(profile, reviewerId);

        return profile;
    }

    private void ensureNotOwnProfile(KycProfile profile, UUID reviewerId) {

        if (profile.getCustomer().getId().equals(reviewerId)) {
            throw new AccessDeniedException(
                    "Reviewers cannot act on their own KYC"
            );
        }
    }

    private KycProfile findProfileOfCustomer(UUID customerId) {

        return kycProfileRepository
                .findByCustomer(findCustomer(customerId))
                .orElseThrow(KycNotFoundException::new);
    }

    private KycProfile findProfileOfCustomerForUpdate(UUID customerId) {

        return kycProfileRepository
                .findByCustomerForUpdate(findCustomer(customerId))
                .orElseThrow(KycNotFoundException::new);
    }

    private Customer findCustomer(UUID customerId) {

        return customerRepository
                .findById(customerId)
                .orElseThrow(() ->
                        new CustomerNotFoundException(customerId)
                );
    }

    private void transition(Runnable change) {

        try {
            change.run();
        } catch (IllegalStateException exception) {
            throw new InvalidKycStateException(exception.getMessage());
        }
    }

    private KycResponse toResponse(KycProfile profile) {

        return new KycResponse(
                profile.getId(),
                profile.getCustomer().getId(),
                profile.getStatus(),
                profile.getReviewReason(),
                profile.getCreatedAt(),
                profile.getUpdatedAt()
        );
    }
}
