package com.adesa.attendance.attendance.dto;

import java.time.Instant;
import java.time.LocalDate;

import com.adesa.attendance.attendance.AttendanceState;

/**
 * The employee's attendance state for the current workday.
 *
 * <p>{@code canSignIn} / {@code canSignOut} tell the dashboard which action the
 * backend currently accepts. The office-network policy is evaluated on the
 * action itself, so a request from outside the approved network is rejected
 * with HTTP 403 even when {@code canSignIn} is true.
 */
public record AttendanceStatusResponse(
        LocalDate attendanceDate,
        String timezone,
        AttendanceState state,
        Instant signInAt,
        Instant signOutAt,
        boolean canSignIn,
        boolean canSignOut,
        String deviceStatus) {
}
