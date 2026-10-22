package com.adesa.attendance.admin.dto;

/**
 * What the application sees as the caller's client address.
 *
 * <p>Used by ADESA IT to verify, from inside the office network, that the
 * application resolves the office's public egress address before the
 * office-network restriction is switched on in production, and to identify the
 * hosting topology (for example a platform load balancer in front of the
 * application).
 *
 * <p>{@code resolvedClientAddress} is the <strong>authoritative</strong> value:
 * it is the address the policy itself uses. A user-supplied address is never
 * accepted as the source of truth.
 *
 * <p>Only the resolved address of the caller is reported — never the configured
 * networks or the trusted proxies themselves. The raw forwarding header is
 * reported separately and is clearly marked as untrusted: it is only shown for
 * troubleshooting and never influences a policy decision.
 */
public record NetworkDiagnosticsResponse(
        /** Resolved client address, or {@code null} when it cannot be determined. */
        String resolvedClientAddress,
        /** True when the resolved address is inside an approved office network. */
        boolean withinApprovedNetwork,
        /** True when the office-network restriction is enforced. */
        boolean enforcementEnabled,
        /** Number of configured approved office networks. */
        int approvedNetworkCount,
        /** Number of configured trusted reverse proxies. */
        int trustedProxyCount,
        /** Name of the header that carries the forwarded client address. */
        String clientIpHeader,
        /** True when the resolved address is IPv4 (the policy is IPv4-only). */
        boolean resolvedAddressIsIpv4,
        /**
         * The raw, <strong>untrusted</strong> forwarding header of this request.
         * Troubleshooting only: it never influences the policy decision.
         */
        String untrustedForwardedClientAddress) {
}
