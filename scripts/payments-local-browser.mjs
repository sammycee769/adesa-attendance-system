/** Local end-to-end check. Requires the dedicated local servers and isolated Chrome on port 9229. */
import assert from 'node:assert/strict';
import {mkdir,writeFile} from 'node:fs/promises';
const origin='http://localhost:5176';
const tab=await(await fetch('http://127.0.0.1:9229/json/new?about:blank',{method:'PUT'})).json();
const ws=new WebSocket(tab.webSocketDebuggerUrl);
await new Promise((resolve,reject)=>{ws.onopen=resolve;ws.onerror=reject;});
let sequence=0;const pending=new Map();const errors=[];
ws.onmessage=event=>{const response=JSON.parse(event.data);if(response.id){const item=pending.get(response.id);if(item){pending.delete(response.id);response.error?item.reject(Error(JSON.stringify(response.error))):item.resolve(response.result);}}if(response.method==='Runtime.exceptionThrown')errors.push(response.params.exceptionDetails.text);};
function command(method,params={},sessionId){const id=++sequence;const result=new Promise((resolve,reject)=>pending.set(id,{resolve,reject}));ws.send(JSON.stringify({id,method,params,sessionId}));return result;}
async function evaluate(expression,contextId,sessionId){const result=await command('Runtime.evaluate',{expression,contextId,awaitPromise:true,returnByValue:true},sessionId);if(result.exceptionDetails)throw Error(JSON.stringify(result.exceptionDetails));return result.result?.value;}
async function waitFor(expression){for(let i=0;i<100;i++){if(await evaluate(`Boolean(${expression})`))return;await new Promise(r=>setTimeout(r,150));}throw Error('Timed out: '+expression);}
async function click(text,index=0){assert.ok(await evaluate(`(()=>{const buttons=[...document.querySelectorAll('button,a')].filter(b=>b.textContent.trim()===${JSON.stringify(text)});if(!buttons[${index}])return false;buttons[${index}].click();return true;})()`),'Missing control: '+text);}
async function field(id,value){await evaluate(`(()=>{const input=document.getElementById(${JSON.stringify(id)});Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(input,${JSON.stringify(value)});input.dispatchEvent(new Event('input',{bubbles:true}));})()`);}
try {
 await command('Runtime.enable');await command('Page.enable');await command('Network.enable');await command('Network.clearBrowserCookies');
 await command('Page.navigate',{url:origin+'/login'});await waitFor('document.querySelector("#email")');
 await field('email','admin@payments.example.test');await field('password','LocalPayments-Only-2026!');
 await evaluate('document.querySelector("form").requestSubmit()');
 await waitFor('document.querySelector("a[href=\\"/recurring-payments\\"]")');
 await waitFor('location.pathname==="/" && document.body.textContent.includes("Synthetic Payments Admin")');
 await evaluate('new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)))');
 await click('Recurring Payments');await waitFor('document.querySelectorAll(".payment-card").length===3');
 assert.equal(await evaluate('document.querySelector(".payment-checkbox input").checked'),false);
 assert.ok(await evaluate('document.body.textContent.includes("Preview delivery only")'));
 await click('Save automation settings');await waitFor('document.body.textContent.includes("Automation settings saved.")');
 assert.equal(await evaluate('document.querySelector(".payment-checkbox input").checked'),false);
 await mkdir('/tmp/adesa-payment-previews',{recursive:true});
 for(let i=0;i<3;i++){
  await click('Preview email',i);await waitFor('document.querySelector(".payment-preview iframe")');
  const html=await evaluate('document.querySelector(".payment-preview iframe").srcdoc');
  assert.ok(html.includes('CHRISTIAN SAMUEL'));assert.ok(html.includes('IT OFFICER'));assert.ok(html.includes('The content of this email is confidential'));
  assert.equal((html.match(/CHRISTIAN SAMUEL/g)||[]).length,1);assert.ok(!html.includes('/zm/ImageSignature'));
  await writeFile(`/tmp/adesa-payment-previews/${['zoho','data','chatgpt'][i]}.html`,html);
  // Sandboxed srcdoc may be an out-of-process iframe; attach only to this isolated test browser.
  let target;
  for(let attempt=0;attempt<40;attempt++){
   target=(await command('Target.getTargets')).targetInfos.find(t=>t.type==='iframe'&&t.url==='about:srcdoc');
   if(target)break;await new Promise(r=>setTimeout(r,100));
  }
  assert.ok(target,'Sandboxed email iframe target exists');
  const attached=await command('Target.attachToTarget',{targetId:target.targetId,flatten:true});
  for(let attempt=0;attempt<40;attempt++){
   if(await evaluate('document.images.length===2 && [...document.images].every(i=>i.complete&&i.naturalWidth>0)',undefined,attached.sessionId))break;
   await new Promise(r=>setTimeout(r,100));
  }
  assert.equal(await evaluate('document.images.length===2 && [...document.images].every(i=>i.complete&&i.naturalWidth>0)',undefined,attached.sessionId),true,'Both original signature images load');
  const renderedText=await evaluate('document.body.textContent',undefined,attached.sessionId);
  await command('Target.detachFromTarget',{sessionId:attached.sessionId});
  if(i===2)assert.ok(renderedText.includes('28th')&&renderedText.includes('$20')&&renderedText.includes('₦30,000–₦33,000'));
  console.log('Verified email preview and original signature images:',i+1);
  await click('Close preview');
  await click('Record preview',i);await waitFor('document.body.textContent.includes("Preview request recorded;")');
 }
 for(const width of [320,390,768,1024,1440]){
  await command('Emulation.setDeviceMetricsOverride',{width,height:900,deviceScaleFactor:1,mobile:width<768});
  await evaluate('new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r)))');
  assert.ok(await evaluate('document.documentElement.scrollWidth<=innerWidth'),`Page overflow at ${width}`);
  if(width<768){await click('Menu');assert.ok(await evaluate('[...document.querySelectorAll(".app-nav a")].every(a=>a.getBoundingClientRect().height>=44)'));await click('Menu');}
  const image=await command('Page.captureScreenshot',{format:'png'});await writeFile(`/tmp/adesa-payments-${width}.png`,Buffer.from(image.data,'base64'));
  console.log('Verified local payment dashboard:',width+'px','no page overflow');
 }
 const state=await evaluate(`(async()=>{const r=await fetch('/api/v1/admin/recurring-payments');const d=await r.json();return {enabled:d.settings.enabled,transport:d.settings.transport,history:d.history.map(r=>r.status),attempts:d.attempts.length};})()`);
 assert.equal(state.enabled,false);assert.equal(state.transport,'mock');assert.ok(state.history.every(s=>s==='PREVIEW'));assert.equal(state.attempts,0);
 assert.deepEqual(errors,[]);console.log(JSON.stringify({result:'passed',state,consoleErrors:errors,previews:'/tmp/adesa-payment-previews'}));
}catch(error){console.error(JSON.stringify({error:String(error),consoleErrors:errors,page:await evaluate('document.body.innerText.slice(0,2000)')}));throw error;}finally{await command('Page.close');ws.close();}
