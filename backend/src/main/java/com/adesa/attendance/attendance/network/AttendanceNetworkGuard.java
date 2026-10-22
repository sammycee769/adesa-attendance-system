package com.adesa.attendance.attendance.network;

import com.adesa.attendance.config.AttendanceProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import inet.ipaddr.IPAddress;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Single entry point for the office-network restriction on attendance actions.
 *
 * <p>Attendance sign-in/sign-out must call
 * {@link #requireApprovedClientNetwork(HttpServletRequest)} before touching
 * attendance state. When the policy is enabled the request is rejected with
 * HTTP 403 unless the client address is inside an approved office network;
 * when the address cannot be resolved the request is rejected as well (fail
 * closed).
 *
 * <p>The policy is IPv4-only: a native IPv6 client address is never approved,
 * so IPv6 traffic cannot bypass the IPv4 allowlist.
 *
 * <p>When the policy is disabled the request is <strong>refused (fail
 * closed)</strong> unless the explicit local-development bypass is set. The
 * bypass is off by default and refused under the {@code prod} profile
 * ({@code ProductionSafetyValidator}), so a disabled policy can never silently
 * allow attendance: in any environment that is not an explicitly-flagged local
 * development machine, "enforcement off" means "attendance blocked".
 */
@Component
public class AttendanceNetworkGuard {

    private static final Logger log = LoggerFactory.getLogger(AttendanceNetworkGuard.class);

    static final String DENIED_MESSAGE =
            "Attendance is only available from the approved ADESA office network.";

    private final ClientIpResolver clientIpResolver;
    private final OfficeNetworkPolicy officeNetworkPolicy;
    private final boolean localDevelopmentBypass;

    public AttendanceNetworkGuard(ClientIpResolver clientIpResolver, OfficeNetworkPolicy officeNetworkPolicy,
                                  AttendanceProperties properties) {
        this.clientIpResolver = clientIpResolver;
        this.officeNetworkPolicy = officeNetworkPolicy;
        this.localDevelopmentBypass = properties.getNetwork().isLocalDevelopmentBypass();
    }

    /**
     * Verifies that the request originates from the approved office network.
     *
     * @return the resolved client address (may be {@code null} when the
     *         policy is disabled for local development and the address could
     *         not be determined); callers may use it for auditing.
     * @throws NetworkAccessDeniedException when the policy is enabled and the
     *         client is outside the approved networks, is an IPv6 client or
     *         cannot be determined, or when the policy is disabled and the
     *         explicit local-development bypass is not set (fail closed)
     */
    public IPAddress requireApprovedClientNetwork(HttpServletRequest request) {
        IPAddress clientAddress = clientIpResolver.resolve(request);

        if (!officeNetworkPolicy.isEnforcementEnabled()) {
            if (localDevelopmentBypass) {
                log.warn("Office-network restriction is disabled and the local-development bypass is set; "
                        + "allowing attendance request without a network check. This must never happen in "
                        + "production.");
                return clientAddress;
            }
            // Enforcement is off and no explicit local-development bypass is
            // set. An unconfigured policy must never grant access: fail closed.
            log.warn("Rejecting attendance request: office-network enforcement is disabled and the "
                    + "local-development bypass is not set. Refusing to record attendance from an "
                    + "unverified network.");
            throw new NetworkAccessDeniedException(DENIED_MESSAGE,
                    NetworkAccessDeniedException.REASON_ENFORCEMENT_DISABLED,
                    clientAddress != null ? clientAddress.toString() : null);
        }

        if (clientAddress == null) {
            // Cannot evaluate the policy safely -> fail closed.
            log.warn("Rejecting attendance request: the client address could not be determined safely");
            throw new NetworkAccessDeniedException(DENIED_MESSAGE,
                    NetworkAccessDeniedException.REASON_UNRESOLVED, null);
        }

        if (!clientAddress.isIPv4()) {
            // The approved office network is IPv4-only.
            log.warn("Rejecting attendance request: the client address is not an IPv4 address");
            throw new NetworkAccessDeniedException(DENIED_MESSAGE,
                    NetworkAccessDeniedException.REASON_IPV6_NOT_APPROVED, clientAddress.toString());
        }

        if (!officeNetworkPolicy.isWithinApprovedNetwork(clientAddress)) {
            log.warn("Rejecting attendance request: the client is outside the approved office network");
            throw new NetworkAccessDeniedException(DENIED_MESSAGE,
                    NetworkAccessDeniedException.REASON_OUTSIDE_APPROVED_NETWORK, clientAddress.toString());
        }

        return clientAddress;
    }
}
