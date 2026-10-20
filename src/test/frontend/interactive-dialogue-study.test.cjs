const test = require('node:test');
const assert = require('node:assert/strict');
const page = require('../../main/resources/static/js/pages/interactive-dialogue-study.js');

for (const mode of ['REVIEW', 'PRACTICE']) {
  test(`mounted ${mode} never sends engagement writes after visibility changes`, async () => {
    const vm = require('node:vm');
    const fs = require('node:fs');
    const events = {}, requests = [];
    const node = { innerHTML: '', setAttribute() {}, addEventListener() {}, querySelector() { return null; } };
    const document = { readyState: 'loading', visibilityState: 'visible',
      getElementById: () => node, addEventListener: (name, handler) => { events[name] = handler; } };
    const window = { document, location: { search: '?activity=1&mode=' + mode.toLowerCase() },
      JlasAuth: { requireLogin: () => true, authHeaders: () => ({}) },
      InteractiveStudyMode: { resolveMode: () => mode, banner: () => '' } };
    const context = vm.createContext({ window, document, URLSearchParams, setInterval() { throw Error('Replay timer'); },
      clearInterval() {}, fetch: async (url, options) => { requests.push({ url, method: options.method || 'GET' });
        return { ok: true, json: async () => ({ data: study({ completed: true }) }) }; } });
    vm.runInContext(fs.readFileSync(require.resolve('../../main/resources/static/js/pages/interactive-dialogue-study.js'), 'utf8'), context);
    events.DOMContentLoaded();
    await new Promise(setImmediate);
    document.visibilityState = 'hidden'; events.visibilitychange();
    document.visibilityState = 'visible'; events.visibilitychange();
    await new Promise(setImmediate);
    assert.deepEqual(requests.map(r => r.method), ['GET']);
    assert.match(requests[0].url, new RegExp('/' + mode.toLowerCase() + '$'));
    assert.doesNotMatch(node.innerHTML, /dialogueBottomSentinel/);
  });
}

function line(overrides = {}) {
  return Object.assign({
    lineId: 201, orderIndex: 1, roleCode: 'STAFF', roleName: 'Nhân viên', sceneCode: 'scene-1',
    bubbleSide: 'trai', japanese: 'いらっしゃいませ。', meaningVi: 'Xin chào quý khách.', audioUrl: null
  }, overrides);
}

function study(overrides = {}) {
  return Object.assign({
    activityId: 1, activityType: 'DIALOGUE', activityState: 'STUDYING', sourceRevision: 'rev-1',
    requiredSeconds: 180, accumulatedSeconds: 0, remainingSeconds: 180, reachedBottom: false,
    completed: false, lines: [line()]
  }, overrides);
}

test('sorts lines by order index', () => {
  const model = page.viewModel(study({ lines: [line({ lineId: 2, orderIndex: 2 }), line({ lineId: 1, orderIndex: 1 })] }));
  assert.equal(model.lines[0].lineId, 1);
  assert.equal(model.lines[1].lineId, 2);
});

test('computes percent complete from server-reported accumulated/required seconds only', () => {
  const model = page.viewModel(study({ accumulatedSeconds: 90, requiredSeconds: 180 }));
  assert.equal(model.percent, 50);
});

test('percent never exceeds 100 even if accumulated seconds is reported past the requirement', () => {
  const model = page.viewModel(study({ accumulatedSeconds: 400, requiredSeconds: 180 }));
  assert.equal(model.percent, 100);
});

test('formats remaining time as a short friendly duration', () => {
  assert.equal(page.formatRemaining(42), '42 giây');
  assert.equal(page.formatRemaining(102), '1 phút 42 giây');
  assert.equal(page.formatRemaining(120), '2 phút');
  assert.equal(page.formatRemaining(-5), '0 giây');
});

test('renders a line with role, Japanese text and meaning, without fabricating audio', () => {
  const html = page.renderLine(line());
  assert.match(html, /Nhân viên/);
  assert.match(html, /いらっしゃいませ。/);
  assert.match(html, /Xin chào quý khách\./);
  assert.doesNotMatch(html, /data-play-audio/);
});

