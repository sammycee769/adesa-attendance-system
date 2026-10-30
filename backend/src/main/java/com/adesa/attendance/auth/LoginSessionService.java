package com.adesa.attendance.auth;

import java.util.UUID;

import com.adesa.attendance.audit.AuditAction;
import com.adesa.attendance.audit.AuditService;
import com.adesa.attendance.common.UnauthorizedException;
import com.adesa.attendance.employee.Employee;
import com.adesa.attendance.employee.EmployeeRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Commits a distinct session version for each successful login. */
@Service
public class LoginSessionService {
    private final EmployeeRepository employees;
    private final AuditService audit;

    public LoginSessionService(EmployeeRepository employees, AuditService audit) {
        this.employees = employees;
        this.audit = audit;
    }

    @Transactional
    public Employee replaceSession(UUID employeeId, String verifiedPasswordHash) {
        Employee employee = employees.findForLogin(employeeId)
                .orElseThrow(() -> new UnauthorizedException("Invalid email or password"));
        // Recheck after taking the lock: a password reset or deactivation may
        // have occurred since AuthService verified the supplied password.
        if (!employee.isActive() || !employee.getPasswordHash().equals(verifiedPasswordHash)) {
            throw new UnauthorizedException("Invalid email or password");
        }
        employee.bumpCredentialsVersion();
        employees.saveAndFlush(employee);
        audit.record(employeeId, AuditAction.LOGIN_SUCCEEDED, "EMPLOYEE", employeeId, null);
        return employee;
    }
}
