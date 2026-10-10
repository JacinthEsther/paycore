package com.fintechplatform.paycore.device.exception;

public class DeviceNotTrustedException extends RuntimeException {

    public DeviceNotTrustedException() {
        super("Confirm this device with the code we email you before moving money from it.");
    }
}