test('renders an audio button only when an approved audio url exists', () => {
  const html = page.renderLine(line({ audioUrl: '/audio/line-201.mp3' }));
  assert.match(html, /data-play-audio="\/audio\/line-201\.mp3"/);
});

test('labels the J-LAS supplement voice and renders Japanese text for browser speech synthesis', () => {
  const html = page.renderScript(page.viewModel(study({ lines: [line({ audioUrl: 'tts:ja-JP' })] })));
  assert.match(html, /Nội dung bổ sung J-LAS · Giọng đọc tiếng Nhật tổng hợp/);
  assert.match(html, /data-play-audio="tts:ja-JP" data-speak-text="いらっしゃいませ。"/);
  assert.match(html, /Đọc bằng giọng tổng hợp/);
});

test('plays the explicit synthetic voice through the browser speech API with Japanese locale', async () => {
  const vm = require('node:vm');
  const fs = require('node:fs');
  const handlers = {}, spoken = [], status = { textContent: '' };
  const node = { innerHTML: '', setAttribute() {}, addEventListener: (name, handler) => { handlers[name] = handler; },
    querySelector: selector => selector === '[data-audio-status]' ? status : null };
  const document = { readyState: 'loading', visibilityState: 'hidden', getElementById: () => node,
    addEventListener: (name, handler) => { handlers['document:' + name] = handler; } };
  function SpeechSynthesisUtterance(text) { this.text = text; }
  const window = { document, location: { search: '?activity=1' }, JlasAuth: { requireLogin: () => true, authHeaders: () => ({}) },
    InteractiveStudyMode: { resolveMode: () => 'LEARN' }, SpeechSynthesisUtterance,
    speechSynthesis: { getVoices: () => [{ lang: 'ja-JP', name: 'Japanese' }], cancel() {}, speak: utterance => spoken.push(utterance) } };
  const context = vm.createContext({ window, document, URLSearchParams, setInterval() { return 1; }, clearInterval() {},
    fetch: async () => ({ ok: true, json: async () => ({ data: study({ lines: [line({ audioUrl: 'tts:ja-JP' })] }) }) }) });
  vm.runInContext(fs.readFileSync(require.resolve('../../main/resources/static/js/pages/interactive-dialogue-study.js'), 'utf8'), context);
  handlers['document:DOMContentLoaded']();
  await new Promise(setImmediate);
  const button = { getAttribute: name => ({ 'data-play-audio': 'tts:ja-JP', 'data-speak-text': 'いらっしゃいませ。' })[name] || null };
  handlers.click({ target: { closest: selector => selector === '[data-retry]' ? null : selector === '[data-play-audio]' ? button : null } });
  assert.equal(spoken.length, 1);
  assert.equal(spoken[0].text, 'いらっしゃいませ。');
  assert.equal(spoken[0].lang, 'ja-JP');
  assert.equal(spoken[0].voice.lang, 'ja-JP');
  assert.match(status.textContent, / giọng tổng hợp tiếng Nhật/);
});

