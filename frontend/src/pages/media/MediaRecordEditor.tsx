import { useRevealEditor } from '../../utils/useRevealEditor';
import type { FormEvent } from 'react';
import type { Entity, MediaRow, Option, Register } from './mediaRegister';
import { label, lagosToday, text } from './mediaRegister';

type Field = {
  name: string;
  label: string;
  type?: 'date' | 'money' | 'notes' | 'check' | 'url';
  options?: Option[];
  value?: string;
  checked?: boolean;
  required?: boolean;
  immutable?: boolean;
};
export type RecordEdit = { entity: Entity; row?: MediaRow; action?: 'record' | 'correct' | 'reverse'; renew?: boolean; kind?: string };

const options = (items: string[]): Option[] => items.map(value => ({ value, label: label(value) }));
const activeOptions = (rows: MediaRow[], display: (row: MediaRow) => string): Option[] =>
  rows.filter(row => !row.archived_at).map(row => ({ value: row.id, label: display(row) }));

function editorFields(edit: RecordEdit, register: Register): Field[] {
  const { entity, row, renew, action } = edit;
  const vendor = activeOptions(register.parties.filter(p => p.kind === 'VENDOR'), p => text(p, 'name'));
  const client = activeOptions(register.parties.filter(p => p.kind === 'CLIENT'), p => text(p, 'name'));
  const boards = activeOptions(register.boards, b => `${text(b, 'code')} · ${text(b, 'location')}`);
  const contractName = (c: MediaRow) => `${text(register.boards.find(b => b.id === c.board_id), 'code')} · ${label(text(c, 'kind'))} · ${text(c, 'end_date', 'Draft')}`;
  if (entity === 'party') return [
    { name: 'kind', label: 'Type', options: options(['VENDOR', 'CLIENT']), value: text(row, 'kind', edit.kind), required: true, immutable: Boolean(row) },
    { name: 'name', label: 'Name', value: text(row, 'name'), required: true },
    { name: 'notes', label: 'Notes', type: 'notes', value: text(row, 'notes') },
  ];
  if (entity === 'board') return [
    { name: 'code', label: 'Board ID', value: text(row, 'code'), required: true },
    { name: 'description', label: 'Description', value: text(row, 'description'), required: true },
    { name: 'location', label: 'Location', value: text(row, 'location'), required: true },
    { name: 'vendorId', label: 'Vendor', options: vendor, value: text(row, 'vendor_id') },
    { name: 'clientId', label: 'Client', options: client, value: text(row, 'client_id') },
    { name: 'managerId', label: 'Account manager', options: register.managers.map(m => ({ value: m.id, label: m.name })), value: text(row, 'manager_id') },
    { name: 'status', label: 'Job status', options: options(['RUNNING', 'WORK_IN_PROGRESS', 'COMPLETED']), value: text(row, 'status') },
    { name: 'notes', label: 'Notes', type: 'notes', value: text(row, 'notes') },
  ];
  if (entity === 'contract') return [
    { name: 'boardId', label: 'Board', options: boards, value: text(row, 'board_id'), required: true, immutable: Boolean(row) },
    { name: 'kind', label: 'Contract side', options: options(['VENDOR', 'CLIENT']), value: text(row, 'kind'), required: true, immutable: Boolean(row) },
    { name: 'partyId', label: 'Counterparty', options: [
      ...vendor.map(p => ({ ...p, label: `Vendor · ${p.label}` })), ...client.map(p => ({ ...p, label: `Client · ${p.label}` })),
    ], value: text(row, 'party_id') },
    { name: 'startDate', label: 'Start date', type: 'date', value: renew ? '' : text(row, 'start_date') },
    { name: 'endDate', label: 'Expiry date', type: 'date', value: renew ? '' : text(row, 'end_date') },
    { name: 'amount', label: 'Agreement amount (NGN)', type: 'money', value: text(row, 'amount') },
    { name: 'terms', label: 'Terms', type: 'notes', value: text(row, 'terms') },
    { name: 'confirmed', label: 'Confirm this contract period and its dates', type: 'check', checked: renew, immutable: renew },
  ];
  if (entity === 'obligation') return [
    { name: 'contractId', label: 'Confirmed contract', options: activeOptions(register.contracts.filter(c => c.confirmed), contractName),
      value: text(row, 'contract_id'), required: true, immutable: Boolean(row) },
    { name: 'reference', label: 'Obligation reference', value: text(row, 'reference'), required: true },
    { name: 'amount', label: 'Amount (NGN)', type: 'money', value: text(row, 'amount'), required: true },
    { name: 'dueDate', label: 'Due date', type: 'date', value: text(row, 'due_date') },
    { name: 'invoiceSent', label: 'Invoice sent', type: 'check', checked: Boolean(row?.invoice_sent) },
    { name: 'notes', label: 'Notes', type: 'notes', value: text(row, 'notes') },
  ];
  const paymentFields: Field[] = [];
  if (action === 'record') paymentFields.push({ name: 'obligationId', label: 'Obligation', required: true,
    options: activeOptions(register.obligations.filter(o => Number(o.balance) > 0), o => text(o, 'reference')) });
  if (action !== 'reverse') paymentFields.push(
    { name: 'amount', label: 'Amount (NGN)', type: 'money', required: true, value: text(row, 'amount') },
    { name: 'paidOn', label: 'Payment date', type: 'date', required: true, value: text(row, 'paid_on', lagosToday()) },
    { name: 'reference', label: 'Payment reference', required: true, value: text(row, 'reference') },
    { name: 'slaLink', label: 'SLA link', type: 'url', required: true, value: text(row, 'sla_link') },
    { name: 'evidence', label: 'Evidence reference or note', value: text(row, 'evidence') },
  );
  if (action !== 'record') paymentFields.push({ name: 'reason', label: 'Reason', type: 'notes', required: true });
  return paymentFields;
}

