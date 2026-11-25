package com.adesa.attendance.admin;

import static com.adesa.attendance.admin.AttendanceReportLayout.*;

import com.adesa.attendance.attendance.DailyAttendanceReport;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.List;

final class AttendanceCsvWriter {
    private AttendanceCsvWriter() {}

    static byte[] write(DailyAttendanceReport report) {
        var csv = new StringBuilder("\ufeff");
        line(csv,List.of("ADESA HOLDINGS | Staff Attendance Report"));
        line(csv,List.of("Reporting period",report.from().toString(),report.to().toString(),report.timezone()));
        ZoneId zone = ZoneId.of(report.timezone());
        for (var day : report.days()) {
            csv.append('\n');
            line(csv,List.of(DAY.format(day.date())));
            var metrics = metrics(day.summary()).subList(1,9);
            line(csv,metrics.stream().map(Metric::label).toList());
            line(csv,metrics.stream().map(Metric::value).toList());
            line(csv,COLUMNS);
            int number = 0;
            for (var employee : day.employees()) line(csv,cells(employee,++number,zone));
        }
        csv.append('\n');
        line(csv,List.of("Overall attendance summary"));
        for (var metric : metrics(report.summary())) line(csv,List.of(metric.label(),metric.value()));
        var employeeTotals = employeeTotals(report);
        if (!employeeTotals.isEmpty()) {
            csv.append('\n');
            line(csv,List.of(EMPLOYEE_TOTAL_HEADING));
            line(csv,EMPLOYEE_TOTAL_COLUMNS);
            for (var employee : employeeTotals) line(csv,employee.cells());
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void line(StringBuilder csv, List<String> values) {
        csv.append(String.join(",",values.stream().map(AttendanceCsvWriter::cell).toList())).append('\n');
    }

    private static String cell(String value) {
        String trimmed = value.stripLeading();
        if (!trimmed.isEmpty() && "=+-@".indexOf(trimmed.charAt(0)) >= 0) value = "'" + value;
        return "\"" + value.replace("\"","\"\"") + "\"";
    }
}
