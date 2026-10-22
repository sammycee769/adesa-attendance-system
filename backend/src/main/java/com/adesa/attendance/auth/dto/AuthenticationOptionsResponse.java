package com.adesa.attendance.auth.dto;

import java.util.List;
import java.util.UUID;

/**
 * Response for {@code POST /auth/webauthn/authenticate/options}.
 *
 * <p>{@code publicKey} follows the W3C {@code PublicKeyCredentialRequestOptions}
 * shape and is passed verbatim to {@code navigator.credentials.get()}.
 */
public record AuthenticationOptionsResponse(UUID challengeId, PublicKeyOptions publicKey) {

    public record PublicKeyOptions(
            String challenge,
            long timeout,
            String rpId,
            String userVerification,
            List<CredentialDescriptor> allowCredentials) {
    }

    public record CredentialDescriptor(String type, String id, List<String> transports) {
    }
}
