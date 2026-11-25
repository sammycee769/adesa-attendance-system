import { useRevealEditor } from '../../utils/useRevealEditor';
import { useCallback, useEffect, useState, type FormEvent, type ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { apiDownload, apiRequest } from '../../api/client';
import { MediaWorkbookPage, importedJobStatus, type Batch, type Draft, type Preview, type Review } from './MediaWorkbookPage';
import { MediaRecordEditor, type RecordEdit } from './MediaRecordEditor';
import { contractStatus, label, lagosToday, mediaRoot, money, paymentStatus, text, type Entity, type MediaRow, type Register } from './mediaRegister';

const tabs = [
  ['overview','Dashboard'], ['boards','Boards'], ['parties','Vendors and clients'],
  ['contracts','Contracts'], ['payments','Payments'], ['reminders','Reminders'],
  ['reports','Reports'], ['imports','Workbook imports'], ['audit','Audit'],
] as const;
type Section = typeof tabs[number][0];
type LifecycleEdit = { entity: Entity; row: MediaRow; action: 'archive' | 'restore' | 'purge' };
type Report = { headers: string[]; rows: string[][] };
type ReminderPlan = { verifiedRecipients: number; upcoming: { entity: string; entity_id: string; stage: number;
  reminder_day: string; payload: { boardId: string; board: string; side: string; date: string; reference?: string } }[] };

function Table({ headers, rows, empty }: { headers: string[]; rows: ReactNode[][]; empty: string }) {
  const [page, setPage] = useState(0);
  const pages = Math.ceil(rows.length / 20);
  const current = Math.min(page, Math.max(0, pages - 1));
  if (!rows.length) return <p>{empty}</p>;
  return <div className="media-table"><table>
    <thead><tr>{headers.map(h => <th scope="col" key={h}>{h}</th>)}</tr></thead>
    <tbody>{rows.slice(current * 20, current * 20 + 20).map((row, i) => <tr key={i}>{row.map((cell, j) => j === 0
      ? <th scope="row" key={j}>{cell}</th> : <td key={j}>{cell}</td>)}</tr>)}</tbody>
  </table>{pages > 1 && <div className="media-actions">
    <button type="button" className="button button-secondary" disabled={current === 0}
      onClick={() => setPage(current - 1)}>Previous page</button>
    <span>Page {current + 1} of {pages}</span>
    <button type="button" className="button button-secondary" disabled={current + 1 === pages}
      onClick={() => setPage(current + 1)}>Next page</button>
  </div>}</div>;
}

function StatusPill({ value }: { value: string }) {
  const key = value.toUpperCase().replaceAll(' ', '_');
  const positive = ['RUNNING', 'COMPLETED', 'ACTIVE', 'PAID', 'SENT', 'LOCAL_CAPTURED'];
  const attention = ['WORK_IN_PROGRESS', 'EXPIRING', 'PART_PAYMENT', 'AWAITING_BALANCE', 'OWING', 'RETRY', 'PENDING'];
  const critical = ['EXPIRED', 'OVERDUE', 'FAILED'];
  const tone = positive.includes(key) ? 'positive' : attention.includes(key) ? 'attention'
    : critical.includes(key) ? 'critical' : 'neutral';
  return <span className={`media-status media-status--${tone}`}>{label(key)}</span>;
}

const formatError = (cause: unknown) => cause instanceof Error ? cause.message : 'The request failed. Try again.';
const currency = (row: MediaRow, key: string) => money(text(row, key));
const actionButton = (caption: string, action: () => void, disabled = false) =>
  <button type="button" className="button button-secondary" onClick={action} disabled={disabled}>{caption}</button>;

type ImportedBoard = { draft: Draft; review?: Review; location: string; client: string; vendor: string; status: string };

async function newestImportedBoards(signal?: AbortSignal): Promise<ImportedBoard[]> {
  const batches = await apiRequest<Batch[]>(`${mediaRoot}/imports`, { signal });
  const latest = batches[0];
  if (!latest) return [];
  const path = `${mediaRoot}/imports/${latest.id}`;
  const [preview, reviews, links] = await Promise.all([
    apiRequest<Preview>(path, { signal }),
    apiRequest<Review[]>(`${path}/reviews`, { signal }),
    apiRequest<Record<string, string>>(`${path}/links`, { signal }),
  ]);
  const byRow = new Map(reviews.map(review => [review.row, review]));
  return preview.drafts.filter(draft => !links[draft.row]).map(draft => {
    const review = byRow.get(draft.row);
    return {
      draft, review, status: importedJobStatus(draft),
      location: review?.location || draft.values['OOH Location'] || '',
      client: (review ? review.client : draft.values.Client) ?? '',
      vendor: (review ? review.vendor : draft.values['Vendor Name']) ?? '',
    };
  });
}

export function MediaWorkspace() {
  const [search, setSearch] = useSearchParams();
  const selected = search.get('section');
  const section: Section = tabs.find(([key]) => key === selected)?.[0] ?? 'overview';
  const boardId = search.get('board') ?? '';
  const [allowed, setAllowed] = useState<boolean>();
  const [register, setRegister] = useState<Register>();
  const [importedBoards, setImportedBoards] = useState<ImportedBoard[]>([]);
  const [reminders, setReminders] = useState<MediaRow[]>([]);
  const [plan, setPlan] = useState<ReminderPlan>();
  const [profit, setProfit] = useState<Report>();
  const [edit, setEdit] = useState<RecordEdit>();
  const [lifecycle, setLifecycle] = useState<LifecycleEdit>();
  const lifecycleEditor = useRevealEditor<HTMLFormElement>(lifecycle && `${lifecycle.action}:${lifecycle.row.id}`);
  const [archived, setArchived] = useState(false);
  const [query, setQuery] = useState('');
  const [vendorFilter, setVendorFilter] = useState('');
  const [clientFilter, setClientFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const [reportKind, setReportKind] = useState('BOARDS');
  const [report, setReport] = useState<Report>();

  const refresh = useCallback(async (signal?: AbortSignal) => {
    const [data, queue, dates, imported] = await Promise.all([
      apiRequest<Register>(`${mediaRoot}/register`, { signal }),
      apiRequest<MediaRow[]>(`${mediaRoot}/reminders`, { signal }),
      apiRequest<ReminderPlan>(`${mediaRoot}/reminders/plan`, { signal }),
      newestImportedBoards(signal),
    ]);
    if (signal?.aborted) return;
    setRegister(data);
    setReminders(queue);
    setPlan(dates);
    setImportedBoards(imported);
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    void apiRequest<{ allowed: boolean }>(`${mediaRoot}/access`, { signal: controller.signal })
      .then(async access => {
        if (controller.signal.aborted) return;
        setAllowed(access.allowed);
        if (access.allowed) await refresh(controller.signal);
      }).catch(cause => { if (!controller.signal.aborted) setError(formatError(cause)); });
    return () => controller.abort();
  }, [refresh]);

  useEffect(() => {
    if (section !== 'overview' || !register) return;
    const controller = new AbortController();
    const params = new URLSearchParams({ archived: String(archived) });
    if (boardId) params.set('board', boardId);
    if (vendorFilter) params.set('vendor', vendorFilter);
    if (clientFilter) params.set('client', clientFilter);
    void apiRequest<Report>(`${mediaRoot}/reports/PROFITABILITY/preview?${params}`, { signal: controller.signal })
      .then(value => { if (!controller.signal.aborted) setProfit(value); })
      .catch(cause => { if (!controller.signal.aborted) setError(formatError(cause)); });
    return () => controller.abort();
  }, [section, register, boardId, vendorFilter, clientFilter, archived]);

  const today = lagosToday();

  async function run(action: () => Promise<void>) {
    setBusy(true);
    setError('');
    setMessage('');
    try { await action(); }
    catch (cause) { setError(formatError(cause)); }
    finally { setBusy(false); }
  }

  async function mutate(entity: Entity, action: string, body: Record<string, unknown>) {
    await run(async () => {
      await apiRequest(`${mediaRoot}/register/${entity}/${action}`, { method: 'POST', body });
      await refresh();
      setEdit(undefined);
      setLifecycle(undefined);
      setMessage('Changes saved.');
    });
  }

  function navigate(next: Section, board = '') {
    setEdit(undefined);
    setLifecycle(undefined);
    setError('');
    setMessage('');
    setStatusFilter('');
    setReport(undefined);
    if (next !== 'contracts' && next !== 'payments' && next !== 'reports') { setFrom(''); setTo(''); }
    setSearch({ section: next, ...(board ? { board } : {}) });
  }

  const board = (id: unknown) => register?.boards.find(row => row.id === id);
  const party = (id: unknown) => register?.parties.find(row => row.id === id);
  const contract = (id: unknown) => register?.contracts.find(row => row.id === id);
  const obligation = (id: unknown) => register?.obligations.find(row => row.id === id);
  const visible = (r: MediaRow | undefined) => Boolean(r && (archived || !r.archived_at));
  const matchesBoard = (b: MediaRow | undefined) => visible(b) && (!boardId || b?.id === boardId)
    && (!vendorFilter || b?.vendor_id === vendorFilter) && (!clientFilter || b?.client_id === clientFilter)
    && [text(b,'code'),text(b,'location'),text(b,'description')].some(item => item.toLowerCase().includes(query.trim().toLowerCase()));
  const matchesStatus = (value: string) => !statusFilter || value === statusFilter;
  const inDates = (value: string) => (!from || value >= from) && (!to || Boolean(value) && value <= to);
  const boards = register?.boards.filter(b => matchesBoard(b) && matchesStatus(text(b,'status'))) ?? [];
  const visibleImported = importedBoards.filter(item => !boardId
    && (!vendorFilter || item.vendor.toLowerCase() === text(party(vendorFilter), 'name').toLowerCase())
    && (!clientFilter || item.client.toLowerCase() === text(party(clientFilter), 'name').toLowerCase())
    && (!statusFilter || item.status.toUpperCase().replaceAll(' ', '_') === statusFilter)
    && [item.location, item.client, item.vendor].some(value => value.toLowerCase().includes(query.trim().toLowerCase())));
  const contracts = register?.contracts.filter(c => visible(c) && matchesBoard(board(c.board_id))
    && matchesStatus(contractStatus(c,today)) && inDates(text(c,'end_date'))) ?? [];
  const paymentAgreements = register?.contracts.filter(c => c.confirmed && visible(c) && matchesBoard(board(c.board_id))
    && matchesStatus(paymentStatus(c)) && inDates(text(c,'end_date'))) ?? [];
  const agreementIds = new Set(paymentAgreements.map(c => c.id));
  const transactionAgreement = (t: MediaRow) => contract(t.contract_id ?? obligation(t.obligation_id)?.contract_id);
  const reversed = new Set(register?.transactions.filter(t => Boolean(t.reversal_of)).map(t => t.reversal_of));
  const boardName = (id: unknown) => text(board(id), 'code', 'Unknown board');
  const canArchive = register?.category === 'ADMIN' || register?.category === 'BUSINESS_MANAGER' || register?.category === 'COO';
  const admin = register?.category === 'ADMIN';
  const canAudit = admin || register?.category === 'COO';

  function rowActions(entity: Entity, row: MediaRow, extra?: ReactNode, canEdit = true) {
    return <div className="media-actions">
      {!row.archived_at && canEdit && actionButton('Edit', () => { setLifecycle(undefined); setEdit({ entity, row }); }, busy)}
      {!row.archived_at && extra}
      {!row.archived_at && canArchive && actionButton('Archive', () => { setEdit(undefined); setLifecycle({ entity, row, action: 'archive' }); }, busy)}
      {row.archived_at && admin && <>
        {actionButton('Restore', () => setLifecycle({ entity, row, action: 'restore' }), busy)}
        {actionButton('Permanently delete', () => setLifecycle({ entity, row, action: 'purge' }), busy)}
      </>}
    </div>;
  }

  function submitLifecycle(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!lifecycle) return;
    const form = new FormData(event.currentTarget);
    void mutate(lifecycle.entity, lifecycle.action, { id: lifecycle.row.id, revision: lifecycle.row.revision,
      reason: form.get('reason'), acknowledgeHistory: form.has('confirm') });
  }

  const reportParams = { board: boardId, vendor: vendorFilter, client: clientFilter, status: statusFilter, from, to, archived: String(archived) };
  function reportUrl() { return `${mediaRoot}/reports/${reportKind}`; }
  async function exportReport(format: 'pdf' | 'xlsx') {
    const blob = await apiDownload(reportUrl(), { params: { ...reportParams, format },
      contentType: format === 'pdf' ? 'application/pdf' : 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });
    const file = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = file;
    link.download = `media-${reportKind.toLowerCase()}.${format}`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(file), 1000);
  }

  if (!register) return <div className="media-workspace"><header className="media-hero"><h1>Media Boards</h1></header>
    {error ? <p role="alert">{error}</p> : allowed === false ? <p>You do not have access to Media Boards.</p>
      : <p role="status">Loading Media Boards…</p>}
  </div>;

  const activeContracts = contracts.filter(c => ['ACTIVE','EXPIRING'].includes(contractStatus(c,today)));
  const openAgreements = paymentAgreements.filter(c => Number(c.balance)>0);

  return <div className="media-workspace">
    <header className="media-hero">
      <div>
        <p className="media-hero-brand">ADESA Media</p>
        <h1>Media Boards</h1>
        <p>Manage boards, vendor and client agreements, payments and reminders.</p>
      </div>
      {section !== 'imports' && <Link className="button media-hero-action" to="?section=imports"
        onClick={event => { event.preventDefault(); navigate('imports'); }}>Open workbook</Link>}
    </header>
    <nav className="media-tabs" aria-label="Media Boards">
      {tabs.filter(([key]) => key !== 'audit' || canAudit).map(([key, caption]) =>
        <Link key={key} to={`?section=${key}`} aria-current={section === key ? 'page' : undefined}
          onClick={event => { event.preventDefault(); navigate(key); }}>{caption}</Link>)}
    </nav>
    {error && <p role="alert" className="media-notice media-notice--error">{error}</p>}
    {message && <p role="status" className="media-notice">{message}</p>}
    {section === 'imports' ? <MediaWorkbookPage register={register} onRegisterChanged={() => refresh()} initialRow={Number(search.get('row')) || undefined} /> : <>
      {['overview','boards','contracts','payments','reports'].includes(section) && <section className="card media-filters" aria-label="Filters">
        <div className="media-form-grid">
          {section !== 'reports' && <label>Find board<input type="search" value={query} onChange={e => setQuery(e.target.value)} /></label>}
          <label>Board<select value={boardId} onChange={e => { setSearch({ section, board: e.target.value }); setReport(undefined); }}>
            <option value="">All boards</option>{register.boards.map(b => <option key={b.id} value={b.id}>{text(b,'code')} · {text(b,'location')}</option>)}
          </select></label>
          {(['VENDOR','CLIENT'] as const).map(kind => <label key={kind}>{label(kind)}
            <select value={kind === 'VENDOR' ? vendorFilter : clientFilter} onChange={e => {
              (kind === 'VENDOR' ? setVendorFilter : setClientFilter)(e.target.value); setReport(undefined);
            }}>
              <option value="">All</option>{register.parties.filter(p => p.kind === kind).map(p =>
                <option key={p.id} value={p.id}>{text(p,'name')}</option>)}
            </select></label>)}
          {['contracts','payments','reports'].includes(section) && <>
            <label>{section === 'payments' ? 'Expiry from' : 'From date'}<input type="date" value={from} onChange={e => { setFrom(e.target.value); setReport(undefined); }} /></label>
            <label>{section === 'payments' ? 'Expiry through' : 'To date'}<input type="date" value={to} onChange={e => { setTo(e.target.value); setReport(undefined); }} /></label>
          </>}
          {['boards','contracts','payments','reports'].includes(section) && <label>Status
            <select value={statusFilter} onChange={e => { setStatusFilter(e.target.value); setReport(undefined); }}><option value="">All</option>
              {(section === 'boards' ? ['RUNNING','WORK_IN_PROGRESS','COMPLETED'] :
                section === 'contracts' ? ['ACTIVE','UPCOMING','EXPIRING','EXPIRED','RENEWED','DRAFT','ARCHIVED'] :
                section === 'payments' ? ['AWAITING_BALANCE','OWING','PART_PAYMENT','PAID'] :
                ['RUNNING','WORK_IN_PROGRESS','COMPLETED','ACTIVE','UPCOMING','EXPIRING','EXPIRED','RENEWED','DRAFT','ARCHIVED',
                  'AWAITING_BALANCE','OWING','PART_PAYMENT','PAID','PAYMENT','REVERSAL','CORRECTION',
                  'PENDING','RETRY','SENT','LOCAL_CAPTURED','FAILED','CANCELLED','UNKNOWN']).map(state =>
                <option key={state} value={state}>{label(state)}</option>)}
            </select>
          </label>}
          <label><input type="checkbox" checked={archived} onChange={e => { setArchived(e.target.checked); setReport(undefined); }} /> Include archived records</label>
        </div>
      </section>}

      {edit && <MediaRecordEditor key={`${edit.entity}:${edit.row?.id}:${edit.action}:${edit.renew}:${edit.kind}:${edit.agreement?.id}`}
        edit={edit} register={register} busy={busy} cancel={() => setEdit(undefined)} save={mutate} />}
      {lifecycle && <form ref={lifecycleEditor} className="card media-editor reveal-editor" onSubmit={submitLifecycle}>
        <h2>{label(lifecycle.action)} {lifecycle.entity}</h2>
        <p>{lifecycle.action === 'archive' ? 'Archiving removes the record from active views and retains dependent history.'
          : lifecycle.action === 'purge' ? 'Permanent deletion cannot be undone. Financial, import and renewal history is protected.'
          : 'Restoring returns the record to active views.'}</p>
        <label>Reason<textarea name="reason" maxLength={1000} required /></label>
        {lifecycle.action !== 'restore' && <label><input type="checkbox" name="confirm" required />
          {lifecycle.action === 'purge' ? ' Confirm permanent deletion' : ' I understand that dependent records and history will be retained'}</label>}
        <div className="media-actions"><button className="button" disabled={busy}>Confirm {lifecycle.action}</button>
          {actionButton('Cancel',() => setLifecycle(undefined),busy)}</div>
      </form>}

      {section === 'overview' && <>
        <section className="card"><h2>Board and contract status</h2>
          <dl className="media-metrics">{[
            ['Boards',boards.length + visibleImported.length],
            ['From workbook',visibleImported.length],
            ['Running',boards.filter(b=>b.status==='RUNNING').length + visibleImported.filter(b=>b.status==='Running').length],
            ['Work in progress',boards.filter(b=>b.status==='WORK_IN_PROGRESS').length + visibleImported.filter(b=>b.status==='Work in progress').length],
            ['Completed',boards.filter(b=>b.status==='COMPLETED').length + visibleImported.filter(b=>b.status==='Completed').length],
            ['Active contracts',activeContracts.length], ['Expiring contracts',contracts.filter(c=>contractStatus(c,today)==='EXPIRING').length],
            ['Expired contracts',contracts.filter(c=>contractStatus(c,today)==='EXPIRED').length],
            ['Renewal periods',contracts.filter(c=>Boolean(c.renews_id)).length],
            ['Open receivables',openAgreements.filter(c=>c.kind==='CLIENT').length],
            ['Open payables',openAgreements.filter(c=>c.kind==='VENDOR').length],
          ].map(([caption,value])=><div key={caption} data-featured={caption === 'Boards' || caption === 'Expiring contracts' || undefined}>
            <dt>{caption}</dt><dd>{value}</dd>
          </div>)}</dl>
          {actionButton('Open board register',()=>navigate('boards'))}{' '}
          {actionButton('Review imported boards',()=>navigate('imports'))}
        </section>
        {importedBoards.length > 0 && <section className="card"><h2>Boards from the workbook</h2>
          <Table headers={['Location','Client','Vendor','Job status','Date review','Action']}
            rows={visibleImported.map(item => [
              item.location || `Workbook row ${item.draft.row}`,item.client || '—',item.vendor || '—',<StatusPill value={item.status} />,
              item.review?.vendorApproved && item.review.clientApproved ? 'Both dates approved'
                : item.review?.vendorApproved ? 'Vendor date approved'
                : item.review?.clientApproved ? 'Client date approved' : 'Dates to review',
              <Link to={`?section=imports&row=${item.draft.row}`}>Review board</Link>,
            ])} empty="No workbook boards match the filters." />
        </section>}
        <section className="card"><h2>Outstanding balances</h2>
          <Table headers={['Board','Side','Agreement expiry','Remaining','Status']}
            rows={openAgreements.slice(0,12).map(c=>[boardName(c.board_id),label(text(c,'kind')),
              text(c,'end_date'),currency(c,'balance'),<StatusPill value={paymentStatus(c)} />])}
            empty="There are no outstanding balances in this view." />
        </section>
        <section className="card"><h2>Profitability and cash</h2>
          <p>Gross profit compares confirmed vendor and client agreements for the same dates. Cash comes from recorded payments.</p>
          {profit && <Table headers={profit.headers} rows={profit.rows.slice(0,12)}
            empty="No confirmed vendor and client contract periods have matching dates in this view."/>}
          {actionButton('Profitability report',()=>{setReportKind('PROFITABILITY');navigate('reports',boardId);})}
        </section>
        <section className="card"><h2>Reminders</h2>
          <p>{plan?.upcoming.filter(item => matchesBoard(board(item.payload.boardId))).length ?? 0} upcoming reminder stages in the next 30 days.
            {' '}{reminders.filter(r=>['PENDING','RETRY','SENDING'].includes(text(r,'status'))).length} deliveries awaiting processing.</p>
          {actionButton('Reminder schedule and history',()=>navigate('reminders',boardId))}
        </section>
      </>}

      {section === 'boards' && <section className="card"><h2>Board register</h2>
        {actionButton('Add board',()=>setEdit({entity:'board'}),busy)}
        <Table headers={['Board','Location','Vendor','Client','Manager','Job status','Actions']}
          rows={boards.map(b=>[
            <>{text(b,'code')}{b.archived_at && ' · Archived'}<br />{text(b,'description')}</>,text(b,'location'),text(party(b.vendor_id),'name','—'),
            text(party(b.client_id),'name','—'),register.managers.find(m=>m.id===b.manager_id)?.name??'—',
            <StatusPill value={text(b,'status','UNCONFIRMED')} />,rowActions('board',b,<>
              {actionButton('Contracts',()=>navigate('contracts',b.id))}
              {actionButton('Payments',()=>navigate('payments',b.id))}
            </>)])}
          empty="No registered boards match this view." />
        {importedBoards.length > 0 && <p><Link to="?section=overview">See boards from the workbook on the dashboard</Link></p>}
      </section>}

      {section === 'parties' && <section className="card"><h2>Vendors and clients</h2>
        <label><input type="checkbox" checked={archived} onChange={e=>setArchived(e.target.checked)}/> Include archived records</label>
        <div className="media-actions">
          {actionButton('Add vendor',()=>setEdit({entity:'party',kind:'VENDOR'}),busy)}
          {actionButton('Add client',()=>setEdit({entity:'party',kind:'CLIENT'}),busy)}
        </div>
        <Table headers={['Name','Type','Notes','Actions']} rows={register.parties.filter(visible).map(p=>
          [text(p,'name')+(p.archived_at ? ' · Archived' : ''),label(text(p,'kind')),text(p,'notes'),rowActions('party',p)])}
          empty="No vendors or clients are registered yet." />
      </section>}

      {section === 'contracts' && <section className="card"><h2>Vendor and client contracts</h2>
        {actionButton('Add contract',()=>setEdit({entity:'contract',row:undefined}),busy)}
        <Table headers={['Board','Side','Counterparty','Start','Expiry','Rate','Status','Actions']}
          rows={contracts.map(c=>[boardName(c.board_id),label(text(c,'kind')),text(party(c.party_id),'name','—'),
            text(c,'start_date','Needs confirmation'),text(c,'end_date','Needs confirmation'),currency(c,'amount'),
            <StatusPill value={contractStatus(c,today)} />,rowActions('contract',c,c.confirmed && !c.superseded
              ? actionButton('Renew',()=>setEdit({entity:'contract',row:c,renew:true}),busy) : undefined,!c.confirmed)])}
          empty="No contract periods match this view."/>
      </section>}

      {section === 'payments' && <>
        {(['CLIENT','VENDOR'] as const).map(side => <section className="card" key={side}>
          <h2>{side === 'CLIENT' ? 'Client payments' : 'Vendor payments'}</h2>
          <Table headers={['Board','Agreement expiry',side === 'CLIENT' ? 'Client amount' : 'Vendor cost',
            side === 'CLIENT' ? 'Received' : 'Paid','Remaining',
            ...(side === 'CLIENT' ? ['Invoice sent'] : []),'Status','Actions']}
            rows={paymentAgreements.filter(c => c.kind === side).map(c => {
              const active = !c.archived_at && !board(c.board_id)?.archived_at;
              return [boardName(c.board_id),text(c,'end_date'),currency(c,'amount'),currency(c,'paid'),currency(c,'balance'),
                ...(side === 'CLIENT' ? [<input type="checkbox" checked={Boolean(c.invoice_sent)} disabled={busy || !active}
                  aria-label={`Invoice sent for ${boardName(c.board_id)} ending ${text(c,'end_date')}`}
                  onChange={event => void mutate('contract','invoice',{
                    id:c.id, revision:c.invoice_revision, invoiceSent:event.target.checked,
                  })} />] : []),
                <StatusPill value={paymentStatus(c)} />,
                active && Number(c.balance)>0 && actionButton('Record payment',
                  ()=>setEdit({entity:'payment',action:'record',agreement:c}),busy)];
            })}
            empty={`No confirmed ${side.toLowerCase()} agreements match this view.`}/>
        </section>)}
        <section className="card"><h2>Payment ledger</h2>
          <Table headers={['Reference','Board and side','Date','Amount','Entry','SLA','Evidence or reason','Actions']}
            rows={register.transactions.filter(t=>agreementIds.has(transactionAgreement(t)?.id ?? '')).map(t=>[
              text(t,'reference'),`${boardName(transactionAgreement(t)?.board_id)} · ${label(text(transactionAgreement(t),'kind'))}`,text(t,'paid_on'),currency(t,'amount'),
              t.reversal_of?'Reversal':reversed.has(t.id)?'Reversed':t.replacement_for?'Replacement':'Payment',
              /^https?:\/\//i.test(text(t,'sla_link'))
                ? <a href={text(t,'sla_link')} target="_blank" rel="noopener noreferrer">Open SLA</a> : '—',
              [text(t,'evidence'),text(t,'reason')].filter(Boolean).join(' · '),
              !t.reversal_of && !reversed.has(t.id) && !obligation(t.obligation_id)?.archived_at && !transactionAgreement(t)?.archived_at
                && !board(transactionAgreement(t)?.board_id)?.archived_at && <div className="media-actions">
                {actionButton('Correct',()=>setEdit({entity:'payment',row:t,action:'correct'}),busy)}
                {actionButton('Reverse',()=>setEdit({entity:'payment',row:t,action:'reverse'}),busy)}
              </div>])}
            empty="No payments have been recorded for these agreements."/>
        </section>
      </>}

      {section === 'reminders' && <>
        <section className="card"><h2>Upcoming reminders</h2>
          <p>Each confirmed client and vendor agreement has reminders 30 and 7 days before its own expiry.</p>
          <p>{plan?.verifiedRecipients ?? 0} of 3 internal recipients verified. Delivery requires an approved worker configuration.</p>
          <Table headers={['Board','Reminder','Side','Expiry date','Reminder date','Days before']}
            rows={(plan?.upcoming ?? []).filter(item => !boardId || item.payload.boardId === boardId).map(item =>
              [item.payload.board,label(item.entity),label(item.payload.side),item.payload.date,item.reminder_day,item.stage])}
            empty="No confirmed agreements have an expiry reminder due in the next 30 days."/>
        </section>
        <section className="card"><h2>Reminder history</h2>
          <Table headers={['Board','Reminder','Internal recipient','Scheduled date','Days before','Status','Attempts']}
            rows={reminders.map(r=>[
              typeof r.payload === 'object' && r.payload !== null && 'board' in r.payload ? String(r.payload.board) : '—',
              label(text(r,'entity')),register.managers.find(m=>m.id===r.recipient_id)?.name ?? 'Former recipient',
              text(r,'reminder_day'),text(r,'stage'),<StatusPill value={text(r,'status')} />,text(r,'attempts')])}
            empty="There are no reminder deliveries in this environment."/>
        </section>
      </>}

      {section === 'reports' && <section className="card"><h2>Reports</h2>
        <label>Report{' '}<select value={reportKind} onChange={e=>{setReportKind(e.target.value);setReport(undefined);}}>
          {['BOARDS','CONTRACTS','EXPIRIES','RENEWALS','PAYMENTS','BALANCES','PROFITABILITY','REMINDERS'].map(kind=>
            <option key={kind} value={kind}>{label(kind)}</option>)}
        </select></label>
        {reportKind==='PROFITABILITY' && <p>Gross profit compares confirmed periods with matching start and expiry dates. Cash totals come from the ledger.</p>}
        <div className="media-actions">
          {actionButton('Preview report',()=>void run(async()=> {
            const params = new URLSearchParams(Object.entries(reportParams).filter(([,v])=>v!==''));
            setReport(await apiRequest<Report>(`${reportUrl()}/preview?${params}`));
          }),busy)}
          {actionButton('Download PDF',()=>void run(()=>exportReport('pdf')),busy)}
          {actionButton('Download Excel',()=>void run(()=>exportReport('xlsx')),busy)}
        </div>
        {report && <Table headers={report.headers} rows={report.rows} empty="No report records match the filters."/>}
      </section>}

      {section === 'audit' && canAudit && <section className="card"><h2>Audit</h2>
        <Table headers={['Action','Record','Actor','Recorded at','Changes']} rows={register.audit.map(a=>[
          label(text(a,'action')),label(text(a,'entity'))+' · '+text(a,'entity_id'),register.managers.find(m=>m.id===a.actor_id)?.name??text(a,'actor_id','System'),
          text(a,'recorded_at'),<details><summary>Before and after</summary>
            {text(a,'reason') && <p>{text(a,'reason')}</p>}<pre>{JSON.stringify({before:a.before_values,after:a.after_values},null,2)}</pre>
          </details>])} empty="No Media register changes recorded yet." />
      </section>}
    </>}
  </div>;
}
