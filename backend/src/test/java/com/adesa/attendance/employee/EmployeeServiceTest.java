package com.adesa.attendance.employee;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.adesa.attendance.audit.AuditAction;
import com.adesa.attendance.audit.AuditService;
import com.adesa.attendance.common.BusinessRuleException;
import com.adesa.attendance.common.ConflictException;
import com.adesa.attendance.common.NotFoundException;
import com.adesa.attendance.employee.dto.CreateEmployeeRequest;
import com.adesa.attendance.employee.dto.EmployeeResponse;
import com.adesa.attendance.employee.dto.UpdateEmployeeRequest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Business-rule unit tests for employee management (no database needed).
 */
class EmployeeServiceTest {

    private EmployeeRepository repository;
    private PasswordEncoder passwordEncoder;
    private AuditService auditService;
    private EmployeeService service;

    @BeforeEach
    void setUp() {
        repository = org.mockito.Mockito.mock(EmployeeRepository.class);
        passwordEncoder = org.mockito.Mockito.mock(PasswordEncoder.class);
        auditService = org.mockito.Mockito.mock(AuditService.class);
        service = new EmployeeService(repository, passwordEncoder, auditService);
        when(passwordEncoder.encode(any())).thenReturn("$2a$hashed");
    }

    private Employee existingEmployee() {
        Employee employee = new Employee("EMP-001", "Ada Obi", "ada.obi@adesa.test",
                "$2a$oldhash", EmployeeRole.EMPLOYEE, true);
        return employee;
    }

