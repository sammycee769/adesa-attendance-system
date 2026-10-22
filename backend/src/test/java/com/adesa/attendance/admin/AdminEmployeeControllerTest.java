package com.adesa.attendance.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * Integration tests for the admin employee endpoints: role-based authorization
 * (server-side), validation, conflicts and audit persistence against a real
 * PostgreSQL database.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AdminEmployeeControllerTest {

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

    /**
     * A real ADMIN employee row, because audit entries reference employees(id)
     * via a foreign key — the acting administrator always exists in production.
     */
    private static UUID adminActorId;

    @BeforeEach
    void ensureAdminActorExists() {
        if (adminActorId == null) {
            adminActorId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO employees (id, employee_number, full_name, email, password_hash, role, is_active) "
                            + "VALUES (?, 'TEST-ACTOR', 'Test Actor', 'test.actor@adesa.test', 'x', 'ADMIN', true)",
                    adminActorId);
        }
    }

    private static Authentication asRole(EmployeeRole role) {
        AuthenticatedEmployee principal = new AuthenticatedEmployee(
                UUID.randomUUID(), "actor@adesa.test", "Actor", role);
        return new TestingAuthenticationToken(principal, null, "ROLE_" + role.name());
    }

    private static Authentication asAdmin() {
        AuthenticatedEmployee principal = new AuthenticatedEmployee(
                adminActorId, "test.actor@adesa.test", "Test Actor", EmployeeRole.ADMIN);
        return new TestingAuthenticationToken(principal, null, "ROLE_ADMIN");
    }

    private UUID createEmployee(String number, String email, EmployeeRole role) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/employees")
                        .with(authentication(asAdmin()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "employeeNumber", number,
                                "fullName", "Test Person",
                                "email", email,
                                "password", "a-very-strong-pass1",
                                "role", role.name()))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return UUID.fromString(body.get("id").asText());
    }

    @Test
    @DisplayName("Anonymous access to admin endpoints is rejected")
    void anonymousIsRejected() throws Exception {
        // An unauthenticated caller gets 401 (JSON from the entry point), which
        // is distinct from 403: authenticated but unauthorized.
        mockMvc.perform(get("/api/v1/admin/employees"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/admin/employees")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("EMPLOYEE role cannot access admin endpoints")
    void employeeRoleIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/admin/employees").with(authentication(asRole(EmployeeRole.EMPLOYEE))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("HR may read the roster but gets no account-management powers")
    void hrRoleIsReadOnly() throws Exception {
        // HR needs the roster to review attendance (master prompt, Phase 7).
        mockMvc.perform(get("/api/v1/admin/employees").with(authentication(asRole(EmployeeRole.HR))))
                .andExpect(status().isOk());

        // Creating and changing accounts stay ADMIN-only.
        mockMvc.perform(post("/api/v1/admin/employees")
                        .with(authentication(asRole(EmployeeRole.HR)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "employeeNumber", "HR-TRY-1",
                                "fullName", "Should Not Work",
                                "email", "should.not.work@adesa.test",
                                "password", "a-very-strong-pass1",
                                "role", "EMPLOYEE"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/v1/admin/employees/" + UUID.randomUUID())
                        .with(authentication(asRole(EmployeeRole.HR)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isActive\":false}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADMIN can create an employee; response never contains password material")
    void adminCreatesEmployee() throws Exception {
        mockMvc.perform(post("/api/v1/admin/employees")
                        .with(authentication(asAdmin()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "employeeNumber", "EMP-100",
                                "fullName", "Ada Obi",
                                "email", "Ada.Obi@Adesa.test",
                                "password", "a-very-strong-pass1",
                                "role", "EMPLOYEE"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.employeeNumber").value("EMP-100"))
                .andExpect(jsonPath("$.email").value("ada.obi@adesa.test"))
                .andExpect(jsonPath("$.isActive").value(true))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    @DisplayName("Duplicate employee number or email returns 409")
    void duplicateReturnsConflict() throws Exception {
        createEmployee("EMP-101", "one@adesa.test", EmployeeRole.EMPLOYEE);

        mockMvc.perform(post("/api/v1/admin/employees")
                        .with(authentication(asAdmin()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "employeeNumber", "EMP-101",
                                "fullName", "Copy",
                                "email", "two@adesa.test",
                                "password", "a-very-strong-pass1",
                                "role", "EMPLOYEE"))))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/admin/employees")
                        .with(authentication(asAdmin()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "employeeNumber", "EMP-102",
                                "fullName", "Copy",
                                "email", "ONE@adesa.test",
                                "password", "a-very-strong-pass1",
                                "role", "EMPLOYEE"))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Validation failures return 400 with field errors")
    void validationFailuresReturnFieldErrors() throws Exception {
        String tooLongPassword = "A1".repeat(65);
        mockMvc.perform(post("/api/v1/admin/employees")
                        .with(authentication(asAdmin()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "employeeNumber", " ",
                                "fullName", "Bad",
                                "email", "not-an-email",
                                "password", tooLongPassword,
                                "role", "EMPLOYEE"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'email')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'password')]").exists());
    }

    @Test
    @DisplayName("Unknown employee id returns 404")
    void unknownEmployeeReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/admin/employees/" + UUID.randomUUID())
                        .with(authentication(asAdmin())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("ADMIN can deactivate, re-role and reset a password; changes are audited")
    void adminUpdatesEmployeeAndAudits() throws Exception {
        UUID id = createEmployee("EMP-103", "three@adesa.test", EmployeeRole.EMPLOYEE);

        mockMvc.perform(patch("/api/v1/admin/employees/" + id)
                        .with(authentication(asAdmin()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"HR\",\"isActive\":false,\"password\":\"another-strong-pass1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("HR"))
                .andExpect(jsonPath("$.isActive").value(false));

        List<Map<String, Object>> actions = jdbcTemplate.queryForList(
                "SELECT action FROM audit_logs WHERE target_id = ? ORDER BY created_at", id);
        assertThat(actions).extracting(row -> row.get("action").toString())
                .contains("EMPLOYEE_UPDATED", "EMPLOYEE_ROLE_CHANGED", "EMPLOYEE_DEACTIVATED",
                        "EMPLOYEE_PASSWORD_RESET");

        // The stored password hash must have changed and never equal the plaintext.
        String hash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM employees WHERE id = ?", String.class, id);
        assertThat(hash).isNotBlank().isNotEqualTo("another-strong-pass1");
    }

    @Test
    @DisplayName("List supports filters and returns ISO timestamps")
    void listSupportsFilters() throws Exception {
        createEmployee("EMP-104", "four@adesa.test", EmployeeRole.EMPLOYEE);
        createEmployee("EMP-105", "five@adesa.test", EmployeeRole.HR);

        MvcResult result = mockMvc.perform(get("/api/v1/admin/employees")
                        .param("role", "HR")
                        .param("search", "five")
                        .with(authentication(asAdmin())))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body).hasSize(1);
        assertThat(body.get(0).get("role").asText()).isEqualTo("HR");
        assertThat(body.get(0).get("createdAt").asText()).isNotBlank();

        mockMvc.perform(get("/api/v1/admin/employees")
                        .param("search", "five")
                        .with(authentication(asAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("An administrator cannot be deactivated through the normal workflow")
    void administratorCannotBeDeactivatedThroughUpdate() throws Exception {
        UUID adminId = createEmployee("ADM-200", "second.admin@adesa.test", EmployeeRole.ADMIN);

        mockMvc.perform(patch("/api/v1/admin/employees/" + adminId)
                        .with(authentication(asAdmin()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isActive\":false}"))
                .andExpect(status().isConflict());

        Boolean active = jdbcTemplate.queryForObject(
                "SELECT is_active FROM employees WHERE id = ?", Boolean.class, adminId);
        assertThat(active).isTrue();

        // The rejected attempt is audited.
        List<Map<String, Object>> actions = jdbcTemplate.queryForList(
                "SELECT action FROM audit_logs WHERE target_id = ?", adminId);
        assertThat(actions).extracting(row -> row.get("action").toString())
                .contains("EMPLOYEE_ACTIVATION_REJECTED");
    }

    @Test
    @DisplayName("An authorized administrator can reactivate a deactivated administrator account")
    void administratorCanBeReactivated() throws Exception {
        UUID adminId = createEmployee("ADM-201", "back.admin@adesa.test", EmployeeRole.ADMIN);
        // Deactivate out of band (the API refuses): simulates a legacy/incident state.
        jdbcTemplate.update("UPDATE employees SET is_active = false WHERE id = ?", adminId);

        mockMvc.perform(post("/api/v1/admin/employees/" + adminId + "/reactivate")
                        .with(authentication(asAdmin()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isActive").value(true));

        Boolean active = jdbcTemplate.queryForObject(
                "SELECT is_active FROM employees WHERE id = ?", Boolean.class, adminId);
        assertThat(active).isTrue();
        List<Map<String, Object>> actions = jdbcTemplate.queryForList(
                "SELECT action FROM audit_logs WHERE target_id = ?", adminId);
        assertThat(actions).extracting(row -> row.get("action").toString())
                .contains("EMPLOYEE_REACTIVATED");
    }

    @Test
    @DisplayName("Reactivation is refused for a non-administrator caller (HR/EMPLOYEE)")
    void reactivationRequiresAdminRole() throws Exception {
        UUID adminId = createEmployee("ADM-202", "locked.admin@adesa.test", EmployeeRole.ADMIN);
        jdbcTemplate.update("UPDATE employees SET is_active = false WHERE id = ?", adminId);

        mockMvc.perform(post("/api/v1/admin/employees/" + adminId + "/reactivate")
                        .with(authentication(asRole(EmployeeRole.HR)))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/admin/employees/" + adminId + "/reactivate")
                        .with(authentication(asRole(EmployeeRole.EMPLOYEE)))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        Boolean active = jdbcTemplate.queryForObject(
                "SELECT is_active FROM employees WHERE id = ?", Boolean.class, adminId);
        assertThat(active).isFalse();
    }

    @Test
    @DisplayName("Reactivating an already active account is a conflict")
    void reactivatingAnActiveAccountIsAConflict() throws Exception {
        UUID id = createEmployee("EMP-106", "six@adesa.test", EmployeeRole.EMPLOYEE);

        mockMvc.perform(post("/api/v1/admin/employees/" + id + "/reactivate")
                        .with(authentication(asAdmin()))
                        .with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Ordinary employee deactivation still works through the update endpoint")
    void ordinaryEmployeeDeactivationStillWorks() throws Exception {
        UUID id = createEmployee("EMP-107", "seven@adesa.test", EmployeeRole.EMPLOYEE);

        mockMvc.perform(patch("/api/v1/admin/employees/" + id)
                        .with(authentication(asAdmin()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isActive\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isActive").value(false));
    }
}
