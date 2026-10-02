const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const source = fs.readFileSync(require.resolve('../../main/resources/static/js/theme.js'), 'utf8');
function setup({ savedTheme, blocked = false, systemDark = false, reduced = false } = {}) {
  const saved = { 'jlas-theme': savedTheme }, nodes = [], winHandlers = {}, mediaHandlers = {};
  function element(tag) {
    const attrs = {}, handlers = {}, classes = new Set();
    const e = { tag, attrs, children: [], handlers,
      classList: { add(v) { classes.add(v); }, remove(...vs) { vs.forEach(v=>classes.delete(v)); }, toggle(v,on) { if(on)classes.add(v);else classes.delete(v); } },
      setAttribute(k,v) { attrs[k]=String(v); }, getAttribute(k) { return attrs[k] || null; },
      appendChild(v) { this.children.push(v); }, addEventListener(k,f) { handlers[k]=f; },
      getBoundingClientRect() { return {left:0,top:0,width:48,height:280}; }
    }; nodes.push(e); return e;
  }
  const doc = { readyState:'complete', documentElement:element('html'), body:element('body'), createElement:element, createElementNS:(_,tag)=>element(tag) };
  const win = { document:doc, localStorage:{getItem:k=>{if(blocked)throw Error();return saved[k];},setItem:(k,v)=>{if(blocked)throw Error();saved[k]=v;}},
    matchMedia:q=>({matches:q.includes('reduced')?reduced:systemDark,addEventListener:(k,f)=>{mediaHandlers[q]=f;}}),
    addEventListener:(k,f)=>{winHandlers[k]=f;},removeEventListener:k=>{delete winHandlers[k];} };
  const context=vm.createContext({window:win,document:doc,requestAnimationFrame:()=>1}); vm.runInContext(source,context);
  const cord=doc.body.children[0], svg=cord.children[0];
  const fire=(target,type,data={})=>target.handlers?.[type]?.({type,isPrimary:true,button:0,pointerId:1,clientX:24,clientY:208,detail:1,preventDefault(){},...data});
  return {win,doc,cord,svg,saved,fire,theme:()=>doc.documentElement.getAttribute('data-theme'),
    pointer(type,data={}) { const e={type,isPrimary:true,button:0,pointerId:1,clientX:24,clientY:208,...data}; if(type==='pointerdown')svg.handlers[type](e);else winHandlers[type]?.(e); },
    storage(value) { saved['jlas-theme']=value;winHandlers.storage({key:'jlas-theme'}); } };
}

test('original SVG rope mounts once, resolves stored/system theme and does not save automatic preference',()=>{
 const c=setup({systemDark:true});assert.equal(c.theme(),'dark');assert.equal(c.saved['jlas-theme'],undefined);
 c.win.JlasTheme.init();assert.equal(c.doc.body.children.length,1);
 assert.deepEqual(c.svg.children.map(e=>e.tag),['polyline','circle']);
 assert.equal(setup({savedTheme:'light',systemDark:true}).theme(),'light');
});
test('handle click and Enter/Space toggle with blocked storage too',()=>{
 for(const blocked of [false,true]){const c=setup({blocked});c.pointer('pointerdown');c.pointer('pointerup');assert.equal(c.theme(),'dark');
 c.fire(c.cord,'keydown',{key:'Enter'});assert.equal(c.theme(),'light');c.fire(c.cord,'keydown',{key:' '});assert.equal(c.theme(),'dark');
 c.fire(c.cord,'keydown',{key:' ',repeat:true});assert.equal(c.theme(),'dark');}
});
test('stretch detent toggles once, release and browser click cannot toggle again',()=>{
 const c=setup();c.pointer('pointerdown');c.pointer('pointermove',{clientY:265});assert.equal(c.theme(),'dark');
 c.pointer('pointermove',{clientY:280});c.pointer('pointerup');c.fire(c.cord,'click');assert.equal(c.theme(),'dark');
 assert.equal(c.cord.getAttribute('aria-pressed'),'false');
});
test('cancel and unrelated pointers do not activate the cord',()=>{
 const c=setup();c.pointer('pointerdown');c.pointer('pointermove',{clientY:280,pointerId:2});c.pointer('pointercancel');assert.equal(c.theme(),'light');
 c.pointer('pointerdown',{button:2});c.pointer('pointerup');assert.equal(c.theme(),'light');
});
test('reduced motion remains clickable and cross-tab theme updates cord state',()=>{
 const c=setup({reduced:true});c.pointer('pointerdown');assert.equal(c.theme(),'light');c.pointer('pointerup');assert.equal(c.theme(),'dark');
 c.storage('light');assert.equal(c.theme(),'light');assert.equal(c.cord.getAttribute('aria-pressed'),'true');
 c.fire(c.cord,'click',{detail:0});assert.equal(c.theme(),'dark');
});
