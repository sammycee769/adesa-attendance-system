package com.adesa.attendance.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ExistingMediaWorkbookTest {
    static byte[] fixture() throws Exception {
        try (var book = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = book.createSheet("OOH Sheet");
            var header = sheet.createRow(0);
            var names = List.of("S/N", "Client", "OOH Location", "Vendor Name", "initial Start Date",
                    "Renewed Date", "Expiration Date after renewal with vendors", "Expiration Date With Client",
                    "Expiration Date with Vendor", "Duration", "Cost with Vendor", "Cost with Client",
                    "Profit", "Total Annual Cost", "Client Status");
            for (int i = 0; i < names.size(); i++) header.createCell(i).setCellValue(names.get(i));
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue(1);
            row.createCell(1).setCellValue("Test Client");
            row.createCell(2).setCellValue("Ring Road");
            row.createCell(3).setCellValue("Test Vendor");
            row.createCell(4).setCellValue("June 13th 2025");
            row.createCell(6).setCellValue("9/30/2026");
            row.createCell(7).setCellValue("Expires Dec 16");
            var excelDate = row.createCell(8);
            excelDate.setCellValue(LocalDate.of(2026, 10, 1));
            var style = book.createCellStyle();
            style.setDataFormat(book.createDataFormat().getFormat("yyyy-mm-dd"));
            excelDate.setCellStyle(style);
            row.createCell(10).setCellValue(100);
            row.createCell(11).setCellValue(150);
            row.createCell(12).setCellFormula("L2-K2");
            row.createCell(14).setCellValue("PAID");
            var continuation = sheet.createRow(2);
            continuation.createCell(0).setCellValue(1);
            continuation.createCell(2).setCellValue("Ring Road");
            var spend = book.createSheet("Client Spend Tracker");
            spend.createRow(0).createCell(0).setCellValue("Project");
            spend.createRow(1).createCell(0).setCellValue("Social media unrelated to billboard");
            for (String name : List.of("Client Update_WIP", "Client Update DONE")) {
                var tracker = book.createSheet(name);
                var fields = List.of("S/N", "CLIENTS", "DESCRIPTION", "JOB STATUS", "Timeline", "RESPONSIBILITY", "UPDATES");
                var trackerHeader = tracker.createRow(1);
                for (int i = 0; i < fields.size(); i++) trackerHeader.createCell(i).setCellValue(fields.get(i));
                var update = tracker.createRow(2);
                update.createCell(1).setCellValue("Test Client");
                update.createCell(2).setCellValue("OOH Ring Road");
                update.createCell(6).setCellValue("Client has PAID");
            }
            book.write(out);
            return out.toByteArray();
        }
    }

    @Test void stagesDraftsWithStrictDatesAndOriginalProvenance() throws Exception {
        var preview = new ExistingMediaWorkbook().inspect(fixture());
        assertThat(preview.drafts()).hasSize(2);
        var first = preview.drafts().getFirst();
        assertThat(first.dates()).containsEntry("initial Start Date", LocalDate.of(2025, 6, 13))
                .containsEntry("Expiration Date after renewal with vendors", LocalDate.of(2026, 9, 30))
                .containsEntry("Expiration Date with Vendor", LocalDate.of(2026, 10, 1))
                .doesNotContainKey("Expiration Date With Client");
        assertThat(first.values()).containsEntry("Client Status", "PAID");
        assertThat(first.operationalNotes()).singleElement().satisfies(note -> assertThat(note.notes()).isEqualTo("Client has PAID"));
        assertThat(preview.findings()).extracting(ExistingMediaWorkbook.Finding::code)
                .contains("VENDOR_EXPIRY_CONFLICT", "DATE_REVIEW", "IDENTITY_REVIEW", "BOARD_MATCH_REVIEW",
                        "REPEATED_SERIAL", "REPEATED_TRACKER_ROW");
        assertThat(preview.sheets().getFirst().rows().get(1).cells()).anySatisfy(cell -> {
            assertThat(cell.address()).isEqualTo("M2");
            assertThat(cell.formula()).isEqualTo("L2-K2");
        });
    }

    @Test void rejectsUnrelatedOrDamagedUploads() {
        assertThatThrownBy(() -> new ExistingMediaWorkbook().inspect(new byte[]{1, 2, 3}))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("xlsx");
    }

    @Test void readsSuppliedWorkbookWithoutSavingIt() throws Exception {
        String path = System.getProperty("adesa.media.workbook-fixture");
        assumeTrue(path != null, "Supply the internal workbook path to run this check.");
        byte[] bytes = Files.readAllBytes(Path.of(path));
        var preview = new ExistingMediaWorkbook().inspect(bytes);
        assertThat(preview.sheets()).extracting(ExistingMediaWorkbook.SourceSheet::name)
                .containsExactly("Client Update_WIP", "Client Update DONE", "Client Spend Tracker", "OOH Sheet");
        assertThat(preview.drafts()).hasSize(8);
        assertThat(preview.sheets().getLast().mergedRanges()).contains("A11:BZT11");
        assertThat(preview.sheets().getLast().headers()).containsEntry("O", "Client Status");
        assertThat(preview.findings().stream().filter(f -> f.code().equals("VENDOR_EXPIRY_CONFLICT"))).hasSize(4);
        assertThat(Files.readAllBytes(Path.of(path))).isEqualTo(bytes);
    }
}
