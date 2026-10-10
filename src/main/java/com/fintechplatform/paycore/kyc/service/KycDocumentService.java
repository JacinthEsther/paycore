package com.fintechplatform.paycore.kyc.service;

import com.fintechplatform.paycore.customer.exception.CustomerNotFoundException;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
import com.fintechplatform.paycore.kyc.dto.KycDocumentResponse;
import com.fintechplatform.paycore.kyc.dto.KycDocumentSummary;
import com.fintechplatform.paycore.kyc.entity.KycDocument;
import com.fintechplatform.paycore.kyc.entity.KycProfile;
import com.fintechplatform.paycore.kyc.enums.KycDocumentType;
import com.fintechplatform.paycore.kyc.exception.InvalidKycDocumentException;
import com.fintechplatform.paycore.kyc.exception.InvalidKycStateException;
import com.fintechplatform.paycore.kyc.exception.KycDocumentNotFoundException;
import com.fintechplatform.paycore.kyc.exception.KycNotFoundException;
import com.fintechplatform.paycore.kyc.repository.KycDocumentRepository;
import com.fintechplatform.paycore.kyc.repository.KycProfileRepository;
import com.fintechplatform.paycore.kyc.storage.KycDocumentStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

@Service
public class KycDocumentService {

    private static final Logger log =
            LoggerFactory.getLogger(KycDocumentService.class);

    static final int MAX_DOCUMENT_BYTES = 5 * 1024 * 1024;

    private final CustomerRepository customerRepository;
    private final KycProfileRepository kycProfileRepository;
    private final KycDocumentRepository kycDocumentRepository;
    private final KycDocumentStorage storage;

    public KycDocumentService(
            CustomerRepository customerRepository,
            KycProfileRepository kycProfileRepository,
            KycDocumentRepository kycDocumentRepository,
            KycDocumentStorage storage
    ) {
        this.customerRepository = customerRepository;
        this.kycProfileRepository = kycProfileRepository;
        this.kycDocumentRepository = kycDocumentRepository;
        this.storage = storage;
    }

    @Transactional(readOnly = true)
    public List<KycDocumentSummary> documentsOf(UUID kycId) {

        KycProfile profile =
                kycProfileRepository
                        .findById(kycId)
                        .orElseThrow(KycNotFoundException::new);

        return kycDocumentRepository
                .findByKycProfileOrderByCreatedAtAscIdAsc(profile)
                .stream()
                .map(document -> new KycDocumentSummary(
                        document.getId(),
                        document.getDocumentType(),
                        document.getDocumentNumber(),
                        document.getCreatedAt()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public DocumentContent content(UUID documentId) {

        KycDocument document =
                kycDocumentRepository
                        .findById(documentId)
                        .orElseThrow(KycDocumentNotFoundException::new);

        String key = document.getStorageKey();
        String mediaType = key.endsWith(".pdf") ? "application/pdf"
                : key.endsWith(".png") ? "image/png"
                : "image/jpeg";

        return new DocumentContent(storage.read(key), mediaType);
    }

    public record DocumentContent(byte[] bytes, String mediaType) {
    }

    @Transactional
    public KycDocumentResponse upload(
            UUID customerId,
            KycDocumentType documentType,
            String documentNumber,
            byte[] content
    ) {

        if (documentType == null) {
            throw new InvalidKycDocumentException("Document type is required");
        }

        requireValid(documentNumber, content);

        KycProfile profile =
                kycProfileRepository
                        .findByCustomerForUpdate(
                                customerRepository
                                        .findById(customerId)
                                        .orElseThrow(() ->
                                                new CustomerNotFoundException(customerId)
                                        )
                        )
                        .orElseThrow(KycNotFoundException::new);

        if (!profile.isInProgress()) {
            throw new InvalidKycStateException(
                    "Documents can only be uploaded while KYC is in progress"
            );
        }

        KycDocument document = store(profile, documentType, documentNumber, content);

        return new KycDocumentResponse(
                document.getId(),
                profile.getId(),
                document.getDocumentType(),
                profile.getStatus(),
                document.getCreatedAt()
        );
    }

    @Transactional
    public KycDocument store(
            KycProfile profile,
            KycDocumentType documentType,
            String documentNumber,
            byte[] content
    ) {

        String extension = requireValid(documentNumber, content);

        String storageKey =
                storage.store(profile.getId().toString(), extension, content);

        deleteFileIfTransactionRollsBack(storageKey);

        return kycDocumentRepository.save(
                KycDocument.create(
                        profile,
                        documentType,
                        storageKey,
                        blankToNull(documentNumber)
                )
        );
    }

    private String requireValid(String documentNumber, byte[] content) {

        if (documentNumber != null && documentNumber.length() > 255) {
            throw new InvalidKycDocumentException(
                    "Document number must not exceed 255 characters"
            );
        }

        return detectExtension(content);
    }

    private String detectExtension(byte[] content) {

        if (content == null || content.length == 0) {
            throw new InvalidKycDocumentException("Document file is empty");
        }

        if (content.length > MAX_DOCUMENT_BYTES) {
            throw new InvalidKycDocumentException(
                    "Document file must not exceed 5 MB"
            );
        }

        if (startsWith(content, 0x25, 0x50, 0x44, 0x46)) {
            return "pdf";
        }

        if (startsWith(content, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "png";
        }

        if (startsWith(content, 0xFF, 0xD8, 0xFF)) {
            return "jpg";
        }

        throw new InvalidKycDocumentException(
                "Document must be a PDF, PNG or JPEG file"
        );
    }

    private boolean startsWith(byte[] content, int... signature) {

        if (content.length < signature.length) {
            return false;
        }

        for (int i = 0; i < signature.length; i++) {
            if ((content[i] & 0xFF) != signature[i]) {
                return false;
            }
        }

        return true;
    }

    private void deleteFileIfTransactionRollsBack(String storageKey) {

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status == STATUS_COMMITTED) {
                            return;
                        }
                        try {
                            storage.delete(storageKey);
                        } catch (RuntimeException exception) {
                            log.warn(
                                    "Could not remove orphaned KYC document {}",
                                    storageKey,
                                    exception
                            );
                        }
                    }
                }
        );
    }

    private String blankToNull(String value) {

        return value == null || value.isBlank() ? null : value.trim();
    }
}
