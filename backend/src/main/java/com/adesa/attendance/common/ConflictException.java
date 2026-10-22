package com.adesa.attendance.common;

/** Thrown when a request conflicts with existing data (e.g. duplicates). Maps to HTTP 409. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
