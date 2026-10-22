package com.adesa.attendance.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.adesa.attendance.common.ForbiddenException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The attendance business rules against a real PostgreSQL container.
 *
 * <p>Every attendance action needs a session (the device proof is bound to
 * it), so the tests sign in through the real login endpoint first.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@org.springframework.test.context.ActiveProfiles("dev")
class AttendanceApiTest {

    private static final String PASSWORD = "attendance-password-1";
    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");

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
        // This class exercises the attendance business rules, not the
        // office-network policy, so it runs with the explicit local-development
        // bypass — the same posture as a developer machine. The network policy
        // itself is covered by AttendanceNetworkRestrictionTest,
        // AttendanceTrustedProxyTest and AttendanceNetworkFailClosedTest.
        registry.add("attendance.network.local-development-bypass", () -> "true");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AttendanceService attendanceService;

    private AttendanceTestFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new AttendanceTestFixtures(jdbcTemplate, passwordEncoder);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private MockHttpSession login(UUID employeeId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", fixtures.emailFor(employeeId), "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private ResultActions signIn(MockHttpSession session, UUID proofId) throws Exception {
        return mockMvc.perform(post("/api/v1/attendance/sign-in")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(AttendanceTestFixtures.proofBody(proofId))));
    }

    private ResultActions signOut(MockHttpSession session, UUID proofId) throws Exception {
        return mockMvc.perform(post("/api/v1/attendance/sign-out")
                .session(session)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(AttendanceTestFixtures.proofBody(proofId))));
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    @DisplayName("An employee can sign in and the record is created and audited")
    void signInCreatesTheWorkdayRecord() throws Exception {
        UUID employeeId = fixtures.createEmployee(true, PASSWORD);
        MockHttpSession session = login(employeeId);
        UUID deviceId = fixtures.approveDevice(employeeId);
        UUID proofId = fixtures.issueProof(employeeId, deviceId, session.getId());

        signIn(session, proofId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("SIGNED_IN"))
                .andExpect(jsonPath("$.attendanceDate").value(LocalDate.now(LAGOS).toString()))
                .andExpect(jsonPath("$.timezone").value("Africa/Lagos"))
                .andExpect(jsonPath("$.signInAt").isNotEmpty())
                .andExpect(jsonPath("$.signOutAt").doesNotExist());

        assertThat(fixtures.countRecords(employeeId)).isEqualTo(1);
        assertThat(fixtures.countAudit(employeeId, "ATTENDANCE_SIGNED_IN")).isEqualTo(1);
    }

