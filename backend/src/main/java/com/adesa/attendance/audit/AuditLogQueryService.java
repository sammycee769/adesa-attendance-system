package com.adesa.attendance.audit;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.adesa.attendance.audit.dto.AuditLogResponse;
import com.adesa.attendance.common.PageResponse;
import com.adesa.attendance.employee.Employee;
import com.adesa.attendance.employee.EmployeeRepository;
import com.adesa.attendance.employee.EmployeeRole;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading the audit trail.
 *
 * <p>Administrators see every entry. HR sees the entries that are relevant to
 * attendance and devices only: the master prompt gives HR "view relevant audit
 * records" and forbids HR from receiving unrestricted system-administration
 * privileges, so the restriction is applied here, on the server, and cannot be
 * lifted with a query parameter.
 */
@Service
public class AuditLogQueryService {

    /** The actions HR is allowed to see. */
    static final Set<AuditAction> HR_VISIBLE_ACTIONS = EnumSet.of(
            AuditAction.ATTENDANCE_SIGNED_IN,
            AuditAction.ATTENDANCE_SIGNED_OUT,
            AuditAction.ATTENDANCE_ACTION_REJECTED,
            AuditAction.ATTENDANCE_CORRECTED,
            AuditAction.DEVICE_REGISTERED,
            AuditAction.DEVICE_APPROVED,
            AuditAction.DEVICE_REVOKED,
            AuditAction.DEVICE_REPLACED,
            AuditAction.DEVICE_AUTHENTICATED);

    private final AuditLogRepository auditLogRepository;
    private final EmployeeRepository employeeRepository;

    public AuditLogQueryService(AuditLogRepository auditLogRepository, EmployeeRepository employeeRepository) {
        this.auditLogRepository = auditLogRepository;
        this.employeeRepository = employeeRepository;
    }

    /**
     * A page of audit entries.
     *
     * @param actorRole the role of the requester; it decides which actions are visible
     */
    @Transactional(readOnly = true)
    public PageResponse<AuditLogResponse> list(EmployeeRole actorRole, AuditAction action, String targetType,
                                               UUID actorId, Instant from, Instant to, int page, int size) {
        Set<AuditAction> visible = visibleActions(actorRole);
        if (action != null && !visible.contains(action)) {
            // The requested action is not visible for this role: return an empty
            // page instead of revealing that the action exists.
            return PageResponse.of(List.of(), page, size, 0);
        }

        Specification<AuditLog> specification = Specification.where(null);
        specification = specification.and((root, query, cb) -> root.get("action").in(visible));
        if (action != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("action"), action));
        }
        if (targetType != null && !targetType.isBlank()) {
            specification = specification.and(
                    (root, query, cb) -> cb.equal(root.get("targetType"), targetType.trim()));
        }
        if (actorId != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("actorId"), actorId));
        }
        if (from != null) {
            specification = specification.and(
                    (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from));
        }
        if (to != null) {
            specification = specification.and(
                    (root, query, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), to));
        }

        Page<AuditLog> entries = auditLogRepository.findAll(specification,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));

        Map<UUID, Employee> actors = actorsById(entries.getContent().stream()
                .map(AuditLog::getActorId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList());

        List<AuditLogResponse> mapped = entries.getContent().stream()
                .map(entry -> {
                    Employee actor = entry.getActorId() != null ? actors.get(entry.getActorId()) : null;
                    return AuditLogResponse.from(entry,
                            actor != null ? actor.getEmployeeNumber() : null,
                            actor != null ? actor.getFullName() : null);
                })
                .toList();

        return PageResponse.of(mapped, page, size, entries.getTotalElements());
    }

    /** The actions a role may see. */
    Set<AuditAction> visibleActions(EmployeeRole role) {
        if (role == EmployeeRole.ADMIN) {
            return EnumSet.allOf(AuditAction.class);
        }
        if (role == EmployeeRole.HR) {
            return EnumSet.copyOf(HR_VISIBLE_ACTIONS);
        }
        return EnumSet.noneOf(AuditAction.class);
    }

    private Map<UUID, Employee> actorsById(List<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return employeeRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Employee::getId, Function.identity()));
    }
}
