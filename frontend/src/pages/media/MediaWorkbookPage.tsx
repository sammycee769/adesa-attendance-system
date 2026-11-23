import { useRevealEditor } from '../../utils/useRevealEditor';
import { useEffect, useRef, useState, type FormEvent } from 'react';

import { apiDownload, apiRequest } from '../../api/client';
import { lagosToday, text, type Register } from './mediaRegister';
import { GuidedImportBoard } from './GuidedImportBoard';

type Finding = { sheet: string; row: number; column: string; code: string; message: string };
type SourceCell = { address: string; display: string };
type SourceSheet = {
  name: string;
  headerRow: number | null;
  headers: Record<string, string>;
  rows: { number: number; cells: SourceCell[] }[];
};
export type Draft = {
  row: number;
  values: Record<string, string | null>;
  dates: Record<string, string | undefined>;
  rates: Record<string, number | undefined>;
  operationalNotes: { sheet: string; row: number; status: string; responsibility: string; notes: string }[];
};
export type Preview = { sheets: SourceSheet[]; findings: Finding[]; drafts: Draft[] };
export type Batch = { id: string; filename: string; created_at: string };
export type Review = {
  row: number;
  revision: number;
  location: string;
  client: string | null;
  vendor: string | null;
  vendorExpiry: string | null;
  clientExpiry: string | null;
  vendorApproved: boolean;
  clientApproved: boolean;
  notes: string | null;
};
type StageResult = { id: string; inserted: number; updated: number; skipped: number; flagged: number };

const root = '/api/v1/media';
const amount = new Intl.NumberFormat('en-NG', { style: 'currency', currency: 'NGN' });
const dateColumns = [
  'initial Start Date', 'Renewed Date', 'Expiration Date after renewal with vendors',
  'Expiration Date with Vendor', 'Expiration Date With Client',
];

const jobStatuses = ['Running', 'Work in progress', 'Completed', 'Unconfirmed'] as const;
const firstVendorExpiry = 'Expiration Date after renewal with vendors';
const secondVendorExpiry = 'Expiration Date with Vendor';
const clientExpiry = 'Expiration Date With Client';
const jobStatusLabels: Record<string, typeof jobStatuses[number]> = {
  RUNNING: 'Running', 'WORK IN PROGRESS': 'Work in progress', WIP: 'Work in progress', COMPLETED: 'Completed',
};

export function importedJobStatus(draft: Draft): typeof jobStatuses[number] {
  const labels = new Set(draft.operationalNotes.flatMap(note => {
    const label = jobStatusLabels[note.status.trim().toUpperCase().replace(/_+/g, ' ')];
    return label ? [label] : [];
  }));
  return labels.size === 1 ? [...labels][0] ?? 'Unconfirmed' : 'Unconfirmed';
}

function reminderDates(expiry: string | undefined, today: string) {
  if (!expiry) return 'Expiry needs confirmation';
  const dates = [30, 7].map(days => {
    const date = new Date(`${expiry}T12:00:00Z`);
    date.setUTCDate(date.getUTCDate() - days);
    return { days, date: date.toISOString().slice(0, 10) };
  }).filter(stage => stage.date >= today);
  return dates.length === 0 ? 'No upcoming dates' : dates.map(stage => `${stage.date} (${stage.days} days before)`).join(' · ');
}

function vendorReminder(draft: Draft, findings: Finding[], headers: Record<string, string>, today: string) {
  const a = draft.dates[firstVendorExpiry];
  const b = draft.dates[secondVendorExpiry];
  if (a && b && a !== b) return 'Confirm vendor expiry dates';
  const unresolved = [firstVendorExpiry, secondVendorExpiry].some(field => draft.values[field] && !draft.dates[field]);
  if (unresolved || findings.some(f => f.code === 'CONTRADICTORY_DATE' && [firstVendorExpiry, secondVendorExpiry].includes(headers[f.column] ?? ''))) {
    return 'Expiry needs confirmation';
  }
  return reminderDates(a || b, today);
}

