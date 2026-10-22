package com.adesa.attendance.admin;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.adesa.attendance.attendance.dto.AdminAttendanceRecordResponse;

/**
 * Writes an attendance report as CSV.
 *
 * <p>Values that a spreadsheet would interpret as a formula are prefixed with
 * an apostrophe, so a name like {@code =cmd()} cannot become an executable
 * formula in Excel.
 */
final class AttendanceCsvWriter {

    /** The UTF-8 byte order mark, so Excel opens the file with the right encoding. */
    private static final String UTF8_BOM = "﻿";

    private AttendanceCsvWriter() {
        // utility class
    }

    static byte[] write(List<AdminAttendanceRecordResponse> records) {
        StringBuilder csv = new StringBuilder();
        csv.append("Employee number,Employee name,Role,Workday,State,Sign in,Sign out,Corrected\n");

        for (AdminAttendanceRecordResponse record : records) {
            csv.append(cell(record.employeeNumber())).append(',')
                    .append(cell(record.employeeName())).append(',')
                    .append(cell(record.employeeRole())).append(',')
                    .append(cell(record.attendanceDate() == null ? null : record.attendanceDate().toString())).append(',')
                    .append(cell(record.state() == null ? null : record.state().name())).append(',')
                    .append(cell(record.signInAt() == null ? null : record.signInAt().toString())).append(',')
                    .append(cell(record.signOutAt() == null ? null : record.signOutAt().toString())).append(',')
                    .append(record.corrected() ? "yes" : "no")
                    .append('\n');
        }
        return (UTF8_BOM + csv).getBytes(StandardCharsets.UTF_8);
    }

    /** Escapes a CSV cell and neutralises spreadsheet formulas. */
    private static String cell(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        if (!escaped.isEmpty() && "=+-@".indexOf(escaped.charAt(0)) >= 0) {
            escaped = "'" + escaped;
        }
        return "\"" + escaped + "\"";
    }
}
