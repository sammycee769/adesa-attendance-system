package com.adesa.attendance.config;

import java.net.URI;
import java.util.List;
import java.util.Locale;

import inet.ipaddr.IPAddress;
import inet.ipaddr.IPAddressString;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Fails application startup when the configuration is unsafe for the active
 * profile.
 *
 * <p>When the {@code prod} profile is active:
 *
 * <ul>
 *   <li>the attendance office-network restriction must be enabled, the
 *       development bypass must be off, and at least one approved CIDR must be
 *       configured;</li>
 *   <li>the allowlist may not be broader than a {@code /24}, and trusted
 *       proxies must be single host addresses, so a {@code 0.0.0.0/0} (or a
 *       whole-network proxy) cannot silently disable the control;</li>
 *   <li>the database password must not be the development default;</li>
 *   <li>the session cookie must be {@code Secure};</li>
 *   <li>WebAuthn must be bound to a real HTTPS domain and require user
 *       verification.</li>
 * </ul>
 *
 * <p>Outside production, the development bypass is only accepted when the
 * {@code dev} profile is explicitly active. A deployment that forgets to
 * activate any profile but sets the bypass therefore refuses to start instead
 * of silently disabling the office-network control.
 */
@Component
public class ProductionSafetyValidator implements InitializingBean {

    static final String PROD_PROFILE = "prod";
    static final String DEV_PROFILE = "dev";

    /** The documented local-development database password. */
    static final String DEV_DATABASE_PASSWORD = "adesa_dev_password";

    /** The widest allowlist prefix accepted in production. */
    static final int MIN_ALLOWLIST_PREFIX = 24;

    private final Environment environment;
    private final AttendanceProperties attendanceProperties;
    private final WebAuthnProperties webAuthnProperties;
    private final AccountProperties accountProperties;
    private final MailProperties mailProperties;

    public ProductionSafetyValidator(Environment environment,
                                     AttendanceProperties attendanceProperties,
                                     WebAuthnProperties webAuthnProperties,
                                     AccountProperties accountProperties,
                                     MailProperties mailProperties) {
        this.environment = environment;
        this.attendanceProperties = attendanceProperties;
        this.webAuthnProperties = webAuthnProperties;
        this.accountProperties = accountProperties;
        this.mailProperties = mailProperties;
    }

    @Override
    public void afterPropertiesSet() {
        if (isProdProfileActive()) {
            validateProduction();
            return;
        }
        if (attendanceProperties.getNetwork().isLocalDevelopmentBypass() && !isDevProfileActive()) {
            throw new IllegalStateException(
                    "Refusing to start: attendance.network.local-development-bypass is set but the "
                            + "'dev' profile is not active. The bypass exists only for local development; "
                            + "start with SPRING_PROFILES_ACTIVE=dev or turn the bypass off.");
        }
    }

    private void validateProduction() {
        validateNetwork(attendanceProperties.getNetwork());
        validateDatabasePassword();
        validateSessionCookie();
        validateWebAuthn();
        validatePublicUrl();
        validateMail();
    }

    /**
     * The public URL is the only source of absolute setup/recovery links, so in
     * production it must be an HTTPS origin that lies within the WebAuthn RP ID
     * (the deployed frontend domain). A localhost or plain-HTTP value would
     * produce links that leak the single-use token over an insecure channel or
     * point at the wrong host.
     */
    private void validatePublicUrl() {
        String publicUrl = accountProperties.getPublicUrl();
        if (!StringUtils.hasText(publicUrl)) {
            throw new IllegalStateException(
                    "Refusing to start: the 'prod' profile is active but APP_PUBLIC_URL is unset. "
                            + "Set it to the deployed frontend origin (for example "
                            + "https://attendance.adesa.example) so account-setup and recovery links "
                            + "are built from a trusted value, never from the Host header.");
        }
        URI uri;
        try {
            uri = URI.create(publicUrl.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "Refusing to start: APP_PUBLIC_URL is not a valid URL: '" + publicUrl + "'.");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalStateException(
                    "Refusing to start: APP_PUBLIC_URL must use https in production, got '"
                            + publicUrl + "'. A plain-HTTP link would expose the single-use token.");
        }
        String host = uri.getHost();
        if (host == null) {
            throw new IllegalStateException(
                    "Refusing to start: APP_PUBLIC_URL has no host: '" + publicUrl + "'.");
        }
        // It must be a real deployed origin, not a development placeholder.
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (normalizedHost.equals("localhost") || normalizedHost.equals("127.0.0.1")
                || normalizedHost.equals("::1") || new IPAddressString(normalizedHost).isValid()) {
            throw new IllegalStateException(
                    "Refusing to start: APP_PUBLIC_URL must be the deployed frontend domain, not '"
                            + host + "'.");
        }
        // And it must belong to the same domain as the WebAuthn RP ID.
        String rpId = webAuthnProperties.getRpId().trim().toLowerCase(Locale.ROOT);
        if (!normalizedHost.equals(rpId) && !normalizedHost.endsWith("." + rpId)
                && !rpId.endsWith("." + normalizedHost)) {
            throw new IllegalStateException(
                    "Refusing to start: APP_PUBLIC_URL host '" + host
                            + "' does not match the WebAuthn RP ID '" + rpId + "'. The account links "
                            + "and the passkey ceremony must use the same domain.");
        }
    }

