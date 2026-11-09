import { useCallback, useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { NavLink, useParams } from 'react-router-dom';
import { apiRequest, apiDownload, buildQuery } from '../../api/client';
import { assetApi, assetCommand, listAssets, saveAssetFile, type AssetRow, type AssetPage as Page, type AssetDashboard } from '../../api/assets';
import { ErrorBanner, SuccessBanner } from '../../components/Banners';
import { useAuth } from '../../auth/AuthProvider';

type Field = {name: string; label: string; type?: string; options?: {value: string; label: string}[]; required?: boolean; value?: string; max?: string};
type ActionForm = {title: string; path: string; method?: 'POST'|'PUT'|'PATCH'; fields: Field[]; extra?: Record<string, unknown>};
const options = (values: string[]) => values.map(value => ({value, label: value.replaceAll('_',' ')}));
const conditions = ['NEW','GOOD','FAIR','DAMAGED','UNUSABLE'];
const sections: [string,string][] = [ ['dashboard','Asset Dashboard'], ['inventory','Asset Inventory'], ['requests','Asset Requests'], ['assignments','Assign Assets'], ['issues','Reported Issues'], ['repairs','Repairs & Maintenance'], ['replacements','Replacement Requests'], ['returns','Asset Returns'], ['clearance','Employee Clearance'], ['reports','Asset Reports'], ['audit-logs','Asset Audit History'] ];
const reports = ['INVENTORY','BY_EMPLOYEE','BY_SUBSIDIARY','AVAILABLE','DAMAGED','UNDER_REPAIR','REPAIR_COSTS','PENDING_REPLACEMENTS','OUTSTANDING_RETURNS','CLEARANCE'];
const text = (value: unknown) => value == null ? '—' : String(value).replaceAll('_',' ');
const notes: Field = {name:'notes',label:'Decision / supporting notes',type:'textarea',required:true};
const today = () => { const d=new Date();return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`; };

function WorkflowForm({form, close, submit, busy, error, searchAssets, searchEmployees, affiliations}: {affiliations:Record<string,string>;error:string;searchAssets:(value:string)=>void;searchEmployees:(value:string)=>void;form: ActionForm; close: () => void; submit: (data: Record<string,unknown>) => Promise<void>; busy: boolean}) {
 const id = useId();
 const [selected,setSelected]=useState<Record<string,string>>({});
 const panel=useRef<HTMLElement>(null);
 useEffect(()=>{const previous=document.activeElement as HTMLElement|null;panel.current?.querySelector<HTMLElement>('input,select,textarea,button')?.focus();return ()=>previous?.focus();},[]);
 async function save(event: FormEvent<HTMLFormElement>) {
  event.preventDefault(); const values: Record<string,unknown> = {...form.extra};
  const data = new FormData(event.currentTarget);
  for(const field of form.fields) { const value = String(data.get(field.name) ?? ''); values[field.name] = field.type === 'number' ? (value === '' ? null : Number(value)) : value || null; }
  await submit(values);
 }
 return <div className="asset-modal-backdrop"><section ref={panel} onKeyDown={event=>{
   if(event.key==='Escape'&&!busy) close();
   if(event.key==='Tab') {const fields=panel.current?.querySelectorAll<HTMLElement>('input,select,textarea,button');if(!fields?.length)return;const first=fields[0];const last=fields[fields.length-1];if(event.shiftKey&&document.activeElement===first){event.preventDefault();last?.focus();}else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first?.focus();}}
  }} role="dialog" aria-modal="true" aria-labelledby={id} className="card asset-modal">
  <h2 id={id}>{form.title}</h2><ErrorBanner message={error}/>
  {form.fields.some(f=>['assetId','replacementAssetId','assignmentId'].includes(f.name)) && <label>Search assets by ID, serial, brand or model<input onChange={e=>searchAssets(e.target.value)}/></label>}
  {form.fields.some(f=>f.name==='employeeId') && <label>Search employee by name or email<input onChange={e=>searchEmployees(e.target.value)}/></label>}
  <form onSubmit={save} className="asset-form">
   {form.fields.map(field => <label key={field.name}>{field.label}
    {field.options ? <select onChange={event=>{const value=event.target.value;setSelected(previous=>({...previous,[field.name]:value,...(field.name==='employeeId'?{subsidiaryId:affiliations[value]??''}:{})}));}} autoFocus={field === form.fields[0]} name={field.name} required={field.required ?? true} value={selected[field.name]??field.value??''}><option value="">Select…</option>{field.options.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}</select>
     : field.type === 'textarea' ? <textarea name={field.name} required={field.required ?? true} maxLength={4000} defaultValue={field.value} />
     : <input autoFocus={field === form.fields[0]} name={field.name} type={field.type ?? 'text'} required={field.required ?? true} defaultValue={field.value} max={field.max} min={field.type === 'number' ? 0 : undefined} step={field.type === 'number' ? '0.01' : undefined} maxLength={field.name === 'title' ? 200 : 160} />}
   </label>)}
   <p>Confirm the details before saving. This action will be recorded in asset history.</p>
   <div className="asset-actions"><button className="button button-primary" disabled={busy}>{busy ? 'Saving…' : 'Confirm and save'}</button><button className="button button-secondary" type="button" onClick={close} disabled={busy}>Cancel</button></div>
  </form></section></div>;
}

function Chart({title, rows}: {title: string; rows: {label: string; value: number}[]}) {
 const max = Math.max(1,...rows.map(row => row.value));
 return <section className="card"><h3>{title}</h3>{rows.length === 0 ? <p>No records yet.</p> : <ul className="asset-chart">{rows.map(row => <li key={row.label}><span>{text(row.label)}</span><div style={{width: `${Math.max(1,row.value/max*100)}%`}} /><strong>{row.value}</strong></li>)}</ul>}</section>;
}

export function AssetManagementPage() {
 const {user} = useAuth(); const params = useParams();
 const manager = user?.role === 'ADMIN' || user?.role === 'IT_ADMIN'; const hr = user?.role === 'HR';
 const section = params.section ?? (manager || hr ? 'dashboard' : 'requests');
 const allowed = manager ? sections : hr ? sections.filter(([key]) => ['dashboard','inventory','requests','assignments','clearance','reports'].includes(key)) : sections.filter(([key]) => ['inventory','requests','assignments','issues','repairs','replacements','returns'].includes(key));
 const permitted = allowed.some(([key]) => key === section);
 const [page,setPage] = useState<Page>({content:[],totalElements:0,page:0,size:20});
 const [dashboard,setDashboard] = useState<AssetDashboard|null>(null);
 const [search,setSearch] = useState(''); const [status,setStatus] = useState(''); const [condition,setCondition] = useState(''); const [assetType,setAssetType] = useState(''); const [subsidiaryFilter,setSubsidiaryFilter] = useState(''); const [sort,setSort] = useState('assetCode'); const [direction,setDirection] = useState('asc');
 const [index,setIndex] = useState(0); const [revision,setRevision] = useState(0);
 const [loading,setLoading] = useState(false); const [busy,setBusy] = useState(false); const [error,setError] = useState(''); const [success,setSuccess] = useState('');
 const [form,setForm] = useState<ActionForm|null>(null); const [details,setDetails] = useState<unknown>(null);
 const [availableAssets,setAvailableAssets] = useState<AssetRow[]>([]);
 const [returnAssignments,setReturnAssignments] = useState<AssetRow[]>([]);
 const [assetSearch,setAssetSearch] = useState('');
 const [assetSearchQuery,setAssetSearchQuery]=useState('');
 useEffect(()=>{const timer=setTimeout(()=>setAssetSearchQuery(assetSearch),200);return ()=>clearTimeout(timer);},[assetSearch]);
 const [employees,setEmployees] = useState<AssetRow[]>([]); const [employeeSearch,setEmployeeSearch] = useState('');
 const [subsidiaries,setSubsidiaries] = useState<AssetRow[]>([]); const [assets,setAssets] = useState<AssetRow[]>([]);
 const [report,setReport] = useState(hr?'BY_EMPLOYEE':'INVENTORY'); const [reportRows,setReportRows] = useState<AssetRow[]>([]);
 const fail = (cause: unknown) => setError(cause instanceof Error ? cause.message : 'The request failed.');
 useEffect(() => {setIndex(0);setSearch('');setStatus('');setDetails(null);setForm(null);setCondition('');setAssetType('');setSubsidiaryFilter('');},[section]);
 useEffect(() => {
  if(!permitted) return;
  const abort = new AbortController(); setLoading(true);setError('');
  (async () => {
   if(section === 'dashboard') setDashboard(await apiRequest<AssetDashboard>(`${assetApi}/dashboard`,{signal:abort.signal}));
   else if(section === 'reports') setReportRows(await apiRequest<AssetRow[]>(`${assetApi}/reports${buildQuery({report})}`,{signal:abort.signal}));
   else setPage(await listAssets(section,{search,status,page:index,size:20,...(section==='inventory'?{condition,type:assetType,subsidiary:subsidiaryFilter,sort,direction}:{})},abort.signal));
  })().catch(cause => {if(!abort.signal.aborted) fail(cause);}).finally(() => {if(!abort.signal.aborted) setLoading(false);});
  return () => abort.abort();
 },[section,permitted,search,status,condition,assetType,subsidiaryFilter,sort,direction,index,revision,report]);
 useEffect(() => {
  if(!manager && !hr) return;
  const abort = new AbortController(); const timer = setTimeout(() => {
   apiRequest<AssetRow[]>(`${assetApi}/employees${buildQuery({search:employeeSearch})}`,{signal:abort.signal}).then(setEmployees).catch(cause => {if(!abort.signal.aborted) fail(cause);});
  },200); return () => {clearTimeout(timer);abort.abort();};
 },[manager,hr,employeeSearch]);
 useEffect(() => {
  const abort = new AbortController();
  (async () => {
   const [subsidiaryRows,assetRows,assignmentRows,availableRows] = await Promise.all([
    apiRequest<AssetRow[]>(`${assetApi}/${manager||hr?'subsidiaries':'request-subsidiaries'}`,{signal:abort.signal}),
    listAssets('inventory',{size:100,page:0,search:assetSearchQuery,status:''},abort.signal),
    manager?listAssets('assignments',{size:100,page:0,search:assetSearchQuery,status:'ACTIVE'},abort.signal):Promise.resolve(null),
    listAssets('available',{size:100,page:0,search:assetSearchQuery},abort.signal),
   ]);
   setSubsidiaries(subsidiaryRows);setAssets(assetRows.content);setAvailableAssets(availableRows.content);setReturnAssignments(assignmentRows?.content.filter(a=>a.approval_status==='APPROVED')??[]);
  })().catch(cause => {if(!abort.signal.aborted) fail(cause);}); return () => abort.abort();
 },[manager,hr,revision,assetSearchQuery]);
 const employeeOptions = employees.map(e => ({value:String(e.id),label:`${e.full_name} (${e.email})`}));
 const subsidiaryOptions = subsidiaries.filter(s=>s.active!==false).map(s => ({value:String(s.id),label:String(s.name)}));
 const assetOptions = (available=false) => (available ? availableAssets : assets.filter(a => a.employee_id === user?.id && a.status !== 'RETIRED')).map(a => ({value:String(a.id),label:`${a.asset_code} — ${a.brand} ${a.model}`}));
 const select = (name:string,label:string,opts:{value:string;label:string}[],value?:string):Field => ({name,label,options:opts,value});
 const employee = select('employeeId','Employee (search the roster above)',employeeOptions);
 const subsidiary = select('subsidiaryId','Subsidiary',subsidiaryOptions);
 const asset = select('assetId','Asset',assetOptions());
 const open = (title:string,path:string,fields:Field[],method:ActionForm['method']='POST',extra?:Record<string,unknown>) => {setError('');setSuccess('');setForm({title,path,fields,method,extra});};
 const decide = (title:string,path:string) => open(title,path,[notes]);
 async function submit(data:Record<string,unknown>) {
  if(!form || busy) return; setBusy(true);setError('');
  try {const path=form.path==='/employees/selected/subsidiary'?`/employees/${String(data.employeeId)}/subsidiary`:form.path;await assetCommand(path,data,form.method);setSuccess(`${form.title} saved.`);setForm(null);setRevision(v=>v+1);}
  catch(cause) {fail(cause);} finally {setBusy(false);}
 }
 async function view(path:string) {setError('');try {setDetails(await apiRequest(`${assetApi}${path}`));} catch(cause) {fail(cause);}}
 const download = useCallback(async (path:string,name:string,csv=false) => {
  setBusy(true);setError('');try {
   saveAssetFile(await apiDownload(`${assetApi}${path}`, {contentType:csv?'text/csv':'application/pdf'}),name);
  } catch(cause) {fail(cause);}finally {setBusy(false);}
 },[]);
 const registration:Field[] = [select('assetType','Asset type',options(['LAPTOP','MOBILE_PHONE'])),{name:'brand',label:'Brand'},{name:'model',label:'Model'},{name:'serialNumber',label:'Serial number'},{name:'purchaseDate',label:'Purchase date',type:'date',max:today()},select('condition','Condition',options(conditions))];
 const newAssignment = (a?:AssetRow) => open('Submit asset assignment','/assignments',[select('assetId','Available asset',assetOptions(true),a?String(a.id):undefined),employee,subsidiary,{name:'assignmentDate',label:'Assignment date',type:'date',value:today(),max:today()},{...notes,required:false}]);
 const newIssue = () => open('Report issue','/issues',[asset,{name:'title',label:'Issue title'},{name:'description',label:'Description',type:'textarea'},select('category','Category',options(['HARDWARE','SOFTWARE','BATTERY','SCREEN','KEYBOARD','NETWORK','PHYSICAL_DAMAGE','OTHER'])),select('priority','Priority',options(['LOW','MEDIUM','HIGH','CRITICAL'])),{...notes,required:false}]);
 const newRepair = (a?:AssetRow) => open('Request repair','/repairs',[select('assetId','Asset',assets.map(v=>({value:String(v.id),label:String(v.asset_code)})),a?String(a.id):undefined),{name:'issueId',label:'Related issue ID (optional)',required:false},{name:'description',label:'Problem description',type:'textarea'},select('repairType','Repair type',options(['INTERNAL','EXTERNAL'])),{name:'technician',label:'Vendor / technician',required:false},{name:'estimatedCost',label:'Estimated cost',type:'number',required:false},{...notes,required:false}]);
 const newAssetRequest = () => open('Request an asset','/requests',[select('assetId','Available asset',availableAssets.map(a=>({value:String(a.id),label:`${a.asset_code} — ${a.brand} ${a.model}`}))),subsidiary,{name:'reason',label:'Why do you need this asset?',type:'textarea'}]);
 const employeeRepair = () => open('Request a repair','/repairs/requests',[asset,{name:'description',label:'Problem description',type:'textarea'},{...notes,required:false}]);
 const rowActions = (row:AssetRow) => {
  const id=String(row.id); const current=String(row.status); const prefix=`/${section}/${id}`;
  return <div className="asset-actions">
   {section==='inventory' && <><button className="button button-secondary" onClick={()=>void view(`/${id}`)}>View details</button>{!hr && <button className="button button-secondary" onClick={()=>void view(`/${id}/history`)}>View history</button>}{manager && <>
    <button className="button button-secondary" onClick={()=>open('Edit asset',`/${id}`,registration.filter(f=>f.name!=='assetType').map(f=>({...f,value:String(row[({serialNumber:'serial_number',purchaseDate:'purchase_date'} as Record<string,string>)[f.name]??f.name]??'')})),'PUT',{version:row.version})}>Edit</button>
    {current==='AVAILABLE' && <button className="button button-primary" onClick={()=>newAssignment(row)}>Assign</button>}
    {!['RETIRED','LOST','DISPOSED'].includes(current) && <button className="button button-secondary" onClick={()=>newRepair(row)}>Mark for repair</button>}
    {user?.role==='ADMIN' && !['RETIRED','LOST','DISPOSED'].includes(current) && <button className="button button-secondary" onClick={()=>decide('Authorize loss accounting',`/${id}/account-loss`)}>Account for loss</button>}
    <button className="button button-secondary" onClick={()=>decide('Retire asset' ,`/${id}/status/RETIRED`)}>Retire</button>
   </>}</>}
   {manager && section==='requests' && current==='PENDING' && row.employee_id!==user?.id && <><button className="button button-primary" onClick={()=>decide('Approve asset request',`${prefix}/approve`)}>Approve request</button><button className="button button-secondary" onClick={()=>decide('Reject asset request',`${prefix}/reject`)}>Reject request</button></>}
   {manager && section==='assignments' && row.approval_status==='PENDING' && <><button className="button button-primary" onClick={()=>decide('Approve assignment',`${prefix}/approve`)}>Approve</button><button className="button button-secondary" onClick={()=>decide('Reject assignment',`${prefix}/reject`)}>Reject</button></>}
   {manager && section==='assignments' && current==='ACTIVE' && row.approval_status==='APPROVED' && <button className="button button-primary" onClick={()=>open('Initiate return','/returns',[{name:'expectedDate',label:'Expected return date',type:'date',value:today()},notes],'POST',{assignmentId:id})}>Request return</button>}
   {manager && section==='issues' && <><button className="button button-primary" onClick={()=>open('Update issue',prefix,[select('status','Issue status',options(['OPEN','UNDER_REVIEW','IN_PROGRESS','RESOLVED','CLOSED']),current),select('priority','Priority',options(['LOW','MEDIUM','HIGH','CRITICAL']),String(row.priority)),notes],'PATCH')}>Update issue</button><button className="button button-secondary" onClick={()=>open('Repair reported issue','/repairs',[{name:'description',label:'Problem description',type:'textarea',value:String(row.description)},select('repairType','Repair type',options(['INTERNAL','EXTERNAL'])),{name:'technician',label:'Vendor / technician'},{name:'estimatedCost',label:'Estimated cost',type:'number',required:false},notes],'POST',{assetId:row.asset_id,issueId:id})}>Create repair</button></>}
   {manager && section==='repairs' && !['COMPLETED','REJECTED','CANCELLED'].includes(current) && <button className="button button-primary" onClick={()=>open('Update repair',prefix,[select('status','Repair status',options(current==='REPORTED'?['UNDER_REVIEW','REJECTED','CANCELLED']:current==='UNDER_REVIEW'?['APPROVED','REJECTED','CANCELLED']:current==='APPROVED'?['IN_PROGRESS','CANCELLED']:['COMPLETED','CANCELLED'])),select('decision','Repair decision',options(['REPAIR','REPLACE','REJECT'])),{name:'technician',label:'Vendor / technician',value:String(row.technician??''),required:false},{name:'actualCost',label:'Actual cost (required to complete)',type:'number',required:false}, {...select('condition','Condition after repair (required to complete)',options(conditions)),required:false},notes],'PATCH')}>Manage repair</button>}
   {manager && section==='replacements' && ['PENDING','UNDER_REVIEW'].includes(current) && row.employee_id!==user?.id && <><button className="button button-primary" onClick={()=>decide('Review replacement',`${prefix}/review`)}>Review</button><button className="button button-primary" onClick={()=>decide('Approve replacement',`${prefix}/approve`)}>Approve</button><button className="button button-secondary" onClick={()=>decide('Reject replacement',`${prefix}/reject`)}>Reject</button></>}
   {manager && section==='replacements' && current==='APPROVED' && <button className="button button-primary" onClick={()=>open('Fulfil replacement',`${prefix}/fulfil`,[select('replacementAssetId','Replacement asset',assetOptions(true)),subsidiary,notes])}>Select replacement</button>}
   {manager && section==='returns' && ['PENDING','OVERDUE'].includes(current) && <button className="button button-primary" onClick={()=>open('Verify asset return',`${prefix}/complete`,[{name:'actualDate',label:'Actual return date',type:'date',value:today(),max:today()},select('condition','Inspected condition',options(conditions)),notes])}>Receive and verify</button>}
   {section==='clearance' && <><button className="button button-secondary" onClick={()=>void view(`${prefix}/summary`)}>Summary</button><button className="button button-secondary" onClick={()=>void download(`${prefix}/pdf`,'clearance.pdf')}>Download PDF</button>{manager && current!=='CLEARED' && <><button className="button button-primary" onClick={()=>decide('Approve clearance',`${prefix}/approve`)}>Approve clearance</button><button className="button button-secondary" onClick={()=>decide('Mark not cleared',`${prefix}/reject`)}>Not cleared</button></>}</>}
  </div>;
 };
 const columns:Record<string,string[]> = {
  requests:['asset_code','employee_name','subsidiary_name','reason','status','created_at','reviewed_by','reviewed_at','notes'],
  inventory:['asset_code','asset_type','brand','model','serial_number','employee_name','subsidiary_name','condition','status','purchase_date'],
  assignments:['asset_code','employee_name','subsidiary_name','assignment_date','status','approval_status','created_by','approved_by','notes'],
  issues:['asset_code','employee_name','title','description','category','priority','status','created_at','notes'],
  repairs:['asset_code','issue_id','description','repair_type','status','decision','technician','estimated_cost','actual_cost','submitted_date','approved_date','start_date','completion_date','approved_by','notes'],
  replacements:['asset_code','employee_name','reason','description','status','created_at','reviewed_by','approval_date','notes'],
  returns:['asset_code','employee_name','request_date','expected_date','actual_date','condition','display_status','received_by','notes'],
  clearance:['employee_name','employee_email','status','clearance_date','cleared_by','notes'],
  'audit-logs':['asset_code','employee_name','action','created_by','created_at','previous_state','new_state','notes'],
 };
 return <div className="asset-module"><h1>IT Asset Management</h1><nav className="asset-tabs" aria-label="Asset management">{allowed.map(([key,label])=><NavLink key={key} to={`/assets/${key}`}>{label}</NavLink>)}</nav>
  {!permitted ? <p role="alert">You do not have permission to access this section.</p> : <>
  <h2>{sections.find(([key])=>key===section)?.[1]}</h2>
  <ErrorBanner message={form?null:error}/><SuccessBanner message={success}/>
  {(manager || hr) && !['dashboard','audit-logs','reports'].includes(section) && <label className="asset-roster-search">Search employees by name or email<input value={employeeSearch} onChange={e=>setEmployeeSearch(e.target.value)} placeholder="Name or email" /></label>}
  <div className="asset-actions">
   {manager && section==='inventory' && <><button className="button button-primary" onClick={()=>open('Register asset','',registration)}>Add asset</button><button className="button button-secondary" onClick={()=>open('Add subsidiary','/subsidiaries',[{name:'name',label:'Subsidiary name'}])}>Add subsidiary</button><button className="button button-secondary" onClick={()=>open('Set employee subsidiary','/employees/selected/subsidiary',[employee,subsidiary],'PUT')}>Set employee subsidiary</button></>}
   {manager && section==='assignments' && <button className="button button-primary" onClick={()=>newAssignment()}>Assign asset</button>}
   {section==='requests' && <button className="button button-primary" onClick={newAssetRequest}>Request an asset</button>}
   {section==='repairs' && !manager && <button className="button button-primary" onClick={employeeRepair}>Request a repair</button>}
   {section==='issues' && <button className="button button-primary" onClick={newIssue}>Report issue</button>}
   {manager && section==='returns' && <button className="button button-primary" onClick={()=>open('Initiate asset return','/returns',[select('assignmentId','Active assignment',returnAssignments.map(a=>({value:String(a.id),label:`${a.asset_code} — ${a.employee_name}`}))),{name:'expectedDate',label:'Expected return date',type:'date',value:today()},notes])}>Initiate return</button>}
   {manager && section==='repairs' && <button className="button button-primary" onClick={()=>newRepair()}>Request repair</button>}
   {section==='replacements' && <button className="button button-primary" onClick={()=>open('Request replacement','/replacements',[asset,select('reason','Reason',options(['DAMAGED','FAULTY','OBSOLETE','PERFORMANCE_ISSUES','OTHER'])),{name:'description',label:'Replacement description',type:'textarea'}])}>Request replacement</button>}
   {(manager || hr) && section==='clearance' && <button className="button button-primary" onClick={()=>open('Initiate employee clearance','/clearance',[employee,notes])}>Initiate clearance</button>}
  </div>
  {section==='requests' && <p>Choose an available asset and submit your reason for IT review. Pending requests reserve the device until IT approves or rejects them.</p>}
  {section==='returns' && <p>Pending return requests are your in-application return notifications. Contact IT to arrange inspection and receipt.</p>}
  {loading && <p role="status">Loading asset records…</p>}
  {section==='dashboard' && dashboard && <>
   <div className="asset-stats">{Object.entries({pendingAssetRequests:'Pending Asset Requests',totalAssets:'Total Assets',availableAssets:'Available Assets',assignedAssets:'Assigned Assets',underRepair:'Assets Under Repair',damagedAssets:'Damaged Assets',pendingReplacements:'Pending Replacement Requests',pendingReturns:'Pending Returns',pendingClearances:'Pending Employee Clearances'}).map(([key,label])=><section className="card" key={key}><h3>{label}</h3><strong className="asset-stat">{String(dashboard[key as keyof AssetDashboard])}</strong></section>)}</div>
   <div className="asset-charts"><Chart title="Assets by Type" rows={dashboard.byType}/><Chart title="Assets by Subsidiary" rows={dashboard.bySubsidiary}/><Chart title="Assets by Status" rows={dashboard.byStatus}/><Chart title="Asset Condition Distribution" rows={dashboard.byCondition}/>{dashboard.monthlyRepairs && <Chart title="Monthly Repair Activity" rows={dashboard.monthlyRepairs}/>}</div>
   {dashboard.recentActivity && <section className="card"><h3>Recent activity</h3><ul>{dashboard.recentActivity.map((row,i)=><li key={i}>{text(row.created_at)} · {text(row.asset_code)} · {text(row.action)} · {text(row.notes)}</li>)}</ul></section>}
  </>}
  {section==='reports' && <><div className="asset-actions"><label>Report<select value={report} onChange={e=>setReport(e.target.value)}>{(hr?['BY_EMPLOYEE','BY_SUBSIDIARY','CLEARANCE']:reports).map(r=><option key={r} value={r}>{text(r)}</option>)}</select></label><button disabled={busy} className="button button-primary" onClick={()=>void download(`/reports/export?report=${report}&format=csv`,'assets.csv',true)}>Export CSV</button><button disabled={busy} className="button button-secondary" onClick={()=>void download(`/reports/export?report=${report}&format=pdf`,'assets.pdf')}>Export PDF</button></div><DataTable rows={reportRows} columns={Object.keys(reportRows[0]??{})}/></>}
  {!['dashboard','reports'].includes(section) && <>
   <div className="asset-filters"><label>Search<input value={search} onChange={e=>{setSearch(e.target.value);setIndex(0);}} placeholder="Asset, employee, serial, subsidiary…" /></label><label>Status / action<input value={status} onChange={e=>{setStatus(e.target.value.toUpperCase());setIndex(0);}} placeholder="All statuses"/></label>
    {section==='inventory' && <><label>Type<select value={assetType} onChange={e=>{setAssetType(e.target.value);setIndex(0);}}><option value="">All types</option>{options(['LAPTOP','MOBILE_PHONE']).map(o=><option key={o.value} value={o.value}>{o.label}</option>)}</select></label><label>Condition<select value={condition} onChange={e=>{setCondition(e.target.value);setIndex(0);}}><option value="">All conditions</option>{conditions.map(c=><option key={c}>{c}</option>)}</select></label>{(manager||hr)&&<label>Subsidiary<select value={subsidiaryFilter} onChange={e=>{setSubsidiaryFilter(e.target.value);setIndex(0);}}><option value="">All subsidiaries</option>{subsidiaryOptions.map(o=><option key={o.value} value={o.value}>{o.label}</option>)}</select></label>}<label>Sort<select value={sort} onChange={e=>setSort(e.target.value)}>{['assetCode','purchaseDate','status','brand','createdAt'].map(s=><option key={s}>{s}</option>)}</select></label><label>Order<select value={direction} onChange={e=>setDirection(e.target.value)}><option value="asc">Ascending</option><option value="desc">Descending</option></select></label></>}
   </div><DataTable rows={page.content} columns={section==='repairs'&&!manager?['asset_code','description','status','submitted_date','approved_date','start_date','completion_date','notes']:columns[section]??[]} actions={rowActions}/>
   <div className="asset-actions"><button className="button button-secondary" disabled={index===0||loading} onClick={()=>setIndex(i=>i-1)}>Previous</button><span>Page {index+1} · {page.totalElements} records</span><button className="button button-secondary" disabled={(index+1)*20>=page.totalElements||loading} onClick={()=>setIndex(i=>i+1)}>Next</button></div>
  </>}
  {details!=null && <section className="card asset-details"><div className="asset-actions"><h3>Asset record / history</h3><button className="button button-secondary" onClick={()=>setDetails(null)}>Close details</button><button className="button button-secondary" onClick={()=>window.print()}>Print summary</button></div><RecordDetails value={details}/></section>}
  {form && <WorkflowForm key={form.title+form.path} form={{...form,fields:form.fields.map(field=>field.name==='employeeId'?{...field,options:employeeOptions}:field.name==='subsidiaryId'?{...field,options:subsidiaryOptions}:field.name==='assignmentId'?{...field,options:returnAssignments.map(a=>({value:String(a.id),label:`${a.asset_code} — ${a.employee_name}`}))}:field.name==='replacementAssetId'?{...field,options:assetOptions(true)}:field.name==='assetId'?{...field,options:form.path==='/requests'?availableAssets.map(a=>({value:String(a.id),label:`${a.asset_code} — ${a.brand} ${a.model}`})):form.title.toLowerCase().includes('repair')&&manager?assets.map(v=>({value:String(v.id),label:String(v.asset_code)})):form.title.toLowerCase().includes('assignment')?assetOptions(true):assetOptions()}:field)}} affiliations={Object.fromEntries(employees.filter(e=>e.subsidiary_id).map(e=>[String(e.id),String(e.subsidiary_id)]))} searchAssets={setAssetSearch} searchEmployees={setEmployeeSearch} close={()=>setForm(null)} submit={submit} busy={busy} error={error}/>}
  </>}
 </div>;
}
function DataTable({rows,columns,actions}:{rows:AssetRow[];columns:string[];actions?:(row:AssetRow)=>React.ReactNode}) {
 return <div className="table-wrap"><table><thead><tr>{columns.map(c=><th key={c}>{text(c)}</th>)}{actions&&<th>Actions</th>}</tr></thead><tbody>{rows.length===0?<tr><td colSpan={columns.length+1}>No records found.</td></tr>:rows.map((row,i)=><tr key={String(row.id??i)}>{columns.map(c=><td key={c}>{text(row[c])}</td>)}{actions&&<td>{actions(row)}</td>}</tr>)}</tbody></table></div>;
}
function RecordDetails({value}:{value:unknown}) {
 if(Array.isArray(value)) return value.length ? <ol>{value.map((item,i)=><li key={i}><RecordDetails value={item}/></li>)}</ol> : <p>None</p>;
 if(value && typeof value==='object') return <dl>{Object.entries(value).map(([key,item])=><div key={key}><dt>{text(key)}</dt><dd>{typeof item==='object'&&item!=null?<RecordDetails value={item}/>:text(item)}</dd></div>)}</dl>;
 return <span>{text(value)}</span>;
}
