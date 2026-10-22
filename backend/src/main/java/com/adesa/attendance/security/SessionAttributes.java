package com.adesa.attendance.security;

/**
 * Session attribute keys used by authentication and session validation.
 * Centralised so the login flow and the validating filters cannot drift.
 */
public final class SessionAttributes {

    /** {@link java.util.UUID} of the signed-in employee. */
    public static final String EMPLOYEE_ID = "adesa.employeeId";

    /** Credentials version at the moment of login; compared on every request. */
    public static final String CREDENTIALS_VERSION = "adesa.credentialsVersion";

    /** {@link java.time.Instant} when the session was established. */
    public static final String LOGIN_TIME = "adesa.loginTime";

    private SessionAttributes() {
    }
}
