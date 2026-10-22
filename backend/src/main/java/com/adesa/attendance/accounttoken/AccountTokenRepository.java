package com.adesa.attendance.accounttoken;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountTokenRepository extends JpaRepository<AccountToken, UUID> {

    Optional<AccountToken> findByTokenHash(String tokenHash);

    /**
     * Atomically consumes a token.
     *
     * <p>The {@code consumed_at IS NULL} predicate makes the consume a single
     * compare-and-set: two simultaneous requests both matching the same token
     * will each run this statement, but only the first updates a row. The
     * second sees zero affected rows and is rejected, so a token can never be
     * replayed by a race.
     *
     * @return the number of rows updated (1 on success, 0 if already consumed)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update AccountToken t set t.consumedAt = :now "
            + "where t.tokenHash = :tokenHash and t.consumedAt is null")
    int consume(@Param("tokenHash") String tokenHash, @Param("now") Instant now);

    /** Outstanding (unconsumed) tokens of a type, newest first. */
    List<AccountToken> findByEmployeeIdAndTypeAndConsumedAtIsNullOrderByCreatedAtDesc(
            UUID employeeId, AccountTokenType type);

    /** Housekeeping: remove tokens that expired long ago. */
    @Modifying
    @Query("delete from AccountToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
