package com.adesa.attendance.employee;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.adesa.attendance.audit.AuditAction;
import com.adesa.attendance.audit.AuditService;
import com.adesa.attendance.accounttoken.AccountPasswordService;
import com.adesa.attendance.accounttoken.PasswordPolicy;
import com.adesa.attendance.common.BadRequestException;
import com.adesa.attendance.common.BusinessRuleException;
import com.adesa.attendance.common.ConflictException;
import com.adesa.attendance.common.NotFoundException;
import com.adesa.attendance.employee.dto.CreateEmployeeRequest;
import com.adesa.attendance.employee.dto.EmployeeResponse;
import com.adesa.attendance.employee.dto.UpdateEmployeeRequest;

import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Employee account management. All changes are audited; passwords are stored
 * only as BCrypt hashes.
 */
@Service
public class EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final PasswordPolicy passwordPolicy;
    private final AccountPasswordService accountPasswordService;
    private static final SecureRandom RANDOM = new SecureRandom();

    public EmployeeService(EmployeeRepository employeeRepository, PasswordEncoder passwordEncoder,
                           AuditService auditService, PasswordPolicy passwordPolicy,
                           AccountPasswordService accountPasswordService) {
        this.employeeRepository = employeeRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.passwordPolicy = passwordPolicy;
        this.accountPasswordService = accountPasswordService;
    }

    @Transactional
    public EmployeeResponse create(CreateEmployeeRequest request, UUID actorId) {
        String employeeNumber = request.employeeNumber().trim();
        String email = normalizeEmail(request.email());

        if (employeeRepository.existsByEmployeeNumber(employeeNumber)) {
            throw new ConflictException("An employee with this employee number already exists");
        }
        if (employeeRepository.existsByEmail(email)) {
            throw new ConflictException("An employee with this email already exists");
        }

        boolean adminSuppliedPassword = StringUtils.hasText(request.password());
        String initialHash;
        if (adminSuppliedPassword) {
            passwordPolicy.enforce(request.password());
            initialHash = passwordEncoder.encode(request.password());
        } else {
            // No usable password: a random, never-disclosed placeholder hash.
            // The account can only be entered through the emailed setup link.
            initialHash = passwordEncoder.encode(randomPlaceholder());
        }

        Employee employee = new Employee(
                employeeNumber,
                request.fullName().trim(),
                email,
                initialHash,
                request.role(),
                true);
        // The account is not usable until the employee has chosen their own
        // password through the setup link, regardless of how it was created.
        employee.setMustChangePassword(true);

        try {
            employeeRepository.saveAndFlush(employee);
        } catch (DataIntegrityViolationException ex) {
            // Race-safe duplicate handling.
            throw new ConflictException("An employee with this employee number or email already exists");
        }

        auditService.record(actorId, AuditAction.EMPLOYEE_CREATED, "EMPLOYEE", employee.getId(),
                Map.of(
                        "employeeNumber", employee.getEmployeeNumber(),
                        "email", employee.getEmail(),
                        "role", employee.getRole().name(),
                        "passwordSuppliedByAdmin", adminSuppliedPassword));
        // Issue the one-time setup link (delivery failure is logged, not fatal).
        accountPasswordService.issueAccountSetup(employee, actorId);
        return EmployeeResponse.from(employee);
    }

    @Transactional(readOnly = true)
    public EmployeeResponse get(UUID id) {
        return EmployeeResponse.from(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public List<EmployeeResponse> list(EmployeeRole role, Boolean active, String search) {
        Specification<Employee> specification = Specification.where(null);
        if (role != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("role"), role));
        }
        if (active != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("active"), active));
        }
        if (StringUtils.hasText(search)) {
            String pattern = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
            specification = specification.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("fullName")), pattern),
                    cb.like(cb.lower(root.get("email")), pattern),
                    cb.like(cb.lower(root.get("employeeNumber")), pattern)));
        }
        return employeeRepository.findAll(specification, Sort.by(Sort.Direction.ASC, "fullName")).stream()
                .map(EmployeeResponse::from)
                .toList();
    }

    @Transactional
    public EmployeeResponse update(UUID id, UpdateEmployeeRequest request, UUID actorId) {
        Employee employee = findOrThrow(id);
        List<String> changedFields = new ArrayList<>();
        Map<String, Object> details = new LinkedHashMap<>();

        if (StringUtils.hasText(request.fullName())) {
            String fullName = request.fullName().trim();
            if (!fullName.equals(employee.getFullName())) {
                employee.setFullName(fullName);
                changedFields.add("fullName");
            }
        }
        if (StringUtils.hasText(request.email())) {
            String email = normalizeEmail(request.email());
            if (!email.equals(employee.getEmail())) {
                if (employeeRepository.existsByEmailAndIdNot(email, id)) {
                    throw new ConflictException("An employee with this email already exists");
                }
                employee.setEmail(email);
                changedFields.add("email");
            }
        }
        if (request.role() != null && request.role() != employee.getRole()) {
            if (employee.getRole() == EmployeeRole.ADMIN && employee.isActive()
                    && request.role() != EmployeeRole.ADMIN
                    && employeeRepository.countByRoleAndActiveTrue(EmployeeRole.ADMIN) <= 1) {
                // Changing the role of the last active administrator would leave
                // the system without any active administrator.
                rejectActivationChange(actorId, employee, "LAST_ACTIVE_ADMIN_PROTECTED",
                        "The last active administrator cannot lose the ADMIN role.");
                throw new BusinessRuleException(
                        "The system must always have at least one active administrator. "
                                + "Create or activate another administrator first.");
            }
            EmployeeRole previousRole = employee.getRole();
            employee.setRole(request.role());
            changedFields.add("role");
            details.put("previousRole", previousRole.name());
            details.put("newRole", request.role().name());
        }
        if (request.isActive() != null && request.isActive() != employee.isActive()) {
            if (!request.isActive() && employee.getRole() == EmployeeRole.ADMIN) {
                // Administrators must not be deactivated through the normal
                // employee-management workflow. Reactivation of an already
                // inactive administrator has its own dedicated, audited action.
                rejectActivationChange(actorId, employee, "ADMIN_DEACTIVATION_NOT_ALLOWED",
                        "Administrator accounts cannot be deactivated through employee management.");
                throw new BusinessRuleException(
                        "Administrator accounts cannot be deactivated through employee management. "
                                + "Use the dedicated administrator reactivation/deactivation process.");
            }
            employee.setActive(request.isActive());
            changedFields.add("isActive");
        }
        if (StringUtils.hasText(request.password())) {
            passwordPolicy.enforce(request.password());
            employee.setPasswordHash(passwordEncoder.encode(request.password()));
            // A password set by an administrator is temporary: the employee
            // must choose their own at the next sign-in.
            employee.setMustChangePassword(true);
            changedFields.add("password");
            details.put("passwordChanged", true);
        }

        if (changedFields.isEmpty()) {
            return EmployeeResponse.from(employee);
        }

        // Password, role and activation changes must invalidate existing
        // sessions; the credentials version is compared by a session filter.
        if (changedFields.contains("password") || changedFields.contains("role")
                || changedFields.contains("isActive")) {
            employee.bumpCredentialsVersion();
        }

        try {
            employeeRepository.saveAndFlush(employee);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("The request conflicts with existing employee data");
        }

        details.put("changedFields", List.copyOf(changedFields));
        auditService.record(actorId, AuditAction.EMPLOYEE_UPDATED, "EMPLOYEE", id, details);

        if (changedFields.contains("role")) {
            auditService.record(actorId, AuditAction.EMPLOYEE_ROLE_CHANGED, "EMPLOYEE", id,
                    Map.of("previousRole", details.get("previousRole"), "newRole", details.get("newRole")));
        }
        if (changedFields.contains("isActive")) {
            auditService.record(actorId,
                    employee.isActive() ? AuditAction.EMPLOYEE_ACTIVATED : AuditAction.EMPLOYEE_DEACTIVATED,
                    "EMPLOYEE", id, null);
        }
        if (changedFields.contains("password")) {
            auditService.record(actorId, AuditAction.EMPLOYEE_PASSWORD_RESET, "EMPLOYEE", id, null);
        }
        return EmployeeResponse.from(employee);
    }

    /**
     * Reactivates a previously deactivated account.
     *
     * <p>This is the only path that may restore an <strong>administrator</strong>
     * account: the normal employee-management workflow deliberately refuses to
     * deactivate administrators, and this action is the counterpart that lets an
     * authorized administrator bring an inactive administrator back. It only
     * changes the active flag — it never deletes or alters attendance records —
     * and it bumps the credentials version so any stale session is invalidated
     * by the existing session filters.
     *
     * @throws ConflictException when the account is already active (nothing to
     *                           do)
     */
    @Transactional
    public EmployeeResponse reactivate(UUID id, UUID actorId) {
        Employee employee = findOrThrow(id);
        if (employee.isActive()) {
            throw new ConflictException("This account is already active.");
        }

        employee.setActive(true);
        // An activation change must invalidate any stale sessions, exactly like
        // deactivation does.
        employee.bumpCredentialsVersion();
        employeeRepository.saveAndFlush(employee);

        auditService.record(actorId, AuditAction.EMPLOYEE_REACTIVATED, "EMPLOYEE", id,
                Map.of("role", employee.getRole().name(),
                        "previousActive", false,
                        "reason", "ADMIN_ACCOUNT_REACTIVATION"));
        return EmployeeResponse.from(employee);
    }

    /**
     * Records a refused activation/role-change attempt in the audit trail. The
     * entry is written in its own transaction so it survives the rejected
     * request.
     */
    private void rejectActivationChange(UUID actorId, Employee target, String reason, String message) {
        auditService.recordInNewTransaction(actorId, AuditAction.EMPLOYEE_ACTIVATION_REJECTED,
                "EMPLOYEE", target.getId(),
                Map.of("reason", reason, "message", message, "targetRole", target.getRole().name()));
    }

    /**
     * Re-sends the account-setup link for an employee whose first link expired.
     * ADMIN only (enforced by the controller).
     */
    @Transactional
    public EmployeeResponse resendSetup(UUID id, UUID actorId) {
        Employee employee = findOrThrow(id);
        accountPasswordService.resendAccountSetup(id, actorId);
        return EmployeeResponse.from(employee);
    }

    private Employee findOrThrow(UUID id) {
        return employeeRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Employee not found"));
    }

    /**
     * A cryptographically random value used as the stored password hash for an
     * account created without a password. It is never disclosed anywhere, so
     * the account is unreachable by password until the setup link is used.
     */
    private static String randomPlaceholder() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
