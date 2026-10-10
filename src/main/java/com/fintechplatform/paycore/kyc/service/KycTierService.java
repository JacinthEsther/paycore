package com.fintechplatform.paycore.kyc.service;

import com.fintechplatform.paycore.account.entity.Account;
import com.fintechplatform.paycore.account.enums.AccountStatus;
import com.fintechplatform.paycore.account.repository.AccountRepository;
import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.customer.exception.CustomerNotFoundException;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
import com.fintechplatform.paycore.kyc.dto.KycTierResponse;
import com.fintechplatform.paycore.kyc.dto.KycTierUpgradeResponse;
import com.fintechplatform.paycore.kyc.entity.KycDocument;
import com.fintechplatform.paycore.kyc.entity.KycProfile;
import com.fintechplatform.paycore.kyc.entity.KycTierUpgrade;
import com.fintechplatform.paycore.kyc.enums.KycDocumentType;
import com.fintechplatform.paycore.kyc.enums.KycTierUpgradeStatus;
import com.fintechplatform.paycore.kyc.enums.KycVerificationType;
import com.fintechplatform.paycore.kyc.enums.VerificationResult;
import com.fintechplatform.paycore.kyc.exception.InvalidKycStateException;
import com.fintechplatform.paycore.kyc.exception.KycNotFoundException;
import com.fintechplatform.paycore.kyc.repository.KycProfileRepository;
import com.fintechplatform.paycore.kyc.repository.KycTierUpgradeRepository;
import com.fintechplatform.paycore.kyc.repository.KycVerificationRepository;
import com.fintechplatform.paycore.ledger.domain.Currency;
import com.fintechplatform.paycore.ledger.domain.Money;
import com.fintechplatform.paycore.limits.config.TierLimitProperties.TierLimit;
import com.fintechplatform.paycore.limits.service.SpendingLimitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fintechplatform.paycore.customer.enums.CustomerStatus;
import com.fintechplatform.paycore.kyc.config.TierUpgradeProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class KycTierService {

    private static final Logger log = LoggerFactory.getLogger(KycTierService.class);

    private static final String CURRENCY = "NGN";

    private final KycProfileRepository profileRepository;
    private final KycTierUpgradeRepository upgradeRepository;
    private final KycVerificationRepository verificationRepository;
    private final KycDocumentService documentService;
    private final CustomerRepository customerRepository;
    private final AccountRepository accountRepository;
    private final SpendingLimitService spendingLimits;
    private final TierUpgradeProperties upgradeProperties;
    private final ZoneId timeZone;

    public KycTierService(
            KycProfileRepository profileRepository,
            KycTierUpgradeRepository upgradeRepository,
            KycVerificationRepository verificationRepository,
            KycDocumentService documentService,
            CustomerRepository customerRepository,
            AccountRepository accountRepository,
            SpendingLimitService spendingLimits,
            TierUpgradeProperties upgradeProperties,
            @Value("${paycore.ledger.statement-time-zone:Africa/Lagos}") String timeZone
    ) {
        this.profileRepository = profileRepository;
        this.upgradeRepository = upgradeRepository;
        this.verificationRepository = verificationRepository;
        this.documentService = documentService;
        this.customerRepository = customerRepository;
        this.accountRepository = accountRepository;
        this.spendingLimits = spendingLimits;
        this.upgradeProperties = upgradeProperties;
        this.timeZone = ZoneId.of(timeZone);
    }

    @Transactional(readOnly = true)
    public KycTierResponse tier(UUID customerId) {

        KycProfile profile = profileOf(customerId);
        int tier = profile.getTier();
        TierLimit limit = spendingLimits.limitsFor(tier);

        BigDecimal sentToday =
                nairaAccount(customerId)
                        .map(account -> Money.ofMinor(spendingLimits.sentToday(account.getId()), Currency.of(CURRENCY)))
                        .map(Money::toMajor)
                        .orElse(BigDecimal.ZERO);

        KycTierResponse.Upgrade upgrade =
                upgradeRepository
                        .findFirstByKycProfileOrderByRequestedAtDesc(profile)
                        .map(found -> new KycTierResponse.Upgrade(
                                found.getRequestedTier(),
                                found.getStatus(),
                                found.getReason(),
                                found.getRequestedAt(),
                                found.getDecidedAt(),
                                found.getReviewDueAt(),
                                !currentProblems(found).isEmpty(),
                                found.isDecidedAutomatically()
                        ))
                        .orElse(null);

        return new KycTierResponse(
                tier,
                CURRENCY,
                limit.getDailySend(),
                sentToday,
                limit.getMaxBalance(),
                spendingLimits.endOfToday(),
                tier < 3 ? tier + 1 : null,
                nextTierNeeds(profile),
                upgrade,
                List.of(
                        level(1, "Your BVN or NIN and an ID document, approved by compliance"),
                        level(2, "Both your BVN and your NIN verified"),
                        level(3, "A proof of address, reviewed within " + upgradeProperties.getReviewWorkingDays() + " working days")
                ),
                upgradeProperties.getReviewWorkingDays()
        );
    }

    @Transactional
    public KycTierResponse requestAddressUpgrade(UUID customerId, byte[] content) {

        KycProfile profile =
                profileRepository
                        .findByCustomerForUpdate(customer(customerId))
                        .orElseThrow(KycNotFoundException::new);

        if (!profile.isVerified()) {
            throw new InvalidKycStateException("Finish identity verification first");
        }

        if (profile.getTier() >= 3) {
            throw new InvalidKycStateException("You are already on the highest tier");
        }

        if (profile.getTier() < 2) {
            throw new InvalidKycStateException("Verify both your BVN and NIN (tier 2) before asking for tier 3");
        }

        if (upgradeRepository.existsByKycProfileAndStatus(profile, KycTierUpgradeStatus.PENDING)) {
            throw new InvalidKycStateException("Your upgrade request is already being reviewed");
        }

        KycDocument document = documentService.store(profile, KycDocumentType.PROOF_OF_ADDRESS, null, content);

        Instant now = Instant.now();

        KycTierUpgrade upgrade = KycTierUpgrade.request(
                profile, 3, document, now,
                WorkingDays.after(now, upgradeProperties.getReviewWorkingDays(), timeZone)
        );

        List<String> problems = problems(profile, 3, now);

        if (!problems.isEmpty()) {
            upgrade.flag(String.join("; ", problems));
        }

        upgradeRepository.save(upgrade);

        log.info("Customer {} asked for tier 3; review due {}{}", customerId, upgrade.getReviewDueAt(),
                problems.isEmpty() ? "" : ", flagged: " + problems);

        return tier(customerId);
    }

    @Transactional(readOnly = true)
    public List<KycTierUpgradeResponse> pending() {

        return upgradeRepository
                .findByStatusOrderByRequestedAtAsc(KycTierUpgradeStatus.PENDING)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public KycTierUpgradeResponse approve(UUID upgradeId, UUID reviewerId) {

        KycTierUpgrade upgrade = reviewable(upgradeId, reviewerId);

        decide(() -> upgrade.approve(reviewerId));

        log.info("Reviewer {} approved tier {} for KYC {}",
                reviewerId, upgrade.getRequestedTier(), upgrade.getKycProfile().getId());

        return toResponse(upgradeRepository.save(upgrade));
    }

    @Transactional
    public KycTierUpgradeResponse reject(UUID upgradeId, UUID reviewerId, String reason) {

        KycTierUpgrade upgrade = reviewable(upgradeId, reviewerId);

        decide(() -> upgrade.reject(reviewerId, reason.trim()));

        return toResponse(upgradeRepository.save(upgrade));
    }

    @Transactional(readOnly = true)
    public List<UUID> dueForAutomaticDecision() {
        return upgradeRepository.findDue(KycTierUpgradeStatus.PENDING, Instant.now());
    }

    @Transactional
    public Optional<KycTierUpgradeStatus> decideAutomatically(UUID upgradeId) {

        KycTierUpgrade upgrade =
                upgradeRepository
                        .findByIdForUpdate(upgradeId)
                        .orElseThrow(KycNotFoundException::new);

        Instant now = Instant.now();

        if (upgrade.getStatus() != KycTierUpgradeStatus.PENDING
                || upgrade.isFlagged()
                || upgrade.getReviewDueAt().isAfter(now)) {
            return Optional.empty();
        }

        List<String> problems = problems(upgrade.getKycProfile(), upgrade.getRequestedTier(), now);

        if (!problems.isEmpty()) {
            upgrade.flag(String.join("; ", problems));
            upgradeRepository.save(upgrade);
            log.warn("Tier upgrade {} was not approved automatically: {}", upgradeId, problems);
            return Optional.empty();
        }

        upgrade.approveAutomatically();
        upgradeRepository.save(upgrade);

        log.info("Tier upgrade {} approved automatically after the review period", upgradeId);

        return Optional.of(KycTierUpgradeStatus.APPROVED);
    }

    private List<String> currentProblems(KycTierUpgrade upgrade) {

        if (upgrade.isFlagged()) {
            return List.of(upgrade.getFlags().split("; "));
        }

        if (upgrade.getStatus() != KycTierUpgradeStatus.PENDING) {
            return List.of();
        }

        return problems(upgrade.getKycProfile(), upgrade.getRequestedTier(), Instant.now());
    }

    private List<String> problems(KycProfile profile, int requestedTier, Instant now) {

        List<String> problems = new ArrayList<>();
        Customer customer = profile.getCustomer();

        if (customer.getStatus() == CustomerStatus.SUSPENDED || customer.getStatus() == CustomerStatus.CLOSED) {
            problems.add("The customer is " + customer.getStatus().name().toLowerCase().replace('_', ' '));
        }

        if (!profile.isVerified()) {
            problems.add("KYC is no longer verified");
        } else if (profile.getTier() != requestedTier - 1) {
            problems.add("The customer is on tier " + profile.getTier() + ", not tier " + (requestedTier - 1));
        }

        accountRepository
                .findByCustomerIdOrderByCreatedAtAsc(customer.getId())
                .stream()
                .filter(account -> account.getStatus() == AccountStatus.FROZEN)
                .forEach(account -> problems.add("Account " + account.getAccountNumber() + " is frozen"));

        Instant recent = now.minus(Duration.ofDays(upgradeProperties.getRecentRejectionDays()));

        if (upgradeRepository.existsByKycProfileAndStatusAndDecidedAtAfter(profile, KycTierUpgradeStatus.REJECTED, recent)) {
            problems.add("An upgrade was declined in the last " + upgradeProperties.getRecentRejectionDays() + " days");
        }

        return problems;
    }

    private KycTierUpgrade reviewable(UUID upgradeId, UUID reviewerId) {

        KycTierUpgrade upgrade =
                upgradeRepository
                        .findByIdForUpdate(upgradeId)
                        .orElseThrow(KycNotFoundException::new);

        if (upgrade.getKycProfile().getCustomer().getId().equals(reviewerId)) {
            throw new AccessDeniedException("Reviewers cannot act on their own KYC");
        }

        return upgrade;
    }

    private KycTierResponse.TierLevel level(int tier, String requirement) {
        TierLimit limit = spendingLimits.limitsFor(tier);
        return new KycTierResponse.TierLevel(tier, limit.getDailySend(), limit.getMaxBalance(), requirement);
    }

    private List<String> nextTierNeeds(KycProfile profile) {

        List<String> needs = new ArrayList<>();

        if (!profile.isVerified()) {
            needs.add("Finish identity verification");
            return needs;
        }

        if (profile.getTier() < 2) {
            for (KycVerificationType type : List.of(KycVerificationType.BVN, KycVerificationType.NIN)) {
                if (!verificationRepository.existsByKycProfileAndVerificationTypeAndResult(
                        profile, type, VerificationResult.PASSED)) {
                    needs.add("Verify your " + type);
                }
            }
        } else if (profile.getTier() == 2) {
            needs.add("Upload a proof of address (utility bill or bank statement, under 3 months old)");
        }

        return needs;
    }

    private Optional<Account> nairaAccount(UUID customerId) {

        return accountRepository
                .findByCustomerIdOrderByCreatedAtAsc(customerId)
                .stream()
                .filter(account -> account.getCurrency().equals(CURRENCY))
                .filter(account -> account.getStatus() != AccountStatus.CLOSED)
                .findFirst();
    }

    private KycProfile profileOf(UUID customerId) {
        return profileRepository.findByCustomer(customer(customerId)).orElseThrow(KycNotFoundException::new);
    }

    private Customer customer(UUID customerId) {
        return customerRepository.findById(customerId).orElseThrow(() -> new CustomerNotFoundException(customerId));
    }

    private static void decide(Runnable decision) {
        try {
            decision.run();
        } catch (IllegalStateException exception) {
            throw new InvalidKycStateException(exception.getMessage());
        }
    }

    private KycTierUpgradeResponse toResponse(KycTierUpgrade upgrade) {

        KycProfile profile = upgrade.getKycProfile();
        Customer customer = profile.getCustomer();

        return new KycTierUpgradeResponse(
                upgrade.getId(),
                profile.getId(),
                customer.getId(),
                customer.getFirstName() + " " + customer.getLastName(),
                customer.getEmail(),
                profile.getTier(),
                upgrade.getRequestedTier(),
                upgrade.getDocument().getId(),
                upgrade.getDocument().getDocumentType(),
                upgrade.getStatus(),
                upgrade.getReason(),
                upgrade.getRequestedAt(),
                upgrade.getDecidedAt(),
                upgrade.getReviewDueAt(),
                currentProblems(upgrade),
                upgrade.isDecidedAutomatically()
        );
    }
}
