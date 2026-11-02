package com.adesa.attendance.attendance;

import java.util.Map;
import java.util.UUID;

import com.adesa.attendance.attendance.network.AttendanceNetworkGuard;
import com.adesa.attendance.attendance.network.NetworkAccessDeniedException;
import com.adesa.attendance.audit.AuditAction;
import com.adesa.attendance.audit.AuditService;
import com.adesa.attendance.audit.SecurityEventLogger;
import com.adesa.attendance.attendance.dto.AttendanceLocation;
import com.adesa.attendance.attendance.network.GeolocationPolicy;
import com.adesa.attendance.common.ForbiddenException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Gate for every attendance mutation: approved office IP, or the enabled location fallback.
 *
 * <p>The check is performed here — before any attendance state is
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
    private final GeolocationPolicy geolocationPolicy;

    public AttendanceAccessGuard(AttendanceNetworkGuard networkGuard,
                                 AuditService auditService,
                                 SecurityEventLogger securityEventLogger,
                                 GeolocationPolicy geolocationPolicy) {
        this.networkGuard = networkGuard;
        this.auditService = auditService;
        this.securityEventLogger = securityEventLogger;
        this.geolocationPolicy = geolocationPolicy;
    }

    /**
     * Verifies that the request originates from the approved office network.
     *
     * @param employeeId the employee performing the action (for the audit trail)
     * @param action     {@code SIGN_IN} or {@code SIGN_OUT}
     * @throws com.adesa.attendance.common.ForbiddenException when the client is
     *         not on an approved network
     */
    public String requireApprovedNetwork(UUID employeeId, String action, HttpServletRequest request) {
        return requireApprovedNetwork(employeeId, action, request, null);
    }

    public String requireApprovedNetwork(UUID employeeId, String action, HttpServletRequest request,
                                        AttendanceLocation location) {
        try {
            networkGuard.requireApprovedClientNetwork(request);
            return "OFFICE_IP";
        } catch (NetworkAccessDeniedException ex) {
            if (!NetworkAccessDeniedException.REASON_ENFORCEMENT_DISABLED.equals(ex.getReason())
                    && geolocationPolicy.canFallback(request)) {
                try {
                    geolocationPolicy.requireInsideOffice(location);
                    return "GEOLOCATION";
                } catch (com.adesa.attendance.attendance.network.LocationRequiredException required) {
                    throw required;
                } catch (ForbiddenException denied) {
                    auditService.recordInNewTransaction(employeeId, AuditAction.ATTENDANCE_ACTION_REJECTED,
                            "ATTENDANCE_RECORD", null, Map.of("action", action, "reason", "LOCATION_NOT_CONFIRMED"));
                    throw denied;
                }
            }
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