test('waits for asynchronously loaded Japanese speech voices after Windows installs the voice pack', async () => {
  const vm = require('node:vm');
  const fs = require('node:fs');
  const handlers = {}, spoken = [], status = { textContent: '' }, voiceEvents = {};
  const voices = [];
  const synth = { getVoices: () => voices, cancel() {}, speak: utterance => spoken.push(utterance),
    addEventListener: (name, handler) => { voiceEvents[name] = handler; },
    removeEventListener: name => { delete voiceEvents[name]; } };
  const node = { innerHTML: '', setAttribute() {}, addEventListener: (name, handler) => { handlers[name] = handler; },
    querySelector: selector => selector === '[data-audio-status]' ? status : null };
  const document = { readyState: 'loading', visibilityState: 'hidden', getElementById: () => node,
    addEventListener: (name, handler) => { handlers['document:' + name] = handler; } };
  function SpeechSynthesisUtterance(text) { this.text = text; }
  const window = { document, location: { search: '?activity=1' }, JlasAuth: { requireLogin: () => true, authHeaders: () => ({}) },
    InteractiveStudyMode: { resolveMode: () => 'LEARN' }, SpeechSynthesisUtterance, speechSynthesis: synth,
    setTimeout: callback => { window.pendingVoiceTimeout = callback; return 1; }, clearTimeout() {} };
  const context = vm.createContext({ window, document, URLSearchParams, setInterval() { return 1; }, clearInterval() {},
    fetch: async () => ({ ok: true, json: async () => ({ data: study({ lines: [line({ audioUrl: 'tts:ja-JP' })] }) }) }) });
  vm.runInContext(fs.readFileSync(require.resolve('../../main/resources/static/js/pages/interactive-dialogue-study.js'), 'utf8'), context);
  handlers['document:DOMContentLoaded']();
  await new Promise(setImmediate);
  const button = { getAttribute: name => ({ 'data-play-audio': 'tts:ja-JP', 'data-speak-text': 'いらっしゃいませ。' })[name] || null };
  handlers.click({ target: { closest: selector => selector === '[data-retry]' ? null : selector === '[data-play-audio]' ? button : null } });
  assert.match(status.textContent, /đang tìm/i);
  voices.push({ lang: 'ja-JP', name: 'Microsoft Haruka' });
  voiceEvents.voiceschanged();
  assert.equal(spoken.length, 1);
  assert.equal(spoken[0].voice.name, 'Microsoft Haruka');
  assert.equal(spoken[0].lang, 'ja-JP');
});

test('does not pronounce Japanese text with an unrelated default voice', async () => {
  const vm = require('node:vm');
  const fs = require('node:fs');
  const handlers = {}, spoken = [], status = { textContent: '' };
  const node = { innerHTML: '', setAttribute() {}, addEventListener: (name, handler) => { handlers[name] = handler; },
    querySelector: selector => selector === '[data-audio-status]' ? status : null };
  const document = { readyState: 'loading', visibilityState: 'hidden', getElementById: () => node,
    addEventListener: (name, handler) => { handlers['document:' + name] = handler; } };
  function SpeechSynthesisUtterance(text) { this.text = text; }
  const window = { document, location: { search: '?activity=1' }, JlasAuth: { requireLogin: () => true, authHeaders: () => ({}) },
    InteractiveStudyMode: { resolveMode: () => 'LEARN' }, SpeechSynthesisUtterance,
    speechSynthesis: { getVoices: () => [{ lang: 'en-US' }], cancel() {}, speak: utterance => spoken.push(utterance) } };
  const context = vm.createContext({ window, document, URLSearchParams, setInterval() { return 1; }, clearInterval() {},
    fetch: async () => ({ ok: true, json: async () => ({ data: study({ lines: [line({ audioUrl: 'tts:ja-JP' })] }) }) }) });
  vm.runInContext(fs.readFileSync(require.resolve('../../main/resources/static/js/pages/interactive-dialogue-study.js'), 'utf8'), context);
  handlers['document:DOMContentLoaded']();
  await new Promise(setImmediate);
  const button = { getAttribute: name => ({ 'data-play-audio': 'tts:ja-JP', 'data-speak-text': 'いらっしゃいませ。' })[name] || null };
  handlers.click({ target: { closest: selector => selector === '[data-retry]' ? null : selector === '[data-play-audio]' ? button : null } });
  assert.equal(spoken.length, 0);
  assert.match(status.textContent, /chưa có giọng tiếng Nhật/);
});

test('bubble side maps to a left/right class without inventing a side when data is missing', () => {
  const right = page.renderLine(line({ bubbleSide: 'phai' }));
  const left = page.renderLine(line({ bubbleSide: 'trai' }));
  assert.match(right, /is-right/);
  assert.match(left, /is-left/);
});

test('script includes a bottom sentinel element for scroll-to-bottom detection', () => {
  const html = page.renderScript(page.viewModel(study()));
  assert.match(html, /id="dialogueBottomSentinel"/);
});

