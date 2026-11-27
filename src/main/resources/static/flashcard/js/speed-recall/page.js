import { createApi } from './api.js';
import { RecallEngine } from './engine.js?v=20261006-romaji';
import { romajiToHiragana } from './romaji.js';
import { normalizeAnswer, ANSWER_TYPES } from './rules.js';
import { createTimer } from './timer.js';
import { $, text, show, slots, review } from './view.js';
import { bindSetupUi } from './setup-ui.js?v=20261123-heading-inside';
import '../card-selection.js?v=20261015-pager';

const params = new URLSearchParams(location.search);
const setId = params.get('setId');
const autoStart = params.get('autostart') === '1';
let sessionId = params.get('sessionId'), page = 0, options, retryOperation;
let pendingEnterQuestionId, compositionEnded = -Infinity, lastRenderedQuestion, disposed = false, canAdvance = false;
const api = createApi(setId), engine = new RecallEngine(), timer = createTimer();
const events = new AbortController();
bindSetupUi();
const studyUrl = '/flashcard/speed-recall.html?setId=' + encodeURIComponent(setId || '');
const setUrl = '/flashcard/study.html?setId=' + encodeURIComponent(setId || '');
const terminal = state => ['FINISHED', 'ABANDONED', 'EXPIRED'].includes(state);
const labels = { READY: 'Chưa bắt đầu', PLAYING: 'Đang học', ANSWER_FEEDBACK: 'Đang học', PAUSED: 'Tạm dừng', FINISHED: 'Đã hoàn tất', ABANDONED: 'Đã kết thúc sớm', EXPIRED: 'Phiên đã hết hạn' };
const filters = { ALL: 'Tất cả thẻ', LEARNING: 'Thẻ mới / chưa nhớ', STARRED: 'Thẻ có sao', CUSTOM: 'Chọn cặp từ tùy ý', RANDOM: 'Ngẫu nhiên', RETRY: 'Luyện lại từ sai' };

