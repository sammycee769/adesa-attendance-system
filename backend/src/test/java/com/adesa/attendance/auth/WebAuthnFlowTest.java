package com.adesa.attendance.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import com.adesa.attendance.device.DeviceProof;
import com.adesa.attendance.device.DeviceProofService;
import com.adesa.attendance.employee.EmployeeRole;
import com.adesa.attendance.security.AuthenticatedEmployee;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.PublicKeyCredentialCreationOptions;
import com.webauthn4j.data.PublicKeyCredentialRequestOptions;
import com.webauthn4j.data.client.Origin;
import com.webauthn4j.test.authenticator.webauthn.NoneAttestationAuthenticator;
import com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor;
import com.webauthn4j.test.client.ClientPlatform;

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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end WebAuthn ceremonies against a real PostgreSQL database using
 * webauthn4j's virtual authenticator.
 *
 * <p>These tests cover the server-side controls that matter for attendance:
 * registration starts as PENDING_APPROVAL and is useless until an administrator
 * approves it, authentication issues a single-use session-bound proof, and
 * revoking a device invalidates live sessions immediately.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class WebAuthnFlowTest {

    /** Must match {@code webauthn.allowed-origins} in application.yml. */
    private static final String ORIGIN = "http://localhost:5173";

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

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectConverter webAuthnObjectConverter;

    @Autowired
    private DeviceProofService deviceProofService;

    /** Real ADMIN row because audit entries reference employees(id). */
    private static UUID adminActorId;

    @BeforeEach
    void seedAdminActor() {
        if (adminActorId == null) {
            adminActorId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO employees (id, employee_number, full_name, email, password_hash, role, is_active) "
                            + "VALUES (?, 'WA-ADMIN', 'WebAuthn Admin', 'webauthn.admin@adesa.test', ?, 'ADMIN', true)",
                    adminActorId, passwordEncoder.encode("admin-password-123"));
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private UUID createEmployee(String password) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO employees (id, employee_number, full_name, email, password_hash, role, is_active) "
                        + "VALUES (?, ?, ?, ?, ?, 'EMPLOYEE', true)",
                id, "EMP-" + id.toString().substring(0, 8), "WebAuthn Employee",
                emailFor(id), passwordEncoder.encode(password));
        return id;
    }

    private static String emailFor(UUID employeeId) {
        return "employee." + employeeId + "@adesa.test";
    }

    /** Signs in for real so the session carries the guards' markers. */
    private MockHttpSession login(UUID employeeId, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", emailFor(employeeId), "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private static Authentication asEmployee(UUID employeeId) {
        AuthenticatedEmployee principal = new AuthenticatedEmployee(
                employeeId, emailFor(employeeId), "WebAuthn Employee", EmployeeRole.EMPLOYEE);
        return new TestingAuthenticationToken(principal, null, "ROLE_EMPLOYEE");
    }

    private static Authentication asAdmin() {
        AuthenticatedEmployee principal = new AuthenticatedEmployee(
                adminActorId, "webauthn.admin@adesa.test", "WebAuthn Admin", EmployeeRole.ADMIN);
        return new TestingAuthenticationToken(principal, null, "ROLE_ADMIN");
    }

    private static ClientPlatform clientPlatform(String origin) {
        return new ClientPlatform(
                new Origin(origin),
                new WebAuthnAuthenticatorAdaptor(new NoneAttestationAuthenticator()));
    }

    /** Starts a registration ceremony and returns the parsed options. */
    private JsonNode startRegistration(MockHttpSession session, UUID employeeId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/webauthn/register/options")
                        .session(session)
                        .with(authentication(asEmployee(employeeId)))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private PublicKeyCredentialCreationOptions readCreationOptions(JsonNode body) {
        return webAuthnObjectConverter.getJsonConverter()
                .readValue(body.get("publicKey").toString(), PublicKeyCredentialCreationOptions.class);
    }

    private String createCredentialJson(ClientPlatform client, JsonNode optionsBody) {
        var credential = client.create(readCreationOptions(optionsBody));
        return webAuthnObjectConverter.getJsonConverter().writeValueAsString(credential);
    }

    private MvcResult submitRegistration(MockHttpSession session, UUID employeeId,
                                         UUID challengeId, String credentialJson, String label)
            throws Exception {
        return mockMvc.perform(post("/api/v1/auth/webauthn/register")
                        .session(session)
                        .with(authentication(asEmployee(employeeId)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "challengeId", challengeId.toString(),
                                "credential", objectMapper.readTree(credentialJson),
                                "label", label))))
                .andReturn();
    }

    /** Registers and approves a device, returning its id. */
    private UUID registerAndApprove(MockHttpSession session, UUID employeeId, ClientPlatform client)
            throws Exception {
        JsonNode options = startRegistration(session, employeeId);
        MvcResult registered = submitRegistration(
                session, employeeId,
                UUID.fromString(options.get("challengeId").asText()),
                createCredentialJson(client, options),
                "Test passkey");
        assertThat(registered.getResponse().getStatus()).isEqualTo(201);
        UUID deviceId = jdbcTemplate.queryForObject(
                "SELECT id FROM registered_devices WHERE employee_id = ?", UUID.class, employeeId);

        mockMvc.perform(post("/api/v1/admin/devices/" + deviceId + "/approve")
                        .with(authentication(asAdmin()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        return deviceId;
    }

    /** Runs a full authentication ceremony and returns the issued proof id. */
    private UUID authenticate(MockHttpSession session, UUID employeeId, ClientPlatform client)
            throws Exception {
        MvcResult optionsResult = mockMvc.perform(post("/api/v1/auth/webauthn/authenticate/options")
                        .session(session)
                        .with(authentication(asEmployee(employeeId)))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode optionsBody = objectMapper.readTree(optionsResult.getResponse().getContentAsString());

        PublicKeyCredentialRequestOptions requestOptions = webAuthnObjectConverter.getJsonConverter()
                .readValue(optionsBody.get("publicKey").toString(), PublicKeyCredentialRequestOptions.class);
        var assertion = client.get(requestOptions);
        String assertionJson = webAuthnObjectConverter.getJsonConverter().writeValueAsString(assertion);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/webauthn/authenticate")
                        .session(session)
                        .with(authentication(asEmployee(employeeId)))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "challengeId", optionsBody.get("challengeId").asText(),
                                "credential", objectMapper.readTree(assertionJson)))))
                .andExpect(status().isOk())
                .andReturn();
        return UUID.fromString(objectMapper
                .readTree(result.getResponse().getContentAsString()).get("deviceProofId").asText());
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Registration stays PENDING_APPROVAL until an ADMIN approves the device")
    void registrationRequiresAdminApproval() throws Exception {
        UUID employeeId = createEmployee("webauthn-password-123");
        MockHttpSession session = login(employeeId, "webauthn-password-123");
        ClientPlatform client = clientPlatform(ORIGIN);

        JsonNode options = startRegistration(session, employeeId);
        MvcResult registered = submitRegistration(
                session, employeeId,
                UUID.fromString(options.get("challengeId").asText()),
                createCredentialJson(client, options),
                "YubiKey");

        assertThat(registered.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(registered.getResponse().getContentAsString());
        assertThat(body.get("status").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(body.get("label").asText()).isEqualTo("YubiKey");
        // Public key material must never be exposed by the API.
        assertThat(body.has("credentialId")).isFalse();
        assertThat(body.has("attestedCredentialData")).isFalse();

        UUID deviceId = jdbcTemplate.queryForObject(
                "SELECT id FROM registered_devices WHERE employee_id = ?", UUID.class, employeeId);

        mockMvc.perform(post("/api/v1/admin/devices/" + deviceId + "/approve")
                        .with(authentication(asAdmin()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedAt").isNotEmpty());
    }

    @Test
    @DisplayName("Authentication issues a single-use proof bound to the calling session")
    void authenticationIssuesSingleUseSessionBoundProof() throws Exception {
        UUID employeeId = createEmployee("webauthn-password-123");
        MockHttpSession session = login(employeeId, "webauthn-password-123");
        ClientPlatform client = clientPlatform(ORIGIN);
        UUID deviceId = registerAndApprove(session, employeeId, client);

        UUID proofId = authenticate(session, employeeId, client);

        // First use succeeds; replaying the same proof fails.
        assertThat(deviceProofService.consume(proofId, employeeId, session.getId())).isTrue();
        assertThat(deviceProofService.consume(proofId, employeeId, session.getId())).isFalse();

        // A proof issued for one session cannot be used from another.
        DeviceProof otherSession = deviceProofService.issue(employeeId, deviceId, session.getId());
        assertThat(deviceProofService.consume(otherSession.getId(), employeeId, "some-other-session"))
                .isFalse();
        assertThat(deviceProofService.consume(otherSession.getId(), employeeId, session.getId())).isTrue();

        // An expired proof is rejected.
        DeviceProof expired = deviceProofService.issue(employeeId, deviceId, session.getId());
        jdbcTemplate.update("UPDATE device_proofs SET expires_at = now() - interval '1 minute' WHERE id = ?",
                expired.getId());
        assertThat(deviceProofService.consume(expired.getId(), employeeId, session.getId())).isFalse();
    }

    @Test
    @DisplayName("An expired challenge cannot be used to complete registration")
    void expiredChallengeIsRejected() throws Exception {
        UUID employeeId = createEmployee("webauthn-password-123");
        MockHttpSession session = login(employeeId, "webauthn-password-123");
        ClientPlatform client = clientPlatform(ORIGIN);

        JsonNode options = startRegistration(session, employeeId);
        UUID challengeId = UUID.fromString(options.get("challengeId").asText());
        jdbcTemplate.update(
                "UPDATE webauthn_challenges SET expires_at = now() - interval '1 minute' WHERE id = ?",
                challengeId);

        MvcResult result = submitRegistration(
                session, employeeId, challengeId, createCredentialJson(client, options), "Test passkey");

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("message").asText()).contains("invalid, expired or already used");
    }

    @Test
    @DisplayName("A credential created for a different origin is rejected")
    void credentialFromAnotherOriginIsRejected() throws Exception {
        UUID employeeId = createEmployee("webauthn-password-123");
        MockHttpSession session = login(employeeId, "webauthn-password-123");
        ClientPlatform evilClient = clientPlatform("http://evil.example");

        JsonNode options = startRegistration(session, employeeId);
        MvcResult result = submitRegistration(
                session, employeeId,
                UUID.fromString(options.get("challengeId").asText()),
                createCredentialJson(evilClient, options),
                "Test passkey");

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString())
                .get("message").asText()).contains("could not be verified");
    }

    @Test
    @DisplayName("A pending device cannot start authentication")
    void pendingDeviceCannotAuthenticate() throws Exception {
        UUID employeeId = createEmployee("webauthn-password-123");
        MockHttpSession session = login(employeeId, "webauthn-password-123");
        ClientPlatform client = clientPlatform(ORIGIN);

        JsonNode options = startRegistration(session, employeeId);
        submitRegistration(session, employeeId,
                UUID.fromString(options.get("challengeId").asText()),
                createCredentialJson(client, options), "Test passkey");

        mockMvc.perform(post("/api/v1/auth/webauthn/authenticate/options")
                        .session(session)
                        .with(authentication(asEmployee(employeeId)))
                        .with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("An employee with an approved device cannot start another registration")
    void approvedDeviceBlocksSecondRegistration() throws Exception {
        UUID employeeId = createEmployee("webauthn-password-123");
        MockHttpSession session = login(employeeId, "webauthn-password-123");
        registerAndApprove(session, employeeId, clientPlatform(ORIGIN));

        mockMvc.perform(post("/api/v1/auth/webauthn/register/options")
                        .session(session)
                        .with(authentication(asEmployee(employeeId)))
                        .with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Revoking a device bumps the credentials version and invalidates live sessions")
    void revokingDeviceInvalidatesSession() throws Exception {
        UUID employeeId = createEmployee("webauthn-password-123");
        MockHttpSession session = login(employeeId, "webauthn-password-123");
        UUID deviceId = registerAndApprove(session, employeeId, clientPlatform(ORIGIN));

        mockMvc.perform(get("/api/v1/auth/me")
                        .session(session)
                        .with(authentication(asEmployee(employeeId))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/admin/devices/" + deviceId + "/revoke")
                        .with(authentication(asAdmin()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        // The next request with the old session is rejected immediately.
        mockMvc.perform(get("/api/v1/auth/me")
                        .session(session)
                        .with(authentication(asEmployee(employeeId))))
                .andExpect(status().isUnauthorized());

        Integer version = jdbcTemplate.queryForObject(
                "SELECT credentials_version FROM employees WHERE id = ?", Integer.class, employeeId);
        assertThat(version).isEqualTo(2);
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM registered_devices WHERE id = ?", String.class, deviceId);
        assertThat(status).isEqualTo("REVOKED");
    }
}
