package com.adesa.attendance.device;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RegisteredDeviceRepository extends JpaRepository<RegisteredDevice, UUID> {

    Optional<RegisteredDevice> findByCredentialId(String credentialId);

    Optional<RegisteredDevice> findFirstByEmployeeIdAndStatus(UUID employeeId, RegisteredDeviceStatus status);

    boolean existsByEmployeeIdAndStatus(UUID employeeId, RegisteredDeviceStatus status);

    List<RegisteredDevice> findByEmployeeIdOrderByCreatedAtDesc(UUID employeeId);

    List<RegisteredDevice> findByStatusOrderByCreatedAtAsc(RegisteredDeviceStatus status);

    List<RegisteredDevice> findAllByOrderByCreatedAtDesc();

    Optional<RegisteredDevice> findByIdAndEmployeeId(UUID id, UUID employeeId);

    /**
     * Bulk delete executed immediately as a single DELETE statement.
     *
     * <p>Deliberately not a derived delete: Hibernate executes inserts before
     * deletes within one flush, so a derived delete would let the replacement
     * registration hit the partial unique indexes first.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RegisteredDevice d where d.employeeId = :employeeId and d.status = :status")
    int deleteByEmployeeIdAndStatus(@Param("employeeId") UUID employeeId,
                                    @Param("status") RegisteredDeviceStatus status);

    /** Bulk delete by credential id; see {@link #deleteByEmployeeIdAndStatus}. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RegisteredDevice d where d.credentialId = :credentialId")
    int deleteByCredentialId(@Param("credentialId") String credentialId);

    /**
     * Atomically consumes the row only if it is still an approved device of the
     * given employee; returns the number of rows updated (0 or 1).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RegisteredDevice d set d.status = :terminalStatus, d.revokedBy = :actorId, "
            + "d.revokedAt = :now, d.revokedReason = :reason, d.updatedAt = :now "
            + "where d.id = :id and d.employeeId = :employeeId and d.status = :expectedStatus")
    int revokeIfStatus(@Param("id") UUID id,
                       @Param("employeeId") UUID employeeId,
                       @Param("expectedStatus") RegisteredDeviceStatus expectedStatus,
                       @Param("terminalStatus") RegisteredDeviceStatus terminalStatus,
                       @Param("actorId") UUID actorId,
                       @Param("reason") String reason,
                       @Param("now") Instant now);
}
