package com.adesa.attendance.attendance.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.adesa.attendance.attendance.AttendanceState;

/**
 * An attendance record as seen by HR or an administrator.
 *
 * <p>Carries the employee's identity so a report row is self-contained, and
 * marks records that were corrected so a reviewer can spot them.
 */
public record AdminAttendanceRecordResponse(
        UUID id,
        UUID employeeId,
        String employeeNumber,
        String employeeName,
        String employeeRole,
        LocalDate attendanceDate,
        String timezone,
        AttendanceState state,
        Instant signInAt,
        Instant signOutAt,
        boolean corrected,
        int correctionCount,
        Instant updatedAt) {
}
