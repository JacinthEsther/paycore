package com.fintechplatform.paycore.kyc.entity;

import com.fintechplatform.paycore.common.persistence.UuidV7;
import com.fintechplatform.paycore.kyc.enums.KycDocumentType;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Metadata for an uploaded document. The file itself lives in object
 * storage; only its storage key is kept here.
 */
@Entity
@Table(name = "kyc_documents")
public class KycDocument {

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
            name = "document_type",
            nullable = false,
            length = 40
    )
    private KycDocumentType documentType;

    @Column(
            name = "storage_key",
            nullable = false,
            length = 500
    )
    private String storageKey;

    @Column(
            name = "document_number",
            length = 255
    )
    private String documentNumber;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected KycDocument() {
    }

    private KycDocument(
            KycProfile kycProfile,
            KycDocumentType documentType,
            String storageKey,
            String documentNumber
    ) {
        this.kycProfile = kycProfile;
        this.documentType = documentType;
        this.storageKey = storageKey;
        this.documentNumber = documentNumber;
        this.createdAt = Instant.now();
    }

    public static KycDocument create(
            KycProfile kycProfile,
            KycDocumentType documentType,
            String storageKey,
            String documentNumber
    ) {
        return new KycDocument(
                kycProfile,
                documentType,
                storageKey,
                documentNumber
        );
    }

    public UUID getId() {
        return id;
    }

    public KycProfile getKycProfile() {
        return kycProfile;
    }

    public KycDocumentType getDocumentType() {
        return documentType;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getDocumentNumber() {
        return documentNumber;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
