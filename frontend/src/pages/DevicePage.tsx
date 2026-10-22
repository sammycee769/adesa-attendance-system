import { useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';

import { ApiError } from '../api/client';
import { registerDevice } from '../api/webauthn';
import { useAuth } from '../auth/AuthProvider';
import { DeviceStatusCard } from '../components/DeviceStatusCard';
import { ErrorBanner, SuccessBanner } from '../components/Banners';
import { describeError } from '../utils/errors';

/**
 * Device setup.
 *
 * Registering a device creates it in "waiting for approval" state: an
 * administrator has to approve it before attendance can be recorded. A device
 * that is already approved can only be replaced by an administrator.
 */
export function DevicePage() {
  const { user, applyDevice } = useAuth();
  const navigate = useNavigate();

  const [label, setLabel] = useState('');
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<{ message: string; hint: string | null } | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  const device = user?.device ?? null;
  const status = device?.status ?? 'NONE';
  const canRegister = status === 'NONE' || status === 'REVOKED' || status === 'REPLACED';

  const handleRegister = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (pending) {
      return;
    }
    setPending(true);
    setError(null);
    setSuccess(null);
    try {
      const updated = await registerDevice(label.trim() || undefined);
      applyDevice(updated);
      setLabel('');
      setSuccess(
        'The device was registered. An administrator has to approve it before you can record attendance.',
      );
    } catch (cause) {
      if (cause instanceof ApiError && cause.isUnauthorized) {
        navigate('/login', { replace: true });
        return;
      }
      setError(describeError(cause, 'The device could not be registered.'));
    } finally {
      setPending(false);
    }
  };

  return (
    <div className="dashboard">
      <h1>Device</h1>

      <ErrorBanner message={error?.message ?? null} hint={error?.hint ?? null} />
      <SuccessBanner message={success} />

      <DeviceStatusCard device={device} />

      {status === 'APPROVED' ? (
        <section className="card">
          <h2>Approved device</h2>
          <p>This device is approved and can be used to record attendance.</p>
        </section>
      ) : null}

      {status === 'PENDING_APPROVAL' ? (
        <section className="card">
          <h2>Waiting for approval</h2>
          <p>
            The device has been registered and is waiting for an administrator to approve it. You will be able
            to record attendance once it is approved.
          </p>
        </section>
      ) : null}

      {canRegister ? (
        <section className="card" aria-labelledby="register-heading">
          <h2 id="register-heading">Register this device</h2>
          <p>
            Use the device you will use at the office — for example your phone with a passkey, a laptop with a
            security key or a fingerprint reader. The browser will ask you to confirm.
          </p>

          <form onSubmit={handleRegister} noValidate>
            <div className="form-field">
              <label htmlFor="device-label">Device name (optional)</label>
              <input
                id="device-label"
                name="device-label"
                type="text"
                maxLength={100}
                placeholder="e.g. Office phone"
                value={label}
                onChange={(event) => setLabel(event.target.value)}
                disabled={pending}
              />
            </div>
            <button type="submit" className="button button-primary" disabled={pending} aria-busy={pending}>
              {pending ? 'Registering…' : 'Register this device'}
            </button>
          </form>
        </section>
      ) : null}
    </div>
  );
}
