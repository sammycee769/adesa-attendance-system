package com.adesa.attendance.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.adesa.attendance.config.AuthProperties;

import org.springframework.stereotype.Component;

/**
 * In-memory limiter for failed password logins.
 *
 * <p>The caller supplies the key: {@code lower-cased email + '|' + source
 * address}. Keying by both prevents an unauthenticated attacker from locking an
 * account out globally by submitting wrong passwords from elsewhere.
 *
 * <p>After {@code auth.login-max-failures} failures within
 * {@code auth.login-window}, further attempts for that key are rejected with
 * HTTP 429 until the window elapses. A successful login clears the counter.
 *
 * <p>The key set is bounded by {@code auth.login-max-tracked-keys}. Expired
 * entries are swept first; if the set is still full, a new key is not tracked
 * (the normal authentication path still rejects the request, only the per-key
 * throttle is unavailable). This prevents an unauthenticated flood of distinct
 * addresses from exhausting heap.
 *
 * <p>Limitation: state is per application instance. A multi-instance
 * deployment needs a shared store (e.g. Redis); this is documented as a
 * limitation to address before horizontal scaling.
 */
@Component
public class LoginRateLimiter {

    private record Failures(AtomicInteger count, Instant firstFailureAt) {
    }

    private final Map<String, Failures> failuresByKey = new ConcurrentHashMap<>();
    private final int maxFailures;
    private final Duration window;
    private final int maxTrackedKeys;

    public LoginRateLimiter(AuthProperties authProperties) {
        this.maxFailures = authProperties.getLoginMaxFailures();
        this.window = authProperties.getLoginWindow();
        this.maxTrackedKeys = authProperties.getLoginMaxTrackedKeys();
    }

    /** True when this key has exhausted its attempts and must be rejected. */
    public boolean isBlocked(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        Failures failures = failuresByKey.get(key);
        if (failures == null) {
            return false;
        }
        if (isExpired(failures)) {
            failuresByKey.remove(key);
            return false;
        }
        return failures.count().get() >= maxFailures;
    }

    /** Records a failed attempt and returns the current failure count. */
    public int recordFailure(String key) {
        if (key == null || key.isBlank()) {
            return 0;
        }
        if (!failuresByKey.containsKey(key) && failuresByKey.size() >= maxTrackedKeys) {
            purgeExpired();
            if (failuresByKey.size() >= maxTrackedKeys) {
                // The key set is full of live entries. Do not grow it further.
                return 1;
            }
        }
        Failures failures = failuresByKey.compute(key, (k, existing) -> {
            if (existing == null || isExpired(existing)) {
                return new Failures(new AtomicInteger(0), Instant.now());
            }
            return existing;
        });
        return failures.count().incrementAndGet();
    }

    /** Clears the counter after a successful login. */
    public void reset(String key) {
        if (key != null && !key.isBlank()) {
            failuresByKey.remove(key);
        }
    }

    /** Seconds until the current window for the key expires (minimum 1). */
    public long retryAfterSeconds(String key) {
        if (key == null || key.isBlank()) {
            return 0;
        }
        Failures failures = failuresByKey.get(key);
        if (failures == null) {
            return 0;
        }
        long remaining = window.minus(Duration.between(failures.firstFailureAt(), Instant.now())).toSeconds();
        return Math.max(remaining, 1);
    }

    /** Removes entries whose window has elapsed. */
    void purgeExpired() {
        Instant now = Instant.now();
        failuresByKey.entrySet().removeIf(entry -> now.isAfter(entry.getValue().firstFailureAt().plus(window)));
    }

    /** Visible for tests: the number of tracked keys. */
    int trackedKeyCount() {
        return failuresByKey.size();
    }

    private boolean isExpired(Failures failures) {
        return Instant.now().isAfter(failures.firstFailureAt().plus(window));
    }
}
