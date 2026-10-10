package com.fintechplatform.paycore.device.service;

final class DeviceLabels {

    private DeviceLabels() {
    }

    static String fromUserAgent(String userAgent) {

        if (userAgent == null || userAgent.isBlank()) {
            return "Unknown device";
        }

        String browser =
                userAgent.contains("Edg/") ? "Edge"
                        : userAgent.contains("OPR/") ? "Opera"
                        : userAgent.contains("Firefox/") ? "Firefox"
                        : userAgent.contains("Chrome/") ? "Chrome"
                        : userAgent.contains("Safari/") ? "Safari"
                        : null;

        String system =
                userAgent.contains("Android") ? "Android"
                        : userAgent.contains("iPhone") || userAgent.contains("iPad") ? "iOS"
                        : userAgent.contains("Windows") ? "Windows"
                        : userAgent.contains("Mac OS") ? "macOS"
                        : userAgent.contains("Linux") ? "Linux"
                        : null;

        if (browser != null && system != null) {
            return browser + " on " + system;
        }
        if (browser != null || system != null) {
            return browser != null ? browser : system;
        }
        return userAgent;
    }
}
