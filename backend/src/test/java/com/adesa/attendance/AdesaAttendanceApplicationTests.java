package com.adesa.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Phase 1 smoke test against a real PostgreSQL container.
 *
 * <p>Verifies that the application context loads, Flyway migrations apply
 * cleanly, and the health endpoint is reachable without authentication while
 * everything else requires a session.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class AdesaAttendanceApplicationTests {

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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("Application context loads")
    void contextLoads() {
    }

    @Test
    @DisplayName("Flyway migrations were applied successfully")
    void flywayMigrationsApplied() {
        List<Map<String, Object>> applied = jdbcTemplate.queryForList(
                "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank");
        assertThat(applied).isNotEmpty();
        assertThat(applied).allSatisfy(row ->
                assertThat(row.get("success")).isEqualTo(Boolean.TRUE));
        assertThat(applied.getFirst().get("version")).isEqualTo("1");
        assertThat(applied.getFirst().get("description").toString()).contains("employees");
    }

    @Test
    @DisplayName("Employees table matches the expected Phase 1 schema")
    void employeesTableExists() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'employees'");
        assertThat(columns).extracting(row -> row.get("column_name").toString())
                .contains("id", "employee_number", "full_name", "email",
                        "password_hash", "role", "is_active", "created_at", "updated_at");
    }

    @Test
    @DisplayName("Health endpoint is public, other endpoints require authentication")
    void healthIsPublicOtherEndpointsAreProtected() {
        var health = restTemplate.getForEntity("/actuator/health", String.class);
        assertThat(health.getStatusCode().is2xxSuccessful()).isTrue();

        var api = restTemplate.getForEntity("/api/v1/anything", String.class);
        assertThat(api.getStatusCode().value()).isIn(401, 403);
    }

    @Test
    @DisplayName("Session and CSRF cookies carry the expected flags on a real server")
    void sessionAndCsrfCookieFlags() {
        UUID employeeId = UUID.randomUUID();
        String email = "smoke." + employeeId + "@adesa.test";
        jdbcTemplate.update(
                "INSERT INTO employees (id, employee_number, full_name, email, password_hash, role, is_active) "
                        + "VALUES (?, 'SMOKE-001', 'Smoke Employee', ?, ?, 'EMPLOYEE', true)",
                employeeId, email, passwordEncoder.encode("smoke-password-1234"));

        // 1. The CSRF cookie must be readable by the SPA, so it is not HttpOnly.
        var csrf = restTemplate.getForEntity("/api/v1/auth/csrf", String.class);
        assertThat(csrf.getStatusCode().value()).isEqualTo(204);
        String csrfSetCookie = csrf.getHeaders().getFirst("Set-Cookie");
        assertThat(csrfSetCookie).startsWith("XSRF-TOKEN=");
        assertThat(csrfSetCookie).doesNotContain("HttpOnly");
        String xsrf = csrfSetCookie.substring("XSRF-TOKEN=".length(), csrfSetCookie.indexOf(';'));

        // 2. Sign in exactly as the SPA does: cookie plus header, both echoed back.
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-XSRF-TOKEN", xsrf);
        headers.set(HttpHeaders.COOKIE, "XSRF-TOKEN=" + xsrf);
        var login = restTemplate.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>("{\"email\":\"" + email + "\",\"password\":\"smoke-password-1234\"}", headers),
                String.class);

        assertThat(login.getStatusCode().value()).isEqualTo(200);
        String sessionCookie = login.getHeaders().getFirst("Set-Cookie");
        assertThat(sessionCookie).startsWith("JSESSIONID=");
        assertThat(sessionCookie).contains("HttpOnly");
        assertThat(sessionCookie).contains("SameSite=Lax");
        assertThat(sessionCookie).contains("Path=/");
        // The server keeps the session server-side; no token is handed to the client.
        assertThat(login.getBody()).contains(email);
        assertThat(login.getBody()).doesNotContainPattern("eyJ[A-Za-z0-9_-]{10,}");
    }
}
