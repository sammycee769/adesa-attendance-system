package com.adesa.attendance.device;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * A device credential registered by an employee and managed by an
 * administrator.
 *
 * <p>Only public WebAuthn data is stored: the credential id and the CBOR
 * attested credential data (which contains the COSE <em>public</em> key). No
 * private key material is ever stored or transmitted.
 */
@Entity
@Table(name = "registered_devices")
public class RegisteredDevice {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "credential_id", nullable = false, unique = true)
    private String credentialId;

    @Column(name = "attested_credential_data", nullable = false)
    private byte[] attestedCredentialData;

    @Column(name = "sign_count", nullable = false)
    private long signCount;

    @Column(name = "transports", length = 255)
    private String transports;

    @Column(name = "label", length = 100)
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private RegisteredDeviceStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by")
    private UUID revokedBy;

    @Column(name = "revoked_reason", length = 255)
    private String revokedReason;

    /** When the credential was last used for an assertion. */
    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    protected RegisteredDevice() {
        // for JPA
    }

    public RegisteredDevice(UUID employeeId, String credentialId, byte[] attestedCredentialData,
                            long signCount, String transports, String label,
                            RegisteredDeviceStatus status) {
        this.id = UUID.randomUUID();
        this.employeeId = employeeId;
        this.credentialId = credentialId;
        this.attestedCredentialData = attestedCredentialData;
        this.signCount = signCount;
        this.transports = transports;
        this.label = label;
        this.status = status;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void approve(UUID actorId, Instant now) {
        this.status = RegisteredDeviceStatus.APPROVED;
        this.approvedBy = actorId;
        this.approvedAt = now;
    }

    public void revoke(RegisteredDeviceStatus terminalStatus, UUID actorId, String reason, Instant now) {
        this.status = terminalStatus;
        this.revokedBy = actorId;
        this.revokedAt = now;
        this.revokedReason = reason;
    }

    public void updateAuthentication(long newSignCount, Instant now) {
        this.signCount = newSignCount;
        this.lastUsedAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEmployeeId() {
        return employeeId;
    }

    public String getCredentialId() {
        return credentialId;
    }

    public byte[] getAttestedCredentialData() {
        return attestedCredentialData;
    }

    public long getSignCount() {
        return signCount;
    }

    public String getTransports() {
        return transports;
    }

    public String getLabel() {
        return label;
    }

    public RegisteredDeviceStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public UUID getApprovedBy() {
        return approvedBy;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public UUID getRevokedBy() {
        return revokedBy;
    }

    public String getRevokedReason() {
        return revokedReason;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }
}
