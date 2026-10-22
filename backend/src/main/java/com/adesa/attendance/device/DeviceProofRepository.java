package com.adesa.attendance.device;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeviceProofRepository extends JpaRepository<DeviceProof, UUID> {

    Optional<DeviceProof> findById(UUID id);

    /**
     * Single-use, session-bound consumption. Matches only when the proof exists,
     * belongs to the employee and session, has not been consumed and has not
     * expired. Returns the number of rows updated (0 or 1); a return value of 0
     * means the proof is invalid, expired, already used or from another session.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update DeviceProof p set p.consumedAt = :now where p.id = :id "
            + "and p.employeeId = :employeeId and p.sessionHash = :sessionHash "
            + "and p.consumedAt is null and p.expiresAt > :now")
    int consume(@Param("id") UUID id,
                @Param("employeeId") UUID employeeId,
                @Param("sessionHash") String sessionHash,
                @Param("now") Instant now);
}
