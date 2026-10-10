package com.fintechplatform.paycore.common.net;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

public final class ClientNetwork {

    public static final String UNKNOWN = "unknown";

    private static final Pattern IPV4_LITERAL =
            Pattern.compile(
                    "((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}"
                            + "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)"
            );

    private static final Pattern IPV6_CHARACTERS =
            Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    private ClientNetwork() {
    }

    public static String key(String ipAddress) {

        if (ipAddress == null || ipAddress.isBlank()) {
            return UNKNOWN;
        }

        String literal = stripDecorations(ipAddress.trim());

        boolean ipv4 = IPV4_LITERAL.matcher(literal).matches();

        boolean ipv6 =
                literal.indexOf(':') >= 0
                        && IPV6_CHARACTERS.matcher(literal).matches();

        if (!ipv4 && !ipv6) {
            return UNKNOWN;
        }

        InetAddress address;

        try {
            address = InetAddress.getByName(literal);
        } catch (UnknownHostException | SecurityException exception) {
            return UNKNOWN;
        }

        if (address instanceof Inet4Address) {
            return address.getHostAddress();
        }

        if (address instanceof Inet6Address) {
            byte[] bytes = address.getAddress();
            return String.format(
                    "%x:%x:%x:%x::/64",
                    hextet(bytes, 0),
                    hextet(bytes, 2),
                    hextet(bytes, 4),
                    hextet(bytes, 6)
            );
        }

        return UNKNOWN;
    }

    private static String stripDecorations(String value) {

        String result = value;

        if (result.startsWith("[") && result.endsWith("]")) {
            result = result.substring(1, result.length() - 1);
        }

        int zone = result.indexOf('%');

        return zone >= 0 ? result.substring(0, zone) : result;
    }

    private static int hextet(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 8) | (bytes[offset + 1] & 0xff);
    }
}