test('empty-safe rendering when there is no dialogue content at all', () => {
  const html = page.renderScript(page.viewModel({ lines: [] }));
  assert.match(html, /Chưa có kịch bản hội thoại/);
});

test('progress panel shows the friendly remaining-time nudge derived from server data, never invented', () => {
  const html = page.renderProgress(page.viewModel(study({ accumulatedSeconds: 60, remainingSeconds: 120 })));
  assert.match(html, /Hãy nán lại thêm 2 phút để luyện hội thoại nhé\./);
});

test('progress panel prompts to scroll when time is satisfied but bottom is not reached', () => {
  const html = page.renderProgress(page.viewModel(study({ accumulatedSeconds: 180, remainingSeconds: 0, reachedBottom: false })));
  assert.match(html, /Hãy cuộn xuống hết đoạn hội thoại/);
});

test('progress panel marks both evidence items done only when both are true', () => {
  const html = page.renderProgress(page.viewModel(study({
    accumulatedSeconds: 180, remainingSeconds: 0, reachedBottom: true, completed: true
  })));
  const doneCount = (html.match(/is-done/g) || []).length;
  assert.equal(doneCount, 2);
  assert.match(html, /Bạn đã hoàn thành phần hội thoại này\./);
});

test('renders a completion state with a return-to-roadmap action', () => {
  const html = page.renderCompleted(17);
  assert.match(html, /Phần Hội thoại đã đạt/);
  assert.match(html, /href="\/learning\/path\/v2"/);
  assert.match(html, /\/learning\/v2\/dialogue\?activity=17&amp;mode=review/);
});

test('error state renders a retry affordance', () => {
  const html = page.error('mất kết nối');
  assert.match(html, /mất kết nối/);
  assert.match(html, /data-retry/);
});

test('review/practice banner and script have no tracking/progress controls', () => {
  const html = page.renderReplay('REVIEW', page.viewModel(study({ completed: true })));
  assert.match(html, /Xem lại hội thoại/);
  assert.match(html, /いらっしゃいませ/);
  assert.doesNotMatch(html, /dialogueBottomSentinel|role="progressbar"|data-retry/);
});

test('a reached-bottom report that raced ahead of the first heartbeat is retried while the end stays in view', async () => {
  // A short dialogue fits the viewport: the sentinel intersects once at load, before the first heartbeat row
  // exists, so the server rejects that report. The observer never fires again, so the next heartbeat must retry.
  const vm = require('node:vm');
  const fs = require('node:fs');
  const handlers = {}, calls = [];
  let observerCallback = null, intervalFn = null, heartbeatStored = false;
  const node = { innerHTML: '', setAttribute() {}, addEventListener: (name, handler) => { handlers[name] = handler; },
    querySelector: () => null };
  const document = { readyState: 'loading', visibilityState: 'visible', getElementById: () => node,
    addEventListener: (name, handler) => { handlers['document:' + name] = handler; } };
  function IntersectionObserver(callback) { observerCallback = callback; this.observe = () => {}; this.disconnect = () => {}; }
  const window = { document, location: { search: '?activity=7' }, JlasAuth: { requireLogin: () => true, authHeaders: () => ({}) },
    InteractiveStudyMode: { resolveMode: () => 'LEARN' } };
  const respond = (ok, data) => ({ ok, json: async () => (ok ? { data } : { message: 'Chưa có bằng chứng hoạt động' }) });
  const fetch = async (url) => {
    calls.push(url.replace('/api/learning/v2/activities/7/dialogue', ''));
    if (url.endsWith('/study')) return respond(true, study({ lines: [line()] }));
    if (url.endsWith('/reached-bottom')) return respond(heartbeatStored, { accumulatedSeconds: 15, requiredSeconds: 180, reachedBottom: true });
    if (url.endsWith('/heartbeat')) { heartbeatStored = true; return respond(true, { accumulatedSeconds: 15, requiredSeconds: 180 }); }
    return respond(false);
  };
  const context = vm.createContext({ window, document, URLSearchParams, IntersectionObserver, fetch,
    setInterval(fn) { intervalFn = fn; return 1; }, clearInterval() {} });
  vm.runInContext(fs.readFileSync(require.resolve('../../main/resources/static/js/pages/interactive-dialogue-study.js'), 'utf8'), context);
  handlers['document:DOMContentLoaded']();
  await new Promise(setImmediate);
  heartbeatStored = false; // the initial heartbeat has not been stored yet when the sentinel first intersects
  observerCallback([{ isIntersecting: true }]);
  await new Promise(setImmediate);
  heartbeatStored = true;
  await intervalFn();
  await new Promise(setImmediate);
  const bottomCalls = calls.filter(path => path === '/reached-bottom').length;
  assert.equal(bottomCalls, 2, 'the rejected reached-bottom report must be retried after a stored heartbeat: ' + calls.join(','));
});

