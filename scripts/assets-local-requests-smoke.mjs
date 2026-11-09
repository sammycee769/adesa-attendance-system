/** Complete local API lifecycle; no destination is configurable or remote. */
import assert from 'node:assert/strict';
const base='http://127.0.0.1:8081';
let ready=false;
for(let i=0;i<360;i++){try{const r=await fetch(`${base}/actuator/health/readiness`);if(r.ok){ready=true;break;}}catch{}await new Promise(resolve=>setTimeout(resolve,500));}
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
const it=new Session(),employee=new Session();
await it.login('it_admin');const user=await employee.login('employee');
const basePath='/api/v1/assets';
const catalogue=await employee.request(`${basePath}/available?size=100`);
assert.ok(catalogue.content.length>0,'Register an available synthetic local asset first');
const asset=catalogue.content[0];
assert.ok(!('employee_email' in asset));assert.ok(!('serial_number' in asset));
const subsidiaries=await employee.request(`${basePath}/request-subsidiaries`);
const requested=(await employee.request(`${basePath}/requests`,'POST',{assetId:asset.id,subsidiaryId:subsidiaries[0].id,reason:'Synthetic employee request and email walkthrough'},201)).id;
const filtered=await employee.request(`${basePath}/available?search=${encodeURIComponent(asset.asset_code)}`);
assert.equal(filtered.content.length,0,'A requested asset must disappear from the available catalogue');
await employee.request(`${basePath}/requests/${requested}/approve`,'POST',{notes:'Employee approval is forbidden'},403);
await it.request(`${basePath}/requests/${requested}/approve`,'POST',{notes:'IT approved the synthetic employee request'},204);
const own=await employee.request(`${basePath}/${asset.id}`);assert.equal(own.status,'ASSIGNED');
const repair=(await employee.request(`${basePath}/repairs/requests`,'POST',{assetId:asset.id,description:'Synthetic repair request from employee',notes:'Local notification test'},201)).id;
await it.request(`${basePath}/repairs/${repair}`,'PATCH',{status:'UNDER_REVIEW',notes:'IT reviewed request'},204);
await it.request(`${basePath}/repairs/${repair}`,'PATCH',{status:'APPROVED',decision:'REPAIR',notes:'IT approved repair'},204);
const replacements=(await employee.request(`${basePath}/replacements`,'POST',{assetId:asset.id,reason:'PERFORMANCE_ISSUES',description:'Synthetic replacement notification test'},201)).id;
await it.request(`${basePath}/replacements/${replacements}/approve`,'POST',{notes:'IT approved replacement; explicit device selection still required'},204);
for(const section of ['requests','repairs','replacements']) {
 const records=await employee.request(`${basePath}/${section}?size=100`);
 assert.ok(records.content.some(r=>r.asset_id===asset.id));
 if(section==='repairs')assert.ok(records.content.every(r=>!('actual_cost' in r)));
}
console.log(JSON.stringify({requests:'passed',employee:user.email,assetCode:asset.asset_code,assetRequest:requested,repairRequest:repair,replacementRequest:replacements,emailEvents:['ASSET_REQUEST_SUBMITTED','ASSET_REQUEST_APPROVED','REPAIR_REQUEST_SUBMITTED','REPAIR_REQUEST_APPROVED','REPLACEMENT_REQUEST_SUBMITTED','REPLACEMENT_REQUEST_APPROVED']}));
