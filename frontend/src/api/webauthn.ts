import { startAuthentication, startRegistration } from '@simplewebauthn/browser';

import { apiRequest } from './client';
import { CeremonyError } from './errors';
import type {
  AuthenticationOptions,
  DeviceProof,
  DeviceStatus,
  RegistrationOptions,
} from './types';

/**
 * WebAuthn ceremonies.
 *
 * The backend sends the options in the W3C JSON form with base64url encoded
 * binary values, which is exactly what the client library expects. The
 * library returns the credential in the same JSON form and the backend
 * verifies it (challenge, origin, RP id and signature) — the frontend never
 * decides whether a device is valid.
 */

export { CeremonyError };

function toAuthenticatorTransports(
  transports: string[] | null | undefined,
): AuthenticatorTransport[] | undefined {
  if (!transports || transports.length === 0) {
    return undefined;
  }
  return transports as AuthenticatorTransport[];
}

/** Runs the registration ceremony and returns the credential for the backend. */
export async function runRegistration(
  options: RegistrationOptions,
): Promise<Record<string, unknown>> {
  const optionsJSON = {
    rp: options.publicKey.rp,
    user: options.publicKey.user,
    challenge: options.publicKey.challenge,
    pubKeyCredParams: options.publicKey.pubKeyCredParams,
    timeout: options.publicKey.timeout,
    attestation: options.publicKey.attestation,
    authenticatorSelection: options.publicKey.authenticatorSelection,
    excludeCredentials: options.publicKey.excludeCredentials.map((credential) => ({
      type: credential.type,
      id: credential.id,
      transports: toAuthenticatorTransports(credential.transports),
    })),
  };

  try {
    const response = await startRegistration({
      optionsJSON: optionsJSON as Parameters<typeof startRegistration>[0]['optionsJSON'],
    });
    return response as unknown as Record<string, unknown>;
  } catch {
    throw new CeremonyError(
      'The device registration could not be completed. Please try again and allow the browser prompt.',
    );
  }
}

/** Runs the authentication ceremony and returns the assertion for the backend. */
export async function runAuthentication(
  options: AuthenticationOptions,
): Promise<Record<string, unknown>> {
  const optionsJSON = {
    challenge: options.publicKey.challenge,
    timeout: options.publicKey.timeout,
    rpId: options.publicKey.rpId,
    userVerification: options.publicKey.userVerification,
    allowCredentials: options.publicKey.allowCredentials.map((credential) => ({
      type: credential.type,
      id: credential.id,
      transports: toAuthenticatorTransports(credential.transports),
    })),
  };

  try {
    const response = await startAuthentication({
      optionsJSON: optionsJSON as Parameters<typeof startAuthentication>[0]['optionsJSON'],
    });
    return response as unknown as Record<string, unknown>;
  } catch {
    throw new CeremonyError(
      'The device check was cancelled or failed. Please try again and follow the browser prompt.',
    );
  }
}

/**
 * Registers a device for the signed-in employee. The device is created in
 * `PENDING_APPROVAL` state: an administrator has to approve it before it can
 * be used for attendance.
 */
export async function registerDevice(label?: string): Promise<DeviceStatus> {
  const options = await apiRequest<RegistrationOptions>('/api/v1/auth/webauthn/register/options', {
    method: 'POST',
  });
  const credential = await runRegistration(options);
  return apiRequest<DeviceStatus>('/api/v1/auth/webauthn/register', {
    method: 'POST',
    body: {
      challengeId: options.challengeId,
      credential,
      ...(label ? { label } : {}),
    },
  });
}

/**
 * Proves possession of the approved device and returns the single-use,
 * session-bound device proof that the attendance action consumes.
 */
export async function authenticateWithDevice(): Promise<DeviceProof> {
  const options = await apiRequest<AuthenticationOptions>(
    '/api/v1/auth/webauthn/authenticate/options',
    { method: 'POST' },
  );
  const credential = await runAuthentication(options);
  return apiRequest<DeviceProof>('/api/v1/auth/webauthn/authenticate', {
    method: 'POST',
    body: { challengeId: options.challengeId, credential },
  });
}