    /**
     * In production, e-mail delivery must be configured: account creation and
     * password recovery depend on it, and a silent failure would strand new
     * employees. Credentials must be present too.
     */
    private void validateMail() {
        if (!mailProperties.isConfigured()) {
            throw new IllegalStateException(
                    "Refusing to start: the 'prod' profile is active but mail delivery is not "
                            + "configured. Set MAIL_HOST and MAIL_FROM (and MAIL_USERNAME/MAIL_PASSWORD "
                            + "for Zoho) so account-setup and password-recovery e-mail can be sent.");
        }
        if (mailProperties.isSmtpAuth()
                && (!StringUtils.hasText(mailProperties.getUsername())
                    || !StringUtils.hasText(mailProperties.getPassword()))) {
            throw new IllegalStateException(
                    "Refusing to start: MAIL_SMTP_AUTH is true but MAIL_USERNAME or MAIL_PASSWORD is "
                            + "unset. Supply the Zoho mailbox credentials through the environment.");
        }
    }

    private void validateNetwork(AttendanceProperties.Network network) {
        if (!network.isEnforcementEnabled()) {
            throw new IllegalStateException(
                    "Refusing to start: the 'prod' profile is active but "
                            + "attendance.network.enforcement-enabled is false. Attendance must only "
                            + "be recordable from the approved ADESA office network. Verify the client "
                            + "address the application sees (GET /api/v1/admin/network/client-address "
                            + "from inside the office network) and then start the application with "
                            + "ATTENDANCE_NETWORK_ENFORCEMENT_ENABLED=true.");
        }
        if (network.isLocalDevelopmentBypass()) {
            throw new IllegalStateException(
                    "Refusing to start: the 'prod' profile is active but "
                            + "attendance.network.local-development-bypass is set. The bypass exists only "
                            + "for local development machines and would disable the office-network "
                            + "restriction in production.");
        }
        if (network.getAllowedIpv4().stream().anyMatch(cidr -> !StringUtils.hasText(cidr))) {
            throw new IllegalStateException(
                    "Refusing to start: attendance.network.allowed-ipv4 contains a blank entry.");
        }
        if (network.getAllowedIpv4().isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start: the 'prod' profile is active but "
                            + "attendance.network.allowed-ipv4 is empty. Approved office-network IPv4 "
                            + "CIDRs must be supplied by ADESA IT.");
        }
        for (String cidr : network.getAllowedIpv4()) {
            int prefix = requireIpv4Prefix(cidr, "attendance.network.allowed-ipv4");
            if (prefix < MIN_ALLOWLIST_PREFIX) {
                throw new IllegalStateException(
                        "Refusing to start: attendance.network.allowed-ipv4 contains the over-broad "
                                + "CIDR '" + cidr + "'. The office allowlist must be at most a /"
                                + MIN_ALLOWLIST_PREFIX + " (a /0, /8 or /16 would approve the whole "
                                + "internet or a whole provider network).");
            }
        }
        for (String proxy : network.getTrustedProxies()) {
            int prefix = requireIpv4Prefix(proxy, "attendance.network.trusted-proxies");
            if (prefix != 32) {
                throw new IllegalStateException(
                        "Refusing to start: attendance.network.trusted-proxies must list single IPv4 "
                                + "host addresses (/32), got '" + proxy + "'. A range would let an "
                                + "entire network influence the resolved client address.");
            }
        }
    }

    private void validateDatabasePassword() {
        String password = environment.getProperty("spring.datasource.password");
        if (!StringUtils.hasText(password) || DEV_DATABASE_PASSWORD.equals(password)) {
            throw new IllegalStateException(
                    "Refusing to start: the 'prod' profile is active but the database password is "
                            + "unset or still the development default. Set DB_PASSWORD to a unique "
                            + "production secret.");
        }
    }

    private void validateSessionCookie() {
        Boolean secure = environment.getProperty(
                "server.servlet.session.cookie.secure", Boolean.class, Boolean.FALSE);
        if (!Boolean.TRUE.equals(secure)) {
            throw new IllegalStateException(
                    "Refusing to start: the 'prod' profile is active but "
                            + "server.servlet.session.cookie.secure is false. The session and CSRF "
                            + "cookies must only travel over HTTPS.");
        }
    }

    private void validateWebAuthn() {
        String rpId = webAuthnProperties.getRpId();
        if (isPlaceholderRpId(rpId)) {
            throw new IllegalStateException(
                    "Refusing to start: the 'prod' profile is active but webauthn.rp-id is '"
                            + rpId + "'. Set WEBAUTHN_RP_ID to the deployed domain (for example "
                            + "attendance.adesa.example), not localhost or an IP address.");
        }
        List<String> origins = webAuthnProperties.getAllowedOrigins();
        if (origins.isEmpty()) {
            throw new IllegalStateException(
                    "Refusing to start: webauthn.allowed-origins is empty in production.");
        }
        for (String origin : origins) {
            validateOrigin(origin, rpId);
        }
        if (!webAuthnProperties.isUserVerificationRequired()) {
            throw new IllegalStateException(
                    "Refusing to start: the 'prod' profile is active but "
                            + "webauthn.user-verification-required is false. The passkey ceremony must "
                            + "require biometric/PIN verification for attendance and administration.");
        }
    }

    private static boolean isPlaceholderRpId(String rpId) {
        if (!StringUtils.hasText(rpId)) {
            return true;
        }
        String value = rpId.trim().toLowerCase(Locale.ROOT);
        if (value.equals("localhost") || value.equals("127.0.0.1") || value.equals("::1")) {
            return true;
        }
        // An IP literal is not a valid WebAuthn RP ID for a deployed service.
        if (new IPAddressString(value).isValid()) {
            return true;
        }
        // A registrable domain has at least one dot.
        return !value.contains(".");
    }

    private static void validateOrigin(String origin, String rpId) {
        URI uri;
        try {
            uri = URI.create(origin.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "Refusing to start: webauthn.allowed-origins contains an invalid origin '" + origin + "'.");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalStateException(
                    "Refusing to start: webauthn.allowed-origins must use https, got '" + origin + "'.");
        }
        if (uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalStateException(
                    "Refusing to start: webauthn.allowed-origins must be a plain origin, got '"
                            + origin + "'.");
        }
        String host = uri.getHost();
        if (host == null) {
            throw new IllegalStateException(
                    "Refusing to start: webauthn.allowed-origins contains an origin without a host: '"
                            + origin + "'.");
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        String normalizedRpId = rpId.trim().toLowerCase(Locale.ROOT);
        if (!normalizedHost.equals(normalizedRpId) && !normalizedHost.endsWith("." + normalizedRpId)) {
            throw new IllegalStateException(
                    "Refusing to start: the origin '" + origin + "' is not within the WebAuthn RP ID '"
                            + rpId + "'.");
        }
    }

    private static int requireIpv4Prefix(String value, String propertyName) {
        IPAddressString parsed = new IPAddressString(value.trim());
        if (!parsed.isValid()) {
            throw new IllegalStateException(
                    "Refusing to start: invalid address or CIDR in " + propertyName + ": '" + value + "'.");
        }
        IPAddress address = parsed.getAddress();
        if (address == null || !address.isIPv4()) {
            throw new IllegalStateException(
                    "Refusing to start: " + propertyName + " is IPv4-only, got '" + value + "'.");
        }
        Integer prefix = parsed.getNetworkPrefixLength();
        return prefix == null ? 32 : prefix;
    }

    private boolean isProdProfileActive() {
        return hasProfile(PROD_PROFILE);
    }

    private boolean isDevProfileActive() {
        return hasProfile(DEV_PROFILE);
    }

    private boolean hasProfile(String profile) {
        for (String active : environment.getActiveProfiles()) {
            if (profile.equalsIgnoreCase(active)) {
                return true;
            }
        }
        return false;
    }
}
