package com.adesa.attendance.attendance.network;

import java.util.List;

import com.adesa.attendance.config.AttendanceProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import inet.ipaddr.IPAddress;

/**
 * The approved ADESA office-network policy.
 *
 * <p>The approved ranges are supplied by ADESA IT and are never invented. The
 * configuration is parsed and validated at startup: an enabled policy with no
 * approved networks, any malformed entry, or an IPv6 range fails startup.
 *
 * <p>The allowlist is <strong>IPv4-only</strong>. A request whose resolved
 * client address is a native IPv6 address is never approved, so IPv6 traffic
 * cannot be used to bypass the IPv4 policy. (An IPv4-mapped IPv6 peer such as
 * {@code ::ffff:105.127.6.174} is the same IPv4 host and is normalised to IPv4
 * before the check.)
 */
@Component
public class OfficeNetworkPolicy {

    private static final Logger log = LoggerFactory.getLogger(OfficeNetworkPolicy.class);

    private final boolean enforcementEnabled;
    private final List<IPAddress> approvedNetworks;

    public OfficeNetworkPolicy(AttendanceProperties properties) {
        this.enforcementEnabled = properties.getNetwork().isEnforcementEnabled();
        this.approvedNetworks = NetworkAddresses.parseAll(
                properties.getNetwork().getAllowedIpv4(), "attendance.network.allowed-ipv4");

        if (enforcementEnabled && approvedNetworks.isEmpty()) {
            throw new IllegalStateException(
                    "attendance.network.enforcement-enabled is true but attendance.network.allowed-ipv4 "
                            + "is empty. Approved office-network IPv4 CIDRs must be supplied by ADESA IT "
                            + "(or enable the policy only where it is configured).");
        }
        for (IPAddress network : approvedNetworks) {
            if (!network.isIPv4()) {
                throw new IllegalStateException(
                        "attendance.network.allowed-ipv4 must contain IPv4 ranges only, but '"
                                + network + "' is not IPv4. The office-network policy is IPv4-only.");
            }
        }
        if (!enforcementEnabled) {
            if (properties.getNetwork().isLocalDevelopmentBypass()) {
                log.warn("Office-network restriction is DISABLED but the explicit local-development bypass is "
                        + "set. Attendance will not be limited to the approved office network. This is "
                        + "acceptable for local development only; the 'prod' profile refuses to start in "
                        + "this state.");
            } else {
                log.warn("Office-network restriction is DISABLED and no local-development bypass is set. "
                        + "Attendance actions will FAIL CLOSED (HTTP 403) until enforcement is enabled or "
                        + "the local-development bypass is explicitly set.");
            }
        } else {
            log.info("Office-network restriction is ENABLED with {} approved office network(s). "
                    + "Forwarded client-address headers are honoured only from configured trusted "
                    + "proxies.", approvedNetworks.size());
        }
    }

    public boolean isEnforcementEnabled() {
        return enforcementEnabled;
    }

    /** Number of configured approved networks (useful for diagnostics; not exposed publicly). */
    public int approvedNetworkCount() {
        return approvedNetworks.size();
    }

    /**
     * True only when the address is an IPv4 address inside one of the approved
     * office networks. Native IPv6 addresses are never approved.
     */
    public boolean isWithinApprovedNetwork(IPAddress clientAddress) {
        if (clientAddress == null) {
            return false;
        }
        IPAddress normalized = NetworkAddresses.normalize(clientAddress);
        if (!normalized.isIPv4()) {
            // IPv4-only policy: an IPv6 client is not in the approved network.
            return false;
        }
        return approvedNetworks.stream().anyMatch(network -> network.contains(normalized));
    }
}
