import { ApiError } from '../api/client';
import { CeremonyError } from '../api/errors';

/**
 * Turns a failed request into a message the employee can act on.
 *
 * The backend messages are written for clients (no stack traces, no
 * infrastructure detail), so they are shown as they are. Only the additional
 * hint is added here.
 */
export interface ErrorDescription {
  message: string;
  hint: string | null;
}

const NETWORK_HINT =
  'Sign in again from the ADESA office connection (office Wi-Fi). Attendance cannot be recorded from outside the office network.';

const DEVICE_HINT =
  'Register this device and ask an administrator to approve it before recording attendance.';

const ACCOUNT_HINT = 'Contact the HR department if your account should be active.';

const RATE_LIMIT_HINT = 'Wait a few minutes before trying again.';

/** An error inside the signed-in area of the application. */
export function describeError(error: unknown, fallback: string): ErrorDescription {
  const description = describeSignedInError(error, fallback);
  if (error instanceof ApiError && error.fieldErrors.length > 0) {
    // The backend names the offending field(s); surface them so an administrator
    // is not left guessing which input the server refused.
    const detail = error.fieldErrors
      .map((violation) => (violation.field ? `${violation.field}: ${violation.message}` : violation.message))
      .join(' ');
    return { message: `${description.message} ${detail}`.trim(), hint: description.hint };
  }
  return description;
}

function describeSignedInError(error: unknown, fallback: string): ErrorDescription {
  if (error instanceof CeremonyError) {
    return { message: error.message, hint: null };
  }
  if (error instanceof ApiError) {
    if (error.status === 0) {
      return { message: error.message, hint: null };
    }
    if (error.isUnauthorized) {
      return {
        message: 'Your session has expired. Please sign in again.',
        hint: null,
      };
    }
    if (error.status === 429) {
      return { message: error.message, hint: RATE_LIMIT_HINT };
    }
    if (error.isForbidden) {
      if (/office network/i.test(error.message)) {
        return { message: error.message, hint: NETWORK_HINT };
      }
      if (/device/i.test(error.message)) {
        return { message: error.message, hint: DEVICE_HINT };
      }
      if (/account/i.test(error.message)) {
        return { message: error.message, hint: ACCOUNT_HINT };
      }
      return { message: error.message, hint: null };
    }
    return { message: error.message, hint: null };
  }
  return { message: fallback, hint: null };
}

/**
 * An error on the login page.
 *
 * A refused sign-in always shows the same message: the application never
 * reveals whether the account exists.
 */
export function describeLoginError(error: unknown, fallback: string): ErrorDescription {
  if (error instanceof ApiError) {
    if (error.status === 0) {
      return { message: error.message, hint: null };
    }
    if (error.status === 400 || error.status === 401) {
      return { message: 'Email or password is incorrect.', hint: null };
    }
    if (error.status === 429) {
      return { message: error.message, hint: RATE_LIMIT_HINT };
    }
    if (error.isForbidden) {
      return { message: error.message, hint: ACCOUNT_HINT };
    }
    return { message: error.message, hint: null };
  }
  return { message: fallback, hint: null };
}
