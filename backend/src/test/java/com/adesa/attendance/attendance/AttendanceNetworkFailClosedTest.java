package com.adesa.attendance.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The office-network restriction must fail closed when enforcement is disabled
 * and no explicit local-development bypass is set.
 *
 * <p>This is the regression test for the reported incident: a deployment ran
 * with {@code ATTENDANCE_NETWORK_ENFORCEMENT_ENABLED=false} and no bypass, so
 * every attendance action must now be refused (HTTP 403) instead of silently
 * allowed. Only an explicit local-development bypass — refused under the
 * {@code prod} profile — may turn the check off.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AttendanceNetworkFailClosedTest {

    private static final String PASSWORD = "fail-closed-password-1";

    /** The approved ADESA HQ office address, correctly configured. */
    private static final String APPROVED_CIDR = "105.127.6.174/32";
    private static final String OFFICE_PUBLIC_ADDRESS = "105.127.6.174";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("adesa_attendance")
            .withUsername("adesa_app")
            .withPassword("test_password");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // The incident configuration: enforcement disabled and NO bypass.
        registry.add("attendance.network.enforcement-enabled", () -> "false");
        registry.add("attendance.network.local-development-bypass", () -> "false");
        registry.add("attendance.network.allowed-ipv4", () -> APPROVED_CIDR);
        registry.add("attendance.network.trusted-proxies", () -> "");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

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

    private RequestPostProcessor from(String remoteAddress) {
        return request -> {
            request.setRemoteAddr(remoteAddress);
            return request;
        };
    }

    @Test
    @DisplayName("Sign-in is refused even from the office address while enforcement is disabled without a bypass")
    void signInFailsClosedWhenEnforcementDisabled() throws Exception {
        UUID employeeId = fixtures.createEmployee(true, PASSWORD);
        MockHttpSession session = login(employeeId);
        UUID deviceId = fixtures.approveDevice(employeeId);
        UUID proofId = fixtures.issueProof(employeeId, deviceId, session.getId());

        mockMvc.perform(post("/api/v1/attendance/sign-in")
                        .session(session)
                        .with(from(OFFICE_PUBLIC_ADDRESS))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(AttendanceTestFixtures.proofBody(proofId))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(
                        "Attendance is only available from the approved ADESA office network."));

        assertThat(fixtures.countRecords(employeeId)).isZero();
    }

    @Test
    @DisplayName("Sign-out is refused while enforcement is disabled without a bypass")
    void signOutFailsClosedWhenEnforcementDisabled() throws Exception {
        UUID employeeId = fixtures.createEmployee(true, PASSWORD);
        MockHttpSession session = login(employeeId);
        UUID deviceId = fixtures.approveDevice(employeeId);
        UUID proofId = fixtures.issueProof(employeeId, deviceId, session.getId());

        mockMvc.perform(post("/api/v1/attendance/sign-out")
                        .session(session)
                        .with(from(OFFICE_PUBLIC_ADDRESS))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(AttendanceTestFixtures.proofBody(proofId))))
                .andExpect(status().isForbidden());

        assertThat(fixtures.countRecords(employeeId)).isZero();
    }
}
