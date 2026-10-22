package com.adesa.attendance.attendance.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import com.adesa.attendance.attendance.AttendanceRecord;
import com.adesa.attendance.attendance.AttendanceState;

/**
 * A single attendance record.
 *
 * <p>The timestamps are absolute instants; {@code timezone} tells the client
 * which timezone the workday ({@code attendanceDate}) is defined in.
 */
public record AttendanceRecordResponse(
        UUID id,
        LocalDate attendanceDate,
        String timezone,
        AttendanceState state,
        Instant signInAt,
        Instant signOutAt) {

    public static AttendanceRecordResponse from(AttendanceRecord record, ZoneId zone) {
        return new AttendanceRecordResponse(
                record.getId(),
                record.getAttendanceDate(),
                zone.getId(),
                record.getSignOutAt() != null ? AttendanceState.SIGNED_OUT : AttendanceState.SIGNED_IN,
                record.getSignInAt(),
                record.getSignOutAt());
    }
}
