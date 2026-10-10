package com.fintechplatform.paycore.device.dto;

import java.time.Instant;

public record CodeSentResponse(String sentTo, Instant expiresAt) {
}
