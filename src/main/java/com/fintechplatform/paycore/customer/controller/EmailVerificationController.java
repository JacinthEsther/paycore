package com.fintechplatform.paycore.customer.controller;

import com.fintechplatform.paycore.customer.dto.request.VerifyEmailRequest;
import com.fintechplatform.paycore.customer.dto.response.CustomerResponse;
import com.fintechplatform.paycore.customer.service.EmailVerificationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, like login: the link may be opened on a device where the
 * customer is not signed in. The token itself is the proof.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class EmailVerificationController {

    private final EmailVerificationService emailVerificationService;

    public EmailVerificationController(EmailVerificationService emailVerificationService) {
        this.emailVerificationService = emailVerificationService;
    }

    /**
     * Verifies the email and, for a new customer, activates them.
     */
    @PostMapping("/verify-email")
    public CustomerResponse verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        return emailVerificationService.verify(request.token().trim());
    }
}
