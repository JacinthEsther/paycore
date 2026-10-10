package com.fintechplatform.paycore.banktransfer.rail;

public record RailStatus(Outcome outcome, String reason) {

    public enum Outcome {
        SUCCESSFUL,
        FAILED,
        PENDING,
        NOT_FOUND
    }

    public static RailStatus successful() {
        return new RailStatus(Outcome.SUCCESSFUL, null);
    }

    public static RailStatus failed(String reason) {
        return new RailStatus(Outcome.FAILED, reason);
    }

    public static RailStatus pending() {
        return new RailStatus(Outcome.PENDING, null);
    }

    public static RailStatus notFound() {
        return new RailStatus(Outcome.NOT_FOUND, null);
    }
}
