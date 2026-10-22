package com.adesa.attendance.auth.dto;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.constraints.NotNull;

/**
 * Result of {@code navigator.credentials.get()} on the client. Validated by the
 * WebAuthn library, never trusted as-is.
 */
public record VerifyAuthenticationRequest(
        @NotNull UUID challengeId,
        @NotNull JsonNode credential) {
}
