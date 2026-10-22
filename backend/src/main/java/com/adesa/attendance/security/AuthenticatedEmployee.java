package com.adesa.attendance.security;

import java.util.UUID;

import com.adesa.attendance.employee.EmployeeRole;

import org.springframework.security.core.Authentication;

/**
 * Authenticated identity extracted from the server-side session principal.
 *
 * <p>Phase 2 note: during Phase 3 the session principal becomes an
 * {@code AuthenticatedEmployee} built at login. Until then, tests inject this
 * principal directly. Attendance identity is always derived from this
 * principal — never from request bodies.
 */
public record AuthenticatedEmployee(UUID employeeId, String email, String fullName, EmployeeRole role) {

    /**
     * Best-effort actor extraction; returns null for anonymous or unrecognized
     * principals (audit entries then record a system actor).
     */
    public static UUID actorId(Authentication authentication) {
        if (authentication == null || authentication.getPrincipal() == null) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof AuthenticatedEmployee employee) {
            return employee.employeeId();
        }
        return null;
    }

    public static AuthenticatedEmployee from(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedEmployee employee)) {
            throw new IllegalStateException("No authenticated employee in the security context");
        }
        return employee;
    }
}
