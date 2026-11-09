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
let sequence=0;const pending=new Map();const errors=[];const networkFailures=[];const networkUrls=new Map();
socket.onmessage=event=>{const result=JSON.parse(event.data);if(result.id){const p=pending.get(result.id);if(p){pending.delete(result.id);result.error?p.reject(new Error(JSON.stringify(result.error))):p.resolve(result.result);}}if(result.method==='Network.requestWillBeSent')networkUrls.set(result.params.requestId,result.params.request.url);if(result.method==='Network.loadingFailed')networkFailures.push({url:networkUrls.get(result.params.requestId),reason:result.params.errorText,canceled:result.params.canceled});if(result.method==='Runtime.exceptionThrown')errors.push(result.params.exceptionDetails.text);};
async function command(method,params={}) {const id=++sequence;const result=new Promise((resolve,reject)=>pending.set(id,{resolve,reject}));socket.send(JSON.stringify({id,method,params}));return result;}
async function evaluate(expression) {const result=await command('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:true});if(result.exceptionDetails)throw new Error(JSON.stringify(result.exceptionDetails));return result.result?.value;}
async function waitFor(expression) {for(let i=0;i<100;i++){if(await evaluate(`Boolean(${expression})`))return;await new Promise(resolve=>setTimeout(resolve,200));}throw new Error(`Timed out: ${expression}`);}
async function click(label) {const found=await evaluate(`(()=>{const button=[...document.querySelectorAll('button,a')].find(b=>b.textContent.trim()===${JSON.stringify(label)});if(!button)return false;button.click();return true;})()`);assert.ok(found,`Control missing: ${label}`);}
async function field(name,value) {await evaluate(`(()=>{const input=document.querySelector('[name="${name}"]')??document.getElementById('${name}');if(!input)throw Error('Missing field ${name}');const proto=input instanceof HTMLSelectElement?HTMLSelectElement.prototype:input instanceof HTMLTextAreaElement?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;Object.getOwnPropertyDescriptor(proto,'value').set.call(input,${JSON.stringify(value)});input.dispatchEvent(new Event(input instanceof HTMLSelectElement?'change':'input',{bubbles:true}));})()`);}
try {
 await command('Runtime.enable');await command('Page.enable');await command('Network.enable');await command('Network.clearBrowserCookies');
 await command('Page.navigate',{url:`${origin}/login`});await waitFor(`document.querySelector('#email')`);
 await field('email','employee@assets.example.test');await field('password','LocalAssets-Only-2026!');await evaluate(`document.querySelector('form').requestSubmit()`);
 await waitFor(`document.querySelector('a[href="/assets"]')`);await click('IT Asset Management');
 await waitFor(`document.body.innerText.includes('Asset Requests') && [...document.querySelectorAll('button')].some(b=>b.textContent.trim()==='Request an asset')`);
 await click('Request an asset');await waitFor(`document.querySelector('[name="assetId"] option[value]:not([value=""])')`);
 const chosen=await evaluate(`document.querySelector('[name="assetId"] option[value]:not([value=""])').value`);
 const subsidiary=await evaluate(`document.querySelector('[name="subsidiaryId"] option[value]:not([value=""])').value`);
 const reason=`Synthetic browser employee request ${Date.now()}`;
 await field('assetId',chosen);await field('subsidiaryId',subsidiary);await field('reason',reason);await click('Confirm and save');
 await waitFor(`!document.querySelector('[role="dialog"]') && document.body.innerText.includes(${JSON.stringify(reason)})`);
 const evidence=await evaluate(`(async()=>{const requests=await(await fetch('/api/v1/assets/requests',{credentials:'include'})).json();const available=await(await fetch('/api/v1/assets/available?size=100',{credentials:'include'})).json();return {request:requests.content.find(r=>r.reason===${JSON.stringify(reason)}),availableIds:available.content.map(a=>a.id),canApprove:[...document.querySelectorAll('button')].some(b=>b.textContent.includes('Approve request'))};})()`);
 assert.equal(evidence.request.status,'PENDING');assert.equal(evidence.canApprove,false);assert.ok(!evidence.availableIds.includes(chosen));
 await command('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true});await new Promise(resolve=>setTimeout(resolve,300));
 assert.ok(await evaluate('document.documentElement.scrollWidth <= window.innerWidth + 1'));
 const screenshot=await command('Page.captureScreenshot',{format:'png',captureBeyondViewport:false});await writeFile('/tmp/adesa-employee-request-mobile.png',Buffer.from(screenshot.data,'base64'));
 assert.deepEqual(errors,[]);console.log(JSON.stringify({browser:'passed',employeeRequest:evidence.request.id,status:evidence.request.status,reserved:true,consoleErrors:errors,mobileScreenshot:'/tmp/adesa-employee-request-mobile.png'}));
} catch(error) {console.error(JSON.stringify({page:await evaluate('location.href'),networkFailures,error:String(error)}));throw error;} finally {socket.close();}