export function MediaRecordEditor({ edit, register, busy, cancel, save }: {
  edit: RecordEdit; register: Register; busy: boolean; cancel: () => void;
  save: (entity: Entity, action: string, payload: Record<string, unknown>) => Promise<void>;
}) {
  const editor = useRevealEditor<HTMLFormElement>(edit);
  const fields = editorFields(edit, register);
  const operation = edit.action ?? 'save';
  const title = edit.renew ? 'Renew contract' : edit.action ? `${label(edit.action)} payment`
    : `${edit.row ? 'Edit' : 'Add'} ${edit.entity === 'party' ? 'vendor or client' : edit.entity}`;
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const payload: Record<string, unknown> = {};
    for (const f of fields) {
      const value = f.immutable ? f.value : form.get(f.name);
      payload[f.name] = f.type === 'check' ? f.immutable ? Boolean(f.checked) : form.has(f.name)
        : typeof value === 'string' ? value.trim() || null : null;
    }
    if (edit.renew) payload.renewsId = edit.row?.id;
    else if (edit.row) {
      payload.id = edit.row.id;
      payload.revision = edit.row.revision;
    }
    void save(edit.entity, operation, payload);
  }

  return <form ref={editor} className="card media-editor reveal-editor" aria-label={title} onSubmit={submit}>
    <h2>{title}</h2>
    {edit.renew && <p>The previous contract period stays in renewal history.</p>}
    {edit.action === 'correct' && <p>This adds a reversal and a replacement payment to the ledger.</p>}
    {edit.action === 'reverse' && <p>This adds a reversal and restores the amount to the outstanding balance.</p>}
    <fieldset disabled={busy}>
      <div className="media-form-grid">
        {fields.map(f => <label key={f.name}>
          {f.type === 'check'
            ? <><input type="checkbox" name={f.name} defaultChecked={f.checked} disabled={f.immutable} /> {f.label}</>
            : <>{f.label}
              {f.options ? <select name={f.name} defaultValue={f.value ?? ''} required={f.required} disabled={f.immutable}>
                <option value="">Choose…</option>
                {f.options.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}
              </select> : f.type === 'notes' ? <textarea name={f.name} defaultValue={f.value} required={f.required} rows={3} />
                : <input name={f.name} type={f.type === 'money' ? 'number' : f.type ?? 'text'}
                  min={f.type === 'money' ? 0 : undefined} step={f.type === 'money' ? '0.01' : undefined}
                  maxLength={f.type === 'url' ? 2048 : undefined}
                  pattern={f.type === 'url' ? 'https?://.+' : undefined}
                  defaultValue={f.value} required={f.required} />}
            </>}
        </label>)}
      </div>
      <div className="media-actions">
        <button className="button" type="submit">{edit.action === 'reverse' ? 'Record reversal' : 'Save'}</button>
        <button className="button button-secondary" type="button" onClick={cancel}>Cancel</button>
      </div>
    </fieldset>
  </form>;
}