    @Test
    @DisplayName("Create hashes the password, normalizes the email and audits")
    void createHashesPasswordAndAudits() {
        when(repository.existsByEmployeeNumber("EMP-002")).thenReturn(false);
        when(repository.existsByEmail("john.doe@adesa.test")).thenReturn(false);

        EmployeeResponse response = service.create(
                new CreateEmployeeRequest("EMP-002", "  John Doe ", "John.Doe@adesa.test",
                        "super-secret-pass", EmployeeRole.EMPLOYEE),
                UUID.randomUUID());

        verify(passwordEncoder).encode("super-secret-pass");
        ArgumentCaptor<Employee> captor = ArgumentCaptor.forClass(Employee.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("john.doe@adesa.test");
        assertThat(captor.getValue().isActive()).isTrue();
        assertThat(response.email()).isEqualTo("john.doe@adesa.test");
        assertThat(response.isActive()).isTrue();
        verify(auditService).record(any(), eq(AuditAction.EMPLOYEE_CREATED), eq("EMPLOYEE"), any(), anyMap());
    }

    @Test
    @DisplayName("Create with duplicate employee number is rejected")
    void createDuplicateNumberRejected() {
        when(repository.existsByEmployeeNumber("EMP-001")).thenReturn(true);

        assertThatThrownBy(() -> service.create(
                new CreateEmployeeRequest("EMP-001", "X", "x@adesa.test", "super-secret-pass", EmployeeRole.EMPLOYEE),
                null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("employee number");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Create with duplicate email is rejected")
    void createDuplicateEmailRejected() {
        when(repository.existsByEmployeeNumber("EMP-009")).thenReturn(false);
        when(repository.existsByEmail("dup@adesa.test")).thenReturn(true);

        assertThatThrownBy(() -> service.create(
                new CreateEmployeeRequest("EMP-009", "X", "dup@adesa.test", "super-secret-pass", EmployeeRole.HR),
                null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("email");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Create survives a uniqueness race as a conflict")
    void createRaceBecomesConflict() {
        when(repository.existsByEmployeeNumber("EMP-010")).thenReturn(false);
        when(repository.existsByEmail("race@adesa.test")).thenReturn(false);
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThatThrownBy(() -> service.create(
                new CreateEmployeeRequest("EMP-010", "X", "race@adesa.test", "super-secret-pass", EmployeeRole.EMPLOYEE),
                null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("Update of unknown employee is a 404")
    void updateUnknownEmployee() {
        when(repository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(UUID.randomUUID(), new UpdateEmployeeRequest(null, null, null, null, null), null))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("Update audits role change, deactivation and password reset separately")
    void updateAuditsRoleChangeAndDeactivation() {
        Employee employee = existingEmployee();
        when(repository.findById(employee.getId())).thenReturn(Optional.of(employee));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        EmployeeResponse response = service.update(employee.getId(),
                new UpdateEmployeeRequest(null, null, EmployeeRole.HR, false, "new-super-secret"),
                UUID.randomUUID());

        assertThat(response.role()).isEqualTo(EmployeeRole.HR);
        assertThat(response.isActive()).isFalse();
        verify(auditService).record(any(), eq(AuditAction.EMPLOYEE_UPDATED), eq("EMPLOYEE"), eq(employee.getId()), anyMap());
        verify(auditService).record(any(), eq(AuditAction.EMPLOYEE_ROLE_CHANGED), eq("EMPLOYEE"), eq(employee.getId()), anyMap());
        verify(auditService).record(any(), eq(AuditAction.EMPLOYEE_DEACTIVATED), eq("EMPLOYEE"), eq(employee.getId()), isNull());
        verify(auditService).record(any(), eq(AuditAction.EMPLOYEE_PASSWORD_RESET), eq("EMPLOYEE"), eq(employee.getId()), isNull());
    }

    @Test
    @DisplayName("Update with duplicate email is rejected")
    void updateDuplicateEmailRejected() {
        Employee employee = existingEmployee();
        when(repository.findById(employee.getId())).thenReturn(Optional.of(employee));
        when(repository.existsByEmailAndIdNot("taken@adesa.test", employee.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.update(employee.getId(),
                new UpdateEmployeeRequest(null, "taken@adesa.test", null, null, null), null))
                .isInstanceOf(ConflictException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Update with no effective changes does nothing and does not audit")
    void updateNoChangesIsNoOp() {
        Employee employee = existingEmployee();
        when(repository.findById(employee.getId())).thenReturn(Optional.of(employee));

        EmployeeResponse response = service.update(employee.getId(),
                new UpdateEmployeeRequest(null, null, null, null, null), null);

        assertThat(response.fullName()).isEqualTo("Ada Obi");
        verify(repository, never()).saveAndFlush(any());
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("List maps entities and never returns password material")
    void listNeverExposesPassword() {
        Employee employee = existingEmployee();
        when(repository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(org.springframework.data.domain.Sort.class))).thenReturn(List.of(employee));

        List<EmployeeResponse> results = service.list(null, true, "ada");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).toString()).doesNotContain("password", "hash");
    }

    private Employee existingAdmin() {
        return new Employee("ADM-001", "Site Administrator", "admin@adesa.test",
                "$2a$oldhash", EmployeeRole.ADMIN, true);
    }

    @Test
    @DisplayName("An administrator cannot be deactivated through the normal workflow")
    void adminDeactivationThroughUpdateIsRejected() {
        Employee admin = existingAdmin();
        when(repository.findById(admin.getId())).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> service.update(admin.getId(),
                new UpdateEmployeeRequest(null, null, null, false, null), UUID.randomUUID()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Administrator accounts cannot be deactivated");

        assertThat(admin.isActive()).isTrue();
        verify(repository, never()).saveAndFlush(any());
        verify(auditService).recordInNewTransaction(any(), eq(AuditAction.EMPLOYEE_ACTIVATION_REJECTED),
                eq("EMPLOYEE"), eq(admin.getId()), anyMap());
    }

    @Test
    @DisplayName("The last active administrator cannot lose the ADMIN role")
    void lastActiveAdminRoleChangeIsRejected() {
        Employee admin = existingAdmin();
        when(repository.findById(admin.getId())).thenReturn(Optional.of(admin));
        when(repository.countByRoleAndActiveTrue(EmployeeRole.ADMIN)).thenReturn(1L);

        assertThatThrownBy(() -> service.update(admin.getId(),
                new UpdateEmployeeRequest(null, null, EmployeeRole.EMPLOYEE, null, null), UUID.randomUUID()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("at least one active administrator");

        assertThat(admin.getRole()).isEqualTo(EmployeeRole.ADMIN);
        verify(repository, never()).saveAndFlush(any());
        verify(auditService).recordInNewTransaction(any(), eq(AuditAction.EMPLOYEE_ACTIVATION_REJECTED),
                eq("EMPLOYEE"), eq(admin.getId()), anyMap());
    }

    @Test
    @DisplayName("An administrator can lose the ADMIN role when another active admin remains")
    void adminRoleChangeAllowedWhenAnotherActiveAdminExists() {
        Employee admin = existingAdmin();
        when(repository.findById(admin.getId())).thenReturn(Optional.of(admin));
        when(repository.countByRoleAndActiveTrue(EmployeeRole.ADMIN)).thenReturn(2L);
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        EmployeeResponse response = service.update(admin.getId(),
                new UpdateEmployeeRequest(null, null, EmployeeRole.HR, null, null), UUID.randomUUID());

        assertThat(response.role()).isEqualTo(EmployeeRole.HR);
        verify(auditService).record(any(), eq(AuditAction.EMPLOYEE_ROLE_CHANGED), eq("EMPLOYEE"),
                eq(admin.getId()), anyMap());
    }

    @Test
    @DisplayName("An authorized administrator can reactivate a deactivated administrator")
    void reactivateInactiveAdmin() {
        Employee admin = new Employee("ADM-002", "Second Admin", "second.admin@adesa.test",
                "$2a$oldhash", EmployeeRole.ADMIN, false);
        when(repository.findById(admin.getId())).thenReturn(Optional.of(admin));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        int versionBefore = admin.getCredentialsVersion();

        EmployeeResponse response = service.reactivate(admin.getId(), UUID.randomUUID());

        assertThat(response.isActive()).isTrue();
        assertThat(admin.getCredentialsVersion()).isEqualTo(versionBefore + 1);
        verify(auditService).record(any(), eq(AuditAction.EMPLOYEE_REACTIVATED), eq("EMPLOYEE"),
                eq(admin.getId()), anyMap());
    }

    @Test
    @DisplayName("Reactivating an already-active account is a conflict")
    void reactivateActiveAccountIsConflict() {
        Employee admin = existingAdmin();
        when(repository.findById(admin.getId())).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> service.reactivate(admin.getId(), UUID.randomUUID()))
                .isInstanceOf(ConflictException.class);

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Ordinary employee deactivation and activation continue to work")
    void ordinaryEmployeeDeactivationStillWorks() {
        Employee employee = existingEmployee();
        when(repository.findById(employee.getId())).thenReturn(Optional.of(employee));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        EmployeeResponse deactivated = service.update(employee.getId(),
                new UpdateEmployeeRequest(null, null, null, false, null), UUID.randomUUID());
        assertThat(deactivated.isActive()).isFalse();
        verify(auditService).record(any(), eq(AuditAction.EMPLOYEE_DEACTIVATED), eq("EMPLOYEE"),
                eq(employee.getId()), isNull());

        EmployeeResponse activated = service.reactivate(employee.getId(), UUID.randomUUID());
        assertThat(activated.isActive()).isTrue();
        verify(auditService).record(any(), eq(AuditAction.EMPLOYEE_REACTIVATED), eq("EMPLOYEE"),
                eq(employee.getId()), anyMap());
    }
}
