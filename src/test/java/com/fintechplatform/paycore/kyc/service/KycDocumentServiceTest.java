package com.fintechplatform.paycore.kyc.service;

import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.customer.exception.CustomerNotFoundException;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
import com.fintechplatform.paycore.kyc.dto.KycDocumentResponse;
import com.fintechplatform.paycore.kyc.entity.KycDocument;
import com.fintechplatform.paycore.kyc.entity.KycProfile;
import com.fintechplatform.paycore.kyc.enums.KycDocumentType;
import com.fintechplatform.paycore.kyc.enums.KycStatus;
import com.fintechplatform.paycore.kyc.exception.InvalidKycDocumentException;
import com.fintechplatform.paycore.kyc.exception.InvalidKycStateException;
import com.fintechplatform.paycore.kyc.exception.KycNotFoundException;
import com.fintechplatform.paycore.kyc.repository.KycDocumentRepository;
import com.fintechplatform.paycore.kyc.repository.KycProfileRepository;
import com.fintechplatform.paycore.kyc.storage.KycDocumentStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KycDocumentServiceTest {

    private static final byte[] PDF = {0x25, 0x50, 0x44, 0x46, 0x2D, 0x31};

    private static final byte[] PNG =
            {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00};

    private static final byte[] JPEG =
            {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private KycProfileRepository kycProfileRepository;

    @Mock
    private KycDocumentRepository kycDocumentRepository;

    @Mock
    private KycDocumentStorage storage;

    private KycDocumentService service;

    private UUID customerId;
    private Customer customer;
    private KycProfile profile;

    @BeforeEach
    void setUp() {

        service =
                new KycDocumentService(
                        customerRepository,
                        kycProfileRepository,
                        kycDocumentRepository,
                        storage
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

    @Test
    void shouldStoreFileAndSaveMetadataOnly() {

        profile.start();
        stubProfile();
        stubStorage("pdf");

        KycDocumentResponse response =
                service.upload(customerId, KycDocumentType.PASSPORT, " A123 ", PDF);

        assertThat(response.kycId()).isEqualTo(profile.getId());
        assertThat(response.documentType()).isEqualTo(KycDocumentType.PASSPORT);
        assertThat(response.kycStatus()).isEqualTo(KycStatus.IN_PROGRESS);

        ArgumentCaptor<KycDocument> saved = ArgumentCaptor.forClass(KycDocument.class);
        verify(kycDocumentRepository).save(saved.capture());

        assertThat(saved.getValue().getKycProfile()).isSameAs(profile);
        assertThat(saved.getValue().getStorageKey())
                .isEqualTo(profile.getId() + "/stored.pdf");
        assertThat(saved.getValue().getDocumentNumber()).isEqualTo("A123");
    }

    @Test
    void shouldDetectPngAndJpegFromContent() {

        profile.start();
        stubProfile();
        stubStorage("png");
        stubStorage("jpg");

        service.upload(customerId, KycDocumentType.NATIONAL_ID, null, PNG);
        service.upload(customerId, KycDocumentType.PROOF_OF_ADDRESS, null, JPEG);

        verify(storage).store(profile.getId().toString(), "png", PNG);
        verify(storage).store(profile.getId().toString(), "jpg", JPEG);
    }

    @Test
    void shouldStoreBlankDocumentNumberAsNull() {

        profile.start();
        stubProfile();
        stubStorage("pdf");

        service.upload(customerId, KycDocumentType.PASSPORT, "  ", PDF);

        ArgumentCaptor<KycDocument> saved = ArgumentCaptor.forClass(KycDocument.class);
        verify(kycDocumentRepository).save(saved.capture());

        assertThat(saved.getValue().getDocumentNumber()).isNull();
    }

    @Test
    void shouldNotUploadBeforeKycIsStarted() {

        stubProfile();

        assertThatThrownBy(() ->
                service.upload(customerId, KycDocumentType.PASSPORT, null, PDF)
        )
                .isInstanceOf(InvalidKycStateException.class)
                .hasMessage("Documents can only be uploaded while KYC is in progress");

        verifyNoInteractions(storage, kycDocumentRepository);
    }

    @Test
    void shouldNotUploadWhileUnderReview() {

        profile.start();
        profile.submit();
        profile.startReview();
        stubProfile();

        assertThatThrownBy(() ->
                service.upload(customerId, KycDocumentType.PASSPORT, null, PDF)
        )
                .isInstanceOf(InvalidKycStateException.class);

        verifyNoInteractions(storage, kycDocumentRepository);
    }

    @Test
    void shouldRejectEmptyFile() {

        assertThatThrownBy(() ->
                service.upload(customerId, KycDocumentType.PASSPORT, null, new byte[0])
        )
                .isInstanceOf(InvalidKycDocumentException.class)
                .hasMessage("Document file is empty");

        verifyNoInteractions(storage);
    }

    @Test
    void shouldRejectUnsupportedFileTypeWhateverItIsCalled() {

        byte[] executable = {0x4D, 0x5A, (byte) 0x90, 0x00};

        assertThatThrownBy(() ->
                service.upload(customerId, KycDocumentType.PASSPORT, null, executable)
        )
                .isInstanceOf(InvalidKycDocumentException.class)
                .hasMessage("Document must be a PDF, PNG or JPEG file");

        verifyNoInteractions(storage);
    }

    @Test
    void shouldRejectFileOverFiveMegabytes() {

        byte[] large = new byte[KycDocumentService.MAX_DOCUMENT_BYTES + 1];
        System.arraycopy(PDF, 0, large, 0, PDF.length);

        assertThatThrownBy(() ->
                service.upload(customerId, KycDocumentType.PASSPORT, null, large)
        )
                .isInstanceOf(InvalidKycDocumentException.class)
                .hasMessageContaining("5 MB");

        verifyNoInteractions(storage);
    }

    @Test
    void shouldRejectOverlongDocumentNumber() {

        assertThatThrownBy(() ->
                service.upload(
                        customerId,
                        KycDocumentType.PASSPORT,
                        "x".repeat(256),
                        PDF
                )
        )
                .isInstanceOf(InvalidKycDocumentException.class);

        verifyNoInteractions(storage);
    }

    @Test
    void shouldRequireDocumentType() {

        assertThatThrownBy(() -> service.upload(customerId, null, null, PDF))
                .isInstanceOf(InvalidKycDocumentException.class);
    }

    @Test
    void shouldThrowForUnknownCustomer() {

        when(customerRepository.findById(customerId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.upload(customerId, KycDocumentType.PASSPORT, null, PDF)
        )
                .isInstanceOf(CustomerNotFoundException.class);
    }

    @Test
    void shouldThrowWithoutKycProfile() {

        when(customerRepository.findById(customerId))
                .thenReturn(Optional.of(customer));
        when(kycProfileRepository.findByCustomerForUpdate(customer))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.upload(customerId, KycDocumentType.PASSPORT, null, PDF)
        )
                .isInstanceOf(KycNotFoundException.class);

        verifyNoInteractions(storage);
    }

    private void stubProfile() {

        when(customerRepository.findById(customerId))
                .thenReturn(Optional.of(customer));

        when(kycProfileRepository.findByCustomerForUpdate(customer))
                .thenReturn(Optional.of(profile));
    }

    private void stubStorage(String extension) {

        when(storage.store(eq(profile.getId().toString()), eq(extension), any()))
                .thenReturn(profile.getId() + "/stored." + extension);

        lenient()
                .when(kycDocumentRepository.save(any(KycDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }
}
