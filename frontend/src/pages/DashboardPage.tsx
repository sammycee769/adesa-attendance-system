import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { fetchHistory, fetchToday, signIn as apiSignIn, signOut as apiSignOut } from '../api/attendance';
import { ApiError } from '../api/client';
import { authenticateWithDevice } from '../api/webauthn';
import type { AttendanceHistory, AttendanceStatus } from '../api/types';
import { useAuth } from '../auth/AuthProvider';
import { AttendancePanel } from '../components/AttendancePanel';
import { ErrorBanner, SuccessBanner } from '../components/Banners';
import { DeviceStatusCard } from '../components/DeviceStatusCard';
import { HistoryTable } from '../components/HistoryTable';
import { describeError } from '../utils/errors';
import { formatTime, formatToday } from '../utils/format';

/**
 * The employee dashboard.
 *
 * The backend decides which action is allowed (`canSignIn` / `canSignOut`);
 * the buttons only mirror that decision. Every action is preceded by a fresh
 * WebAuthn check of the approved device, and the state is reloaded from the
 * server after each action.
 */
export function DashboardPage() {
  const { user } = useAuth();
  const navigate = useNavigate();

  const [status, setStatus] = useState<AttendanceStatus | null>(null);
  const [history, setHistory] = useState<AttendanceHistory | null>(null);
  const [pending, setPending] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<{ message: string; hint: string | null } | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  const reload = useCallback(async () => {
    const [today, past] = await Promise.all([fetchToday(), fetchHistory(0, 20)]);
    setStatus(today);
    setHistory(past);
  }, []);

  useEffect(() => {
    let active = true;
    (async () => {
      try {
        const [today, past] = await Promise.all([fetchToday(), fetchHistory(0, 20)]);
        if (active) {
          setStatus(today);
          setHistory(past);
        }
      } catch (cause) {
        if (active) {
          setError(describeError(cause, 'The attendance information could not be loaded.'));
        }
      } finally {
        if (active) {
          setLoading(false);
        }
      }
    })();
    return () => {
      active = false;
    };
  }, []);

  const runAction = async (action: 'sign-in' | 'sign-out') => {
    if (pending) {
      return;
    }
    setPending(true);
    setError(null);
    setSuccess(null);
    try {
      const proof = await authenticateWithDevice();
      const record =
        action === 'sign-in'
          ? await apiSignIn(proof.deviceProofId)
          : await apiSignOut(proof.deviceProofId);
      setSuccess(
        action === 'sign-in'
          ? `Signed in at ${formatTime(record.signInAt)}.`
          : `Signed out at ${formatTime(record.signOutAt)}.`,
      );
      await reload();
    } catch (cause) {
      if (cause instanceof ApiError && cause.isUnauthorized) {
        navigate('/login', { replace: true });
        return;
      }
      setError(describeError(cause, 'The attendance action could not be completed.'));
    } finally {
      setPending(false);
    }
  };

  return (
    <div className="dashboard">
      <h1>Attendance</h1>

      <ErrorBanner message={error?.message ?? null} hint={error?.hint ?? null} />
      <SuccessBanner message={success} />

      <section className="card" aria-labelledby="identity-heading">
        <h2 id="identity-heading">Today at ADESA HQ</h2>
        <dl className="definition-list">
          <dt>Name</dt>
          <dd>{user?.fullName ?? '—'}</dd>
          <dt>Employee number</dt>
          <dd>{user?.employeeNumber ?? '—'}</dd>
          <dt>Date</dt>
          <dd>{formatToday()}</dd>
        </dl>
      </section>

      {loading ? (
        <p className="page-status" role="status" aria-live="polite">
          Loading…
        </p>
      ) : (
        <AttendancePanel
          status={status}
          pending={pending}
          onSignIn={() => void runAction('sign-in')}
          onSignOut={() => void runAction('sign-out')}
        />
      )}

      <DeviceStatusCard device={user?.device ?? null} />

      <HistoryTable history={history} />
    </div>
  );
}
