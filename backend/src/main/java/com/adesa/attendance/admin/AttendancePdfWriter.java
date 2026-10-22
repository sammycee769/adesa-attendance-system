package com.adesa.attendance.admin;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.adesa.attendance.attendance.dto.AdminAttendanceRecordResponse;
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.HeaderFooter;
import com.lowagie.text.PageSize;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

/**
 * Writes an attendance report as a genuine PDF.
 *
 * <p>The export includes a title, applied filters, the count of records and a
 * table with readable column widths. Multi-page reports repeat the header row
 * and include page breaks handled by OpenPDF.
 */
final class AttendancePdfWriter {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final Font TITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16, new Color(11, 92, 171));
    private static final Font SUBTITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA, 10, Color.DARK_GRAY);
    private static final Font HEADER_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.WHITE);
    private static final Font CELL_FONT = FontFactory.getFont(FontFactory.HELVETICA, 9, Color.BLACK);
    private static final Font FOOTER_FONT = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.DARK_GRAY);

    private AttendancePdfWriter() {
        // utility class
    }

    static byte[] write(List<AdminAttendanceRecordResponse> records, LocalDate from, LocalDate to) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4.rotate(), 36, 36, 72, 54);

        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            HeaderFooter footer = new HeaderFooter(
                    new Phrase("ADESA HQ Attendance Report | Page ", FOOTER_FONT), true);
            footer.setAlignment(Element.ALIGN_CENTER);
            footer.setBorder(Rectangle.NO_BORDER);
            document.setFooter(footer);

            document.open();

            // Title
            com.lowagie.text.Paragraph title = new com.lowagie.text.Paragraph("ADESA HQ Attendance Report", TITLE_FONT);
            title.setSpacingAfter(4f);
            document.add(title);

            // Filters / metadata
            StringBuilder filters = new StringBuilder("Date range: ");
            filters.append(from != null ? DATE.format(from) : "All");
            filters.append(" to ");
            filters.append(to != null ? DATE.format(to) : "All");
            filters.append(" | Records: ").append(records.size());
            filters.append(" | Generated: ").append(TIME.format(java.time.LocalDateTime.now()));
            com.lowagie.text.Paragraph meta = new com.lowagie.text.Paragraph(filters.toString(), SUBTITLE_FONT);
            meta.setSpacingAfter(12f);
            document.add(meta);

            if (records.isEmpty()) {
                com.lowagie.text.Paragraph empty = new com.lowagie.text.Paragraph(
                        "No attendance records match the selected filters.", SUBTITLE_FONT);
                empty.setSpacingBefore(20f);
                document.add(empty);
            } else {
                PdfPTable table = new PdfPTable(new float[]{10f, 14f, 8f, 9f, 9f, 12f, 12f, 7f});
                table.setWidthPercentage(100);
                table.setSpacingBefore(6f);
                table.setHeaderRows(1);
                table.setSplitLate(false);
                table.setSplitRows(true);

                addHeader(table, "Employee #");
                addHeader(table, "Employee");
                addHeader(table, "Role");
                addHeader(table, "Workday");
                addHeader(table, "State");
                addHeader(table, "Sign In");
                addHeader(table, "Sign Out");
                addHeader(table, "Corrected");

                for (AdminAttendanceRecordResponse r : records) {
                    addCell(table, r.employeeNumber());
                    addCell(table, r.employeeName());
                    addCell(table, r.employeeRole());
                    addCell(table, r.attendanceDate() != null ? r.attendanceDate().toString() : "");
                    addCell(table, r.state() != null ? r.state().name() : "");
                    addCell(table, r.signInAt() != null ? r.signInAt().toString().replace("T", " ") : "-");
                    addCell(table, r.signOutAt() != null ? r.signOutAt().toString().replace("T", " ") : "-");
                    addCell(table, Boolean.TRUE.equals(r.corrected()) ? "Yes" : "No");
                }

                document.add(table);
            }

            document.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate attendance PDF report", e);
        }
    }

    private static void addHeader(PdfPTable table, String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, HEADER_FONT));
        cell.setBackgroundColor(new Color(11, 92, 171));
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setPadding(6f);
        cell.setBorderColor(new Color(8, 70, 130));
        table.addCell(cell);
    }

    private static void addCell(PdfPTable table, String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text == null ? "" : text, CELL_FONT));
        cell.setPadding(5f);
        cell.setBorderColor(new Color(211, 218, 224));
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        table.addCell(cell);
    }
}
