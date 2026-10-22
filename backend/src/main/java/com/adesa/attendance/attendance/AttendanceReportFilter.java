package com.adesa.attendance.attendance;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The filter of an attendance report.
 *
 * @param employeeId restrict to one employee
 * @param search     free text over name, employee number and email
 * @param from       first workday (inclusive)
 * @param to         last workday (inclusive)
 * @param status     {@code SIGNED_IN} or {@code SIGNED_OUT}
 */
public record AttendanceReportFilter(
        UUID employeeId,
        String search,
        LocalDate from,
        LocalDate to,
        AttendanceState status) {

    public static AttendanceReportFilter empty() {
        return new AttendanceReportFilter(null, null, null, null, null);
    }
}
