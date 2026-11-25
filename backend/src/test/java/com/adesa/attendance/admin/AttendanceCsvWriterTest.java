package com.adesa.attendance.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static com.adesa.attendance.attendance.DailyAttendanceReport.*;

import com.adesa.attendance.attendance.DailyAttendanceReport;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AttendanceCsvWriterTest {
    private static final LocalDate DATE = LocalDate.of(2026,3,5);

    private static DailyAttendanceReport report(String name) {
        var row = new Row(null,UUID.randomUUID(),"EMP-001",name,"ADESA","Operations",
                null,null,null,Status.ABSENT,"",true,false,false,1);
        var rows = List.of(row);
        var days = List.of(new Day(DATE,rows,Summary.day(rows)));
        return new DailyAttendanceReport(DATE,DATE,Instant.EPOCH,"Africa/Lagos",0,1,days,Summary.of(days));
    }

    @Test
    void writesDailyAndOverallSummariesWithAbsentEmployee() {
        String csv = new String(AttendanceCsvWriter.write(report("Amina Bello")),StandardCharsets.UTF_8);
        assertThat(csv).contains("Thursday, 5 March 2026","Employee number","EMP-001","Amina Bello",
                "\"–\",\"–\",\"–\",\"Absent\"","Overall attendance summary","Attendance percentage");
    }

    @Test
    void neutralisesFormulas() {
        String csv = new String(AttendanceCsvWriter.write(report("  =SUM(1,2)")),StandardCharsets.UTF_8);
        assertThat(csv).contains("\"'  =SUM(1,2)\"").doesNotContain("\"  =SUM");
    }

    @Test
    void escapesQuotes() {
        String csv = new String(AttendanceCsvWriter.write(report("Ada \"Bello\"")),StandardCharsets.UTF_8);
        assertThat(csv).contains("\"Ada \"\"Bello\"\"\"");
    }

    @Test
    void startsWithByteOrderMark() {
        byte[] csv = AttendanceCsvWriter.write(report("Amina Bello"));
        assertThat(csv[0]).isEqualTo((byte)0xEF);
        assertThat(csv[1]).isEqualTo((byte)0xBB);
        assertThat(csv[2]).isEqualTo((byte)0xBF);
    }
}
