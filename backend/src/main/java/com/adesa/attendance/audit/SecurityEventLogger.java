package com.adesa.attendance.audit;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes security events to the restricted security log.
 *
 * <p>The {@code SECURITY} logger is routed to its own appender (see
 * {@code logback-spring.xml}); the file must only be readable by the
 * operations/IT account and must not be shipped to a general log aggregator.
 *
 * <p>Each entry carries the resolved client IP, the reason and the acting
 * employee. The timestamp is added by the log pattern. Nothing that is
 * sensitive (no password, no session id, no device proof) is ever written
 * here.
 */
@Component
public class SecurityEventLogger {

    /** Logger name that the logging configuration routes to the security log. */
    public static final String SECURITY_LOGGER_NAME = "SECURITY";

    private static final Logger securityLog = LoggerFactory.getLogger(SECURITY_LOGGER_NAME);

    /**
     * Records a denied attendance attempt.
     *
     * @param action   {@code SIGN_IN} or {@code SIGN_OUT}
     * @param reason   machine-readable reason code, e.g. {@code OUTSIDE_APPROVED_NETWORK}
     * @param clientIp the resolved client address, or {@code null} if it could not be determined
     * @param employee the employee who attempted the action
     */
    public void attendanceDenied(String action, String reason, String clientIp, UUID employee) {
        securityLog.warn("event=attendance_denied action={} reason={} client_ip={} employee={}",
                action,
                reason,
                clientIp == null ? "unresolved" : clientIp,
                employee);
    }

    /**
     * Records a denied attendance attempt without an acting employee (for
     * example a request that was refused before the identity was resolved).
     */
    public void attendanceDenied(String action, String reason, String clientIp) {
        securityLog.warn("event=attendance_denied action={} reason={} client_ip={}",
                action,
                reason,
                clientIp == null ? "unresolved" : clientIp);
    }
}
