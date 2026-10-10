package com.fintechplatform.paycore.kyc.entity;

import com.fintechplatform.paycore.common.persistence.UuidV7;
import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.kyc.enums.KycStatus;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "kyc_profiles",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_kyc_profile_customer",
                        columnNames = "customer_id"
                )
        }
)
public class KycProfile {

    @Id
    @UuidV7
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "customer_id",
            nullable = false
    )
    private Customer customer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private KycStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "bvn_attempts_reset_at")
    private Instant bvnAttemptsResetAt;

    @Column(name = "bvn_attempts_reset_by")
    private UUID bvnAttemptsResetBy;

    @Column(name = "review_reason", length = 1000)
    private String reviewReason;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(nullable = false)
    private short tier;

    protected KycProfile() {
    }

    private KycProfile(Customer customer) {
        this.customer = customer;
        this.status = KycStatus.NOT_STARTED;

        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public static KycProfile create(Customer customer) {
        return new KycProfile(customer);
    }

    public void start() {

        if (status != KycStatus.NOT_STARTED
                && status != KycStatus.ADDITIONAL_INFO_REQUIRED) {

            throw new IllegalStateException(
                    "KYC cannot be started from status " + status
            );
        }

        status = KycStatus.IN_PROGRESS;
        touch();
    }

    public void submit() {

        if (status != KycStatus.IN_PROGRESS) {
            throw new IllegalStateException(
                    "KYC cannot be submitted from status " + status
            );
        }

        status = KycStatus.SUBMITTED;
        touch();
    }

    public void startReview() {

        if (status != KycStatus.SUBMITTED) {
            throw new IllegalStateException(
                    "KYC cannot enter review from status " + status
            );
        }

        status = KycStatus.UNDER_REVIEW;
        touch();
    }

    public void approve(UUID reviewerId, int tier) {

        if (status != KycStatus.UNDER_REVIEW) {
            throw new IllegalStateException(
                    "KYC cannot be approved from status " + status
            );
        }

        requireTier(tier);

        status = KycStatus.VERIFIED;
        this.tier = (short) tier;
        recordDecision(reviewerId, null);
    }

    public void raiseTier(int tier) {

        if (status != KycStatus.VERIFIED) {
            throw new IllegalStateException(
                    "Only verified KYC can move up a tier, not " + status
            );
        }

        requireTier(tier);

        if (tier > this.tier) {
            this.tier = (short) tier;
            touch();
        }
    }

    public void verifyForSeededAccount(int tier) {
        requireTier(tier);
        status = KycStatus.VERIFIED;
        this.tier = (short) tier;
        touch();
    }

    private static void requireTier(int tier) {
        if (tier < 1 || tier > 3) {
            throw new IllegalArgumentException("Tier must be 1, 2 or 3");
        }
    }

    public void reject(UUID reviewerId, String reason) {

        if (status != KycStatus.UNDER_REVIEW) {
            throw new IllegalStateException(
                    "KYC cannot be rejected from status " + status
            );
        }

        status = KycStatus.REJECTED;
        recordDecision(reviewerId, reason);
    }

    public void requestAdditionalInformation(UUID reviewerId, String reason) {

        if (status != KycStatus.UNDER_REVIEW) {
            throw new IllegalStateException(
                    "Additional information cannot be requested from status "
                            + status
            );
        }

        status = KycStatus.ADDITIONAL_INFO_REQUIRED;
        recordDecision(reviewerId, reason);
    }

    public boolean isVerified() {
        return status == KycStatus.VERIFIED;
    }

    public boolean isInProgress() {
        return status == KycStatus.IN_PROGRESS;
    }

    public void resetBvnAttempts(UUID adminId) {

        if (status != KycStatus.NOT_STARTED
                && status != KycStatus.IN_PROGRESS
                && status != KycStatus.ADDITIONAL_INFO_REQUIRED) {

            throw new IllegalStateException(
                    "BVN attempts cannot be reset from status " + status
            );
        }

        bvnAttemptsResetAt = Instant.now();
        bvnAttemptsResetBy = adminId;
        touch();
    }

    private void recordDecision(UUID reviewerId, String reason) {
        reviewedBy = reviewerId;
        reviewReason = reason;
        reviewedAt = Instant.now();
        updatedAt = reviewedAt;
    }

    private void touch() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public Customer getCustomer() {
        return customer;
    }

    public KycStatus getStatus() {
        return status;
    }

    public int getTier() {
        return tier;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getBvnAttemptsResetAt() {
        return bvnAttemptsResetAt;
    }

    public UUID getBvnAttemptsResetBy() {
        return bvnAttemptsResetBy;
    }

    public String getReviewReason() {
        return reviewReason;
    }

    public UUID getReviewedBy() {
        return reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }
}
