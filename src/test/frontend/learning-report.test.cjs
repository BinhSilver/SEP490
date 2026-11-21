const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const source=fs.readFileSync(path.resolve(__dirname,'../../main/resources/static/js/components/learning-report.js'),'utf8');
const flush=()=>new Promise(resolve=>setImmediate(resolve));
function app(options={}){
  const dialogs=[],requests=[],toasts=[],timers=new Map();let timerId=0;
  function element(){return{children:[],listeners:{},attributes:{},disabled:false,hidden:false,value:'',dataset:{},isConnected:true,
    setAttribute(k,v){this.attributes[k]=v;},addEventListener(k,h){this.listeners[k]=h;},appendChild(node){this.children.push(node);node.parentElement=this;},focus(){this.focused=true;},remove(){this.isConnected=false;}};}
  function dialog(){
    const el=element(),form=element(),status=element(),note=element(),submit=element(),cancel=element();
    let html='',inputs=[];
    Object.defineProperty(el,'innerHTML',{get(){return html;},set(value){html=value;inputs=Array.from(value.matchAll(/name="learningReportReason" value="([A-Z]+)"/g),m=>Object.assign(element(),{value:m[1],checked:false}));}});
    form.querySelector=selector=>selector==='input:checked'?inputs.find(input=>input.checked):selector==='input'?inputs[0]:selector==='textarea'?note:null;
    form.querySelectorAll=()=>inputs.concat(note,submit,cancel);
    el.querySelector=selector=>selector==='form'?form:selector==='.lr-status'?status:selector==='textarea'?note:selector==='[type=submit]'?submit:null;
    el.showModal=()=>{el.open=true;};el.close=()=>{el.open=false;if(el.listeners.close)el.listeners.close();};
    el.form=form;el.status=status;el.note=note;el.submit=submit;el.cancelButton=cancel;
    el.pick=value=>{inputs.forEach(input=>input.checked=input.value===value);form.listeners.change();};
    el.send=()=>form.listeners.submit({preventDefault(){}});
    dialogs.push(el);return el;
  }
  const body=element(),doc=element();doc.body=body;doc.createElement=tag=>tag==='dialog'?dialog():element();
  const root={document:doc,location:{pathname:'/jlpt/exam',search:'?code=N3'},AbortController,
    setTimeout(fn){timers.set(++timerId,fn);return timerId;},clearTimeout(id){timers.delete(id);},
    JlasAuth:{ready:Promise.resolve(),isLoggedIn:()=>!options.guest,authHeaders:()=>({Authorization:'Bearer test-token'})},
    JlasUI:{toast(message,type){toasts.push({message,type});}},
    fetch(url,init){requests.push({url,init});return options.fetch?options.fetch(url,init):Promise.resolve({ok:true,json:async()=>({success:true,data:{reportId:1}})});}};
  vm.runInNewContext(source,{window:root});
  return{api:root.LearningReport,root,dialogs,requests,toasts,timers,opener:element()};
}
const question=()=>({targetType:'JLPT_QUESTION',page:'JLPT_EXAM',questionId:'12',label:'N3 · Câu 1'});

