package com.adesa.attendance.admin;

import com.adesa.attendance.attendance.DailyAttendanceReport.Row;
import com.adesa.attendance.attendance.DailyAttendanceReport.Summary;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

final class AttendanceReportLayout {
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE, d MMMM uuuu", Locale.UK);
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("d MMM uuuu, HH:mm", Locale.UK);
    static final List<String> COLUMNS = List.of("No.", "Employee number", "Employee name", "Subsidiary",
            "Department", "Sign in", "Sign out", "Hours worked", "Attendance status", "Remarks");

    private AttendanceReportLayout() {}

    static List<String> cells(Row row, int number, ZoneId zone) {
        return List.of(Integer.toString(number), show(row.employeeNumber()), show(row.employeeName()), show(row.subsidiary()),
                show(row.department()), time(row.signInAt(),zone), time(row.signOutAt(),zone),
                row.workedMinutes() == null ? "–" : "%dh %02dm".formatted(row.workedMinutes()/60,row.workedMinutes()%60),
                row.status().label(), show(row.remarks()));
    }

    static List<Metric> metrics(Summary s) {
        return List.of(metric("Working days",s.workingDays()), metric("Expected attendance",s.expected()),
                metric("Present",s.present()), metric("Absent",s.absent()), metric("Late arrivals",s.late()),
                metric("Signed out",s.signedOut()), metric("Yet to sign out",s.yetToSignOut()),
                metric("Incomplete attendance",s.incomplete()), metric("Approved leave / excused",s.approvedAbsences()),
                new Metric("Attendance percentage", s.expected() == 0 ? "–" :
                        "%.1f%%".formatted(100.0*s.expectedPresent()/s.expected())));
    }

    private static Metric metric(String name, int value) { return new Metric(name,Integer.toString(value)); }
    record Metric(String label, String value) {}

    private static String time(Instant time, ZoneId zone) { return time == null ? "–" : TIME.format(time.atZone(zone)); }
    private static String show(String value) { return value == null || value.isBlank() ? "–" : value; }
}
