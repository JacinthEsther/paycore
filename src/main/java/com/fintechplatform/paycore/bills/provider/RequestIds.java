package com.fintechplatform.paycore.bills.provider;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

public final class RequestIds {

    private static final DateTimeFormatter PREFIX =
            DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneId.of("Africa/Lagos"));

    private static final SecureRandom RANDOM = new SecureRandom();

    private RequestIds() {
    }

    public static String next() {
        byte[] suffix = new byte[8];
        RANDOM.nextBytes(suffix);
        return PREFIX.format(Instant.now()) + HexFormat.of().withUpperCase().formatHex(suffix);
    }
}