// ── Question / answer mode ─────────────────────────────────────────────
// Any card field can be asked and any other one answered. A card joins a mode only when it has both
// fields (the server decides and reports counts per mode); choices with no usable card are disabled.
const FIELDS = ['KANJI', 'HIRAGANA', 'MEANING', 'HAN_VIET'];
const FIELD_NAMES = { KANJI: 'Kanji', HIRAGANA: 'Hiragana', MEANING: 'Nghĩa', HAN_VIET: 'Hán Việt' };
const FIELD_LABELS = { KANJI: 'Kanji', HIRAGANA: 'cách đọc', MEANING: 'nghĩa', HAN_VIET: 'âm Hán Việt' };
const ANSWER_PROMPTS = {
  HIRAGANA: 'Gõ Romaji hoặc kana, Enter để kiểm tra',
  KANJI: 'Gõ Kanji bằng bộ gõ tiếng Nhật, Enter để kiểm tra',
  MEANING: 'Gõ nghĩa tiếng Việt (có dấu hay không đều được), Enter để kiểm tra',
  HAN_VIET: 'Gõ âm Hán Việt (có dấu hay không đều được), Enter để kiểm tra'
};
const ANSWER_HINTS = {
  HIRAGANA: 'Gõ Romaji bằng bàn phím ENG hoặc nhập kana', KANJI: 'Gõ Kanji bằng bộ gõ tiếng Nhật',
  MEANING: 'Gõ nghĩa, có dấu hay không dấu đều được', HAN_VIET: 'Gõ âm Hán Việt, có dấu hay không dấu đều được'
};
const MODE_KEY = 'jlas.speedRecall.mode';
const TIME_KEY = 'jlas.speedRecall.timeLimit';
let mode = { question: 'KANJI', answer: 'HIRAGANA' };
let selectedCardIds = null, randomSelection = null;
// Filter + usable cards the count was last set for; when they change the count goes back to "all of them".
let countKey = '';
// The original mode is named KANJI_TO_READING, so a Hiragana answer is "READING" on the wire.
function modeName(m = mode) { return m.question + '_TO_' + (m.answer === 'HIRAGANA' ? 'READING' : m.answer); }
function parseMode(name) {
  const match = /^(KANJI|HIRAGANA|MEANING|HAN_VIET)_TO_(READING|KANJI|MEANING|HAN_VIET)$/.exec(name || '');
  return match ? { question: match[1], answer: match[2] === 'READING' ? 'HIRAGANA' : match[2] } : { question: 'KANJI', answer: 'HIRAGANA' };
}
function modeCount(m) {
  if (m.question === m.answer) return 0;
  const counts = options?.modeCounts;
  return counts ? counts[modeName(m)] || 0 : 1; // unknown yet: do not block
}
function validMode(m) { return m.question !== m.answer && modeCount(m) > 0; }
function modeLabel(name) {
  const m = parseMode(name);
  return FIELD_NAMES[m.question] + ' sang ' + FIELD_NAMES[m.answer];
}
/** Closest usable mode: keep the question if possible, else the first question that has any answer. */
function nearestMode(preferred) {
  const answers = q => [preferred.answer, 'HIRAGANA', 'MEANING', 'HAN_VIET', 'KANJI'].map(a => ({ question: q, answer: a }));
  for (const q of [preferred.question, ...FIELDS]) {
    const found = answers(q).find(validMode);
    if (found) return found;
  }
  return preferred;
}
function loadSavedMode() {
  try {
    const saved = parseMode(localStorage.getItem(MODE_KEY));
    if (saved.question !== saved.answer) mode = saved;
  } catch (e) { /* storage may be blocked */ }
}
function saveMode() { try { localStorage.setItem(MODE_KEY, modeName()); } catch (e) { /* optional */ } }
/** Seconds per word picked on the setup form, remembered for the next session; null keeps the automatic time. */
function timeLimitSeconds() {
  const value = Number($('time-limit')?.value);
  return Number.isInteger(value) && value > 0 ? value : null;
}
function loadSavedTimeLimit() {
  const select = $('time-limit');
  if (!select) return;
  try {
    const saved = localStorage.getItem(TIME_KEY);
    if (saved && Array.from(select.options).some(option => option.value === saved)) select.value = saved;
  } catch (e) { /* storage may be blocked */ }
}
/** Hỏi bằng / Đáp bằng are dropdowns; an option that cannot be played is disabled and says why on its second line. */
function renderModePicker() {
  const question = $('question-field'), answer = $('answer-field');
  if (question) {
    Array.from(question.options).forEach(option => {
      const usable = FIELDS.some(a => validMode({ question: option.value, answer: a }));
      option.disabled = !usable;
      if (usable) delete option.dataset.hint;
      else option.dataset.hint = 'Bộ thẻ chưa có thẻ nào đủ ' + FIELD_LABELS[option.value] + ' để hỏi';
    });
    question.value = mode.question;
  }
  if (answer) {
    Array.from(answer.options).forEach(option => {
      const a = option.value, same = a === mode.question, usable = validMode({ question: mode.question, answer: a });
      option.disabled = !usable;
      if (usable) delete option.dataset.hint;
      else option.dataset.hint = same ? 'Không thể hỏi và đáp cùng một nội dung'
        : 'Không có thẻ nào có đủ ' + FIELD_LABELS[mode.question] + ' và ' + FIELD_LABELS[a];
    });
    answer.value = mode.answer;
  }
  text('answer-hint', ANSWER_HINTS[mode.answer]);
}
async function changeMode(next) {
  if (engine.busy || retryOperation) return;
  if (!validMode(next)) next = nearestMode(next);
  if (next.question === mode.question && next.answer === mode.answer) return;
  mode = next; saveMode(); renderModePicker();
  // Eligible card counts depend on the mode (e.g. cards without Hán Việt drop out).
  await run(async () => { options = await api.options(modeName()); optionSummary(); renderModePicker(); });
}
/** Applies the answer type of the session being played (it may differ from the picker, e.g. history links). */
function applySessionMode(name) {
  const m = parseMode(name);
  engine.type = ANSWER_TYPES[m.answer];
  if (!$('playing') || !$('typing-input')) return; // result/history pages have no play surface
  // Read by CSS (::before / ::after) for the mode chip and the footer line.
  document.querySelector('#playing .sr-question').dataset.modeLabel = modeLabel(name);
  $('playing').dataset.answerHint = ANSWER_HINTS[m.answer] + ' để trả lời';
  $('question-word').lang = m.question === 'KANJI' || m.question === 'HIRAGANA' ? 'ja' : 'vi';
  const input = $('typing-input');
  input.lang = m.answer === 'HIRAGANA' ? 'en' : ANSWER_TYPES[m.answer].lang;
  text('answer-prompt', ANSWER_PROMPTS[m.answer]);
  input.setAttribute('aria-label', ANSWER_PROMPTS[m.answer]);
  $('typing-slots').setAttribute('aria-label', 'Chạm để nhập đáp án');
}
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
    text('game-keyboard-hint', engine.view?.state === 'ANSWER_FEEDBACK'
      ? (busy ? 'Đang đồng bộ kết quả…' : canAdvance ? 'Nhấn Enter để sang câu tiếp' : 'Chờ một chút để sang câu tiếp…')
      : 'Nhấn Enter để kiểm tra đáp án');
  }
  $('typing-input').disabled = engine.view?.state !== 'PLAYING' || busy;
  show('answer-tools', engine.view?.state === 'PLAYING');
  $('clear-answer').disabled = busy || !engine.input;
}
function available() {
  return selectionPool().length;
}
function selectionPool() {
  return window.FlashcardSelection.pool(options?.cards || [], $('filter').value,
    card => $('filter').value === 'LEARNING' ? card.learning : card.eligible,
    card => card.learning, selectedCardIds || new Set());
}
function sessionCards() {
  const pool = selectionPool();
  if ($('filter').value !== 'RANDOM') return pool;
  const count = Number($('count').value);
  const key = [modeName(), count, ...pool.map(card => card.id)].join('|');
  if (randomSelection?.key !== key) randomSelection = { key, cards: window.FlashcardSelection.sample(pool, count) };
  return randomSelection.cards;
}
function renderSelection() {
  const host = $('selection-list');
  if (!host) return;
  const fieldValue = (card, field) => card[{ KANJI: 'kanji', HIRAGANA: 'hiragana', MEANING: 'meaning', HAN_VIET: 'hanViet' }[field]] || '';
  const cards = sessionCards(), custom = $('filter').value === 'CUSTOM';
  host.innerHTML = window.FlashcardSelection.table({
    cards: custom ? options?.cards || [] : cards, mode: $('filter').value,
    selected: custom ? selectedCardIds || new Set() : new Set(cards.map(card => card.id)),
    eligible: card => $('filter').value === 'LEARNING' ? card.learning : card.eligible,
    questionLabel: FIELD_NAMES[mode.question], answerLabel: FIELD_NAMES[mode.answer],
    question: card => fieldValue(card, mode.question), answer: card => fieldValue(card, mode.answer),
    reason: card => card.unavailableReason
  });
}
function optionSummary() {
  const eligible = new Set((options?.cards || []).filter(card => card.eligible).map(card => card.id));
  if (selectedCardIds === null) selectedCardIds = new Set(eligible);
  else selectedCardIds.forEach(id => { if (!eligible.has(id)) selectedCardIds.delete(id); });
  const total = available(), count = $('count');
  const current = Number(count.value);
  const key = $('filter').value + ':' + selectionPool().map(card => card.id).join(',');
  const reset = key !== countKey;
  countKey = key;
  count.max = String(total);
  // Only "Chọn cặp từ tùy ý" is fixed: there the ticked cards are the count. Other filters take any count up to
  // the usable cards (the server draws that many at random).
  count.readOnly = $('filter').value === 'CUSTOM';
  count.value = String(count.readOnly || (reset && $('filter').value !== 'RANDOM') ? total
    : Number.isInteger(current) ? Math.min(total, Math.max(1, current)) : total);
  text('count-label', 'Số câu');
  text('count-suffix', '/ ' + total + ' thẻ');
  renderSelection();
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
    if (!retryOperation && engine.view?.state === 'PLAYING' && !document.hidden) $('typing-input').focus({ preventScroll: true });
  }
}
function accept(view) {
  applySessionMode(view.mode);
  if (view.state !== 'PLAYING' || view.question?.id !== pendingEnterQuestionId) pendingEnterQuestionId = undefined;
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
    const perfect = v.state === 'FINISHED' && v.stats.correct === v.stats.total && v.stats.correct > 0;
    $('result').dataset.outcome = perfect ? 'perfect' : 'review';
    resultMascot(perfect ? 'celebrate' : 'cheer');
    text('result-mascot-title', perfect ? 'Đúng hết rồi!' : 'Kitsu cổ vũ bạn!');
    text('result-mascot-message', perfect ? '' : v.state === 'FINISHED' ? 'Luyện lại những từ bên dưới, rồi thử một lượt mới nhé.' : 'Tiến trình đã được lưu. Bạn có thể bắt đầu lượt mới.');
    text('result-title', v.state === 'FINISHED' ? 'Hoàn thành lượt chơi' : v.state === 'ABANDONED' ? 'Kết thúc lượt chơi' : 'Phiên chơi đã hết hạn');
    text('result-badge', v.state === 'FINISHED' && v.stats.wrong === 0 && v.stats.correct > 0 ? 'Không bỏ sót từ nào!' : 'Đã lưu kết quả');
    text('result-set', v.title); text('result-status', labels[v.state] + ' · Đã lưu ' + (v.stats.correct + v.stats.wrong) + '/' + v.stats.total + ' câu.');
    text('result-score', v.stats.score); text('result-accuracy', v.stats.accuracy + '%');
    text('result-time', (v.stats.averageResponseMs / 1000).toFixed(1) + 's');
    text('result-streak', v.stats.bestStreak); text('result-correct', v.stats.correct + '/' + v.stats.total);
    text('personal-best', v.personalBestScore == null ? '' : 'Kỷ lục với cùng dữ liệu và thiết lập: ' + v.personalBestScore + ' điểm.');
    const wrong = v.results.filter(a => a.result !== 'CORRECT');
    review($('review-list'), wrong);
    show('review-title', wrong.length > 0);
    show('review-list', wrong.length > 0);
    requestAnimationFrame(reviewNavigation);
    show('no-mistakes', !wrong.length && v.stats.correct > 0);
    const retryable = v.state === 'FINISHED' && wrong.some(a => a.cardId != null);
    show('retry-wrong', retryable);
    text('live-announcement', labels[v.state] + '. Điểm ' + v.stats.score);
    return;
  }
  section('playing');
  text('round', (v.currentQuestionIndex + 1) + ' / ' + v.stats.total);
  text('score', v.stats.score); text('streak', v.stats.streak);
  $('playing').dataset.feedback = v.state === 'ANSWER_FEEDBACK' ? (v.feedback?.result || '') : '';
  const completed = v.stats.correct + v.stats.wrong, total = Math.max(1, v.stats.total);
  $('run-progress').setAttribute('aria-valuemax', String(total));
  $('run-progress').setAttribute('aria-valuenow', String(completed));
  $('run-progress').setAttribute('aria-valuetext', 'Đã trả lời ' + completed + ' trên ' + v.stats.total + ' câu');
  $('run-progress-fill').style.transform = 'scaleX(' + Math.min(1, completed / total) + ')';
  document.querySelector('.sr-game-timer').classList.remove('is-urgent');
  if (v.question) {
    text('question-word', v.question.text);
    $('typing-input').value = engine.rawInput;
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
    text('feedback-word', a.question); text('feedback-reading', a.expectedAnswer || a.reading);
    // When the question already is the meaning, repeating it adds nothing.
    text('feedback-meaning', parseMode(v.mode).question === 'MEANING' ? '' : a.meaning);
    text('feedback-answer', 'Bạn nhập: ' + (a.userAnswer || '(trống)'));
  }
  show('answer-status', false);
  controls();
  if (v.state === 'PAUSED' && !dialog.open) dialog.showModal();
  if (v.state === 'PLAYING' && !document.hidden) $('typing-input').focus({ preventScroll: true });
}
/** Native WebP keeps all 30 painted frames together and loops without a CSS sprite timer. */
function resultMascot(pose) {
  const sprite = $('result-mascot-sprite');
  if (sprite.dataset.pose === pose) return;
  sprite.dataset.pose = pose;
  $('result-mascot-image').src = '/images/mascots/kitsu/kitsu-' + pose + '-30.webp';
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
      document.querySelector('.sr-game-timer').classList.toggle('is-urgent', left - 80 <= 1500);
      $('timer-bar').style.transform = 'scaleX(' + Math.min(1, Math.max(0, left - 80) / v.question.timeLimitMs) + ')';
    }, () => submit(true));
  }
}
function updateInput(event, commitRomaji = false) {
  if (engine.view?.state !== 'PLAYING' || engine.busy || retryOperation) return;
  if (event?.isComposing) engine.composing = true;
  const raw = $('typing-input').value;
  engine.updateInput(engine.type === ANSWER_TYPES.HIRAGANA && !engine.composing
    ? romajiToHiragana(raw, { commit: commitRomaji }) : raw, raw);
  $('typing-slots').classList.remove('is-mistake');
  slots($('typing-slots'), engine.slots());
  show('answer-status', false);
  // Typing only edits; a physical Enter can request a check after IME commits.
  controls();
  queueEnterCheck();
}
/**
 * The capture input is 1px wide and invisible, so the end is the only place its caret can mean anything. A caret
 * moved elsewhere (a click on the field, a refocus) put the next letters before the first one: "ninki" became
 * "inkin". Never moved during an IME composition, which owns the caret until it commits.
 */
