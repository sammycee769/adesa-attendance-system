import { apiRequest } from './client';
import type { TokenStatus } from './types';

/**
 * Account setup, password recovery and password change.
 *
 * The forgot-password endpoint always answers the same way; the frontend must
 * never imply whether an address is registered.
 */

/** Requests a recovery e-mail. Always resolves, whatever the address. */
export async function requestPasswordReset(email: string): Promise<void> {
  await apiRequest<void>('/api/v1/auth/forgot-password', {
    method: 'POST',
    body: { email },
  });
}

/** Checks whether a setup/recovery link is still usable. */
export async function checkPasswordToken(token: string): Promise<TokenStatus> {
  return apiRequest<TokenStatus>(
    `/api/v1/auth/password-token?token=${encodeURIComponent(token)}`,
  );
}

/** Completes the setup/recovery flow with a new password. */
export async function setPasswordWithToken(
  token: string,
  newPassword: string,
  confirmPassword: string,
): Promise<void> {
  await apiRequest<void>('/api/v1/auth/reset-password', {
    method: 'POST',
    body: { token, newPassword, confirmPassword },
  });
}

/** Changes the password of the signed-in employee. */
export async function changePassword(
  currentPassword: string,
  newPassword: string,
  confirmPassword: string,
): Promise<void> {
  await apiRequest<void>('/api/v1/auth/change-password', {
    method: 'POST',
    body: { currentPassword, newPassword, confirmPassword },
  });
}
