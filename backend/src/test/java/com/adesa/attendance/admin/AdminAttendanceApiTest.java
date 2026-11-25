package com.adesa.attendance.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.adesa.attendance.employee.EmployeeRole;
import com.adesa.attendance.security.AuthenticatedEmployee;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Phase 7: the HR and admin attendance interfaces.
 *
 * <p>Covers the role separation (HR may report and correct, but gets no
 * system-administration powers), the mandatory correction reason, the
 * transactional correction with its audit trail, the CSV export and the
 * restricted HR view of the audit log.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AdminAttendanceApiTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("adesa_attendance")
            .withUsername("adesa_app")
            .withPassword("test_password");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static UUID adminActorId;
    private static UUID hrActorId;

    @BeforeEach
    void ensureActorsExist() {
        if (adminActorId == null) {
            adminActorId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO employees (id, employee_number, full_name, email, password_hash, role, is_active) "
                            + "VALUES (?, 'TEST-ADMIN', 'Test Admin', 'test.admin@adesa.test', 'x', 'ADMIN', true)",
                    adminActorId);
        }
        if (hrActorId == null) {
            hrActorId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO employees (id, employee_number, full_name, email, password_hash, role, is_active) "
                            + "VALUES (?, 'TEST-HR', 'Test Hr', 'test.hr@adesa.test', 'x', 'HR', true)",
                    hrActorId);
        }
    }

    private static Authentication asRole(EmployeeRole role) {
        AuthenticatedEmployee principal = new AuthenticatedEmployee(
                UUID.randomUUID(), "actor@adesa.test", "Actor", role);
        return new TestingAuthenticationToken(principal, null, "ROLE_" + role.name());
    }

    private static Authentication asAdmin() {
        return new TestingAuthenticationToken(
                new AuthenticatedEmployee(adminActorId, "test.admin@adesa.test", "Test Admin", EmployeeRole.ADMIN),
                null, "ROLE_ADMIN");
    }

    private static Authentication asHr() {
        return new TestingAuthenticationToken(
                new AuthenticatedEmployee(hrActorId, "test.hr@adesa.test", "Test Hr", EmployeeRole.HR),
                null, "ROLE_HR");
    }

    /** Creates an employee row and returns its id. */
    private UUID createEmployee(String number, String name, String email, EmployeeRole role) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO employees (id, employee_number, full_name, email, password_hash, role, is_active) "
                        + "VALUES (?, ?, ?, ?, 'x', ?, true)",
                id, number, name, email, role.name());
        return id;
    }

    /** Creates an attendance record and returns its id. */
    private UUID createRecord(UUID employeeId, LocalDate date, Instant signIn, Instant signOut) {
        UUID id = UUID.randomUUID();
        // The driver cannot infer the SQL type of an Instant (or of a null
        // timestamp), so the cast tells PostgreSQL what the column is and the
        // value is passed as an OffsetDateTime.
        jdbcTemplate.update(
                "INSERT INTO attendance_records (id, employee_id, attendance_date, sign_in_at, sign_out_at) "
                        + "VALUES (?, ?, ?, ?::timestamptz, ?::timestamptz)",
                id, employeeId, date, timestamp(signIn), timestamp(signOut));
        return id;
    }

    private static Object timestamp(Instant instant) {
        return instant == null
                ? new org.springframework.jdbc.core.SqlParameterValue(java.sql.Types.TIMESTAMP, null)
                : java.time.OffsetDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
    }

    @Test
    @DisplayName("Anonymous access to the reporting endpoints is rejected")
    void anonymousIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/admin/attendance")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/attendance/overview")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/attendance/export")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/audit-logs")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("EMPLOYEE role cannot access the reporting endpoints")
    void employeeIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/admin/attendance").with(authentication(asRole(EmployeeRole.EMPLOYEE))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/attendance/overview").with(authentication(asRole(EmployeeRole.EMPLOYEE))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/attendance/export").with(authentication(asRole(EmployeeRole.EMPLOYEE))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/audit-logs").with(authentication(asRole(EmployeeRole.EMPLOYEE))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("HR and ADMIN can both list attendance records")
    void hrAndAdminCanList() throws Exception {
        UUID employeeId = createEmployee("RPT-001", "Ada Obi", "ada.obi@adesa.test", EmployeeRole.EMPLOYEE);
        createRecord(employeeId, LocalDate.of(2026, 3, 2),
                Instant.parse("2026-03-02T08:00:00Z"), Instant.parse("2026-03-02T17:00:00Z"));

        for (Authentication actor : List.of(asHr(), asAdmin())) {
            mockMvc.perform(get("/api/v1/admin/attendance")
                            .with(authentication(actor)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.records[?(@.employeeNumber == 'RPT-001')]").exists())
                    .andExpect(jsonPath("$.records[?(@.employeeNumber == 'RPT-001')].state").value("SIGNED_OUT"))
                    .andExpect(jsonPath("$.records[?(@.employeeNumber == 'RPT-001')].employeeName")
                            .value("Ada Obi"));
        }
    }

    @Test
    @DisplayName("The report can be filtered by workday and by search term")
    void reportFilters() throws Exception {
        UUID employeeId = createEmployee("RPT-002", "Bayo Cole", "bayo.cole@adesa.test", EmployeeRole.EMPLOYEE);
        createRecord(employeeId, LocalDate.of(2026, 3, 2), Instant.parse("2026-03-02T08:00:00Z"), null);
        createRecord(employeeId, LocalDate.of(2026, 3, 3), Instant.parse("2026-03-03T08:00:00Z"),
                Instant.parse("2026-03-03T17:00:00Z"));

        mockMvc.perform(get("/api/v1/admin/attendance")
                        .param("from", "2026-03-03")
                        .with(authentication(asAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.records[0].attendanceDate").value("2026-03-03"));

        mockMvc.perform(get("/api/v1/admin/attendance")
                        .param("search", "bayo")
                        .with(authentication(asAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/api/v1/admin/attendance")
                        .param("status", "SIGNED_IN")
                        .with(authentication(asAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("A correction without a reason is rejected")
    void correctionWithoutReasonIsRejected() throws Exception {
        UUID employeeId = createEmployee("RPT-003", "Cora Nwosu", "cora.nwosu@adesa.test", EmployeeRole.EMPLOYEE);
        UUID recordId = createRecord(employeeId, LocalDate.of(2026, 3, 4),
                Instant.parse("2026-03-04T08:00:00Z"), null);

        mockMvc.perform(patch("/api/v1/admin/attendance/" + recordId)
                        .with(authentication(asAdmin()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"signInAt\":\"2026-03-04T08:30:00Z\",\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest());

        // The record must be untouched.
        Instant stored = jdbcTemplate.queryForObject(
                "SELECT sign_in_at FROM attendance_records WHERE id = ?", java.sql.Timestamp.class, recordId)
                .toInstant();
        assertThat(stored).isEqualTo(Instant.parse("2026-03-04T08:00:00Z"));
    }

    @Test
    @DisplayName("A correction with a reason updates the record, the history and the audit log")
    void correctionIsAppliedAndAudited() throws Exception {
        UUID employeeId = createEmployee("RPT-004", "Dee Adeyemi", "dee.adeyemi@adesa.test", EmployeeRole.EMPLOYEE);
        UUID recordId = createRecord(employeeId, LocalDate.of(2026, 3, 5),
                Instant.parse("2026-03-05T08:00:00Z"), null);

        mockMvc.perform(patch("/api/v1/admin/attendance/" + recordId)
                        .with(authentication(asAdmin()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"signInAt\":\"2026-03-05T07:45:00Z\","
                                + "\"signOutAt\":\"2026-03-05T16:30:00Z\","
                                + "\"reason\":\"The badge reader was offline in the morning\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signInAt").value("2026-03-05T07:45:00Z"))
                .andExpect(jsonPath("$.signOutAt").value("2026-03-05T16:30:00Z"))
                .andExpect(jsonPath("$.corrected").value(true))
                .andExpect(jsonPath("$.correctionCount").value(1));

        // The corrected values are stored ...
        Map<String, Object> stored = jdbcTemplate.queryForMap(
                "SELECT sign_in_at, sign_out_at FROM attendance_records WHERE id = ?", recordId);
        assertThat(((java.sql.Timestamp) stored.get("sign_in_at")).toInstant())
                .isEqualTo(Instant.parse("2026-03-05T07:45:00Z"));

        // ... the previous values and the reason are preserved ...
        List<Map<String, Object>> corrections = jdbcTemplate.queryForList(
                "SELECT previous_sign_in_at, corrected_sign_in_at, reason, actor_id "
                        + "FROM attendance_corrections WHERE attendance_record_id = ?", recordId);
        assertThat(corrections).hasSize(1);
        assertThat(((java.sql.Timestamp) corrections.get(0).get("previous_sign_in_at")).toInstant())
                .isEqualTo(Instant.parse("2026-03-05T08:00:00Z"));
        assertThat(corrections.get(0).get("reason").toString())
                .isEqualTo("The badge reader was offline in the morning");
        assertThat(corrections.get(0).get("actor_id")).isEqualTo(adminActorId);

        // ... and the audit log carries the actor, the reason and both values.
        List<Map<String, Object>> audit = jdbcTemplate.queryForList(
                "SELECT action, actor_id, details FROM audit_logs WHERE target_id = ? AND target_type = ?",
                recordId, "ATTENDANCE_RECORD");
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).get("action").toString()).isEqualTo("ATTENDANCE_CORRECTED");
        assertThat(audit.get(0).get("actor_id")).isEqualTo(adminActorId);
        String details = audit.get(0).get("details").toString();
        assertThat(details).contains("badge reader was offline")
                .contains("2026-03-05T08:00:00Z")
                .contains("2026-03-05T07:45:00Z");
    }

    @Test
    @DisplayName("HR may correct a record, and the correction is attributed to the HR officer")
    void hrMayCorrect() throws Exception {
        UUID employeeId = createEmployee("RPT-005", "Eve James", "eve.james@adesa.test", EmployeeRole.EMPLOYEE);
        UUID recordId = createRecord(employeeId, LocalDate.of(2026, 3, 6),
                Instant.parse("2026-03-06T08:00:00Z"), null);

        mockMvc.perform(patch("/api/v1/admin/attendance/" + recordId)
                        .with(authentication(asHr()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"signInAt\":\"2026-03-06T08:20:00Z\","
                                + "\"reason\":\"Approved late arrival by the floor manager\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signInAt").value("2026-03-06T08:20:00Z"));

        String actor = jdbcTemplate.queryForObject(
                "SELECT actor_id FROM attendance_corrections WHERE attendance_record_id = ?",
                String.class, recordId);
        assertThat(actor).isEqualTo(hrActorId.toString());
    }

    @Test
    @DisplayName("A sign-out before the sign-in is rejected")
    void correctionWithInvertedTimesIsRejected() throws Exception {
        UUID employeeId = createEmployee("RPT-006", "Femi Balogun", "femi.balogun@adesa.test", EmployeeRole.EMPLOYEE);
        UUID recordId = createRecord(employeeId, LocalDate.of(2026, 3, 7),
                Instant.parse("2026-03-07T08:00:00Z"), null);

        mockMvc.perform(patch("/api/v1/admin/attendance/" + recordId)
                        .with(authentication(asAdmin()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"signInAt\":\"2026-03-07T08:00:00Z\","
                                + "\"signOutAt\":\"2026-03-07T07:00:00Z\","
                                + "\"reason\":\"Inverted times on purpose\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Correcting an unknown record returns 404")
    void correctionOfUnknownRecordReturns404() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/attendance/" + UUID.randomUUID())
                        .with(authentication(asAdmin()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"signInAt\":\"2026-03-08T08:00:00Z\",\"reason\":\"A valid reason here\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("The PDF export returns a downloadable PDF")
    void exportReturnsPdf() throws Exception {
        UUID employeeId = createEmployee("RPT-007", "Gita Mensah", "gita.mensah@adesa.test", EmployeeRole.EMPLOYEE);
        createRecord(employeeId, LocalDate.of(2026, 3, 9), Instant.parse("2026-03-09T08:00:00Z"), null);

        MvcResult result = mockMvc.perform(get("/api/v1/admin/attendance/export")
                        .param("from", "2026-03-09")
                        .param("to", "2026-03-09")
                        .with(authentication(asHr())))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/pdf"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(".pdf")))
                .andReturn();

        byte[] pdf = result.getResponse().getContentAsByteArray();
        // A PDF always starts with the %PDF- signature.
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void csvExportIsAuthorizedAndContainsAttendanceRows() throws Exception {
        UUID employeeId = createEmployee("CSV-001", "CSV Employee", "csv@adesa.test", EmployeeRole.EMPLOYEE);
        createRecord(employeeId, LocalDate.of(2026, 3, 9), Instant.parse("2026-03-09T08:00:00Z"), null);
        mockMvc.perform(get("/api/v1/admin/attendance/export.csv"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/attendance/export.csv")
                        .with(authentication(asRole(EmployeeRole.EMPLOYEE))))
                .andExpect(status().isForbidden());
        var response = mockMvc.perform(get("/api/v1/admin/attendance/export.csv")
                        .param("from","2026-03-09").param("to","2026-03-09")
                        .with(authentication(asHr())))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andReturn().getResponse();
        assertThat(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("Monday, 9 March 2026", "Employee number", "CSV-001", "CSV Employee", "Overall attendance summary");
    }

    @Test
    void groupedAttendanceUsesVerifiedEmploymentAndRosterForMissingClockIns() throws Exception {
        var history = LocalDate.of(2026,1,12);
        var today = LocalDate.now(java.time.ZoneId.of("Africa/Lagos"));
        boolean remoteDay = today.getDayOfWeek() == java.time.DayOfWeek.TUESDAY
                || today.getDayOfWeek() == java.time.DayOfWeek.THURSDAY;
        UUID subsidiary = UUID.randomUUID();
        jdbcTemplate.update("insert into subsidiaries(id,name,active,created_at,updated_at) values (?,'Roster Subsidiary',true,now(),now())",subsidiary);
        UUID signed = createEmployee("DAY-01","Roster A Signed","roster.a@adesa.test",EmployeeRole.EMPLOYEE);
        UUID late = createEmployee("DAY-02","Roster B Late","roster.b@adesa.test",EmployeeRole.EMPLOYEE);
        UUID incomplete = createEmployee("DAY-03","Roster C Incomplete","roster.c@adesa.test",EmployeeRole.EMPLOYEE);
        UUID absent = createEmployee("DAY-04","Roster D Absent","roster.d@adesa.test",EmployeeRole.EMPLOYEE);
        UUID leave = createEmployee("DAY-05","Roster E Leave","roster.e@adesa.test",EmployeeRole.EMPLOYEE);
        UUID excused = createEmployee("DAY-06","Roster F Excused","roster.f@adesa.test",EmployeeRole.EMPLOYEE);
        UUID remote = createEmployee("DAY-07","Roster G Remote","roster.g@adesa.test",EmployeeRole.EMPLOYEE);
        UUID unscheduled = createEmployee("DAY-08","Roster H Not Scheduled","roster.h@adesa.test",EmployeeRole.EMPLOYEE);
        UUID newHire = createEmployee("DAY-09","Roster New Hire","roster.new@adesa.test",EmployeeRole.EMPLOYEE);
        UUID former = createEmployee("DAY-10","Roster Former Staff","roster.former@adesa.test",EmployeeRole.EMPLOYEE);
        jdbcTemplate.update("update employees set is_active=false where id=?",former);
        for (UUID staff : List.of(signed,late,incomplete,absent,former)) {
            roster(staff,subsidiary,history,"OFFICE",history.minusDays(3),staff.equals(former) ? history : null);
        }
        roster(leave,subsidiary,history,"APPROVED_LEAVE",history,null);
        roster(excused,subsidiary,history,"EXCUSED_ABSENCE",history,null);
        roster(remote,subsidiary,history,"REMOTE",history,null);
        roster(unscheduled,subsidiary,history,"NOT_SCHEDULED",history,null);
        roster(unscheduled,subsidiary,history.plusDays(1),"NOT_SCHEDULED",history,null); // public holiday
        roster(unscheduled,subsidiary,history.plusDays(5),"NOT_SCHEDULED",history,null); // weekend
        roster(newHire,subsidiary,history.plusDays(2),"OFFICE",history.plusDays(2),null);
        roster(signed,subsidiary,history.plusDays(3),"OFFICE",history.minusDays(3),null);
        roster(absent,subsidiary,history.plusDays(3),"OFFICE",history.minusDays(3),null);
        roster(absent,subsidiary,history.plusDays(1),"OFFICE",history.minusDays(3),null);
        createRecord(signed,history.plusDays(3),at(history.plusDays(3),7),at(history.plusDays(3),16));
        Instant upcoming = Instant.now().plusSeconds(600);
        jdbcTemplate.update("""
                insert into attendance_private.daily_roster(workday,employee_id,employment_start,subsidiary_id,department,
                    arrangement,absence_at,shift_end,source_reference)
                values (?,?,?,?,'Operations','OFFICE',?,?,'Approved test roster')
                """,today,absent,history,subsidiary,java.sql.Timestamp.from(upcoming),java.sql.Timestamp.from(upcoming.plusSeconds(600)));
        createRecord(signed,history,at(history,7),at(history,15));
        createRecord(late,history,at(history,9),at(history,15));
        UUID open = createRecord(incomplete,history,at(history,8),null);

        String url = "/api/v1/admin/attendance/days";
        mockMvc.perform(get(url)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(url).with(authentication(asRole(EmployeeRole.EMPLOYEE)))).andExpect(status().isForbidden());
        var query = get(url).param("from",history.toString()).param("to",history.plusDays(6).toString())
                .param("subsidiary","Roster Subsidiary").param("department","Operations");
        mockMvc.perform(query.with(authentication(asHr())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalDays").value(4))
                .andExpect(jsonPath("$.days[0].summary.present").value(1))
                .andExpect(jsonPath("$.days[0].employees[?(@.status == 'REMOTE_WORK')].employeeNumber").value(org.hamcrest.Matchers.contains("DAY-04")))
                .andExpect(jsonPath("$.days[2].employees[?(@.status == 'REMOTE_WORK')].employeeNumber").value(org.hamcrest.Matchers.contains("DAY-04")))
                .andExpect(jsonPath("$.days[1].summary.absent").value(1))
                .andExpect(jsonPath("$.days[3].employees.length()").value(9))
                .andExpect(jsonPath("$.days[3].summary.present").value(3))
                .andExpect(jsonPath("$.days[3].summary.expected").value(6))
                .andExpect(jsonPath("$.days[3].summary.absent").value(2))
                .andExpect(jsonPath("$.days[3].summary.signedOut").value(2))
                .andExpect(jsonPath("$.days[3].summary.late").value(1))
                .andExpect(jsonPath("$.days[3].summary.incomplete").value(1))
                .andExpect(jsonPath("$.days[3].summary.approvedAbsences").value(2))
                .andExpect(jsonPath("$.days[3].employees[?(@.status == 'REMOTE_WORK')]").isNotEmpty())
                .andExpect(jsonPath("$.days[3].employees[?(@.status == 'EXCUSED_ABSENCE')]").isNotEmpty())
                .andExpect(jsonPath("$.days[3].employees[?(@.status == 'ON_APPROVED_LEAVE')]").isNotEmpty())
                .andExpect(jsonPath("$.days[3].employees[?(@.status == 'NOT_SCHEDULED')]").isNotEmpty());

        mockMvc.perform(get(url).param("date",today.toString()).param("status",remoteDay ? "REMOTE_WORK" : "NOT_YET_SIGNED_IN").with(authentication(asHr())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.days[0].employees[0].employeeNumber").value("DAY-04"));
        mockMvc.perform(get(url).param("from",history.toString()).param("to",history.plusDays(6).toString())
                        .param("search","Roster").param("page","1").param("size","1").with(authentication(asHr())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.days.length()").value(1))
                .andExpect(jsonPath("$.days[0].date").value(history.plusDays(2).toString()));

        mockMvc.perform(patch("/api/v1/admin/attendance/{id}",open).with(authentication(asHr())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of(
                        "signInAt",at(history,8).toString(),"signOutAt",at(history,16).toString(),"reason","Verified sign-out from supervisor"))))
                .andExpect(status().isOk());
        mockMvc.perform(get(url).param("date",history.toString()).with(authentication(asHr())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.days[0].summary.incomplete").value(0))
                .andExpect(jsonPath("$.days[0].summary.signedOut").value(3))
                .andExpect(jsonPath("$.days[0].employees[?(@.corrected == true)]").isNotEmpty());
        byte[] pdf = mockMvc.perform(get("/api/v1/admin/attendance/export").param("date",history.toString())
                        .param("status","ABSENT").with(authentication(asHr())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var reader = new com.lowagie.text.pdf.PdfReader(pdf);
        try {
            String extracted = new com.lowagie.text.pdf.parser.PdfTextExtractor(reader).getTextFromPage(1);
            assertThat(extracted).contains("Monday, 12 January 2026","Roster D Absent","Roster Former Staff","Absent 2")
                    .doesNotContain("Roster A Signed");
        } finally { reader.close(); }
    }

    private void roster(UUID employee, UUID subsidiary, LocalDate date, String arrangement, LocalDate start, LocalDate end) {
        jdbcTemplate.update("""
                insert into attendance_private.daily_roster(workday, employee_id, employment_start, employment_end,
                    subsidiary_id, department, arrangement, late_after, absence_at, shift_end, source_reference)
                values (?,?,?,?,?,'Operations',?,?,?,?,'Approved test roster')
                """,date,employee,start,end,subsidiary,arrangement,
                java.sql.Timestamp.from(at(date,8)),java.sql.Timestamp.from(at(date,10)),java.sql.Timestamp.from(at(date,16)));
    }

    private static Instant at(LocalDate date, int utcHour) {
        return date.atTime(utcHour,0).toInstant(java.time.ZoneOffset.UTC);
    }

    @Test
    @DisplayName("The daily overview lists every active employee")
    void overviewListsActiveEmployees() throws Exception {
        UUID employeeId = createEmployee("RPT-008", "Hauwa Yusuf", "hauwa.yusuf@adesa.test", EmployeeRole.EMPLOYEE);
        createRecord(employeeId, LocalDate.of(2026, 3, 10), Instant.parse("2026-03-10T08:00:00Z"), null);

        MvcResult result = mockMvc.perform(get("/api/v1/admin/attendance/overview")
                        .param("date", "2026-03-10")
                        .with(authentication(asHr())))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body).isNotEmpty();
        boolean found = false;
        for (JsonNode entry : body) {
            if ("RPT-008".equals(entry.get("employeeNumber").asText())) {
                found = true;
                assertThat(entry.get("state").asText()).isEqualTo("SIGNED_IN");
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    @DisplayName("HR sees the attendance and device audit entries, ADMIN sees every entry")
    void auditVisibilityDependsOnRole() throws Exception {
        // An entry that has nothing to do with attendance: a password reset.
        UUID target = createEmployee("RPT-009", "Ibrahim Sanni", "ibrahim.sanni@adesa.test", EmployeeRole.EMPLOYEE);
        jdbcTemplate.update(
                "INSERT INTO audit_logs (id, actor_id, action, target_type, target_id, details) "
                        + "VALUES (?, ?, 'EMPLOYEE_PASSWORD_RESET', 'EMPLOYEE', ?, '{}'::jsonb)",
                UUID.randomUUID(), adminActorId, target);
        // An attendance entry.
        UUID recordId = createRecord(target, LocalDate.of(2026, 3, 11),
                Instant.parse("2026-03-11T08:00:00Z"), null);
        jdbcTemplate.update(
                "INSERT INTO audit_logs (id, actor_id, action, target_type, target_id, details) "
                        + "VALUES (?, ?, 'ATTENDANCE_SIGNED_IN', 'ATTENDANCE_RECORD', ?, '{}'::jsonb)",
                UUID.randomUUID(), adminActorId, recordId);

        MvcResult hrResult = mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .with(authentication(asHr())))
                .andExpect(status().isOk())
                .andReturn();
        String hrBody = hrResult.getResponse().getContentAsString();
        assertThat(hrBody).contains("ATTENDANCE_SIGNED_IN");
        assertThat(hrBody).doesNotContain("EMPLOYEE_PASSWORD_RESET");

        MvcResult adminResult = mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .with(authentication(asAdmin())))
                .andExpect(status().isOk())
                .andReturn();
        String adminBody = adminResult.getResponse().getContentAsString();
        assertThat(adminBody).contains("ATTENDANCE_SIGNED_IN");
        assertThat(adminBody).contains("EMPLOYEE_PASSWORD_RESET");
    }

    @Test
    @DisplayName("HR cannot create employees, approve devices or read the network diagnostics")
    void hrKeepsNoAdministrationPowers() throws Exception {
        mockMvc.perform(post("/api/v1/admin/employees")
                        .with(authentication(asHr()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "employeeNumber", "HR-TRY-1",
                                "fullName", "Should Not Work",
                                "email", "should.not.work@adesa.test",
                                "password", "a-very-strong-pass",
                                "role", "EMPLOYEE"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/admin/devices/" + UUID.randomUUID() + "/approve")
                        .with(authentication(asHr()))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/admin/network/client-address").with(authentication(asHr())))
                .andExpect(status().isForbidden());
    }
}
