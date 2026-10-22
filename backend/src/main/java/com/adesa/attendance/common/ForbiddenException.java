package com.adesa.attendance.common;

/**
 * Thrown when an authenticated request is not permitted by a policy (e.g. the
 * attendance office-network restriction). Maps to HTTP 403.
 *
 * <p>Messages must stay non-sensitive: never include the client address or the
 * approved infrastructure ranges.
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
