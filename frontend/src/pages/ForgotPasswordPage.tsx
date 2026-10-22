import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';

import { requestPasswordReset } from '../api/account';
import loginHero from '../assets/login-hero.jpeg';

/**
 * "Forgot password" request form.
 *
 * The confirmation is deliberately identical whether or not the address is
 * registered: the page must never disclose which e-mail addresses have
 * accounts.
 */
export function ForgotPasswordPage() {
  const [email, setEmail] = useState('');
  const [submitted, setSubmitted] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (submitting) {
      return;
    }
    setSubmitting(true);
    try {
      await requestPasswordReset(email.trim());
    } catch {
      // Even a transport error must not reveal anything about the address;
      // the same confirmation is shown either way.
    } finally {
      setSubmitting(false);
      // Always show the same neutral confirmation.
      setSubmitted(true);
    }
  };

  return (
    <div className="auth-page">
      <section className="card auth-card" aria-labelledby="forgot-heading">
        <img src={loginHero} alt="ADESA" className="login-hero" />
        <h1 id="forgot-heading">Reset your password</h1>

        {submitted ? (
          <>
            <SuccessMessage />
            <p className="hint">
              <Link to="/login">Back to sign in</Link>
            </p>
          </>
        ) : (
          <>
            <p className="subtitle">
              Enter your ADESA work e-mail and we will send a reset link if the account exists.
            </p>
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
              <button
                type="submit"
                className="button button-primary"
                disabled={submitting}
                aria-busy={submitting}
              >
                {submitting ? 'Sending…' : 'Send reset link'}
              </button>
            </form>
            <p className="hint">
              <Link to="/login">Back to sign in</Link>
            </p>
          </>
        )}
      </section>
    </div>
  );
}

function SuccessMessage() {
  return (
    <div className="banner banner-success" role="status" aria-live="polite">
      <p className="banner-message">
        If an account exists for that address, a reset link has been sent.
      </p>
      <p className="banner-hint">
        Check your inbox (and spam folder). The link can be used once and expires shortly.
      </p>
    </div>
  );
}
