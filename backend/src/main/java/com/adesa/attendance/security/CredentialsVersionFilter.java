package com.adesa.attendance.security;

import java.io.IOException;
import java.util.Objects;

import com.adesa.attendance.employee.Employee;
import com.adesa.attendance.employee.EmployeeRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Immediately invalidates a session when the employee's credentials change or
 * the account is deactivated.
 *
 * <p>Every session records the {@code credentials_version} it was created with.
 * If that version no longer matches the database value — because an
 * administrator reset the password, changed the role, deactivated the account
 * or revoked the employee's device — the session is destroyed and the caller
 * receives 401. This is what makes "invalidate sessions on credential
 * revocation" true without a separate blocklist.
 *
 * <p>The check only applies to sessions established by this application's own
 * login flow, which stamps {@link SessionAttributes#LOGIN_TIME} together with
 * {@link SessionAttributes#CREDENTIALS_VERSION}. A session without that marker
 * was not created by {@code AuthService} — in production that cannot carry an
 * authenticated principal (form login and HTTP Basic are disabled, so login is
 * the only way to authenticate) — so it is left alone rather than misjudged.
 *
 * <p>Not a {@code @Component}: it is instantiated and added to the Spring
 * Security chain only, so it does not also run as a plain servlet filter.
 */
public class CredentialsVersionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CredentialsVersionFilter.class);

    private final EmployeeRepository employeeRepository;
    private final SecurityErrorWriter errorWriter;

    public CredentialsVersionFilter(EmployeeRepository employeeRepository, SecurityErrorWriter errorWriter) {
        this.employeeRepository = employeeRepository;
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        HttpSession session = request.getSession(false);

        if (authentication != null
                && authentication.getPrincipal() instanceof AuthenticatedEmployee principal
                && isManagedSession(session)) {
            if (isStale(session, principal)) {
                log.warn("Rejecting session for employee {}: credentials are no longer valid", principal.employeeId());
                session.invalidate();
                SecurityContextHolder.clearContext();
                errorWriter.write(request, response, HttpStatus.UNAUTHORIZED,
                        "Your session is no longer valid. Please sign in again.");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * True when the session was established by this application's login and is
     * therefore governed by this filter's session guards.
     */
    private boolean isManagedSession(HttpSession session) {
        return session != null && session.getAttribute(SessionAttributes.LOGIN_TIME) != null;
    }

    private boolean isStale(HttpSession session, AuthenticatedEmployee principal) {
        Employee employee = employeeRepository.findById(principal.employeeId()).orElse(null);
        if (employee == null || !employee.isActive()) {
            return true;
        }
        Object sessionVersion = session.getAttribute(SessionAttributes.CREDENTIALS_VERSION);
        return !(sessionVersion instanceof Integer version)
                || !Objects.equals(version, employee.getCredentialsVersion());
    }
}