// ---- Owner 2026-10-19: communication dialogues are practised turn by turn, never timed ----

function commStudy(overrides = {}) {
  return study(Object.assign({ requiredSeconds: 0, remainingSeconds: 0, lineByLinePractice: true,
    practicedLineIds: [], lines: [line(), line({ lineId: 202, orderIndex: 2, roleCode: 'GUEST', roleName: 'Khách',
      bubbleSide: 'phai', japanese: 'チェックインをお願いします。', meaningVi: 'Cho tôi nhận phòng.', audioUrl: 'tts:ja-JP' })] }, overrides));
}

test('communication progress counts practised lines and the end, with no timer or seconds wording', () => {
  const v = page.viewModel(commStudy({ practicedLineIds: [201] }));
  assert.equal(v.lineByLinePractice, true);
  assert.equal(v.practicedCount, 1);
  assert.equal(v.totalLines, 2);
  const html = page.renderProgress(v);
  assert.match(html, /Đã luyện 1\/2 lượt/);
  assert.match(html, /Đã cuộn đến cuối/);
  assert.doesNotMatch(html, /giây|phút/);
});

test('each communication line offers listen, slow listen and a practised toggle that reflects server state', () => {
  const v = page.viewModel(commStudy({ practicedLineIds: [201] }));
  const html = page.renderScript(v, { showTranslation: true, rolePlay: null, revealed: [] });
  assert.match(html, /data-practice-line="201"[^>]*aria-pressed="true"/);
  assert.match(html, /data-practice-line="202"[^>]*aria-pressed="false"/);
  assert.match(html, /data-speak-rate="0.7"/, 'slow synthetic listening for beginners');
  assert.match(html, /data-toggle-translation/);
  assert.match(html, /data-roleplay/);
  assert.doesNotMatch(html, /giây/);
});

test('role-play hides the chosen role until revealed and hiding translation keeps Japanese visible', () => {
  const v = page.viewModel(commStudy());
  const hidden = page.renderScript(v, { showTranslation: false, rolePlay: 'GUEST', revealed: [] });
  assert.doesNotMatch(hidden, /チェックインをお願いします/);
  assert.match(hidden, /data-reveal-line="202"/);
  assert.match(hidden, /いらっしゃいませ/);
  assert.doesNotMatch(hidden, /Xin chào quý khách/);
  const revealed = page.renderScript(v, { showTranslation: true, rolePlay: 'GUEST', revealed: [202] });
  assert.match(revealed, /チェックインをお願いします/);
});

test('JLPT dialogues keep the timed panel and no practice controls', () => {
  const v = page.viewModel(study());
  assert.equal(v.lineByLinePractice, false);
  assert.doesNotMatch(page.renderScript(v, {}), /data-practice-line/);
  assert.match(page.renderProgress(v), /180 giây/);
});

