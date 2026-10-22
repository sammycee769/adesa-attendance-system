package com.adesa.attendance.audit.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.adesa.attendance.audit.AuditLog;

/**
 * An audit entry as shown to HR and administrators.
 *
 * <p>{@code details} is the JSONB payload written by the action. It never
 * contains passwords, session tokens, cookies or key material.
 */
public record AuditLogResponse(
        UUID id,
        UUID actorId,
        String actorEmployeeNumber,
        String actorName,
        String action,
        String targetType,
        UUID targetId,
        Map<String, Object> details,
        Instant createdAt) {

    public static AuditLogResponse from(AuditLog entry, String actorEmployeeNumber, String actorName) {
        return new AuditLogResponse(
                entry.getId(),
                entry.getActorId(),
                actorEmployeeNumber,
                actorName,
                entry.getAction().name(),
                entry.getTargetType(),
                entry.getTargetId(),
                entry.getDetails(),
                entry.getCreatedAt());
    }
}
