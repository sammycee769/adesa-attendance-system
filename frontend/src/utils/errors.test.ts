import { describe, expect, it } from 'vitest';

import { ApiError } from '../api/client';
import { describeError, describeLoginError } from './errors';

describe('error descriptions', () => {
  it('appends the offending fields of a validation error', () => {
    const error = new ApiError(400, 'The request was not valid.', [
      { field: 'email', message: 'must be a well-formed email address' },
    ]);

    const described = describeError(error, 'fallback');

    expect(described.message).toContain('The request was not valid.');
    expect(described.message).toContain('email: must be a well-formed email address');
  });

  it('keeps the generic message when there are no field errors', () => {
    const described = describeError(new ApiError(400, 'The request was not valid.'), 'fallback');

    expect(described.message).toBe('The request was not valid.');
  });

  it('never reveals field details on the login page', () => {
    // A field-level error here would be a user-enumeration oracle.
    const error = new ApiError(400, 'The request was not valid.', [
      { field: 'email', message: 'no such account' },
    ]);

    const described = describeLoginError(error, 'fallback');

    expect(described.message).toBe('Email or password is incorrect.');
    expect(described.message).not.toContain('no such account');
  });
});
