package com.adesa.attendance.common;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.adesa.attendance.employee.EmployeeRole;
import com.adesa.attendance.security.AuthenticatedEmployee;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The error contract of the API.
 *
 * <p>An unmapped path is a client mistake and must be answered with 404, not
 * with the 500 that the catch-all handler would otherwise produce: a 500
 * hides the real cause, pollutes the error metrics and makes the frontend
 * show "the request could not be completed" instead of "not found".
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class GlobalExceptionHandlerTest {

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

    @Test
    @DisplayName("An unmapped API path answers 404 with the standard error body")
    void unknownPathAnswersNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/definitely-not-a-real-endpoint").with(authentication(employeeAuth())))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.path").value("/api/v1/definitely-not-a-real-endpoint"))
                // The body must stay generic: no stack trace, no internals.
                .andExpect(jsonPath("$.message").value("The requested endpoint does not exist"));
    }

    @Test
    @DisplayName("An unmapped path never leaks a stack trace")
    void unknownPathDoesNotLeakInternals() throws Exception {
        String body = mockMvc.perform(get("/api/v1/definitely-not-a-real-endpoint").with(authentication(employeeAuth())))
                .andReturn()
                .getResponse()
                .getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body)
                .doesNotContain("org.springframework")
                .doesNotContain("Exception")
                .doesNotContain("java.");
    }

    /** A signed-in EMPLOYEE principal; the identity comes from the session, not the request. */
    private static TestingAuthenticationToken employeeAuth() {
        AuthenticatedEmployee principal = new AuthenticatedEmployee(
                UUID.randomUUID(), "test.employee@adesa.test", "Test Employee", EmployeeRole.EMPLOYEE);
        return new TestingAuthenticationToken(principal, null, "ROLE_EMPLOYEE");
    }
}
