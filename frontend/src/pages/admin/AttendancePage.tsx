import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';

import {
  correctAttendance,
  exportAttendancePdf,
  fetchAttendance,
  fetchAttendanceOverview,
} from '../../api/admin';
import { ApiError } from '../../api/client';
import type { AdminAttendanceRecord, AttendanceOverviewEntry } from '../../api/types';
import { ErrorBanner, SuccessBanner } from '../../components/Banners';
import { AttendanceStateBadge } from '../../components/StatusBadge';
import { describeError } from '../../utils/errors';
import {
  ATTENDANCE_TIMEZONE,
  attendanceLocalToInstant,
  formatTime,
  formatWorkday,
  instantToAttendanceLocal,
  todayWorkday,
} from '../../utils/format';

/**
 * The attendance report.
 *
 * Two views on the same data:
 *
 * - the daily overview: every active employee and their state for one workday,
 * - the record list: filterable, exportable as PDF, and correctable.
 *
 * A correction always needs a reason. It is written to the correction history
 * and to the audit log by the backend; the frontend only sends it.
 */

const PAGE_SIZE = 50;

export function AttendancePage() {
  const navigate = useNavigate();

  const [view, setView] = useState<'overview' | 'records'>('overview');
  const [date, setDate] = useState(todayWorkday());

  const [searchInput, setSearchInput] = useState('');
  const [search, setSearch] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [status, setStatus] = useState<'' | 'SIGNED_IN' | 'SIGNED_OUT'>('');
  const [page, setPage] = useState(0);

  const [overview, setOverview] = useState<AttendanceOverviewEntry[]>([]);
  const [records, setRecords] = useState<AdminAttendanceRecord[]>([]);
  const [totalElements, setTotalElements] = useState(0);
  const [totalPages, setTotalPages] = useState(0);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<{ message: string; hint: string | null } | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  const [editing, setEditing] = useState<AdminAttendanceRecord | null>(null);
  const [signIn, setSignIn] = useState('');
  const [signOut, setSignOut] = useState('');
  const [reason, setReason] = useState('');
  /** Returns keyboard focus to the row button when the correction form closes. */
  const correctionTrigger = useRef<HTMLButtonElement | null>(null);

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

  const loadOverview = useCallback(async (workday: string) => {
    const result = await fetchAttendanceOverview(workday);
    setOverview(result);
  }, []);

  const loadRecords = useCallback(
    async (currentPage: number, filters: { search: string; from: string; to: string; status: string }) => {
      const result = await fetchAttendance({
        search: filters.search || undefined,
        from: filters.from || undefined,
        to: filters.to || undefined,
        status: filters.status === '' ? undefined : (filters.status as 'SIGNED_IN' | 'SIGNED_OUT'),
        page: currentPage,
        size: PAGE_SIZE,
      });
      setRecords(result.records);
      setTotalElements(result.totalElements);
      setTotalPages(result.totalPages);
    },
    [],
  );

  // Debounce the free-text filter so typing does not issue one request per
  // keystroke against the report endpoint.
  useEffect(() => {
    const timer = setTimeout(() => setSearch(searchInput), 300);
    return () => clearTimeout(timer);
  }, [searchInput]);

  useEffect(() => {
    let active = true;
    setLoading(true);
    (async () => {
      try {
        if (view === 'overview') {
          await loadOverview(date);
        } else {
          await loadRecords(page, { search, from, to, status });
        }
        if (active) {
          setError(null);
        }
      } catch (cause) {
        if (active) {
          handleError(cause, 'The attendance information could not be loaded.');
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
    // The filters are read on every change; they are part of the dependency list.
  }, [view, date, page, search, from, to, status, loadOverview, loadRecords, handleError]);

  const handleExport = async () => {
    if (busy) {
      return;
    }
    setBusy(true);
    setError(null);
    setSuccess(null);
    try {
      const blob = await exportAttendancePdf({
        search: search || undefined,
        from: from || undefined,
        to: to || undefined,
        status: status === '' ? undefined : (status as 'SIGNED_IN' | 'SIGNED_OUT'),
      });
      downloadBlob(blob, `adesa-attendance-${from || 'all'}-to-${to || 'all'}.pdf`);
      setSuccess('The report was downloaded.');
    } catch (cause) {
      handleError(cause, 'The report could not be exported.');
    } finally {
      setBusy(false);
    }
  };

  const openCorrection = (record: AdminAttendanceRecord) => {
    setEditing(record);
    setSignIn(instantToAttendanceLocal(record.signInAt));
    setSignOut(instantToAttendanceLocal(record.signOutAt));
    setReason('');
    setError(null);
    setSuccess(null);
  };

  /** Closes the correction form and returns focus to the button that opened it. */
  const closeCorrection = () => {
    setEditing(null);
    correctionTrigger.current?.focus();
  };

  const handleCorrection = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!editing || busy) {
      return;
    }
    const signInAt = attendanceLocalToInstant(signIn);
    const signOutAt = signOut.trim() ? attendanceLocalToInstant(signOut) : null;
    if (!signInAt) {
      setError({ message: 'Please give the sign-in time.', hint: null });
      return;
    }
    if (signOut.trim() && !signOutAt) {
      setError({ message: 'Please give the sign-out time.', hint: null });
      return;
    }
    if (reason.trim().length < 10) {
      setError({ message: 'Please give a reason (at least 10 characters).', hint: null });
      return;
    }
    setBusy(true);
    setError(null);
    setSuccess(null);
    try {
      await correctAttendance(editing.id, { signInAt, signOutAt, reason: reason.trim() });
      setSuccess(`The attendance record of ${editing.employeeName} was corrected.`);
      setEditing(null);
      await loadRecords(page, { search, from, to, status });
    } catch (cause) {
      handleError(cause, 'The correction could not be saved.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="dashboard">
      <h1>Attendance</h1>

      <ErrorBanner message={error?.message ?? null} hint={error?.hint ?? null} />
      <SuccessBanner message={success} />

      <section className="card">
        <div className="filter-row" role="group" aria-label="Report view">
          <button
            type="button"
            className={`button ${view === 'overview' ? 'button-primary' : 'button-secondary'}`}
            aria-pressed={view === 'overview'}
            onClick={() => setView('overview')}
          >
            Daily overview
          </button>
          <button
            type="button"
            className={`button ${view === 'records' ? 'button-primary' : 'button-secondary'}`}
            aria-pressed={view === 'records'}
            onClick={() => setView('records')}
          >
            Records
          </button>
        </div>

        {view === 'overview' ? (
          <div className="form-field">
            <label htmlFor="overview-date">Workday</label>
            <input
              id="overview-date"
              name="overview-date"
              type="date"
              value={date}
              onChange={(event) => setDate(event.target.value)}
              disabled={loading}
            />
          </div>
        ) : (
          <div className="filter-grid">
            <div className="form-field">
              <label htmlFor="filter-search">Employee</label>
              <input
                id="filter-search"
                name="filter-search"
                type="search"
                placeholder="Name, number or email"
                autoComplete="off"
                autoCapitalize="none"
                autoCorrect="off"
                spellCheck={false}
                value={searchInput}
                onChange={(event) => {
                  setSearchInput(event.target.value);
                  setPage(0);
                }}
              />
            </div>
            <div className="form-field">
              <label htmlFor="filter-from">From</label>
              <input
                id="filter-from"
                name="filter-from"
                type="date"
                value={from}
                onChange={(event) => {
                  setFrom(event.target.value);
                  setPage(0);
                }}
              />
            </div>
            <div className="form-field">
              <label htmlFor="filter-to">To</label>
              <input
                id="filter-to"
                name="filter-to"
                type="date"
                value={to}
                onChange={(event) => {
                  setTo(event.target.value);
                  setPage(0);
                }}
              />
            </div>
            <div className="form-field">
              <label htmlFor="filter-status">State</label>
              <select
                id="filter-status"
                name="filter-status"
                value={status}
                onChange={(event) => {
                  setStatus(event.target.value as '' | 'SIGNED_IN' | 'SIGNED_OUT');
                  setPage(0);
                }}
              >
                <option value="">All</option>
                <option value="SIGNED_IN">Signed in</option>
                <option value="SIGNED_OUT">Signed out</option>
              </select>
            </div>
          </div>
        )}
      </section>

      {view === 'overview' ? (
        <section className="card" aria-labelledby="overview-heading">
          <h2 id="overview-heading">Daily overview — {formatWorkday(date)}</h2>
          <p className="hint">
            &ldquo;Not signed in&rdquo; means that no record exists yet. It does not mean that the employee was
            absent.
          </p>
          {loading ? (
            <p className="page-status" role="status" aria-live="polite">
              Loading the overview…
            </p>
          ) : (
            <div className="table-scroll">
              <table>
                <caption className="visually-hidden">Attendance state of every active employee</caption>
                <thead>
                  <tr>
                    <th scope="col">Employee</th>
                    <th scope="col">State</th>
                    <th scope="col">Sign in</th>
                    <th scope="col">Sign out</th>
                  </tr>
                </thead>
                <tbody>
                  {overview.map((entry) => (
                    <tr key={entry.employeeId}>
                      <td>
                        {entry.employeeName}
                        <span className="cell-note"> {entry.employeeNumber}</span>
                      </td>
                      <td>
                        <AttendanceStateBadge state={entry.state} />
                        {entry.corrected ? <span className="cell-note"> corrected</span> : null}
                      </td>
                      <td>{formatTime(entry.signInAt)}</td>
                      <td>{formatTime(entry.signOutAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </section>
      ) : (
        <section className="card" aria-labelledby="records-heading">
          <div className="section-header">
            <h2 id="records-heading">Records</h2>
            <button
              type="button"
              className="button button-secondary"
              onClick={handleExport}
              disabled={busy}
            >
              {busy ? 'Exporting…' : 'Export as PDF'}
            </button>
          </div>

          {loading ? (
            <p className="page-status" role="status" aria-live="polite">
              Loading the records…
            </p>
          ) : records.length === 0 ? (
            <p className="hint">No record matches these filters.</p>
          ) : (
            <div className="table-scroll">
              <table>
                <caption className="visually-hidden">Attendance records</caption>
                <thead>
                  <tr>
                    <th scope="col">Employee</th>
                    <th scope="col">Workday</th>
                    <th scope="col">State</th>
                    <th scope="col">Sign in</th>
                    <th scope="col">Sign out</th>
                    <th scope="col">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {records.map((record) => (
                    <tr key={record.id}>
                      <td>
                        {record.employeeName}
                        <span className="cell-note"> {record.employeeNumber}</span>
                      </td>
                      <td>{formatWorkday(record.attendanceDate)}</td>
                      <td>
                        <AttendanceStateBadge state={record.state} />
                        {record.corrected ? (
                          <span className="cell-note"> corrected {record.correctionCount}×</span>
                        ) : null}
                      </td>
                      <td>{formatTime(record.signInAt)}</td>
                      <td>{formatTime(record.signOutAt)}</td>
                      <td>
                        <button
                          type="button"
                          className="button button-secondary"
                          onClick={(event) => {
                            correctionTrigger.current = event.currentTarget;
                            openCorrection(record);
                          }}
                          disabled={busy}
                        >
                          Correct
                        </button>
                      </td>
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
                Page {page + 1} of {totalPages} — {totalElements} records
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
      )}

      {editing ? (
        <section className="card" aria-labelledby="correction-heading">
          <h2 id="correction-heading">Correct the record of {editing.employeeName}</h2>
          <p className="hint">
            The times are in the attendance timezone ({ATTENDANCE_TIMEZONE}). The previous values are kept in
            the correction history and in the audit log.
          </p>

          <form onSubmit={handleCorrection} noValidate>
            <div className="filter-grid">
              <div className="form-field">
                <label htmlFor="correction-sign-in">Sign in</label>
                <input
                  id="correction-sign-in"
                  name="correction-sign-in"
                  type="datetime-local"
                  autoFocus
                  required
                  value={signIn}
                  onChange={(event) => setSignIn(event.target.value)}
                  disabled={busy}
                />
              </div>
              <div className="form-field">
                <label htmlFor="correction-sign-out">Sign out (optional)</label>
                <input
                  id="correction-sign-out"
                  name="correction-sign-out"
                  type="datetime-local"
                  value={signOut}
                  onChange={(event) => setSignOut(event.target.value)}
                  disabled={busy}
                />
              </div>
            </div>
            <div className="form-field">
              <label htmlFor="correction-reason">Reason</label>
              <input
                id="correction-reason"
                name="correction-reason"
                type="text"
                maxLength={500}
                required
                value={reason}
                onChange={(event) => setReason(event.target.value)}
                disabled={busy}
              />
              <p className="hint">A reason is mandatory and is stored in the audit trail.</p>
            </div>
            <div className="button-row">
              <button type="submit" className="button button-primary" disabled={busy}>
                {busy ? 'Saving…' : 'Save the correction'}
              </button>
              <button
                type="button"
                className="button button-secondary"
                disabled={busy}
                onClick={closeCorrection}
              >
                Cancel
              </button>
            </div>
          </form>
        </section>
      ) : null}
    </div>
  );
}

/** Saves a file in the browser. */
function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}
