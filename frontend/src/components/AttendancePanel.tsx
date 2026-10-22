import type { AttendanceStatus } from '../api/types';
import { formatTime } from '../utils/format';
import { AttendanceStateBadge } from './StatusBadge';

interface AttendancePanelProps {
  status: AttendanceStatus | null;
  /** True while a sign-in or sign-out request is running. */
  pending: boolean;
  onSignIn: () => void;
  onSignOut: () => void;
}

/**
 * The sign-in / sign-out card.
 *
 * The buttons follow the backend's decision (`canSignIn` / `canSignOut`) and
 * are disabled while a request is running. The backend remains authoritative:
 * a button that looks enabled can still be refused by the server.
 */
export function AttendancePanel({ status, pending, onSignIn, onSignOut }: AttendancePanelProps) {
  const canSignIn = Boolean(status?.canSignIn) && !pending;
  const canSignOut = Boolean(status?.canSignOut) && !pending;

  return (
    <section className="card" aria-labelledby="attendance-heading">
      <h2 id="attendance-heading">Today</h2>

      <div className="attendance-summary">
        <p className="attendance-state">
          <span className="label">Status:</span>{' '}
          {status ? <AttendanceStateBadge state={status.state} /> : <span>—</span>}
        </p>
        <p className="attendance-timestamp">
          <span className="label">Signed in:</span> {formatTime(status?.signInAt ?? null)}
        </p>
        <p className="attendance-timestamp">
          <span className="label">Signed out:</span> {formatTime(status?.signOutAt ?? null)}
        </p>
      </div>

      <div className="button-row">
        <button
          type="button"
          className="button button-primary"
          onClick={onSignIn}
          disabled={!canSignIn}
          aria-busy={pending}
        >
          Sign In
        </button>
        <button
          type="button"
          className="button button-secondary"
          onClick={onSignOut}
          disabled={!canSignOut}
          aria-busy={pending}
        >
          Sign Out
        </button>
      </div>

      {pending ? (
        <p className="hint" role="status" aria-live="polite">
          Please confirm the device prompt to continue.
        </p>
      ) : null}

      {status && !status.canSignIn && !status.canSignOut ? (
        <p className="hint">No attendance action is available for today.</p>
      ) : null}
    </section>
  );
}
