package com.adesa.attendance.admin;

import java.time.Instant;
import java.util.UUID;

import com.adesa.attendance.audit.AuditAction;
import com.adesa.attendance.audit.AuditLogQueryService;
import com.adesa.attendance.audit.dto.AuditLogResponse;
import com.adesa.attendance.common.PageResponse;
import com.adesa.attendance.employee.EmployeeRole;
import com.adesa.attendance.security.AuthenticatedEmployee;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * The audit trail.
 *
 * <p>Administrators see every entry. HR sees the attendance- and device-related
 * entries only; that restriction is applied by the service, so it does not
 * depend on the frontend hiding anything.
 */
@RestController
@RequestMapping("/api/v1/admin/audit-logs")
@Validated
@Tag(name = "Admin: Audit log", description = "Audit trail (ADMIN: all entries, HR: attendance and device entries)")
public class AdminAuditLogController {

    private final AuditLogQueryService auditLogQueryService;

    public AdminAuditLogController(AuditLogQueryService auditLogQueryService) {
        this.auditLogQueryService = auditLogQueryService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('HR','ADMIN')")
    @Operation(summary = "List audit entries",
            description = "Requires HR or ADMIN. ADMIN sees every action; HR sees the attendance and device "
                    + "actions only. Optional filters: action, targetType, actorId, from, to.")
    public PageResponse<AuditLogResponse> list(
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size,
            Authentication authentication) {
        EmployeeRole role = AuthenticatedEmployee.from(authentication).role();
        return auditLogQueryService.list(role, action, targetType, actorId, from, to, page, size);
    }
}
