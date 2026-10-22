package com.adesa.attendance.security;

import java.io.IOException;
import java.util.Set;

import com.adesa.attendance.employee.Employee;
import com.adesa.attendance.employee.EmployeeRepository;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Server-side enforcement of the forced first-login password change.
 *
 * <p>An account with {@code must_change_password = true} may reach only the
 * endpoints it needs to become usable: read its own identity, change the
 * password, sign out and bootstrap CSRF. Every other request — including every
 * administrative action — is refused with HTTP 403 and a machine-readable code
 * so the SPA can route the user to the change-password screen. Hiding the
 * dashboard in React is not the control; this filter is.
 *
 * <p>The allowlist is method-aware: {@code GET /auth/me} is allowed,
 * {@code POST /auth/change-password} and {@code POST /auth/logout} are allowed
 * (they are also CSRF-protected), and nothing else under {@code /api} is.
 */
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {

    /** Endpoints reachable while a password change is required. */
    private static final Set<String> ALLOWED_PATHS = Set.of(
            "/api/v1/auth/me",
            "/api/v1/auth/change-password",
            "/api/v1/auth/logout",
            "/api/v1/auth/csrf");

    private final EmployeeRepository employeeRepository;
    private final SecurityErrorWriter errorWriter;

    public PasswordChangeRequiredFilter(EmployeeRepository employeeRepository, SecurityErrorWriter errorWriter) {
        this.employeeRepository = employeeRepository;
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.getPrincipal() instanceof AuthenticatedEmployee principal
                && requiresPasswordChange(principal)
                && !isAllowed(request)) {
            errorWriter.write(request, response, HttpStatus.FORBIDDEN,
                    "You must set a new password before continuing.");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean requiresPasswordChange(AuthenticatedEmployee principal) {
        Employee employee = employeeRepository.findById(principal.employeeId()).orElse(null);
        return employee != null && employee.isMustChangePassword();
    }

    private boolean isAllowed(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!ALLOWED_PATHS.contains(path)) {
            return false;
        }
        String method = request.getMethod();
        return "GET".equals(method) || "POST".equals(method);
    }
}
