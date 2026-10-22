package com.adesa.attendance.auth.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Issued when a WebAuthn assertion succeeds. The proof id is single-use,
 * bound to the caller's session and consumed by the next attendance action.
 */
public record DeviceAuthenticationResponse(UUID deviceProofId, Instant expiresAt) {
}