function caretToEnd(event) {
  if (event?.isComposing || engine.composing) return;
  const input = $('typing-input'), end = input.value.length;
  if (input.selectionStart !== end || input.selectionEnd !== end) input.setSelectionRange?.(end, end);
}
/** Wait for compositionend and the final input event before checking an IME Enter. */
function queueEnterCheck() {
  const questionId = pendingEnterQuestionId;
  if (questionId == null) return;
  window.setTimeout(() => {
    if (pendingEnterQuestionId !== questionId) return;
    if (engine.view?.state !== 'PLAYING' || engine.view.question?.id !== questionId
        || disposed || engine.busy || retryOperation) {
      pendingEnterQuestionId = undefined;
      return;
    }
    if (engine.composing) return;
    if (engine.type === ANSWER_TYPES.HIRAGANA) {
      const raw = $('typing-input').value;
      engine.updateInput(romajiToHiragana(raw, { commit: true }), raw);
      slots($('typing-slots'), engine.slots());
    }
    if (engine.pendingInput()) {
      if (engine.type === ANSWER_TYPES.HIRAGANA) {
        pendingEnterQuestionId = undefined;
        unfinishedRomaji();
      }
      return;
    }
    pendingEnterQuestionId = undefined;
    submit(false);
  }, 0);
}
function checkTypedAnswer(event) {
  if (!['Enter', 'NumpadEnter'].includes(event.code) && event.key !== 'Enter') return;
  if (event.repeat || engine.busy || retryOperation || engine.view?.state !== 'PLAYING') return;
  const composing = event.isComposing || engine.composing || event.keyCode === 229;
  if (composing || performance.now() - compositionEnded <= 80) {
    // Let the IME finish its native Enter action; grade the committed text next.
    if (!composing) event.preventDefault();
    pendingEnterQuestionId = engine.view.question.id;
    queueEnterCheck();
    return;
  }
  if (engine.type === ANSWER_TYPES.HIRAGANA) updateInput(undefined, true);
  if (engine.pendingInput()) {
    event.preventDefault();
    unfinishedRomaji();
    return;
  }
  if (normalizeAnswer(engine.input) !== '') {
    event.preventDefault();
    pendingEnterQuestionId = undefined;
    submit(false);
  }
}
function unfinishedRomaji() {
  if (engine.type !== ANSWER_TYPES.HIRAGANA) return;
  text('answer-status', 'Âm Romaji chưa hoàn tất. Gõ tiếp hoặc sửa rồi nhấn Enter.');
  show('answer-status', true);
}
/** The clear button and edit keys preserve the active question and its deadline. */
function editTypedAnswer(key = 'clear') {
  if (engine.busy || retryOperation || engine.composing || engine.view?.state !== 'PLAYING') return false;
  pendingEnterQuestionId = undefined;
  const input = $('typing-input'), surface = $('typing-slots'), value = engine.rawInput;
  let start = Math.min(input.selectionStart ?? value.length, value.length);
  let end = Math.min(input.selectionEnd ?? start, value.length);
  if (key === 'clear') { start = 0; end = value.length; }
  else if (start === end && key === 'Backspace') start -= Array.from(value.slice(0, start)).at(-1)?.length || 0;
  else if (start === end && key === 'Delete') end += Array.from(value.slice(end))[0]?.length || 0;
  const raw = value.slice(0, start) + value.slice(end);
  engine.updateInput(engine.type === ANSWER_TYPES.HIRAGANA ? romajiToHiragana(raw) : raw, raw);
  input.value = raw;
  surface.classList.remove('is-mistake');
  show('answer-status', false);
  slots(surface, engine.slots());
  controls();
  input.focus({ preventScroll: true });
  input.setSelectionRange?.(start, start);
  text('live-announcement', key === 'clear' ? 'Đã xóa đáp án. Hãy nhập lại.' : 'Bạn có thể sửa đáp án.');
  return true;
}
/** A wrong check leaves the answer editable immediately; the question timer keeps running. */
function rejectTypedAnswer() {
  $('typing-slots').classList.add('is-mistake');
  text('answer-status', 'Chưa đúng. Sửa đáp án rồi nhấn Enter để thử lại.');
  show('answer-status', true);
  text('live-announcement', 'Chưa đúng. Bạn có thể thử lại khi còn thời gian.');
}
function submit(timeout) {
  if (engine.busy || retryOperation || (!timeout && !engine.canSubmit())) return;
  const v = engine.view;
  if (v?.state !== 'PLAYING') return;
  const body = { questionId: v.question.id, requestId: crypto.randomUUID(), userAnswer: engine.type.lang === 'ja' && engine.pendingInput() ? engine.confirmedInput : engine.input };
  timer.stop();
  void run(async () => {
    const response = await api.action(sessionId, timeout ? 'timeout' : 'answers', body);
    accept(response);
    if (response.state === 'PLAYING' && response.feedback?.result === 'WRONG'
        && response.feedback.questionId === body.questionId) rejectTypedAnswer();
  });
}
async function loadHistory() {
  const h = await api.history(page);
  section('history'); text('history-set', options.title);
  const list = $('history-list'); list.replaceChildren();
  for (const item of h.content) {
    const row = document.createElement('li'), link = document.createElement('a'), detail = document.createElement('p');
    link.href = studyUrl + '&sessionId=' + item.id;
    link.textContent = new Date(item.startedAt).toLocaleString('vi-VN') + ' · ' + labels[item.state];
    detail.textContent = modeLabel(item.mode) + ' · ' + filters[item.filter] + ' · ' + (item.autoSubmit ? 'Tự kiểm tra' : 'Nhấn Enter') + ' · ' +
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
  const body = { requestId: crypto.randomUUID(), filter: $('filter').value, count, autoSubmit: false, mode: modeName(),
    timeLimitSeconds: timeLimitSeconds() };
  if (['CUSTOM', 'RANDOM'].includes(body.filter)) body.selectedCardIds = sessionCards().map(card => card.id);
  void run(async () => accept(await api.create(body)));
});
on('filter', 'change', optionSummary);
on('time-limit', 'change', () => { try { localStorage.setItem(TIME_KEY, $('time-limit').value); } catch (e) { /* optional */ } });
on('count', 'input', () => { if (validQuestionCount(false) !== null) renderSelection(); });
on('selection-list', 'change', event => {
  const input = event.target.closest('[data-selection-card]');
  if (!input || input.disabled || $('filter').value !== 'CUSTOM') return;
  const id = Number(input.dataset.selectionCard);
  if (input.checked) selectedCardIds.add(id); else selectedCardIds.delete(id);
  optionSummary();
});
on('selection-list', 'click', event => {
  if (!event.target.closest('[data-selection-shuffle]')) return;
  randomSelection = null;
  renderSelection();
});
on('retry-request', 'click', () => { const op = retryOperation; if (op) void run(op); });
on('typing-input', 'compositionstart', () => {
  engine.composing = true;
  slots($('typing-slots'), engine.slots());
});
on('typing-input', 'compositionend', event => { engine.composing = false; compositionEnded = performance.now(); updateInput(event); });
on('typing-input', 'input', updateInput);
// Registered before checkTypedAnswer, so an edit key also acts at the end.
on('typing-input', 'keydown', caretToEnd);
on('typing-input', 'keydown', checkTypedAnswer);
on('typing-input', 'focus', caretToEnd);
on('typing-input', 'click', caretToEnd);

