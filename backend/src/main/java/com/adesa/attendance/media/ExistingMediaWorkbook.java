package com.adesa.attendance.media;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipInputStream;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Snapshot the source separately from any proposed interpretation. */
@Component
public class ExistingMediaWorkbook {
    static final int MAX_BYTES = 512 * 1024;
    private static final String OOH = "OOH Sheet";
    private static final String CLIENT = "Client";
    private static final String VENDOR = "Vendor Name";
    private static final String LOCATION = "OOH Location";
    private static final String START = "initial Start Date";
    private static final String RENEWAL = "Renewed Date";
    private static final String RENEWED_VENDOR_END = "Expiration Date after renewal with vendors";
    private static final String VENDOR_END = "Expiration Date with Vendor";
    private static final String CLIENT_END = "Expiration Date With Client";
    private static final List<String> DATES = List.of(START, RENEWAL, RENEWED_VENDOR_END, CLIENT_END, VENDOR_END);
    private static final List<String> COSTS = List.of("Cost with Vendor", "Cost with Client");
    private static final List<String> REQUIRED = List.of("S/N", CLIENT, LOCATION, VENDOR, START, RENEWAL,
            RENEWED_VENDOR_END, CLIENT_END, VENDOR_END, "Duration", "Cost with Vendor", "Cost with Client");
    private static final List<String> TRACKERS = List.of("Client Update_WIP", "Client Update DONE");

    public record Cell(String address, String type, String value, String display,
                       String formula, String numberFormat, String excelDate) {}
    public record SourceRow(int number, List<Cell> cells) {}
    public record SourceSheet(String name, Integer headerRow, Map<String, String> headers,
                              List<String> mergedRanges, List<SourceRow> rows) {}
    public record Finding(String sheet, int row, String column, String code, String message) {}
    public record OperationalNote(String sheet, int row, String status, String responsibility, String notes) {}
    public record Draft(int row, Map<String, String> values, Map<String, LocalDate> dates,
                        Map<String, BigDecimal> rates, List<OperationalNote> operationalNotes) {}
    public record Preview(List<SourceSheet> sheets, List<Draft> drafts, List<Finding> findings) {}

