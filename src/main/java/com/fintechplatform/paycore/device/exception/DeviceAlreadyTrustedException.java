package com.fintechplatform.paycore.device.exception;

public class DeviceAlreadyTrustedException extends RuntimeException {

    public DeviceAlreadyTrustedException() {
        super("This device is already confirmed");
    }
}
