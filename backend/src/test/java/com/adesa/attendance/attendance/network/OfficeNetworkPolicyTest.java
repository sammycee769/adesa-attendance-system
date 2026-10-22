package com.adesa.attendance.attendance.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.adesa.attendance.config.AttendanceProperties;

import org.junit.jupiter.api.Test;

import inet.ipaddr.IPAddress;
import inet.ipaddr.IPAddressString;

/**
 * Unit tests for the approved-office-network matching rules. These are pure
 * policy tests: no web layer, no database.
 */
class OfficeNetworkPolicyTest {

    private static IPAddress address(String value) {
        return new IPAddressString(value).getAddress();
    }

    private static OfficeNetworkPolicy policy(boolean enabled, List<String> cidrs) {
        AttendanceProperties properties = new AttendanceProperties();
        properties.getNetwork().setEnforcementEnabled(enabled);
        properties.getNetwork().setAllowedIpv4(cidrs);
        return new OfficeNetworkPolicy(properties);
    }

    @Test
    void addressInsideApprovedCidrIsAllowed() {
        OfficeNetworkPolicy policy = policy(true, List.of("192.168.3.0/24"));

        assertThat(policy.isEnforcementEnabled()).isTrue();
        assertThat(policy.isWithinApprovedNetwork(address("192.168.3.170"))).isTrue();
        assertThat(policy.isWithinApprovedNetwork(address("192.168.3.1"))).isTrue();
    }

    @Test
    void addressOutsideApprovedCidrIsRejected() {
        OfficeNetworkPolicy policy = policy(true, List.of("192.168.3.0/24"));

        assertThat(policy.isWithinApprovedNetwork(address("192.168.4.1"))).isFalse();
        assertThat(policy.isWithinApprovedNetwork(address("10.0.0.5"))).isFalse();
        assertThat(policy.isWithinApprovedNetwork(address("203.0.113.9"))).isFalse();
    }

    @Test
    void singleHostEntryMatchesOnlyItself() {
        OfficeNetworkPolicy policy = policy(true, List.of("192.168.3.170"));

        assertThat(policy.isWithinApprovedNetwork(address("192.168.3.170"))).isTrue();
        assertThat(policy.isWithinApprovedNetwork(address("192.168.3.171"))).isFalse();
    }

    @Test
    void anyConfiguredCidrMayMatch() {
        OfficeNetworkPolicy policy = policy(true, List.of("192.168.3.0/24", "198.51.100.0/24"));

        assertThat(policy.isWithinApprovedNetwork(address("198.51.100.20"))).isTrue();
        assertThat(policy.approvedNetworkCount()).isEqualTo(2);
    }

    @Test
    void ipv4MappedIpv6ClientMatchesIpv4Network() {
        OfficeNetworkPolicy policy = policy(true, List.of("192.168.3.0/24"));

        assertThat(policy.isWithinApprovedNetwork(address("::ffff:192.168.3.170"))).isTrue();
    }

    @Test
    void nullAddressIsNotAllowed() {
        OfficeNetworkPolicy policy = policy(true, List.of("192.168.3.0/24"));

        assertThat(policy.isWithinApprovedNetwork(null)).isFalse();
    }

    @Test
    void theOfficePublicAddressIsTheOnlyApprovedAddress() {
        // The approved office network is the ADESA HQ public address as supplied
        // by ADESA. Private and link-local addresses are deliberately not on it.
        OfficeNetworkPolicy policy = policy(true, List.of("105.127.6.174/32"));

        assertThat(policy.isWithinApprovedNetwork(address("105.127.6.174"))).isTrue();
        assertThat(policy.isWithinApprovedNetwork(address("105.127.6.173"))).isFalse();
        assertThat(policy.isWithinApprovedNetwork(address("105.127.6.175"))).isFalse();
        assertThat(policy.isWithinApprovedNetwork(address("192.168.3.170"))).isFalse();
        assertThat(policy.isWithinApprovedNetwork(address("fe80::1"))).isFalse();
        assertThat(policy.approvedNetworkCount()).isEqualTo(1);
    }

    @Test
    void malformedCidrFailsStartup() {
        AttendanceProperties properties = new AttendanceProperties();
        properties.getNetwork().setEnforcementEnabled(true);
        properties.getNetwork().setAllowedIpv4(List.of("192.168.3.0/24", "not-a-cidr"));

        assertThatThrownBy(() -> new OfficeNetworkPolicy(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("attendance.network.allowed-ipv4");
    }

    @Test
    void enabledPolicyWithoutAnyCidrFailsStartup() {
        AttendanceProperties properties = new AttendanceProperties();
        properties.getNetwork().setEnforcementEnabled(true);
        properties.getNetwork().setAllowedIpv4(List.of());

        assertThatThrownBy(() -> new OfficeNetworkPolicy(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowed-ipv4 is empty");
    }

    @Test
    void anIpv6RangeInTheAllowlistFailsStartup() {
        // The office-network policy is IPv4-only: an IPv6 range must not be
        // configured, and IPv6 traffic must never bypass the policy.
        AttendanceProperties properties = new AttendanceProperties();
        properties.getNetwork().setEnforcementEnabled(true);
        properties.getNetwork().setAllowedIpv4(List.of("2001:db8::/32"));

        assertThatThrownBy(() -> new OfficeNetworkPolicy(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IPv4");
    }

    @Test
    void aNativeIpv6ClientIsNeverApproved() {
        OfficeNetworkPolicy policy = policy(true, List.of("105.127.6.174/32"));

        assertThat(policy.isWithinApprovedNetwork(address("2001:db8::1"))).isFalse();
        assertThat(policy.isWithinApprovedNetwork(address("fe80::1"))).isFalse();
    }

    @Test
    void anIpv4MappedIpv6ClientIsTreatedAsItsIpv4Address() {
        // ::ffff:105.127.6.174 is the same host as 105.127.6.174 on a
        // dual-stack socket, so it is normalised and approved.
        OfficeNetworkPolicy policy = policy(true, List.of("105.127.6.174/32"));

        assertThat(policy.isWithinApprovedNetwork(address("::ffff:105.127.6.174"))).isTrue();
    }

    @Test
    void disabledPolicyReportsEnforcementOff() {
        OfficeNetworkPolicy policy = policy(false, List.of());

        assertThat(policy.isEnforcementEnabled()).isFalse();
        assertThat(policy.approvedNetworkCount()).isZero();
        assertThat(policy.isWithinApprovedNetwork(address("192.168.3.170"))).isFalse();
    }
}
