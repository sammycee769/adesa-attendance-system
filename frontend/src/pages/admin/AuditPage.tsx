import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { fetchAuditLogs } from '../../api/admin';
import { ApiError } from '../../api/client';
import type { AuditLogEntry, EmployeeRole } from '../../api/types';
import { useAuth } from '../../auth/AuthProvider';
import { ErrorBanner } from '../../components/Banners';
import { describeError } from '../../utils/errors';
import { formatDateTime } from '../../utils/format';

/**
 * The audit trail.
 *
 * An administrator sees every entry. HR sees the attendance and device entries
 * only — the backend applies that restriction, this screen only mirrors it.
 */

const ADMIN_ACTIONS = [
  'EMPLOYEE_CREATED',
  'EMPLOYEE_UPDATED',
  'EMPLOYEE_ACTIVATED',
  'EMPLOYEE_DEACTIVATED',
  'EMPLOYEE_ROLE_CHANGED',
  'EMPLOYEE_PASSWORD_RESET',
  'FIRST_ADMIN_BOOTSTRAPPED',
  'LOGIN_SUCCEEDED',
  'LOGIN_FAILED',
  'LOGIN_RATE_LIMITED',
  'LOGOUT',
  'SESSION_REJECTED',
  'DEVICE_REGISTERED',
  'DEVICE_AUTHENTICATED',
  'DEVICE_APPROVED',
  'DEVICE_REVOKED',
  'DEVICE_REPLACED',
  'ATTENDANCE_SIGNED_IN',
  'ATTENDANCE_SIGNED_OUT',
  'ATTENDANCE_ACTION_REJECTED',
  'ATTENDANCE_CORRECTED',
];

const HR_ACTIONS = [
  'DEVICE_REGISTERED',
  'DEVICE_AUTHENTICATED',
  'DEVICE_APPROVED',
  'DEVICE_REVOKED',
  'DEVICE_REPLACED',
  'ATTENDANCE_SIGNED_IN',
  'ATTENDANCE_SIGNED_OUT',
  'ATTENDANCE_ACTION_REJECTED',
  'ATTENDANCE_CORRECTED',
];

const PAGE_SIZE = 50;

export function AuditPage() {
  const navigate = useNavigate();
  const { user } = useAuth();
  const role: EmployeeRole = user?.role ?? 'EMPLOYEE';

  const [entries, setEntries] = useState<AuditLogEntry[]>([]);
  const [action, setAction] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<{ message: string; hint: string | null } | null>(null);

  const actions = role === 'ADMIN' ? ADMIN_ACTIONS : HR_ACTIONS;

  const handleError = useCallback(
    (cause: unknown, fallback: string) => {
      if (cause instanceof ApiError && cause.isUnauthorized) {
        navigate('/login', { replace: true });
        return;
      }
      setError(describeError(cause, fallback));
    },
    [navigate],
  );

  useEffect(() => {
    let active = true;
    setLoading(true);
    (async () => {
      try {
        const result = await fetchAuditLogs({
          action: action || undefined,
          from: from ? `${from}T00:00:00Z` : undefined,
          to: to ? `${to}T23:59:59Z` : undefined,
          page,
          size: PAGE_SIZE,
        });
        if (active) {
          setEntries(result.records);
          setTotalPages(result.totalPages);
          setTotalElements(result.totalElements);
          setError(null);
        }
      } catch (cause) {
        if (active) {
          handleError(cause, 'The audit trail could not be loaded.');
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
  }, [action, from, to, page, handleError]);

  return (
    <div className="dashboard">
      <h1>Audit trail</h1>
      <p className="subtitle">
        {role === 'ADMIN'
          ? 'Every recorded action: accounts, sign-ins, devices and attendance.'
          : 'The attendance and device actions. Account administration is not part of the HR view.'}
      </p>

      <ErrorBanner message={error?.message ?? null} hint={error?.hint ?? null} />

      <section className="card">
        <div className="filter-grid">
          <div className="form-field">
            <label htmlFor="audit-action">Action</label>
            <select
              id="audit-action"
              name="audit-action"
              value={action}
              onChange={(event) => {
                setAction(event.target.value);
                setPage(0);
              }}
            >
              <option value="">All actions</option>
              {actions.map((value) => (
                <option key={value} value={value}>
                  {value}
                </option>
              ))}
            </select>
          </div>
          <div className="form-field">
            <label htmlFor="audit-from">From</label>
            <input
              id="audit-from"
              name="audit-from"
              type="date"
              value={from}
              onChange={(event) => {
                setFrom(event.target.value);
                setPage(0);
              }}
            />
          </div>
          <div className="form-field">
            <label htmlFor="audit-to">To</label>
            <input
              id="audit-to"
              name="audit-to"
              type="date"
              value={to}
              onChange={(event) => {
                setTo(event.target.value);
                setPage(0);
              }}
            />
          </div>
        </div>
      </section>

      <section className="card" aria-labelledby="audit-heading">
        <h2 id="audit-heading">Entries</h2>
        {loading ? (
          <p className="page-status" role="status" aria-live="polite">
            Loading the audit trail…
          </p>
        ) : entries.length === 0 ? (
          <p className="hint">No entry matches these filters.</p>
        ) : (
          <div className="table-scroll">
            <table>
              <caption className="visually-hidden">Audit entries</caption>
              <thead>
                <tr>
                  <th scope="col">When</th>
                  <th scope="col">Who</th>
                  <th scope="col">Action</th>
                  <th scope="col">Target</th>
                  <th scope="col">Details</th>
                </tr>
              </thead>
              <tbody>
                {entries.map((entry) => (
                  <tr key={entry.id}>
                    <td>{formatDateTime(entry.createdAt)}</td>
                    <td>
                      {entry.actorName ?? 'System'}
                      {entry.actorEmployeeNumber ? (
                        <span className="cell-note"> {entry.actorEmployeeNumber}</span>
                      ) : null}
                    </td>
                    <td>{entry.action}</td>
                    <td>{entry.targetType ?? '—'}</td>
                    <td className="cell-details">{describeDetails(entry.details)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {totalPages > 1 ? (
          <div className="button-row pagination">
            <button
              type="button"
              className="button button-secondary"
              disabled={page === 0}
              onClick={() => setPage((current) => Math.max(0, current - 1))}
            >
              Previous
            </button>
            <span className="hint">
              Page {page + 1} of {totalPages} — {totalElements} entries
            </span>
            <button
              type="button"
              className="button button-secondary"
              disabled={page + 1 >= totalPages}
              onClick={() => setPage((current) => current + 1)}
            >
              Next
            </button>
          </div>
        ) : null}
      </section>
    </div>
  );
}

/** The audit details as a short, readable text. */
function describeDetails(details: Record<string, unknown> | null): string {
  if (!details) {
    return '—';
  }
  const parts = Object.entries(details)
    .filter(([, value]) => value !== null && value !== '')
    .map(([key, value]) => `${key}: ${String(value)}`);
  return parts.length === 0 ? '—' : parts.join(', ');
}
