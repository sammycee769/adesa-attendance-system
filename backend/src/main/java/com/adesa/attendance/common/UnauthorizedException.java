package com.adesa.attendance.common;

/**
 * Thrown when authentication is required but missing or invalid. Maps to
 * HTTP 401. Messages must stay generic so they do not help an attacker
 * (e.g. they must not reveal whether an email exists).
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
