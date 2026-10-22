package com.adesa.attendance.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Authentication/session policy.
 *
 * <p>The idle timeout is configured separately via
 * {@code server.servlet.session.timeout}; this class adds the absolute session
 * lifetime (which Spring cannot enforce on its own) plus login rate limiting
 * and WebAuthn ceremony lifetimes.
 */
@Validated
@ConfigurationProperties(prefix = "auth")
public class AuthProperties {

    /**
     * Hard upper bound on a session's lifetime regardless of activity, enforced
     * by a filter. Stored as {@code login-time} in the session.
     */
    @NotNull
    private Duration sessionAbsoluteTimeout = Duration.ofHours(12);

    /** Failed login attempts allowed per email within {@link #loginWindow}. */
    @Min(1)
    private int loginMaxFailures = 5;

    /**
     * Upper bound on the number of distinct login keys the in-memory limiter
     * tracks. Bounds memory when an unauthenticated client submits a flood of
     * distinct e-mail addresses.
     */
    @Min(1)
    private int loginMaxTrackedKeys = 10_000;

    /** Window over which failed login attempts are counted. */
    @NotNull
    private Duration loginWindow = Duration.ofMinutes(15);

    /** Lifetime of a single-use WebAuthn challenge. */
    @NotNull
    private Duration webauthnChallengeTtl = Duration.ofMinutes(2);

    /** Lifetime of a single-use device proof issued after a WebAuthn assertion. */
    @NotNull
    private Duration deviceProofTtl = Duration.ofMinutes(2);

    public Duration getSessionAbsoluteTimeout() {
        return sessionAbsoluteTimeout;
    }

    public void setSessionAbsoluteTimeout(Duration sessionAbsoluteTimeout) {
        this.sessionAbsoluteTimeout = sessionAbsoluteTimeout;
    }

    public int getLoginMaxFailures() {
        return loginMaxFailures;
    }

    public void setLoginMaxFailures(int loginMaxFailures) {
        this.loginMaxFailures = loginMaxFailures;
    }

    public int getLoginMaxTrackedKeys() {
        return loginMaxTrackedKeys;
    }

    public void setLoginMaxTrackedKeys(int loginMaxTrackedKeys) {
        this.loginMaxTrackedKeys = loginMaxTrackedKeys;
    }

    public Duration getLoginWindow() {
        return loginWindow;
    }

    public void setLoginWindow(Duration loginWindow) {
        this.loginWindow = loginWindow;
    }

    public Duration getWebauthnChallengeTtl() {
        return webauthnChallengeTtl;
    }

    public void setWebauthnChallengeTtl(Duration webauthnChallengeTtl) {
        this.webauthnChallengeTtl = webauthnChallengeTtl;
    }

    public Duration getDeviceProofTtl() {
        return deviceProofTtl;
    }

    public void setDeviceProofTtl(Duration deviceProofTtl) {
        this.deviceProofTtl = deviceProofTtl;
    }
}
