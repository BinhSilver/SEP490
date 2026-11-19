const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const base = path.resolve(__dirname, '../../main/resources');
const helper = require(path.join(base, 'static/js/pages/notebook-flashcards.js'));
const source = fs.readFileSync(path.join(base, 'static/js/pages/weakness-notebook.js'), 'utf8');
const template = fs.readFileSync(path.join(base, 'templates/learning/weakness-notebook.html'), 'utf8');
const flush = () => new Promise(resolve => setImmediate(resolve));
const words = [
  {vocabularyId:12,expression:'手紙',reading:'てがみ',meaning:'lá thư',exampleJa:'手紙を書く。',exampleVi:'Viết thư.',createdAt:'2026-11-19T08:00:00'},
  {vocabularyId:13,expression:'読む',reading:'よむ',meaning:'đọc',createdAt:'2026-11-19T07:00:00'}
];
const characters = [{character:'木',hanViet:'Mộc',meaningVi:'Cây',on:['モク','ボク'],kun:['き','モク'],savedAt:'2026-11-19T06:00:00'}];
const items = () => helper.notebookItems(words,characters);

test('both saved sources become the same card fields without changing the original notebook',()=>{
  const original=JSON.stringify({words,characters});
  const normalized=items();
  assert.equal(normalized.length,3);
  assert.equal(normalized[2].reading,'モク · ボク · き');
  const draft=helper.buildDraft({name:'Sổ của tôi'},normalized,['word:12','kanji:木']);
  assert.equal(draft.visibility,'PRIVATE');
  assert.deepEqual(draft.cards.map(c=>c.front),['手紙','木']);
  assert.deepEqual(draft.cards.map(c=>c.position),[0,1]);
  assert.equal(draft.cards[0].exampleVi,'Viết thư.');
  assert.equal(draft.cards[1].meaning,'Cây');
  assert.equal(draft.cards[1].back,'Cây');
  assert.equal(draft.cards[1].hanViet,'Mộc');
  assert.equal(draft.cards[1].reading,'モク · ボク · き');
  assert.equal(JSON.stringify({words,characters}),original);
  assert.equal(helper.editorUrl({id:71}),'/flashcard/editor.html?id=71');
});

test('distinct source keys prevent selection collisions and duplicate rows export once',()=>{
  const rows=items();
  assert.equal(helper.buildDraft({},rows.concat(rows[0]),['word:12','word:12']).cards.length,1);
  assert.throws(()=>helper.buildDraft({},rows,['word:木']),/không còn/);
  assert.throws(()=>helper.buildDraft({},rows,[]),/Chọn ít nhất/);
  const incomplete=helper.notebookItems([],[{character:'猫'}]);
  assert.equal(helper.buildDraft({},incomplete,['kanji:猫']).cards[0].meaning,'');
  const tooLong=helper.notebookItems([{vocabularyId:1,expression:'a'.repeat(501)}],[]);
  assert.throws(()=>helper.buildDraft({},tooLong,['word:1']),/quá dài/);
  for(const id of [null,0,-1,1.5,'https://invalid',Number.MAX_SAFE_INTEGER+1])assert.throws(()=>helper.editorUrl({id}),/Không nhận được/);
});

function page(options={}) {
  const nodes=new Map();
  function make(id){return{id,hidden:false,disabled:false,value:'',textContent:'',innerHTML:'',dataset:{},attributes:{},listeners:{},parentElement:{},
    setAttribute(k,v){this.attributes[k]=v;},addEventListener(k,h){this.listeners[k]=h;},querySelector(){return{textContent:''};},querySelectorAll(){return[];},focus(){},showModal(){},close(){}};}
  for(const match of template.matchAll(/\bid="([^"]+)"/g))nodes.set(match[1],make(match[1]));
  const node=id=>{assert.ok(nodes.has(id),'Missing DOM element: '+id);return nodes.get(id);};
  let currentWords=(options.words||words).slice(),currentCharacters=(options.characters||characters).slice(),finishExport;
  const calls=[],location={search:'?book=1',href:''};
  const response=(data,ok=true,message='')=>({ok,json:async()=>({success:ok,data,message})});
  const fetch=async(url,init={})=>{
    calls.push({url,init});
    if(url==='/api/dictionary/notebooks')return response([{id:1,name:'Sổ của tôi',type:'CUSTOM',wordCount:currentWords.length,kanjiCount:currentCharacters.length}]);
    if(init.method==='DELETE'){
      if(url.includes('/kanji/'))currentCharacters=currentCharacters.filter(c=>c.character!==decodeURIComponent(url.split('/').at(-1)));
      else currentWords=currentWords.filter(c=>String(c.vocabularyId)!==url.split('/').at(-1));
      return response(null);
    }
    if(url.endsWith('/entries'))return response(currentWords);
    if(url.endsWith('/kanji'))return response(currentCharacters);
    if(url==='/api/flashcards/drafts')return new Promise(resolve=>{finishExport=()=>resolve(options.fail?response(null,false,'Không lưu được bộ thẻ'):response({id:71}));});
    throw Error('Unexpected API: '+url);
  };
  vm.runInNewContext(source,{document:{getElementById:node,addEventListener(){}},window:{location,NotebookFlashcards:helper,JlasAuth:{authHeaders:()=>({Authorization:'Bearer test-token'})}},location,URLSearchParams,fetch});
  return{node,calls,location,resolveExport(){finishExport();},
    select(key,checked=true){node('notebookEntries').listeners.change({target:{dataset:{selectEntry:key},checked,matches:selector=>selector==='[data-select-entry]'}});},
    click(id){return node(id).listeners.click.call(node(id));},
    search(query){node('notebookQuery').value=query;node('notebookQuery').listeners.input();},
    remove(character){const button={disabled:false,dataset:{removeKanji:character},hasAttribute:name=>name==='data-remove-kanji'};return node('notebookEntries').listeners.click({target:{closest:selector=>selector==='[data-speak-entry]'?null:button}});},
    async ready(){await flush();await flush();}};
}

