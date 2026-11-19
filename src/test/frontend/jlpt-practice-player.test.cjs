const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const source=fs.readFileSync(path.resolve(__dirname,'../../main/resources/static/js/pages/jlpt-practice.js'),'utf8');
const flush=()=>new Promise(resolve=>setImmediate(resolve));
async function page(){
  const nodes=new Map(),scrolls=[];let selected='1';
  function node(id){
    if(id==='practiceAudioPlayer')return null;
    if(!nodes.has(id))nodes.set(id,{innerHTML:'',textContent:'',value:'',hidden:false,disabled:false,dataset:{},style:{},parentElement:{style:{setProperty(){}}},attributes:{},listeners:{},label:{textContent:''},querySelector(){return this.label;},querySelectorAll(){return[];},classList:{toggle(){},contains(){return false;}},setAttribute(k,v){this.attributes[k]=v;},addEventListener(k,h){this.listeners[k]=h;}});
    return nodes.get(id);
  }
  const types=[{code:'READ',section:'vocabulary',nameVi:'Cách đọc Kanji',questionCount:3}];
  const questions=[1,2,3].map(id=>({id,number:id,typeCode:'READ',typeName:'Cách đọc Kanji',section:'vocabulary',examCode:'N3_2024',text:'問題 '+id,correctOption:1,options:[{number:1,text:'正しい'},{number:2,text:'違う'}]}));
  const filters=['all','unanswered','marked'].map(type=>({dataset:{practiceFilter:type},attributes:{},setAttribute(k,v){this.attributes[k]=v;}}));
  node('practicePaletteFilters').querySelectorAll=()=>filters;
  vm.runInNewContext(source,{document:{getElementById:node,createElement(){return{innerHTML:'',content:{querySelectorAll(){return[];}}};},querySelector(s){if(s==='#practiceLimit input:checked')return{value:'20'};if(s==='input[name="practiceChoice"]:checked')return{value:selected};throw Error(s);}},window:{scrollTo(p){scrolls.push(p);}},fetch:async url=>({ok:true,json:async()=>({success:true,data:url.startsWith('/api/jlpt/practice/types')?types:questions})})});
  await flush();node('practiceStart').listeners.click();await flush();
  assert.equal(node('practiceRunner').hidden,false);
  scrolls.length=0;
  return{node,scrolls,filters,select(value){selected=String(value);},filter(type){node('practicePaletteFilters').listeners.click({target:{closest(){return{dataset:{practiceFilter:type}};}}});},jump(index){node('practicePalette').listeners.click({target:{closest(){return{dataset:{practiceIndex:String(index)}};}}});}};
}
test('palette, next and previous keep one current question and scroll once to the top',async()=>{
  const p=await page();
  assert.equal(p.node('practiceAnswered').textContent,'0 / 3');
  assert.equal((p.node('practicePalette').innerHTML.match(/aria-current="true"/g)||[]).length,1);
  p.node('practiceNext').listeners.click();assert.equal(p.node('practiceProgress').textContent,'Câu 2 / 3');
  p.node('practicePrevious').listeners.click();assert.equal(p.node('practiceProgress').textContent,'Câu 1 / 3');
  p.jump(2);assert.equal(p.node('practiceProgress').textContent,'Câu 3 / 3');
  assert.equal(p.scrolls.length,3);
  p.scrolls.forEach(s=>assert.deepEqual(JSON.parse(JSON.stringify(s)),{top:0,left:0,behavior:'instant'}));
});
test('checked answers and score survive palette navigation without counting twice',async()=>{
  const p=await page();p.node('practiceCheck').listeners.click();
  assert.equal(p.node('practiceAnswered').textContent,'1 / 3');
  p.jump(1);p.jump(0);
  assert.match(p.node('practiceChoices').innerHTML,/value="1" checked\s+disabled/);
  assert.equal(p.node('practiceCheck').hidden,true);
  p.node('practiceCheck').listeners.click();
  p.jump(2);p.node('practiceNext').listeners.click();
  assert.equal(p.node('practiceScore').textContent,'1 / 3');
});

test('all, unanswered and marked tabs retain their state without changing the current question',async()=>{
  const p=await page();p.node('practiceCheck').listeners.click();
  p.node('practiceMark').listeners.click();
  assert.equal(p.node('practiceMark').attributes['aria-pressed'],'true');
  p.filter('unanswered');
  assert.doesNotMatch(p.node('practicePalette').innerHTML,/data-practice-index="0"/);
  assert.match(p.node('practicePalette').innerHTML,/data-practice-index="1"/);
  assert.equal(p.node('practiceProgress').textContent,'Câu 1 / 3');
  p.filter('marked');
  assert.match(p.node('practicePalette').innerHTML,/data-practice-index="0"/);
  assert.doesNotMatch(p.node('practicePalette').innerHTML,/data-practice-index="1"/);
  assert.match(p.node('practicePalette').innerHTML,/is-marked/);
  assert.equal(p.filters.filter(f=>f.attributes['aria-pressed']==='true').length,1);
  p.node('practiceMark').listeners.click();
  assert.match(p.node('practicePalette').innerHTML,/Chưa có câu đánh dấu/);
  p.filter('all');assert.equal((p.node('practicePalette').innerHTML.match(/data-practice-index=/g)||[]).length,3);
  assert.equal(p.scrolls.length,0,'marking and filtering do not move the page');
});

test('wrong answers highlight the correct option without the redundant answer text',async()=>{
  const p=await page();p.select(2);p.node('practiceCheck').listeners.click();
  assert.equal(p.node('practiceFeedback').textContent,'');
  assert.match(p.node('practiceChoices').innerHTML,/is-correct/);
  assert.match(p.node('practiceChoices').innerHTML,/is-wrong/);
});
