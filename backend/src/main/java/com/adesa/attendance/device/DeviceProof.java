package com.adesa.attendance.device;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * Single-use, session-bound proof that the holder of a session presented an
 * approved device credential moments ago.
 *
 * <p>Attendance sign-in/sign-out consume a proof; a proof can be used at most
 * once and only from the session it was issued to. This prevents a captured
 * request from being replayed and keeps device verification close in time to
 * the attendance action.
 */
@Entity
@Table(name = "device_proofs")
public class DeviceProof {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "session_hash", nullable = false)
    private String sessionHash;

    @Column(name = "device_id")
    private UUID deviceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    protected DeviceProof() {
        // for JPA
    }

    public DeviceProof(UUID employeeId, String sessionHash, UUID deviceId, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.employeeId = employeeId;
        this.sessionHash = sessionHash;
        this.deviceId = deviceId;
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

    public String getSessionHash() {
        return sessionHash;
    }

    public UUID getDeviceId() {
        return deviceId;
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
