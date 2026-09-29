package com.fintechplatform.paycore.common.net;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ClientNetworkTest {

    @ParameterizedTest
    @CsvSource({
            "203.0.113.7,                 203.0.113.7",
            "' 203.0.113.7 ',             203.0.113.7",
            "::ffff:203.0.113.7,          203.0.113.7",
            "2001:db8:1:2:3:4:5:6,        2001:db8:1:2::/64",
            "2001:DB8:1:2::abcd,          2001:db8:1:2::/64",
            "[2001:db8:1:2::1],           2001:db8:1:2::/64",
            "fe80::1%eth0,                fe80:0:0:0::/64",
            "::1,                         0:0:0:0::/64"
    })
    void shouldNormalizeToNetworkKey(String input, String expected) {

        assertThat(ClientNetwork.key(input)).isEqualTo(expected);
    }

    @Test
    void shouldGroupAllAddressesInOneIpv6SlashSixtyFour() {

        assertThat(ClientNetwork.key("2001:db8:aa:bb::1"))
                .isEqualTo(ClientNetwork.key("2001:db8:aa:bb:ffff:ffff:ffff:ffff"));

        assertThat(ClientNetwork.key("2001:db8:aa:bb::1"))
                .isNotEqualTo(ClientNetwork.key("2001:db8:aa:bc::1"));
    }

    @Test
    void shouldKeepDistinctIpv4AddressesSeparate() {

        assertThat(ClientNetwork.key("203.0.113.7"))
                .isNotEqualTo(ClientNetwork.key("203.0.113.8"));
    }

    @Test
    void shouldNeverResolveHostnames() {

        // Each of these would trigger a DNS lookup if passed to
        // InetAddress.getByName; a slow or failing lookup would show up
        // as a long runtime. They must be rejected before parsing.
        long start = System.nanoTime();

        for (String input : new String[]{
                "abc", "999.1.1.1", "cafe", "a.b.c.d", "invalid.example"
        }) {
            assertThat(ClientNetwork.key(input)).isEqualTo(ClientNetwork.UNKNOWN);
        }

        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start))
                .isLessThan(java.time.Duration.ofMillis(200));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ",
            "not-an-ip",
            "example.com",
            "localhost",
            "999.1.1.1",
            "1.2.3",
            "01.2.3.4",
            "abc",
            "deadbeef",
            "1.2.3.4; DROP TABLE kyc_verifications",
            "2001:db8::zz"
    })
    void shouldMapMissingOrInvalidInputToUnknown(String input) {

        // Hostnames are never resolved; everything unparseable shares one
        // bucket, which is still rate limited.
        assertThat(ClientNetwork.key(input)).isEqualTo(ClientNetwork.UNKNOWN);
    }
}
