package com.adesa.attendance.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Locale;

import com.adesa.attendance.accounttoken.PasswordPolicy;
import com.adesa.attendance.audit.AuditAction;
import com.adesa.attendance.audit.AuditService;
import com.adesa.attendance.audit.AuditLogRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import com.adesa.attendance.config.AccountProperties;
import com.adesa.attendance.config.BootstrapAdminProperties;
import com.adesa.attendance.employee.Employee;
import com.adesa.attendance.employee.EmployeeRepository;
import com.adesa.attendance.employee.EmployeeRole;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unit tests for the controlled first-administrator bootstrap.
 */
class FirstAdminBootstrapTest {

    private BootstrapAdminProperties properties;
    private EmployeeRepository repository;
    private PasswordEncoder passwordEncoder;
    private AuditService auditService;
    private FirstAdminBootstrap bootstrap;
    private JdbcTemplate jdbc;
    private AuditLogRepository auditLogs;

    @BeforeEach
    void setUp() {
        properties = new BootstrapAdminProperties();
        jdbc = mock(JdbcTemplate.class);
        auditLogs = mock(AuditLogRepository.class);
        repository = mock(EmployeeRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        auditService = mock(AuditService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        bootstrap = new FirstAdminBootstrap(properties, new PasswordPolicy(new AccountProperties()),
                repository, passwordEncoder, auditService, transactionTemplate, jdbc, auditLogs);
        when(passwordEncoder.encode(any())).thenReturn("$2a$hashed");
    }

    private void requestBootstrap() {
        properties.setEmployeeNumber("ADMIN-001");
        properties.setFullName("Site Admin");
        properties.setEmail("admin@adesa.test");
        properties.setPassword("correct-horse-battery-42");
    }

    @Test
    @DisplayName("No bootstrap requested: nothing is created")
    void noBootstrapRequestedDoesNothing() throws Exception {
        when(repository.existsByRole(EmployeeRole.ADMIN)).thenReturn(false);

        bootstrap.run(null);

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Bootstrap requested but an active admin already exists: skipped")
    void bootstrapSkippedWhenActiveAdminExists() throws Exception {
        requestBootstrap();
        when(repository.existsByRole(EmployeeRole.ADMIN)).thenReturn(true);

        bootstrap.run(null);

        verify(repository, never()).save(any());
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("An inactive administrator prevents another bootstrap")
    void inactiveAdminBlocksBootstrap() {
        requestBootstrap();
        when(repository.existsByRole(EmployeeRole.ADMIN)).thenReturn(true);
        bootstrap.run(null);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("The audit marker prevents bootstrap even after an admin is demoted")
    void completedBootstrapCannotBeRepeated() {
        requestBootstrap();
        when(auditLogs.existsByAction(AuditAction.FIRST_ADMIN_BOOTSTRAPPED)).thenReturn(true);
        bootstrap.run(null);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Bootstrap creates an active ADMIN with a hashed password and audits it")
    void bootstrapCreatesFirstAdmin() throws Exception {
        requestBootstrap();
        when(repository.existsByRole(EmployeeRole.ADMIN)).thenReturn(false);

        bootstrap.run(null);

        ArgumentCaptor<Employee> captor = ArgumentCaptor.forClass(Employee.class);
        var order = org.mockito.Mockito.inOrder(jdbc, repository);
        order.verify(jdbc).execute("LOCK TABLE employees IN EXCLUSIVE MODE");
        order.verify(repository).existsByRole(EmployeeRole.ADMIN);
        verify(repository).save(captor.capture());
        Employee admin = captor.getValue();
        assertThat(admin.getRole()).isEqualTo(EmployeeRole.ADMIN);
        assertThat(admin.isActive()).isTrue();
        assertThat(admin.getEmail()).isEqualTo("admin@adesa.test");
        assertThat(admin.getPasswordHash()).isEqualTo("$2a$hashed");
        assertThat(admin.getPasswordHash()).doesNotContain("correct-horse");
        verify(auditService).record(isNull(), eq(AuditAction.FIRST_ADMIN_BOOTSTRAPPED), eq("EMPLOYEE"),
                any(java.util.UUID.class), anyMap());
    }

    @Test
    @DisplayName("Bootstrap with a short password fails startup")
    void bootstrapShortPasswordFails() {
        requestBootstrap();
        properties.setPassword("short");
        when(repository.existsByRole(EmployeeRole.ADMIN)).thenReturn(false);

        assertThatThrownBy(() -> bootstrap.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("12-128 characters");
    }

    @Test
    @DisplayName("Bootstrap rejects a long password that fails the configured password policy")
    void bootstrapWeakPasswordFails() {
        requestBootstrap();
        properties.setPassword("correct-horse-battery");
        when(repository.existsByRole(EmployeeRole.ADMIN)).thenReturn(false);

        assertThatThrownBy(() -> bootstrap.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least one letter and one digit");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("Bootstrap with email but missing fields fails startup")
    void bootstrapMissingFieldsFails() {
        properties.setEmail("admin@adesa.test");
        when(repository.existsByRole(EmployeeRole.ADMIN)).thenReturn(false);

        assertThatThrownBy(() -> bootstrap.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must all be provided");
    }

    @Test
    @DisplayName("Email is stored lowercase")
    void bootstrapNormalizesEmail() throws Exception {
        requestBootstrap();
        properties.setEmail("Admin@Adesa.TEST");
        when(repository.existsByRole(EmployeeRole.ADMIN)).thenReturn(false);

        bootstrap.run(null);

        ArgumentCaptor<Employee> captor = ArgumentCaptor.forClass(Employee.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getEmail())
                .isEqualTo("admin@adesa.test".toLowerCase(Locale.ROOT));
    }

    @Test
    @DisplayName("A data-integrity race during bootstrap is not swallowed")
    void bootstrapRacePropagates() {
        requestBootstrap();
        when(repository.existsByRole(EmployeeRole.ADMIN)).thenReturn(false);
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("admin already inserted"));

        assertThatThrownBy(() -> bootstrap.run(null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