    @Test
    @DisplayName("A duplicate sign-in is rejected and no second record is created")
    void duplicateSignInRejected() throws Exception {
        UUID employeeId = fixtures.createEmployee(true, PASSWORD);
        MockHttpSession session = login(employeeId);
        UUID deviceId = fixtures.approveDevice(employeeId);

        signIn(session, fixtures.issueProof(employeeId, deviceId, session.getId()))
                .andExpect(status().isCreated());

        signIn(session, fixtures.issueProof(employeeId, deviceId, session.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("You have already signed in for today."));

        assertThat(fixtures.countRecords(employeeId)).isEqualTo(1);
    }

    @Test
    @DisplayName("An employee can sign out after signing in")
    void signOutClosesTheWorkday() throws Exception {
        UUID employeeId = fixtures.createEmployee(true, PASSWORD);
        MockHttpSession session = login(employeeId);
        UUID deviceId = fixtures.approveDevice(employeeId);

        signIn(session, fixtures.issueProof(employeeId, deviceId, session.getId()))
                .andExpect(status().isCreated());

        signOut(session, fixtures.issueProof(employeeId, deviceId, session.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SIGNED_OUT"))
                .andExpect(jsonPath("$.signInAt").isNotEmpty())
                .andExpect(jsonPath("$.signOutAt").isNotEmpty());

        assertThat(fixtures.countAudit(employeeId, "ATTENDANCE_SIGNED_OUT")).isEqualTo(1);
    }

    @Test
    @DisplayName("An employee cannot sign out before signing in")
    void signOutWithoutSignInRejected() throws Exception {
        UUID employeeId = fixtures.createEmployee(true, PASSWORD);
        MockHttpSession session = login(employeeId);
        UUID deviceId = fixtures.approveDevice(employeeId);

        signOut(session, fixtures.issueProof(employeeId, deviceId, session.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("There is no sign-in for today. Sign in first."));

        assertThat(fixtures.countRecords(employeeId)).isZero();
    }

    @Test
    @DisplayName("A duplicate sign-out is rejected")
    void duplicateSignOutRejected() throws Exception {
        UUID employeeId = fixtures.createEmployee(true, PASSWORD);
        MockHttpSession session = login(employeeId);
        UUID deviceId = fixtures.approveDevice(employeeId);

        signIn(session, fixtures.issueProof(employeeId, deviceId, session.getId()))
                .andExpect(status().isCreated());
        signOut(session, fixtures.issueProof(employeeId, deviceId, session.getId()))
                .andExpect(status().isOk());

        signOut(session, fixtures.issueProof(employeeId, deviceId, session.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("You have already signed out for today."));
    }

    @Test
    @DisplayName("Attendance identity comes from the session, never from the request body")
    void attendanceIsBoundToTheSessionEmployee() throws Exception {
        UUID employeeId = fixtures.createEmployee(true, PASSWORD);
        UUID otherEmployeeId = fixtures.createEmployee(true, PASSWORD);
        MockHttpSession session = login(employeeId);
        UUID deviceId = fixtures.approveDevice(employeeId);
        UUID proofId = fixtures.issueProof(employeeId, deviceId, session.getId());

        mockMvc.perform(post("/api/v1/attendance/sign-in")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("deviceProofId", proofId, "employeeId", otherEmployeeId))))
                .andExpect(status().isCreated());

        assertThat(fixtures.countRecords(employeeId)).isEqualTo(1);
        assertThat(fixtures.countRecords(otherEmployeeId)).isZero();
    }

    @Test
    @DisplayName("An attendance action without a valid device proof is refused")
    void invalidDeviceProofRejected() throws Exception {
        UUID employeeId = fixtures.createEmployee(true, PASSWORD);
        MockHttpSession session = login(employeeId);
        fixtures.approveDevice(employeeId);

        signIn(session, UUID.randomUUID())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(
                        "Device authentication is required. Please sign in with your registered device and try again."));

        assertThat(fixtures.countRecords(employeeId)).isZero();
        assertThat(fixtures.countAudit(employeeId, "ATTENDANCE_ACTION_REJECTED")).isEqualTo(1);
    }

    @Test
    @DisplayName("A device proof cannot be replayed")
    void deviceProofCannotBeReplayed() throws Exception {
        UUID employeeId = fixtures.createEmployee(true, PASSWORD);
        MockHttpSession session = login(employeeId);
        UUID deviceId = fixtures.approveDevice(employeeId);
        UUID proofId = fixtures.issueProof(employeeId, deviceId, session.getId());

        signIn(session, proofId).andExpect(status().isCreated());

        // The very same proof must not open a second attendance action.
        signOut(session, proofId)
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("A device proof issued for another session is refused")
    void deviceProofFromAnotherSessionRejected() throws Exception {
        UUID employeeId = fixtures.createEmployee(true, PASSWORD);
        MockHttpSession session = login(employeeId);
        MockHttpSession otherSession = login(employeeId);
        UUID deviceId = fixtures.approveDevice(employeeId);
        // Proof issued to the other session.
        UUID proofId = fixtures.issueProof(employeeId, deviceId, otherSession.getId());

        signIn(session, proofId)
                .andExpect(status().isForbidden());

        assertThat(fixtures.countRecords(employeeId)).isZero();
    }

    @Test
    @DisplayName("A device that is not approved cannot record attendance")
    void unapprovedDeviceCannotRecordAttendance() throws Exception {
        UUID employeeId = fixtures.createEmployee(true, PASSWORD);
        MockHttpSession session = login(employeeId);
        UUID pendingDeviceId = fixtures.createUnapprovedDevice(employeeId, "PENDING_APPROVAL");
        UUID proofId = fixtures.issueProof(employeeId, pendingDeviceId, session.getId());

        signIn(session, proofId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "No approved device is registered for this account. Register a device and wait for "
                                + "administrator approval."));

        assertThat(fixtures.countRecords(employeeId)).isZero();
    }

    @Test
    @DisplayName("An inactive account cannot record attendance")
    void inactiveEmployeeCannotRecordAttendance() {
        UUID employeeId = fixtures.createEmployee(true, PASSWORD);
        UUID deviceId = fixtures.approveDevice(employeeId);
        MockHttpSession session = new MockHttpSession();
        UUID proofId = fixtures.issueProof(employeeId, deviceId, session.getId());
        fixtures.deactivate(employeeId);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> attendanceService.signIn(request, employeeId, proofId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("not active");

        assertThat(fixtures.countRecords(employeeId)).isZero();
    }

    @Test
    @DisplayName("Today's state reflects the workday and tells the dashboard what is allowed")
    void todayReportsTheState() throws Exception {
        UUID employeeId = fixtures.createEmployee(true, PASSWORD);
        MockHttpSession session = login(employeeId);
        UUID deviceId = fixtures.approveDevice(employeeId);

        mockMvc.perform(get("/api/v1/attendance/me/today").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("NOT_SIGNED_IN"))
                .andExpect(jsonPath("$.attendanceDate").value(LocalDate.now(LAGOS).toString()))
                .andExpect(jsonPath("$.canSignIn").value(true))
                .andExpect(jsonPath("$.canSignOut").value(false));

        signIn(session, fixtures.issueProof(employeeId, deviceId, session.getId()))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/attendance/me/today").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SIGNED_IN"))
                .andExpect(jsonPath("$.canSignIn").value(false))
                .andExpect(jsonPath("$.canSignOut").value(true));
    }

    @Test
    @DisplayName("The history contains only the employee's own records")
    void historyIsScopedToTheEmployee() throws Exception {
        UUID employeeId = fixtures.createEmployee(true, PASSWORD);
        UUID otherEmployeeId = fixtures.createEmployee(true, PASSWORD);
        MockHttpSession session = login(employeeId);
        UUID deviceId = fixtures.approveDevice(employeeId);

        signIn(session, fixtures.issueProof(employeeId, deviceId, session.getId()))
                .andExpect(status().isCreated());

        // A record of another employee must not show up.
        jdbcTemplate.update(
                "INSERT INTO attendance_records (id, employee_id, attendance_date, sign_in_at, created_at, updated_at) "
                        + "VALUES (?, ?, current_date, now(), now(), now())",
                UUID.randomUUID(), otherEmployeeId);

        mockMvc.perform(get("/api/v1/attendance/me/history").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records.length()").value(1))
                .andExpect(jsonPath("$.records[0].state").value("SIGNED_IN"));
    }

    @Test
    @DisplayName("Concurrent sign-ins create exactly one daily record")
    void concurrentSignInsCreateOnlyOneRecord() throws Exception {
        UUID employeeId = fixtures.createEmployee(true, PASSWORD);
        MockHttpSession session = login(employeeId);
        UUID deviceId = fixtures.approveDevice(employeeId);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);

        int attempts = 2;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (int attempt = 0; attempt < attempts; attempt++) {
                UUID proofId = fixtures.issueProof(employeeId, deviceId, session.getId());
                results.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    try {
                        attendanceService.signIn(request, employeeId, proofId);
                        return "SIGNED_IN";
                    } catch (RuntimeException ex) {
                        return ex.getClass().getSimpleName();
                    }
                }));
            }
            start.countDown();

            List<String> outcomes = new ArrayList<>();
            for (Future<String> result : results) {
                outcomes.add(result.get(30, TimeUnit.SECONDS));
            }

            assertThat(outcomes).filteredOn("SIGNED_IN"::equals).hasSize(1);
            assertThat(outcomes).filteredOn(outcome -> !outcome.equals("SIGNED_IN")).hasSize(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(fixtures.countRecords(employeeId)).isEqualTo(1);
    }

    @Test
    @DisplayName("Invalid history parameters are rejected")
    void invalidHistoryParametersRejected() throws Exception {
        UUID employeeId = fixtures.createEmployee(true, PASSWORD);
        MockHttpSession session = login(employeeId);

        mockMvc.perform(get("/api/v1/attendance/me/history").param("size", "500").session(session))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/attendance/me/history")
                        .param("from", "2026-02-01")
                        .param("to", "2026-01-01")
                        .session(session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("'from' must not be later than 'to'."));
    }

    @Test
    @DisplayName("An unauthenticated caller cannot record attendance")
    void attendanceRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/attendance/sign-in")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                AttendanceTestFixtures.proofBody(UUID.randomUUID()))))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/attendance/me/today"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("An attendance action requires a CSRF token")
    void attendanceRequiresCsrf() throws Exception {
        UUID employeeId = fixtures.createEmployee(true, PASSWORD);
        MockHttpSession session = login(employeeId);
        UUID deviceId = fixtures.approveDevice(employeeId);
        UUID proofId = fixtures.issueProof(employeeId, deviceId, session.getId());

        mockMvc.perform(post("/api/v1/attendance/sign-in")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(AttendanceTestFixtures.proofBody(proofId))))
                .andExpect(status().isForbidden());
    }
}
