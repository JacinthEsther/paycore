package com.fintechplatform.paycore.kyc.entity;

import com.fintechplatform.paycore.common.persistence.UuidV7;
import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.kyc.enums.KycStatus;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * The KYC aggregate. Its status is independent of Customer.status and
 * only changes through the transition methods below, never a setter.
 *
 * <pre>
 * NOT_STARTED -> IN_PROGRESS -> SUBMITTED -> UNDER_REVIEW
 * UNDER_REVIEW -> VERIFIED | REJECTED | ADDITIONAL_INFO_REQUIRED
 * ADDITIONAL_INFO_REQUIRED -> IN_PROGRESS
 * </pre>
 *
 * Verification results (e.g. a passed BVN check) never move the status:
 * the customer submits the package and a reviewer decides.
 */
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

    /**
     * Failed BVN checks before this instant no longer count toward the
     * retry limit. The attempts themselves are kept as history.
     */
    @Column(name = "bvn_attempts_reset_at")
    private Instant bvnAttemptsResetAt;

    /**
     * Customer id of the admin who performed the most recent reset.
     */
    @Column(name = "bvn_attempts_reset_by")
    private UUID bvnAttemptsResetBy;

    /**
     * Why the most recent reviewer rejected the profile or asked for more
     * information; null after an approval. Shown to the customer.
     */
    @Column(name = "review_reason", length = 1000)
    private String reviewReason;

    /**
     * Customer id of the reviewer who made the most recent decision.
     */
    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

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

    public void approve(UUID reviewerId) {

        if (status != KycStatus.UNDER_REVIEW) {
            throw new IllegalStateException(
                    "KYC cannot be approved from status " + status
            );
        }

        status = KycStatus.VERIFIED;
        recordDecision(reviewerId, null);
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

    /**
     * Gives the customer a fresh set of BVN attempts. Only meaningful
     * while the customer can still run BVN checks.
     */
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
