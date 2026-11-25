package com.adesa.attendance.admin;

import static com.adesa.attendance.attendance.DailyAttendanceReport.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.adesa.attendance.attendance.DailyAttendanceReport;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AttendanceEmployeeTotalsTest {
    @Test
    void appendsEmployeeTotalsToBothFormatsUsingTheFilteredDailyRows() throws Exception {
        LocalDate first = LocalDate.of(2026,11,23);
        UUID employeeOne = UUID.randomUUID();
        UUID employeeTwo = UUID.randomUUID();
        List<Day> dates = List.of(
                day(first.plusDays(2),row(employeeOne,"ST-01",Status.ABSENT),row(employeeTwo,"ST-02",Status.ON_APPROVED_LEAVE)),
                day(first.plusDays(1),row(employeeOne,"ST-01",Status.REMOTE_WORK),row(employeeTwo,"ST-02",Status.LATE)),
                day(first,row(employeeOne,"ST-01",Status.INCOMPLETE_ATTENDANCE),row(employeeTwo,"ST-02",Status.ABSENT)));
        var report = new DailyAttendanceReport(first,first.plusDays(2),Instant.EPOCH,"Africa/Lagos",0,3,dates,Summary.of(dates));
        var filter = new Filter(null,"Ada",first,first.plusDays(2),null,null,null,null);

        var totals = AttendanceReportLayout.employeeTotals(report);
        assertThat(totals).extracting(AttendanceReportLayout.EmployeeTotal::cells)
                .containsExactly(List.of("ST-01","Ada Bello","1","1","2"),List.of("ST-02","Ada Bello","1","1","1"));
        String csv = new String(AttendanceCsvWriter.write(report),StandardCharsets.UTF_8);
        assertThat(csv.substring(csv.indexOf("Cumulative attendance by employee")))
                .contains("Days present","Days absent","Expected office days","\"ST-01\",\"Ada Bello\",\"1\",\"1\"",
                        "\"ST-02\",\"Ada Bello\",\"1\",\"1\"");

        var pdf = new PdfReader(AttendancePdfWriter.write(report,filter));
        try {
            var extractor = new PdfTextExtractor(pdf);
            var text = new StringBuilder();
            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                text.append(extractor.getTextFromPage(page)).append('\n');
            }
            assertThat(text.substring(text.indexOf("Cumulative attendance by employee")))
                    .contains("Days present","Days absent","Expected office days","ST-01","ST-02","Ada Bello");
        } finally { pdf.close(); }
    }

    private static Day day(LocalDate date, Row... rows) {
        return new Day(date,List.of(rows),Summary.day(List.of(rows)));
    }

    private static Row row(UUID employeeId, String number, Status status) {
        Instant signIn = status == Status.INCOMPLETE_ATTENDANCE || status == Status.LATE ? Instant.EPOCH : null;
        return new Row(null,employeeId,number,"Ada Bello","ADESA","Operations",signIn,null,null,
                status,"",status != Status.ON_APPROVED_LEAVE,status == Status.LATE,false,number.equals("ST-01") ? 2 : 1);
    }
}
