package com.adesa.attendance.employee;

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
 * Employee account. Never exposed directly through REST; controllers use DTOs.
 */
@Entity
@Table(name = "employees")
public class Employee {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "employee_number", nullable = false, unique = true, length = 32)
    private String employeeNumber;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    /** Always stored lowercase. */
    @Column(name = "email", nullable = false, unique = true, length = 320)
    private String email;

    /** Encoded password hash (BCrypt). Never a plaintext password. */
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    private EmployeeRole role;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    /**
     * True while the account still has to choose its own password (a freshly
     * created account, or an administrator-forced reset). Enforced server-side:
     * see {@code PasswordChangeRequiredFilter}.
     */
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    /**
     * Bumped whenever a change must invalidate existing sessions: password
     * reset, role change, deactivation, device revocation or replacement.
     */
    @Column(name = "credentials_version", nullable = false)
    private int credentialsVersion = 1;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Employee() {
        // for JPA
    }

    public Employee(String employeeNumber, String fullName, String email, String passwordHash,
                    EmployeeRole role, boolean active) {
        this.id = UUID.randomUUID();
        this.employeeNumber = employeeNumber;
        this.fullName = fullName;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.active = active;
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

    public UUID getId() {
        return id;
    }

    public String getEmployeeNumber() {
        return employeeNumber;
    }

    public String getFullName() {
        return fullName;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public EmployeeRole getRole() {
        return role;
    }

    public boolean isActive() {
        return active;
    }

    public int getCredentialsVersion() {
        return credentialsVersion;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public void setMustChangePassword(boolean mustChangePassword) {
        this.mustChangePassword = mustChangePassword;
    }

    /** Invalidates every existing session for this employee. */
    public void bumpCredentialsVersion() {
        this.credentialsVersion = this.credentialsVersion + 1;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public void setRole(EmployeeRole role) {
        this.role = role;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
