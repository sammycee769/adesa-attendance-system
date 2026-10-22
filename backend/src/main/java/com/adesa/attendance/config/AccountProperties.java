package com.adesa.attendance.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Password-setup, recovery and mail configuration.
 *
 * <p>Recovery/setup tokens are opaque 256-bit random values; only their SHA-256
 * hash is stored. Their lifetime is deliberately short.
 */
@Validated
@ConfigurationProperties(prefix = "adesa.account")
public class AccountProperties {

    /** Lifetime of an emailed account-setup link. */
    @NotNull
    private Duration setupTokenTtl = Duration.ofHours(48);

    /** Lifetime of an emailed password-recovery link. */
    @NotNull
    private Duration recoveryTokenTtl = Duration.ofMinutes(30);

    /** Minimum password length, enforced identically on the server and client. */
    @Min(8)
    private int passwordMinLength = 12;

    /** Maximum password length (bcrypt input is capped defensively). */
    @Min(16)
    private int passwordMaxLength = 128;

    /**
     * Failed recovery requests / token submissions allowed per client address
     * within {@code recoveryWindow}.
     */
    @Min(1)
    private int recoveryMaxAttempts = 5;

    @NotNull
    private Duration recoveryWindow = Duration.ofMinutes(15);

    /**
     * Public base URL of the deployed frontend, used to build absolute
     * setup/recovery links. Never derived from the incoming Host header. The
     * {@code prod} profile refuses to start without an HTTPS value.
     */
    @NotBlank
    private String publicUrl = "http://localhost:5173";

    public Duration getSetupTokenTtl() {
        return setupTokenTtl;
    }

    public void setSetupTokenTtl(Duration setupTokenTtl) {
        this.setupTokenTtl = setupTokenTtl;
    }

    public Duration getRecoveryTokenTtl() {
        return recoveryTokenTtl;
    }

    public void setRecoveryTokenTtl(Duration recoveryTokenTtl) {
        this.recoveryTokenTtl = recoveryTokenTtl;
    }

    public int getPasswordMinLength() {
        return passwordMinLength;
    }

    public void setPasswordMinLength(int passwordMinLength) {
        this.passwordMinLength = passwordMinLength;
    }

    public int getPasswordMaxLength() {
        return passwordMaxLength;
    }

    public void setPasswordMaxLength(int passwordMaxLength) {
        this.passwordMaxLength = passwordMaxLength;
    }

    public int getRecoveryMaxAttempts() {
        return recoveryMaxAttempts;
    }

    public void setRecoveryMaxAttempts(int recoveryMaxAttempts) {
        this.recoveryMaxAttempts = recoveryMaxAttempts;
    }

    public Duration getRecoveryWindow() {
        return recoveryWindow;
    }

    public void setRecoveryWindow(Duration recoveryWindow) {
        this.recoveryWindow = recoveryWindow;
    }

    public String getPublicUrl() {
        return publicUrl;
    }

    public void setPublicUrl(String publicUrl) {
        this.publicUrl = publicUrl;
    }
}