test('default reading mode shows all entries with no type badges, tabs, or checkboxes',async()=>{
  const p=page();await p.ready();
  const html=p.node('notebookEntries').innerHTML;
  for(const term of ['手紙','読む','木'])assert.ok(html.includes(term));
  assert.doesNotMatch(html,/notebook-entry-kind|data-entry-kind|data-select-entry=/);
  assert.equal(template.includes('notebookEntryFilters'),false);
  assert.equal(p.node('notebookTotal').textContent,'3 từ đã lưu');
  assert.match(p.node('notebookBooks').innerHTML,/3 từ/);
  assert.equal(p.node('notebookFlashcardToolbar').hidden,true);
  p.click('notebookChooseFlashcards');p.select('kanji:木');p.click('notebookCancelSelection');
  assert.equal(p.node('notebookFlashcardToolbar').hidden,true);
  assert.doesNotMatch(p.node('notebookEntries').innerHTML,/data-select-entry=/);
  assert.equal(p.calls.some(c=>c.init.method==='POST'),false);
});

test('all entries are selectable and one private draft contains both sources despite repeated clicks',async()=>{
  const p=page();await p.ready();p.click('notebookChooseFlashcards');
  assert.equal((p.node('notebookEntries').innerHTML.match(/data-select-entry=/g)||[]).length,3);
  p.select('word:12');p.select('kanji:木');
  const pending=p.click('notebookCreateFlashcards');p.click('notebookCreateFlashcards');
  assert.equal(p.node('notebookCreateFlashcards').disabled,true);
  const writes=p.calls.filter(c=>c.url==='/api/flashcards/drafts');
  assert.equal(writes.length,1);assert.equal(writes[0].init.method,'POST');
  assert.equal(writes[0].init.headers.Authorization,'Bearer test-token');
  const payload=JSON.parse(writes[0].init.body);
  assert.equal(payload.visibility,'PRIVATE');assert.deepEqual(payload.cards.map(c=>c.front),['手紙','木']);
  assert.equal(payload.cards[1].hanViet,'Mộc');
  p.resolveExport();await pending;
  assert.equal(p.location.href,'/flashcard/editor.html?id=71');
  assert.equal(p.calls.some(c=>/study|progress|rating/.test(c.url)),false);
});

test('searching by reading or Han Viet retains selection and select-all affects visible entries only',async()=>{
  const p=page();await p.ready();p.click('notebookChooseFlashcards');p.select('word:12');
  p.search('Mộc');assert.match(p.node('notebookEntries').innerHTML,/data-select-entry="kanji:木"/);
  assert.doesNotMatch(p.node('notebookEntries').innerHTML,/data-select-entry="word:12"/);
  p.click('notebookSelectWords');assert.equal(p.node('notebookSelectedCount').textContent,'2 từ được chọn');
  p.search('き');assert.match(p.node('notebookEntries').innerHTML,/data-select-entry="kanji:木"/);
  p.click('notebookSelectWords');assert.equal(p.node('notebookSelectedCount').textContent,'1 từ được chọn');
  p.search('');p.click('notebookSelectWords');assert.equal(p.node('notebookSelectedCount').textContent,'3 từ được chọn');
});

test('a notebook containing only map characters can create flashcards',async()=>{
  const p=page({words:[]});await p.ready();
  assert.equal(p.node('notebookChooseFlashcards').hidden,false);
  p.click('notebookChooseFlashcards');p.click('notebookSelectWords');
  assert.equal(p.node('notebookCreateFlashcards').disabled,false);
  const pending=p.click('notebookCreateFlashcards');
  assert.deepEqual(JSON.parse(p.calls.at(-1).init.body).cards.map(c=>c.front),['木']);
  p.resolveExport();await pending;
});

test('failed draft creation retains selections from both sources and permits retry',async()=>{
  const p=page({fail:true});await p.ready();p.click('notebookChooseFlashcards');p.select('word:13');p.select('kanji:木');
  const pending=p.click('notebookCreateFlashcards');p.resolveExport();await pending;
  assert.equal(p.location.href,'');assert.equal(p.node('notebookDetailNotice').textContent,'Không lưu được bộ thẻ');
  assert.equal(p.node('notebookCreateFlashcards').disabled,false);assert.equal(p.node('notebookSelectedCount').textContent,'2 từ được chọn');
});

test('removing a saved character uses its existing endpoint, keeps words, and clears its selection',async()=>{
  const p=page();await p.ready();p.click('notebookChooseFlashcards');p.select('word:12');p.select('kanji:木');
  await p.remove('木');
  assert.equal(p.calls.find(c=>c.init.method==='DELETE').url,'/api/dictionary/notebooks/1/kanji/'+encodeURIComponent('木'));
  assert.equal(p.node('notebookSelectedCount').textContent,'1 từ được chọn');
  assert.match(p.node('notebookEntries').innerHTML,/手紙/);
  assert.doesNotMatch(p.node('notebookEntries').innerHTML,/data-entry-key="kanji:木"/);
  assert.equal(p.node('notebookTotal').textContent,'2 từ đã lưu');
});
