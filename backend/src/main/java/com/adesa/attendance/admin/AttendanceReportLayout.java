package com.adesa.attendance.admin;

import com.adesa.attendance.attendance.DailyAttendanceReport;
import com.adesa.attendance.attendance.DailyAttendanceReport.Row;
import com.adesa.attendance.attendance.DailyAttendanceReport.Status;
import com.adesa.attendance.attendance.DailyAttendanceReport.Summary;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.Locale;

final class AttendanceReportLayout {
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE, d MMMM uuuu", Locale.UK);
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("d MMM uuuu, HH:mm", Locale.UK);
    static final List<String> COLUMNS = List.of("No.", "Employee number", "Employee name", "Subsidiary",
            "Department", "Sign in", "Sign out", "Hours worked", "Attendance status", "Remarks");

    static final List<String> EMPLOYEE_TOTAL_COLUMNS =
            List.of("Employee number","Employee name","Days present","Days absent");
    static final String EMPLOYEE_TOTAL_HEADING = "Cumulative attendance by employee";

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

    static List<EmployeeTotal> employeeTotals(DailyAttendanceReport report) {
        var rowsByEmployee = report.days().stream().flatMap(day -> day.employees().stream())
                .collect(Collectors.groupingBy(Row::employeeId));
        return rowsByEmployee.entrySet().stream().map(entry -> {
            List<Row> rows = entry.getValue();
            Row latest = rows.getFirst();
            return new EmployeeTotal(entry.getKey(),show(latest.employeeNumber()),show(latest.employeeName()),
                    rows.stream().filter(row -> row.signInAt() != null).count(),
                    rows.stream().filter(row -> row.status() == Status.ABSENT).count());
        }).sorted(Comparator.comparing(EmployeeTotal::name,String.CASE_INSENSITIVE_ORDER)
                .thenComparing(EmployeeTotal::number).thenComparing(EmployeeTotal::id)).toList();
    }

    record EmployeeTotal(UUID id, String number, String name, long present, long absent) {
        List<String> cells() { return List.of(number,name,Long.toString(present),Long.toString(absent)); }
    }

    private static Metric metric(String name, int value) { return new Metric(name,Integer.toString(value)); }
    record Metric(String label, String value) {}

    private static String time(Instant time, ZoneId zone) { return time == null ? "–" : TIME.format(time.atZone(zone)); }
    private static String show(String value) { return value == null || value.isBlank() ? "–" : value; }
}
