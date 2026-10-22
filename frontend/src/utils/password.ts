/**
 * Password strength rules, mirroring the backend `PasswordPolicy` exactly.
 *
 * The frontend only gives immediate feedback; the server re-checks every
 * password, so the two must never disagree on the wording.
 */

/** Must match `adesa.account.password-min-length` (PASSWORD_MIN_LENGTH). */
export const PASSWORD_MIN_LENGTH = 12;

/** Must match `adesa.account.password-max-length`. */
export const PASSWORD_MAX_LENGTH = 128;

const WEAK = new Set([
  'password',
  'password1',
  'password12',
  'password123',
  'password1234',
  'passw0rd',
  'passw0rd1',
  'passw0rd12',
  '123456789012',
  'qwertyuiop12',
  'administrator',
  'administrator1',
  'adesa1234567',
  'adesapassword',
  'letmein12345',
  'welcome12345',
  'changeme1234',
  'iloveyou1234',
]);

/** Returns the reason a password is unacceptable, or null when it is valid. */
export function validatePassword(password: string): string | null {
  if (!password) {
    return 'A password is required.';
  }
  if (password.length < PASSWORD_MIN_LENGTH) {
    return `The password must have at least ${PASSWORD_MIN_LENGTH} characters.`;
  }
  if (password.length > PASSWORD_MAX_LENGTH) {
    return `The password must have at most ${PASSWORD_MAX_LENGTH} characters.`;
  }
  if (WEAK.has(password.toLowerCase())) {
    return 'This password is too common. Choose a different one.';
  }
  const hasLetter = /[a-zA-Z]/.test(password);
  const hasDigit = /[0-9]/.test(password);
  if (!hasLetter || !hasDigit) {
    return 'The password must contain at least one letter and one digit.';
  }
  return null;
}

/** A short human hint shown under the new-password fields. */
export const PASSWORD_HINT =
  `At least ${PASSWORD_MIN_LENGTH} characters, including a letter and a digit.`;
