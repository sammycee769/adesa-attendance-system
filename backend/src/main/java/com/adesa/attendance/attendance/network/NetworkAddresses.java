package com.adesa.attendance.attendance.network;

import java.util.ArrayList;
import java.util.List;

import inet.ipaddr.IPAddress;
import inet.ipaddr.IPAddressString;

/**
 * Parsing/cloning helpers for IPv4/IPv6 addresses and CIDR blocks.
 *
 * <p>Every configured value is validated at startup: an invalid CIDR must fail
 * startup rather than silently disable or weaken enforcement.
 */
final class NetworkAddresses {

    private NetworkAddresses() {
    }

    /** Parses an address, returning null when it is missing, blank or malformed. */
    static IPAddress parseOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        IPAddressString addressString = new IPAddressString(value.trim());
        if (!addressString.isValid()) {
            return null;
        }
        return normalize(addressString.getAddress());
    }

    /**
     * Parses a list of addresses/CIDR blocks, failing startup on any invalid
     * entry so misconfiguration cannot silently change network policy.
     */
    static List<IPAddress> parseAll(List<String> values, String propertyName) {
        List<IPAddress> parsed = new ArrayList<>();
        for (String value : values) {
            IPAddress address = parseOrNull(value);
            if (address == null) {
                throw new IllegalStateException(
                        "Invalid address or CIDR in " + propertyName + ": '" + value + "'");
            }
            parsed.add(address);
        }
        return List.copyOf(parsed);
    }

    /**
     * IPv4-mapped IPv6 addresses (::ffff:a.b.c.d) are converted to plain IPv4
     * so that they match IPv4 CIDR blocks.
     */
    static IPAddress normalize(IPAddress address) {
        if (address == null) {
            return null;
        }
        if (!address.isIPv4() && address.isIPv4Convertible()) {
            return address.toIPv4();
        }
        return address;
    }
}
