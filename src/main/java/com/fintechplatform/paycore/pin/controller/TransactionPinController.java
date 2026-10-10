package com.fintechplatform.paycore.pin.controller;

import com.fintechplatform.paycore.device.dto.CodeSentResponse;
import com.fintechplatform.paycore.pin.dto.PinRequests.ChangePinRequest;
import com.fintechplatform.paycore.pin.dto.PinRequests.ResetPinRequest;
import com.fintechplatform.paycore.pin.dto.PinRequests.SetPinRequest;
import com.fintechplatform.paycore.pin.dto.PinStatusResponse;
import com.fintechplatform.paycore.pin.service.TransactionPinService;
import com.fintechplatform.paycore.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/security/pin")
public class TransactionPinController {

    private final TransactionPinService pinService;

    public TransactionPinController(TransactionPinService pinService) {
        this.pinService = pinService;
    }

    @GetMapping
    public PinStatusResponse status(@AuthenticationPrincipal CurrentUser currentUser) {
        return pinService.status(currentUser.customerId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PinStatusResponse set(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody SetPinRequest request
    ) {
        return pinService.set(currentUser.customerId(), currentUser.sessionId(), request.pin());
    }

    @PutMapping
    public PinStatusResponse change(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody ChangePinRequest request
    ) {
        return pinService.change(
                currentUser.customerId(), currentUser.sessionId(), request.currentPin(), request.newPin()
        );
    }

    @PostMapping("/reset-code")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CodeSentResponse sendResetCode(@AuthenticationPrincipal CurrentUser currentUser) {
        return pinService.sendResetCode(currentUser.customerId(), currentUser.sessionId());
    }

    @PostMapping("/reset")
    public PinStatusResponse reset(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody ResetPinRequest request
    ) {
        return pinService.reset(currentUser.customerId(), currentUser.sessionId(), request.code(), request.newPin());
    }
}
