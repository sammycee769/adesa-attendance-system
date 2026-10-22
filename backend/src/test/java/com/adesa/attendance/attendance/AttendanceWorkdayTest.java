package com.adesa.attendance.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The attendance workday is the employee's local calendar day in
 * {@code Africa/Lagos} — never the server's timezone and never the device
 * clock.
 *
 * <p>The clock is pinned so the midnight boundary can be tested exactly.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@org.springframework.test.context.ActiveProfiles("dev")
class AttendanceWorkdayTest {

    private static final String PASSWORD = "workday-password-1";
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
        // This class exercises the workday/timezone rules, not the
        // office-network policy, so it runs with the explicit
        // local-development bypass (the network policy is covered by the
        // dedicated network test classes).
        registry.add("attendance.network.local-development-bypass", () -> "true");
    }

    /** Replaces the application clock with one the test can move. */
    @TestConfiguration
    static class MovableClockConfiguration {

        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock(Instant.parse("2026-01-01T10:00:00Z"));
        }
    }

    /** A clock whose instant can be changed while the context stays up. */
    static final class MovableClock extends Clock {

        private final ZoneId zone;
        private Instant instant;

        MovableClock(Instant instant) {
            this(instant, ZoneOffset.UTC);
        }

        private MovableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MovableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
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
    private MovableClock clock;

    private AttendanceTestFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new AttendanceTestFixtures(jdbcTemplate, passwordEncoder);
    }

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

    @Test
    @DisplayName("A sign-in shortly after midnight in Lagos belongs to the new Lagos workday")
    void signInAfterLagosMidnightUsesTheLagosWorkday() throws Exception {
        // 2026-01-01T23:30Z is 2026-01-02T00:30 in Lagos (UTC+1).
        clock.setInstant(Instant.parse("2026-01-01T23:30:00Z"));

        UUID employeeId = fixtures.createEmployee(true, PASSWORD);
        MockHttpSession session = login(employeeId);
        UUID deviceId = fixtures.approveDevice(employeeId);
        UUID proofId = fixtures.issueProof(employeeId, deviceId, session.getId());

        mockMvc.perform(post("/api/v1/attendance/sign-in")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(AttendanceTestFixtures.proofBody(proofId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attendanceDate").value("2026-01-02"));

        // The stored record carries the Lagos workday, not the UTC one.
        String storedDate = jdbcTemplate.queryForObject(
                "SELECT attendance_date::text FROM attendance_records WHERE employee_id = ?",
                String.class, employeeId);
        assertThat(storedDate).isEqualTo("2026-01-02");
        assertThat(LocalDate.ofInstant(clock.instant(), LAGOS)).isEqualTo(LocalDate.parse("2026-01-02"));
    }

    @Test
    @DisplayName("A sign-in during the Lagos working day belongs to the same workday")
    void signInDuringTheLagosDayUsesTheLagosWorkday() throws Exception {
        // 2026-01-01T10:00Z is 2026-01-01T11:00 in Lagos.
        clock.setInstant(Instant.parse("2026-01-01T10:00:00Z"));

        UUID employeeId = fixtures.createEmployee(true, PASSWORD);
        MockHttpSession session = login(employeeId);
        UUID deviceId = fixtures.approveDevice(employeeId);
        UUID proofId = fixtures.issueProof(employeeId, deviceId, session.getId());

        mockMvc.perform(post("/api/v1/attendance/sign-in")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(AttendanceTestFixtures.proofBody(proofId))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attendanceDate").value("2026-01-01"));

        mockMvc.perform(get("/api/v1/attendance/me/today").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attendanceDate").value("2026-01-01"))
                .andExpect(jsonPath("$.timezone").value("Africa/Lagos"));
    }
}
