package com.fintechplatform.paycore.kyc.entity;

import com.fintechplatform.paycore.common.persistence.UuidV7;
import com.fintechplatform.paycore.kyc.enums.KycVerificationType;
import com.fintechplatform.paycore.kyc.enums.VerificationResult;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable record of one provider verification attempt. Attempts are
 * appended, never overwritten, and never store the raw identifier
 * (BVN/NIN) that was checked.
 */
@Entity
@Table(name = "kyc_verifications")
public class KycVerification {

    @Id
    @UuidV7
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "kyc_profile_id",
            nullable = false
    )
    private KycProfile kycProfile;

    @Enumerated(EnumType.STRING)
    @Column(
            name = "verification_type",
            nullable = false,
            length = 40
    )
    private KycVerificationType verificationType;

    @Column(nullable = false, length = 100)
    private String provider;

    @Column(
            name = "provider_reference",
            length = 255
    )
    private String providerReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private VerificationResult result;

    @Column(length = 1000)
    private String reason;

    /**
     * Normalized client network the attempt came from (see
     * {@link com.fintechplatform.paycore.common.net.ClientNetwork}).
     */
    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected KycVerification() {
    }

    private KycVerification(
            KycProfile kycProfile,
            KycVerificationType verificationType,
            String provider,
            String providerReference,
            VerificationResult result,
            String reason,
            String ipAddress
    ) {
        this.kycProfile = kycProfile;
        this.verificationType = verificationType;
        this.provider = provider;
        this.providerReference = providerReference;
        this.result = result;
        this.reason = reason;
        this.ipAddress = ipAddress;
        this.createdAt = Instant.now();
    }

    public static KycVerification create(
            KycProfile kycProfile,
            KycVerificationType verificationType,
            String provider,
            String providerReference,
            VerificationResult result,
            String reason,
            String ipAddress
    ) {
        return new KycVerification(
                kycProfile,
                verificationType,
                provider,
                providerReference,
                result,
                reason,
                ipAddress
        );
    }

    /**
     * An attempt with no known client network.
     */
    public static KycVerification create(
            KycProfile kycProfile,
            KycVerificationType verificationType,
            String provider,
            String providerReference,
            VerificationResult result,
            String reason
    ) {
        return create(
                kycProfile,
                verificationType,
                provider,
                providerReference,
                result,
                reason,
                null
        );
    }

    public UUID getId() {
        return id;
    }

    public KycProfile getKycProfile() {
        return kycProfile;
    }

    public KycVerificationType getVerificationType() {
        return verificationType;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public VerificationResult getResult() {
        return result;
    }

    public String getReason() {
        return reason;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
