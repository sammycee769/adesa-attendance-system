package com.adesa.attendance.common;

/**
 * Thrown when a request is syntactically valid but cannot be processed (for
 * example a WebAuthn ceremony response that fails verification). Maps to
 * HTTP 400. Messages must stay non-sensitive.
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
