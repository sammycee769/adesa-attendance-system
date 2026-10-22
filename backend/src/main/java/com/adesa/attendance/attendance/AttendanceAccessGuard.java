package com.adesa.attendance.attendance;

import java.util.Map;
import java.util.UUID;

import com.adesa.attendance.attendance.network.AttendanceNetworkGuard;
import com.adesa.attendance.attendance.network.NetworkAccessDeniedException;
import com.adesa.attendance.audit.AuditAction;
import com.adesa.attendance.audit.AuditService;
import com.adesa.attendance.audit.SecurityEventLogger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Gate for every attendance mutation: the office-network policy.
 *
 * <p>Attendance sign-in and sign-out are only accepted from the approved ADESA
 * office network. The check is performed here — before any attendance state is
 * touched — and a rejection is recorded as a security event in its own
 * transaction, so the audit trail survives the rejected request.
 *
 * <p>The resolved client address and the reason are written to the restricted
 * security log (with a timestamp). The database audit entry keeps the generic
 * reason {@code NETWORK_NOT_APPROVED}, so no infrastructure detail ends up in
 * the business audit trail.
 */
@Component
public class AttendanceAccessGuard {

    private static final Logger log = LoggerFactory.getLogger(AttendanceAccessGuard.class);

    private final AttendanceNetworkGuard networkGuard;
    private final AuditService auditService;
    private final SecurityEventLogger securityEventLogger;

    public AttendanceAccessGuard(AttendanceNetworkGuard networkGuard,
                                 AuditService auditService,
                                 SecurityEventLogger securityEventLogger) {
        this.networkGuard = networkGuard;
        this.auditService = auditService;
        this.securityEventLogger = securityEventLogger;
    }

    /**
     * Verifies that the request originates from the approved office network.
     *
     * @param employeeId the employee performing the action (for the audit trail)
     * @param action     {@code SIGN_IN} or {@code SIGN_OUT}
     * @throws com.adesa.attendance.common.ForbiddenException when the client is
     *         not on an approved network
     */
    public void requireApprovedNetwork(UUID employeeId, String action, HttpServletRequest request) {
        try {
            networkGuard.requireApprovedClientNetwork(request);
        } catch (NetworkAccessDeniedException ex) {
            log.warn("Attendance {} rejected for employee {}: the office-network policy refused the request",
                    action, employeeId);
            securityEventLogger.attendanceDenied(action, ex.getReason(), ex.getClientIp(), employeeId);
            auditService.recordInNewTransaction(employeeId, AuditAction.ATTENDANCE_ACTION_REJECTED,
                    "ATTENDANCE_RECORD", null,
                    Map.of("action", action, "reason", "NETWORK_NOT_APPROVED"));
            throw ex;
        }
    }
}
