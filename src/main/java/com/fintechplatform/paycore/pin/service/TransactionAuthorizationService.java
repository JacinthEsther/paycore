package com.fintechplatform.paycore.pin.service;

import com.fintechplatform.paycore.device.exception.DeviceNotTrustedException;
import com.fintechplatform.paycore.device.service.DeviceService;
import com.fintechplatform.paycore.security.CurrentUser;
import org.springframework.stereotype.Service;

@Service
public class TransactionAuthorizationService {

    public static final String PIN_HEADER = "X-Transaction-PIN";

    private final DeviceService deviceService;
    private final TransactionPinService pinService;

    public TransactionAuthorizationService(DeviceService deviceService, TransactionPinService pinService) {
        this.deviceService = deviceService;
        this.pinService = pinService;
    }

    public void authorize(CurrentUser user, String rawPin) {
        deviceService.requireTrusted(user.sessionId());
        pinService.verify(user.customerId(), rawPin);
    }
}
