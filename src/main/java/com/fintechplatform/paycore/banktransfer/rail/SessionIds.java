package com.fintechplatform.paycore.banktransfer.rail;

import java.security.SecureRandom;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * NIP-style session ids: 30 digits, the sending institution's 6-digit
 * code, the Lagos date and time to the second (yyMMddHHmmss), then 12
 * random digits.
 */
public final class SessionIds {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyMMddHHmmss");
    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    private static final SecureRandom RANDOM = new SecureRandom();

    private SessionIds() {
    }

    public static String next(String institutionCode) {

        StringBuilder id = new StringBuilder(30)
                .append(institutionCode)
                .append(STAMP.format(ZonedDateTime.now(LAGOS)));

        while (id.length() < 30) {
            id.append(RANDOM.nextInt(10));
        }

        return id.toString();
    }
}
