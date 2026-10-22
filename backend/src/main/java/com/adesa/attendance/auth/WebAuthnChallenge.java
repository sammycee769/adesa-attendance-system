package com.adesa.attendance.auth;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * A single-use, short-lived WebAuthn challenge.
 *
 * <p>The challenge value is generated server-side, never accepted from the
 * client. It is consumed atomically, so a captured ceremony response cannot be
 * replayed.
 */
@Entity
@Table(name = "webauthn_challenges")
public class WebAuthnChallenge {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private WebAuthnChallengeType type;

    /** Base64url encoded challenge value. */
    @Column(name = "challenge", nullable = false)
    private String challenge;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected WebAuthnChallenge() {
        // for JPA
    }

    public WebAuthnChallenge(UUID employeeId, WebAuthnChallengeType type, String challenge, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.employeeId = employeeId;
        this.type = type;
        this.challenge = challenge;
        this.expiresAt = expiresAt;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getEmployeeId() {
        return employeeId;
    }

    public WebAuthnChallengeType getType() {
        return type;
    }

    public String getChallenge() {
        return challenge;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }
}
