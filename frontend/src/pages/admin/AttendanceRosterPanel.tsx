import { useState, type FormEvent } from 'react';
import { downloadAttendanceRosterTemplate, importAttendanceRoster } from '../../api/admin';
import { describeError } from '../../utils/errors';
import { todayWorkday } from '../../utils/format';

export function AttendanceRosterPanel({ onImported, download }: {
  onImported: (count: number) => void;
  download: (blob: Blob, filename: string) => void;
}) {
  const [date,setDate] = useState(todayWorkday);
  const [file,setFile] = useState<File | null>(null);
  const [source,setSource] = useState('');
  const [verified,setVerified] = useState(false);
  const [busy,setBusy] = useState(false);
  const [error,setError] = useState('');

  async function template() {
    setBusy(true);
    setError('');
    try {
      download(await downloadAttendanceRosterTemplate(date),`attendance-roster-${date}.tsv`);
    } catch (cause) {
      setError(describeError(cause,'The template could not be downloaded.').message);
    } finally { setBusy(false); }
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!file || !verified || busy) return;
    setError('');
    if (file.size > 800_000) {
      setError('Use a roster file under 800 KB and import no more than 2,000 rows at once.');
      return;
    }
    const form = event.currentTarget;
    setBusy(true);
    try {
      const result = await importAttendanceRoster(await file.text(),source,verified);
      setFile(null);
      setSource('');
      setVerified(false);
      form.reset();
      onImported(result.imported);
    } catch (cause) {
      setError(describeError(cause,'The roster could not be imported.').message);
    } finally { setBusy(false); }
  }

  return <details className="card">
    <summary><strong>Include staff who did not clock in</strong></summary>
    <p>Download a roster template for all active staff. Fill in the approved schedule for each employee and date.
      Add former staff by employee number when reporting on an approved historical roster.</p>
    <div className="button-row">
      <label>Template date <input type="date" value={date} onChange={event => setDate(event.target.value)} /></label>
      <button type="button" className="button button-secondary" onClick={() => void template()} disabled={busy || !date}>
        Download roster template
      </button>
    </div>
    <p className="hint">Use YYYY-MM-DD for dates and YYYY-MM-DDTHH:MM for Lagos clock rules.
      Arrangement values are OFFICE, REMOTE, APPROVED_LEAVE, EXCUSED_ABSENCE, and NOT_SCHEDULED.
      Office rows need an approved absence cutoff and shift end. Save the completed file as UTF-8 tab-separated text (.tsv).</p>
    <form onSubmit={submit}>
      <div className="filter-grid">
        <label className="form-field">Completed roster
          <input type="file" accept=".tsv,.txt,text/tab-separated-values" required
            onChange={event => setFile(event.target.files?.[0] ?? null)} />
        </label>
        <label className="form-field">Approved roster reference
          <input value={source} onChange={event => setSource(event.target.value)} required maxLength={200} />
        </label>
      </div>
      <label><input type="checkbox" required checked={verified} onChange={event => setVerified(event.target.checked)} />
        {' '}I verified employment dates, schedules and exceptions against the approved roster.
      </label>
      {error && <p role="alert">{error}</p>}
      <div className="button-row">
        <button type="submit" className="button button-primary" disabled={busy || !file || !verified}>Import approved roster</button>
      </div>
    </form>
  </details>;
}
