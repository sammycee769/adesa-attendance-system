package com.adesa.attendance.device;

/**
 * Lifecycle of a registered device.
 *
 * <p>An employee may hold at most one {@link #APPROVED} device and at most one
 * {@link #PENDING_APPROVAL} registration at a time (enforced by partial unique
 * indexes). {@link #REVOKED} and {@link #REPLACED} are terminal for that row;
 * replacement creates a new registration after revocation.
 */
public enum RegisteredDeviceStatus {
    PENDING_APPROVAL,
    APPROVED,
    REVOKED,
    REPLACED
}
