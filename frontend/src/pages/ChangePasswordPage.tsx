import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';

import { changePassword } from '../api/account';
import { ApiError } from '../api/client';
import { useAuth } from '../auth/AuthProvider';
import { ErrorBanner, SuccessBanner } from '../components/Banners';
import { describeError } from '../utils/errors';
import { PASSWORD_HINT, validatePassword } from '../utils/password';

/**
 * Authenticated password change.
 *
 * The current password is always required (except during a forced first-login
 * setup, which the backend routes to the token-based page instead). Changing
 * the password signs out the employee's other sessions; registered passkeys
 * are not affected.
 */
export function ChangePasswordPage() {
  const { user, refresh } = useAuth();
  const navigate = useNavigate();

  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [error, setError] = useState<{ message: string; hint: string | null } | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (submitting) {
      return;
    }
    const local = validatePassword(newPassword);
    if (local) {
      setError({ message: local, hint: null });
      return;
    }
    if (newPassword !== confirmPassword) {
      setError({ message: 'The two passwords do not match.', hint: null });
      return;
    }
    setError(null);
    setSuccess(null);
    setSubmitting(true);
    try {
      await changePassword(currentPassword, newPassword, confirmPassword);
      setCurrentPassword('');
      setNewPassword('');
      setConfirmPassword('');
      // The forced-change state may have just been cleared.
      await refresh();
      setSuccess('Your password has been changed. Your other sessions have been signed out.');
    } catch (cause) {
      if (cause instanceof ApiError && cause.isUnauthorized) {
        navigate('/login', { replace: true });
        return;
      }
      setError(describeError(cause, 'The password could not be changed.'));
    } finally {
      setSubmitting(false);
    }
  };

  const forced = user?.mustChangePassword ?? false;

  return (
    <div className="dashboard">
      <h1>Change password</h1>

      {forced ? (
        <div className="banner banner-warning" role="alert">
          <p className="banner-message">You must set a new password before using the application.</p>
        </div>
      ) : null}

      <ErrorBanner message={error?.message ?? null} hint={error?.hint ?? null} />
      <SuccessBanner message={success} />

      <section className="card" aria-labelledby="change-password-heading">
        <h2 id="change-password-heading">Set a new password</h2>
        <form onSubmit={handleSubmit} noValidate>
          <div className="form-field">
            <label htmlFor="current-password">Current password</label>
            <input
              id="current-password"
              name="current-password"
              type="password"
              autoComplete="current-password"
              required
              value={currentPassword}
              onChange={(event) => setCurrentPassword(event.target.value)}
              disabled={submitting}
            />
          </div>
          <div className="form-field">
            <label htmlFor="new-password">New password</label>
            <input
              id="new-password"
              name="new-password"
              type="password"
              autoComplete="new-password"
              required
              value={newPassword}
              onChange={(event) => setNewPassword(event.target.value)}
              disabled={submitting}
            />
            <p className="hint">{PASSWORD_HINT}</p>
          </div>
          <div className="form-field">
            <label htmlFor="confirm-password">Confirm new password</label>
            <input
              id="confirm-password"
              name="confirm-password"
              type="password"
              autoComplete="new-password"
              required
              value={confirmPassword}
              onChange={(event) => setConfirmPassword(event.target.value)}
              disabled={submitting}
            />
          </div>
          <button
            type="submit"
            className="button button-primary"
            disabled={submitting}
            aria-busy={submitting}
          >
            {submitting ? 'Changing…' : 'Change password'}
          </button>
        </form>
      </section>
    </div>
  );
}
