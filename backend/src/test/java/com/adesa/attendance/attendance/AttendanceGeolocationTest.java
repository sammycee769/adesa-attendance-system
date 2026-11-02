package com.adesa.attendance.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Map;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AttendanceGeolocationTest {
    private static final String SECRET = "a".repeat(64);
    private static final String PASSWORD = "geolocation-password-123";
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("adesa_attendance").withUsername("adesa_app").withPassword("test_password");
    @DynamicPropertySource static void settings(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("attendance.network.enforcement-enabled", () -> true);
        r.add("attendance.network.authenticated-ingress-enabled", () -> true);
        r.add("attendance.network.ingress-shared-secret", () -> SECRET);
        r.add("attendance.network.allowed-ipv4", () -> "198.51.100.174/32");
        r.add("attendance.geolocation.enabled", () -> true);
        r.add("attendance.geolocation.latitude", () -> 6.6412393);
        r.add("attendance.geolocation.longitude", () -> 3.371182);
    }
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper mapper;
    AttendanceTestFixtures fixtures;
    UUID employee;
    UUID device;
    UUID proof;
    MockHttpSession session;

    @BeforeEach void prepareEmployee() throws Exception {
        fixtures = new AttendanceTestFixtures(jdbc, passwords);
        employee = fixtures.createEmployee(true, PASSWORD);
        var login = mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email", fixtures.emailFor(employee), "password", PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        session = (MockHttpSession) login.getRequest().getSession(false);
        device = fixtures.approveDevice(employee);
        proof = fixtures.issueProof(employee, device, session.getId());
    }
    private Map<String, Object> fix(double latitude) {
        return Map.of("latitude", latitude, "longitude", 3.371182,
                "accuracyMetres", 10, "timestampMillis", System.currentTimeMillis());
    }
    private ResultActions action(String action, String ip, String key, UUID proofId, Map<String, Object> location) throws Exception {
        var body = new java.util.HashMap<String, Object>();
        body.put("deviceProofId", proofId);
        if (location != null) body.put("location", location);
        return mvc.perform(post("/api/v1/attendance/" + action).session(session).with(csrf())
                .header("X-Adesa-Ingress-Key", key).header("X-Adesa-Client-IP", ip)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));
    }
    private boolean consumed(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT consumed_at IS NOT NULL FROM device_proofs WHERE id=?", Boolean.class, id));
    }

    @Test void officeIpNeedsNoLocationAndIgnoresAnUnneededLocationFix() throws Exception {
        action("sign-in", "198.51.100.174", SECRET, proof, fix(0)).andExpect(status().isCreated());
        String verification = jdbc.queryForObject("SELECT details->>'verificationMethod' FROM audit_logs WHERE actor_id=? AND action='ATTENDANCE_SIGNED_IN'", String.class, employee);
        assertThat(verification).isEqualTo("OFFICE_IP");
    }

    @Test void challengeDoesNotWriteOrConsumeProofAndAutomaticRetryCanSignInAndOut() throws Exception {
        action("sign-in", "203.0.113.22", SECRET, proof, null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("LOCATION_REQUIRED"));
        assertThat(fixtures.countRecords(employee)).isZero();
        assertThat(consumed(proof)).isFalse();
        action("sign-in", "203.0.113.22", SECRET, proof, fix(6.6412393)).andExpect(status().isCreated());
        assertThat(consumed(proof)).isTrue();
        UUID outProof = fixtures.issueProof(employee, device, session.getId());
        action("sign-out", "203.0.113.22", SECRET, outProof, null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("LOCATION_REQUIRED"));
        assertThat(consumed(outProof)).isFalse();
        action("sign-out", "203.0.113.22", SECRET, outProof, fix(6.6412393)).andExpect(status().isOk());
        assertThat(consumed(outProof)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE actor_id=? AND details->>'verificationMethod'='GEOLOCATION'", Integer.class, employee)).isEqualTo(2);
        assertThat(fixtures.countRecords(employee)).isEqualTo(1);
    }

    @Test void outsideLocationIsDeniedWithoutBurningTheProof() throws Exception {
        action("sign-in", "203.0.113.22", SECRET, proof, fix(6.65)).andExpect(status().isForbidden());
        assertThat(fixtures.countRecords(employee)).isZero();
        assertThat(consumed(proof)).isFalse();
    }

    @Test void sixtyMetresIsDeniedForSignInAndSignOutWithoutChangingAttendance() throws Exception {
        // Approximately 60m north of the pin: within the old 100m radius, outside the new 50m radius.
        double sixtyMetresNorth = 6.6412393 + Math.toDegrees(60.0 / 6_371_008.8);
        action("sign-in", "203.0.113.22", SECRET, proof, fix(sixtyMetresNorth))
                .andExpect(status().isForbidden());
        assertThat(fixtures.countRecords(employee)).isZero();
        assertThat(consumed(proof)).isFalse();
        action("sign-in", "203.0.113.22", SECRET, proof, fix(6.6412393)).andExpect(status().isCreated());
        UUID outProof = fixtures.issueProof(employee, device, session.getId());
        action("sign-out", "203.0.113.22", SECRET, outProof, fix(sixtyMetresNorth))
                .andExpect(status().isForbidden());
        assertThat(consumed(outProof)).isFalse();
        assertThat(jdbc.queryForObject("SELECT sign_out_at IS NULL FROM attendance_records WHERE employee_id=?", Boolean.class, employee))
                .isTrue();
    }

    @Test void forgedGatewayCannotUseEvenAnOfficeLocation() throws Exception {
        action("sign-in", "198.51.100.174", "b".repeat(64), proof, fix(6.6412393))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").doesNotExist());
        assertThat(fixtures.countRecords(employee)).isZero();
    }

    @Test void locationDoesNotReplaceTheApprovedDeviceProof() throws Exception {
        action("sign-in", "203.0.113.22", SECRET, UUID.randomUUID(), fix(6.6412393)).andExpect(status().isForbidden());
        assertThat(fixtures.countRecords(employee)).isZero();
    }

    @Test void authenticatedIpv6CanUseLocationButNeverTheIpv4FastPath() throws Exception {
        action("sign-in", "2001:db8::1", SECRET, proof, null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("LOCATION_REQUIRED"));
        action("sign-in", "2001:db8::1", SECRET, proof, fix(6.6412393)).andExpect(status().isCreated());
    }
}
