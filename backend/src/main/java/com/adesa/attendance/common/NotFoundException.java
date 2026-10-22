package com.adesa.attendance.common;

/** Thrown when a requested entity does not exist. Maps to HTTP 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
