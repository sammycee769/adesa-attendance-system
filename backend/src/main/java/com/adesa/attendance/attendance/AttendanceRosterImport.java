package com.adesa.attendance.attendance;

import com.adesa.attendance.audit.AuditAction;
import com.adesa.attendance.audit.AuditService;
import com.adesa.attendance.common.BadRequestException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AttendanceRosterImport {
    private static final String HEADER = String.join("\t", "Employee number","Workday","Employment start","Employment end",
            "Subsidiary","Department","Arrangement","Late after","Absence cutoff","Shift end","Remarks");
    private static final Set<String> ARRANGEMENTS =
            Set.of("OFFICE","REMOTE","APPROVED_LEAVE","EXCUSED_ABSENCE","NOT_SCHEDULED");
    private static final String INSERT = """
            INSERT INTO attendance_private.daily_roster(workday,employee_id,employment_start,employment_end,
                subsidiary_id,department,arrangement,late_after,absence_at,shift_end,remarks,source_reference)
            VALUES (:day,:employee,:start,:end,:subsidiary,:department,:arrangement,:late,:cutoff,:shiftEnd,:remarks,:source)
            """;
    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;
    private final WorkdayResolver workdays;

    public AttendanceRosterImport(NamedParameterJdbcTemplate jdbc, AuditService audit, WorkdayResolver workdays) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.workdays = workdays;
    }

    @Transactional(readOnly = true)
    public byte[] template(LocalDate date) {
        var file = new StringBuilder(HEADER).append('\n');
        jdbc.query("""
                SELECT e.employee_number,s.name AS subsidiary FROM public.employees e
                LEFT JOIN public.subsidiaries s ON s.id=e.subsidiary_id WHERE e.is_active
                ORDER BY e.employee_number
                """, Map.of(), result -> {
            file.append(spreadsheetCell(result.getString("employee_number"))).append('\t').append(date)
                    .append("\t\t\t").append(spreadsheetCell(result.getString("subsidiary"))).append("\t\t\t\t\t\t\n");
        });
        return file.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Transactional
    public int load(String text, String sourceReference, UUID actor) {
        if (text == null || text.length() > 800_000 || sourceReference == null
                || sourceReference.isBlank() || sourceReference.strip().length() > 200) {
            throw new BadRequestException("Provide an approved roster file and a source reference up to 200 characters");
        }
        String[] lines = text.replaceFirst("^\uFEFF", "").split("\\R",-1);
        if (lines.length < 2 || lines.length > 2002 || !HEADER.equals(lines[0])) {
            throw new BadRequestException("Use the roster template with up to 2,000 employee-day rows");
        }
        var employees = index("SELECT employee_number AS lookup,id FROM public.employees");
        var subsidiaries = index("SELECT lower(name) AS lookup,id FROM public.subsidiaries");
        var statements = new ArrayList<MapSqlParameterSource>();
        var keys = new HashSet<String>();
        LocalDate first = null, last = null;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            String[] cells = lines[i].split("\t",-1);
            if (cells.length != 11) throw rowError(i,"Expected 11 tab-separated columns");
            for (int col = 0; col < cells.length; col++) cells[col] = cells[col].strip();
            var employee = employees.get(unescape(cells[0]));
            var subsidiary = subsidiaries.get(unescape(cells[4]).toLowerCase(Locale.ROOT));
            if (employee == null || !cells[4].isEmpty() && subsidiary == null) {
                throw rowError(i,"Unknown employee number or subsidiary");
            }
            LocalDate day = date(cells[1],i,false);
            LocalDate start = date(cells[2],i,false);
            LocalDate end = date(cells[3],i,true);
            String arrangement = cells[6].toUpperCase(Locale.ROOT);
            if (!ARRANGEMENTS.contains(arrangement) || day.isBefore(start) || end != null && day.isAfter(end)) {
                throw rowError(i,"Choose a valid arrangement and a working date within employment");
            }
            var late = time(cells[7],day,i);
            var cutoff = time(cells[8],day,i);
            var shiftEnd = time(cells[9],day,i);
            if (arrangement.equals("OFFICE") && (cutoff == null || shiftEnd == null)
                    || shiftEnd != null && (cutoff != null && cutoff.isAfter(shiftEnd) || late != null && late.isAfter(shiftEnd))) {
                throw rowError(i,"Office rows require an absence cutoff and shift end in the correct order");
            }
            if (cells[5].length() > 120 || cells[10].length() > 500 || !keys.add(employee + ":" + day)) {
                throw rowError(i,"Duplicate employee and date, or the department or remarks exceed the limit");
            }
            if (first == null || day.isBefore(first)) first = day;
            if (last == null || day.isAfter(last)) last = day;
            statements.add(new MapSqlParameterSource().addValue("day",day).addValue("employee",employee)
                    .addValue("start",start).addValue("end",end).addValue("subsidiary",subsidiary)
                    .addValue("department",cells[5].isEmpty() ? null : cells[5]).addValue("arrangement",arrangement)
                    .addValue("late",late).addValue("cutoff",cutoff).addValue("shiftEnd",shiftEnd)
                    .addValue("remarks",cells[10]).addValue("source",sourceReference.strip()));
        }
        if (statements.isEmpty() || statements.size() > 2000) throw new BadRequestException("Provide 1 to 2,000 employee-day rows");
        jdbc.batchUpdate(INSERT,statements.toArray(MapSqlParameterSource[]::new));
        audit.record(actor,AuditAction.ATTENDANCE_ROSTER_IMPORTED,"ATTENDANCE_ROSTER",null,Map.of(
                "rows",statements.size(),"from",first.toString(),"to",last.toString(),"source",sourceReference.strip()));
        return statements.size();
    }

    private Map<String,UUID> index(String query) {
        var values = new HashMap<String,UUID>();
        jdbc.query(query,Map.of(),row -> { values.put(row.getString("lookup"),row.getObject("id",UUID.class)); });
        return values;
    }

    private static LocalDate date(String value, int line, boolean optional) {
        if (value.isEmpty() && optional) return null;
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException ex) { throw rowError(line,"Use YYYY-MM-DD for dates"); }
    }

    private OffsetDateTime time(String value, LocalDate day, int line) {
        if (value.isEmpty()) return null;
        try {
            var time = LocalDateTime.parse(value);
            if (time.toLocalDate().isBefore(day) || time.toLocalDate().isAfter(day.plusDays(1))) {
                throw rowError(line,"Clock rules must fall on the workday or following day");
            }
            return time.atZone(workdays.zone()).toOffsetDateTime();
        } catch (DateTimeParseException ex) { throw rowError(line,"Use YYYY-MM-DDTHH:MM for clock rules"); }
    }

    private static BadRequestException rowError(int index, String message) {
        return new BadRequestException("Roster line " + (index + 1) + ": " + message);
    }

    private static String spreadsheetCell(String value) {
        if (value == null) return "";
        String safe = value.replaceAll("[\\t\\r\\n]", " ");
        return !safe.isEmpty() && "=+-@".indexOf(safe.charAt(0)) >= 0 ? "'" + safe : safe;
    }

    private static String unescape(String value) {
        return value.length() > 1 && value.charAt(0) == '\'' && "=+-@".indexOf(value.charAt(1)) >= 0 ? value.substring(1) : value;
    }
}
