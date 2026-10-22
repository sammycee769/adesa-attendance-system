package com.adesa.attendance.auth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WebAuthnChallengeRepository extends JpaRepository<WebAuthnChallenge, UUID> {

    Optional<WebAuthnChallenge> findByIdAndEmployeeId(UUID id, UUID employeeId);

    /**
     * Atomically consumes the challenge only if it belongs to the employee and
     * ceremony, has not been consumed and has not expired. Returns the number of
     * rows updated (0 or 1); 0 means the challenge is unknown, expired, already
     * used or of the wrong type.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update WebAuthnChallenge c set c.consumedAt = :now where c.id = :id "
            + "and c.employeeId = :employeeId and c.type = :type "
            + "and c.consumedAt is null and c.expiresAt > :now")
    int consume(@Param("id") UUID id,
                @Param("employeeId") UUID employeeId,
                @Param("type") WebAuthnChallengeType type,
                @Param("now") Instant now);
}
