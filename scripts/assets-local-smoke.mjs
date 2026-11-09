/** Local-only browser smoke check, using Chrome's DevTools protocol and Node built-ins.
 * Start Chrome with --remote-debugging-port=9227 and a fresh temporary profile first.
 */
import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
const origin='http://localhost:5174';
const tabs=await (await fetch('http://127.0.0.1:9227/json/list')).json();
const tab=tabs.find(t=>t.type==='page');assert.ok(tab,'A local Chrome page is required');
const socket=new WebSocket(tab.webSocketDebuggerUrl);
await new Promise((resolve,reject)=>{socket.onopen=resolve;socket.onerror=reject;});
let sequence=0;const pending=new Map();const errors=[];
socket.onmessage=event=>{const result=JSON.parse(event.data);if(result.id){const p=pending.get(result.id);if(p){pending.delete(result.id);result.error?p.reject(new Error(JSON.stringify(result.error))):p.resolve(result.result);}}if(result.method==='Runtime.exceptionThrown')errors.push(result.params.exceptionDetails.text);};
async function command(method,params={}) {const id=++sequence;const result=new Promise((resolve,reject)=>pending.set(id,{resolve,reject}));socket.send(JSON.stringify({id,method,params}));return result;}
async function evaluate(expression) {const result=await command('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});if(result.exceptionDetails)throw new Error(JSON.stringify(result.exceptionDetails));return result.result?.value;}
async function waitFor(expression) {for(let i=0;i<100;i++){if(await evaluate(`Boolean(${expression})`))return;await new Promise(resolve=>setTimeout(resolve,200));}throw new Error(`Timed out: ${expression}`);}
async function click(label) {const found=await evaluate(`(()=>{const button=[...document.querySelectorAll('button,a')].find(b=>b.textContent.trim()===${JSON.stringify(label)});if(!button)return false;button.click();return true;})()`);assert.ok(found,`Control missing: ${label}`);}
async function field(name,value) {await evaluate(`(()=>{const input=document.querySelector('[name="${name}"]')??document.getElementById('${name}');if(!input)throw Error('Missing field ${name}');const proto=input instanceof HTMLSelectElement?HTMLSelectElement.prototype:input instanceof HTMLTextAreaElement?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;Object.getOwnPropertyDescriptor(proto,'value').set.call(input,${JSON.stringify(value)});input.dispatchEvent(new Event(input instanceof HTMLSelectElement?'change':'input',{bubbles:true}));})()`);}
try {
 await command('Runtime.enable');await command('Page.enable');await command('Network.clearBrowserCookies');
 await command('Page.navigate',{url:`${origin}/login`});await waitFor(`document.querySelector('#email')`);
 await field('email','admin@assets.example.test');await field('password','LocalAssets-Only-2026!');
 await evaluate(`document.querySelector('form').requestSubmit()`);await waitFor(`document.querySelector('a[href="/assets"]')`);
 await click('IT Asset Management');await waitFor(`document.body.innerText.includes('Total Assets')`);
 await click('Asset Inventory');await waitFor(`document.body.innerText.includes('SYNTHETIC-LAP-001')`);
 await click('Add asset');await waitFor(`document.querySelector('[role="dialog"]')`);
 const serial=`BROWSER-SYNTHETIC-${Date.now()}`;
 for(const [key,value] of Object.entries({assetType:'LAPTOP',brand:'Browser Demo',model:'Synthetic model',serialNumber:serial,purchaseDate:'2025-01-01',condition:'GOOD'}))await field(key,value);
 await click('Confirm and save');await waitFor(`!document.querySelector('[role="dialog"]') && document.body.innerText.includes(${JSON.stringify(serial)})`);
 const evidence=await evaluate(`(async()=>{const assets=await(await fetch('/api/v1/assets?search=${serial}',{credentials:'include'})).json();return {total:assets.totalElements,serial:assets.content[0]?.serial_number,assetCode:assets.content[0]?.asset_code};})()`);
 assert.equal(evidence.total,1);assert.equal(evidence.serial,serial);assert.match(evidence.assetCode,/^ADESA-LAP-/);
 const pdf=await evaluate(`(async()=>{const r=await fetch('/api/v1/assets/reports/export?format=pdf',{credentials:'include'});return {status:r.status,type:r.headers.get('content-type'),size:(await r.blob()).size};})()`);assert.equal(pdf.status,200);assert.match(pdf.type,/application\/pdf/);assert.ok(pdf.size>100);
 await command('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});
 await new Promise(resolve=>setTimeout(resolve,300));assert.ok(await evaluate('document.documentElement.scrollWidth <= window.innerWidth + 1'),'Mobile page must not overflow horizontally');
 const screenshot=await command('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});await writeFile('/tmp/adesa-assets-mobile.png',Buffer.from(screenshot.data,'base64'));
 const csv=await evaluate(`(async()=>{const r=await fetch('/api/v1/assets/reports/export?format=csv',{credentials:'include'});return {status:r.status,type:r.headers.get('content-type'),text:await r.text()};})()`);assert.equal(csv.status,200);assert.match(csv.type,/text\/csv/);assert.ok(csv.text.includes(serial));
 assert.deepEqual(errors,[]);console.log(JSON.stringify({browser:'passed',registration:evidence,pdf,csv:{status:csv.status,type:csv.type},mobileScreenshot:'/tmp/adesa-assets-mobile.png',consoleErrors:errors}));
} finally {socket.close();}