test('practising a communication line posts only the line id and never starts the heartbeat timer', async () => {
  const vm = require('node:vm');
  const fs = require('node:fs');
  const handlers = {}, calls = [];
  let intervals = 0;
  const node = { innerHTML: '', setAttribute() {}, addEventListener: (name, handler) => { handlers[name] = handler; },
    querySelector: () => null };
  const document = { readyState: 'loading', visibilityState: 'visible', getElementById: () => node,
    addEventListener: (name, handler) => { handlers['document:' + name] = handler; } };
  const window = { document, location: { search: '?activity=7' }, JlasAuth: { requireLogin: () => true, authHeaders: () => ({}) },
    InteractiveStudyMode: { resolveMode: () => 'LEARN' }, localStorage: { getItem: () => null, setItem() {} } };
  const fetch = async (url, options) => {
    calls.push((options && options.method || 'GET') + ' ' + url.replace('/api/learning/v2/activities/7/dialogue', ''));
    const data = url.endsWith('/study') ? commStudy()
      : { activityId: 7, requiredSeconds: 0, practicedLines: 1, totalLines: 2, lineByLinePractice: true, reachedBottom: false, completed: false };
    return { ok: true, json: async () => ({ data }) };
  };
  const context = vm.createContext({ window, document, URLSearchParams, fetch,
    setInterval() { intervals++; return 1; }, clearInterval() {} });
  vm.runInContext(fs.readFileSync(require.resolve('../../main/resources/static/js/pages/interactive-dialogue-study.js'), 'utf8'), context);
  handlers['document:DOMContentLoaded']();
  await new Promise(setImmediate);
  const button = { getAttribute: name => (name === 'data-practice-line' ? '202' : null) };
  handlers.click({ target: { closest: selector => (selector === '[data-practice-line]' ? button : null) } });
  await new Promise(setImmediate);
  assert.equal(intervals, 0, 'no heartbeat timer for communication dialogues');
  assert.ok(calls.includes('POST /lines/202/practiced'), calls.join(','));
});

// ---- Owner 2026-10-19 (C2/C4): furigana toggle, "Từ & cụm từ", end summary (J-LAS supplement) ----

function aidStudy(overrides = {}) {
  return commStudy(Object.assign({
    lines: [line({ furigana: null }), line({ lineId: 202, orderIndex: 2, roleCode: 'GUEST', roleName: 'Khách', bubbleSide: 'phai',
      japanese: 'チェックインをお願いします。', meaningVi: 'Cho tôi nhận phòng.', audioUrl: 'tts:ja-JP',
      furigana: 'チェックインをお{願|ねが}いします。' })],
    aids: { label: 'Nội dung bổ sung J-LAS · cần rà soát', reviewStatus: 'NEEDS_REVIEW',
      phrases: [{ index: 0, japanese: 'お願いします', reading: 'おねがいします', meaningVi: 'xin vui lòng', lineId: 202, flipped: false },
        { index: 1, japanese: '<b>x</b>', reading: null, meaningVi: '<i>y</i>', lineId: null, flipped: true }],
      patterns: [{ pattern: '〜をお願いします', meaningVi: 'Cho tôi xin ~', note: 'Câu đề nghị' }] }
  }, overrides));
}

test('furigana renders as ruby only when its base text is the line itself, and the toggle turns it off', () => {
  const v = page.viewModel(aidStudy());
  const on = page.renderScript(v, { showTranslation: true, showFurigana: true, rolePlay: null, revealed: [] });
  assert.match(on, /<ruby>願<rt>ねが<\/rt><\/ruby>/);
  assert.match(on, /data-toggle-furigana[^>]*checked/);
  const off = page.renderScript(v, { showTranslation: true, showFurigana: false, rolePlay: null, revealed: [] });
  assert.doesNotMatch(off, /<ruby>/);
  assert.match(off, /チェックインをお願いします。/);
  assert.equal(page.rubyHtml('{予約|よやく}した。', '予約しました。'), '予約しました。', 'stale furigana falls back to the plain line');
  assert.equal(page.rubyHtml('{<b>|x}', '<b>'), '<ruby>&lt;b&gt;<rt>x</rt></ruby>', 'ruby text is escaped');
});

