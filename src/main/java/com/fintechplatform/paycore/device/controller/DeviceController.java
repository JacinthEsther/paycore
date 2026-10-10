package com.fintechplatform.paycore.device.controller;

import com.fintechplatform.paycore.device.dto.CodeSentResponse;
import com.fintechplatform.paycore.device.dto.ConfirmDeviceRequest;
import com.fintechplatform.paycore.device.dto.DeviceResponse;
import com.fintechplatform.paycore.device.service.DeviceService;
import com.fintechplatform.paycore.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/devices")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @GetMapping
    public List<DeviceResponse> list(@AuthenticationPrincipal CurrentUser currentUser) {
        return deviceService.list(currentUser.customerId(), currentUser.sessionId());
    }

    @GetMapping("/current")
    public DeviceResponse current(@AuthenticationPrincipal CurrentUser currentUser) {
        return deviceService.current(currentUser.sessionId());
    }

    @PostMapping("/current/confirmation-code")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CodeSentResponse sendConfirmationCode(@AuthenticationPrincipal CurrentUser currentUser) {
        return deviceService.sendConfirmationCode(currentUser.customerId(), currentUser.sessionId());
    }

    @PostMapping("/current/confirm")
    public DeviceResponse confirm(
            @AuthenticationPrincipal CurrentUser currentUser,
            @Valid @RequestBody ConfirmDeviceRequest request
    ) {
        return deviceService.confirm(currentUser.customerId(), currentUser.sessionId(), request.code());
    }

    @DeleteMapping("/{deviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(
            @AuthenticationPrincipal CurrentUser currentUser,
            @PathVariable UUID deviceId
    ) {
        deviceService.revoke(currentUser.customerId(), currentUser.sessionId(), deviceId);
    }
}
