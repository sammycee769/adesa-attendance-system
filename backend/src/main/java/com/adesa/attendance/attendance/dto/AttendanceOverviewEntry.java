package com.adesa.attendance.attendance.dto;

import java.time.Instant;
import java.util.UUID;

import com.adesa.attendance.attendance.AttendanceState;

/**
 * One row of the daily attendance overview: every active employee with their
 * state for a workday.
 */
public record AttendanceOverviewEntry(
        UUID employeeId,
        String employeeNumber,
        String employeeName,
        String employeeRole,
        AttendanceState state,
        Instant signInAt,
        Instant signOutAt,
        boolean corrected) {
}
