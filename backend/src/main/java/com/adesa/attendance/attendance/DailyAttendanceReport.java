package com.adesa.attendance.attendance;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import static org.springframework.format.annotation.DateTimeFormat.ISO.DATE;

public record DailyAttendanceReport(LocalDate from, LocalDate to, Instant generatedAt, String timezone,
        int page, int totalDays, List<Day> days, Summary summary) {

    public enum Status {
        PRESENT("Present"), LATE("Late"), ABSENT("Absent"), NOT_YET_SIGNED_IN("Not Yet Signed In"),
        SIGNED_OUT("Signed Out"), INCOMPLETE_ATTENDANCE("Incomplete Attendance"),
        ON_APPROVED_LEAVE("On Approved Leave"), EXCUSED_ABSENCE("Excused Absence"),
        REMOTE_WORK("Remote Work"), NOT_SCHEDULED("Not Scheduled");
        private final String label;
        Status(String label) { this.label = label; }
        public String label() { return label; }
    }

    public record Filter(UUID employeeId, String search, @DateTimeFormat(iso = DATE) LocalDate from,
            @DateTimeFormat(iso = DATE) LocalDate to, @DateTimeFormat(iso = DATE) LocalDate date,
            String subsidiary, String department, Status status) {}

    public record Day(LocalDate date, List<Row> employees, Summary summary) {}

    public record Row(UUID recordId, UUID employeeId, String employeeNumber, String employeeName,
            String subsidiary, String department, Instant signInAt, Instant signOutAt, Long workedMinutes,
            Status status, String remarks, boolean expected, boolean late, boolean corrected, int expectedOfficeDays) {}

    public record Summary(int workingDays, int expected, int present, int absent, int late,
            int signedOut, int yetToSignOut, int incomplete, int approvedAbsences, int expectedPresent) {
        public static Summary of(List<Day> days) {
            return new Summary(days.size(), sum(days, Summary::expected), sum(days, Summary::present),
                    sum(days, Summary::absent), sum(days, Summary::late), sum(days, Summary::signedOut),
                    sum(days, Summary::yetToSignOut), sum(days, Summary::incomplete),
                    sum(days, Summary::approvedAbsences), sum(days, Summary::expectedPresent));
        }

        private static int sum(List<Day> days, java.util.function.ToIntFunction<Summary> value) {
            return days.stream().map(Day::summary).mapToInt(value).sum();
        }

        public static Summary day(List<Row> rows) {
            int expected = 0, present = 0, absent = 0, late = 0, signedOut = 0;
            int pending = 0, incomplete = 0, approved = 0, expectedPresent = 0;
            for (Row row : rows) {
                if (row.expected()) expected++;
                if (row.signInAt() != null) {
                    present++;
                    if (row.expected()) expectedPresent++;
                }
                if (row.late()) late++;
                if (row.signOutAt() != null) signedOut++;
                switch (row.status()) {
                    case ABSENT -> absent++;
                    case INCOMPLETE_ATTENDANCE -> incomplete++;
                    case ON_APPROVED_LEAVE, EXCUSED_ABSENCE -> approved++;
                    default -> {
                        if (row.signInAt() != null && row.signOutAt() == null) pending++;
                    }
                }
            }
            return new Summary(1, expected, present, absent, late, signedOut, pending, incomplete, approved, expectedPresent);
        }
    }
}
