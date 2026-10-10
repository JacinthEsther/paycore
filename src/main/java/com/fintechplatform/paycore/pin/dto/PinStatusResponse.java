package com.fintechplatform.paycore.pin.dto;

public record PinStatusResponse(boolean set, boolean locked, int attemptsRemaining) {
}
