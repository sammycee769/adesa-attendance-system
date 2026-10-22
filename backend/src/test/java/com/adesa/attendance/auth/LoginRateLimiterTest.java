package com.adesa.attendance.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import com.adesa.attendance.config.AuthProperties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the in-memory failed-login limiter. No Spring context and no
 * database: the limiter is pure policy, so it is exercised directly.
 */
class LoginRateLimiterTest {

    private static LoginRateLimiter limiter(int maxFailures, Duration window) {
        AuthProperties properties = new AuthProperties();
        properties.setLoginMaxFailures(maxFailures);
        properties.setLoginWindow(window);
        return new LoginRateLimiter(properties);
    }

    @Test
    @DisplayName("A key is blocked only once it reaches the configured failure limit")
    void blocksOnlyAfterMaxFailures() {
        LoginRateLimiter limiter = limiter(3, Duration.ofMinutes(15));

        assertThat(limiter.recordFailure("a@b.test")).isEqualTo(1);
        assertThat(limiter.isBlocked("a@b.test")).isFalse();
        assertThat(limiter.recordFailure("a@b.test")).isEqualTo(2);
        assertThat(limiter.isBlocked("a@b.test")).isFalse();
        assertThat(limiter.recordFailure("a@b.test")).isEqualTo(3);

        assertThat(limiter.isBlocked("a@b.test")).isTrue();
    }

    @Test
    @DisplayName("Failures are counted per key, not globally")
    void failuresArePerKey() {
        LoginRateLimiter limiter = limiter(2, Duration.ofMinutes(15));

        limiter.recordFailure("first@b.test");
        limiter.recordFailure("first@b.test");

        assertThat(limiter.isBlocked("first@b.test")).isTrue();
        assertThat(limiter.isBlocked("second@b.test")).isFalse();
        assertThat(limiter.recordFailure("second@b.test")).isEqualTo(1);
    }

    @Test
    @DisplayName("A successful login resets the counter")
    void resetClearsCounter() {
        LoginRateLimiter limiter = limiter(2, Duration.ofMinutes(15));

        limiter.recordFailure("a@b.test");
        limiter.recordFailure("a@b.test");
        assertThat(limiter.isBlocked("a@b.test")).isTrue();

        limiter.reset("a@b.test");

        assertThat(limiter.isBlocked("a@b.test")).isFalse();
        assertThat(limiter.recordFailure("a@b.test")).isEqualTo(1);
    }

    @Test
    @DisplayName("retryAfterSeconds is 0 for unknown keys and capped at the window otherwise")
    void retryAfterSeconds() {
        LoginRateLimiter limiter = limiter(3, Duration.ofSeconds(60));

        assertThat(limiter.retryAfterSeconds("unknown@b.test")).isZero();

        limiter.recordFailure("a@b.test");
        assertThat(limiter.retryAfterSeconds("a@b.test"))
                .isGreaterThan(0)
                .isLessThanOrEqualTo(60);
    }

    @Test
    @DisplayName("Failures older than the window expire and no longer block")
    void windowExpiryUnblocks() throws InterruptedException {
        LoginRateLimiter limiter = limiter(2, Duration.ofMillis(50));

        limiter.recordFailure("a@b.test");
        limiter.recordFailure("a@b.test");
        assertThat(limiter.isBlocked("a@b.test")).isTrue();

        Thread.sleep(200);

        assertThat(limiter.isBlocked("a@b.test")).isFalse();
        // A new attempt starts a fresh window rather than continuing the old count.
        assertThat(limiter.recordFailure("a@b.test")).isEqualTo(1);
    }

    @Test
    @DisplayName("The tracked key set is bounded")
    void trackedKeysAreBounded() {
        AuthProperties properties = new AuthProperties();
        properties.setLoginMaxFailures(2);
        properties.setLoginWindow(Duration.ofMinutes(15));
        properties.setLoginMaxTrackedKeys(2);
        LoginRateLimiter limiter = new LoginRateLimiter(properties);

        limiter.recordFailure("first@b.test");
        limiter.recordFailure("second@b.test");
        // The third distinct key must not grow the map without bound.
        limiter.recordFailure("third@b.test");

        assertThat(limiter.trackedKeyCount()).isEqualTo(2);
        assertThat(limiter.isBlocked("third@b.test")).isFalse();
        // Already-tracked keys keep their counters.
        limiter.recordFailure("first@b.test");
        assertThat(limiter.isBlocked("first@b.test")).isTrue();
    }

    @Test
    @DisplayName("Blank keys are ignored")
    void blankKeysAreIgnored() {
        LoginRateLimiter limiter = limiter(2, Duration.ofMinutes(15));

        assertThat(limiter.recordFailure("")).isZero();
        assertThat(limiter.recordFailure(null)).isZero();
        assertThat(limiter.isBlocked("")).isFalse();
        assertThat(limiter.isBlocked(null)).isFalse();
        assertThat(limiter.trackedKeyCount()).isZero();
    }
}
