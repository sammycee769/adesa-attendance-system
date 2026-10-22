package com.adesa.attendance.attendance.network;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.adesa.attendance.common.ForbiddenException;
import com.adesa.attendance.config.AttendanceProperties;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import inet.ipaddr.IPAddress;

/**
 * Behaviour of the attendance network guard: allowed, denied, fail-closed and
 * the explicit (development-only) disabled path.
 */
class AttendanceNetworkGuardTest {

    private static AttendanceNetworkGuard guard(
            boolean enabled, List<String> cidrs, List<String> trustedProxies) {
        return guard(enabled, false, cidrs, trustedProxies);
    }

    private static AttendanceNetworkGuard guard(
            boolean enabled, boolean localDevelopmentBypass, List<String> cidrs, List<String> trustedProxies) {
        AttendanceProperties properties = new AttendanceProperties();
        properties.getNetwork().setEnforcementEnabled(enabled);
        properties.getNetwork().setLocalDevelopmentBypass(localDevelopmentBypass);
        properties.getNetwork().setAllowedIpv4(cidrs);
        properties.getNetwork().setTrustedProxies(trustedProxies);
        ClientIpResolver resolver = new ClientIpResolver(properties);
        OfficeNetworkPolicy policy = new OfficeNetworkPolicy(properties);
        return new AttendanceNetworkGuard(resolver, policy, properties);
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
    void clientInsideApprovedNetworkIsAccepted() {
        AttendanceNetworkGuard guard = guard(true, List.of("192.168.3.0/24"), List.of());

        IPAddress client = guard.requireApprovedClientNetwork(request("192.168.3.170", null));

        assertThat(client.toString()).isEqualTo("192.168.3.170");
    }

    @Test
    void clientOutsideApprovedNetworkIsRejected() {
        AttendanceNetworkGuard guard = guard(true, List.of("192.168.3.0/24"), List.of());

        assertThatThrownBy(() -> guard.requireApprovedClientNetwork(request("203.0.113.50", null)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Attendance is only available from the approved ADESA office network.");
    }

    @Test
    void denialMessageDoesNotLeakNetworkDetails() {
        AttendanceNetworkGuard guard = guard(true, List.of("192.168.3.0/24"), List.of("10.10.0.1"));

        assertThatThrownBy(() -> guard.requireApprovedClientNetwork(request("203.0.113.50", null)))
                .isInstanceOf(ForbiddenException.class)
                .satisfies(ex -> assertThat(ex.getMessage())
                        .doesNotContain("192.168.3.0/24")
                        .doesNotContain("10.10.0.1")
                        .doesNotContain("203.0.113.50"));
    }

    @Test
    void spoofedForwardedHeaderCannotBypassEnforcement() {
        AttendanceNetworkGuard guard = guard(true, List.of("192.168.3.0/24"), List.of("10.10.0.1"));

        // Untrusted peer claims to be on the office network.
        assertThatThrownBy(() -> guard.requireApprovedClientNetwork(request("203.0.113.50", "192.168.3.170")))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void trustedProxyForwardedClientInsideNetworkIsAccepted() {
        AttendanceNetworkGuard guard = guard(true, List.of("192.168.3.0/24"), List.of("10.10.0.1"));

        IPAddress client = guard.requireApprovedClientNetwork(request("10.10.0.1", "192.168.3.170"));

        assertThat(client.toString()).isEqualTo("192.168.3.170");
    }

    @Test
    void undeterminableClientAddressFailsClosed() {
        AttendanceNetworkGuard guard = guard(true, List.of("192.168.3.0/24"), List.of());

        assertThatThrownBy(() -> guard.requireApprovedClientNetwork(request("", null)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void disabledPolicyWithoutBypassFailsClosed() {
        // Enforcement off and no local-development bypass: an unconfigured
        // policy must never grant access.
        AttendanceNetworkGuard guard = guard(false, false, List.of(), List.of());

        assertThatThrownBy(() -> guard.requireApprovedClientNetwork(request("203.0.113.50", null)))
                .isInstanceOf(NetworkAccessDeniedException.class)
                .satisfies(ex -> {
                    NetworkAccessDeniedException denied = (NetworkAccessDeniedException) ex;
                    assertThat(denied.getReason())
                            .isEqualTo(NetworkAccessDeniedException.REASON_ENFORCEMENT_DISABLED);
                    assertThat(denied.getClientIp()).isEqualTo("203.0.113.50");
                });
    }

    @Test
    void disabledPolicyWithoutBypassFailsClosedForAnUnresolvedAddressToo() {
        AttendanceNetworkGuard guard = guard(false, false, List.of(), List.of());

        assertThatThrownBy(() -> guard.requireApprovedClientNetwork(request("", null)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void disabledPolicyWithTheExplicitLocalDevelopmentBypassAllowsRequests() {
        AttendanceNetworkGuard guard = guard(false, true, List.of(), List.of());

        // Even an address that could never be on the office network is allowed
        // while the explicit dev bypass is set (local development only; the
        // 'prod' profile refuses to start in this state).
        IPAddress client = guard.requireApprovedClientNetwork(request("203.0.113.50", null));

        assertThat(client.toString()).isEqualTo("203.0.113.50");
    }

    @Test
    void disabledPolicyWithTheBypassToleratesUndeterminableAddress() {
        AttendanceNetworkGuard guard = guard(false, true, List.of(), List.of());

        assertThat(guard.requireApprovedClientNetwork(request("", null))).isNull();
    }

    @Test
    void denialCarriesTheReasonOutsideTheApprovedNetwork() {
        AttendanceNetworkGuard guard = guard(true, List.of("198.51.100.174/32"), List.of());

        assertThatThrownBy(() -> guard.requireApprovedClientNetwork(request("203.0.113.50", null)))
                .isInstanceOf(NetworkAccessDeniedException.class)
                .satisfies(ex -> {
                    NetworkAccessDeniedException denied = (NetworkAccessDeniedException) ex;
                    assertThat(denied.getReason())
                            .isEqualTo(NetworkAccessDeniedException.REASON_OUTSIDE_APPROVED_NETWORK);
                    assertThat(denied.getClientIp()).isEqualTo("203.0.113.50");
                });
    }

    @Test
    void denialCarriesTheReasonWhenTheAddressIsUnresolved() {
        AttendanceNetworkGuard guard = guard(true, List.of("198.51.100.174/32"), List.of());

        assertThatThrownBy(() -> guard.requireApprovedClientNetwork(request("", null)))
                .isInstanceOf(NetworkAccessDeniedException.class)
                .satisfies(ex -> assertThat(((NetworkAccessDeniedException) ex).getReason())
                        .isEqualTo(NetworkAccessDeniedException.REASON_UNRESOLVED));
    }

    @Test
    void anIpv6ClientIsRejectedWithItsOwnReason() {
        AttendanceNetworkGuard guard = guard(true, List.of("198.51.100.174/32"), List.of());

        assertThatThrownBy(() -> guard.requireApprovedClientNetwork(request("2001:db8::1", null)))
                .isInstanceOf(NetworkAccessDeniedException.class)
                .satisfies(ex -> assertThat(((NetworkAccessDeniedException) ex).getReason())
                        .isEqualTo(NetworkAccessDeniedException.REASON_IPV6_NOT_APPROVED));
    }
}
