package com.adesa.attendance.auth.dto;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Result of {@code navigator.credentials.create()} on the client.
 *
 * <p>{@code credential} is the raw PublicKeyCredential JSON produced by the
 * browser; it is validated by the WebAuthn library (signature, challenge,
 * origin, RP id), never trusted as-is.
 */
public record VerifyRegistrationRequest(
        @NotNull UUID challengeId,
        @NotNull JsonNode credential,
        @Size(max = 100) String label) {
}
