/**
 * Errors raised by the frontend itself (as opposed to the API).
 *
 * They live in their own module so that they can be imported without pulling
 * in the API modules (which are replaced by test doubles).
 */

/** Raised when a browser WebAuthn ceremony could not be completed. */
export class CeremonyError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'CeremonyError';
  }
}
