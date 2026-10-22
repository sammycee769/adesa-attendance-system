package com.adesa.attendance.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import com.adesa.attendance.employee.EmployeeRole;
import com.adesa.attendance.security.AuthenticatedEmployee;
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
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
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
 * Attendance requests that arrive through a configured reverse proxy.
 *
 * <p>When the application is deployed behind a proxy, the proxy's address must
 * be listed in {@code attendance.trusted-proxy-addresses}. Only then is the
 * forwarded client address used, and only the right-most hop that is not itself
 * a trusted proxy is taken — an attacker cannot prepend an approved address.
 *
 * <p>The configured office network is a TEST-NET-2 fixture
 * ({@code 198.51.100.174/32}), not an ADESA egress address.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AttendanceTrustedProxyTest {

    private static final String PASSWORD = "proxy-password-1";

    private static final String OFFICE_PUBLIC_ADDRESS = "198.51.100.174";
    private static final String APPROVED_CIDR = "198.51.100.174/32";
    private static final String TRUSTED_PROXY_CIDR = "172.31.0.5/32";
    private static final String PROXY_ADDRESS = "172.31.0.5";

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
        registry.add("attendance.network.enforcement-enabled", () -> "true");
        registry.add("attendance.network.allowed-ipv4", () -> APPROVED_CIDR);
        registry.add("attendance.network.trusted-proxies", () -> TRUSTED_PROXY_CIDR);
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

    private static UUID adminActorId;

    @BeforeEach
    void setUp() {
        fixtures = new AttendanceTestFixtures(jdbcTemplate, passwordEncoder);
        if (adminActorId == null) {
            adminActorId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO employees (id, employee_number, full_name, email, password_hash, role, is_active) "
                            + "VALUES (?, 'PROXY-ADMIN', 'Proxy Admin', 'proxy.admin@adesa.test', 'x', "
                            + "'ADMIN', true)",
                    adminActorId);
        }
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

    private static Authentication asAdmin() {
        AuthenticatedEmployee principal = new AuthenticatedEmployee(
                adminActorId, "proxy.admin@adesa.test", "Proxy Admin", EmployeeRole.ADMIN);
        return new TestingAuthenticationToken(principal, null, "ROLE_ADMIN");
    }

    @Test
    @DisplayName("The office address forwarded by a trusted proxy is accepted")
    void officeAddressForwardedByTrustedProxyAccepted() throws Exception {
        UUID employeeId = fixtures.createEmployee(true, PASSWORD);
        MockHttpSession session = login(employeeId);
        UUID deviceId = fixtures.approveDevice(employeeId);
        UUID proofId = fixtures.issueProof(employeeId, deviceId, session.getId());

        mockMvc.perform(post("/api/v1/attendance/sign-in")
                        .session(session)
                        .with(from(PROXY_ADDRESS))
                        .header("X-Forwarded-For", OFFICE_PUBLIC_ADDRESS)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(AttendanceTestFixtures.proofBody(proofId))))
                .andExpect(status().isCreated());

        assertThat(fixtures.countRecords(employeeId)).isEqualTo(1);
    }

    @Test
    @DisplayName("A spoofed entry in front of the real address does not help")
    void spoofedEntryInTheForwardedChainIsRejected() throws Exception {
        UUID employeeId = fixtures.createEmployee(true, PASSWORD);
        MockHttpSession session = login(employeeId);
        UUID deviceId = fixtures.approveDevice(employeeId);
        UUID proofId = fixtures.issueProof(employeeId, deviceId, session.getId());

        mockMvc.perform(post("/api/v1/attendance/sign-in")
                        .session(session)
                        .with(from(PROXY_ADDRESS))
                        .header("X-Forwarded-For", OFFICE_PUBLIC_ADDRESS + ", 203.0.113.9")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(AttendanceTestFixtures.proofBody(proofId))))
                .andExpect(status().isForbidden());

        assertThat(fixtures.countRecords(employeeId)).isZero();
    }

    @Test
    @DisplayName("A malformed forwarded chain from a trusted proxy fails closed")
    void malformedForwardedChainFromTrustedProxyRejected() throws Exception {
        UUID employeeId = fixtures.createEmployee(true, PASSWORD);
        MockHttpSession session = login(employeeId);
        UUID deviceId = fixtures.approveDevice(employeeId);
        UUID proofId = fixtures.issueProof(employeeId, deviceId, session.getId());

        mockMvc.perform(post("/api/v1/attendance/sign-in")
                        .session(session)
                        .with(from(PROXY_ADDRESS))
                        .header("X-Forwarded-For", "not-an-ip")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(AttendanceTestFixtures.proofBody(proofId))))
                .andExpect(status().isForbidden());

        assertThat(fixtures.countRecords(employeeId)).isZero();
    }

    @Test
    @DisplayName("A forwarded header from an untrusted peer is ignored")
    void forwardedHeaderFromUntrustedPeerIgnored() throws Exception {
        UUID employeeId = fixtures.createEmployee(true, PASSWORD);
        MockHttpSession session = login(employeeId);
        UUID deviceId = fixtures.approveDevice(employeeId);
        UUID proofId = fixtures.issueProof(employeeId, deviceId, session.getId());

        mockMvc.perform(post("/api/v1/attendance/sign-in")
                        .session(session)
                        .with(from("192.168.3.170"))
                        .header("X-Forwarded-For", OFFICE_PUBLIC_ADDRESS)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(AttendanceTestFixtures.proofBody(proofId))))
                .andExpect(status().isForbidden());

        assertThat(fixtures.countRecords(employeeId)).isZero();
    }

    @Test
    @DisplayName("The diagnostics endpoint reports the forwarded office address and the trusted proxy count")
    void diagnosticsReportTheForwardedAddress() throws Exception {
        mockMvc.perform(get("/api/v1/admin/network/client-address")
                        .with(from(PROXY_ADDRESS))
                        .header("X-Forwarded-For", OFFICE_PUBLIC_ADDRESS)
                        .with(authentication(asAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedClientAddress").value(OFFICE_PUBLIC_ADDRESS))
                .andExpect(jsonPath("$.withinApprovedNetwork").value(true))
                .andExpect(jsonPath("$.trustedProxyCount").value(1));
    }
}
