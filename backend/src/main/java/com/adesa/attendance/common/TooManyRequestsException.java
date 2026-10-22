package com.adesa.attendance.common;

/**
 * Thrown when a caller exceeds a rate limit (for example repeated failed
 * logins). Maps to HTTP 429 and carries the number of seconds a client should
 * wait before retrying.
 */
public class TooManyRequestsException extends RuntimeException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(String message, long retryAfterSeconds) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
