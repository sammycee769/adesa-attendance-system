package com.adesa.attendance.accounttoken;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.adesa.attendance.config.AccountProperties;

import org.springframework.stereotype.Component;

/**
 * In-memory limiter for the public password-recovery endpoints, keyed by the
 * resolved client address.
 *
 * <p>Bounds both the "forgot password" submission and repeated token
 * verification attempts so the endpoints cannot be used to spray e-mails or to
 * brute-force a token. The key set is bounded, mirroring
 * {@code LoginRateLimiter}, so an unauthenticated flood cannot grow the heap.
 *
 * <p>Limitation: state is per instance (same as the login limiter); a
 * multi-instance deployment needs a shared store.
 */
@Component
public class RecoveryRateLimiter {

    private record Attempts(AtomicInteger count, Instant firstAttemptAt) {
    }

    private final Map<String, Attempts> attemptsByKey = new ConcurrentHashMap<>();
    private final int maxAttempts;
    private final Duration window;
    private final int maxTrackedKeys;

    public RecoveryRateLimiter(AccountProperties accountProperties) {
        this.maxAttempts = accountProperties.getRecoveryMaxAttempts();
        this.window = accountProperties.getRecoveryWindow();
        this.maxTrackedKeys = 10_000;
    }

    public boolean isBlocked(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        Attempts attempts = attemptsByKey.get(key);
        if (attempts == null) {
            return false;
        }
        if (isExpired(attempts)) {
            attemptsByKey.remove(key);
            return false;
        }
        return attempts.count().get() >= maxAttempts;
    }

    public void recordAttempt(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        if (!attemptsByKey.containsKey(key) && attemptsByKey.size() >= maxTrackedKeys) {
            purgeExpired();
            if (attemptsByKey.size() >= maxTrackedKeys) {
                return;
            }
        }
        attemptsByKey.compute(key, (k, existing) -> {
            if (existing == null || isExpired(existing)) {
                return new Attempts(new AtomicInteger(0), Instant.now());
            }
            existing.count().incrementAndGet();
            return existing;
        });
    }

    public long retryAfterSeconds(String key) {
        if (key == null || key.isBlank()) {
            return 0;
        }
        Attempts attempts = attemptsByKey.get(key);
        if (attempts == null) {
            return 0;
        }
        long remaining = window.minus(Duration.between(attempts.firstAttemptAt(), Instant.now())).toSeconds();
        return Math.max(remaining, 1);
    }

    void purgeExpired() {
        Instant now = Instant.now();
        attemptsByKey.entrySet().removeIf(entry -> now.isAfter(entry.getValue().firstAttemptAt().plus(window)));
    }

    int trackedKeyCount() {
        return attemptsByKey.size();
    }

    private boolean isExpired(Attempts attempts) {
        return Instant.now().isAfter(attempts.firstAttemptAt().plus(window));
    }
}
