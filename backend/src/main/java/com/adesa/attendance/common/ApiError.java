package com.adesa.attendance.common;

import java.time.Instant;
import java.util.List;

/**
 * Consistent error response body for all REST endpoints.
 *
 * <p>Messages are safe for clients: no stack traces, no infrastructure
 * details, no secrets.
 */
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        List<FieldViolation> fieldErrors) {

    public record FieldViolation(String field, String message) {
    }

    public static ApiError of(int status, String error, String message, String path) {
        return new ApiError(Instant.now(), status, error, message, path, List.of());
    }

    public static ApiError of(int status, String error, String message, String path,
                              List<FieldViolation> fieldErrors) {
        return new ApiError(Instant.now(), status, error, message, path, fieldErrors);
    }
}
