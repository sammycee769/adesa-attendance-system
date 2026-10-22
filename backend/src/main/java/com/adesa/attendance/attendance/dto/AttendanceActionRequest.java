package com.adesa.attendance.attendance.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * Body of an attendance sign-in / sign-out request.
 *
 * <p>The employee identity is never part of the body: it is always taken from
 * the authenticated session. The device proof id identifies the single-use,
 * session-bound proof of a recent WebAuthn assertion with the employee's
 * approved device.
 */
public record AttendanceActionRequest(@NotNull UUID deviceProofId) {
}
