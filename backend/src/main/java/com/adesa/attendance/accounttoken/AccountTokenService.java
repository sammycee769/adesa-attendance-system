package com.adesa.attendance.accounttoken;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import com.adesa.attendance.config.AccountProperties;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and consumes single-use account tokens.
 *
 * <p>Tokens are 256 bits from {@link SecureRandom}, encoded as URL-safe
 * base64url without padding. Only the SHA-256 hash is persisted, so the raw
 * token lives solely in the e-mail. Consumption is atomic
 * ({@link AccountTokenRepository#consume}).
 */
@Service
public class AccountTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();
    /** 32 bytes = 256 bits of entropy. */
    private static final int TOKEN_BYTES = 32;
    /** Issuing more than this many times in a row retires the older tokens. */
    private static final int MAX_OUTSTANDING_PER_TYPE = 3;

    private final AccountTokenRepository repository;
    private final AccountProperties accountProperties;

    public AccountTokenService(AccountTokenRepository repository, AccountProperties accountProperties) {
        this.repository = repository;
        this.accountProperties = accountProperties;
    }

    /**
     * Issues a fresh token for an employee, retiring any earlier outstanding
     * tokens of the same type beyond a small cap so a re-send does not leave a
     * trail of valid links.
     *
     * @return the raw token (only ever handed to the mail layer, never stored)
     */
    @Transactional
    public String issue(UUID employeeId, AccountTokenType type) {
        Instant expiresAt = Instant.now().plus(ttlFor(type));
        String rawToken = randomToken();
        repository.save(new AccountToken(employeeId, hash(rawToken), type, expiresAt));
        expireSuperseded(employeeId, type);
        return rawToken;
    }

    /**
     * Atomically consumes a presented token.
     *
     * @return the employee id the token belongs to, or empty when the token is
     *         unknown, expired or already used. The caller must treat all three
     *         cases identically so it cannot be used to probe for accounts.
     */
    @Transactional
    public Optional<UUID> consume(String rawToken, AccountTokenType expectedType) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        String tokenHash = hash(rawToken);
        Instant now = Instant.now();
        Optional<AccountToken> found = repository.findByTokenHash(tokenHash);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        AccountToken token = found.get();
        if (token.getType() != expectedType || token.isStale(now)) {
            return Optional.empty();
        }
        // Compare-and-set: only the first concurrent request updates a row.
        int updated = repository.consume(tokenHash, now);
        if (updated != 1) {
            return Optional.empty();
        }
        return Optional.of(token.getEmployeeId());
    }

    /** True when the token exists, matches the type, and has not expired or been used. */
    @Transactional(readOnly = true)
    public boolean isUsable(String rawToken, AccountTokenType expectedType) {
        if (rawToken == null || rawToken.isBlank()) {
            return false;
        }
        return repository.findByTokenHash(hash(rawToken))
                .filter(token -> token.getType() == expectedType)
                .filter(token -> token.isUsable(Instant.now()))
                .isPresent();
    }

    /** Removes tokens that expired more than a day ago (housekeeping). */
    @Transactional
    public int purgeExpired() {
        return repository.deleteExpiredBefore(Instant.now().minus(java.time.Duration.ofDays(1)));
    }

    private void expireSuperseded(UUID employeeId, AccountTokenType type) {
        var outstanding = repository.findByEmployeeIdAndTypeAndConsumedAtIsNullOrderByCreatedAtDesc(employeeId, type);
        // Keep the newest MAX_OUTSTANDING_PER_TYPE-1 and retire the rest by
        // marking them consumed, so an old link stops working promptly.
        for (int i = MAX_OUTSTANDING_PER_TYPE - 1; i < outstanding.size(); i++) {
            AccountToken stale = outstanding.get(i);
            repository.consume(stale.getTokenHash(), Instant.now());
        }
    }

    private java.time.Duration ttlFor(AccountTokenType type) {
        return type == AccountTokenType.ACCOUNT_SETUP
                ? accountProperties.getSetupTokenTtl()
                : accountProperties.getRecoveryTokenTtl();
    }

    private static String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 hex. Deterministic so a look-up by hash is possible. */
    static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandatory in every JVM; this cannot happen.
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
