package com.adesa.attendance.admin;

import java.util.Map;

import com.adesa.attendance.audit.AuditAction;
import com.adesa.attendance.audit.AuditService;
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
    private final EmployeeRepository employeeRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final TransactionTemplate transactionTemplate;

    public FirstAdminBootstrap(BootstrapAdminProperties properties, EmployeeRepository employeeRepository,
                               PasswordEncoder passwordEncoder, AuditService auditService,
                               TransactionTemplate transactionTemplate) {
        this.properties = properties;
        this.employeeRepository = employeeRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        // An administrator exists only if one is ACTIVE: an inactive
        // administrator cannot authorize anything, so it must not block the
        // controlled recovery path that establishes a working administrator.
        boolean activeAdminExists = transactionTemplate.execute(
                status -> employeeRepository.existsByRoleAndActiveTrue(EmployeeRole.ADMIN));

        if (!properties.isRequested()) {
            if (Boolean.FALSE.equals(activeAdminExists)) {
                log.warn("No ACTIVE ADMIN account exists. To create the first administrator, set "
                        + "ADESA_BOOTSTRAP_ADMIN_EMPLOYEE_NUMBER, ADESA_BOOTSTRAP_ADMIN_FULL_NAME, "
                        + "ADESA_BOOTSTRAP_ADMIN_EMAIL and ADESA_BOOTSTRAP_ADMIN_PASSWORD, then restart. "
                        + "Unset them again after the account is created.");
            }
            return;
        }
        if (Boolean.TRUE.equals(activeAdminExists)) {
            log.info("Admin bootstrap requested but an ACTIVE ADMIN account already exists; skipping. "
                    + "Unset the ADESA_BOOTSTRAP_ADMIN_* variables.");
            return;
        }
        validate();
        transactionTemplate.executeWithoutResult(status -> {
            Employee admin = new Employee(
                    properties.getEmployeeNumber().trim(),
                    properties.getFullName().trim(),
                    properties.getEmail().trim().toLowerCase(java.util.Locale.ROOT),
                    passwordEncoder.encode(properties.getPassword()),
                    EmployeeRole.ADMIN,
                    true);
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
    }
}
