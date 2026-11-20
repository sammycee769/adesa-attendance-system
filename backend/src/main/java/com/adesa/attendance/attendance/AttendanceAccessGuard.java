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
import com.adesa.attendance.attendance.network.LocationRequiredException;
import com.adesa.attendance.common.ForbiddenException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/** Checks the configured attendance policy before any attendance state is changed. */
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

    public String requireOfficePresence(UUID employeeId, String action, HttpServletRequest request,
                                        AttendanceLocation location) {
        if (geolocationPolicy.isEnabled()) {
            try {
                geolocationPolicy.requireAuthenticatedIngress(request);
                geolocationPolicy.requireInsideOffice(location);
                return "GEOLOCATION";
            } catch (LocationRequiredException required) {
                throw required;
            } catch (ForbiddenException denied) {
                auditService.recordInNewTransaction(employeeId, AuditAction.ATTENDANCE_ACTION_REJECTED,
                        "ATTENDANCE_RECORD", null, Map.of("action", action, "reason", "LOCATION_NOT_CONFIRMED"));
                throw denied;
            }
        }

        try {
            networkGuard.requireApprovedClientNetwork(request);
            return "OFFICE_IP";
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
