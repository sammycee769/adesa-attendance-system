import { useState, type FormEvent } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';

import { useAuth } from '../auth/AuthProvider';
import { describeLoginError } from '../utils/errors';
import { ErrorBanner } from '../components/Banners';
import loginHero from '../assets/login-hero.jpeg';

/**
 * Employee login.
 *
 * There is no public registration: accounts are created by HR. The page shows
 * a clear message when the sign-in is refused because of the account, the
 * device or the network.
 */
export function LoginPage() {
  const { user, signIn } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<{ message: string; hint: string | null } | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const from = (location.state as { from?: string } | null)?.from ?? '/';

  if (user) {
    return <Navigate to={from} replace />;
  }

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (submitting) {
      return;
    }
    setError(null);
    setSubmitting(true);
    try {
      await signIn(email.trim(), password);
      navigate(from, { replace: true });
    } catch (cause) {
      setError(describeLoginError(cause, 'The sign-in could not be completed. Please try again.'));
      setPassword('');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="auth-page">
      <section className="card auth-card" aria-labelledby="login-heading">
        <img src={loginHero} alt="ADESA" className="login-hero" />
        <h1 id="login-heading">ADESA HQ Attendance</h1>
        <p className="subtitle">Sign in with your ADESA account.</p>

        <ErrorBanner message={error?.message ?? null} hint={error?.hint ?? null} />

        <form onSubmit={handleSubmit} noValidate>
          <div className="form-field">
            <label htmlFor="email">Work email</label>
            <input
              id="email"
              name="email"
              type="email"
              autoComplete="username"
              autoCapitalize="none"
              autoCorrect="off"
              spellCheck={false}
              inputMode="email"
              required
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              disabled={submitting}
            />
          </div>

          <div className="form-field">
            <label htmlFor="password">Password</label>
            <input
              id="password"
              name="password"
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              disabled={submitting}
            />
          </div>

          <p className="hint">
            <Link to="/forgot-password">Forgot your password?</Link>
          </p>

          <button type="submit" className="button button-primary" disabled={submitting} aria-busy={submitting}>
            {submitting ? 'Signing in…' : 'Sign in'}
          </button>
        </form>

        <p className="hint">
          Attendance can only be recorded from the ADESA office network, and only with an approved device.
        </p>
      </section>
    </div>
  );
}
