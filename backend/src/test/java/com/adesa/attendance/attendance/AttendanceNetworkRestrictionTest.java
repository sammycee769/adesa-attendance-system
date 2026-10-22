package com.adesa.attendance.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
 * The office-network restriction applied to attendance actions.
 *
 * <p>The approved office network is the ADESA HQ public address supplied by
 * ADESA ({@code 105.127.6.174/32}). No trusted reverse proxy is configured yet,
 * so the direct peer address is authoritative and forwarded headers are
 * ignored.
 *
 * <p>The tests prove that the backend enforces exactly the configured CIDR:
 * the office address is accepted, every other address is refused, a request
 * without a usable address fails closed, and a client cannot spoof its way
 * past the check with a forwarding header.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AttendanceNetworkRestrictionTest {

    private static final String PASSWORD = "network-password-1";

    /** The ADESA HQ office public address, as supplied by ADESA. */
    private static final String OFFICE_PUBLIC_ADDRESS = "105.127.6.174";
    private static final String APPROVED_CIDR = "105.127.6.174/32";

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

    private static UUID adminActorId;

    @BeforeEach
    void setUp() {
        fixtures = new AttendanceTestFixtures(jdbcTemplate, passwordEncoder);
        if (adminActorId == null) {
            adminActorId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO employees (id, employee_number, full_name, email, password_hash, role, is_active) "
                            + "VALUES (?, 'NETWORK-ADMIN', 'Network Admin', 'network.admin@adesa.test', 'x', "
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

    /** Simulates the direct peer address the server sees. */
    private RequestPostProcessor from(String remoteAddress) {
        return request -> {
            request.setRemoteAddr(remoteAddress);
            return request;
        };
    }

    private static Authentication asAdmin() {
        AuthenticatedEmployee principal = new AuthenticatedEmployee(
                adminActorId, "network.admin@adesa.test", "Network Admin", EmployeeRole.ADMIN);
        return new TestingAuthenticationToken(principal, null, "ROLE_ADMIN");
    }

    private static Authentication asEmployee() {
        AuthenticatedEmployee principal = new AuthenticatedEmployee(
                UUID.randomUUID(), "employee@adesa.test", "Employee", EmployeeRole.EMPLOYEE);
        return new TestingAuthenticationToken(principal, null, "ROLE_EMPLOYEE");
    }

    @Test
    @DisplayName("A sign-in from the approved ADESA office address is accepted")
    void signInFromTheApprovedOfficeAddressAccepted() throws Exception {
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
                .andExpect(status().isCreated());

        assertThat(fixtures.countRecords(employeeId)).isEqualTo(1);
    }

    @Test
    @DisplayName("A sign-in from outside the approved office network is rejected and audited")
    void signInOutsideTheApprovedNetworkRejected() throws Exception {
        UUID employeeId = fixtures.createEmployee(true, PASSWORD);
        MockHttpSession session = login(employeeId);
        UUID deviceId = fixtures.approveDevice(employeeId);
        UUID proofId = fixtures.issueProof(employeeId, deviceId, session.getId());

        mockMvc.perform(post("/api/v1/attendance/sign-in")
                        .session(session)
                        .with(from("203.0.113.7"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(AttendanceTestFixtures.proofBody(proofId))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(
                        "Attendance is only available from the approved ADESA office network."));

        assertThat(fixtures.countRecords(employeeId)).isZero();
        assertThat(fixtures.countAudit(employeeId, "ATTENDANCE_ACTION_REJECTED")).isEqualTo(1);
    }

    @Test
    @DisplayName("A neighbouring public address is rejected (the allowlist is a single host)")
    void signInFromANeighbouringPublicAddressRejected() throws Exception {
        UUID employeeId = fixtures.createEmployee(true, PASSWORD);
        MockHttpSession session = login(employeeId);
        UUID deviceId = fixtures.approveDevice(employeeId);
        UUID proofId = fixtures.issueProof(employeeId, deviceId, session.getId());

        mockMvc.perform(post("/api/v1/attendance/sign-in")
                        .session(session)
                        .with(from("105.127.6.175"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(AttendanceTestFixtures.proofBody(proofId))))
                .andExpect(status().isForbidden());

        assertThat(fixtures.countRecords(employeeId)).isZero();
    }

    @Test
    @DisplayName("A private laptop address is rejected (only the office public address is approved)")
    void signInFromAPrivateLaptopAddressRejected() throws Exception {
        UUID employeeId = fixtures.createEmployee(true, PASSWORD);
        MockHttpSession session = login(employeeId);
        UUID deviceId = fixtures.approveDevice(employeeId);
        UUID proofId = fixtures.issueProof(employeeId, deviceId, session.getId());

        mockMvc.perform(post("/api/v1/attendance/sign-in")
                        .session(session)
                        .with(from("192.168.3.170"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(AttendanceTestFixtures.proofBody(proofId))))
                .andExpect(status().isForbidden());

        assertThat(fixtures.countRecords(employeeId)).isZero();
    }

    @Test
    @DisplayName("A request without any client address is rejected (fail closed)")
    void signInWithoutClientAddressRejected() throws Exception {
        UUID employeeId = fixtures.createEmployee(true, PASSWORD);
        MockHttpSession session = login(employeeId);
        UUID deviceId = fixtures.approveDevice(employeeId);
        UUID proofId = fixtures.issueProof(employeeId, deviceId, session.getId());

        mockMvc.perform(post("/api/v1/attendance/sign-in")
                        .session(session)
                        .with(from(null))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(AttendanceTestFixtures.proofBody(proofId))))
                .andExpect(status().isForbidden());

        assertThat(fixtures.countRecords(employeeId)).isZero();
    }

    @Test
    @DisplayName("A request with an unparsable client address is rejected (fail closed)")
    void signInWithMalformedClientAddressRejected() throws Exception {
        UUID employeeId = fixtures.createEmployee(true, PASSWORD);
        MockHttpSession session = login(employeeId);
        UUID deviceId = fixtures.approveDevice(employeeId);
        UUID proofId = fixtures.issueProof(employeeId, deviceId, session.getId());

        mockMvc.perform(post("/api/v1/attendance/sign-in")
                        .session(session)
                        .with(from("not-an-ip-address"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(AttendanceTestFixtures.proofBody(proofId))))
                .andExpect(status().isForbidden());

        assertThat(fixtures.countRecords(employeeId)).isZero();
    }

    @Test
    @DisplayName("A spoofed forwarding header from an untrusted source does not bypass the restriction")
    void spoofedForwardingHeaderRejected() throws Exception {
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
    @DisplayName("A sign-out from outside the approved office network is rejected")
    void signOutOutsideTheApprovedNetworkRejected() throws Exception {
        UUID employeeId = fixtures.createEmployee(true, PASSWORD);
        MockHttpSession session = login(employeeId);
        UUID deviceId = fixtures.approveDevice(employeeId);

        mockMvc.perform(post("/api/v1/attendance/sign-in")
                        .session(session)
                        .with(from(OFFICE_PUBLIC_ADDRESS))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                AttendanceTestFixtures.proofBody(
                                        fixtures.issueProof(employeeId, deviceId, session.getId())))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/attendance/sign-out")
                        .session(session)
                        .with(from("203.0.113.7"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                AttendanceTestFixtures.proofBody(
                                        fixtures.issueProof(employeeId, deviceId, session.getId())))))
                .andExpect(status().isForbidden());

        // The workday stays open.
        Integer signOut = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM attendance_records WHERE employee_id = ? AND sign_out_at IS NOT NULL",
                Integer.class, employeeId);
        assertThat(signOut).isZero();
    }

    @Test
    @DisplayName("A native IPv6 client is rejected (the allowlist is IPv4-only)")
    void signInFromAnIpv6ClientRejected() throws Exception {
        UUID employeeId = fixtures.createEmployee(true, PASSWORD);
        MockHttpSession session = login(employeeId);
        UUID deviceId = fixtures.approveDevice(employeeId);
        UUID proofId = fixtures.issueProof(employeeId, deviceId, session.getId());

        mockMvc.perform(post("/api/v1/attendance/sign-in")
                        .session(session)
                        .with(from("2001:db8::1"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(AttendanceTestFixtures.proofBody(proofId))))
                .andExpect(status().isForbidden());

        assertThat(fixtures.countRecords(employeeId)).isZero();
    }

    @Test
    @DisplayName("An IPv4-mapped IPv6 client is treated as its IPv4 address")
    void signInFromAnIpv4MappedIpv6ClientAccepted() throws Exception {
        UUID employeeId = fixtures.createEmployee(true, PASSWORD);
        MockHttpSession session = login(employeeId);
        UUID deviceId = fixtures.approveDevice(employeeId);
        UUID proofId = fixtures.issueProof(employeeId, deviceId, session.getId());

        mockMvc.perform(post("/api/v1/attendance/sign-in")
                        .session(session)
                        .with(from("::ffff:" + OFFICE_PUBLIC_ADDRESS))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(AttendanceTestFixtures.proofBody(proofId))))
                .andExpect(status().isCreated());

        assertThat(fixtures.countRecords(employeeId)).isEqualTo(1);
    }

    @Test
    @DisplayName("A denied attempt is written to the restricted security log with IP, timestamp and reason")
    void deniedAttemptIsWrittenToTheSecurityLog() throws Exception {
        UUID employeeId = fixtures.createEmployee(true, PASSWORD);
        MockHttpSession session = login(employeeId);
        UUID deviceId = fixtures.approveDevice(employeeId);
        UUID proofId = fixtures.issueProof(employeeId, deviceId, session.getId());

        String deniedAddress = "198.51.100.77";

        mockMvc.perform(post("/api/v1/attendance/sign-in")
                        .session(session)
                        .with(from(deniedAddress))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(AttendanceTestFixtures.proofBody(proofId))))
                .andExpect(status().isForbidden());

        List<String> lines = Files.readAllLines(Path.of("target/security.log"));
        String entry = lines.stream()
                .filter(line -> line.contains("client_ip=" + deniedAddress))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No security-log entry for " + deniedAddress
                        + ". Log: " + String.join("\n", lines)));

        assertThat(entry)
                .contains("event=attendance_denied")
                .contains("action=SIGN_IN")
                .contains("reason=OUTSIDE_APPROVED_NETWORK")
                .contains("client_ip=" + deniedAddress);
        // The entry starts with an ISO-8601 timestamp.
        assertThat(entry).matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*");
    }

    @Test
    @DisplayName("The diagnostics endpoint reports the resolved address, not the forwarded header")
    void diagnosticsReportTheResolvedAddressNotTheHeader() throws Exception {
        mockMvc.perform(get("/api/v1/admin/network/client-address")
                        .with(from("192.168.3.170"))
                        .header("X-Forwarded-For", OFFICE_PUBLIC_ADDRESS)
                        .with(authentication(asAdmin())))
                .andExpect(status().isOk())
                // The authoritative value is the direct peer: the forwarded header
                // is not trusted because the peer is not a configured proxy.
                .andExpect(jsonPath("$.resolvedClientAddress").value("192.168.3.170"))
                .andExpect(jsonPath("$.withinApprovedNetwork").value(false))
                .andExpect(jsonPath("$.resolvedAddressIsIpv4").value(true))
                // The raw header is reported for troubleshooting only.
                .andExpect(jsonPath("$.untrustedForwardedClientAddress").value(OFFICE_PUBLIC_ADDRESS));
    }

    @Test
    @DisplayName("The diagnostics endpoint reports an IPv6 client as not IPv4")
    void diagnosticsReportAnIpv6Client() throws Exception {
        mockMvc.perform(get("/api/v1/admin/network/client-address")
                        .with(from("2001:db8::1"))
                        .with(authentication(asAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedClientAddress").value("2001:db8::1"))
                .andExpect(jsonPath("$.resolvedAddressIsIpv4").value(false))
                .andExpect(jsonPath("$.withinApprovedNetwork").value(false));
    }

    @Test
    @DisplayName("The diagnostics endpoint reports the client address the application sees")
    void diagnosticsReportTheResolvedClientAddress() throws Exception {
        mockMvc.perform(get("/api/v1/admin/network/client-address")
                        .with(from(OFFICE_PUBLIC_ADDRESS))
                        .with(authentication(asAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedClientAddress").value(OFFICE_PUBLIC_ADDRESS))
                .andExpect(jsonPath("$.withinApprovedNetwork").value(true))
                .andExpect(jsonPath("$.enforcementEnabled").value(true))
                .andExpect(jsonPath("$.approvedNetworkCount").value(1))
                .andExpect(jsonPath("$.trustedProxyCount").value(0))
                .andExpect(jsonPath("$.clientIpHeader").value("X-Forwarded-For"));
    }

    @Test
    @DisplayName("The diagnostics endpoint reports an address outside the approved network")
    void diagnosticsReportAnUnapprovedClientAddress() throws Exception {
        mockMvc.perform(get("/api/v1/admin/network/client-address")
                        .with(from("203.0.113.7"))
                        .with(authentication(asAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedClientAddress").value("203.0.113.7"))
                .andExpect(jsonPath("$.withinApprovedNetwork").value(false));
    }

    @Test
    @DisplayName("The diagnostics endpoint is restricted to ADMIN")
    void diagnosticsAreAdminOnly() throws Exception {
        mockMvc.perform(get("/api/v1/admin/network/client-address")
                        .with(from(OFFICE_PUBLIC_ADDRESS)))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/admin/network/client-address")
                        .with(from(OFFICE_PUBLIC_ADDRESS))
                        .with(authentication(asEmployee())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("The diagnostics endpoint never returns the configured networks or proxies")
    void diagnosticsDoNotExposeConfiguration() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/admin/network/client-address")
                        .with(from(OFFICE_PUBLIC_ADDRESS))
                        .with(authentication(asAdmin())))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain(APPROVED_CIDR).doesNotContain("/32");
    }
}
