package com.adesa.attendance.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
class MediaReports {
    private final MediaRegisterService service;
    MediaReports(MediaRegisterService service) { this.service = service; }

    enum Report { BOARDS, CONTRACTS, EXPIRIES, RENEWALS, OBLIGATIONS, PAYMENTS, BALANCES, PROFITABILITY, REMINDERS }
    record Filter(UUID board, UUID vendor, UUID client, String status, LocalDate from, LocalDate to, boolean archived) {}
    record Table(List<String> headers, List<List<String>> rows) {}

    Table dataset(UUID actor, Report report, Filter filter) {
        if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The report date range is invalid.");
        }
        var register = service.register(actor);
        var parties = index(register.path("parties"));
        var boards = index(register.path("boards"));
        var contracts = index(register.path("contracts"));
        var obligations = index(register.path("obligations"));
        var rows = new ArrayList<List<String>>();
        var day = LocalDate.now(ZoneId.of("Africa/Lagos"));
        List<String> headers = switch (report) {
            case BOARDS -> labels("Board","Description","Location","Vendor","Client","Job status","Archived");
            case CONTRACTS, EXPIRIES, RENEWALS -> labels("Board","Side","Counterparty","Start","Expiry","NGN","Lifecycle","Renewed from");
            case OBLIGATIONS, BALANCES -> labels("Board","Side","Expiry","Amount NGN","Paid NGN","Balance NGN","Invoice","Payment status");
            case PAYMENTS -> labels("Board","Side","Agreement expiry","Reference","Paid date","Entry","NGN","SLA","Reason","Evidence");
            case PROFITABILITY -> labels("Board","Start","End","Vendor NGN","Client NGN","Gross profit NGN","Margin %","Cash in NGN","Cash out NGN");
            case REMINDERS -> labels("Board","Reminder","Side","Reference date","Reminder date","Stage days before","Outcome","Attempts");
        };
        switch (report) {
            case BOARDS -> {
                for (var b : register.path("boards")) if (eligible(b, filter) && dateRange(b,"created_at",filter)
                        && statusMatches(filter, text(b,"status"))) {
                    rows.add(labels(text(b,"code"),text(b,"description"),text(b,"location"),
                            text(find(parties,b,"vendor_id"),"name"),text(find(parties,b,"client_id"),"name"),
                            text(b,"status"),text(b,"archived_at")));
                }
            }
            case CONTRACTS, EXPIRIES, RENEWALS -> {
                for (var c : register.path("contracts")) {
                    var b = find(boards,c,"board_id");
                    String state = contractState(c, day);
                    if (!eligible(b,filter) || !visible(c,filter) || !statusMatches(filter,state) || !dateRange(c,"end_date",filter)
                            || report==Report.EXPIRIES && !state.equals("EXPIRED") && !state.equals("EXPIRING")
                            || report==Report.RENEWALS && text(c,"renews_id").isBlank()) continue;
                    rows.add(labels(text(b,"code"),text(c,"kind"),text(find(parties,c,"party_id"),"name"),
                            text(c,"start_date"),text(c,"end_date"),text(c,"amount"),state,text(c,"renews_id")));
                }
            }
            case OBLIGATIONS, BALANCES -> {
                for (var c : register.path("contracts")) {
                    var b = find(boards,c,"board_id");
                    String state = paymentState(c);
                    if (!eligible(b,filter) || !visible(c,filter) || !c.path("confirmed").asBoolean()
                            || !dateRange(c,"end_date",filter) || !statusMatches(filter,state)
                            || report==Report.BALANCES && money(c,"balance").signum()==0) continue;
                    String invoice = !"CLIENT".equals(text(c,"kind")) ? ""
                            : c.path("invoice_sent").asBoolean() ? "SENT" : "UNSENT";
                    rows.add(labels(text(b,"code"),text(c,"kind"),text(c,"end_date"),
                            text(c,"amount"),text(c,"paid"),text(c,"balance"),invoice,state));
                }
            }
            case PAYMENTS -> {
                for (var t : register.path("transactions")) {
                    var legacy = find(obligations,t,"obligation_id");
                    var c = text(t,"contract_id").isBlank() ? find(contracts,legacy,"contract_id") : find(contracts,t,"contract_id");
                    var b = find(boards,c,"board_id");
                    String kind = !text(t,"reversal_of").isBlank() ? "REVERSAL" : !text(t,"replacement_for").isBlank() ? "CORRECTION" : "PAYMENT";
                    if (!eligible(b,filter) || !visible(c,filter) || !dateRange(t,"paid_on",filter) || !statusMatches(filter,kind)) continue;
                    rows.add(labels(text(b,"code"),text(c,"kind"),text(c,"end_date"),text(t,"reference"),
                            text(t,"paid_on"),kind,text(t,"amount"),text(t,"sla_link"),text(t,"reason"),text(t,"evidence")));
                }
            }
            case PROFITABILITY -> {
                for (var vendor : register.path("contracts")) {
                    var b = find(boards,vendor,"board_id");
                    if (!eligible(b,filter) || !visible(vendor,filter) || !vendor.path("confirmed").asBoolean()
                            || !"VENDOR".equals(text(vendor,"kind")) || !dateRange(vendor,"end_date",filter)
                            || !statusMatches(filter,contractState(vendor,day))) continue;
                    for (var client : register.path("contracts")) {
                        if (!client.path("confirmed").asBoolean() || !visible(client,filter) || !"CLIENT".equals(text(client,"kind"))
                                || !text(vendor,"board_id").equals(text(client,"board_id"))
                                || !text(vendor,"start_date").equals(text(client,"start_date"))
                                || !text(vendor,"end_date").equals(text(client,"end_date"))) continue;
                        var cost=money(vendor,"amount"); var revenue=money(client,"amount"); var profit=revenue.subtract(cost);
                        String margin = revenue.signum() == 0 ? "" : profit.multiply(BigDecimal.valueOf(100))
                                .divide(revenue,2,RoundingMode.HALF_UP).toPlainString();
                        rows.add(labels(text(b,"code"),text(client,"start_date"),text(client,"end_date"),formatMoney(cost),
                                formatMoney(revenue),formatMoney(profit),margin,formatMoney(money(client,"paid")),
                                formatMoney(money(vendor,"paid"))));
                    }
                }
            }
            case REMINDERS -> {
                for (var r : service.reminders(actor)) {
                    var p=r.path("payload");
                    if (!eligible(find(boards,p,"boardId"),filter) || !dateRange(r,"reminder_day",filter)
                            || !statusMatches(filter,text(r,"status"))) continue;
                    rows.add(labels(text(p,"board"),text(r,"entity"),text(p,"side"),text(p,"date"),
                            text(r,"reminder_day"),text(r,"stage"),text(r,"status"),text(r,"attempts")));
                }
            }
        }
        return new Table(headers,rows);
    }

    byte[] export(Table table, Report report, String format) {
        try (var bytes = new ByteArrayOutputStream()) {
            if ("xlsx".equals(format)) {
                try (var book = new XSSFWorkbook()) {
                    var sheet = book.createSheet(report.name());
                    List<List<String>> all = new ArrayList<>(table.rows());
                    all.addFirst(table.headers());
                    for (int i=0;i<all.size();i++) {
                        var row=sheet.createRow(i);
                        for (int j=0;j<all.get(i).size();j++) row.createCell(j).setCellValue(all.get(i).get(j));
                    }
                    sheet.createFreezePane(0,1);
                    for (int i=0;i<table.headers().size();i++) sheet.setColumnWidth(i,24*256);
                    book.write(bytes);
                }
            } else if ("pdf".equals(format)) {
                try (var doc = new Document(PageSize.A4.rotate())) {
                    PdfWriter.getInstance(doc,bytes);
                    doc.open();
                    doc.add(new Paragraph("ADESA Media | " + report.name()));
                    doc.add(new Paragraph(" "));
                    var pdfTable=new PdfPTable(table.headers().size());
                    pdfTable.setWidthPercentage(100);
                    table.headers().forEach(pdfTable::addCell);
                    pdfTable.setHeaderRows(1);
                    table.rows().forEach(r -> r.forEach(pdfTable::addCell));
                    doc.add(pdfTable);
                }
            } else {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose PDF or Excel.");
            }
            return bytes.toByteArray();
        } catch (IOException | DocumentException ex) {
            throw new IllegalStateException("Media report generation failed",ex);
        }
    }

    static String contractState(JsonNode c, LocalDate day) {
        if (!text(c,"archived_at").isBlank()) return "ARCHIVED";
        if (!c.path("confirmed").asBoolean()) return "DRAFT";
        if (c.path("superseded").asBoolean()) return "RENEWED";
        var expires=LocalDate.parse(text(c,"end_date"));
        if (expires.isBefore(day)) return "EXPIRED";
        if (LocalDate.parse(text(c,"start_date")).isAfter(day)) return "UPCOMING";
        return !expires.isAfter(day.plusDays(30)) ? "EXPIRING" : "ACTIVE";
    }

    static String paymentState(JsonNode agreement) {
        if (money(agreement,"balance").signum()==0) return "PAID";
        if ("CLIENT".equals(text(agreement,"kind"))) return "AWAITING_BALANCE";
        return money(agreement,"paid").signum()>0 ? "PART_PAYMENT" : "OWING";
    }

    private static boolean eligible(JsonNode b, Filter f) {
        return !b.isMissingNode() && visible(b,f) && matches(b,"id",f.board())
                && matches(b,"vendor_id",f.vendor()) && matches(b,"client_id",f.client());
    }
    private static boolean visible(JsonNode node, Filter f) { return f.archived() || text(node,"archived_at").isBlank(); }
    private static boolean matches(JsonNode node,String key,UUID expected) { return expected==null || expected.toString().equals(text(node,key)); }
    private static boolean statusMatches(Filter f,String state) { return f.status()==null || f.status().isBlank() || f.status().equalsIgnoreCase(state); }
    private static boolean dateRange(JsonNode node,String key,Filter f) {
        if (f.from()==null && f.to()==null) return true;
        String value=text(node,key);
        if (value.length()<10) return false;
        var day=LocalDate.parse(value.substring(0,10));
        return (f.from()==null || !day.isBefore(f.from())) && (f.to()==null || !day.isAfter(f.to()));
    }
    private static Map<String,JsonNode> index(JsonNode rows) {
        var map=new HashMap<String,JsonNode>();
        rows.forEach(row -> map.put(text(row,"id"),row));
        return map;
    }
    private static JsonNode find(Map<String,JsonNode> rows,JsonNode row,String key) {
        return rows.getOrDefault(text(row,key),com.fasterxml.jackson.databind.node.MissingNode.getInstance());
    }
    private static String text(JsonNode node,String key) { return node.path(key).asText(""); }
    private static BigDecimal money(JsonNode row,String key) {
        return row.path(key).isNumber() ? row.path(key).decimalValue() : BigDecimal.ZERO;
    }
    private static String formatMoney(BigDecimal value) { return value.setScale(2).toPlainString(); }
    private static List<String> labels(String... items) { return List.of(items); }
}