on('question-field', 'change', () => void changeMode({ question: $('question-field').value, answer: mode.answer }));
on('answer-field', 'change', () => void changeMode({ question: mode.question, answer: $('answer-field').value }));
on('typing-slots', 'click', () => $('typing-input').focus({ preventScroll: true }));
on('typing-slots', 'keydown', event => {
  if (event.key === 'Enter' && engine.view?.state === 'ANSWER_FEEDBACK') {
    event.preventDefault();
    if (!event.repeat && !event.isComposing && event.keyCode !== 229 && !engine.composing && performance.now() - compositionEnded > 80) advanceQuestion();
    return;
  }
  if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault();
    $('typing-input').focus({ preventScroll: true });
  }
});
function reviewNavigation() {
  const list = $('review-list');
  const max = list.scrollWidth - list.clientWidth;
  show('review-navigation', !list.hidden && max > 2);
  $('review-previous').disabled = list.scrollLeft <= 2;
  $('review-next').disabled = list.scrollLeft >= max - 2;
}
function scrollReview(direction) {
  const list = $('review-list'), card = list.firstElementChild;
  if (!card) return;
  list.scrollBy({ left: direction * (card.getBoundingClientRect().width + 10), behavior: 'smooth' });
}
on('review-previous', 'click', () => scrollReview(-1));
on('review-next', 'click', () => scrollReview(1));
on('review-list', 'scroll', reviewNavigation);
window.addEventListener('resize', reviewNavigation, { signal: events.signal });
on('clear-answer', 'click', () => editTypedAnswer());
on('pause', 'click', () => void run(async () => accept(await api.action(sessionId, 'pause'))));
on('resume', 'click', () => void run(async () => accept(await api.action(sessionId, 'resume'))));
on('pause-dialog', 'cancel', event => { event.preventDefault(); if (!retryOperation) $('resume').click(); });
on('abandon', 'click', () => void run(async () => accept(await api.action(sessionId, 'abandon'))));
function advanceQuestion() {
  if (engine.busy || retryOperation || !canAdvance || engine.view?.state !== 'ANSWER_FEEDBACK') return;
  void run(async () => accept(await api.action(sessionId, 'next', { questionIndex: engine.view.currentQuestionIndex + 1 })));
}
on('next-question', 'click', advanceQuestion);
on('retry-wrong', 'click', () => {
  const source = sessionId, body = { requestId: crypto.randomUUID() };
  void run(async () => accept(await api.action(source, 'retry-wrong', body)));
});
on('previous-page', 'click', () => { if (engine.busy) return; page = Math.max(0, page - 1); void run(loadHistory); });
on('next-page', 'click', () => { if (engine.busy) return; page++; void run(loadHistory); });
document.addEventListener('keydown', event => {
  // Enter confirms IME composition first; it must never consume that same key to skip a question.
  if (event.key === 'Enter' && !event.defaultPrevented && !event.repeat && !event.isComposing
      && !engine.composing && event.keyCode !== 229 && performance.now() - compositionEnded > 80
      && !event.target?.closest('button, a, input, textarea, select, [contenteditable="true"]')) {
    if (engine.view?.state === 'ANSWER_FEEDBACK') {
      event.preventDefault();
      advanceQuestion();
    } else if (terminal(engine.view?.state) && !engine.busy && !retryOperation) {
      event.preventDefault();
      const retry = $('retry-wrong');
      if (!retry.hidden && !retry.disabled) retry.click();
      else $('new-session').click();
    }
  }
  if (event.key === 'Escape' && !event.isComposing && !engine.composing && ['PLAYING', 'ANSWER_FEEDBACK'].includes(engine.view?.state)) {
    event.preventDefault(); $('pause').click();
  }
}, { signal: events.signal });
document.addEventListener('visibilitychange', () => {
  if (document.hidden && !engine.busy && !retryOperation && ['PLAYING', 'ANSWER_FEEDBACK'].includes(engine.view?.state)) $('pause').click();
  else if (!document.hidden && sessionId && !engine.busy && !retryOperation && !terminal(engine.view?.state)) void run(async () => accept(await api.state(sessionId)));
}, { signal: events.signal });
window.addEventListener('pagehide', () => { disposed = true; timer.stop(); events.abort(); }, { once: true });
window.addEventListener('pageshow', event => { if (event.persisted) location.reload(); });
links();
void run(async () => {
  if (!setId || !/^\d+$/.test(setId)) throw new Error('Đường dẫn thiếu mã bộ thẻ hợp lệ.');
  loadSavedMode();
  loadSavedTimeLimit();
  options = await api.options(modeName());
  // The remembered mode may have no usable card in this set: fall back to the closest one that has.
  if (!validMode(mode)) { mode = nearestMode(mode); options = await api.options(modeName()); }
  renderModePicker(); text('set-title', options.title); optionSummary();
  if (location.pathname.endsWith('speed-recall-history.html')) await loadHistory();
  else if (sessionId) accept(await api.state(sessionId));
  else if (autoStart) {
    const count = available();
    if (!count) throw new Error('Bộ thẻ này chưa có thẻ hợp lệ để luyện phản xạ.');
    accept(await api.create({ requestId: crypto.randomUUID(), filter: 'ALL', count, autoSubmit: false, mode: modeName(),
      timeLimitSeconds: timeLimitSeconds() }));
  } else section('setup');
});
