package com.fintechplatform.paycore.kyc.controller;

import com.fintechplatform.paycore.kyc.dto.KycReviewRequest;
import com.fintechplatform.paycore.kyc.dto.BvnAttemptsResetResponse;
import com.fintechplatform.paycore.kyc.dto.BvnAttemptsResponse;
import com.fintechplatform.paycore.kyc.dto.KycDocumentResponse;
import com.fintechplatform.paycore.kyc.dto.KycResponse;
import com.fintechplatform.paycore.kyc.dto.KycReviewQueueResponse;
import com.fintechplatform.paycore.kyc.dto.KycStatusResponse;
import com.fintechplatform.paycore.kyc.dto.KycVerificationResponse;
import com.fintechplatform.paycore.kyc.dto.VerifyBvnRequest;
import com.fintechplatform.paycore.kyc.dto.VerifyNinRequest;
import com.fintechplatform.paycore.kyc.enums.KycDocumentType;
import com.fintechplatform.paycore.kyc.enums.KycStatus;
import com.fintechplatform.paycore.kyc.enums.KycVerificationType;
import com.fintechplatform.paycore.kyc.service.KycDocumentService;
import com.fintechplatform.paycore.kyc.service.KycService;
import com.fintechplatform.paycore.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

/**
 * Customer endpoints act on the caller's own KYC (resolved from the
 * access token), so one customer can never touch another's profile.
 * They need KYC_READ or KYC_SUBMIT. Review endpoints need KYC_REVIEW
 * and refuse self-review.
 */
@RestController
@RequestMapping("/api/v1/kyc")
public class KycController {

    private final KycService kycService;
    private final KycDocumentService kycDocumentService;

    public KycController(
            KycService kycService,
            KycDocumentService kycDocumentService
    ) {
        this.kycService = kycService;
        this.kycDocumentService = kycDocumentService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('KYC_SUBMIT')")
    public ResponseEntity<KycResponse> createKyc(
            @AuthenticationPrincipal CurrentUser currentUser
    ) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(kycService.createKyc(currentUser.customerId()));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('KYC_READ')")
    public KycResponse getKyc(
            @AuthenticationPrincipal CurrentUser currentUser
    ) {

        return kycService.getKyc(currentUser.customerId());
    }

    @GetMapping("/status")
    @PreAuthorize("hasAuthority('KYC_READ')")
    public KycStatusResponse getStatus(
            @AuthenticationPrincipal CurrentUser currentUser
    ) {

        return new KycStatusResponse(
                kycService.getKyc(currentUser.customerId()).status()
        );
    }

    @PostMapping("/start")
    @PreAuthorize("hasAuthority('KYC_SUBMIT')")
    public KycResponse startKyc(
            @AuthenticationPrincipal CurrentUser currentUser
    ) {

        return kycService.startKyc(currentUser.customerId());
    }

    /**
     * Multipart upload of a PDF, PNG or JPEG (max 5 MB). Only metadata
     * and the storage key are kept in the database.
     */
    @PostMapping(
            value = "/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @PreAuthorize("hasAuthority('KYC_SUBMIT')")
    public ResponseEntity<KycDocumentResponse> uploadDocument(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam KycDocumentType documentType,
            @RequestParam(required = false) String documentNumber,
            @RequestPart("file") MultipartFile file
    ) throws IOException {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(kycDocumentService.upload(
                        currentUser.customerId(),
                        documentType,
                        documentNumber,
                        file.getBytes()
                ));
    }

    @PostMapping("/submit")
    @PreAuthorize("hasAuthority('KYC_SUBMIT')")
    public KycResponse submitKyc(
            @AuthenticationPrincipal CurrentUser currentUser
    ) {

        return kycService.submitKyc(currentUser.customerId());
    }

    /**
     * The client IP comes from the servlet request's remote address, never
     * from a raw X-Forwarded-For header (which clients can forge). Behind
     * a reverse proxy, the prod profile (application-prod.properties) makes
     * Tomcat set the remote address from X-Forwarded-For, but only for
     * requests arriving from trusted proxies.
     */
    @PostMapping("/bvn")
    @PreAuthorize("hasAuthority('KYC_SUBMIT')")
    public KycVerificationResponse verifyBvn(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody VerifyBvnRequest request,
            HttpServletRequest httpRequest
    ) {

        return kycService.verifyBvn(
                currentUser.customerId(),
                request,
                httpRequest.getRemoteAddr()
        );
    }

    /**
     * National Identification Number check; an alternative to the BVN.
     * Same client-IP handling and limits as {@link #verifyBvn}, counted
     * separately per check type.
     */
    @PostMapping("/nin")
    @PreAuthorize("hasAuthority('KYC_SUBMIT')")
    public KycVerificationResponse verifyNin(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody VerifyNinRequest request,
            HttpServletRequest httpRequest
    ) {

        return kycService.verifyNin(
                currentUser.customerId(),
                request,
                httpRequest.getRemoteAddr()
        );
    }

    /**
     * Review queue, most recently changed first. Omit {@code status} to
     * list profiles in every state.
     */
    @GetMapping("/reviews")
    @PreAuthorize("hasAuthority('KYC_REVIEW')")
    public KycReviewQueueResponse listProfiles(
            @RequestParam(required = false) KycStatus status,

            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "page must be 0 or greater")
            int page,

            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "size must be at least 1")
            @Max(value = 100, message = "size must not exceed 100")
            int size
    ) {

        return kycService.listProfiles(status, page, size);
    }

