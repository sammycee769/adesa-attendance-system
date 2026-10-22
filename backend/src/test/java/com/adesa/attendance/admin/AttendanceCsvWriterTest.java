package com.adesa.attendance.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.adesa.attendance.attendance.AttendanceState;
import com.adesa.attendance.attendance.dto.AdminAttendanceRecordResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The CSV export of the attendance report.
 */
class AttendanceCsvWriterTest {

    private static AdminAttendanceRecordResponse record(String number, String name) {
        return new AdminAttendanceRecordResponse(
                java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(),
                number,
                name,
                "EMPLOYEE",
                LocalDate.of(2026, 3, 5),
                "Africa/Lagos",
                AttendanceState.SIGNED_OUT,
                Instant.parse("2026-03-05T07:45:00Z"),
                Instant.parse("2026-03-05T15:30:00Z"),
                true,
                1,
                Instant.parse("2026-03-05T15:30:00Z"));
    }

    @Test
    @DisplayName("The export has a header row and one row per record")
    void writesHeaderAndRows() {
        String csv = new String(
                AttendanceCsvWriter.write(List.of(record("EMP-001", "Amina Bello"))), StandardCharsets.UTF_8);

        assertThat(csv).contains("Employee number,Employee name,Role,Workday,State,Sign in,Sign out,Corrected");
        assertThat(csv).contains("\"EMP-001\"");
        assertThat(csv).contains("\"Amina Bello\"");
        assertThat(csv).contains("\"2026-03-05\"");
        assertThat(csv).contains("\"SIGNED_OUT\"");
        assertThat(csv).contains("yes");
    }

    @Test
    @DisplayName("A value that a spreadsheet would run as a formula is neutralised")
    void neutralisesFormulas() {
        String csv = new String(
                AttendanceCsvWriter.write(List.of(record("EMP-002", "=cmd|'/c calc'!A1"))),
                StandardCharsets.UTF_8);

        assertThat(csv).contains("\"'=cmd|'/c calc'!A1\"");
        assertThat(csv).doesNotContain("\"=cmd");
    }

    @Test
    @DisplayName("A value with a quote is escaped")
    void escapesQuotes() {
        String csv = new String(
                AttendanceCsvWriter.write(List.of(record("EMP-003", "Ada \"Bello\""))), StandardCharsets.UTF_8);

        assertThat(csv).contains("\"Ada \"\"Bello\"\"\"");
    }

    @Test
    @DisplayName("The export starts with the byte order mark, so Excel reads UTF-8")
    void startsWithByteOrderMark() {
        byte[] csv = AttendanceCsvWriter.write(List.of());

        assertThat(csv[0]).isEqualTo((byte) 0xEF);
        assertThat(csv[1]).isEqualTo((byte) 0xBB);
        assertThat(csv[2]).isEqualTo((byte) 0xBF);
    }
}
