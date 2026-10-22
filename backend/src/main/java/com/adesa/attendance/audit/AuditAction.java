package com.adesa.attendance.audit;

/**
 * Auditable actions. Stored as strings in the database; add new values by
 * appending (never reusing) constants.
 */
public enum AuditAction {

    // Employee lifecycle
    EMPLOYEE_CREATED,
    EMPLOYEE_UPDATED,
    EMPLOYEE_ACTIVATED,
    EMPLOYEE_DEACTIVATED,
    /**
     * A previously deactivated administrator account was reactivated by an
     * authorized administrator through the dedicated reactivation action.
     */
    EMPLOYEE_REACTIVATED,
    /**
     * An attempt to deactivate or reactivate an account was refused because it
     * would violate the administrator-account business rules.
     */
    EMPLOYEE_ACTIVATION_REJECTED,
    EMPLOYEE_ROLE_CHANGED,
    EMPLOYEE_PASSWORD_RESET,
    FIRST_ADMIN_BOOTSTRAPPED,

    // Password setup / recovery (Phase: password security)
    /** An administrator created an account that must set its own password. */
    EMPLOYEE_ACCOUNT_SETUP_ISSUED,
    /** An employee completed the emailed setup link and chose a password. */
    EMPLOYEE_PASSWORD_SET_UP,
    /** A password-recovery link was requested (recorded without account existence). */
    PASSWORD_RECOVERY_REQUESTED,
    /** A password was reset through a valid recovery link. */
    PASSWORD_RECOVERY_COMPLETED,
    /** A signed-in employee changed their own password. */
    PASSWORD_CHANGED,
    /** A recovery/setup link was rejected (expired, invalid or already used). */
    PASSWORD_RESET_REJECTED,
    /** Forced password change still outstanding: an action was refused. */
    PASSWORD_CHANGE_REQUIRED_REJECTED,

    // Authentication
    LOGIN_SUCCEEDED,
    LOGIN_FAILED,
    LOGIN_RATE_LIMITED,
    LOGOUT,
    SESSION_REJECTED,

    // Device lifecycle
    DEVICE_REGISTERED,
    DEVICE_AUTHENTICATED,
    DEVICE_APPROVED,
    DEVICE_REVOKED,
    DEVICE_REPLACED,

    // Attendance
    ATTENDANCE_SIGNED_IN,
    ATTENDANCE_SIGNED_OUT,
    /** An attendance action that was refused (network, device proof, sequence). */
    ATTENDANCE_ACTION_REJECTED,
    /** A manual correction of an attendance record by HR or an administrator. */
    ATTENDANCE_CORRECTED
}
