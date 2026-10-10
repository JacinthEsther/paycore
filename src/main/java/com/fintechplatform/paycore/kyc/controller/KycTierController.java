package com.fintechplatform.paycore.kyc.controller;

import com.fintechplatform.paycore.kyc.dto.KycReviewRequest;
import com.fintechplatform.paycore.kyc.dto.KycTierResponse;
import com.fintechplatform.paycore.kyc.dto.KycTierUpgradeResponse;
import com.fintechplatform.paycore.kyc.service.KycTierService;
import com.fintechplatform.paycore.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/kyc")
public class KycTierController {

    private final KycTierService tierService;

    public KycTierController(KycTierService tierService) {
        this.tierService = tierService;
    }

    @GetMapping("/tier")
    @PreAuthorize("hasAuthority('KYC_READ')")
    public KycTierResponse tier(@AuthenticationPrincipal CurrentUser currentUser) {
        return tierService.tier(currentUser.customerId());
    }

    @PostMapping(value = "/tier/upgrade", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('KYC_SUBMIT')")
    @ResponseStatus(HttpStatus.CREATED)
    public KycTierResponse requestUpgrade(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestPart("file") MultipartFile file
    ) throws IOException {
        return tierService.requestAddressUpgrade(currentUser.customerId(), file.getBytes());
    }

    @GetMapping("/tier-upgrades")
    @PreAuthorize("hasAuthority('KYC_REVIEW')")
    public List<KycTierUpgradeResponse> pending() {
        return tierService.pending();
    }

    @PostMapping("/tier-upgrades/{upgradeId}/approve")
    @PreAuthorize("hasAuthority('KYC_REVIEW')")
    public KycTierUpgradeResponse approve(
            @PathVariable UUID upgradeId,
            @AuthenticationPrincipal CurrentUser reviewer
    ) {
        return tierService.approve(upgradeId, reviewer.customerId());
    }

    @PostMapping("/tier-upgrades/{upgradeId}/reject")
    @PreAuthorize("hasAuthority('KYC_REVIEW')")
    public KycTierUpgradeResponse reject(
            @PathVariable UUID upgradeId,
            @AuthenticationPrincipal CurrentUser reviewer,
            @Valid @RequestBody KycReviewRequest request
    ) {
        return tierService.reject(upgradeId, reviewer.customerId(), request.reason());
    }
}