function BoardOverview({ preview, reviews }: { preview?: Preview; reviews: Review[] }) {
  const drafts = preview?.drafts ?? [];
  const today = lagosToday();
  const headers = preview?.sheets.find(sheet => sheet.name === 'OOH Sheet')?.headers ?? {};
  const statusCounts = drafts.map(importedJobStatus);
  return (
    <section className="card" aria-labelledby="media-overview">
      <h2 id="media-overview">Board status</h2>
      <dl className="media-metrics">
        <div><dt>Workbook boards</dt><dd>{drafts.length}</dd></div>
        {jobStatuses.map(status => <div key={status}>
          <dt>{status}</dt><dd>{statusCounts.filter(value => value === status).length}</dd>
        </div>)}
      </dl>
      {preview && statusCounts.includes('Unconfirmed') &&
        <p>Unconfirmed boards have no single matched job status in the workbook.</p>}

      <h3>Reminder preview</h3>
      <p>Dates below are for planning. Open Reminders for delivery history. Workbook dates can be approved here, then linked to the register and confirmed as contract periods.</p>
      {drafts.length > 0 && <details open>
        <summary>View proposed contract reminder dates</summary>
        <div className="media-table">
          <table>
            <thead><tr><th scope="col">Board</th><th scope="col">Vendor contract</th><th scope="col">Client contract</th></tr></thead>
            <tbody>{drafts.map(draft => {
              const review = reviews.find(item => item.row === draft.row);
              const findings = preview?.findings.filter(f => f.sheet === 'OOH Sheet' && f.row === draft.row) ?? [];
              const clientNeedsReview = findings.some(f => f.code === 'CONTRADICTORY_DATE' && headers[f.column] === clientExpiry);
              return <tr key={draft.row}>
                <th scope="row">{review?.location || draft.values['OOH Location'] || `Workbook row ${draft.row}`}</th>
                <td>{review?.vendorApproved
                  ? reminderDates(review.vendorExpiry ?? undefined, today) : vendorReminder(draft, findings, headers, today)}</td>
                <td>{review?.clientApproved ? reminderDates(review.clientExpiry ?? undefined, today)
                  : clientNeedsReview ? 'Expiry needs confirmation' : reminderDates(draft.dates[clientExpiry], today)}</td>
              </tr>;
            })}</tbody>
          </table>
        </div>
      </details>}
      <p>Payment reminders are planned for 7 days before and on a verified due date. This workbook preview has no verified payment due dates.</p>
    </section>
  );
}

function errorMessage(cause: unknown) {
  return cause instanceof Error ? cause.message : 'The request failed. Try again.';
}

function ReviewEditor({ draft, review, busy, onSave, onClose }: {
  draft: Draft; review?: Review; busy: boolean; onSave: (review: Omit<Review, 'row'>) => void; onClose: () => void;
}) {
  const editor = useRevealEditor<HTMLFormElement>();
  const first = draft.dates[firstVendorExpiry];
  const second = draft.dates[secondVendorExpiry];
  const [vendorExpiry, setVendorExpiry] = useState(review?.vendorExpiry ?? (first && second && first !== second ? '' : first || second || ''));
  const [clientDate, setClientDate] = useState(review?.clientExpiry ?? draft.dates[clientExpiry] ?? '');
  const [vendorApproved, approveVendor] = useState(review?.vendorApproved ?? false);
  const [clientApproved, approveClient] = useState(review?.clientApproved ?? false);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const value = (field: string) => String(form.get(field) ?? '').trim();
    onSave({
      revision: review?.revision ?? 0, location: value('location'), client: value('client') || null,
      vendor: value('vendor') || null, vendorExpiry: vendorExpiry || null, clientExpiry: clientDate || null,
      vendorApproved, clientApproved, notes: value('notes') || null,
    });
  }

  return <form ref={editor} className="card media-editor reveal-editor" aria-label="Review board" onSubmit={submit}>
    <h2>Review board · workbook row {draft.row}</h2>
    <p>Original vendor expiry after renewal: {first || draft.values[firstVendorExpiry] || '—'}.
      Other vendor expiry: {second || draft.values[secondVendorExpiry] || '—'}.
      Client expiry: {draft.dates[clientExpiry] || draft.values[clientExpiry] || '—'}.</p>
    <fieldset disabled={busy}>
      <div className="media-form-grid">
        <label>Board location<input name="location" required maxLength={200} defaultValue={review?.location ?? draft.values['OOH Location'] ?? ''} /></label>
        <label>Client name<input name="client" maxLength={200} defaultValue={review?.client ?? draft.values.Client ?? ''} /></label>
        <label>Vendor name<input name="vendor" maxLength={200} defaultValue={review?.vendor ?? draft.values['Vendor Name'] ?? ''} /></label>
        <label>Reviewed vendor expiry<input type="date" value={vendorExpiry} required={vendorApproved}
          onChange={event => { setVendorExpiry(event.target.value); approveVendor(false); }} /></label>
        <label>Reviewed client expiry<input type="date" value={clientDate} required={clientApproved}
          onChange={event => { setClientDate(event.target.value); approveClient(false); }} /></label>
        <label>Review notes<textarea name="notes" maxLength={1000} rows={2} defaultValue={review?.notes ?? ''} /></label>
      </div>
      <label><input type="checkbox" checked={vendorApproved} disabled={!vendorExpiry} onChange={event => approveVendor(event.target.checked)} /> Approve vendor expiry</label>
      <label><input type="checkbox" checked={clientApproved} disabled={!clientDate} onChange={event => approveClient(event.target.checked)} /> Approve client expiry</label>
      <div className="media-actions">
        <button className="button" type="submit">Save board review</button>
        <button className="button button-secondary" type="button" onClick={onClose}>Cancel</button>
      </div>
    </fieldset>
  </form>;
}

