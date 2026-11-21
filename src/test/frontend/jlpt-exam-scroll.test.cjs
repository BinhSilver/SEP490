const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/pages/jlpt-exam.js'), 'utf8');
// Test the real binding and rendering functions, with content renderers isolated from scrolling.
const bindings = source.slice(source.indexOf('  function bind() {'), source.indexOf('  function questionById('));
const renderer = source.slice(source.indexOf('  function render() {'), source.indexOf('  function passageHtml('));

function page(index) {
  const nodes = new Map(), scrolls = [], animations = [];
  function node(id) {
    if (!nodes.has(id)) nodes.set(id, { listeners: {}, addEventListener(name, handler) { this.listeners[name] = handler; } });
    return nodes.get(id);
  }
  const pages = [1, 2, 3].map(id => ({ questions: [{ id }], mondai: { number: id, instructionHtml: '' } }));
  const context = vm.createContext({
    $: node, pageIndex: index, partIndex: 0, pendingFocus: null, scriptOpen: false, furiganaOn: false,
    isExamMode: false, pages: () => pages, currentPart: () => ({}), partShortName: () => 'GOI',
    questionRows: () => [1, 2, 3], questionPosition: id => id - 1, questionTypeLabel: () => '',
    pageIndexForQuestion: id => Number(id) - 1, safeHtml: value => value,
    savePracticeSession() {}, renderTabs() {}, renderPassage() {}, renderAudio() {},
    renderQuestionGroup() {}, renderPalette() {}, applyFurigana() {},
    handleAudioClick() {}, handleReviewAction() {}, applyReviewFilters() {},
    window: { scrollTo(value) { scrolls.push({ target: 'window', ...value }); } },
    requestAnimationFrame(callback) { animations.push(callback); callback(); },
    document: { querySelector() { return { scrollIntoView(value) { scrolls.push({ target: 'card', ...value }); } }; } }
  });
  vm.runInContext(bindings + '\n' + renderer + '\nbind();', context);
  return { context, node, scrolls, animations };
}

function assertSingleTop(p) {
  assert.equal(p.scrolls.length, 1);
  assert.deepEqual(p.scrolls[0], { target: 'window', top: 0, left: 0, behavior: 'instant' });
  assert.equal(p.animations.length, 0);
}

test('next question scrolls to the top once without smooth animation', () => {
  const p = page(0); p.node('jlptNext').onclick();
  assert.equal(p.context.pageIndex, 1); assertSingleTop(p);
});
test('previous question uses the same scroll target', () => {
  const p = page(2); p.node('jlptPrevious').onclick();
  assert.equal(p.context.pageIndex, 1); assertSingleTop(p);
});
test('question palette no longer scrolls up and then down into the card', () => {
  const p = page(0);
  p.node('jlptPalette').listeners.click({ target: { closest() { return { dataset: { pageIndex: '2', questionId: '3' } }; } } });
  assert.equal(p.context.pageIndex, 2); assertSingleTop(p);
});
test('changing the exam part uses the same single top scroll', () => {
  const p = page(2);
  p.node('jlptPartTabs').listeners.click({ target: { closest() { return { dataset: { part: '1' } }; } } });
  assert.equal(p.context.partIndex, 1);
  assert.equal(p.context.pageIndex, 0); assertSingleTop(p);
});
