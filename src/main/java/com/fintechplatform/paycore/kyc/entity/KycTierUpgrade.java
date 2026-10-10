package com.fintechplatform.paycore.kyc.entity;

import com.fintechplatform.paycore.common.persistence.UuidV7;
import com.fintechplatform.paycore.kyc.enums.KycTierUpgradeStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "kyc_tier_upgrades")
public class KycTierUpgrade {

    @Id
    @UuidV7
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "kyc_profile_id", nullable = false, updatable = false)
    private KycProfile kycProfile;

    @Column(name = "requested_tier", nullable = false, updatable = false)
    private short requestedTier;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false, updatable = false)
    private KycDocument document;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private KycTierUpgradeStatus status;

    @Column(length = 1000)
    private String reason;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "review_due_at", nullable = false)
    private Instant reviewDueAt;

    @Column(length = 1000)
    private String flags;

    @Column(name = "decided_automatically", nullable = false)
    private boolean decidedAutomatically;

    protected KycTierUpgrade() {
    }

    public static KycTierUpgrade request(
            KycProfile profile,
            int requestedTier,
            KycDocument document,
            Instant requestedAt,
            Instant reviewDueAt
    ) {

        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(document, "document");

        KycTierUpgrade upgrade = new KycTierUpgrade();
        upgrade.kycProfile = profile;
        upgrade.requestedTier = (short) requestedTier;
        upgrade.document = document;
        upgrade.status = KycTierUpgradeStatus.PENDING;
        upgrade.requestedAt = requestedAt;
        upgrade.reviewDueAt = Objects.requireNonNull(reviewDueAt, "reviewDueAt");
        return upgrade;
    }

    public void approve(UUID reviewerId) {
        decide(KycTierUpgradeStatus.APPROVED, reviewerId, null);
        kycProfile.raiseTier(requestedTier);
    }

    public void approveAutomatically() {
        if (flags != null) {
            throw new IllegalStateException("A flagged upgrade needs a reviewer's decision");
        }
        decide(KycTierUpgradeStatus.APPROVED, null, null);
        decidedAutomatically = true;
        kycProfile.raiseTier(requestedTier);
    }

    public void flag(String problems) {
        if (status != KycTierUpgradeStatus.PENDING) {
            throw new IllegalStateException("Only a pending upgrade can be flagged");
        }
        flags = Objects.requireNonNull(problems, "problems");
    }

    public boolean isFlagged() {
        return flags != null;
    }

    public Instant getReviewDueAt() {
        return reviewDueAt;
    }

    public String getFlags() {
        return flags;
    }

    public boolean isDecidedAutomatically() {
        return decidedAutomatically;
    }

    public void reject(UUID reviewerId, String reason) {
        decide(KycTierUpgradeStatus.REJECTED, reviewerId, Objects.requireNonNull(reason, "reason"));
    }

    private void decide(KycTierUpgradeStatus decision, UUID reviewerId, String reason) {
        if (status != KycTierUpgradeStatus.PENDING) {
            throw new IllegalStateException("This upgrade request was already " + status.name().toLowerCase());
        }
        status = decision;
        decidedBy = reviewerId;
        decidedAt = Instant.now();
        this.reason = reason;
    }

    public UUID getId() {
        return id;
    }

    public KycProfile getKycProfile() {
        return kycProfile;
    }

    public int getRequestedTier() {
        return requestedTier;
    }

    public KycDocument getDocument() {
        return document;
    }

    public KycTierUpgradeStatus getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public UUID getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}
