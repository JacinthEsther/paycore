package com.fintechplatform.paycore.kyc.repository;

import com.fintechplatform.paycore.kyc.entity.KycDocument;
import com.fintechplatform.paycore.kyc.entity.KycProfile;
import com.fintechplatform.paycore.kyc.enums.KycDocumentType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface KycDocumentRepository
        extends JpaRepository<KycDocument, UUID> {

    List<KycDocument> findByKycProfileOrderByCreatedAtAscIdAsc(KycProfile kycProfile);

    boolean existsByKycProfile(KycProfile kycProfile);

    boolean existsByKycProfileAndDocumentType(
            KycProfile kycProfile,
            KycDocumentType documentType
    );
}
