package com.adesa.attendance.attendance.network;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import com.adesa.attendance.attendance.dto.AttendanceLocation;
import com.adesa.attendance.common.ForbiddenException;
import com.adesa.attendance.config.AttendanceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class GeolocationPolicyTest {
    private AttendanceProperties settings() {
        var p = new AttendanceProperties();
        p.getNetwork().setEnforcementEnabled(true);
        p.getNetwork().setAllowedIpv4(List.of("198.51.100.174/32"));
        p.getNetwork().setAuthenticatedIngressEnabled(true);
        p.getNetwork().setIngressSharedSecret("a".repeat(64));
        p.getGeolocation().setEnabled(true);
        p.getGeolocation().setLatitude(6.6412393);
        p.getGeolocation().setLongitude(3.371182);
        return p;
    }
    private GeolocationPolicy policy(AttendanceProperties p) { return new GeolocationPolicy(p, new ClientIpResolver(p)); }
    private AttendanceLocation fix(double latitude, double longitude, double accuracy) {
        return new AttendanceLocation(latitude, longitude, accuracy, System.currentTimeMillis());
    }

    @Test void acceptsAnAccurateOfficeFixAndRejectsOutsideOrUncertainFixes() {
        var policy = policy(settings());
        assertThatCode(() -> policy.requireInsideOffice(fix(6.6412393, 3.371182, 15))).doesNotThrowAnyException();
        assertThatCode(() -> policy.requireInsideOffice(fix(6.6414393, 3.371182, 10))).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.requireInsideOffice(fix(6.6418393, 3.371182, 5))).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> policy.requireInsideOffice(fix(6.65, 3.38, 5))).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> policy.requireInsideOffice(fix(6.6412393, 3.371182, 200))).isInstanceOf(ForbiddenException.class);
        // About 40m from the pin plus 20m uncertainty must not enlarge the 50m boundary.
        assertThatThrownBy(() -> policy.requireInsideOffice(fix(6.6415993, 3.371182, 20))).isInstanceOf(ForbiddenException.class);
    }

    @Test void requiresFreshFiniteCompleteCoordinates() {
        var policy = policy(settings());
        assertThatThrownBy(() -> policy.requireInsideOffice(null)).isInstanceOf(LocationRequiredException.class);
        for (var fix : List.of(
                new AttendanceLocation(null, 3.371182, 10.0, System.currentTimeMillis()),
                fix(Double.NaN, 3.371182, 10), fix(91, 3.371182, 10), fix(6.6412393, 181, 10),
                fix(6.6412393, 3.371182, Double.NaN), fix(6.6412393, 3.371182, -1),
                new AttendanceLocation(6.6412393, 3.371182, 10.0, null),
                new AttendanceLocation(6.6412393, 3.371182, 10.0, System.currentTimeMillis() - 120_000),
                new AttendanceLocation(6.6412393, 3.371182, 10.0, System.currentTimeMillis() + 60_000))) {
            assertThatThrownBy(() -> policy.requireInsideOffice(fix)).isInstanceOf(ForbiddenException.class);
        }
    }

    @Test void fallbackRequiresTheRealGatewayAndCanBeDisabled() {
        var p = settings();
        var policy = policy(p);
        var request = new MockHttpServletRequest();
        request.addHeader(ClientIpResolver.INGRESS_IP_HEADER, "2001:db8::1");
        assertThat(policy.canFallback(request)).isFalse();
        request.addHeader(ClientIpResolver.INGRESS_KEY_HEADER, "a".repeat(64));
        assertThat(policy.canFallback(request)).isTrue();
        request.addHeader(ClientIpResolver.INGRESS_KEY_HEADER, "a".repeat(64));
        assertThat(policy.canFallback(request)).isFalse();
        request.removeHeader(ClientIpResolver.INGRESS_KEY_HEADER);
        request.addHeader(ClientIpResolver.INGRESS_KEY_HEADER, "a".repeat(64));
        assertThat(policy.canFallback(request)).isTrue();
        p.getGeolocation().setEnabled(false);
        assertThat(policy.canFallback(request)).isFalse();
    }

    @Test void invalidConfigurationFailsAtStartup() {
        var missingPin = settings();
        missingPin.getGeolocation().setLatitude(null);
        assertThatThrownBy(() -> policy(missingPin)).isInstanceOf(IllegalStateException.class);
        var invalidRadius = settings();
        invalidRadius.getGeolocation().setRadiusMetres(-1);
        assertThatThrownBy(() -> policy(invalidRadius)).isInstanceOf(IllegalStateException.class);
    }
}
