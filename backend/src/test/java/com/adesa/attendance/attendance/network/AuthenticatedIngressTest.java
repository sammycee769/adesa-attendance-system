package com.adesa.attendance.attendance.network;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import com.adesa.attendance.config.AttendanceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class AuthenticatedIngressTest {
    private static final String SECRET = "a".repeat(64);

    private AttendanceProperties properties() {
        var properties = new AttendanceProperties();
        var network = properties.getNetwork();
        network.setEnforcementEnabled(true);
        network.setAllowedIpv4(List.of("105.127.8.52/32"));
        network.setAuthenticatedIngressEnabled(true);
        network.setIngressSharedSecret(SECRET);
        return properties;
    }

    private MockHttpServletRequest request(String key, String ip) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("::1");
        request.addHeader("X-Forwarded-For", "105.127.8.52");
        if (key != null) request.addHeader(ClientIpResolver.INGRESS_KEY_HEADER, key);
        if (ip != null) request.addHeader(ClientIpResolver.INGRESS_IP_HEADER, ip);
        return request;
    }

    @Test
    void authenticatedOfficeRequestPassesButAuthenticatedHotspotIsDenied() {
        var properties = properties();
        var resolver = new ClientIpResolver(properties);
        var guard = new AttendanceNetworkGuard(resolver, new OfficeNetworkPolicy(properties), properties);
        assertThat(guard.requireApprovedClientNetwork(request(SECRET, "105.127.8.52")).toString())
                .isEqualTo("105.127.8.52");
        assertThatThrownBy(() -> guard.requireApprovedClientNetwork(request(SECRET, "129.205.124.218")))
                .isInstanceOf(NetworkAccessDeniedException.class);
    }

    @Test
    void directOriginOrForgedGatewayKeyNeverFallsBackToXffOrRemoteAddress() {
        var resolver = new ClientIpResolver(properties());
        for (String key : new String[] {null, "", "b".repeat(64), SECRET + "," + SECRET}) {
            assertThat(resolver.resolve(request(key, "105.127.8.52"))).isNull();
        }
    }

    @Test
    void repeatedKeysOrIpHeadersFailClosed() {
        var resolver = new ClientIpResolver(properties());
        var request = request(SECRET, "105.127.8.52");
        request.addHeader(ClientIpResolver.INGRESS_KEY_HEADER, SECRET);
        assertThat(resolver.resolve(request)).isNull();
        request = request(SECRET, "105.127.8.52");
        request.addHeader(ClientIpResolver.INGRESS_IP_HEADER, "129.205.124.218");
        assertThat(resolver.resolve(request)).isNull();
    }

    @Test
    void onlySingleCanonicalIpv4IsAccepted() {
        var resolver = new ClientIpResolver(properties());
        for (String ip : new String[] {"105.127.8.52/32", "105.127.8.52,129.205.124.218",
                "::1", "::ffff:105.127.8.52", "999.1.2.3", "010.0.0.1", "105.127.8.*", "", " 105.127.8.52"}) {
            assertThat(resolver.resolve(request(SECRET, ip))).as(ip).isNull();
        }
    }

    @Test
    void missingOrWeakSecretsAndMixedTrustModesFailAtStartup() {
        var properties = properties();
        properties.getNetwork().setIngressSharedSecret("weak");
        assertThatThrownBy(() -> new ClientIpResolver(properties)).isInstanceOf(IllegalStateException.class);
        properties.getNetwork().setIngressSharedSecret(SECRET);
        properties.getNetwork().setTrustedProxies(List.of("10.28.123.5/32"));
        assertThatThrownBy(() -> new ClientIpResolver(properties)).isInstanceOf(IllegalStateException.class);
    }
}
