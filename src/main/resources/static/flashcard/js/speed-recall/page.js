import { createApi } from './api.js';
import { RecallEngine } from './engine.js';
import { isKanaAnswer, normalizeAnswer } from './rules.js';
import { createTimer } from './timer.js';
import { $, text, show, slots, review } from './view.js';

const params = new URLSearchParams(location.search);
const setId = params.get('setId');
const autoStart = params.get('autostart') === '1';
let sessionId = params.get('sessionId'), page = 0, options, retryOperation;
let compositionEnded = -Infinity, lastRenderedQuestion, disposed = false, canAdvance = false, incorrectResetTimer;
const INCORRECT_REVEAL_MS = 1000;
const api = createApi(setId), engine = new RecallEngine(), timer = createTimer();
const events = new AbortController();
const studyUrl = '/flashcard/speed-recall.html?setId=' + encodeURIComponent(setId || '');
const setUrl = '/flashcard/study.html?setId=' + encodeURIComponent(setId || '');
const terminal = state => ['FINISHED', 'ABANDONED', 'EXPIRED'].includes(state);
const labels = { READY: 'Chưa bắt đầu', PLAYING: 'Đang học', ANSWER_FEEDBACK: 'Đang học', PAUSED: 'Tạm dừng', FINISHED: 'Đã hoàn tất', ABANDONED: 'Đã kết thúc sớm', EXPIRED: 'Phiên đã hết hạn' };
const filters = { ALL: 'Tất cả thẻ', LEARNING: 'Thẻ mới / chưa nhớ', STARRED: 'Thẻ có sao', RETRY: 'Luyện lại từ sai' };
function on(id, event, fn) {
  const element = $(id);
  if (element) element.addEventListener(event, fn, { signal: events.signal });
}
function section(name) { for (const id of ['setup', 'playing', 'result', 'history']) show(id, id === name); }
function links() {
  for (const id of ['result-back']) {
    const link = $(id); if (link) link.href = setUrl;
  }
  for (const id of ['new-session', 'history-start']) {
    const link = $(id); if (link) link.href = studyUrl;
  }
}
function controls() {
  const busy = engine.busy || !!retryOperation;
  for (const id of ['start', 'pause', 'resume', 'abandon', 'retry-wrong']) $(id).disabled = busy;
  $('start').disabled = busy || !available();
  const next = $('next-question');
  if (next) {
    next.hidden = engine.view?.state !== 'ANSWER_FEEDBACK';
    next.disabled = busy || !canAdvance;
  }
  $('typing-input').disabled = engine.view?.state !== 'PLAYING' || !!incorrectResetTimer;
}
function available() {
  if (!options) return 0;
  return options[{ ALL: 'eligible', LEARNING: 'learning', STARRED: 'starred' }[$('filter').value]] || 0;
}
function optionSummary() {
  const total = available(), count = $('count');
  const current = Number(count.value);
  count.max = String(total);
  count.value = String(Number.isInteger(current) ? Math.min(total, Math.max(1, current)) : total);
  text('count-label', 'Số câu');
  text('count-suffix', '/ ' + total + ' thẻ');
  text('eligible-count', total + ' thẻ sẵn sàng');
  controls();
}
function validQuestionCount(report) {
  const input = $('count'), total = available(), count = Number(input.value);
  const valid = Number.isInteger(count) && count >= 1 && count <= total;
  input.setCustomValidity(valid ? '' : 'Số câu hỏi phải là số nguyên từ 1 đến ' + total + '.');
  input.setAttribute('aria-invalid', String(!valid));
  if (!valid && report) {
    input.focus();
    input.reportValidity();
  }
  return valid ? count : null;
}
async function run(operation) {
  if (engine.busy || disposed) return;
  engine.busy = true; retryOperation = null; show('error-box', false); controls();
  try { await operation(); }
  catch (error) {
    timer.stop(); retryOperation = operation;
    if ($('pause-dialog').open) $('pause-dialog').close();
    text('error-message', (error.message || 'Không thể kết nối máy chủ.') + ' Kết quả chưa được xác nhận. Thử lại để đồng bộ phiên học.');
    show('error-box', true);
  } finally {
    engine.busy = false; show('loading', false); controls();
    if (!retryOperation && engine.view) schedule();
  }
}
function accept(view) {
  engine.setView(view); sessionId = String(view.id);
  canAdvance = view.state === 'ANSWER_FEEDBACK' && remaining(view.feedbackUntil, view.serverNow) === 0;
  const path = terminal(view.state) ? '/flashcard/speed-recall-result.html' : '/flashcard/speed-recall.html';
  history.replaceState(null, '', path + '?setId=' + encodeURIComponent(setId) + '&sessionId=' + sessionId);
  render();
}
function render() {
  const v = engine.view;
  timer.stop();
  const dialog = $('pause-dialog');
  if (v.state !== 'PAUSED' && dialog.open) dialog.close();
  if (terminal(v.state)) {
    section('result');
    text('result-set', v.title); text('result-status', labels[v.state] + ' · Đã lưu ' + (v.stats.correct + v.stats.wrong) + '/' + v.stats.total + ' câu.');
    text('result-score', v.stats.score); text('result-accuracy', v.stats.accuracy + '%');
    text('result-time', (v.stats.averageResponseMs / 1000).toFixed(1) + 's');
    text('result-streak', v.stats.bestStreak); text('result-correct', v.stats.correct + '/' + v.stats.total);
    text('personal-best', v.personalBestScore == null ? '' : 'Điểm tốt nhất của bạn với cùng dữ liệu và thiết lập: ' + v.personalBestScore + '.');
    const wrong = v.results.filter(a => a.result !== 'CORRECT');
    review($('review-list'), wrong);
    show('no-mistakes', !wrong.length && v.stats.correct > 0);
    show('retry-wrong', v.state === 'FINISHED' && wrong.some(a => a.cardId != null));
    text('live-announcement', labels[v.state] + '. Điểm ' + v.stats.score);
    return;
  }
  section('playing');
  text('round', (v.currentQuestionIndex + 1) + ' / ' + v.stats.total);
  text('score', v.stats.score); text('streak', v.stats.streak);
  if (v.question) {
    text('question-word', v.question.text);
    $('typing-input').value = engine.input;
    slots($('typing-slots'), engine.slots());
    if (lastRenderedQuestion !== v.question.id) {
      text('live-announcement', 'Câu ' + (v.question.index + 1) + '. ' + v.question.text + '. ' + v.question.timeLimitMs / 1000 + ' giây.');
      lastRenderedQuestion = v.question.id;
    }
  } else if (v.feedback) text('question-word', v.feedback.question);
  show('feedback', !!v.feedback && v.state !== 'PLAYING');
  if (v.feedback) {
    const a = v.feedback;
    text('feedback-title', a.result === 'CORRECT' ? 'Chính xác' : a.result === 'TIMEOUT' ? 'Hết giờ' : 'Chưa đúng');
    text('feedback-score', '+' + a.scoreAwarded + ' điểm');
    text('feedback-word', a.question); text('feedback-reading', a.reading); text('feedback-meaning', a.meaning);
    text('feedback-answer', 'Bạn nhập: ' + (a.userAnswer || '(trống)'));
  }
  controls();
  if (v.state === 'PAUSED' && !dialog.open) dialog.showModal();
  if (v.state === 'PLAYING' && !document.hidden) $('typing-input').focus({ preventScroll: true });
}
function remaining(deadline, serverNow) { return Math.max(0, Date.parse(deadline) - Date.parse(serverNow)); }
function schedule() {
  timer.stop();
  const v = engine.view;
  if (!v || disposed || terminal(v.state) || v.state === 'PAUSED') return;
  if (document.hidden && ['PLAYING', 'ANSWER_FEEDBACK'].includes(v.state)) {
    void run(async () => accept(await api.action(sessionId, 'pause'))); return;
  }
  if (v.state === 'READY') {
    void run(async () => accept(await api.action(sessionId, 'next', { questionIndex: 0 }))); return;
  }
  if (v.state === 'ANSWER_FEEDBACK') {
    const wait = remaining(v.feedbackUntil, v.serverNow);
    if (wait > 0) {
      timer.start(wait + 80, () => {}, () => {
        canAdvance = true;
        controls();
        text('live-announcement', 'Có thể chuyển sang câu tiếp theo.');
      });
    }
    return;
  }
  if (v.state === 'PLAYING') {
    timer.start(remaining(v.question.deadlineAt, v.serverNow) + 80, left => {
      text('time-left', (Math.max(0, left - 80) / 1000).toFixed(1) + 's');
      $('timer-bar').style.transform = 'scaleX(' + Math.min(1, Math.max(0, left - 80) / v.question.timeLimitMs) + ')';
    }, () => submit(true));
  }
}
function updateInput() {
  const input = $('typing-input');
  // IME can deliver queued input/composition events after the answer is marked wrong.
  // Keep the red answer immutable until its single reset timer finishes.
  if (incorrectResetTimer) {
    input.value = engine.input;
    return;
  }
  engine.input = input.value;
  slots($('typing-slots'), engine.slots());
  if (!retryOperation && engine.view?.state === 'PLAYING') {
    const answer = normalizeAnswer(engine.input);
    const expected = normalizeAnswer(engine.view.question?.expectedAnswer);
    const answerChars = Array.from(answer);
    const expectedLength = Array.from(expected).length;
    // Japanese IME may keep composition open while producing kana. Non-kana input
    // can be judged immediately so Vietnamese/Latin composition cannot overflow.
    const canJudge = !engine.composing || !isKanaAnswer(answer);
    if (expectedLength && canJudge && answerChars.length >= expectedLength) {
      if (!engine.composing && answer === expected) {
        submit(false);
        return;
      }
      engine.input = answerChars.slice(0, expectedLength).join('');
      input.value = engine.input;
      slots($('typing-slots'), engine.slots());
      const slotSurface = $('typing-slots');
      slotSurface.classList.remove('is-mistake');
      void slotSurface.offsetWidth;
      slotSurface.classList.add('is-mistake');
      text('live-announcement', 'Chưa đúng.');
      incorrectResetTimer = window.setTimeout(() => {
        incorrectResetTimer = undefined;
        if (engine.view?.state !== 'PLAYING' || engine.busy || retryOperation) return;
        engine.input = '';
        input.value = '';
        slots($('typing-slots'), engine.slots());
        slotSurface.classList.remove('is-mistake');
        controls();
        input.focus({ preventScroll: true });
        text('live-announcement', 'Hãy nhập lại.');
      }, INCORRECT_REVEAL_MS);
      input.disabled = true;
    }
  }
  controls();
}
function submit(timeout) {
  if (engine.busy || retryOperation || (!timeout && !engine.canSubmit())) return;
  const v = engine.view;
  if (v?.state !== 'PLAYING') return;
  const body = { questionId: v.question.id, requestId: crypto.randomUUID(), userAnswer: engine.input };
  timer.stop();
  void run(async () => accept(await api.action(sessionId, timeout ? 'timeout' : 'answers', body)));
}
async function loadHistory() {
  const h = await api.history(page);
  section('history'); text('history-set', options.title);
  const list = $('history-list'); list.replaceChildren();
  for (const item of h.content) {
    const row = document.createElement('li'), link = document.createElement('a'), detail = document.createElement('p');
    link.href = studyUrl + '&sessionId=' + item.id;
    link.textContent = new Date(item.startedAt).toLocaleString('vi-VN') + ' · ' + labels[item.state];
    detail.textContent = filters[item.filter] + ' · ' + (item.autoSubmit ? 'Tự kiểm tra' : 'Nhấn Enter') + ' · ' +
      (item.stats.correct + item.stats.wrong) + '/' + item.stats.total + ' câu · ' + item.stats.score + ' điểm · ' + item.stats.accuracy + '% đúng';
    row.append(link, detail); list.append(row);
  }
  show('history-empty', !h.totalElements); text('history-page', (h.page + 1) + '/' + Math.max(1, h.totalPages));
  $('previous-page').disabled = h.page === 0; $('next-page').disabled = h.page + 1 >= h.totalPages;
}
on('setup-form', 'submit', event => {
  event.preventDefault();
  if (!available()) return;
  const count = validQuestionCount(true);
  if (count === null) return;
  const body = { requestId: crypto.randomUUID(), filter: $('filter').value, count, autoSubmit: true };
  void run(async () => accept(await api.create(body)));
});
on('filter', 'change', optionSummary);
on('count', 'input', () => validQuestionCount(false));
on('retry-request', 'click', () => { const op = retryOperation; if (op) void run(op); });
on('typing-input', 'compositionstart', () => { engine.composing = true; });
on('typing-input', 'compositionend', () => { engine.composing = false; compositionEnded = performance.now(); updateInput(); });
on('typing-input', 'input', updateInput);
on('typing-input', 'keydown', event => {
  if (event.key === 'Enter' && (event.isComposing || engine.composing || event.keyCode === 229 || performance.now() - compositionEnded <= 80)) {
    // Do not prevent the native IME commit while composing.
    if (!event.isComposing && !engine.composing && event.keyCode !== 229) event.preventDefault();
  }
});
on('typing-slots', 'click', () => $('typing-input').focus({ preventScroll: true }));
on('typing-slots', 'keydown', event => {
  if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault();
    $('typing-input').focus({ preventScroll: true });
  }
});
on('pause', 'click', () => void run(async () => accept(await api.action(sessionId, 'pause'))));
on('resume', 'click', () => void run(async () => accept(await api.action(sessionId, 'resume'))));
on('pause-dialog', 'cancel', event => { event.preventDefault(); if (!retryOperation) $('resume').click(); });
on('abandon', 'click', () => void run(async () => accept(await api.action(sessionId, 'abandon'))));
on('next-question', 'click', () => {
  if (!canAdvance || engine.view?.state !== 'ANSWER_FEEDBACK') return;
  void run(async () => accept(await api.action(sessionId, 'next', { questionIndex: engine.view.currentQuestionIndex + 1 })));
});
on('retry-wrong', 'click', () => {
  const source = sessionId, body = { requestId: crypto.randomUUID() };
  void run(async () => accept(await api.action(source, 'retry-wrong', body)));
});
on('previous-page', 'click', () => { if (engine.busy) return; page = Math.max(0, page - 1); void run(loadHistory); });
on('next-page', 'click', () => { if (engine.busy) return; page++; void run(loadHistory); });
document.addEventListener('keydown', event => {
  if (event.key === 'Escape' && !event.isComposing && !engine.composing && ['PLAYING', 'ANSWER_FEEDBACK'].includes(engine.view?.state)) {
    event.preventDefault(); $('pause').click();
  }
}, { signal: events.signal });
document.addEventListener('visibilitychange', () => {
  if (document.hidden && !engine.busy && !retryOperation && ['PLAYING', 'ANSWER_FEEDBACK'].includes(engine.view?.state)) $('pause').click();
  else if (!document.hidden && sessionId && !engine.busy && !retryOperation && !terminal(engine.view?.state)) void run(async () => accept(await api.state(sessionId)));
}, { signal: events.signal });
window.addEventListener('pagehide', () => { disposed = true; clearTimeout(incorrectResetTimer); timer.stop(); events.abort(); }, { once: true });
window.addEventListener('pageshow', event => { if (event.persisted) location.reload(); });
links();
void run(async () => {
  if (!setId || !/^\d+$/.test(setId)) throw new Error('Đường dẫn thiếu mã bộ thẻ hợp lệ.');
  options = await api.options(); text('set-title', options.title); optionSummary();
  if (location.pathname.endsWith('speed-recall-history.html')) await loadHistory();
  else if (sessionId) accept(await api.state(sessionId));
  else if (autoStart) {
    const count = available();
    if (!count) throw new Error('Bộ thẻ này chưa có thẻ hợp lệ để luyện phản xạ.');
    accept(await api.create({ requestId: crypto.randomUUID(), filter: 'ALL', count, autoSubmit: true }));
  } else section('setup');
});
