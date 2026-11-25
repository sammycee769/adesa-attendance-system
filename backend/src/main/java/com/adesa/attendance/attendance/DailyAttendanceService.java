package com.adesa.attendance.attendance;

import com.adesa.attendance.common.BadRequestException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.adesa.attendance.attendance.DailyAttendanceReport.*;

@Service
public class DailyAttendanceService {
    private static final int MAX_EXPORT_ROWS = 10_000;
    private static final int MAX_PAGE_ROWS = 10_000;
    private static final int MAX_RANGE_DAYS = 366;
    private static final String QUERY = """
            WITH punches AS (
                SELECT * FROM public.attendance_records WHERE attendance_date BETWEEN :from AND :to
            ), roster AS (
                SELECT * FROM attendance_private.daily_roster WHERE workday BETWEEN :from AND :to
            ), weekdays AS (
                SELECT date_value::date AS day
                FROM generate_series(greatest(CAST(:from AS date), CAST(:policyFrom AS date)),
                    least(CAST(:to AS date), CAST(:today AS date)), interval '1 day') AS dates(date_value)
                WHERE extract(isodow FROM date_value) BETWEEN 1 AND 5
            ), defaults AS (
                SELECT w.day, e.id AS employee_id
                FROM weekdays w JOIN public.employees e ON e.is_active
                    AND (e.created_at AT TIME ZONE :zone)::date <= w.day
            ), employee_days AS (
                SELECT attendance_date AS day, employee_id FROM punches
                UNION SELECT workday, employee_id FROM roster
                UNION SELECT day, employee_id FROM defaults
            ), combined AS (
                SELECT d.day, e.id AS employee_id, e.employee_number, e.full_name,
                       s.name AS subsidiary, r.department, p.id AS record_id, p.sign_in_at, p.sign_out_at,
                       r.remarks, r.arrangement, r.shift_end, r.absence_at,
                       (r.employee_id IS NULL AND w.employee_id IS NOT NULL) AS standard_weekday,
                       coalesce(p.sign_in_at > r.late_after, false) AS late,
                       coalesce(r.arrangement IN ('OFFICE','REMOTE'), w.employee_id IS NOT NULL) AS expected
                FROM employee_days d
                JOIN public.employees e ON e.id=d.employee_id
                LEFT JOIN roster r ON r.employee_id=d.employee_id AND r.workday=d.day
                LEFT JOIN punches p ON p.employee_id=d.employee_id AND p.attendance_date=d.day
                LEFT JOIN defaults w ON w.employee_id=d.employee_id AND w.day=d.day
                LEFT JOIN public.subsidiaries s ON s.id=CASE WHEN r.employee_id IS NULL THEN e.subsidiary_id ELSE r.subsidiary_id END
                JOIN (SELECT attendance_date AS day FROM punches
                      UNION SELECT workday FROM roster WHERE arrangement <> 'NOT_SCHEDULED'
                      UNION SELECT d2.day FROM defaults d2 WHERE NOT EXISTS (
                          SELECT 1 FROM roster r2 WHERE r2.workday=d2.day AND r2.employee_id=d2.employee_id
                              AND r2.arrangement='NOT_SCHEDULED')) working_days ON working_days.day=d.day
                WHERE (CAST(:employee AS uuid) IS NULL OR e.id=:employee)
                  AND (:search='' OR strpos(lower(e.full_name),:search)>0 OR strpos(lower(e.employee_number),:search)>0
                       OR strpos(lower(e.email),:search)>0)
                  AND (:subsidiary='' OR lower(s.name)=:subsidiary)
                  AND (:department='' OR lower(r.department)=:department)
            ), classified AS (
                SELECT c.*, count(*) FILTER (WHERE expected AND arrangement IS DISTINCT FROM 'REMOTE'
                    AND extract(isodow FROM day) IN (1,3,5)) OVER (PARTITION BY employee_id) AS expected_office_days,
                CASE
                    WHEN sign_in_at IS NOT NULL AND sign_out_at IS NULL AND
                        (shift_end <= :now OR (shift_end IS NULL AND day < :today)) THEN 'INCOMPLETE_ATTENDANCE'
                    WHEN sign_in_at IS NOT NULL AND late THEN 'LATE'
                    WHEN sign_out_at IS NOT NULL THEN 'SIGNED_OUT'
                    WHEN sign_in_at IS NOT NULL AND arrangement='REMOTE' THEN 'REMOTE_WORK'
                    WHEN sign_in_at IS NOT NULL THEN 'PRESENT'
                    WHEN arrangement='APPROVED_LEAVE' THEN 'ON_APPROVED_LEAVE'
                    WHEN arrangement='EXCUSED_ABSENCE' THEN 'EXCUSED_ABSENCE'
                    WHEN arrangement='NOT_SCHEDULED' THEN 'NOT_SCHEDULED'
                    WHEN arrangement='REMOTE' OR ((arrangement='OFFICE' OR standard_weekday) AND extract(isodow FROM day) IN (2,4))
                        THEN 'REMOTE_WORK'
                    WHEN absence_at <= :now OR (standard_weekday AND day < :today) THEN 'ABSENT'
                    ELSE 'NOT_YET_SIGNED_IN'
                END AS status
                FROM combined c
            ), ranked AS (
                SELECT *, dense_rank() OVER (ORDER BY day DESC) AS day_rank
                FROM classified WHERE :status='' OR status=:status
            ), counted AS (
                SELECT *, max(day_rank) OVER () AS total_days FROM ranked
            )
            SELECT *, EXISTS (SELECT 1 FROM public.attendance_corrections a WHERE a.attendance_record_id=counted.record_id) AS corrected
            FROM counted WHERE day_rank>:skip AND day_rank<=:through
            ORDER BY day DESC, lower(full_name), employee_number, employee_id
            LIMIT :limit
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final WorkdayResolver workdays;

    private final LocalDate policyFrom;

    public DailyAttendanceService(NamedParameterJdbcTemplate jdbc, WorkdayResolver workdays,
            @Value("${attendance.reporting.weekday-policy-from:2026-11-25}") LocalDate policyFrom) {
        this.jdbc = jdbc;
        this.workdays = workdays;
        this.policyFrom = policyFrom;
    }

    @Transactional(readOnly = true)
    public DailyAttendanceReport read(Filter filter, int page, int size, boolean export) {
        Instant now = workdays.now();
        LocalDate today = now.atZone(workdays.zone()).toLocalDate();
        LocalDate end = filter.to() == null ? (filter.date() == null ? today : filter.date()) : filter.to();
        LocalDate start = filter.from() == null ? (filter.date() == null ? end.minusDays(30) : filter.date()) : filter.from();
        if (start.isAfter(end) || ChronoUnit.DAYS.between(start, end) >= MAX_RANGE_DAYS) {
            throw new BadRequestException("Choose a date range of 366 days or fewer with the start before the end");
        }
        if (filter.date() != null) {
            if (filter.date().isBefore(start) || filter.date().isAfter(end)) {
                throw new BadRequestException("The specific working day must be within the date range");
            }
            start = filter.date();
            end = filter.date();
        }
        int maxRows = export ? MAX_EXPORT_ROWS : MAX_PAGE_ROWS;
        long skip = export ? 0 : (long) page * size;
        var params = new MapSqlParameterSource()
                .addValue("from",start).addValue("to",end).addValue("today",today)
                .addValue("policyFrom",policyFrom).addValue("zone",workdays.zoneId())
                .addValue("now",now.atOffset(java.time.ZoneOffset.UTC))
                .addValue("employee",filter.employeeId()).addValue("search",normalized(filter.search()))
                .addValue("subsidiary",normalized(filter.subsidiary())).addValue("department",normalized(filter.department()))
                .addValue("status",filter.status() == null ? "" : filter.status().name())
                .addValue("skip",skip).addValue("through",export ? MAX_RANGE_DAYS : skip + size)
                .addValue("limit",maxRows + 1);
        Map<LocalDate, List<Row>> groups = new LinkedHashMap<>();
        int totalDays = jdbc.query(QUERY,params,rs -> {
            int count = 0;
            int dates = 0;
            while (rs.next()) {
                if (++count > maxRows) throw new BadRequestException("Too many attendance rows; narrow the report filters");
                dates = rs.getInt("total_days");
                groups.computeIfAbsent(rs.getObject("day", LocalDate.class), key -> new ArrayList<>()).add(mapRow(rs));
            }
            return dates;
        });
        List<Day> days = groups.entrySet().stream()
                .map(group -> new Day(group.getKey(), List.copyOf(group.getValue()), Summary.day(group.getValue()))).toList();
        return new DailyAttendanceReport(start,end,now,workdays.zoneId(),page,totalDays,days,Summary.of(days));
    }

    private static Row mapRow(ResultSet rs) throws SQLException {
        Instant signIn = instant(rs,"sign_in_at");
        Instant signOut = instant(rs,"sign_out_at");
        boolean corrected = rs.getBoolean("corrected");
        boolean late = rs.getBoolean("late");
        List<String> remarks = new ArrayList<>();
        if (rs.getString("remarks") != null && !rs.getString("remarks").isBlank()) remarks.add(rs.getString("remarks"));
        if (corrected) remarks.add("Corrected");
        if (signIn == null && ("OFFICE".equals(rs.getString("arrangement")) || rs.getBoolean("standard_weekday"))
                && "REMOTE_WORK".equals(rs.getString("status"))) remarks.add("Tuesday/Thursday remote work policy");
        if (rs.getBoolean("standard_weekday")) remarks.add("Standard weekday policy");
        else if (rs.getString("arrangement") == null) remarks.add("Schedule unverified");
        if (late && !"LATE".equals(rs.getString("status"))) remarks.add("Late arrival");
        return new Row(rs.getObject("record_id",UUID.class),rs.getObject("employee_id",UUID.class),
                rs.getString("employee_number"),rs.getString("full_name"),rs.getString("subsidiary"),rs.getString("department"),
                signIn,signOut,signIn != null && signOut != null ? Duration.between(signIn,signOut).toMinutes() : null,
                Status.valueOf(rs.getString("status")),String.join(" · ",remarks),rs.getBoolean("expected"),late,corrected,rs.getInt("expected_office_days"));
    }

    private static Instant instant(ResultSet rs, String name) throws SQLException {
        OffsetDateTime value = rs.getObject(name,OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }
}
