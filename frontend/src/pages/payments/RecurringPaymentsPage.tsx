import { useEffect, useState } from 'react';
import { paymentApi, type PaymentConfig, type PaymentDashboard, type PaymentMessage, type PaymentRecord } from '../../api/payments';
import { useAuth } from '../../auth/AuthProvider';
import { PaymentEditor } from './PaymentEditor';
function date(value:string|null|undefined) {return value?value.replace('T',' ').replace(/\[.*$/,''):'—';}
export function RecurringPaymentsPage() {
 const {user}=useAuth();const canManage=user?.role==='ADMIN';
 const [creating,setCreating]=useState(false);
 const [data,setData]=useState<PaymentDashboard|null>(null);
 const [error,setError]=useState('');const [notice,setNotice]=useState('');const [busy,setBusy]=useState(false);
 const [editing,setEditing]=useState<string|null>(null);const [preview,setPreview]=useState<PaymentMessage|null>(null);
 const [month,setMonth]=useState(()=>{const parts=new Intl.DateTimeFormat('en-CA',{timeZone:'Africa/Lagos',year:'numeric',month:'2-digit'}).formatToParts(new Date());return `${parts.find(p=>p.type==='year')?.value}-${parts.find(p=>p.type==='month')?.value}`;});
 const [signature,setSignature]=useState('');const [enabled,setEnabled]=useState(false);
 const [recovery,setRecovery]=useState<PaymentRecord|null>(null);const [reason,setReason]=useState('');
 const [accepted,setAccepted]=useState(false);const [providerId,setProviderId]=useState('');
 useEffect(()=>{const abort=new AbortController();paymentApi.dashboard(abort.signal).then(result=>{
  setData(result);setEnabled(result.settings.enabled);setSignature(result.settings.signature_html);
 }).catch(ex=>{if(!abort.signal.aborted)setError(ex instanceof Error?ex.message:'Unable to load recurring payments');});return()=>abort.abort();},[]);
 async function action(work:()=>Promise<unknown>,message:string) {
  setBusy(true);setError('');setNotice('');
  try{await work();const result=await paymentApi.dashboard();setData(result);setNotice(message);}catch(ex){setError(ex instanceof Error?ex.message:'Request failed');}finally{setBusy(false);}
 }
 async function save(payment:PaymentConfig){await action(async()=>{if(payment.id==='new')await paymentApi.create(payment);else await paymentApi.save(payment);setEditing(null);setCreating(false);},'Payment configuration saved.');}
 return <section className="payments-page">
  <h1>Recurring Payments</h1>
  <p>Monthly payment requests at 9:00 AM WAT. Weekend dates move to the preceding Friday.</p>
  {error?<p role="alert" className="error-message">{error}</p>:null}
  {notice?<p role="status">{notice}</p>:null}
  {!data?(error?null:<p role="status">Loading recurring payments…</p>):<>
   {canManage?<form className="card payment-settings" onSubmit={e=>{e.preventDefault();void action(()=>paymentApi.settings(enabled,signature),'Automation settings saved.');}}>
    <h2>Automation and signature</h2>
    <p><strong>{data.settings.enabled?'Automatic sending enabled':'Automatic sending disabled'}</strong> · Delivery: {data.settings.transport} · From: {data.settings.sender}</p>
    {data.settings.transport==='mock'?<p className="payment-warning">Preview delivery only. No real emails are sent, including when automation is enabled.</p>:null}
    <label className="payment-checkbox"><input type="checkbox" checked={enabled} onChange={e=>setEnabled(e.target.checked)}/>Enable automatic sending</label>
    <label>Christian’s existing Zoho HTML signature<textarea rows={6} maxLength={100000} value={signature} onChange={e=>setSignature(e.target.value)} placeholder="Existing Christian signature; clear to restore the imported original"/></label>
    <p className="hint">Christian’s associated Zoho signature is loaded by default. Original logos use {'{{PUBLIC_URL}}'}/assets/email/ links. Keep the original layout, contact details and confidentiality notice. Clearing this field restores the imported original.</p>
    <button className="button" disabled={busy}>Save automation settings</button>
   </form>:null}
   {canManage?<button className="button" disabled={busy} onClick={()=>setCreating(true)}>Add recurring payment</button>:null}
   {creating&&canManage?<div className="card"><PaymentEditor payment={newPayment()} employees={data.employees} busy={busy} onSave={p=>void save(p)} onCancel={()=>setCreating(false)}/></div>:null}
   {!data.payments.length?<p className="card">No recurring payments have been assigned to you yet.</p>:null}
   <label className="payment-month">Preview billing month<input type="month" min="2000-01" max="2099-12" value={month} onChange={e=>setMonth(e.target.value)} required/></label>
   <div className="payment-grid">{data.payments.map(payment=><article className="card payment-card" key={payment.id}>
    <h2>{payment.name}</h2><p className="payment-amount">{new Intl.NumberFormat('en-NG',{style:'currency',currency:payment.currency}).format(payment.amount)}</p>
    {payment.estimated_ngn?<p>Estimated: {payment.estimated_ngn}</p>:null}
    <dl>{canManage?<><dt>Assigned employee</dt><dd>{payment.owner_name??'Unassigned'}{payment.owner_email?` (${payment.owner_email})`:null}</dd></>:null}<dt>Monthly scheduled day</dt><dd>{payment.scheduled_day}</dd><dt>Scheduled date</dt><dd>{date(payment.scheduled_date)}</dd>
     <dt>Adjusted sending date</dt><dd>{date(payment.adjusted_date)}</dd><dt>Next request</dt><dd>{date(payment.next_request_date)}</dd>
     <dt>Renewal date</dt><dd>{date(payment.renewal_date)}</dd><dt>Schedule</dt><dd>{payment.enabled?'Enabled':'Disabled'}</dd>
     <dt>Automatic sending</dt><dd>{payment.enabled&&data.settings.enabled?'Enabled':'Disabled'}</dd>
     <dt>Last successful send</dt><dd>{date(payment.last_successful_send)}</dd>
     <dt>To</dt><dd>{payment.to_address}</dd><dt>CC</dt><dd>{payment.cc_addresses}</dd><dt>Subject</dt><dd>{payment.subject}</dd></dl>
    <details><summary>Upcoming monthly requests</summary><ul>{payment.upcoming.map(d=><li key={d}>{date(d)}</li>)}</ul></details>
    <div className="payment-actions">
     <button className="button button-secondary" disabled={busy||!month} onClick={()=>void action(async()=>setPreview(await paymentApi.preview(payment.id,month)),'Email preview generated; nothing sent.')}>Preview email</button>
     {canManage?<button className="button button-secondary" disabled={busy||!month} onClick={()=>void action(()=>paymentApi.generate(payment.id,month),'Preview request recorded; nothing queued or sent.')}>Record preview</button>:null}
     {canManage?<button className="button" disabled={busy} onClick={()=>setEditing(payment.id)}>Edit</button>:null}
    </div>
    {canManage&&editing===payment.id?<PaymentEditor employees={data.employees} key={payment.version} payment={payment} busy={busy} onSave={p=>void save(p)} onCancel={()=>setEditing(null)}/>:null}
   </article>)}</div>
   {preview?<section className="card payment-preview"><h2>Email preview</h2><p>From: {preview.from}<br/>To: {preview.to}<br/>CC: {preview.cc}</p><h3>{preview.subject}</h3>
    {!preview.signatureConfigured?<p role="status">The existing Christian signature still needs to be configured. Live delivery will be blocked.</p>:null}
    <iframe title="Payment email preview" sandbox="" referrerPolicy="no-referrer" srcDoc={preview.html}/>
    <details><summary>Plain text</summary><pre>{preview.text}</pre></details><button className="button button-secondary" onClick={()=>setPreview(null)}>Close preview</button></section>:null}
   <section className="card"><h2>Payment request history</h2><p>Missed requests need explicit recovery. Only the current billing month can be recovered. Uncertain deliveries must be reconciled against Zoho before retrying.</p>
    <div className="payment-table"><table><thead><tr><th>Payment</th><th>Billing month</th><th>Sending date</th><th>Status</th><th>Attempts</th><th>Sent / receipt</th><th>Actions</th></tr></thead><tbody>{data.history.map(r=><tr key={r.id}><td>{r.name}</td><td>{r.billing_month}</td><td>{r.send_date}</td><td>{r.status}</td><td>{r.attempts}</td><td>{date(r.sent_at)}<br/>{r.provider_message_id}</td><td>
     <button className="button button-secondary" onClick={()=>{try{setPreview(JSON.parse(r.snapshot) as PaymentMessage);}catch{setError('Stored preview is invalid');}}}>View email</button>
     {canManage&&['PREVIEW','MISSED','FAILED','RETRY','UNKNOWN'].includes(r.status)?<button className="button button-secondary" disabled={busy} onClick={()=>{setRecovery(r);setReason('');setProviderId('');setAccepted(false);}}>{r.status==='UNKNOWN'?'Reconcile':'Recover / retry'}</button>:null}
    </td></tr>)}</tbody></table></div>{!data.history.length?<p>No payment requests recorded.</p>:null}
   </section>
   {canManage&&recovery?<form className="card" onSubmit={e=>{e.preventDefault();void action(async()=>{if(recovery.status==='UNKNOWN')await paymentApi.reconcile(recovery.id,accepted,providerId,reason);else await paymentApi.recover(recovery.id,reason);setRecovery(null);},'Recovery action recorded.');}}>
    <h2>{recovery.status==='UNKNOWN'?'Reconcile uncertain delivery':'Queue confirmed unsent request'}: {recovery.name}</h2>
    {recovery.status==='UNKNOWN'?<><label className="payment-checkbox"><input type="checkbox" checked={accepted} onChange={e=>setAccepted(e.target.checked)}/>Zoho confirms this message was accepted</label>
     {!accepted?<p>Confirm with Zoho that this message was never accepted before marking it failed. Recovery is a separate action.</p>:null}
     <label>Provider message ID<input maxLength={300} required={accepted} value={providerId} onChange={e=>setProviderId(e.target.value)}/></label></>:<p>This queues the request for delivery today using the saved configuration. Delivery mode: {data.settings.transport}. Automation and schedule must be enabled.</p>}
    <label>Reason / provider verification<textarea required maxLength={1000} value={reason} onChange={e=>setReason(e.target.value)}/></label>
    <div className="payment-actions"><button className="button" disabled={busy}>Confirm {recovery.status==='UNKNOWN'?'reconciliation':'queue request'}</button><button className="button button-secondary" type="button" onClick={()=>setRecovery(null)}>Cancel</button></div>
   </form>:null}
   <section className="card"><h2>Email delivery attempts</h2><div className="payment-table"><table><thead><tr><th>Request</th><th>Attempt</th><th>Started</th><th>Finished</th><th>Result</th><th>Receipt / detail</th></tr></thead><tbody>{data.attempts.map(a=><tr key={a.id}><td>{a.request_id}</td><td>{a.attempt}</td><td>{date(a.started_at)}</td><td>{date(a.finished_at)}</td><td>{a.result}</td><td>{a.provider_message_id}<br/>{a.detail}</td></tr>)}</tbody></table></div>{!data.attempts.length?<p>No delivery attempts.</p>:null}</section>
   {canManage?<section className="card"><h2>Audit log</h2><div className="payment-table"><table><thead><tr><th>Time</th><th>Actor</th><th>Action</th><th>Target</th><th>Detail</th></tr></thead><tbody>{data.audit.map(a=><tr key={a.id}><td>{date(a.created_at)}</td><td>{a.actor}</td><td>{a.action}</td><td>{a.target}</td><td><details><summary>Details</summary><pre>{a.detail}</pre></details></td></tr>)}</tbody></table></div></section>:null}
  </>}
 </section>;
}

function newPayment():PaymentConfig {return {id:'new',code:'',name:'New recurring payment',amount:1,currency:'NGN',estimated_ngn:'',scheduled_day:1,renewal_day:null,description:'',to_address:'Finance Manager <finance@adesahq.com>',cc_addresses:'',subject:'Request for Payment – {{name}}',body:'Dear Finance Team,\n\nI would like to request payment of {{amount}} for {{name}}.\n\nThank you for your prompt attention.\n\nBest regards,',enabled:false,version:0,scheduled_date:'',adjusted_date:'',next_request_date:'',renewal_date:null,last_successful_send:null,upcoming:[]};}
