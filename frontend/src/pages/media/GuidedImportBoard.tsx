import { useRevealEditor } from '../../utils/useRevealEditor';
import type { FormEvent } from 'react';
import type { Draft, Review } from './MediaWorkbookPage';
import { text, type Register } from './mediaRegister';

const vendorRenewed = 'Expiration Date after renewal with vendors';
const vendorOriginal = 'Expiration Date with Vendor';
const clientExpiry = 'Expiration Date With Client';
type Side = 'vendor' | 'client';

function ContractFields({ draft, review, side }: { draft: Draft; review?: Review; side: Side }) {
  const vendor = side === 'vendor';
  const renewed = draft.dates[vendorRenewed];
  const original = draft.dates[vendorOriginal];
  const knownVendorDate = renewed && original && renewed !== original ? '' : renewed || original;
  const expiry = vendor
    ? review?.vendorExpiry || knownVendorDate || ''
    : review?.clientExpiry || draft.dates[clientExpiry] || '';
  const kind = vendor ? 'Vendor' : 'Client';
  const originalStart = draft.dates['initial Start Date'];
  const renewedStart = draft.dates['Renewed Date'];
  const start = vendor && renewedStart ? renewedStart : originalStart;
  return <fieldset className="media-import-fieldset">
    <legend>{kind} agreement and payment</legend>
    {vendor && <p>Workbook vendor expiry dates: {draft.values[vendorRenewed] || 'missing'} / {draft.values[vendorOriginal] || 'missing'}.
      {renewed && original && renewed !== original && ' Enter the correct date from the agreement.'}</p>}
    <div className="media-form-grid">
      <label>{kind} name<input name={`${side}.name`} required maxLength={200} defaultValue={
        (vendor ? review?.vendor || draft.values['Vendor Name'] : review?.client || draft.values.Client) ?? ''
      } /></label>
      <label>{kind} start date<input name={`${side}.startDate`} type="date" required defaultValue={start} /></label>
      <label>{kind} expiry<input name={`${side}.endDate`} type="date" required defaultValue={expiry} /></label>
      <label>{kind} agreement rate (NGN)<input name={`${side}.amount`} type="number" required min="0" step="0.01"
        defaultValue={draft.rates[vendor ? 'Cost with Vendor' : 'Cost with Client']} /></label>
      {!vendor && <label><input name="client.invoiceSent" type="checkbox" /> Client invoice sent</label>}
    </div>
  </fieldset>;
}

export function GuidedImportBoard({ draft, review, register, busy, save, later }: {
  draft: Draft; review?: Review; register: Register; busy: boolean;
  save: (data: Record<string, unknown>) => void; later: () => void;
}) {
  const editor = useRevealEditor<HTMLFormElement>();
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const read = (key: string) => String(form.get(key) ?? '').trim() || null;
    function sideFields(side: Side) {
      return {
        name: read(`${side}.name`), startDate: read(`${side}.startDate`), endDate: read(`${side}.endDate`),
        amount: read(`${side}.amount`),
        ...(side === 'client' ? { invoiceSent: form.has('client.invoiceSent') } : {}),
      };
    }
    save({ revision: review?.revision ?? 0, confirmed: form.has('confirmed'),
      location: read('location'), boardId: read('boardId'), vendor: sideFields('vendor'), client: sideFields('client') });
  }

  return <form ref={editor} className="card media-editor reveal-editor" aria-label="Complete workbook board" onSubmit={submit}>
    <h2>Complete workbook board · row {draft.row}</h2>
    <p>Check the filled values against the agreements. Confirm each side’s expiry to schedule its reminders.</p>
    {draft.values['Client Status'] && <p>Workbook status note: {draft.values['Client Status']}</p>}
    <fieldset disabled={busy}>
      <div className="media-form-grid">
        <label>Board location<input name="location" required maxLength={200} defaultValue={review?.location ?? draft.values['OOH Location'] ?? ''} /></label>
        <label>Board record<select name="boardId">
          <option value="">Create from this workbook row</option>
          {register.boards.filter(board => !board.archived_at).map(board =>
            <option key={board.id} value={board.id}>{text(board, 'code')} · {text(board, 'location')}</option>)}
        </select></label>
      </div>
      <ContractFields side="vendor" draft={draft} review={review} />
      <ContractFields side="client" draft={draft} review={review} />
      <label><input type="checkbox" name="confirmed" required /> I verified both agreements and the details entered above.</label>
      <div className="media-actions">
        <button type="submit" className="button">Confirm board and set reminders</button>
        <button type="button" className="button button-secondary" onClick={later}>Finish later</button>
      </div>
    </fieldset>
  </form>;
}