test('communication script shows Từ & cụm từ flip cards labelled as a J-LAS supplement, escaped, with listen and jump actions', () => {
  const html = page.renderScript(page.viewModel(aidStudy()), { showTranslation: true, showFurigana: true, rolePlay: null, revealed: [] });
  assert.match(html, /Từ &amp; cụm từ trong hội thoại \(1\/2 thẻ đã lật\)/);
  assert.match(html, /Nội dung bổ sung J-LAS · cần rà soát/);
  assert.match(html, /data-flip-phrase="0"[^>]*aria-pressed="false"/);
  assert.match(html, /data-flip-phrase="1"[^>]*aria-pressed="true"/);
  assert.match(html, /お願いします[\s\S]*おねがいします/);
  assert.doesNotMatch(html, /xin vui lòng/, 'the meaning stays on the back until the card is flipped');
  assert.match(html, /&lt;i&gt;y&lt;\/i&gt;/, 'a flipped card shows its escaped meaning');
  assert.match(html, /data-jump-line="202"/);
  assert.match(html, /data-speak-text="お願いします"/);
  assert.doesNotMatch(html, /<b>x<\/b>|<i>y<\/i>/);
  assert.equal((html.match(/data-jump-line=/g) || []).length, 1, 'a phrase without a line has no jump action');
});

test('dialogues without aids keep the old script: no phrase panel and no furigana toggle', () => {
  const comm = page.renderScript(page.viewModel(commStudy()), { showTranslation: true, rolePlay: null, revealed: [] });
  assert.doesNotMatch(comm, /Từ &amp; cụm từ trong hội thoại|data-toggle-furigana/);
  assert.doesNotMatch(page.renderScript(page.viewModel(study()), {}), /Từ &amp; cụm từ|<ruby>/);
});

test('the completion screen of a communication dialogue ends with Tóm tắt cuối bài: practised turns, patterns and phrases', () => {
  const v = page.viewModel(aidStudy({ practicedLineIds: [201, 202], completed: true }));
  const html = page.renderCompleted(1, true, v);
  assert.match(html, /Tóm tắt cuối bài/);
  assert.match(html, /Đã luyện 2\/2 lượt/);
  assert.match(html, /〜をお願いします[\s\S]*Cho tôi xin ~/);
  assert.match(html, /お願いします[\s\S]*xin vui lòng/);
  assert.match(html, /Nội dung bổ sung J-LAS · cần rà soát/);
  assert.match(html, /Về lộ trình/);
  assert.doesNotMatch(page.renderCompleted(1, false), /Tóm tắt cuối bài/, 'JLPT completion is unchanged');
});

test('review of a communication dialogue shows furigana and the summary without tracking controls', () => {
  const html = page.renderReplay('REVIEW', page.viewModel(aidStudy({ completed: true })));
  assert.match(html, /<ruby>願<rt>ねが<\/rt><\/ruby>/);
  assert.match(html, /Tóm tắt cuối bài/);
  assert.doesNotMatch(html, /data-practice-line|dialogueBottomSentinel/);
});

test('communication progress counts flipped Từ & cụm từ cards and asks for the remaining ones before completion', () => {
  const v = page.viewModel(aidStudy({ practicedLineIds: [201, 202], reachedBottom: true }));
  assert.equal(v.flippedPhrases, 1);
  assert.equal(v.totalPhrases, 2);
  const html = page.renderProgress(v);
  assert.match(html, /Đã lật 1\/2 thẻ Từ &amp; cụm từ/);
  assert.match(html, /Còn 1 thẻ Từ &amp; cụm từ chưa lật/);
  const fromResult = page.viewModel({ lineByLinePractice: true, practicedLines: 2, totalLines: 2, flippedPhrases: 2,
    totalPhrases: 2, reachedBottom: true, lines: [] });
  assert.match(page.renderProgress(fromResult), /Đã lật 2\/2 thẻ/);
  assert.doesNotMatch(page.renderProgress(page.viewModel(commStudy())), /thẻ Từ/, 'no phrase cards, no phrase progress');
});

