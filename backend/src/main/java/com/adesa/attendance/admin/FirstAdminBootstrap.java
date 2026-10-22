package com.adesa.attendance.admin;

import java.util.Map;

import com.adesa.attendance.accounttoken.PasswordPolicy;
import com.adesa.attendance.audit.AuditAction;
import com.adesa.attendance.audit.AuditService;
import com.adesa.attendance.audit.AuditLogRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import com.adesa.attendance.common.BadRequestException;
import com.adesa.attendance.config.BootstrapAdminProperties;
import com.adesa.attendance.employee.Employee;
import com.adesa.attendance.employee.EmployeeRepository;
import com.adesa.attendance.employee.EmployeeRole;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Creates the first ADMIN account in a controlled way at startup.
 *
 * <p>Runs only when bootstrap values are supplied via environment variables
 * AND no ADMIN account exists. Never overwrites or duplicates an existing
 * administrator, and never runs without explicit configuration.
 */
@Component
public class FirstAdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FirstAdminBootstrap.class);

    private final BootstrapAdminProperties properties;
    private final PasswordPolicy passwordPolicy;
    private final EmployeeRepository employeeRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbc;
    private final AuditLogRepository auditLogs;

    public FirstAdminBootstrap(BootstrapAdminProperties properties, PasswordPolicy passwordPolicy,
                               EmployeeRepository employeeRepository, PasswordEncoder passwordEncoder,
                               AuditService auditService,
                               TransactionTemplate transactionTemplate, JdbcTemplate jdbc, AuditLogRepository auditLogs) {
        this.properties = properties;
        this.passwordPolicy = passwordPolicy;
        this.employeeRepository = employeeRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.transactionTemplate = transactionTemplate;
        this.jdbc = jdbc;
        this.auditLogs = auditLogs;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isRequested()) {
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            // Serialize all initializers, including different emails. The lock is
            // released on commit/rollback, and also excludes employee writes.
            jdbc.execute("LOCK TABLE employees IN EXCLUSIVE MODE");
            if (employeeRepository.existsByRole(EmployeeRole.ADMIN)
                    || auditLogs.existsByAction(AuditAction.FIRST_ADMIN_BOOTSTRAPPED)) {
                log.info("First-admin bootstrap already completed or an administrator exists; skipping. "
                        + "Unset the ADESA_BOOTSTRAP_ADMIN_* variables.");
                return;
            }
            validate();
            Employee admin = new Employee(
                    properties.getEmployeeNumber().trim(),
                    properties.getFullName().trim(),
                    properties.getEmail().trim().toLowerCase(java.util.Locale.ROOT),
                    passwordEncoder.encode(properties.getPassword()),
                    EmployeeRole.ADMIN,
                    true);
            admin.setMustChangePassword(true);
            employeeRepository.save(admin);
            auditService.record(null, AuditAction.FIRST_ADMIN_BOOTSTRAPPED, "EMPLOYEE", admin.getId(),
                    Map.of("employeeNumber", admin.getEmployeeNumber(), "email", admin.getEmail()));
            log.info("First administrator created: employeeNumber={}, email={}",
                    admin.getEmployeeNumber(), admin.getEmail());
        });
    }

    private void validate() {
        if (!StringUtils.hasText(properties.getEmployeeNumber())
                || !StringUtils.hasText(properties.getFullName())
                || !StringUtils.hasText(properties.getPassword())) {
            throw new IllegalStateException(
                    "Admin bootstrap requested via ADESA_BOOTSTRAP_ADMIN_EMAIL but "
                            + "employee number, full name and password must all be provided.");
        }
        String password = properties.getPassword();
        if (password.length() < 12 || password.length() > 128) {
            throw new IllegalStateException(
                    "Admin bootstrap password must be 12-128 characters.");
        }
        try {
            passwordPolicy.enforce(password);
        } catch (BadRequestException ex) {
            throw new IllegalStateException(
                    "Admin bootstrap password does not meet the configured password policy: "
                            + ex.getMessage(),
                    ex);
        }
    }
}
