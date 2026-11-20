package com.adesa.attendance.attendance.network;

import com.adesa.attendance.attendance.dto.AttendanceLocation;
import com.adesa.attendance.common.ForbiddenException;
import com.adesa.attendance.config.AttendanceProperties;
import org.springframework.stereotype.Component;
import jakarta.servlet.http.HttpServletRequest;

/** The configured office geofence for employee attendance. */
@Component
public class GeolocationPolicy {
    private final AttendanceProperties.Geolocation settings;
    private final ClientIpResolver resolver;

    public GeolocationPolicy(AttendanceProperties properties, ClientIpResolver resolver) {
        this.settings = properties.getGeolocation();
        this.resolver = resolver;
        if (settings.isEnabled() && (!properties.getNetwork().isAuthenticatedIngressEnabled()
                || !coordinate(settings.getLatitude(), 90) || !coordinate(settings.getLongitude(), 180)
                || !positive(settings.getRadiusMetres()) || settings.getRadiusMetres() > 500
                || !positive(settings.getMaxAccuracyMetres())
                || settings.getMaxAccuracyMetres() > settings.getRadiusMetres())) {
            throw new IllegalStateException("Attendance location policy requires authenticated ingress, "
                    + "valid office coordinates, a radius up to 500m, and an accuracy limit within that radius");
        }
    }

    public boolean isEnabled() {
        return settings.isEnabled();
    }

    public double maxAccuracyMetres() {
        return settings.getMaxAccuracyMetres();
    }

    public void requireAuthenticatedIngress(HttpServletRequest request) {
        if (!resolver.hasAuthenticatedIngress(request)) {
            throw new ForbiddenException("Attendance verification is unavailable. Open the attendance website and try again.");
        }
    }

    public void requireInsideOffice(AttendanceLocation fix) {
        if (fix == null) throw new LocationRequiredException();
        if (!coordinate(fix.latitude(), 90) || !coordinate(fix.longitude(), 180)) {
            throw new ForbiddenException("Location is unavailable or invalid. Enable Location Services and try again.");
        }
        long now = System.currentTimeMillis();
        if (fix.timestampMillis() == null || fix.timestampMillis() < now - 60_000
                || fix.timestampMillis() > now + 5_000) {
            throw new ForbiddenException("Your location reading is out of date or has an invalid timestamp. Try again for a fresh reading.");
        }
        if (fix.accuracyMetres() == null || !positive(fix.accuracyMetres())
                || fix.accuracyMetres() > settings.getMaxAccuracyMetres()) {
            throw new ForbiddenException("Your location reading is too imprecise for attendance. Enable Precise Location and try again.");
        }
        double lat1 = Math.toRadians(settings.getLatitude());
        double lat2 = Math.toRadians(fix.latitude());
        double dlat = lat2 - lat1;
        double dlon = Math.toRadians(fix.longitude() - settings.getLongitude());
        double h = Math.pow(Math.sin(dlat / 2), 2) + Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(dlon / 2), 2);
        double distance = 6_371_008.8 * 2 * Math.asin(Math.sqrt(Math.min(1, h)));
        if (distance > settings.getRadiusMetres()) {
            throw new ForbiddenException("Your location is outside the permitted office attendance radius.");
        }
        // Do not enlarge the office boundary to compensate for an uncertain reading.
        if (distance + fix.accuracyMetres() > settings.getRadiusMetres()) {
            throw new ForbiddenException("Your location reading is too imprecise to confirm you are inside the office attendance area. Enable Precise Location and try again.");
        }
    }

    private static boolean coordinate(Double value, double limit) {
        return value != null && Double.isFinite(value) && value >= -limit && value <= limit;
    }
    private static boolean positive(double value) { return Double.isFinite(value) && value > 0; }
}
