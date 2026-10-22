package com.adesa.attendance.auth.dto;

import java.util.List;
import java.util.UUID;

/**
 * Response for {@code POST /auth/webauthn/register/options}.
 *
 * <p>{@code publicKey} follows the W3C {@code PublicKeyCredentialCreationOptions}
 * shape and is passed verbatim to {@code navigator.credentials.create()}.
 * {@code challengeId} identifies the server-side single-use challenge and must
 * be returned with the credential.
 */
public record RegistrationOptionsResponse(UUID challengeId, PublicKeyOptions publicKey) {

    public record PublicKeyOptions(
            RelyingParty rp,
            UserEntity user,
            String challenge,
            List<PubKeyCredParam> pubKeyCredParams,
            long timeout,
            String attestation,
            AuthenticatorSelection authenticatorSelection,
            List<CredentialDescriptor> excludeCredentials) {
    }

    /** {@code id}/{@code name} per the WebAuthn relying-party entity. */
    public record RelyingParty(String id, String name) {
    }

    /** {@code id} is the base64url-encoded user handle (never PII). */
    public record UserEntity(String id, String name, String displayName) {
    }

    public record PubKeyCredParam(String type, int alg) {
    }

    public record AuthenticatorSelection(String residentKey, String userVerification) {
    }

    public record CredentialDescriptor(String type, String id, List<String> transports) {
    }
}
