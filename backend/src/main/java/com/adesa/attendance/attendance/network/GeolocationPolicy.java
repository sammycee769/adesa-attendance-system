package com.adesa.attendance.attendance.network;

import com.adesa.attendance.attendance.dto.AttendanceLocation;
import com.adesa.attendance.common.ForbiddenException;
import com.adesa.attendance.config.AttendanceProperties;
import org.springframework.stereotype.Component;
import jakarta.servlet.http.HttpServletRequest;

/** IP-first fallback. Browser coordinates are a policy signal, not tamper-proof location attestation. */
@Component
public class GeolocationPolicy {
    private final AttendanceProperties.Geolocation settings;
    private final ClientIpResolver resolver;

    public GeolocationPolicy(AttendanceProperties properties, ClientIpResolver resolver) {
        this.settings = properties.getGeolocation();
        this.resolver = resolver;
        if (settings.isEnabled() && (!properties.getNetwork().isEnforcementEnabled()
                || !properties.getNetwork().isAuthenticatedIngressEnabled()
                || !coordinate(settings.getLatitude(), 90) || !coordinate(settings.getLongitude(), 180)
                || !positive(settings.getRadiusMetres()) || settings.getRadiusMetres() > 500
                || !positive(settings.getMaxAccuracyMetres())
                || settings.getMaxAccuracyMetres() > settings.getRadiusMetres())) {
            throw new IllegalStateException("Location fallback requires authenticated ingress, enforced network policy, "
                    + "valid office coordinates, a radius up to 500m, and an accuracy limit within that radius");
        }
    }

    public boolean canFallback(HttpServletRequest request) {
        return settings.isEnabled() && resolver.hasAuthenticatedIngress(request);
    }

    public void requireInsideOffice(AttendanceLocation fix) {
        if (fix == null) throw new LocationRequiredException();
        long now = System.currentTimeMillis();
        if (!coordinate(fix.latitude(), 90) || !coordinate(fix.longitude(), 180)
                || fix.accuracyMetres() == null || !Double.isFinite(fix.accuracyMetres())
                || fix.accuracyMetres() < 0 || fix.accuracyMetres() > settings.getMaxAccuracyMetres()
                || fix.timestampMillis() == null || fix.timestampMillis() < now - 60_000
                || fix.timestampMillis() > now + 5_000) {
            throw new ForbiddenException("Your location could not be confirmed accurately. Enable precise location and try again.");
        }
        double lat1 = Math.toRadians(settings.getLatitude());
        double lat2 = Math.toRadians(fix.latitude());
        double dlat = lat2 - lat1;
        double dlon = Math.toRadians(fix.longitude() - settings.getLongitude());
        double h = Math.pow(Math.sin(dlat / 2), 2) + Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(dlon / 2), 2);
        double distance = 6_371_008.8 * 2 * Math.asin(Math.sqrt(Math.min(1, h)));
        // Do not enlarge the office boundary to compensate for an uncertain reading.
        if (distance + fix.accuracyMetres() > settings.getRadiusMetres()) {
            throw new ForbiddenException("Your location could not be confirmed within the office attendance area.");
        }
    }

    private static boolean coordinate(Double value, double limit) {
        return value != null && Double.isFinite(value) && value >= -limit && value <= limit;
    }
    private static boolean positive(double value) { return Double.isFinite(value) && value > 0; }
}
