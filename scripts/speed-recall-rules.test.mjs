import test from 'node:test';
import assert from 'node:assert/strict';
import { normalizeAnswer, isKanaAnswer, compareCharacters, calculateScore, getQuestionTime } from '../src/main/resources/static/flashcard/js/speed-recall/rules.js';
import { RecallEngine } from '../src/main/resources/static/flashcard/js/speed-recall/engine.js';
import { createTimer } from '../src/main/resources/static/flashcard/js/speed-recall/timer.js';

test('normalization, code points and editable wrong characters', () => {
  assert.equal(normalizeAnswer('　ｶﾞｯｺｳ　'), 'ガッコウ');
  assert.equal(compareCharacters('𠮷', '𠮷').length, 1);
  assert.deepEqual(compareCharacters('がつ', 'がっこう').map(s => s.status), ['correct','wrong','active','empty']);
  assert.equal(compareCharacters('がっこうう', 'がっこう').at(-1).status, 'wrong');
  assert.equal(compareCharacters('がっこう', 'がっこう').every(s => s.status === 'correct'), true);
  assert.equal(isKanaAnswer('しゅるい'), true);
  assert.equal(isKanaAnswer('alphabet'), false);
  assert.equal(isKanaAnswer('tiếng Việt'), false);
});
test('IME and request locks prevent accidental or duplicate submit', () => {
  const e = new RecallEngine();
  e.setView({ state:'PLAYING', autoSubmit:true, question:{ id:1, expectedAnswer:'がっこう' } });
  e.input = 'がっこう'; e.composing = true;
  assert.equal(e.canSubmit(),false); assert.equal(e.shouldAutoSubmit(),false);
  assert.equal(e.slots().every(slot => slot.status === 'correct'), true);
  e.input = 'abcdef'; assert.equal(e.slots().length, 4);
  e.input = e.view.question.expectedAnswer;
  e.composing = false; assert.equal(e.shouldAutoSubmit(),true);
  e.busy = true; assert.equal(e.canSubmit(),false);
  e.busy = false;
  e.setView({ state:'PAUSED', question:{ id:1, expectedAnswer:'がっこう' } });
  assert.equal(e.input,'がっこう'); assert.equal(e.canSubmit(),false);
  e.setView({ state:'PLAYING', question:{ id:2, expectedAnswer:'やくそく' } });
  assert.equal(e.input,'');
});
test('server scoring and timer contract boundaries', () => {
  assert.deepEqual([0,4,5,9,10,19,20].map(getQuestionTime),[6000,6000,5000,5000,4000,4000,3000]);
  assert.equal(calculateScore({ responseTime:3000,maxTime:6000,streak:0 }),130);
  assert.equal(calculateScore({ responseTime:0,maxTime:3000,streak:25 }),250);
});
test('timer ends once and cancelled or replaced callbacks cannot fire', () => {
  let now=0, callback, ended=0, ticked;
  const timer=createTimer({ now:()=>now,frame:fn=>{ callback=fn; return 1; },cancel:()=>{} });
  timer.start(100,left=>{ ticked=left; },()=>ended++);
  callback(); assert.equal(ticked,100);
  const old=callback; timer.stop(); now=150; old(); assert.equal(ended,0);
  timer.start(50,()=>{},()=>ended++); const replaced=callback;
  timer.start(200,()=>{},()=>ended++); now=200; replaced(); assert.equal(ended,0);
  now=350; callback(); assert.equal(ended,1);
});