    public Preview inspect(byte[] bytes) {
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw invalid("Upload an .xlsx file up to 512 KB.");
        verifyArchive(bytes);
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var formatter = new DataFormatter(Locale.ROOT, true);
            formatter.setUseCachedValuesForFormulaCells(true);
            var ngn = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("en-NG"));
            var sheets = new ArrayList<SourceSheet>();
            int count = 0;
            for (var sheet : book) {
                var rows = new ArrayList<SourceRow>();
                if (sheets.size() == 20) throw invalid("The workbook has too many sheets.");
                for (var row : sheet) {
                    if (row.getRowNum() >= 5_000) throw invalid("A workbook sheet exceeds 5,000 rows.");
                    var cells = new ArrayList<Cell>();
                    for (var item : row) {
                        if (++count > 50_000) throw invalid("The workbook exceeds 50,000 cells.");
                        var cell = (XSSFCell) item;
                        var type = cell.getCellType();
                        var cached = type == CellType.FORMULA ? cell.getCachedFormulaResultType() : type;
                        String date = cached == CellType.NUMERIC && type != CellType.FORMULA
                                && DateUtil.isCellDateFormatted(cell)
                                ? cell.getLocalDateTimeCellValue().toLocalDate().toString() : null;
                        String raw = type == CellType.STRING ? cell.getStringCellValue() : cell.getRawValue();
                        String format = cell.getCellStyle().getDataFormatString();
                        // POI cannot render the workbook’s conditional Naira accounting format.
                        String display;
                        if (cached == CellType.NUMERIC && format != null && format.contains("[$₦-en-NG]")) {
                            display = raw == null ? "" : ngn.format(new BigDecimal(raw));
                        } else {
                            display = formatter.formatCellValue(cell);
                        }
                        cells.add(new Cell(cell.getAddress().formatAsString(), type.name(), raw, display,
                                type == CellType.FORMULA ? cell.getCellFormula() : null, format, date));
                    }
                    rows.add(new SourceRow(row.getRowNum() + 1, cells));
                }
                String name = sheet.getSheetName();
                Integer header = TRACKERS.contains(name) ? 2 : OOH.equals(name) || "Client Spend Tracker".equals(name) ? 1 : null;
                var headers = new LinkedHashMap<String, String>();
                if (header != null) {
                    rows.stream().filter(r -> r.number() == header).findFirst().ifPresent(r ->
                            r.cells().forEach(c -> headers.put(column(c.address()), c.display())));
                }
                sheets.add(new SourceSheet(name, header, headers,
                        sheet.getMergedRegions().stream().map(CellRangeAddress::formatAsString).toList(), rows));
            }
            return interpret(sheets);
        } catch (IOException | IllegalArgumentException | org.apache.poi.ooxml.POIXMLException ex) {
            throw invalid("The file could not be read as the existing ADESA Media .xlsx workbook.");
        }
    }

    private static void verifyArchive(byte[] bytes) {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            var names = new HashSet<String>();
            int total = 0;
            byte[] buffer = new byte[8192];
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (names.size() >= 200 || !names.add(entry.getName())) throw invalid("Unsupported workbook archive.");
                for (int size; (size = zip.read(buffer)) > 0;) {
                    total += size;
                    if (total > 12 * 1024 * 1024) throw invalid("The expanded workbook is too large.");
                }
            }
            if (!names.contains("xl/workbook.xml")) throw invalid("Upload the existing ADESA Media .xlsx workbook.");
        } catch (IOException ex) {
            throw invalid("The workbook archive is damaged.");
        }
    }

    private static Preview interpret(List<SourceSheet> sheets) {
        var primary = sheets.stream().filter(s -> OOH.equals(s.name())).findFirst()
                .orElseThrow(() -> invalid("Missing the OOH Sheet."));
        if (!primary.headers().values().containsAll(REQUIRED)) throw invalid("The OOH Sheet is missing existing contract headers.");
        var drafts = new ArrayList<Draft>();
        var findings = new ArrayList<Finding>();
        var locations = new HashMap<String, Integer>();
        var serials = new HashMap<String, Integer>();
        var clients = new HashMap<String, Integer>();
        for (var row : primary.rows()) {
            if (row.number() == 1) continue;
            var values = values(primary, row);
            if (List.of(CLIENT, LOCATION, VENDOR).stream().allMatch(key -> value(values, key).isBlank())) continue;
            var dates = new LinkedHashMap<String, LocalDate>();
            var rates = new LinkedHashMap<String, BigDecimal>();
            for (String field : DATES) {
                Cell cell = values.get(field);
                if (value(values, field).isBlank()) {
                    if (field.equals(START) || field.equals(CLIENT_END)
                            || field.equals(VENDOR_END) && value(values, RENEWED_VENDOR_END).isBlank()) {
                        flag(findings, primary, row.number(), field, "MISSING_DATE", "Confirm the complete date.");
                    }
                    continue;
                }
                LocalDate date = date(cell);
                if (date == null) flag(findings, primary, row.number(), field, "DATE_REVIEW", "Confirm the full, unambiguous date and year.");
                else dates.put(field, date);
            }
            LocalDate renewedEnd = dates.get(RENEWED_VENDOR_END);
            LocalDate vendorEnd = dates.get(VENDOR_END);
            if (renewedEnd != null && vendorEnd != null && !renewedEnd.equals(vendorEnd)) {
                flag(findings, primary, row.number(), VENDOR_END, "VENDOR_EXPIRY_CONFLICT",
                        "Vendor expiry columns disagree. Confirm the authoritative date before scheduling a reminder.");
            }
            LocalDate start = dates.get(START);
            LocalDate renewal = dates.get(RENEWAL);
            for (String field : List.of(RENEWAL, RENEWED_VENDOR_END, CLIENT_END, VENDOR_END)) {
                LocalDate candidate = dates.get(field);
                LocalDate lower = field.equals(RENEWED_VENDOR_END) && renewal != null ? renewal : start;
                if (lower != null && candidate != null && candidate.isBefore(lower)) {
                    flag(findings, primary, row.number(), field, "CONTRADICTORY_DATE", "This date precedes the applicable start or renewal date.");
                }
            }
            for (String field : COSTS) {
                try {
                    Cell cell = values.get(field);
                    if (cell == null || cell.formula() != null) throw new NumberFormatException();
                    String raw = "NUMERIC".equals(cell.type()) ? cell.value() : cell.display().trim();
                    if (raw == null) throw new NumberFormatException();
                    String cleaned = raw.replaceFirst("^₦\\s*", "");
                    if (!"NUMERIC".equals(cell.type()) && !cleaned.matches("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]{1,2})?")) {
                        throw new NumberFormatException();
                    }
                    BigDecimal rate = new BigDecimal(cleaned.replace(",", "")).stripTrailingZeros();
                    if (rate.signum() < 0 || rate.scale() > 2) throw new NumberFormatException();
                    rates.put(field, rate);
                } catch (NumberFormatException ex) {
                    flag(findings, primary, row.number(), field, "RATE_REVIEW", "Confirm the contractual amount in NGN.");
                }
            }
            for (String field : List.of(CLIENT, VENDOR, LOCATION)) {
                if (value(values, field).isBlank()) flag(findings, primary, row.number(), field, "IDENTITY_REVIEW",
                        "Confirm the missing identity. Blank client cells may be continuations.");
            }
            repeated(findings, primary, row, LOCATION, value(values, LOCATION), locations, "BOARD_MATCH_REVIEW");
            repeated(findings, primary, row, "S/N", value(values, "S/N"), serials, "REPEATED_SERIAL");
            repeated(findings, primary, row, CLIENT, value(values, CLIENT), clients, "REPEATED_CLIENT");
            flag(findings, primary, row.number(), "", "DRAFT_COMPLETION",
                    "Confirm board identity, separate contract periods, payment due dates and internal recipients before activation.");
            var display = new LinkedHashMap<String, String>();
            values.forEach((label, cell) -> display.put(label, cell.display()));
            drafts.add(new Draft(row.number(), display, dates, rates, new ArrayList<>()));
        }
        supplement(sheets, drafts, findings);
        return new Preview(sheets, drafts, findings);
    }

    private static void supplement(List<SourceSheet> sheets, List<Draft> drafts, List<Finding> findings) {
        var seen = new HashSet<List<String>>();
        for (var sheet : sheets) {
            if (!TRACKERS.contains(sheet.name())) continue;
            for (var row : sheet.rows()) {
                if (row.number() <= 2) continue;
                var values = values(sheet, row);
                String description = value(values, "DESCRIPTION");
                if (description.isBlank()) continue;
                String client = mergedValue(sheet, row, "CLIENTS");
                String status = value(values, "JOB STATUS");
                String owner = mergedValue(sheet, row, "RESPONSIBILITY");
                String notes = value(values, "UPDATES");
                if (!seen.add(List.of(normalize(client), normalize(description), normalize(status), normalize(owner), normalize(notes),
                        normalize(value(values, "Timeline"))))) {
                    flag(findings, sheet, row.number(), "DESCRIPTION", "REPEATED_TRACKER_ROW", "A matching operations note was already staged.");
                    continue;
                }
                String words = " " + normalize(description) + " ";
                var matches = drafts.stream().filter(d -> {
                    String location = normalize(d.values().getOrDefault(LOCATION, ""));
                    return !location.isEmpty() && !client.isBlank() && normalize(client).equals(normalize(d.values().getOrDefault(CLIENT, "")))
                            && words.contains(" " + location + " ")
                            && (words.contains(" ooh ") || words.contains(" billboard "));
                }).toList();
                if (matches.size() == 1) {
                    matches.getFirst().operationalNotes().add(new OperationalNote(sheet.name(), row.number(), status, owner, notes));
                } else if (words.contains(" ooh ") || words.contains(" billboard ")) {
                    flag(findings, sheet, row.number(), "DESCRIPTION", "TRACKER_MATCH_REVIEW",
                            "No unique matching OOH board. Confirm the client name and location manually.");
                }
                String timeline = value(values, "Timeline");
                if (!timeline.isBlank() && date(values.get("Timeline")) == null) {
                    flag(findings, sheet, row.number(), "Timeline", "TIMELINE_REVIEW", "Confirm the operations timeline separately from contract expiry.");
                }
            }
        }
    }

    private static String mergedValue(SourceSheet sheet, SourceRow row, String header) {
        String value = value(values(sheet, row), header);
        if (!value.isBlank()) return value;
        String key = sheet.headers().entrySet().stream().filter(e -> e.getValue().equals(header))
                .map(Map.Entry::getKey).findFirst().orElse("");
        if (key.isEmpty()) return "";
        for (String range : sheet.mergedRanges()) {
            var cells = CellRangeAddress.valueOf(range);
            if (cells.getFirstColumn() == cells.getLastColumn()
                    && org.apache.poi.ss.util.CellReference.convertNumToColString(cells.getFirstColumn()).equals(key)
                    && cells.isInRange(row.number() - 1, cells.getFirstColumn())) {
                return sheet.rows().stream().filter(r -> r.number() == cells.getFirstRow() + 1)
                        .map(r -> value(values(sheet, r), header)).findFirst().orElse("");
            }
        }
        return "";
    }

    private static Map<String, Cell> values(SourceSheet sheet, SourceRow row) {
        var result = new LinkedHashMap<String, Cell>();
        for (var cell : row.cells()) {
            String header = sheet.headers().get(column(cell.address()));
            if (header != null && !header.isBlank()) result.putIfAbsent(header, cell);
        }
        return result;
    }

    private static String value(Map<String, Cell> values, String name) {
        Cell cell = values.get(name);
        return cell == null ? "" : cell.display();
    }

    private static String column(String address) { return address.replaceFirst("[0-9]+$", ""); }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static void repeated(List<Finding> findings, SourceSheet sheet, SourceRow row,
                                 String label, String value, Map<String, Integer> seen, String code) {
        if (value.isBlank()) return;
        Integer first = seen.putIfAbsent(normalize(value), row.number());
        if (first != null) flag(findings, sheet, row.number(), label, code,
                "Also present on row " + first + ". Review the identity before matching or merging.");
    }

    private static void flag(List<Finding> findings, SourceSheet sheet, int row, String header, String code, String message) {
        String column = sheet.headers().entrySet().stream().filter(e -> e.getValue().equals(header))
                .map(Map.Entry::getKey).findFirst().orElse("");
        findings.add(new Finding(sheet.name(), row, column, code, message));
    }

    private static LocalDate date(Cell cell) {
        if (cell == null || cell.formula() != null) return null;
        if (cell.excelDate() != null) return LocalDate.parse(cell.excelDate());
        if (!"STRING".equals(cell.type())) return null;
        String text = cell.display().trim().replaceAll("(?i)(?<=\\d)(st|nd|rd|th)\\b", "")
                .replace(",", "").replaceAll("\\s+", " ");
        if (text.matches("[0-9]{1,2}/[0-9]{1,2}/[0-9]{4}")) {
            String[] parts = text.split("/");
            int first = Integer.parseInt(parts[0]), second = Integer.parseInt(parts[1]);
            if (first <= 12 && second <= 12 && first != second) return null;
            return parse(text, first > 12 ? "d/M/uuuu" : "M/d/uuuu");
        }
        for (String pattern : List.of("uuuu-MM-dd", "MMM d uuuu", "MMMM d uuuu", "d MMM uuuu", "d MMMM uuuu")) {
            LocalDate result = parse(text, pattern);
            if (result != null) return result;
        }
        return null;
    }

    private static LocalDate parse(String text, String pattern) {
        DateTimeFormatter formatter = new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern)
                .toFormatter(Locale.ENGLISH).withResolverStyle(java.time.format.ResolverStyle.STRICT);
        try { return LocalDate.parse(text, formatter); }
        catch (DateTimeParseException ex) { return null; }
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
