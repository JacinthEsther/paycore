package com.fintechplatform.paycore.device.exception;

public class DeviceNotFoundException extends RuntimeException {

    public DeviceNotFoundException() {
        super("Device not found");
    }
}
