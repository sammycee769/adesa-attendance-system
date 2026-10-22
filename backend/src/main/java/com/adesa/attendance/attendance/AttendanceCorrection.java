package com.adesa.attendance.attendance;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * An immutable entry describing one manual correction of an attendance record.
 *
 * <p>Corrections are only performed by HR or an administrator, always with a
 * mandatory reason. The attendance record keeps the current values; this table
 * keeps the previous ones, so a correction never silently overwrites history.
 */
@Entity
@Table(name = "attendance_corrections")
public class AttendanceCorrection {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "attendance_record_id", nullable = false)
    private UUID attendanceRecordId;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "previous_sign_in_at")
    private Instant previousSignInAt;

    @Column(name = "previous_sign_out_at")
    private Instant previousSignOutAt;

    @Column(name = "corrected_sign_in_at", nullable = false)
    private Instant correctedSignInAt;

    @Column(name = "corrected_sign_out_at")
    private Instant correctedSignOutAt;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    /** The HR officer or administrator who applied the correction. */
    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AttendanceCorrection() {
        // for JPA
    }

    public AttendanceCorrection(UUID attendanceRecordId, UUID employeeId,
                                Instant previousSignInAt, Instant previousSignOutAt,
                                Instant correctedSignInAt, Instant correctedSignOutAt,
                                String reason, UUID actorId) {
        this.id = UUID.randomUUID();
        this.attendanceRecordId = attendanceRecordId;
        this.employeeId = employeeId;
        this.previousSignInAt = previousSignInAt;
        this.previousSignOutAt = previousSignOutAt;
        this.correctedSignInAt = correctedSignInAt;
        this.correctedSignOutAt = correctedSignOutAt;
        this.reason = reason;
        this.actorId = actorId;
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

    public UUID getAttendanceRecordId() {
        return attendanceRecordId;
    }

    public UUID getEmployeeId() {
        return employeeId;
    }

    public Instant getPreviousSignInAt() {
        return previousSignInAt;
    }

    public Instant getPreviousSignOutAt() {
        return previousSignOutAt;
    }

    public Instant getCorrectedSignInAt() {
        return correctedSignInAt;
    }

    public Instant getCorrectedSignOutAt() {
        return correctedSignOutAt;
    }

    public String getReason() {
        return reason;
    }

    public UUID getActorId() {
        return actorId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