function LinkBoardForm({ draft, review, register, busy, onLink, onClose }: {
  draft: Draft; review: Review; register: Register; busy: boolean;
  onLink: (fields: Record<string, string | null>) => void; onClose: () => void;
}) {
  const editor = useRevealEditor<HTMLFormElement>();
  const [mode, setMode] = useState('existing');
  return <form ref={editor} className="card media-editor reveal-editor" aria-label="Link workbook board" onSubmit={event => {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    onLink(Object.fromEntries([...form].map(([key, value]) => [key, String(value).trim() || null])));
  }}>
    <h2>Link {review.location} · workbook row {draft.row}</h2>
    <label>Link method<select value={mode} onChange={e => setMode(e.target.value)}>
      <option value="existing">Link existing board</option><option value="new">Create board with contract drafts</option>
    </select></label>
    <fieldset disabled={busy}>
      {mode === 'existing' ? <label>Register board<select name="boardId" required>
        <option value="">Choose a board…</option>
        {register.boards.filter(b => !b.archived_at).map(b => <option value={b.id} key={b.id}>{text(b,'code')} · {text(b,'location')}</option>)}
      </select></label> : <>
        <p>Create missing counterparties in Vendors and clients. Confirm each contract period in the register after linking.</p>
        <div className="media-form-grid">
          <label>Board ID<input name="code" maxLength={50} required /></label>
          <label>Description<input name="description" maxLength={250} defaultValue={review.location} required /></label>
          {(['VENDOR','CLIENT'] as const).map(kind => <label key={kind}>
            {kind === 'VENDOR' ? 'Vendor' : 'Client'} from the register
            <select name={kind === 'VENDOR' ? 'vendorId' : 'clientId'}>
              <option value="">Leave as draft</option>
              {register.parties.filter(p => p.kind === kind && !p.archived_at).map(p => <option value={p.id} key={p.id}>{text(p,'name')}</option>)}
            </select>
          </label>)}
        </div>
      </>}
      <div className="media-actions">
        <button className="button" type="submit">{mode === 'existing' ? 'Link board' : 'Create board and drafts'}</button>
        <button className="button button-secondary" type="button" onClick={onClose}>Cancel</button>
      </div>
    </fieldset>
  </form>;
}

