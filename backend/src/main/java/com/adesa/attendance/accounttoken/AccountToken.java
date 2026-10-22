package com.adesa.attendance.accounttoken;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A single-use, short-lived token for account setup or password recovery.
 *
 * <p>Only the SHA-256 hash of the emailed token is stored, so a database leak
 * cannot be turned back into a usable link. Consumption is atomic (see
 * {@link AccountTokenRepository#consume}) and a consumed token can never be
 * used twice.
 */
@Entity
@Table(name = "account_tokens")
public class AccountToken {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    /** SHA-256 (hex) of the opaque token. Never the raw token. */
    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32)
    private AccountTokenType type;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected AccountToken() {
        // for JPA
    }

    public AccountToken(UUID employeeId, String tokenHash, AccountTokenType type, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.employeeId = employeeId;
        this.tokenHash = tokenHash;
        this.type = type;
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEmployeeId() {
        return employeeId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public AccountTokenType getType() {
        return type;
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

    public void markConsumed(Instant when) {
        this.consumedAt = when;
    }

    public boolean isUsable(Instant now) {
        return consumedAt == null && now.isBefore(expiresAt);
    }

    /** True when the token exists but was already used or has expired. */
    public boolean isStale(Instant now) {
        return consumedAt != null || !now.isBefore(expiresAt);
    }
}
