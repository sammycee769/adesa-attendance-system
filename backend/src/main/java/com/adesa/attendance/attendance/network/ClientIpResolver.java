package com.adesa.attendance.attendance.network;

import java.util.List;
import java.util.Collections;

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

    public static final String INGRESS_KEY_HEADER = "X-Adesa-Ingress-Key";
    public static final String INGRESS_IP_HEADER = "X-Adesa-Client-IP";
    private final boolean authenticatedIngressEnabled;
    private final byte[] ingressSecret;
    private final String clientIpHeader;
    private final List<IPAddress> trustedProxies;

    public ClientIpResolver(AttendanceProperties properties) {
        this.authenticatedIngressEnabled = properties.getNetwork().isAuthenticatedIngressEnabled();
        String secret = properties.getNetwork().getIngressSharedSecret();
        if (authenticatedIngressEnabled && !secret.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("Authenticated ingress requires a private random 64-character lowercase hex secret");
        }
        if (authenticatedIngressEnabled && !properties.getNetwork().getTrustedProxies().isEmpty()) {
            throw new IllegalStateException("Authenticated ingress must not be combined with trusted XFF proxies");
        }
        this.ingressSecret = secret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        this.clientIpHeader = authenticatedIngressEnabled ? INGRESS_IP_HEADER
                : properties.getNetwork().getClientIpHeader();
        this.trustedProxies = NetworkAddresses.parseAll(
                properties.getNetwork().getTrustedProxies(), "attendance.network.trusted-proxies");
    }

    public IPAddress resolve(HttpServletRequest request) {
        if (authenticatedIngressEnabled) {
            return resolveAuthenticatedIngress(request);
        }
        IPAddress directPeer = NetworkAddresses.parseOrNull(request.getRemoteAddr());
        if (directPeer == null) {
            log.warn("Cannot parse the direct peer address; the request address is unusable");
            return null;
        }
        if (!isTrustedProxy(directPeer)) {
            // Untrusted source: ignore any client-supplied forwarding headers.
            return directPeer;
        }
        String forwardedHeader = String.join(",", Collections.list(request.getHeaders(clientIpHeader)));
        if (!StringUtils.hasText(forwardedHeader)) {
            return directPeer;
        }
        String[] hops = forwardedHeader.split(",", -1);
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

    private IPAddress resolveAuthenticatedIngress(HttpServletRequest request) {
        List<String> keys = Collections.list(request.getHeaders(INGRESS_KEY_HEADER));
        List<String> ips = Collections.list(request.getHeaders(INGRESS_IP_HEADER));
        if (keys.size() != 1 || ips.size() != 1) return null;
        String key = keys.getFirst();
        if (!key.matches("[0-9a-f]{64}") || !java.security.MessageDigest.isEqual(ingressSecret,
                key.getBytes(java.nio.charset.StandardCharsets.UTF_8))) return null;
        String ip = ips.getFirst();
        // One literal IPv4 only: no ranges, CIDRs, chains or alternative encodings.
        if (!ip.matches("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")) return null;
        IPAddress address = NetworkAddresses.parseOrNull(ip);
        return address != null && address.isIPv4() && !address.isMultiple() ? address : null;
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
