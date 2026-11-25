import { useEffect, useRef, useState, type FormEvent, type MouseEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { correctAttendance, exportAttendanceCsv, exportAttendancePdf, fetchDailyAttendance } from '../../api/admin';
import { ApiError } from '../../api/client';
import type { DailyAttendanceReport, DailyAttendanceRow, DailyAttendanceStatus } from '../../api/types';
import { ErrorBanner, SuccessBanner } from '../../components/Banners';
import { describeError } from '../../utils/errors';
import { ATTENDANCE_TIMEZONE, attendanceLocalToInstant, instantToAttendanceLocal } from '../../utils/format';
import { useRevealEditor } from '../../utils/useRevealEditor';
import { AttendanceDayCard, attendanceLabels } from './AttendanceDayCard';

const PAGE_SIZE = 7;
const EMPTY_FILTERS = { from: '', to: '', date: '', search: '', subsidiary: '', department: '', status: '' as '' | DailyAttendanceStatus };
const filterLabels = { from: 'Start date', to: 'End date', date: 'Specific working day',
  search: 'Employee name', subsidiary: 'Subsidiary', department: 'Department' } as const;

export function AttendancePage() {
  const navigate = useNavigate();
  const [draft, setDraft] = useState(EMPTY_FILTERS);
  const [filters, setFilters] = useState(EMPTY_FILTERS);
  const [page, setPage] = useState(0);
  const [report, setReport] = useState<DailyAttendanceReport | null>(null);
  const [revision, setRevision] = useState(0);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<{ message: string; hint: string | null } | null>(null);
  const [success, setSuccess] = useState<string | null>(null);
  const [editing, setEditing] = useState<DailyAttendanceRow | null>(null);
  const correctionEditor = useRevealEditor<HTMLElement>(editing?.recordId);
  const correctionTrigger = useRef<HTMLButtonElement | null>(null);
  const [signIn, setSignIn] = useState('');
  const [signOut, setSignOut] = useState('');
  const [reason, setReason] = useState('');

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    fetchDailyAttendance({ ...filters, status: filters.status || undefined, page, size: PAGE_SIZE },controller.signal)
      .then(result => {
        if (!controller.signal.aborted) {
          setReport(result);
          if (page > 0 && result.days.length === 0) setPage(page - 1);
        }
      })
      .catch(cause => {
        if (controller.signal.aborted) return;
        setReport(null);
        if (cause instanceof ApiError && cause.isUnauthorized) navigate('/login', { replace: true });
        else setError(describeError(cause,'Attendance records could not be loaded.'));
      })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [filters, page, revision, navigate]);

  function applyFilters(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setEditing(null);
    setSuccess(null);
    setPage(0);
    setFilters({ ...draft });
  }

  async function exportReport(format: 'pdf' | 'csv') {
    if (busy || !report) return;
    setBusy(true);
    setError(null);
    setSuccess(null);
    try {
      const query = { ...filters, from: report.from, to: report.to, status: filters.status || undefined };
      const blob = await (format === 'pdf' ? exportAttendancePdf(query) : exportAttendanceCsv(query));
      downloadBlob(blob, `adesa-attendance-${report.from}-to-${report.to}.${format}`);
      setSuccess('The report was downloaded.');
    } catch (cause) {
      if (cause instanceof ApiError && cause.isUnauthorized) navigate('/login', { replace: true });
      else setError(describeError(cause,'The report could not be exported.'));
    } finally {
      setBusy(false);
    }
  }

  function openCorrection(row: DailyAttendanceRow, event: MouseEvent<HTMLButtonElement>) {
    correctionTrigger.current = event.currentTarget;
    setEditing(row);
    setSignIn(instantToAttendanceLocal(row.signInAt));
    setSignOut(instantToAttendanceLocal(row.signOutAt));
    setReason('');
    setError(null);
    setSuccess(null);
  }

  function closeCorrection() {
    setEditing(null);
    correctionTrigger.current?.focus();
  }

  async function handleCorrection(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!editing?.recordId || busy) return;
    const signInAt = attendanceLocalToInstant(signIn);
    const signOutAt = signOut.trim() ? attendanceLocalToInstant(signOut) : null;
    if (!signInAt || (signOut.trim() && !signOutAt) || reason.trim().length < 10) {
      const message = !signInAt ? 'Please give the sign-in time.' : signOut.trim() && !signOutAt
        ? 'Please give the sign-out time.' : 'Please give a reason (at least 10 characters).';
      setError({ message, hint: null });
      return;
    }
    setBusy(true);
    setError(null);
    setSuccess(null);
    try {
      await correctAttendance(editing.recordId,{ signInAt, signOutAt, reason: reason.trim() });
      setSuccess(`The attendance record of ${editing.employeeName} was corrected.`);
      closeCorrection();
      setRevision(previous => previous + 1);
    } catch (cause) {
      if (cause instanceof ApiError && cause.isUnauthorized) navigate('/login', { replace: true });
      else setError(describeError(cause,'The correction could not be saved.'));
    } finally {
      setBusy(false);
    }
  }

  const pageCount = Math.ceil((report?.totalDays ?? 0) / PAGE_SIZE);
  const metrics = report ? [
    ['Expected staff', report.summary.expected], ['Present employees', report.summary.present],
    ['Absent employees', report.summary.absent], ['Signed-out employees', report.summary.signedOut],
  ] as const : [];
  const dayScope = JSON.stringify([filters,page]);

  return (
    <div className="dashboard attendance-records">
      <header>
        <h1>Attendance Records</h1>
        <p className="attendance-subtitle">Review employee attendance by working day and download filtered reports.</p>
      </header>
      <ErrorBanner message={error?.message ?? null} hint={error?.hint ?? null} />
      <SuccessBanner message={success} />
      <section className="card" aria-labelledby="attendance-filters">
        <h2 id="attendance-filters">Attendance filters</h2>
        <form onSubmit={applyFilters}>
          <div className="filter-grid">
            {(Object.keys(filterLabels) as (keyof typeof filterLabels)[]).map(key =>
              <label className="form-field" key={key}>{filterLabels[key]}
                <input type={key === 'search' ? 'search' : ['from','to','date'].includes(key) ? 'date' : 'text'}
                  value={draft[key]} onChange={event => setDraft({ ...draft, [key]: event.target.value })} />
              </label>
            )}
            <label className="form-field">Attendance status
              <select value={draft.status} onChange={event => setDraft({ ...draft, status: event.target.value as '' | DailyAttendanceStatus })}>
                <option value="">All statuses</option>
                {(Object.entries(attendanceLabels)).map(([value,label]) => <option key={value} value={value}>{label}</option>)}
              </select>
            </label>
          </div>
          <div className="button-row">
            <button type="submit" className="button button-primary" disabled={busy}>Apply filters</button>
            <button type="button" className="button button-secondary" disabled={busy}
              onClick={() => { setDraft(EMPTY_FILTERS); setFilters(EMPTY_FILTERS); setPage(0); setEditing(null); }}>Clear filters</button>
            <button type="button" className="button button-secondary" disabled={busy || loading || !report}
              onClick={() => void exportReport('pdf')}>Download PDF</button>
            <button type="button" className="button button-secondary" disabled={busy || loading || !report}
              onClick={() => void exportReport('csv')}>Download CSV</button>
          </div>
        </form>
      </section>
      {editing ? (
        <section ref={correctionEditor} className="card reveal-editor" aria-labelledby="correction-heading">
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
      {loading ? <p role="status">Loading attendance records…</p> : report && <>
        <section aria-label="Attendance totals for the displayed days">
          <dl className="attendance-metrics" aria-label="Totals for displayed working days">
            {metrics.map(([label,value]) => <div className="card" key={label}><dt>{label}</dt><dd>{value}</dd></div>)}
          </dl>
          <p className="hint attendance-coverage">Totals cover the days shown. Expected staff counts use verified schedules.</p>
        </section>
        {report.days.length === 0 && <p className="card">No working days match the selected filters.</p>}
        {report.days.map((day,index) => <AttendanceDayCard key={`${dayScope}:${day.date}`} day={day}
          initiallyOpen={index === 0} onCorrect={openCorrection} />)}
        {pageCount > 1 && <nav className="button-row pagination" aria-label="Attendance working days">
          <button className="button button-secondary" type="button" disabled={page === 0 || busy}
            onClick={() => { setEditing(null); setPage(page - 1); }}>Newer days</button>
          <span>Page {page + 1} of {pageCount}</span>
          <button className="button button-secondary" type="button" disabled={page + 1 >= pageCount || busy}
            onClick={() => { setEditing(null); setPage(page + 1); }}>Older days</button>
        </nav>}
      </>}
    </div>
  );
}

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