export function MediaWorkbookPage({ register, onRegisterChanged, initialRow }: {
  register?: Register; onRegisterChanged?: () => Promise<void>; initialRow?: number;
} = {}) {
  const [allowed, setAllowed] = useState<boolean>();
  const [file, setFile] = useState<File>();
  const [preview, setPreview] = useState<Preview>();
  const [history, setHistory] = useState<Batch[]>([]);
  const [batchId, setBatchId] = useState<string>();
  const [source, setSource] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [result, setResult] = useState<StageResult>();
  const [reviews, setReviews] = useState<Review[]>([]);
  const [links, setLinks] = useState<Record<string, string>>({});
  const [workingDraft, setWorkingDraft] = useState<Draft>();
  const [guidedDraft, setGuidedDraft] = useState<Draft>();
  const [linkDraft, setLinkDraft] = useState<Draft>();
  const [notice, setNotice] = useState('');
  const openedSetup = useRef('');

  async function loadBatch(id: string, signal?: AbortSignal) {
    const [sourcePreview, reviewRows, linked] = await Promise.all([
      apiRequest<Preview>(`${root}/imports/${id}`, { signal }),
      apiRequest<Review[]>(`${root}/imports/${id}/reviews`, { signal }),
      apiRequest<Record<string,string>>(`${root}/imports/${id}/links`, { signal }),
    ]);
    if (signal?.aborted) return;
    show(sourcePreview);
    setReviews(reviewRows);
    setLinks(linked);
    setBatchId(id);
    return { sourcePreview, linked };
  }

  useEffect(() => {
    const controller = new AbortController();
    void apiRequest<{ allowed: boolean }>(`${root}/access`, { signal: controller.signal })
      .then(async access => {
        if (controller.signal.aborted) return;
        if (access.allowed) {
          const batches = await apiRequest<Batch[]>(`${root}/imports`, { signal: controller.signal });
          if (controller.signal.aborted) return;
          setHistory(batches);
          const latest = batches[0];
          if (latest) await loadBatch(latest.id, controller.signal);
        }
        setAllowed(access.allowed);
      })
      .catch(cause => { if (!controller.signal.aborted) setError(errorMessage(cause)); });
    return () => controller.abort();
  }, []);

  useEffect(() => {
    if (!batchId || batchId !== history[0]?.id || !preview || (!initialRow && !register)) return;
    const key = `${batchId}:${initialRow ?? 'next'}`;
    if (openedSetup.current === key) return;
    openedSetup.current = key;
    if (!initialRow) {
      setGuidedDraft(preview.drafts.find(row => !links[row.row]));
      return;
    }
    const draft = preview.drafts.find(row => row.row === initialRow);
    if (register && draft && !links[draft.row]) setGuidedDraft(draft);
    else setWorkingDraft(draft);
  }, [initialRow, batchId, preview, history, register, links]);

  async function run(action: () => Promise<void>) {
    setBusy(true);
    setError('');
    setNotice('');
    try { await action(); }
    catch (cause) { setError(errorMessage(cause)); }
    finally { setBusy(false); }
  }

  function show(next: Preview) {
    setPreview(next);
    setSource(next.sheets.find(sheet => sheet.name === 'OOH Sheet')?.name ?? '');
  }

  async function stageFile(selected: File, guided: boolean) {
    const body = new FormData();
    body.set('workbook', selected);
    const staged = await apiRequest<StageResult>(`${root}/imports`, { method: 'POST', body });
    const loaded = await loadBatch(staged.id);
    setResult(staged);
    setHistory(await apiRequest<Batch[]>(`${root}/imports`));
    if (guided && loaded) {
      const first = loaded.sourcePreview.drafts.find(draft => !loaded.linked[draft.row]);
      setGuidedDraft(first);
      setNotice(first ? 'Workbook saved. Complete the details for each board, or finish later.'
        : 'All workbook boards are already linked to the register.');
    }
    await onRegisterChanged?.();
  }

  async function upload(mode: 'preview' | 'stage') {
    if (!file) return;
    if (mode === 'stage') {
      await stageFile(file, Boolean(register));
    } else {
      const body = new FormData();
      body.set('workbook', file);
      show(await apiRequest<Preview>(`${root}/imports/preview`, { method: 'POST', body }));
    }
  }

  async function openBatch(id: string) {
    const loaded = await loadBatch(id);
    setFile(undefined);
    setWorkingDraft(undefined);
    setGuidedDraft(register && loaded ? loaded.sourcePreview.drafts.find(row => !loaded.linked[row.row]) : undefined);
    setLinkDraft(undefined);
    setResult(undefined);
  }

  async function saveReview(draft: Draft, body: Omit<Review, 'row'>) {
    const saved = await apiRequest<Review>(`${root}/imports/${batchId}/reviews/${draft.row}`, { method: 'PUT', body });
    setReviews(current => [...current.filter(item => item.row !== saved.row), saved]);
    setWorkingDraft(undefined);
    setNotice('Board review saved.');
    await onRegisterChanged?.();
  }

  async function linkBoard(draft: Draft, fields: Record<string, string | null>) {
    const result = await apiRequest<{ id: string }>(`${root}/register/link-import`, { method: 'POST',
      body: { ...fields, sourceBatch: batchId, sourceRow: draft.row } });
    setLinks(current => ({ ...current, [draft.row]: result.id }));
    setLinkDraft(undefined);
    setNotice('Workbook row linked to the register.');
    await onRegisterChanged?.();
  }

  async function completeBoard(draft: Draft, body: Record<string, unknown>) {
    if (!batchId) return;
    await apiRequest(`${root}/imports/${batchId}/complete/${draft.row}`, { method: 'POST', body });
    const loaded = await loadBatch(batchId);
    const next = loaded?.sourcePreview.drafts.find(row => !loaded.linked[row.row]);
    setGuidedDraft(next);
    setNotice(next ? 'Board and agreements saved. Its upcoming dates are now in Reminders. Complete the next board when ready.'
      : 'Workbook boards are complete. Open Reminders for upcoming dates.');
    await onRegisterChanged?.();
  }

  async function download() {
    const blob = await apiDownload(`${root}/imports/${batchId}/discrepancies`, { contentType: 'text/csv' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = 'workbook-discrepancies.csv';
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  const sheet = preview?.sheets.find(item => item.name === source);
  const nextIncompleteBoard = preview?.drafts.find(draft => !links[draft.row]);

  return (
    <div>
      <h2 className="media-section-title">Workbook imports</h2>
      {error && <p role="alert" className="media-notice media-notice--error">{error}</p>}
      {notice && <p role="status" className="media-notice">{notice}</p>}
      {allowed === undefined && !error && <p role="status">Checking access…</p>}
      {allowed === false && <p>You do not have access to Media board imports.</p>}
      {allowed && <>
        {guidedDraft && register && <GuidedImportBoard key={`${batchId}:${guidedDraft.row}`} draft={guidedDraft}
          review={reviews.find(r => r.row === guidedDraft.row)} register={register} busy={busy}
          save={body => void run(() => completeBoard(guidedDraft, body))}
          later={() => setGuidedDraft(undefined)} />}
        {register && batchId && nextIncompleteBoard && !guidedDraft && <div className="media-actions">
          <button type="button" className="button" disabled={busy}
            onClick={() => { setWorkingDraft(undefined); setLinkDraft(undefined); setGuidedDraft(nextIncompleteBoard); }}>
            Resume guided setup
          </button>
        </div>}
        <BoardOverview preview={preview} reviews={reviews} />
        <section className="card media-upload" aria-labelledby="media-upload">
          <h2 id="media-upload">Upload the existing client status workbook</h2>
          <label htmlFor="media-workbook">Workbook (.xlsx, up to 512 KB)</label>{' '}
          <input id="media-workbook" type="file" accept=".xlsx" disabled={busy} onChange={event => {
            const selected = event.target.files?.[0];
            setFile(selected);
            setPreview(undefined);
            setBatchId(undefined);
            setResult(undefined);
            setReviews([]);
            setLinks({});
            setWorkingDraft(undefined);
            setGuidedDraft(undefined);
            setLinkDraft(undefined);
            setNotice('');
            setError('');
            if (register && selected) void run(() => stageFile(selected, true));
          }} />
          {register && <p>Choose your existing workbook to start. Boards and payments are confirmed individually.</p>}
          {!register && <div>
            <button className="button" disabled={!file || busy} onClick={() => void run(() => upload('preview'))}>Preview workbook</button>{' '}
            <button className="button button-secondary" disabled={!file || !preview || Boolean(batchId) || busy}
              onClick={() => void run(() => upload('stage'))}>Save staging batch</button>
          </div>}
          {busy && <p role="status">Working…</p>}
          {result && <p role="status">
            Batches inserted {result.inserted}, updated {result.updated}, skipped {result.skipped}. Rows flagged {result.flagged}.
          </p>}
        </section>

        {workingDraft && <ReviewEditor key={workingDraft.row} draft={workingDraft} busy={busy}
          review={reviews.find(r => r.row === workingDraft.row)}
          onClose={() => setWorkingDraft(undefined)}
          onSave={body => void run(() => saveReview(workingDraft, body))} />}
        {linkDraft && register && <LinkBoardForm key={linkDraft.row} draft={linkDraft}
          review={reviews.find(r => r.row === linkDraft.row)!} register={register} busy={busy}
          onClose={() => setLinkDraft(undefined)} onLink={body => void run(() => linkBoard(linkDraft, body))} />}

        {preview && <>
          <section className="card" aria-labelledby="media-drafts">
            <h2 id="media-drafts">Board candidates</h2>
            <p>{preview.drafts.length} OOH rows for review. {batchId ? 'Select Edit and approve dates beside a board.' : 'Save the staging batch to edit board details or approve dates.'}</p>
            <div className="media-table">
              <table>
                <thead><tr>
                  {['Source row', 'Client', 'Location', 'Vendor', ...dateColumns, 'Vendor rate', 'Client rate', 'Job status', 'Workbook notes', 'Review']
                    .map(label => <th key={label} scope="col">{label}</th>)}
                </tr></thead>
                <tbody>{preview.drafts.map(draft => {
                  const review = reviews.find(r => r.row === draft.row);
                  return <tr key={draft.row}>
                  <th scope="row">{draft.row}</th>
                  <td>{review ? review.client || 'Review' : draft.values.Client || 'Review'}</td>
                  <td>{review?.location || draft.values['OOH Location'] || 'Review'}</td>
                  <td>{review ? review.vendor || 'Review' : draft.values['Vendor Name'] || 'Review'}</td>
                  {dateColumns.map(label => <td key={label}>{draft.dates[label] || draft.values[label] || '—'}</td>)}
                  {['Cost with Vendor', 'Cost with Client'].map(label => {
                    const rate = draft.rates[label];
                    return <td key={label}>{rate == null ? 'Review' : amount.format(rate)}</td>;
                  })}
                  <td>{importedJobStatus(draft)}</td>
                  <td>
                    {draft.values['Client Status']}
                    {draft.operationalNotes.map(note => <p key={`${note.sheet}:${note.row}`}>
                      {[note.status, note.responsibility, note.notes].filter(Boolean).join(' · ')} ({note.sheet}, row {note.row})
                    </p>)}
                  </td>
                  <td>
                    {review && <p>Vendor: {review.vendorExpiry || '—'} {review.vendorApproved && '· Approved'}<br />
                      Client: {review.clientExpiry || '—'} {review.clientApproved && '· Approved'}</p>}
                    {review?.notes && <p>{review.notes}</p>}
                    {batchId && <button type="button" className="button button-secondary" disabled={busy}
                      onClick={() => { setLinkDraft(undefined); setGuidedDraft(undefined); setWorkingDraft(draft); }}>Edit and approve dates</button>}
                    {links[draft.row] ? <p>Linked to register</p> : <>
                      {batchId && register && <button type="button" className="button" disabled={busy}
                        onClick={() => { setWorkingDraft(undefined); setLinkDraft(undefined); setGuidedDraft(draft); }}>Complete board setup</button>}
                      {review && register && <button type="button" className="button button-secondary" disabled={busy}
                        onClick={() => { setWorkingDraft(undefined); setGuidedDraft(undefined); setLinkDraft(draft); }}>Link to register</button>}
                    </>}
                  </td>
                </tr>;
                })}</tbody>
              </table>
            </div>
          </section>

          <section className="card" aria-labelledby="media-discrepancies">
            <h2 id="media-discrepancies">Items to review</h2>
            <p>These are workbook entries with conflicting, missing, or repeated information that needs checking.</p>
            {batchId && <button className="button button-secondary" disabled={busy}
              onClick={() => void run(download)}>Download review items (CSV)</button>}
            <ul>{preview.findings.map((finding, index) => <li key={`${finding.sheet}:${finding.row}:${index}`}>
              <strong>{finding.sheet} · {finding.column || 'Row '}{finding.row}</strong> {finding.message}
            </li>)}</ul>
          </section>

          <section className="card" aria-labelledby="media-source">
            <h2 id="media-source">Workbook reference</h2>
            <details>
              <summary>View worksheet values</summary>
              <label htmlFor="media-sheet">Worksheet</label>{' '}
              <select id="media-sheet" value={source} onChange={event => setSource(event.target.value)}>
                {preview.sheets.map(item => <option key={item.name}>{item.name}</option>)}
              </select>
              {sheet && <div className="media-table">
                <table>
                  <thead><tr><th scope="col">Row</th><th scope="col">Field</th><th scope="col">Value</th></tr></thead>
                  <tbody>{sheet.rows.flatMap(row => row.number === sheet.headerRow ? [] : row.cells
                    .filter(cell => cell.display.trim())
                    .map(cell => {
                      const column = cell.address.replace(/[0-9]+$/, '');
                      return <tr key={cell.address}>
                        <th scope="row">{row.number}</th><td>{sheet.headers[column]?.trim() || column}</td><td>{cell.display}</td>
                      </tr>;
                    }))}</tbody>
                </table>
              </div>}
            </details>
          </section>
        </>}

        <section className="card" aria-labelledby="media-history">
          <h2 id="media-history">Staging history</h2>
          {history.length === 0 ? <p>No workbooks staged.</p> : <ul>{history.map(batch => <li key={batch.id}>
            <button type="button" className="button button-secondary" disabled={busy}
              onClick={() => void run(() => openBatch(batch.id))}>{batch.filename} · {batch.created_at}</button>
          </li>)}</ul>}
        </section>
      </>}
    </div>
  );
}
