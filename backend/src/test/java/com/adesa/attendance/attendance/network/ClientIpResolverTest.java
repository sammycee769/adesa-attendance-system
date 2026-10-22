package com.adesa.attendance.attendance.network;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.adesa.attendance.config.AttendanceProperties;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import inet.ipaddr.IPAddress;

/**
 * Unit tests for client-address resolution, including forwarded-header spoofing
 * scenarios. The direct peer address is trusted; forwarded headers are only
 * honoured when the peer is an explicitly configured trusted proxy.
 */
class ClientIpResolverTest {

    private static ClientIpResolver resolver(List<String> trustedProxies) {
        AttendanceProperties properties = new AttendanceProperties();
        properties.getNetwork().setTrustedProxies(trustedProxies);
        return new ClientIpResolver(properties);
    }

    private static MockHttpServletRequest request(String remoteAddr, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }

    @Test
    void forwardedHeaderIgnoredWhenNoTrustedProxyConfigured() {
        ClientIpResolver resolver = resolver(List.of());

        IPAddress client = resolver.resolve(request("203.0.113.50", "192.168.3.170"));

        assertThat(client).isNotNull();
        assertThat(client.toString()).isEqualTo("203.0.113.50");
    }

    @Test
    void forwardedHeaderIgnoredWhenDirectPeerIsNotTrusted() {
        ClientIpResolver resolver = resolver(List.of("10.10.0.1"));

        // Attacker on an untrusted network spoofs an approved address.
        IPAddress client = resolver.resolve(request("203.0.113.50", "192.168.3.170"));

        assertThat(client.toString()).isEqualTo("203.0.113.50");
    }

    @Test
    void forwardedClientUsedWhenDirectPeerIsTrustedProxy() {
        ClientIpResolver resolver = resolver(List.of("10.10.0.1"));

        IPAddress client = resolver.resolve(request("10.10.0.1", "192.168.3.170"));

        assertThat(client.toString()).isEqualTo("192.168.3.170");
    }

    @Test
    void rightmostUntrustedHopIsTakenFromForwardedChain() {
        ClientIpResolver resolver = resolver(List.of("10.10.0.0/24"));

        // Chain appended left-to-right; the trusted proxy added the rightmost hop.
        IPAddress client = resolver.resolve(request("10.10.0.2", "198.51.100.9, 10.10.0.7"));

        assertThat(client.toString()).isEqualTo("198.51.100.9");
    }

    @Test
    void spoofedLeftmostEntryCannotOverrideTrustedProxyEntry() {
        ClientIpResolver resolver = resolver(List.of("10.10.0.1"));

        // Attacker injected "192.168.3.170" at the left; the trusted proxy added the real client.
        IPAddress client = resolver.resolve(request("10.10.0.1", "192.168.3.170, 198.51.100.9"));

        assertThat(client.toString()).isEqualTo("198.51.100.9");
    }

    @Test
    void allHopsTrustedFallsBackToDirectPeer() {
        ClientIpResolver resolver = resolver(List.of("10.10.0.0/24"));

        IPAddress client = resolver.resolve(request("10.10.0.2", "10.10.0.7"));

        assertThat(client.toString()).isEqualTo("10.10.0.2");
    }

    @Test
    void malformedForwardedEntryFromTrustedProxyFailsSafe() {
        ClientIpResolver resolver = resolver(List.of("10.10.0.1"));

        assertThat(resolver.resolve(request("10.10.0.1", "not-an-ip"))).isNull();
    }

    @Test
    void blankRemoteAddressFailsSafe() {
        ClientIpResolver resolver = resolver(List.of());

        assertThat(resolver.resolve(request("", null))).isNull();
    }

    @Test
    void ipv4MappedIpv6PeerIsNormalisedToIpv4() {
        ClientIpResolver resolver = resolver(List.of());

        IPAddress client = resolver.resolve(request("::ffff:192.168.3.50", null));

        assertThat(client.toString()).isEqualTo("192.168.3.50");
    }

    @Test
    void trustedProxyConfiguredAsCidrIsHonoured() {
        ClientIpResolver resolver = resolver(List.of("10.10.0.0/24"));

        IPAddress client = resolver.resolve(request("10.10.0.2", "192.168.3.170"));

        assertThat(client.toString()).isEqualTo("192.168.3.170");
    }
}