    @PostMapping("/{kycId}/start-review")
    @PreAuthorize("hasAuthority('KYC_REVIEW')")
    public KycResponse startReview(
            @PathVariable UUID kycId,
            @AuthenticationPrincipal CurrentUser reviewer
    ) {

        return kycService.startReview(kycId, reviewer.customerId());
    }

    @PostMapping("/{kycId}/approve")
    @PreAuthorize("hasAuthority('KYC_REVIEW')")
    public KycResponse approve(
            @PathVariable UUID kycId,
            @AuthenticationPrincipal CurrentUser reviewer
    ) {

        return kycService.approve(kycId, reviewer.customerId());
    }

    @PostMapping("/{kycId}/reject")
    @PreAuthorize("hasAuthority('KYC_REVIEW')")
    public KycResponse reject(
            @PathVariable UUID kycId,
            @AuthenticationPrincipal CurrentUser reviewer,
            @Valid @RequestBody KycReviewRequest request
    ) {

        return kycService.reject(
                kycId,
                reviewer.customerId(),
                request.reason()
        );
    }

    /**
     * Admin view of the customer's BVN retry limit and attempt history.
     * Read-only, so unlike the review actions it is also allowed on the
     * admin's own profile.
     *
     * <p>History is paginated ({@code page} is zero-based, newest first);
     * the limit summary describes the current state on every page.
     */
    @GetMapping("/{kycId}/bvn-attempts")
    @PreAuthorize("hasAuthority('KYC_REVIEW')")
    public BvnAttemptsResponse getBvnAttempts(
            @PathVariable UUID kycId,

            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "page must be 0 or greater")
            int page,

            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "size must be at least 1")
            @Max(value = 100, message = "size must not exceed 100")
            int size
    ) {

        return kycService.getBvnAttempts(kycId, page, size);
    }

    /**
     * Same view as {@link #getBvnAttempts} for NIN checks.
     */
    @GetMapping("/{kycId}/nin-attempts")
    @PreAuthorize("hasAuthority('KYC_REVIEW')")
    public BvnAttemptsResponse getNinAttempts(
            @PathVariable UUID kycId,

            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "page must be 0 or greater")
            int page,

            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "size must be at least 1")
            @Max(value = 100, message = "size must not exceed 100")
            int size
    ) {

        return kycService.getAttempts(kycId, KycVerificationType.NIN, page, size);
    }

    /**
     * Gives the customer a fresh set of BVN (and NIN) attempts. Failed attempts are
     * kept as history; only the retry-limit count starts over.
     */
    @PostMapping("/{kycId}/bvn-attempts/reset")
    @PreAuthorize("hasAuthority('KYC_REVIEW')")
    public BvnAttemptsResetResponse resetBvnAttempts(
            @PathVariable UUID kycId,
            @AuthenticationPrincipal CurrentUser admin
    ) {

        return kycService.resetBvnAttempts(kycId, admin.customerId());
    }

    @PostMapping("/{kycId}/request-information")
    @PreAuthorize("hasAuthority('KYC_REVIEW')")
    public KycResponse requestAdditionalInformation(
            @PathVariable UUID kycId,
            @AuthenticationPrincipal CurrentUser reviewer,
            @Valid @RequestBody KycReviewRequest request
    ) {

        return kycService.requestAdditionalInformation(
                kycId,
                reviewer.customerId(),
                request.reason()
        );
    }
}
