package com.adesa.attendance.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.adesa.attendance.employee.EmployeeRole;
import com.adesa.attendance.security.AuthenticatedEmployee;
import com.adesa.attendance.security.SessionAttributes;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;

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
import org.springframework.security.test.web.support.WebTestUtils;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Session authentication against a real PostgreSQL container: login, logout,
 * CSRF, rate limiting, the absolute session timeout and — critically — that
 * password resets and deactivation invalidate live sessions immediately.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuthControllerTest {

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
    private CookieCsrfTokenRepository csrfTokenRepository;

    /** A real ADMIN row: audit entries reference employees(id) by foreign key. */
    private static UUID adminActorId;

    @BeforeEach
    void seedAdminActor() {
        if (adminActorId == null) {
            adminActorId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO employees (id, employee_number, full_name, email, password_hash, role, is_active) "
                            + "VALUES (?, 'AUTH-ADMIN', 'Auth Admin', 'auth.admin@adesa.test', ?, 'ADMIN', true)",
                    adminActorId, passwordEncoder.encode("admin-password-123"));
        }
    }

    private static Authentication asAdmin() {
        AuthenticatedEmployee principal = new AuthenticatedEmployee(
                adminActorId, "auth.admin@adesa.test", "Auth Admin", EmployeeRole.ADMIN);
        return new TestingAuthenticationToken(principal, null, "ROLE_ADMIN");
    }

    /**
     * spring-security-test's {@code csrf()} post-processor swaps the real
     * {@code CsrfFilter} token repository for a test double — a global mutation
     * performed by reflection that is never undone. Any test asserting the real
     * {@code XSRF-TOKEN} cookie must put our repository back first, otherwise it
     * would only pass if it happened to run before the first {@code csrf()} use.
     */
    private RequestPostProcessor restoreRealCsrfTokenRepository() {
        return request -> {
            WebTestUtils.setCsrfTokenRepository(request, csrfTokenRepository);
            return request;
        };
    }

    private UUID createEmployee(boolean active, String password) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO employees (id, employee_number, full_name, email, password_hash, role, is_active) "
                        + "VALUES (?, ?, ?, ?, ?, 'EMPLOYEE', ?)",
                id, "EMP-" + id.toString().substring(0, 8), "Test Employee",
                "employee." + id + "@adesa.test", passwordEncoder.encode(password), active);
        return id;
    }

    private String emailFor(UUID employeeId) {
        return "employee." + employeeId + "@adesa.test";
    }

    private MockHttpSession login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    @DisplayName("Valid credentials create a session and /auth/me returns the identity")
    void loginEstablishesSession() throws Exception {
        UUID employeeId = createEmployee(true, "correct-horse-battery-staple");
        String email = emailFor(employeeId);

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password",
                                "correct-horse-battery-staple"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(employeeId.toString()))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("EMPLOYEE"))
                .andExpect(jsonPath("$.device.status").value("NONE"))
                .andReturn();
        // Note: MockMvc does not run a servlet container, so the JSESSIONID
        // Set-Cookie header itself is asserted against the real embedded server
        // in AdesaAttendanceApplicationTests. Here we assert the session works.
        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);
        assertThat(session).isNotNull();

        mockMvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(employeeId.toString()));

        // Sign-in is audited.
        Integer audited = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'LOGIN_SUCCEEDED' AND actor_id = ?",
                Integer.class, employeeId);
        assertThat(audited).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Wrong password is rejected with a generic message and audited")
    void wrongPasswordRejected() throws Exception {
        UUID employeeId = createEmployee(true, "the-real-password-123");

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", emailFor(employeeId), "password", "definitely-wrong"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));

        // Same generic answer for an address that does not exist at all, so the
        // endpoint cannot be used to enumerate accounts.
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "nobody.here@adesa.test", "password", "whatever-12345"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));

        Integer failed = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'LOGIN_FAILED' AND target_id = ?",
                Integer.class, employeeId);
        assertThat(failed).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("An inactive account cannot sign in")
    void inactiveEmployeeRejected() throws Exception {
        UUID employeeId = createEmployee(false, "some-password-1234");

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", emailFor(employeeId), "password", "some-password-1234"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Repeated failed logins are rate limited with 429 and Retry-After")
    void failedLoginsAreRateLimited() throws Exception {
        UUID employeeId = createEmployee(true, "the-real-password-123");
        String email = emailFor(employeeId);

        // auth.login-max-failures defaults to 5 per auth.login-window.
        for (int attempt = 1; attempt <= 5; attempt++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "email", email, "password", "wrong-password-" + attempt))))
                    .andExpect(status().isUnauthorized());
        }

        MvcResult limited = mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email, "password", "still-wrong"))))
                .andExpect(status().isTooManyRequests())
                .andReturn();

        assertThat(limited.getResponse().getHeader("Retry-After")).isNotBlank();

        Integer rateLimited = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'LOGIN_RATE_LIMITED'", Integer.class);
        assertThat(rateLimited).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Sign-out invalidates the session")
    void logoutInvalidatesSession() throws Exception {
        UUID employeeId = createEmployee(true, "logout-password-1234");
        MockHttpSession session = login(emailFor(employeeId), "logout-password-1234");

        mockMvc.perform(post("/api/v1/auth/logout").session(session).with(csrf()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isUnauthorized());

        Integer loggedOut = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'LOGOUT' AND actor_id = ?",
                Integer.class, employeeId);
        assertThat(loggedOut).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("State-changing auth requests require a CSRF token")
    void loginRequiresCsrf() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.test\",\"password\":\"whatever-1234\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("The XSRF-TOKEN cookie issued by /auth/csrf is accepted as the header value")
    void csrfTokenCookieFlow() throws Exception {
        UUID employeeId = createEmployee(true, "cookie-password-1234");

        MvcResult csrfResult = mockMvc.perform(get("/api/v1/auth/csrf")
                        .with(restoreRealCsrfTokenRepository()))
                .andExpect(status().isNoContent())
                .andReturn();

        // The SPA reads this non-HttpOnly cookie and echoes it as a header, so
        // both travel on the state-changing request exactly as a browser would.
        Cookie xsrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
        assertThat(xsrfCookie).as("XSRF-TOKEN cookie").isNotNull();
        assertThat(xsrfCookie.isHttpOnly())
                .as("the SPA must be able to read the CSRF cookie")
                .isFalse();
        String xsrf = xsrfCookie.getValue();

        mockMvc.perform(post("/api/v1/auth/login")
                        .cookie(xsrfCookie)
                        .header("X-XSRF-TOKEN", xsrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", emailFor(employeeId), "password", "cookie-password-1234"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(employeeId.toString()));

        // A stale/incorrect CSRF value is refused even with a valid cookie.
        mockMvc.perform(post("/api/v1/auth/login")
                        .cookie(new Cookie("XSRF-TOKEN", xsrf))
                        .header("X-XSRF-TOKEN", "not-the-right-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", emailFor(employeeId), "password", "cookie-password-1234"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("API responses carry the defensive security headers")
    void securityHeadersArePresent() throws Exception {
        mockMvc.perform(get("/api/v1/auth/csrf").with(restoreRealCsrfTokenRepository()))
                .andExpect(status().isNoContent())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "same-origin"))
                .andExpect(header().string(
                        "Content-Security-Policy",
                        "default-src 'none'; frame-ancestors 'none'; base-uri 'none'"));
    }

    @Test
    @DisplayName("Login request validation returns 400 with field errors")
    void loginValidation() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    @DisplayName("A password reset by an administrator kills the employee's live session")
    void passwordResetInvalidatesExistingSession() throws Exception {
        UUID employeeId = createEmployee(true, "original-password-123");
        MockHttpSession session = login(emailFor(employeeId), "original-password-123");

        mockMvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/v1/admin/employees/{id}", employeeId)
                        .with(authentication(asAdmin()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"a-brand-new-password-1\"}"))
                .andExpect(status().isOk());

        // The session survives the request but is refused from the next one on.
        mockMvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Your session is no longer valid. Please sign in again."));

        // The new password works and issues a fresh session.
        login(emailFor(employeeId), "a-brand-new-password-1");
    }

    @Test
    @DisplayName("Deactivating an account kills its live session")
    void deactivationInvalidatesExistingSession() throws Exception {
        UUID employeeId = createEmployee(true, "active-password-1234");
        MockHttpSession session = login(emailFor(employeeId), "active-password-1234");

        mockMvc.perform(patch("/api/v1/admin/employees/{id}", employeeId)
                        .with(authentication(asAdmin()))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isActive\":false}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Sessions expire after the configured absolute lifetime even if active")
    void absoluteSessionTimeoutIsEnforced() throws Exception {
        UUID employeeId = createEmployee(true, "timeout-password-123");
        MockHttpSession session = login(emailFor(employeeId), "timeout-password-123");

        // Fresh session still works (idle timeout is 30m by default).
        mockMvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isOk());

        // Backdate the login beyond auth.session-absolute-timeout (12h by default)
        // while keeping the session "active" — Spring's idle timeout alone would
        // never expire it.
        session.setAttribute(SessionAttributes.LOGIN_TIME, Instant.now().minus(Duration.ofHours(24)));

        mockMvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Your session has expired. Please sign in again."));
    }

    @Test
    @DisplayName("Unauthenticated callers get 401 from /auth/me")
    void meRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }
}
