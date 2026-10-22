import { useEffect, useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';

import { checkPasswordToken, setPasswordWithToken } from '../api/account';
import { ApiError } from '../api/client';
import { ErrorBanner, SuccessBanner } from '../components/Banners';
import { useAuth } from '../auth/AuthProvider';
import { describeError } from '../utils/errors';
import { PASSWORD_HINT, validatePassword } from '../utils/password';
import loginHero from '../assets/login-hero.jpeg';

/**
 * Account setup and password reset.
 *
 * The same page serves both flows; the linked token decides which. Before the
 * form is shown the token is validated server-side so an expired or already
 * used link gets a clear, non-scary explanation instead of a broken form.
 */
export function SetPasswordPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const { refresh } = useAuth();

  const token = params.get('token') ?? '';
  const [status, setStatus] = useState<'checking' | 'valid' | 'invalid'>('checking');
  const [tokenType, setTokenType] = useState<'ACCOUNT_SETUP' | 'PASSWORD_RECOVERY' | null>(null);

  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [error, setError] = useState<{ message: string; hint: string | null } | null>(null);
  const [done, setDone] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    let active = true;
    (async () => {
      if (!token) {
        if (active) {
          setStatus('invalid');
        }
        return;
      }
      try {
        const result = await checkPasswordToken(token);
        if (!active) {
          return;
        }
        if (result.valid) {
          setTokenType(result.type);
          setStatus('valid');
        } else {
          setStatus('invalid');
        }
      } catch {
        if (active) {
          setStatus('invalid');
        }
      }
    })();
    return () => {
      active = false;
    };
  }, [token]);

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
    setSubmitting(true);
    try {
      await setPasswordWithToken(token, newPassword, confirmPassword);
      // If this flow was reached while signed in (e.g. an admin forced a
      // reset), refresh the identity so the app knows the change is done.
      await refresh();
      setDone(true);
      // Send the employee to sign in with their new password.
      setTimeout(() => navigate('/login', { replace: true }), 2500);
    } catch (cause) {
      if (cause instanceof ApiError && cause.status === 400) {
        // An invalid/expired token is a terminal state for this page.
        setStatus('invalid');
        setError(describeError(cause, 'This link is invalid or has expired.'));
      } else {
        setError(describeError(cause, 'The password could not be set. Please try again.'));
      }
    } finally {
      setSubmitting(false);
    }
  };

  const heading = tokenType === 'ACCOUNT_SETUP' ? 'Set up your account' : 'Choose a new password';

  return (
    <div className="auth-page">
      <section className="card auth-card" aria-labelledby="set-password-heading">
        <img src={loginHero} alt="ADESA" className="login-hero" />
        <h1 id="set-password-heading">{heading}</h1>

        {status === 'checking' ? (
          <p className="page-status" role="status" aria-live="polite">
            Checking your link…
          </p>
        ) : null}

        {status === 'invalid' ? (
          <>
            <ErrorBanner
              message="This link is invalid or has expired."
              hint="Request a new link and use it within the time shown in the e-mail."
            />
            <p className="hint">
              <Link to="/forgot-password">Request a new link</Link>
            </p>
            <p className="hint">
              <Link to="/login">Back to sign in</Link>
            </p>
          </>
        ) : null}

        {status === 'valid' && !done ? (
          <>
            <p className="subtitle">
              Choose a password for your ADESA HQ Attendance account.
            </p>
            <ErrorBanner message={error?.message ?? null} hint={error?.hint ?? null} />
            <form onSubmit={handleSubmit} noValidate>
              <div className="form-field">
                <label htmlFor="new-password">New password</label>
                <input
                  id="new-password"
                  name="new-password"
                  type="password"
                  autoComplete="new-password"
                  required
                  autoFocus
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
                {submitting ? 'Saving…' : 'Save password'}
              </button>
            </form>
          </>
        ) : null}

        {done ? (
          <>
            <SuccessBanner message="Your password has been set. Redirecting to sign in…" />
            <p className="hint">
              <Link to="/login">Sign in now</Link>
            </p>
          </>
        ) : null}
      </section>
    </div>
  );
}
