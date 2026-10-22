package com.adesa.attendance.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * WebAuthn relying-party configuration.
 *
 * <p>{@code rp-id} and {@code allowed-origins} MUST match the domain the
 * application is deployed on; the values here default to localhost for
 * development only and are placeholders until ADESA IT supplies the
 * production domain.
 */
@Validated
@ConfigurationProperties(prefix = "webauthn")
public class WebAuthnProperties {

    /** WebAuthn relying-party identifier (effectively the site hostname). */
    @NotBlank(message = "webauthn.rp-id must be configured")
    private String rpId = "localhost";

    /** Human-readable relying-party name shown by the authenticator. */
    @NotBlank(message = "webauthn.rp-name must be configured")
    private String rpName = "ADESA HQ Attendance";

    /** Exact origins accepted during WebAuthn ceremonies. */
    @NotEmpty(message = "webauthn.allowed-origins must contain at least one origin")
    private List<String> allowedOrigins = new ArrayList<>(List.of("http://localhost:5173"));

    /**
     * Whether the authenticator must perform user verification (biometric/PIN).
     * Requested as "preferred" in the ceremony; when false it is not mandatory,
     * so authenticators without a verification method still work.
     */
    private boolean userVerificationRequired = false;

    public String getRpId() {
        return rpId;
    }

    public void setRpId(String rpId) {
        this.rpId = rpId;
    }

    public String getRpName() {
        return rpName;
    }

    public void setRpName(String rpName) {
        this.rpName = rpName;
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins != null ? allowedOrigins : new ArrayList<>();
    }

    public boolean isUserVerificationRequired() {
        return userVerificationRequired;
    }

    public void setUserVerificationRequired(boolean userVerificationRequired) {
        this.userVerificationRequired = userVerificationRequired;
    }
}
