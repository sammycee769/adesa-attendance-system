package com.adesa.attendance.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

/**
 * Configuration of the attendance features.
 *
 * <p>The office-network policy lives in the nested {@code attendance.network}
 * group and is bound from the environment:
 *
 * <pre>
 * attendance.network.enforcement-enabled = ${ATTENDANCE_NETWORK_ENFORCEMENT_ENABLED:false}
 * attendance.network.allowed-ipv4       = ${ATTENDANCE_ALLOWED_IPV4:105.127.6.174/32}
 * attendance.network.trusted-proxies    = ${ATTENDANCE_TRUSTED_PROXY_ADDRESSES:}
 * </pre>
 *
 * <p>The approved addresses are supplied by ADESA IT and are never invented by
 * the development team. Enforcement is implemented in the {@code attendance}
 * feature (Phase 4) and applied to every attendance sign-in/sign-out request.
 */
@Validated
@ConfigurationProperties(prefix = "attendance")
public class AttendanceProperties {

    /** The office-network policy. */
    private final Network network = new Network();

    /**
     * Timezone that defines the attendance workday and the displayed
     * timestamps. ADESA HQ is in Lagos, so {@code Africa/Lagos} is the default.
     * An invalid zone id makes the application fail to start.
     */
    @NotBlank
    private String timezone = "Africa/Lagos";

    public Network getNetwork() {
        return network;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = (timezone != null && !timezone.isBlank()) ? timezone.trim() : "Africa/Lagos";
    }

    /**
     * The office-network policy.
     *
     * <p>The allowlist is IPv4-only: a request whose resolved client address is
     * a native IPv6 address is never approved, so IPv6 traffic cannot bypass the
     * policy.
     */
    public static class Network {

        /**
         * Master switch for the office-network restriction. Must be enabled in
         * production; see {@link ProductionSafetyValidator}.
         */
        private boolean enforcementEnabled = false;

        /**
         * Explicit local-development override that allows attendance actions
         * without the office-network check. It is <strong>off by default</strong>
         * so that a disabled policy fails closed: unless enforcement is enabled
         * or this flag is deliberately set, every attendance action is refused.
         *
         * <p>It exists so a developer can run the stack on a machine that is not
         * on the office network without weakening the production posture. It is
         * refused whenever the {@code prod} profile is active, so it can never
         * be used to bypass enforcement in production.
         */
        private boolean localDevelopmentBypass = false;

        /** IPv4 CIDR blocks that represent the approved ADESA office egress. */
        private List<String> allowedIpv4 = new ArrayList<>();

        /**
         * Reverse proxies whose forwarded client-IP headers may be trusted.
         * Headers from any other source are ignored.
         */
        private List<String> trustedProxies = new ArrayList<>();

        /**
         * Request header carrying the forwarded client address. Only consulted when
         * the direct peer is a configured trusted proxy (e.g. {@code X-Forwarded-For}).
         */
        private String clientIpHeader = "X-Forwarded-For";

        public boolean isEnforcementEnabled() {
            return enforcementEnabled;
        }

        public void setEnforcementEnabled(boolean enforcementEnabled) {
            this.enforcementEnabled = enforcementEnabled;
        }

        public boolean isLocalDevelopmentBypass() {
            return localDevelopmentBypass;
        }

        public void setLocalDevelopmentBypass(boolean localDevelopmentBypass) {
            this.localDevelopmentBypass = localDevelopmentBypass;
        }

        public List<String> getAllowedIpv4() {
            return allowedIpv4;
        }

        public void setAllowedIpv4(List<String> allowedIpv4) {
            this.allowedIpv4 = allowedIpv4 != null ? allowedIpv4 : new ArrayList<>();
        }

        public List<String> getTrustedProxies() {
            return trustedProxies;
        }

        public void setTrustedProxies(List<String> trustedProxies) {
            this.trustedProxies = trustedProxies != null ? trustedProxies : new ArrayList<>();
        }

        public String getClientIpHeader() {
            return clientIpHeader;
        }

        public void setClientIpHeader(String clientIpHeader) {
            this.clientIpHeader = (clientIpHeader != null && !clientIpHeader.isBlank())
                    ? clientIpHeader
                    : "X-Forwarded-For";
        }
    }
}
