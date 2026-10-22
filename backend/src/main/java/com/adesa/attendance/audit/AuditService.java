package com.adesa.attendance.audit;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records audit entries. Runs in the caller's transaction so an audited change
 * and its audit entry commit (or roll back) together.
 */
@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID actorId, AuditAction action, String targetType, UUID targetId,
                       Map<String, Object> details) {
        auditLogRepository.save(new AuditLog(actorId, action, targetType, targetId, details));
    }

    /**
     * Records an audit entry in its own transaction.
     *
     * <p>Used for events that must be persisted even when the surrounding
     * request fails — most importantly failed logins and rate-limit rejections,
     * which end in an exception (and therefore a rollback) after the entry is
     * written.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordInNewTransaction(UUID actorId, AuditAction action, String targetType, UUID targetId,
                                       Map<String, Object> details) {
        auditLogRepository.save(new AuditLog(actorId, action, targetType, targetId, details));
    }
}
