import { useState } from 'react';
import type { PaymentConfig } from '../../api/payments';
export function PaymentEditor({payment, busy, onSave, onCancel, employees=[]}:{payment:PaymentConfig;busy:boolean;onSave:(draft:PaymentConfig)=>void;onCancel:()=>void;employees?:{id:string;full_name:string;email:string}[]}) {
 const [draft,setDraft]=useState(payment);
 const update=<K extends keyof PaymentConfig>(key:K,value:PaymentConfig[K])=>setDraft(d=>({...d,[key]:value}));
 return <form className="payment-editor" onSubmit={event=>{event.preventDefault();onSave(draft);}}>
  <h3>Edit {payment.name}</h3>
  <label>Assigned employee<select value={draft.owner_employee_id??''} onChange={e=>update('owner_employee_id',e.target.value||null)}><option value="">Unassigned — automatic sending blocked</option>{employees.map(e=><option key={e.id} value={e.id}>{e.full_name} ({e.email})</option>)}</select></label>
  <div className="payment-fields">
   <label>Name<input required maxLength={150} value={draft.name} onChange={e=>update('name',e.target.value)}/></label>
   <label>Amount<input type="number" required min="0.01" max="999999999999.99" step="0.01" value={draft.amount} onChange={e=>update('amount',Number(e.target.value))}/></label>
   <label>Currency<select value={draft.currency} onChange={e=>update('currency',e.target.value as 'NGN'|'USD')}><option>NGN</option><option>USD</option></select></label>
   <label>Estimated NGN equivalent<input maxLength={100} value={draft.estimated_ngn} onChange={e=>update('estimated_ngn',e.target.value)}/></label>
   <label>Monthly sending day<input type="number" required min={1} max={28} value={draft.scheduled_day} onChange={e=>update('scheduled_day',Number(e.target.value))}/></label>
   <label>Renewal day (optional)<input type="number" min={1} max={28} value={draft.renewal_day??''} onChange={e=>update('renewal_day',e.target.value?Number(e.target.value):null)}/></label>
  </div>
  <label>Description<textarea maxLength={2000} value={draft.description} onChange={e=>update('description',e.target.value)}/></label>
  <label>To<input required maxLength={500} value={draft.to_address} onChange={e=>update('to_address',e.target.value)}/></label>
  <label>CC (comma-separated)<input maxLength={2000} value={draft.cc_addresses} onChange={e=>update('cc_addresses',e.target.value)}/></label>
  <label>Subject<input required maxLength={300} value={draft.subject} onChange={e=>update('subject',e.target.value)}/></label>
  <label>Email body<textarea required maxLength={20000} rows={12} value={draft.body} onChange={e=>update('body',e.target.value)}/></label>
  <p className="hint">Variables: {'{{amount}}, {{estimated_ngn}}, {{renewal_day}}, {{billing_month}}, {{name}}'}. End with “Best regards,”; the signature is appended once.</p>
  <label className="payment-checkbox"><input type="checkbox" checked={draft.enabled} onChange={e=>update('enabled',e.target.checked)}/>Schedule enabled</label>
  <div className="payment-actions"><button className="button" disabled={busy}>Save changes</button><button className="button button-secondary" type="button" disabled={busy} onClick={onCancel}>Cancel</button></div>
 </form>;
}
