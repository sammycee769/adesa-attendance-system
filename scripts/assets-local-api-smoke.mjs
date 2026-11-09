/** Complete local API lifecycle; no destination is configurable or remote. */
import assert from 'node:assert/strict';
const base='http://127.0.0.1:8081';
let ready=false;
for(let i=0;i<180;i++){try{const r=await fetch(`${base}/actuator/health/readiness`);if(r.ok){ready=true;break;}}catch{}await new Promise(resolve=>setTimeout(resolve,500));}
assert.ok(ready,'Local demo backend did not become ready');
class Session {
 cookies=new Map();token='';
 async request(path,method='GET',body,expected=200) {
  assert.ok(path.startsWith('/api/v1/'),'Only local API routes are allowed');
  const headers={Cookie:[...this.cookies].map(([k,v])=>`${k}=${v}`).join('; ')};
  if(body!==undefined)headers['Content-Type']='application/json';
  if(method!=='GET')headers['X-XSRF-TOKEN']=this.token;
  const response=await fetch(`${base}${path}`,{method,headers,body:body===undefined?undefined:JSON.stringify(body),redirect:'error'});
  for(const item of response.headers.getSetCookie()) {const [pair]=item.split(';');const at=pair.indexOf('=');this.cookies.set(pair.slice(0,at),pair.slice(at+1));}
  const content=await response.text();assert.equal(response.status,expected,`${method} ${path}: ${content}`);
  return content?JSON.parse(content):null;
 }
 async login(role) {
  this.token=(await this.request('/api/v1/auth/csrf')).token;
  await this.request('/api/v1/auth/login','POST',{email:`${role}@assets.example.test`,password:'LocalAssets-Only-2026!'});
  this.token=(await this.request('/api/v1/auth/csrf')).token;
  return this.request('/api/v1/auth/me');
 }
}
const admin=new Session(),it=new Session(),hr=new Session(),employee=new Session();
const adminUser=await admin.login('admin'),itUser=await it.login('it_admin'),hrUser=await hr.login('hr'),user=await employee.login('employee');
const path='/api/v1/assets';const date=new Date().toISOString().slice(0,10);const suffix=Date.now();
const subsidiaries=await it.request(`${path}/subsidiaries`);const subsidiaryId=subsidiaries[0].id;
async function asset(serial) {return (await it.request(path,'POST',{assetType:'LAPTOP',brand:'API Smoke',model:'Synthetic local device',serialNumber:serial,purchaseDate:'2025-01-01',condition:'GOOD'},201)).id;}
const current=await asset(`API-SYNTHETIC-${suffix}`),replacement=await asset(`API-REPLACEMENT-${suffix}`);
await it.request(path,'POST',{assetType:'LAPTOP',brand:'Duplicate',model:'Synthetic',serialNumber:`API-SYNTHETIC-${suffix}`,purchaseDate:'2025-01-01',condition:'GOOD'},409);
const assignment=(await it.request(`${path}/assignments`,'POST',{assetId:current,employeeId:user.id,subsidiaryId,assignmentDate:date,notes:'Synthetic local smoke assignment'},201)).id;
await it.request(`${path}/assignments/${assignment}/approve`,'POST',{notes:'Verified assignment'} ,204);
await employee.request(`${path}/${replacement}`,'GET',undefined,403);
await it.request('/api/v1/admin/employees','GET',undefined,403);
const issue=(await employee.request(`${path}/issues`,'POST',{assetId:current,title:'Synthetic battery fault',description:'Local smoke fault',category:'BATTERY',priority:'HIGH',notes:'No real device'},201)).id;
const repair=(await it.request(`${path}/repairs`,'POST',{assetId:current,issueId:issue,description:'Synthetic repair',repairType:'INTERNAL',technician:'Local smoke technician',estimatedCost:10,notes:'Test'},201)).id;
await employee.request(`${path}/repairs/${repair}`,'PATCH',{status:'APPROVED',decision:'REPAIR',notes:'Forbidden approval'},403);
for(const status of ['UNDER_REVIEW','APPROVED','IN_PROGRESS','COMPLETED'])await it.request(`${path}/repairs/${repair}`,'PATCH',{status,decision:'REPAIR',technician:'Local technician',actualCost:10,condition:'GOOD',notes:`Local smoke ${status}`},204);
assert.equal((await it.request(`${path}/${current}`)).status,'ASSIGNED');
const request=(await employee.request(`${path}/replacements`,'POST',{assetId:current,reason:'PERFORMANCE_ISSUES',description:'Synthetic replacement request'},201)).id;
await it.request(`${path}/replacements/${request}/approve`,'POST',{notes:'Synthetic approval'},204);
await it.request(`${path}/replacements/${request}/fulfil`,'POST',{replacementAssetId:replacement,subsidiaryId,notes:'Too early'},409);
const returned=(await it.request(`${path}/returns`,'POST',{assignmentId:assignment,expectedDate:date,notes:'Synthetic return'},201)).id;
await it.request(`${path}/returns/${returned}/complete`,'POST',{actualDate:date,condition:'GOOD',notes:'Physically inspected synthetic return'},204);
await it.request(`${path}/replacements/${request}/fulfil`,'POST',{replacementAssetId:replacement,subsidiaryId,notes:'Explicit synthetic selection'},204);
assert.equal((await it.request(`${path}/${replacement}`)).status,'ASSIGNED');
const clearance=(await hr.request(`${path}/clearance`,'POST',{employeeId:user.id,notes:'Synthetic exit clearance'},201)).id;
await hr.request(`${path}/clearance/${clearance}/approve`,'POST',{notes:'HR cannot approve'},403);
await it.request(`${path}/clearance/${clearance}/approve`,'POST',{notes:'Outstanding assets'},409);
// Return every current synthetic employee asset before final clearance.
const active=await it.request(`${path}/assignments?employeeId=${user.id}&status=ACTIVE&size=100`);
for(const x of active.content.filter(x=>x.approval_status==='APPROVED')) {
 const r=(await it.request(`${path}/returns`,'POST',{assignmentId:x.id,expectedDate:date,notes:'Local exit return'},201)).id;
 await it.request(`${path}/returns/${r}/complete`,'POST',{actualDate:date,condition:'GOOD',notes:'Verified local exit inspection'},204);
}
await it.request(`${path}/clearance/${clearance}/approve`,'POST',{notes:'All synthetic obligations resolved'},204);
const summary=await hr.request(`${path}/clearance/${clearance}/summary`);assert.equal(summary.status,'CLEARED');assert.deepEqual(summary.outstandingAssets,[]);
for(const report of ['INVENTORY','BY_EMPLOYEE','BY_SUBSIDIARY','AVAILABLE','DAMAGED','UNDER_REPAIR','REPAIR_COSTS','PENDING_REPLACEMENTS','OUTSTANDING_RETURNS','CLEARANCE'])assert.ok(Array.isArray(await it.request(`${path}/reports?report=${report}`)));
const history=await it.request(`${path}/${current}/history`);assert.ok(history.some(x=>x.action==='REPAIR_COMPLETED'));assert.ok(history.some(x=>x.action==='ASSET_RETURNED'));
console.log(JSON.stringify({api:'passed',current,replacement,clearance,status:summary.status,auditEvents:history.length,roles:[adminUser.role,itUser.role,hrUser.role,user.role],reports:10}));
