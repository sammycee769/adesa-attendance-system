package com.adesa.attendance.attendance;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import com.adesa.attendance.config.AttendanceProperties;

import org.springframework.stereotype.Component;

/**
 * Resolves the attendance workday and the server timestamps.
 *
 * <p>The workday is the calendar day in the configured attendance timezone
 * ({@code Africa/Lagos} by default) — never the server's default timezone and
 * never the employee's device clock. The clock is injected so tests can pin
 * time deterministically.
 */
@Component
public class WorkdayResolver {

    private final Clock clock;
    private final ZoneId zone;

    public WorkdayResolver(Clock clock, AttendanceProperties properties) {
        this.clock = clock;
        // An invalid zone id must stop the application, not silently change
        // which day counts as the workday.
        this.zone = ZoneId.of(properties.getTimezone());
    }

    /** The current local workday. */
    public LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }

    /** The current server instant (timezone independent). */
    public Instant now() {
        return clock.instant();
    }

    public ZoneId zone() {
        return zone;
    }

    public String zoneId() {
        return zone.getId();
    }
}