test('payload only contains allowed report fields and requires a description for other reasons',()=>{
  const a=app(),context=Object.assign(question(),{status:'RESOLVED',reporterUserId:99,sourceUrl:'https://bad'});
  const body=JSON.parse(JSON.stringify(a.api.buildPayload(context,'CONTENT','  mô tả  ')));
  assert.deepEqual(body,{targetType:'JLPT_QUESTION',page:'JLPT_EXAM',reason:'CONTENT',note:'mô tả',questionId:'12'});
  assert.throws(()=>a.api.buildPayload(context,'OTHER','  '),/mô tả/);
  assert.throws(()=>a.api.buildPayload(context,'COPYRIGHT','x'),/Chọn lý do/);
  assert.throws(()=>a.api.buildPayload(context,'CONTENT','x'.repeat(1001)),/1000/);
  assert.throws(()=>a.api.buildPayload({...context,page:'KANJI_MAP'},'CONTENT',''),/Không xác định/);
});
test('each supported page has a valid source identity and AI reports retain the displayed response',()=>{
  const a=app();
  const ai={targetType:'AI_EXPLANATION',page:'JLPT_PRACTICE',questionId:'12',explanation:'Giải thích <script>sample</script>'};
  assert.equal(a.api.buildPayload(ai,'AI','').explanation,ai.explanation);
  assert.throws(()=>a.api.buildPayload({...ai,explanation:''},'AI',''),/giải thích/);
  assert.throws(()=>a.api.buildPayload({...ai,explanation:'x'.repeat(20001)},'AI',''),/giải thích/);
  assert.equal(a.api.buildPayload({targetType:'KANJI_MAP',page:'KANJI_MAP',character:'木'},'CONTENT','').character,'木');
  assert.equal(a.api.buildPayload({targetType:'VERB_QUESTION',page:'VERB_PRACTICE',questionId:'q1',sessionId:'s1'},'ANSWER','').sessionId,'s1');
});
test('flag labels and identifiers cannot inject markup',()=>{
  const a=app(),html=a.api.buttonHtml('example','"><script>bad</script>','" onclick="bad');
  assert.doesNotMatch(html,/<script>/);assert.match(html,/&lt;script&gt;/);assert.match(html,/aria-label=/);
});
test('popup freezes its target and prevents duplicate sends while waiting',async()=>{
  let finish;const a=app({fetch:()=>new Promise(resolve=>{finish=resolve;})}),context=question();
  const dialog=a.api.open(context,a.opener);context.questionId='99';dialog.pick('ANSWER');
  const pending=dialog.send();dialog.send();await flush();
  assert.equal(a.requests.length,1);assert.equal(JSON.parse(a.requests[0].init.body).questionId,'12');
  assert.equal(dialog.submit.disabled,true);assert.equal(dialog.cancelButton.disabled,true);
  let cancelled=false;dialog.listeners.cancel({preventDefault(){cancelled=true;}});assert.equal(cancelled,true);
  assert.equal(a.requests[0].init.headers.Authorization,'Bearer test-token');
  finish({ok:true,json:async()=>({success:true,data:{reportId:91}})});await pending;
  assert.equal(dialog.open,false);assert.equal(a.toasts.length,1);assert.equal(a.opener.focused,true);
});
test('other reason without text stays open and makes no network request',async()=>{
  const a=app(),dialog=a.api.open(question(),a.opener);dialog.pick('OTHER');await dialog.send();
  assert.equal(dialog.note.required,true);assert.equal(dialog.note.focused,true);assert.equal(dialog.open,true);
  assert.equal(a.requests.length,0);assert.match(dialog.status.textContent,/mô tả/);
});
test('failed requests keep the description and allow retry without a success toast',async()=>{
  const a=app({fetch:async()=>{throw Error('Mất kết nối');}}),dialog=a.api.open(question(),a.opener);
  dialog.pick('OTHER');dialog.note.value='Mô tả đang viết';await dialog.send();
  assert.equal(dialog.open,true);assert.equal(dialog.note.value,'Mô tả đang viết');assert.equal(dialog.submit.disabled,false);
  assert.equal(a.toasts.length,0);assert.match(dialog.status.textContent,/Mất kết nối/);
});
test('a duplicate is shown in the dialog and cannot be resubmitted',async()=>{
  const a=app({fetch:async()=>({ok:false,status:409,json:async()=>({success:false,message:'Bạn đã báo cáo nội dung này.'})})}),dialog=a.api.open(question(),a.opener);
  dialog.pick('CONTENT');await dialog.send();assert.equal(dialog.open,true);assert.equal(dialog.submit.disabled,true);assert.equal(dialog.cancelButton.disabled,false);assert.equal(a.toasts.length,0);
});
test('guests see the login action without submitting data',async()=>{
  const a=app({guest:true}),dialog=a.api.open(question(),a.opener);dialog.pick('CONTENT');await dialog.send();
  assert.equal(a.requests.length,0);assert.match(dialog.status.textContent,/đăng nhập/);
  assert.equal(dialog.status.children[0].href,'/login?redirect='+encodeURIComponent('/jlpt/exam?code=N3'));
});
test('timeout aborts the request and offers a readable retry message',async()=>{
  const a=app({fetch:(url,init)=>new Promise((resolve,reject)=>init.signal.addEventListener('abort',()=>{var error=Error('abort');error.name='AbortError';reject(error);} ))});
  const pending=a.api.sendReport(a.api.buildPayload(question(),'CONTENT',''));await flush();
  a.timers.values().next().value();await assert.rejects(pending,/Kết nối quá lâu/);assert.equal(a.timers.size,0);
});
test('clicking a flag resolves the current source without displaying the exam label',()=>{
  const a=app();a.api.register('question',id=>({...question(),questionId:id,label:'<img src=x onerror=bad>'}));
  a.root.document.listeners.click({target:{closest(){return{dataset:{lrProvider:'question',lrId:'15'}};}}});
  assert.equal(a.dialogs.length,1);assert.doesNotMatch(a.dialogs[0].innerHTML,/lr-context|&lt;img|<img/);
});
test('expired sessions keep the report draft and offer a login link',async()=>{
  const a=app({fetch:async()=>({ok:false,status:401,json:async()=>({success:false,message:'Phiên đăng nhập đã hết hạn.'})})}),dialog=a.api.open(question(),a.opener);
  dialog.pick('OTHER');dialog.note.value='Nội dung đang báo';await dialog.send();
  assert.equal(dialog.open,true);assert.equal(dialog.note.value,'Nội dung đang báo');assert.equal(a.toasts.length,0);
  assert.match(dialog.status.children[0].href,/^\/login\?redirect=/);
});
test('a malformed successful response never shows a false saved message',async()=>{
  const a=app({fetch:async()=>({ok:true,json:async()=>({success:true,data:{}})})}),dialog=a.api.open(question(),a.opener);
  dialog.pick('CONTENT');await dialog.send();assert.equal(dialog.open,true);assert.equal(a.toasts.length,0);assert.match(dialog.status.textContent,/Chưa xác nhận/);
});
test('success feedback remains visible inside a fullscreen Kanji workspace',async()=>{
  const a=app(),workspace={children:[],appendChild(node){this.children.push(node);}},host={};
  a.root.document.fullscreenElement=workspace;a.root.JlasUI.toast=()=>({parentElement:host});
  const dialog=a.api.open({targetType:'KANJI_MAP',page:'KANJI_MAP',character:'木'},a.opener);
  dialog.pick('CONTENT');await dialog.send();assert.ok(workspace.children.includes(host));
});


test('typing in the report popup does not trigger shortcuts on the practice page',()=>{
  const a=app(),dialog=a.api.open(question(),a.opener);
  for(const type of ['keydown','keyup','keypress']){
    let stopped=false;
    dialog.listeners[type]({key:'1',stopPropagation(){stopped=true;}});
    assert.equal(stopped,true);
  }
});
