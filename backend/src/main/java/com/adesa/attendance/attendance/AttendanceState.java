package com.adesa.attendance.attendance;

/**
 * Attendance state of the current workday.
 *
 * <p>{@code NOT_SIGNED_IN} means no record exists yet — it never means
 * "absent"; that is an HR reporting decision, not an attendance state.
 */
public enum AttendanceState {
    NOT_SIGNED_IN,
    SIGNED_IN,
    SIGNED_OUT
}
