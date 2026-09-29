package com.fintechplatform.paycore.kyc.controller;

import com.fintechplatform.paycore.kyc.dto.BvnAttemptResponse;
import com.fintechplatform.paycore.kyc.dto.BvnAttemptsResetResponse;
import com.fintechplatform.paycore.kyc.dto.BvnAttemptsResponse;
import com.fintechplatform.paycore.kyc.dto.KycDocumentResponse;
import com.fintechplatform.paycore.kyc.dto.KycResponse;
import com.fintechplatform.paycore.kyc.dto.KycVerificationResponse;
import com.fintechplatform.paycore.kyc.dto.PageInfo;
import com.fintechplatform.paycore.kyc.dto.VerifyBvnRequest;
import com.fintechplatform.paycore.kyc.enums.KycDocumentType;
import com.fintechplatform.paycore.kyc.enums.KycStatus;
import com.fintechplatform.paycore.kyc.enums.VerificationResult;
import com.fintechplatform.paycore.kyc.exception.BvnAttemptLimitExceededException;
import com.fintechplatform.paycore.kyc.exception.BvnIpRateLimitExceededException;
import com.fintechplatform.paycore.kyc.exception.InvalidKycStateException;
import com.fintechplatform.paycore.kyc.exception.KycAlreadyExistsException;
import com.fintechplatform.paycore.kyc.exception.KycNotFoundException;
import com.fintechplatform.paycore.kyc.exception.KycProviderException;
import com.fintechplatform.paycore.kyc.exception.InvalidKycDocumentException;
import com.fintechplatform.paycore.kyc.service.KycDocumentService;
import com.fintechplatform.paycore.kyc.service.KycService;
import com.fintechplatform.paycore.security.CurrentUser;
import com.fintechplatform.paycore.identity.repository.LoginSessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(KycController.class)
@AutoConfigureMockMvc(addFilters = false)
class KycControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KycService kycService;

    // Needed by CurrentUserJwtAuthenticationConverter, which this slice loads.
    @MockitoBean
    private LoginSessionRepository loginSessionRepository;

    @MockitoBean
    private KycDocumentService kycDocumentService;

    private final UUID customerId = UUID.randomUUID();
    private final UUID kycId = UUID.randomUUID();

    /**
     * Filters are disabled in this slice, so populate the context that
     * @AuthenticationPrincipal reads from directly.
     */
    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        new CurrentUser(customerId),
                        null,
                        List.of()
                )
        );
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldCreateKycForCurrentCustomer() throws Exception {

        when(kycService.createKyc(customerId))
                .thenReturn(response(KycStatus.NOT_STARTED));

        mockMvc.perform(post("/api/v1/kyc"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(kycId.toString()))
                .andExpect(jsonPath("$.customerId").value(customerId.toString()))
                .andExpect(jsonPath("$.status").value("NOT_STARTED"));
    }

    @Test
    void shouldReturnConflictWhenKycExists() throws Exception {

        when(kycService.createKyc(customerId))
                .thenThrow(new KycAlreadyExistsException());

        mockMvc.perform(post("/api/v1/kyc"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("KYC_ALREADY_EXISTS"));
    }

    @Test
    void shouldReturnCurrentKyc() throws Exception {

        when(kycService.getKyc(customerId))
                .thenReturn(response(KycStatus.IN_PROGRESS));

        mockMvc.perform(get("/api/v1/kyc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    void shouldReturnKycStatus() throws Exception {

        when(kycService.getKyc(customerId))
                .thenReturn(response(KycStatus.UNDER_REVIEW));

        mockMvc.perform(get("/api/v1/kyc/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"));
    }

    @Test
    void shouldReturnNotFoundWithoutKyc() throws Exception {

        when(kycService.getKyc(customerId))
                .thenThrow(new KycNotFoundException());

        mockMvc.perform(get("/api/v1/kyc"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("KYC_NOT_FOUND"));
    }

    @Test
    void shouldStartKyc() throws Exception {

        when(kycService.startKyc(customerId))
                .thenReturn(response(KycStatus.IN_PROGRESS));

        mockMvc.perform(post("/api/v1/kyc/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    void shouldSubmitKyc() throws Exception {

        when(kycService.submitKyc(customerId))
                .thenReturn(response(KycStatus.SUBMITTED));

        mockMvc.perform(post("/api/v1/kyc/submit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
    }

    @Test
    void shouldUploadDocument() throws Exception {

        byte[] content = {0x25, 0x50, 0x44, 0x46, 0x2D};

        when(kycDocumentService.upload(
                customerId,
                KycDocumentType.PASSPORT,
                "A12345678",
                content
        ))
                .thenReturn(new KycDocumentResponse(
                        UUID.randomUUID(),
                        kycId,
                        KycDocumentType.PASSPORT,
                        KycStatus.IN_PROGRESS,
                        Instant.now()
                ));

        mockMvc.perform(
                        multipart("/api/v1/kyc/documents")
                                .file(new MockMultipartFile(
                                        "file",
                                        "passport.pdf",
                                        MediaType.APPLICATION_PDF_VALUE,
                                        content
                                ))
                                .param("documentType", "PASSPORT")
                                .param("documentNumber", "A12345678")
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.documentType").value("PASSPORT"))
                .andExpect(jsonPath("$.kycStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.documentNumber").doesNotExist());
    }

    @Test
    void shouldRejectUnknownDocumentType() throws Exception {

        mockMvc.perform(
                        multipart("/api/v1/kyc/documents")
                                .file(new MockMultipartFile(
                                        "file",
                                        "card.pdf",
                                        MediaType.APPLICATION_PDF_VALUE,
                                        new byte[]{0x25, 0x50, 0x44, 0x46}
                                ))
                                .param("documentType", "LIBRARY_CARD")
                )
                .andExpect(status().isBadRequest());

        verifyNoInteractions(kycDocumentService);
    }

    @Test
    void shouldRequireDocumentFile() throws Exception {

        mockMvc.perform(
                        multipart("/api/v1/kyc/documents")
                                .param("documentType", "PASSPORT")
                )
                .andExpect(status().isBadRequest());

        verifyNoInteractions(kycDocumentService);
    }

    @Test
    void shouldReturnBadRequestForInvalidDocument() throws Exception {

        when(kycDocumentService.upload(eq(customerId), any(), any(), any()))
                .thenThrow(new InvalidKycDocumentException(
                        "Document must be a PDF, PNG or JPEG file"
                ));

        mockMvc.perform(
                        multipart("/api/v1/kyc/documents")
                                .file(new MockMultipartFile(
                                        "file",
                                        "script.exe",
                                        MediaType.APPLICATION_OCTET_STREAM_VALUE,
                                        new byte[]{0x4D, 0x5A}
                                ))
                                .param("documentType", "PASSPORT")
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_KYC_DOCUMENT"));
    }

    @Test
    void shouldVerifyBvn() throws Exception {

        when(kycService.verifyBvn(eq(customerId), any(VerifyBvnRequest.class), any()))
                .thenReturn(new KycVerificationResponse(
                        kycId,
                        VerificationResult.PASSED,
                        KycStatus.UNDER_REVIEW,
                        "DOJAH",
                        null,
                        3
                ));

        mockMvc.perform(
                        post("/api/v1/kyc/bvn")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(bvnBody("22222222222", "1995-01-01"))
                                .header("X-Forwarded-For", "1.2.3.4")
                                .with(request -> {
                                    request.setRemoteAddr("198.51.100.23");
                                    return request;
                                })
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("PASSED"))
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"))
                .andExpect(jsonPath("$.provider").value("DOJAH"))
                .andExpect(jsonPath("$.remainingAttempts").value(3));

        // The connection's address is used; a client-supplied
        // X-Forwarded-For header is ignored.
        verify(kycService).verifyBvn(
                customerId,
                new VerifyBvnRequest("22222222222", "Esther", "Test", "1995-01-01"),
                "198.51.100.23"
        );
    }

    @Test
    void shouldReturnTooManyRequestsWhenNetworkLimitIsReached()
            throws Exception {

        when(kycService.verifyBvn(eq(customerId), any(VerifyBvnRequest.class), any()))
                .thenThrow(new BvnIpRateLimitExceededException(
                        Instant.now().plus(Duration.ofMinutes(30))
                ));

        mockMvc.perform(
                        post("/api/v1/kyc/bvn")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(bvnBody("22222222222", "1995-01-01"))
                )
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("BVN_IP_RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.retryAfter").exists())
                .andExpect(header().string(
                        "Retry-After",
                        org.hamcrest.Matchers.matchesPattern("1[78]\\d\\d")
                ));
    }

    @Test
    void shouldRejectMalformedBvn() throws Exception {

        mockMvc.perform(
                        post("/api/v1/kyc/bvn")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(bvnBody("12345", "1995-01-01"))
                )
                .andExpect(status().isBadRequest());

        verifyNoInteractions(kycService);
    }

    @Test
    void shouldRejectMalformedDateOfBirth() throws Exception {

        mockMvc.perform(
                        post("/api/v1/kyc/bvn")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(bvnBody("22222222222", "01/01/1995"))
                )
                .andExpect(status().isBadRequest());

        verifyNoInteractions(kycService);
    }

    @Test
    void shouldReturnBadGatewayWhenProviderIsDown() throws Exception {

        when(kycService.verifyBvn(eq(customerId), any(VerifyBvnRequest.class), any()))
                .thenThrow(new KycProviderException("Dojah returned HTTP 401: Invalid credentials"));

        mockMvc.perform(
                        post("/api/v1/kyc/bvn")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(bvnBody("22222222222", "1995-01-01"))
                )
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("KYC_PROVIDER_UNAVAILABLE"))
                .andExpect(jsonPath("$.message")
                        .value("Identity verification is temporarily unavailable"));
    }

    @Test
    void shouldReturnTooManyRequestsWhenBvnAttemptLimitIsReached()
            throws Exception {

        Instant retryAfter = Instant.now().plus(Duration.ofHours(2));

        when(kycService.verifyBvn(eq(customerId), any(VerifyBvnRequest.class), any()))
                .thenThrow(new BvnAttemptLimitExceededException(retryAfter));

        mockMvc.perform(
                        post("/api/v1/kyc/bvn")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(bvnBody("22222222222", "1995-01-01"))
                )
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("BVN_ATTEMPT_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.retryAfter").exists())
                .andExpect(header().string(
                        "Retry-After",
                        org.hamcrest.Matchers.matchesPattern("71\\d\\d")
                ));
    }

    @Test
    void shouldReturnConflictForInvalidKycState() throws Exception {

        when(kycService.verifyBvn(eq(customerId), any(VerifyBvnRequest.class), any()))
                .thenThrow(new InvalidKycStateException(
                        "KYC cannot be started from status UNDER_REVIEW"
                ));

        mockMvc.perform(
                        post("/api/v1/kyc/bvn")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(bvnBody("22222222222", "1995-01-01"))
                )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_KYC_STATE"));
    }

    @Test
    void shouldApproveKycAsReviewer() throws Exception {

        when(kycService.approve(kycId, customerId))
                .thenReturn(response(KycStatus.VERIFIED));

        mockMvc.perform(post("/api/v1/kyc/{kycId}/approve", kycId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("VERIFIED"));
    }

    @Test
    void shouldStartReviewAsReviewer() throws Exception {

        when(kycService.startReview(kycId, customerId))
                .thenReturn(response(KycStatus.UNDER_REVIEW));

        mockMvc.perform(post("/api/v1/kyc/{kycId}/start-review", kycId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UNDER_REVIEW"));
    }

    @Test
    void shouldRejectKycAsReviewer() throws Exception {

        when(kycService.reject(
                kycId,
                customerId,
                "Identity document could not be validated"
        ))
                .thenReturn(response(KycStatus.REJECTED));

        mockMvc.perform(
                        post("/api/v1/kyc/{kycId}/reject", kycId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        { "reason": "Identity document could not be validated" }
                                        """)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void shouldRequireReasonToReject() throws Exception {

        mockMvc.perform(
                        post("/api/v1/kyc/{kycId}/reject", kycId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        { "reason": " " }
                                        """)
                )
                .andExpect(status().isBadRequest());

        verifyNoInteractions(kycService);
    }

    @Test
    void shouldShowBvnAttemptsToAdmin() throws Exception {

        UUID attemptId = UUID.randomUUID();

        when(kycService.getBvnAttempts(kycId, 0, 20))
                .thenReturn(new BvnAttemptsResponse(
                        kycId,
                        KycStatus.IN_PROGRESS,
                        3,
                        "PT24H",
                        Instant.parse("2026-09-23T10:00:00Z"),
                        3,
                        0,
                        true,
                        Instant.parse("2026-09-24T11:00:00Z"),
                        null,
                        null,
                        List.of(new BvnAttemptResponse(
                                attemptId,
                                VerificationResult.FAILED,
                                "DOJAH",
                                null,
                                "Details do not match BVN record: last_name",
                                "203.0.113.7",
                                Instant.parse("2026-09-23T11:00:00Z"),
                                true
                        )),
                        new PageInfo(0, 20, 1, 1, false)
                ));

        // No paging params: defaults page=0, size=20.
        mockMvc.perform(get("/api/v1/kyc/{kycId}/bvn-attempts", kycId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(jsonPath("$.page.totalPages").value(1))
                .andExpect(jsonPath("$.page.hasNext").value(false))
                .andExpect(jsonPath("$.kycId").value(kycId.toString()))
                .andExpect(jsonPath("$.maxFailedAttempts").value(3))
                .andExpect(jsonPath("$.attemptWindow").value("PT24H"))
                .andExpect(jsonPath("$.failedAttemptsCounted").value(3))
                .andExpect(jsonPath("$.remainingAttempts").value(0))
                .andExpect(jsonPath("$.limited").value(true))
                .andExpect(jsonPath("$.retryAfter").value("2026-09-24T11:00:00Z"))
                .andExpect(jsonPath("$.attempts[0].id").value(attemptId.toString()))
                .andExpect(jsonPath("$.attempts[0].result").value("FAILED"))
                .andExpect(jsonPath("$.attempts[0].countsTowardLimit").value(true))
                .andExpect(jsonPath("$.attempts[0].ipAddress").value("203.0.113.7"))
                .andExpect(jsonPath("$.attempts[0].reason")
                        .value("Details do not match BVN record: last_name"));
    }

    @Test
    void shouldPassPagingParamsToService() throws Exception {

        when(kycService.getBvnAttempts(kycId, 3, 50))
                .thenReturn(new BvnAttemptsResponse(
                        kycId, KycStatus.IN_PROGRESS, 3, "PT24H",
                        Instant.now(), 0, 3, false, null, null, null,
                        List.of(),
                        new PageInfo(3, 50, 120, 3, false)
                ));

        mockMvc.perform(
                        get("/api/v1/kyc/{kycId}/bvn-attempts", kycId)
                                .param("page", "3")
                                .param("size", "50")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.number").value(3))
                .andExpect(jsonPath("$.attempts").isEmpty());

        verify(kycService).getBvnAttempts(kycId, 3, 50);
    }

    @Test
    void shouldRejectInvalidPagingParams() throws Exception {

        for (String[] params : new String[][]{
                {"page", "-1"},
                {"size", "0"},
                {"size", "101"},
                {"size", "abc"}
        }) {
            mockMvc.perform(
                            get("/api/v1/kyc/{kycId}/bvn-attempts", kycId)
                                    .param(params[0], params[1])
                    )
                    .andExpect(status().isBadRequest());
        }

        verifyNoInteractions(kycService);
    }

    @Test
    void shouldReturnNotFoundForAttemptsOfUnknownKyc() throws Exception {

        when(kycService.getBvnAttempts(kycId, 0, 20))
                .thenThrow(new KycNotFoundException());

        mockMvc.perform(get("/api/v1/kyc/{kycId}/bvn-attempts", kycId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("KYC_NOT_FOUND"));
    }

    @Test
    void shouldResetBvnAttemptsAsAdmin() throws Exception {

        Instant resetAt = Instant.parse("2026-09-24T10:00:00Z");

        when(kycService.resetBvnAttempts(kycId, customerId))
                .thenReturn(new BvnAttemptsResetResponse(
                        kycId,
                        3,
                        resetAt,
                        customerId
                ));

        mockMvc.perform(post("/api/v1/kyc/{kycId}/bvn-attempts/reset", kycId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kycId").value(kycId.toString()))
                .andExpect(jsonPath("$.remainingAttempts").value(3))
                .andExpect(jsonPath("$.resetAt").value("2026-09-24T10:00:00Z"))
                .andExpect(jsonPath("$.resetBy").value(customerId.toString()));
    }

    @Test
    void shouldReturnConflictWhenResettingAttemptsInWrongState()
            throws Exception {

        when(kycService.resetBvnAttempts(kycId, customerId))
                .thenThrow(new InvalidKycStateException(
                        "BVN attempts cannot be reset from status VERIFIED"
                ));

        mockMvc.perform(post("/api/v1/kyc/{kycId}/bvn-attempts/reset", kycId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_KYC_STATE"));
    }

    @Test
    void shouldRequestAdditionalInformationAsReviewer() throws Exception {

        when(kycService.requestAdditionalInformation(
                kycId,
                customerId,
                "Please provide a clearer proof of address"
        ))
                .thenReturn(response(KycStatus.ADDITIONAL_INFO_REQUIRED));

        mockMvc.perform(
                        post("/api/v1/kyc/{kycId}/request-information", kycId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        { "reason": "Please provide a clearer proof of address" }
                                        """)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ADDITIONAL_INFO_REQUIRED"));
    }

    @Test
    void shouldRequireReasonToRequestInformation() throws Exception {

        mockMvc.perform(
                        post("/api/v1/kyc/{kycId}/request-information", kycId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}")
                )
                .andExpect(status().isBadRequest());

        verifyNoInteractions(kycService);
    }

    private KycResponse response(KycStatus status) {

        return new KycResponse(
                kycId,
                customerId,
                status,
                null,
                Instant.now(),
                Instant.now()
        );
    }

    private String bvnBody(String bvn, String dob) {

        return """
                {
                  "bvn": "%s",
                  "firstName": "Esther",
                  "lastName": "Test",
                  "dateOfBirth": "%s"
                }
                """.formatted(bvn, dob);
    }
}
