package com.adesa.attendance.attendance.network;

import java.util.List;

import com.adesa.attendance.config.AttendanceProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import inet.ipaddr.IPAddress;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Derives the client IP address from a request.
 *
 * <p>Forwarded headers are honoured <strong>only</strong> when the direct peer
 * is an explicitly configured trusted proxy. From the forwarded chain the
 * right-most address that is not itself a trusted proxy is taken as the
 * client — an attacker cannot prepend a spoofed address because the entries it
 * controls are to the left of the entry added by our own trusted proxy.
 *
 * <p>Returns {@code null} when the address cannot be determined safely, so the
 * caller can fail closed.
 */
@Component
public class ClientIpResolver {

    private static final Logger log = LoggerFactory.getLogger(ClientIpResolver.class);

    private final String clientIpHeader;
    private final List<IPAddress> trustedProxies;

    public ClientIpResolver(AttendanceProperties properties) {
        this.clientIpHeader = properties.getNetwork().getClientIpHeader();
        this.trustedProxies = NetworkAddresses.parseAll(
                properties.getNetwork().getTrustedProxies(), "attendance.network.trusted-proxies");
    }

    public IPAddress resolve(HttpServletRequest request) {
        IPAddress directPeer = NetworkAddresses.parseOrNull(request.getRemoteAddr());
        if (directPeer == null) {
            log.warn("Cannot parse the direct peer address; the request address is unusable");
            return null;
        }
        if (!isTrustedProxy(directPeer)) {
            // Untrusted source: ignore any client-supplied forwarding headers.
            return directPeer;
        }
        String forwardedHeader = request.getHeader(clientIpHeader);
        if (!StringUtils.hasText(forwardedHeader)) {
            return directPeer;
        }
        String[] hops = forwardedHeader.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            IPAddress hop = NetworkAddresses.parseOrNull(hops[i]);
            if (hop == null) {
                // A malformed chain from a trusted proxy cannot be evaluated safely.
                log.warn("Malformed '{}' header received from trusted proxy; failing closed", clientIpHeader);
                return null;
            }
            if (!isTrustedProxy(hop)) {
                return hop;
            }
        }
        // Every hop was a trusted proxy; the direct peer is the best answer.
        return directPeer;
    }

    boolean isTrustedProxy(IPAddress address) {
        return trustedProxies.stream().anyMatch(proxy -> proxy.contains(address));
    }

    /**
     * Number of configured trusted proxies (diagnostics only; the addresses
     * themselves are infrastructure detail and are never returned).
     */
    public int trustedProxyCount() {
        return trustedProxies.size();
    }

    /** Name of the header that carries the forwarded client address. */
    public String clientIpHeaderName() {
        return clientIpHeader;
    }

    /**
     * The raw, <strong>untrusted</strong> value of the forwarding header.
     *
     * <p>It is reported by the diagnostics endpoint only, to help ADESA IT
     * identify the hosting topology (for example to see what a platform load
     * balancer forwards). It never influences a policy decision: the policy
     * uses {@link #resolve(HttpServletRequest)} exclusively.
     */
    public String untrustedForwardedHeaderValue(HttpServletRequest request) {
        String value = request.getHeader(clientIpHeader);
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
