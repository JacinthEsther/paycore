package com.fintechplatform.paycore.banktransfer.rail;

public record RailResult(boolean accepted, String reason) {

    public static RailResult success() {
        return new RailResult(true, null);
    }

    public static RailResult rejected(String reason) {
        return new RailResult(false, reason);
    }
}
