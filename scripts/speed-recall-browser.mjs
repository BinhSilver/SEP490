// Run with: node scripts/speed-recall-browser.mjs [absolute path to playwright package]
// Fixture API isolates UI states. Real persistence is covered by SpeedRecallMysqlTest.
import assert from 'node:assert/strict';
import http from 'node:http';
import { readFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';
const { chromium } = await import(process.argv[2] ? pathToFileURL(path.resolve(process.argv[2],'index.mjs')).href : 'playwright');
const root = fileURLToPath(new URL('../src/main/resources/static/', import.meta.url));
const artifacts = fileURLToPath(new URL('../target/speed-recall-browser/', import.meta.url));
await mkdir(artifacts,{recursive:true});
const server = http.createServer(async(req,res) => {
  try {
    const target=path.resolve(root,'.'+decodeURIComponent(new URL(req.url,'http://localhost').pathname));
    if(!target.startsWith(root)) throw new Error('outside static root');
    const data=await readFile(target),ext=path.extname(target);
    res.writeHead(200,{'Content-Type': {'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.css':'text/css; charset=utf-8'}[ext] || 'application/octet-stream'});res.end(data);
  } catch {res.writeHead(404);res.end();}
});
await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const base='http://127.0.0.1:'+server.address().port;
const browser=await chromium.launch({headless:true});
const context=await browser.newContext({viewport:{width:1365,height:950},reducedMotion:'reduce'});
const page=await context.newPage(), errors=[];
page.on('pageerror',error=>errors.push(error.message));
let view, requestCount=0, failAnswer=false, failedKey, successfulKey;
const stats=()=>({total:2,correct:0,wrong:0,timeout:0,score:0,streak:0,bestStreak:0,accuracy:0,averageResponseMs:0});
const init=()=>({id:100,setId:1,title:'Từ vựng N5 · Trường học và cuộc sống',state:'READY',pausedFrom:null,currentQuestionIndex:0,filter:'ALL',autoSubmit:false,serverNow:new Date().toISOString(),feedbackUntil:null,pausedRemainingMs:null,question:null,feedback:null,stats:stats(),results:[],personalBestScore:null});
view=init();
function question(index) { return {id:index+1,index,text:index===0?'学校':'約束',expectedAnswer:index===0?'がっこう':'やくそく',timeLimitMs:6000,deadlineAt:new Date(Date.now()+6000).toISOString()}; }
await page.route('**/api/flashcards/1/speed-recall/**',async route=>{
  const req=route.request(),url=new URL(req.url()),body=req.postDataJSON(),p=url.pathname;
  let data;
  if(p.endsWith('/options')) data={setId:1,title:view.title,totalCards:4,eligible:2,excluded:2,learning:2,starred:0};
  else if(p.endsWith('/history')) data={content:[{...view,startedAt:new Date().toISOString()}],page:0,totalPages:1,totalElements:1};
  else if(p.endsWith('/sessions')) { view=init();view.autoSubmit=body.autoSubmit;data=view; }
  else if(p.endsWith('/next')) {view.state='PLAYING';view.currentQuestionIndex=body.questionIndex;view.question=question(body.questionIndex);view.feedback=null;data=view;}
  else if(p.endsWith('/answers') || p.endsWith('/timeout')) {
    requestCount++;
    if(failAnswer){ failAnswer=false;failedKey=body.requestId; await route.abort('failed');return; }
    successfulKey=body.requestId;
    const correct=body.userAnswer===view.question.expectedAnswer;
    const a={questionId:view.question.id,cardId:view.question.id,question:view.question.text,reading:view.question.expectedAnswer,meaning:'Nghĩa của thẻ',userAnswer:body.userAnswer,result:correct?'CORRECT':'WRONG',responseMs:2000,scoreAwarded:correct?138:0};
    view.results.push(a);view.feedback=a;view.question=null;view.stats.correct+=correct?1:0;view.stats.wrong+=correct?0:1;view.stats.score+=a.scoreAwarded;
    view.stats.accuracy=view.stats.correct/view.results.length*100;view.stats.averageResponseMs=2000;view.stats.bestStreak=1;
    view.state=view.results.length===2?'FINISHED':'ANSWER_FEEDBACK';view.feedbackUntil=new Date(Date.now()+600).toISOString();
    if(view.state==='FINISHED')view.personalBestScore=view.stats.score;
    data=view;
  } else if(p.endsWith('/pause')) {view.pausedFrom=view.state;view.state='PAUSED';view.pausedRemainingMs=4000;data=view;}
  else if(p.endsWith('/resume')) {view.state=view.pausedFrom;view.pausedFrom=null;if(view.question)view.question.deadlineAt=new Date(Date.now()+4000).toISOString();data=view;}
  else if(p.endsWith('/retry-wrong')) {view=init();view.id=101;view.filter='RETRY';view.stats.total=1;data=view;}
  else if(p.endsWith('/abandon')) {view.state='ABANDONED';data=view;}
  else data=view;
  data.serverNow=new Date().toISOString();
  await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({success:true,data})});
});
try {
  await page.goto(base+'/flashcard/speed-recall.html?setId=1');
  await page.locator('#setup').waitFor({state:'visible'});
  await page.locator('#jlabBaseHeader').waitFor({state:'visible'});
  assert.equal(await page.locator('#jlabBaseBrand').getAttribute('href'),'/');
  assert.equal(await page.locator('#jlabBaseFlashcardLink').getAttribute('aria-current'),'page');
  assert.equal(await page.locator('#jlabBaseToolsButton').getAttribute('aria-current'),'page');
  assert.equal(await page.locator('header.sr-header').count(),0);
  await page.locator('#jlabBaseToolsButton').click();
  assert.equal(await page.locator('#jlabBaseToolsButton').getAttribute('aria-expanded'),'true');
  await page.keyboard.press('Escape');
  assert.equal(await page.locator('#jlabBaseToolsButton').getAttribute('aria-expanded'),'false');
  assert.match(await page.locator('#eligible-count').textContent(),/2 thẻ phù hợp/);
  assert.equal(await page.locator('#count').getAttribute('max'),'2');
  assert.equal(await page.locator('#count').inputValue(),'2');
  await page.locator('#filter').selectOption('STARRED');assert.equal(await page.locator('#start').isDisabled(),true);
  await page.locator('#filter').selectOption('ALL');
  await page.screenshot({path:path.join(artifacts,'setup-desktop.png'),fullPage:true});
  await page.locator('#start').click();
  await page.locator('#typing-input').waitFor({state:'visible'});
  await page.waitForFunction(()=>document.querySelector('#question-word').textContent==='学校');
  await page.locator('#typing-input').dispatchEvent('compositionstart');
  await page.locator('#typing-input').fill('がっこう');
  await page.locator('#typing-input').press('Enter');
  assert.equal(requestCount,0,'IME Enter must not answer');
  await page.locator('#typing-input').dispatchEvent('compositionend');
  await page.locator('#typing-input').fill('がつ');
  assert.equal(await page.locator('.sr-slot.is-wrong').count(),1);
  await page.locator('#typing-input').fill('がっこう');
  assert.equal(await page.locator('.sr-slot.is-wrong').count(),0);
  await page.locator('#pause').click();
  await page.locator('#pause-dialog').waitFor({state:'visible'});
  await page.locator('#resume').click();
  await page.locator('#pause-dialog').waitFor({state:'hidden'});
  assert.equal(await page.locator('#typing-input').inputValue(),'がっこう');
  await page.screenshot({path:path.join(artifacts,'playing-desktop.png'),fullPage:true});
  failAnswer=true;await page.locator('#submit-answer').click();
  await page.locator('#error-box').waitFor({state:'visible'});
  await page.locator('#retry-request').click();
  await page.waitForFunction(()=>document.querySelector('#question-word').textContent==='約束');
  assert.equal(failedKey,successfulKey,'retry must preserve request id');
  await page.locator('#typing-input').fill('ちがう');
  await page.locator('#submit-answer').click();
  await page.locator('#result').waitFor({state:'visible'});
  assert.equal(await page.locator('#result-accuracy').textContent(),'50%');
  assert.equal(await page.locator('#review-list li').count(),1);
  assert.match(page.url(),/speed-recall-result.html/);
  await page.reload();await page.locator('#result').waitFor({state:'visible'});
  await page.screenshot({path:path.join(artifacts,'result-desktop.png'),fullPage:true});
  await page.setViewportSize({width:390,height:844});
  await page.screenshot({path:path.join(artifacts,'result-mobile.png'),fullPage:true});
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true,'mobile must not overflow');
  await page.locator('#history-link').click();await page.locator('#history').waitFor({state:'visible'});
  assert.equal(await page.locator('#history-list li').count(),1);
  await page.locator('#history-list a').click();await page.locator('#result').waitFor({state:'visible'});
  await page.locator('#retry-wrong').click();
  await page.waitForFunction(()=>document.querySelector('#round').textContent==='Câu 1/1');
  assert.match(page.url(),/sessionId=101/);
  await page.screenshot({path:path.join(artifacts,'playing-mobile.png'),fullPage:true});
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
  await page.locator('#abandon').click();await page.locator('#result').waitFor({state:'visible'});
  assert.equal(await page.locator('#retry-wrong').isHidden(),true);
  await page.goto(base+'/flashcard/speed-recall.html?setId=1');
  await page.locator('#auto-submit').check();await page.locator('#start').click();
  await page.waitForFunction(()=>document.querySelector('#question-word').textContent==='学校');
  const before=requestCount;
  await page.locator('#typing-input').dispatchEvent('compositionstart');await page.locator('#typing-input').fill('がっこう');
  assert.equal(requestCount,before);
  await page.locator('#typing-input').dispatchEvent('compositionend');
  await page.waitForFunction(()=>document.querySelector('#question-word').textContent==='約束');
  assert.equal(requestCount,before+1);

  const studyHtml = await (await fetch(base + '/flashcard/study.html')).text();
  assert.match(studyHtml,/id="btn-speed-recall-nav"/,'study toolbar must expose Speed Recall');

  const quizPage = await context.newPage();
  quizPage.on('pageerror',error=>errors.push(error.message));
  const jwt = 'x.' + Buffer.from(JSON.stringify({exp:Math.floor(Date.now()/1000)+3600})).toString('base64url') + '.x';
  await quizPage.addInitScript(token=>localStorage.setItem('accessToken',token),jwt);
  const quizCards = Array.from({length:8},(_,i)=>({id:i+1,kanji:'語'+(i+1),hiragana:'ご',reading:'ご',meaning:'Nghĩa '+(i+1),memoryStatus:'NOT_REMEMBERED'}));
  await quizPage.route('**/api/**',async route=>{
    const pathname=new URL(route.request().url()).pathname;
    let data={};
    if(pathname==='/api/quiz/from-flashcard/1') data={vocabSetId:1,cardCount:8};
    else if(pathname==='/api/quiz/runtime-set/1') data={id:1,name:'Bộ tám thẻ',title:'Bộ tám thẻ',cards:quizCards};
    else if(pathname==='/api/auth/me') data={id:1,email:'test@example.invalid',fullName:'Test'};
    else if(pathname==='/api/notifications') data={items:[],unreadCount:0};
    await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({success:true,data})});
  });
  await quizPage.goto(base+'/flashcard/quiz.html?setId=1&sourceSetId=1');
  await quizPage.locator('#quiz-question-count').waitFor({state:'visible'});
  assert.match(await quizPage.locator('label[for="quiz-question-count"]').textContent(),/tối đa 8/);
  assert.deepEqual(await quizPage.locator('#quiz-question-count option').allTextContents(),['4 câu','8 câu','Toàn bộ 8 câu']);
  assert.match(await quizPage.locator('.quiz-dialog-hint').textContent(),/8 thẻ đã sẵn sàng/);
  await quizPage.locator('#quick-question-field').selectOption('KANJI');
  await quizPage.locator('#quick-answer-field').selectOption('HIRAGANA');
  assert.equal(await quizPage.locator('#start-quick-quiz').isEnabled(),true);
  await quizPage.close();
  assert.deepEqual(errors,[]);
  console.log('PASS: Speed Recall desktop/mobile and IME flows; study toolbar mode; quiz ApiResponse with 8-card maximum. Screenshots: '+artifacts);
} finally {await browser.close();await new Promise(resolve=>server.close(resolve));}
