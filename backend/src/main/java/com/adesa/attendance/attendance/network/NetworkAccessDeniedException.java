package com.adesa.attendance.attendance.network;

import com.adesa.attendance.common.ForbiddenException;

/**
 * Thrown when the office-network policy refuses an attendance request.
 *
 * <p>The exception carries the machine-readable reason and the resolved client
 * address for the <strong>restricted security log</strong>. The HTTP message
 * stays the same generic, non-sensitive sentence for every reason, so nothing
 * about the infrastructure leaks to the client.
 */
public class NetworkAccessDeniedException extends ForbiddenException {

    /** The client address could not be determined safely. */
    public static final String REASON_UNRESOLVED = "CLIENT_ADDRESS_UNRESOLVED";

    /** The client address is outside the approved office networks. */
    public static final String REASON_OUTSIDE_APPROVED_NETWORK = "OUTSIDE_APPROVED_NETWORK";

    /** The client address is IPv6; the approved office network is IPv4-only. */
    public static final String REASON_IPV6_NOT_APPROVED = "IPV6_CLIENT_NOT_APPROVED";

    /**
     * Enforcement is disabled and no explicit local-development bypass is set,
     * so the policy cannot verify the network and refuses the request.
     */
    public static final String REASON_ENFORCEMENT_DISABLED = "ENFORCEMENT_DISABLED_FAIL_CLOSED";

    private final String reason;
    private final String clientIp;

    public NetworkAccessDeniedException(String message, String reason, String clientIp) {
        super(message);
        this.reason = reason;
        this.clientIp = clientIp;
    }

    /** Machine-readable reason code, e.g. {@code OUTSIDE_APPROVED_NETWORK}. */
    public String getReason() {
        return reason;
    }

    /** The resolved client address, or {@code null} when it is unknown. */
    public String getClientIp() {
        return clientIp;
    }
}