// ---- §2.24 G1–G3: optional "vượt ải" — choose the right reply at each learner turn ----

function challengeView(overrides = {}) {
  return Object.assign({ available: true, cleared: false, rewardCredits: 2, learnerRole: 'Khách', label: 'Nội dung bổ sung J-LAS · cần rà soát',
    turns: [{ index: 0, lineId: 202, options: [
      { id: 'a', japanese: '<b>またね。</b>', meaningVi: 'Hẹn gặp lại.', furigana: '<b>またね。</b>' },
      { id: 'b', japanese: 'チェックインをお願いします。', meaningVi: 'Cho tôi nhận phòng.', furigana: 'チェックインをお{願|ねが}いします。' },
      { id: 'c', japanese: '予約していません。', meaningVi: 'Tôi chưa đặt.', furigana: '{予約|よやく}していません。' }] }] }, overrides);
}

test('the challenge plays the script up to the learner turn and offers three escaped replies without any correctness hint', () => {
  const v = page.viewModel(aidStudy());
  const html = page.renderChallenge(challengeView(), v.lines, { turn: 0, answers: [] }, { showFurigana: true, showTranslation: true });
  assert.match(html, /Thử thách vượt ải/);
  assert.match(html, /Vượt ải lần đầu: \+2 CP/);
  assert.match(html, /いらっしゃいませ/, 'the partner line before the learner turn is shown');
  assert.match(html, /Bạn sẽ nói gì\?/);
  assert.equal((html.match(/data-challenge-option="/g) || []).length, 3);
  assert.match(html, /<ruby>予約<rt>よやく<\/rt><\/ruby>/);
  assert.doesNotMatch(html, /<b>またね/);
  assert.doesNotMatch(html, /is-correct|is-wrong|correct/i);
  assert.doesNotMatch(html, /ilp-dialogue__line[^"]*"[^>]*data-line="202"/, 'the learner line itself is not printed before the choice');
});

test('a wrong reply shows the right one with its explanation and offers to retry; nothing is lost', () => {
  const v = page.viewModel(aidStudy());
  const html = page.renderChallenge(challengeView(), v.lines, { turn: 0, answers: [],
    feedback: { chosen: 'a', correct: false, correctOptionId: 'b', explanationVi: 'Khách muốn nhận phòng.' } }, {});
  assert.match(html, /data-challenge-option="b"[^>]*class="[^"]*is-correct/);
  assert.match(html, /data-challenge-option="a"[^>]*class="[^"]*is-wrong/);
  assert.match(html, /Khách muốn nhận phòng\./);
  assert.match(html, /data-challenge-retry/);
  assert.match(html, /không mất gì/);
});

test('the challenge result celebrates the first clear with +2 CP and a replay clear without extra CP', () => {
  const first = page.renderChallengeResult({ cleared: true, firstClear: true, creditsAwarded: 2, correctCount: 1, questionCount: 1 });
  assert.match(first, /Vượt ải thành công/);
  assert.match(first, /\+2 CP/);
  const replay = page.renderChallengeResult({ cleared: true, firstClear: false, creditsAwarded: 0, correctCount: 1, questionCount: 1 });
  assert.match(replay, /không cộng thêm CP/);
  assert.doesNotMatch(replay, /\+2 CP/);
  assert.match(first, /data-challenge-retry/);
  assert.match(first, /href="\/learning\/path\/v2"/);
});

test('communication dialogues with aids offer the optional challenge on the study page and the completion screen; JLPT does not', () => {
  const v = page.viewModel(aidStudy());
  assert.match(page.renderScript(v, { showFurigana: true }), /data-challenge-start[\s\S]*tùy chọn/);
  assert.match(page.renderCompleted(1, true, page.viewModel(aidStudy({ completed: true }))), /data-challenge-start/);
  assert.doesNotMatch(page.renderScript(page.viewModel(study()), {}), /data-challenge-start/);
  assert.doesNotMatch(page.renderCompleted(1, false), /data-challenge-start/);
});
