package com.adesa.attendance.common;

/** Thrown when a request violates a business rule (e.g. invalid attendance transition). */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
