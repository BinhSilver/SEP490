/* [JLAS-W0108] jpath-check.js — the micro-check screen of the JLPT path (/learning/jlpt/check/{token}) and the same
 * check drawn inline (grammar "Luyện ngay").
 *
 * Data: GET /api/learning/jlpt/checks/{token} (JpathCheckView: token, kind, tab, timerOn, issue, questions, answered).
 * The view carries no answer key: a pick goes to POST /checks/{token}/pick, an answer to POST /checks/{token}/answers,
 * and only those replies (after the question is locked) say what was right.
 *
 * window.JpathCheck:
 *   KIND_TITLE                       screen title per check kind (unknown kinds → 'Kiểm tra')
 *   initState(check, now)            rebuilt from answered[] (matchedPairs of boards, locked answers); index = first
 *                                    question not finished (= questions.length when all are)
 *   parts(check)                     each pair board is one part, a run of other questions is one part
 *   headerHtml(check, state)         "Micro-check · Phần 1/2 · Nối từ với nghĩa (5 cặp)" / "… · Câu 3/6", progress,
 *                                    timer toggle while a timed question is open
 *   boardReduce(state, ev)           PICK_LEFT | PICK_RIGHT → {request:{boardId,leftId,rightId}} once both sides are
 *                                    picked; PICK_RESULT (wrong → flash + errors, selection cleared; right → matched);
 *                                    PICK_FAIL; FLASH_END. A matched left or a pending pick sends nothing.
 *   answerReduce(state, ev)          CHOOSE (FILL_TEXT: trim + NFKC, empty ignored) / TIMEOUT (value null) →
 *                                    {request:{questionId,value,elapsedMs}}; nothing while locked or pending;
 *                                    RESULT, FAIL; TIMER_OFF → {save:{grammarTimerOn|quizTimerOn:false}}
 *   orderReduce(state, ev)           TAP_PIECE | TAP_PLACED → {value:'p3,p1'}
 *   questionState(state, q), questionHtml(q, qState, display)
 *                                    PAIR_BOARD, MCQ, AUDIO_MCQ, ORDER, FILL_TEXT; results jp-ok / jp-bad,
 *                                    explanation, compare (.jp-compare-col ×2)
 *   timerRemaining(timerSec, startedMs, nowMs), timerActive(check, state), advance(state, now), errorHtml(err)
 *   submitKey(token, storage, uuid)  one key per token in sessionStorage (jpath.submit.<token>), reused on resend
 *   resultHtml(result, check, opts)  score / Đạt / Chưa đạt (≥ 80%), CP, too fast, wrong items, next / retry; group
 *                                    exercise, "Tôi biết chủ đề này" and free quiz have their own texts
 *   reviewWrongHtml(check, state)    wrong answers with the right option, explanation and report target (CHECK_REVIEW)
 *   retryRequest(check) = check.issue; nextHref(result, search): next.url → ?return= → ?session= → /learning
 *   checkPageUrl(token, search)      keeps ?session= and ?return=
 *   When the last question is done the check is submitted (offline: "Chưa nộp được, đang thử lại…" + "Thử lại",
 *   same key); Look up works on the check at every step (owner 04/11).
 *   mountPage()                      mounts #jpathCheckRoot[data-check-token]
 *   mountInline(el, token, {onDone, embedded, onResult}) → Promise<JpathCheckResult>
 *                                    embedded: the host page drives the next step, so the result shows no
 *                                    continue / retry / review-cards buttons and onResult(result) gets it
 * One mount per element: mounting again into el (el.__jpathCheck) tears the previous one down first. Each
 * question's first-seen time is kept in sessionStorage (jpath.qstart.<token>.<qid>) for the timer and elapsedMs.
 * Keys: 1–4 pick an option, Enter goes on. A wrong pick flashes red for 600 ms.
 * Needs jpath-api.js and jpath-display.js; uses jpath-furigana.js for prompts when loaded.
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathCheck = api;
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var BASE = '/api/learning/jlpt';
  var FLASH_MS = 600;
  var BOARD_DONE_MS = 450;
  var KIND_TITLE = Object.freeze({
    MICRO_CHECK: 'Micro-check', GROUP_EXERCISE: 'Bài tập nhóm', SKIP_ONE: 'Kiểm 1 câu',
    TOPIC_KNOWN: 'Tôi biết chủ đề này', FREE_QUIZ: 'Quiz tự do', CONFUSABLE: 'Phân biệt dễ nhầm',
    KANA_ROW: 'Kiểm hàng chữ', GRAMMAR_PRACTICE: 'Luyện ngay', GRAMMAR_EXTRA: 'Luyện thêm', GRAMMAR_REVIEW: 'Ôn ngữ pháp',
    WEEK_KNOWN: 'Tôi biết tuần này'
  });
  var DIRECTION_LABEL = { JA_VI: 'NHẬT → VIỆT · CHỌN NGHĨA ĐÚNG', VI_JA: 'VIỆT → NHẬT · CHỌN TỪ ĐÚNG' };
  var MSG_NOT_FOUND = 'Không tìm thấy bài kiểm tra này.';
  var MSG_LOAD_FAILED = 'Chưa tải được bài kiểm tra. Thử lại sau.';
  var MSG_SEND_FAILED = 'Chưa gửi được câu trả lời. Hãy thử lại.';
  var BOARD_HINT = 'Chọn 1 ô trái rồi 1 ô phải. Sai thì nháy đỏ, tính 1 lỗi, chọn lại.';

  function D() { return root && root.JpathDisplay; }
  function icon(name) { return root && root.JpathNav && root.JpathNav.iconHtml ? root.JpathNav.iconHtml(name)
    : '<svg class="jp-i" aria-hidden="true"><use href="/images/icons/jpath-icons.svg#i-' + name + '"></use></svg>'; }
  function esc(v) {
    if (v === null || v === undefined) return '';
    return String(v).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }
  function jaHtml(text, furigana) {
    var F = root && root.JpathFurigana;
    if (furigana && F && typeof F.toRuby === 'function') return F.toRuby(furigana);
    return esc(text);
  }
  function has(v) { return v !== null && v !== undefined && v !== ''; }
  function kindTitle(kind) { return KIND_TITLE[kind] || 'Kiểm tra'; }
  function questionsOf(check) { return (check && check.questions) || []; }
  function isBoard(q) { return !!(q && q.board); }
  function audioUrl(token, slot) {
    return BASE + '/checks/' + encodeURIComponent(String(token || '')) + '/audio/' + encodeURIComponent(String(slot));
  }
  function copy(obj) { var o = {}; Object.keys(obj || {}).forEach(function (k) { o[k] = obj[k]; }); return o; }

  // ---- state ------------------------------------------------------------------------------------------------

  function emptyBoard() { return { matched: {}, errors: 0, selLeft: null, selRight: null, flash: null, pending: false }; }

  function boardComplete(q, b) {
    return !!(b && q.board && Object.keys(b.matched).length >= (q.board.left || []).length);
  }

  function finished(state, q) {
    if (isBoard(q)) return boardComplete(q, state.boards[q.board.boardId]);
    return !!state.answers[q.id];
  }

  function firstOpen(state, from) {
    for (var i = from || 0; i < state.questions.length; i++) if (!finished(state, state.questions[i])) return i;
    return state.questions.length;
  }

  function initState(check, now) {
    var qs = questionsOf(check);
    var byId = {};
    var state = { token: check && check.token, kind: check && check.kind, tab: check && check.tab,
      timerOn: !!(check && check.timerOn), questions: qs, types: {}, boards: {}, answers: {}, pending: {}, placed: {},
      qStartedMs: now || 0, index: 0 };
    qs.forEach(function (q) {
      byId[q.id] = q;
      state.types[q.id] = q.type;
      if (isBoard(q)) state.boards[q.board.boardId] = emptyBoard();
    });
    ((check && check.answered) || []).forEach(function (a) {
      var q = byId[a.questionId];
      if (!q) return;
      if (isBoard(q)) {
        var b = state.boards[q.board.boardId];
        (a.matchedPairs || []).forEach(function (p) { if (p && p.length === 2) b.matched[p[0]] = p[1]; });
        b.errors = a.errors || 0;
      } else {
        state.answers[q.id] = { value: a.value, correct: !!a.correct, correctValue: a.correctValue,
          explanation: a.explanation, compare: null, report: a.report || null };
      }
    });
    state.index = firstOpen(state, 0);
    return state;
  }

  /** Next question not finished after the current one; the clock of the new question starts at now. */
  function advance(state, now) {
    var s = copy(state);
    var i = firstOpen(s, Math.min(s.index + 1, s.questions.length));
    s.index = i < s.questions.length ? i : firstOpen(s, 0);
    s.qStartedMs = now || 0;
    return s;
  }

  function parts(check) {
    var out = [];
    var quiz = null;
    questionsOf(check).forEach(function (q, i) {
      if (isBoard(q)) { quiz = null; out.push({ type: 'BOARD', indexes: [i] }); }
      else {
        if (!quiz) { quiz = { type: 'QUIZ', indexes: [] }; out.push(quiz); }
        quiz.indexes.push(i);
      }
    });
    return out;
  }

  function boardTitle(check, q) {
    if (check.kind === 'KANA_ROW' || check.tab === 'KANA') return 'Nối chữ với romaji';
    if (check.tab === 'KANJI') return /cách đọc/.test(q.instructionVi || '') ? 'Nối chữ với cách đọc' : 'Nối chữ với nghĩa';
    return 'Nối từ với nghĩa';
  }

  function current(state) { return state.questions[state.index] || null; }

  function timerActive(check, state) {
    var q = current(state);
    return !!(state.timerOn && q && !isBoard(q) && q.timerSec && !state.answers[q.id]);
  }

  var START_PREFIX = 'jpath.qstart.';
  var startMemory = {};

  /** First time this question was shown (sessionStorage per token + question), so F5 does not restart its clock. */
  function questionStart(storage, token, questionId, now) {
    var key = START_PREFIX + token + '.' + questionId;
    var v = null;
    var usable = !!storage;
    try { v = storage ? storage.getItem(key) : null; } catch (e) { usable = false; }
    if (!usable) v = startMemory[key];
    var n = Number(v);
    if (v !== null && v !== undefined && isFinite(n)) return n;
    try { if (usable) storage.setItem(key, String(now)); } catch (e) { usable = false; }
    if (!usable) startMemory[key] = now;
    return now;
  }

  function timerRemaining(timerSec, startedMs, nowMs) {
    return Math.max(0, Math.ceil((Number(timerSec) * 1000 - (Number(nowMs) - Number(startedMs))) / 1000));
  }

  function headerHtml(check, state) {
    var qs = questionsOf(check);
    var ps = parts(check);
    var idx = Math.min(state.index, qs.length - 1);
    var q = qs[idx];
    var bits = [kindTitle(check.kind)];
    var pi = -1;
    ps.forEach(function (p, i) { if (p.indexes.indexOf(idx) >= 0) pi = i; });
    if (q && pi >= 0) {
      if (ps.length > 1) bits.push('Phần ' + (pi + 1) + '/' + ps.length);
      if (isBoard(q)) bits.push(boardTitle(check, q) + ' (' + (q.board.left || []).length + ' cặp)');
      else bits.push('Câu ' + (ps[pi].indexes.indexOf(idx) + 1) + '/' + ps[pi].indexes.length);
    }
    var done = 0;
    qs.forEach(function (x) { if (finished(state, x)) done++; });
    var pct = qs.length ? Math.round(100 * done / qs.length) : 0;
    var html = '<div class="jp-check__head"><h1 class="jp-check__title">' + esc(bits.join(' · ')) + '</h1>';
    if (timerActive(check, state)) {
      html += '<span class="jp-check__timer" data-part="timer" aria-live="off">' + icon('timer') + ' ' + esc(q.timerSec) + ' giây</span>'
        + '<button type="button" class="jp-link-btn jp-check__timer-off" data-action="timer-off">' + icon('timer-off') + ' Tắt</button>';
    }
    html += '</div><div class="jp-check__bar" role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="'
      + pct + '"><span style="width:' + pct + '%"></span></div>';
    return html;
  }

  // ---- reducers ---------------------------------------------------------------------------------------------

  function withBoard(state, boardId, fn) {
    var s = copy(state);
    s.boards = copy(state.boards);
    var b = copy(state.boards[boardId] || emptyBoard());
    b.matched = copy(b.matched);
    s.boards[boardId] = b;
    var out = fn(b, s) || {};
    out.state = s;
    return out;
  }

  function rightTaken(b, rightId) {
    return Object.keys(b.matched).some(function (l) { return b.matched[l] === rightId; });
  }

  function boardReduce(state, ev) {
    var e = ev || {};
    if (!state.boards[e.boardId]) return { state: state };
    return withBoard(state, e.boardId, function (b) {
      if (e.type === 'PICK_LEFT' || e.type === 'PICK_RIGHT') {
        if (b.pending) return null;
        if (e.type === 'PICK_LEFT') {
          if (b.matched[e.leftId]) { b.selLeft = null; return null; }
          b.selLeft = b.selLeft === e.leftId ? null : e.leftId;
        } else {
          if (rightTaken(b, e.rightId)) { b.selRight = null; return null; }
          b.selRight = b.selRight === e.rightId ? null : e.rightId;
        }
        b.flash = null;
        if (b.selLeft && b.selRight) {
          b.pending = true;
          return { request: { boardId: e.boardId, leftId: b.selLeft, rightId: b.selRight } };
        }
        return null;
      }
      if (e.type === 'PICK_RESULT') {
        var r = e.result || {};
        b.pending = false;
        b.selLeft = null;
        b.selRight = null;
        if (typeof r.errors === 'number') b.errors = r.errors;
        if (r.correct) b.matched[e.leftId] = e.rightId;
        else b.flash = { leftId: e.leftId, rightId: e.rightId };
        return null;
      }
      if (e.type === 'PICK_FAIL') { b.pending = false; b.selLeft = null; b.selRight = null; return null; }
      if (e.type === 'FLASH_END') { b.flash = null; return null; }
      return null;
    });
  }

  function normFill(v) {
    var s = v === null || v === undefined ? '' : String(v);
    if (typeof s.normalize === 'function') s = s.normalize('NFKC');
    return s.trim();
  }

  function timerOffPatch(tab) {
    if (tab === 'GRAMMAR') return { grammarTimerOn: false };
    if (tab === 'VOCAB' || tab === 'KANJI') return { quizTimerOn: false };
    return null;
  }

  function answerReduce(state, ev) {
    var e = ev || {};
    var s = copy(state);
    if (e.type === 'TIMER_OFF') {
      s.timerOn = false;
      var patch = timerOffPatch(state.tab);
      return patch ? { state: s, save: patch } : { state: s };
    }
    var qid = e.questionId;
    s.pending = copy(state.pending);
    if (e.type === 'CHOOSE' || e.type === 'TIMEOUT') {
      if (state.answers[qid] || Object.prototype.hasOwnProperty.call(state.pending, qid)) return { state: state };
      var value = null;
      if (e.type === 'CHOOSE') {
        value = state.types[qid] === 'FILL_TEXT' ? normFill(e.value) : (has(e.value) ? String(e.value) : '');
        if (!value) return { state: state };
      }
      s.pending[qid] = value;
      return { state: s, request: { questionId: qid, value: value,
        elapsedMs: Math.max(0, Math.round(Number(e.nowMs || 0) - Number(state.qStartedMs || 0))) } };
    }
    if (e.type === 'RESULT') {
      var r = e.result || {};
      s.answers = copy(state.answers);
      s.answers[qid] = { value: state.pending[qid] === undefined ? null : state.pending[qid], correct: !!r.correct,
        correctValue: r.correctValue, explanation: r.explanation, compare: r.compare || null, report: r.report || null };
      delete s.pending[qid];
      return { state: s };
    }
    if (e.type === 'FAIL') { delete s.pending[qid]; return { state: s }; }
    return { state: state };
  }

  function orderReduce(state, ev) {
    var e = ev || {};
    var s = copy(state);
    s.placed = copy(state.placed);
    var list = (state.placed[e.questionId] || []).slice();
    if (!state.answers[e.questionId] && !Object.prototype.hasOwnProperty.call(state.pending, e.questionId)) {
      if (e.type === 'TAP_PIECE' && list.indexOf(e.pieceId) < 0) list.push(e.pieceId);
      if (e.type === 'TAP_PLACED') list = list.filter(function (p) { return p !== e.pieceId; });
    }
    s.placed[e.questionId] = list;
    return { state: s, value: list.join(',') };
  }

  // ---- drawing ----------------------------------------------------------------------------------------------

  function questionState(state, q) {
    return { token: state.token, board: isBoard(q) ? state.boards[q.board.boardId] : null,
      answer: state.answers[q.id] || null, pending: Object.prototype.hasOwnProperty.call(state.pending, q.id),
      placed: state.placed[q.id] || [] };
  }

  function boardHtml(q, b) {
    var st = b || emptyBoard();
    var bid = q.board.boardId;
    function cell(side, o) {
      var isLeft = side === 'left';
      var matched = isLeft ? !!st.matched[o.id] : rightTaken(st, o.id);
      var flash = st.flash && (isLeft ? st.flash.leftId === o.id : st.flash.rightId === o.id);
      var sel = isLeft ? st.selLeft === o.id : st.selRight === o.id;
      var cls = 'jp-pair' + (matched ? ' jp-ok' : '') + (flash ? ' jp-bad' : '') + (sel ? ' jp-sel' : '');
      return '<button type="button" class="' + cls + '" data-board="' + esc(bid) + '" data-' + side + '="' + esc(o.id) + '"'
        + ' aria-pressed="' + (sel ? 'true' : 'false') + '"' + (matched ? ' disabled' : '')
        + '>' + (isLeft ? '<span lang="ja">' + esc(o.label) + '</span>' : esc(o.label)) + (matched ? ' ' + icon('circle-check') : '') + (flash ? ' ' + icon('circle-x') : '') + '</button>';
    }
    return '<div class="jp-board" data-board-id="' + esc(bid) + '" title="' + esc(BOARD_HINT) + '">'
      + '<div class="jp-board__col" role="group" aria-label="Cột trái">' + (q.board.left || []).map(function (o) { return cell('left', o); }).join('') + '</div>'
      + '<div class="jp-board__col" role="group" aria-label="Cột phải">' + (q.board.right || []).map(function (o) { return cell('right', o); }).join('') + '</div>'
      + '</div>';
  }

  function compareHtml(c) {
    if (!c || !c.a || !c.b) return '';
    function col(w) {
      return '<div class="jp-compare-col"><div class="jp-compare__ja" lang="ja">' + esc(w.expression) + '</div>'
        + (has(w.reading) ? '<div class="jp-compare__reading" lang="ja">' + esc(w.reading) + '</div>' : '')
        + (has(w.meaning) ? '<div class="jp-compare__meaning">' + esc(w.meaning) + '</div>' : '') + '</div>';
    }
    return '<div class="jp-compare" aria-label="So sánh">' + col(c.a) + col(c.b) + '</div>';
  }

  function feedbackHtml(a, extra) {
    if (!a) return '';
    var html = '<div class="jp-check__feedback ' + (a.correct ? 'jp-ok' : 'jp-bad') + '">'
      + (a.correct ? 'Đúng rồi!' : (a.value === null ? 'Hết giờ.' : 'Chưa đúng.')) + (extra || '') + '</div>';
    if (has(a.explanation)) html += '<p class="jp-check__explain">' + esc(a.explanation) + '</p>';
    html += compareHtml(a.compare);
    return html + '<button type="button" class="jp-btn jp-btn--primary jp-check__next" data-action="next">Tiếp ' + icon('arrow-right') + '</button>';
  }

  function cleanDirectionLabel(q) {
    var raw = DIRECTION_LABEL[q.direction] || q.instructionVi || '';
    if (!raw) return '';
    if (has(q.prompt)) {
      var p = String(q.prompt).trim();
      if (p && raw.indexOf(p) >= 0) {
        raw = raw.replace(p, '').replace(/[:：·-–—]+s*$/, '').trim();
      }
    }
    return raw;
  }

  function promptHtml(q, omitDir) {
    var label = omitDir ? '' : cleanDirectionLabel(q);
    var ja = q.direction !== 'VI_JA';
    var html = '<div class="jp-check__prompt jp-card">';
    if (label) html += '<div class="jp-check__dir">' + esc(label) + '</div>';
    if (q.type === 'AUDIO_MCQ' && q.audioSlot != null) {
      html += '<button type="button" class="jp-check__play" data-action="play-audio" aria-label="Nghe phát âm">' + icon('volume-2') + '</button>';
    }
    if (has(q.prompt)) {
      var rawLen = String(q.prompt || '').trim().length;
      var sizeCls = rawLen > 12 ? ' jp-check__text--long' : (rawLen > 6 ? ' jp-check__text--mid' : ' jp-check__text--short');
      html += '<div class="jp-check__text' + sizeCls + '"' + (ja ? ' lang="ja"' : '') + '>' + (ja ? jaHtml(q.prompt, q.promptFurigana) : esc(q.prompt)) + '</div>';
    }
    return html + '</div>';
  }

  function optionsHtml(q, qs) {
    var a = qs.answer;
    return '<div class="jp-check__options" role="group" aria-label="Các lựa chọn">' + (q.options || []).map(function (o, i) {
      var cls = 'jp-check-opt';
      if (a && a.correctValue === o.id) cls += ' jp-ok';
      else if (a && a.value === o.id) cls += ' jp-bad';
      // the label alone carries lang="ja", so the furigana toggle can annotate it
      var ja = /[぀-ヿ一-鿿]/.test(String(o.label || ''));
      return '<button type="button" class="' + cls + '" data-option="' + esc(o.id) + '"'
        + (a || qs.pending ? ' disabled' : '') + '>'
        + (i < 9 ? '<span class="jp-key" aria-hidden="true">' + (i + 1) + '</span> ' : '')
        + '<span' + (ja ? ' lang="ja"' : '') + '>' + esc(o.label) + '</span>'
        + (a && a.correctValue === o.id ? ' ' + icon('check') : (a && a.value === o.id ? ' ' + icon('x') : '')) + '</button>';
    }).join('') + '</div>';
  }

  function orderHtml(q, qs) {
    var labels = {};
    (q.options || []).forEach(function (o) { labels[o.id] = o.label; });
    var a = qs.answer;
    var placed = a ? String(a.value || '').split(',').filter(Boolean) : qs.placed;
    var html = '<div class="jp-order__slots' + (a ? (a.correct ? ' jp-ok' : ' jp-bad') : '') + '" lang="ja" aria-label="Câu đang xếp">'
      + (placed.length ? placed.map(function (p) {
        return '<button type="button" class="jp-order__piece" data-placed="' + esc(p) + '"' + (a ? ' disabled' : '') + '>'
          + esc(labels[p]) + '</button>';
      }).join('') : '<span class="jp-order__empty">Chạm các phần bên dưới theo thứ tự.</span>') + '</div>';
    if (!a) {
      html += '<div class="jp-order__pool" lang="ja">' + (q.options || []).map(function (o) {
        return '<button type="button" class="jp-order__piece" data-piece="' + esc(o.id) + '"'
          + (placed.indexOf(o.id) >= 0 ? ' disabled' : '') + '>' + esc(o.label) + '</button>';
      }).join('') + '</div><button type="button" class="jp-btn jp-btn--primary" data-action="submit-order"'
        + (placed.length < (q.options || []).length || qs.pending ? ' disabled' : '') + '>Kiểm tra</button>';
      return html;
    }
    var right = a.correct ? '' : ' Đáp án: <span lang="ja">' + esc(String(a.correctValue || '').split(',')
      .map(function (p) { return labels[p] || ''; }).join(' ')) + '</span>';
    return html + feedbackHtml(a, right);
  }

  function fillHtml(q, qs) {
    var a = qs.answer;
    if (!a) {
      return '<div class="jp-fill"><input type="text" class="jp-input jp-fill__input" data-part="fill" lang="ja"'
        + ' autocomplete="off" autocapitalize="off" spellcheck="false" aria-label="Câu trả lời"' + (qs.pending ? ' disabled' : '')
        + '><button type="button" class="jp-btn jp-btn--primary" data-action="submit-fill"' + (qs.pending ? ' disabled' : '')
        + '>Kiểm tra</button></div>';
    }
    var right = a.correct ? '' : ' Đáp án: <span lang="ja">' + esc(a.correctValue) + '</span>';
    return '<p class="jp-fill__given">Bạn điền: <span lang="ja">' + esc(a.value) + '</span></p>' + feedbackHtml(a, right);
  }

  function checkMascotHtml(q, st) {
    var img = '/images/mascots/kitsu/kitsu-thinking-30.webp';
    var speech = 'Suy nghĩ kỹ nhé!';
    var mood = 'think';
    if (isBoard(q)) {
      var b = st.board || emptyBoard();
      if (b.flash) {
        img = '/images/mascots/kitsu/kitsu-confused-30.webp';
        speech = 'Tiếc quá, chọn lại cặp khác nhé!';
        mood = 'bad';
      } else if (Object.keys(b.matched || {}).length > 0) {
        img = '/images/mascots/kitsu/kitsu-cheer-30.webp';
        speech = 'Đúng rồi, nối tiếp nào!';
        mood = 'ok';
      } else {
        speech = 'Chọn 1 ô trái rồi 1 ô phải nhé!';
      }
    } else if (st.answer) {
      if (st.answer.correct) {
        img = '/images/mascots/kitsu/kitsu-cheer-30.webp';
        speech = 'Đúng rồi, tuyệt lắm!';
        mood = 'ok';
      } else {
        img = '/images/mascots/kitsu/kitsu-confused-30.webp';
        speech = 'Tiếc quá, cố lên ở câu sau nhé!';
        mood = 'bad';
      }
    }
    return '<div class="jp-check__head"><div class="jp-check__mascot jp-check__mascot--' + mood + '" aria-hidden="true">'
      + '<img class="jp-check__mascot-img" src="' + img + '" alt="" width="76" height="76" loading="eager">'
      + '<span class="jp-check__mascot-bubble" data-speech="' + esc(speech) + '"></span></div>'
      + '<span class="jp-check__flag-slot" data-report-slot></span></div>';
  }

  function questionHtml(q, qs, display) {
    var st = qs || {};
    if (!q) return '';
    var html = '<section class="jp-check__q" data-qid="' + esc(q.id) + '" data-type="' + esc(q.type) + '">'
      + checkMascotHtml(q, st);
    if (isBoard(q)) return html + boardHtml(q, st.board) + '</section>';
    html += promptHtml(q);
    if (q.type === 'AUDIO_MCQ' && q.audioSlot != null) {
      html = html.replace('data-action="play-audio"', 'data-action="play-audio" data-audio="' + esc(audioUrl(st.token, q.audioSlot)) + '"');
    }
    if (q.type === 'ORDER') html += orderHtml(q, st);
    else if (q.type === 'FILL_TEXT') html += fillHtml(q, st);
    else if (q.options && q.options.length) html += optionsHtml(q, st) + feedbackHtml(st.answer);
    else html += '<p class="jp-status">Dạng câu này chưa hiển thị được.</p>';
    void display;
    return html + '</section>';
  }

  function errorHtml(err) {
    var e = err || {};
    if (e.code === 'JPATH_CHECK_NOT_FOUND') {
      return '<div class="jp-check__error jp-card" role="alert"><p>' + esc(MSG_NOT_FOUND) + '</p>'
        + '<a class="jp-btn" href="/learning">Về lộ trình học</a></div>';
    }
    return '<div class="jp-check__error jp-card" role="alert"><p>' + esc(MSG_LOAD_FAILED) + '</p>'
      + '<button type="button" class="jp-btn" data-action="reload">Thử lại</button></div>';
  }

  // ---- result (Task 49) -------------------------------------------------------------------------------------

  var SUBMIT_PREFIX = 'jpath.submit.';
  var PASS_PERCENT = 80;
  var SUBMIT_RETRY_MS = [4000, 10000, 30000];
  var memoryKeys = {};

  function randomKey() {
    var c = root && root.crypto;
    if (c && typeof c.randomUUID === 'function') return c.randomUUID();
    var s = '';
    for (var i = 0; i < 32; i++) s += Math.floor(Math.random() * 16).toString(16);
    return s;
  }

  /** One submit key per token (sessionStorage jpath.submit.<token>), so a resend is the same submit. */
  function submitKey(token, storage, uuid) {
    var key = SUBMIT_PREFIX + token;
    var v = null;
    var usable = !!storage;
    try { v = storage ? storage.getItem(key) : null; } catch (e) { usable = false; }
    if (!usable) v = memoryKeys[key] || null;
    if (v) return v;
    v = String((uuid || randomKey)()).slice(0, 40);
    try { if (usable) storage.setItem(key, v); } catch (e) { usable = false; }
    if (!usable) memoryKeys[key] = v;
    return v;
  }

  function safeReturn(v) {
    if (typeof v !== 'string') return '/learning';
    if (!/^\/learning(?:[/?#]|$)/.test(v) || v.indexOf('//') >= 0 || v.indexOf('\\') >= 0) return '/learning';
    return v;
  }

  function searchParams(search) {
    var out = {};
    String(search || '').replace(/^\?/, '').split('&').forEach(function (pair) {
      if (!pair) return;
      var i = pair.indexOf('=');
      var k = i < 0 ? pair : pair.slice(0, i);
      var v = i < 0 ? '' : pair.slice(i + 1);
      try { out[decodeURIComponent(k)] = decodeURIComponent(v.replace(/\+/g, ' ')); } catch (e) { /* skip a broken pair */ }
    });
    return out;
  }

  /** next.url → ?return= (only /learning…) → the study round of ?session= → /learning. */
  function nextHref(result, search) {
    var n = result && result.next;
    if (n && typeof n.url === 'string' && safeReturn(n.url) === n.url) return n.url;
    var p = searchParams(search);
    if (has(p['return'])) return safeReturn(p['return']);
    if (/^\d+$/.test(p.session || '')) return '/learning/jlpt/study?session=' + p.session;
    return '/learning';
  }

  /** The check page of a new token, keeping ?session= and ?return= of the current page. */
  function checkPageUrl(token, search) {
    var p = searchParams(search);
    var q = [];
    if (/^\d+$/.test(p.session || '')) q.push('session=' + p.session);
    if (has(p['return'])) q.push('return=' + encodeURIComponent(safeReturn(p['return'])));
    return '/learning/jlpt/check/' + encodeURIComponent(String(token || '')) + (q.length ? '?' + q.join('&') : '');
  }

  function retryRequest(check) { return copy((check && check.issue) || {}); }

  function wrongLine(result, check) {
    var items = (result && result.wrongItems) || [];
    if (!items.length) return '';
    var kind = check && check.kind;
    var names = items.map(function (w) { return w.expression || w.code || ''; }).join(', ');
    var suffix = '';
    // Free quiz misses go the same way as a micro-check's (Task 38: wrongInCheck(QUIZ); grammar never the notebook).
    if (kind !== 'GROUP_EXERCISE' && kind !== 'TOPIC_KNOWN' && kind !== 'WEEK_KNOWN') {
      var vk = items.some(function (w) { return w.itemType === 'VOCAB' || w.itemType === 'KANJI'; });
      var review = items.some(function (w) { return w.itemType !== 'KANA'; });
      if (vk) suffix = ' → đã vào Ôn lại và Sổ tay';
      else if (review) suffix = ' → đã vào Ôn lại';
    }
    return '<p class="jp-result__wrong">Sai ' + items.length + ': <span lang="ja">' + esc(names) + '</span>' + esc(suffix) + '</p>';
  }

  function celebrateArtHtml(passed) {
    var img = passed === false
      ? '/images/mascots/kitsu/kitsu-encouraging.webp'
      : '/images/mascots/kitsu/kitsu-celebrate-30.webp';
    var speech = passed === false
      ? 'Đừng nản, ôn lại chút là vượt qua ngay!'
      : 'Xuất sắc! Bạn làm rất tốt!';
    return '<div class="jp-celebrate__art' + (passed === false ? ' jp-celebrate__art--retry' : '') + '" aria-hidden="true">'
      + '<div class="jp-celebrate__stage">'
      + '<svg class="jp-celebrate__svg" viewBox="0 0 180 104" fill="none">'
      + '<circle class="jp-celebrate__halo" cx="90" cy="54" r="40"/>'
      + '<g class="jp-celebrate__sparks">'
      + '<path class="jp-celebrate__spark jp-celebrate__spark--1" d="M24 30l3.5-8 3.5 8 8 3.5-8 3.5-3.5 8-3.5-8-8-3.5 8-3.5z"/>'
      + '<path class="jp-celebrate__spark jp-celebrate__spark--2" d="M154 24l3-7 3 7 7 3-7 3-3 7-3-7-7-3 7-3z"/>'
      + '<circle class="jp-celebrate__dot jp-celebrate__dot--1" cx="38" cy="78" r="4"/>'
      + '<circle class="jp-celebrate__dot jp-celebrate__dot--2" cx="144" cy="74" r="4.5"/>'
      + '<rect class="jp-celebrate__confetti jp-celebrate__confetti--1" x="48" y="14" width="10" height="4.5" rx="2.2" transform="rotate(-24 48 14)"/>'
      + '<rect class="jp-celebrate__confetti jp-celebrate__confetti--2" x="122" y="12" width="10" height="4.5" rx="2.2" transform="rotate(28 122 12)"/>'
      + '</g></svg>'
      + '<img class="jp-celebrate__mascot" src="' + img + '" alt="" width="88" height="88" loading="eager">'
      + '</div>'
      + '<span class="jp-celebrate__bubble" data-speech="' + esc(speech) + '"></span>'
      + '</div>';
  }

  // [JLAS-W0108] C3: the weekly test (a FREE_QUIZ with source WEEK) ends with a short summary in place:
  // score, the weak items of this week and one next step (no separate summary page, owner 05/11)
  var TAB_VI = { VOCAB: 'Từ vựng', KANJI: 'Kanji', GRAMMAR: 'Ngữ pháp' };
  function isWeekly(check) { return !!(check && check.issue && check.issue.source === 'WEEK'); }
  function weeklySummaryHtml(r, check, pct) {
    var wrong = (r.wrongItems || []).length;
    var tabName = TAB_VI[check.tab || (check.issue && check.issue.tab)] || '';
    var strong = pct >= PASS_PERCENT;
    var tip = !wrong ? 'Tuần này bạn nắm chắc hết. Cứ giữ nhịp học như hiện tại nhé.'
      : strong ? 'Tuần này khá vững. Ôn nhanh ' + wrong + ' mục sai trong Sổ tay là đủ.'
      : 'Nên ôn lại ' + wrong + ' mục sai trong Sổ tay trước khi học mục mới.';
    return '<div class="jp-result__score' + (strong ? ' jp-ok' : ' jp-bad') + '">' + esc(pct) + '%</div>'
      + '<h2 class="jp-result__title" id="jp-result-title" tabindex="-1">Tổng kết tuần' + (tabName ? ' · ' + esc(tabName) : '')
      + ': đúng ' + esc(r.correct) + '/' + esc(r.total) + ' câu</h2>'
      + '<p class="jp-result__note" data-part="week-tip">' + esc(tip)
      + (wrong ? ' <a href="/learning/jlpt/notebook">Mở Sổ tay</a>' : '') + '</p>';
  }

  function resultHtml(result, check, opts) {
    var r = result || {};
    var kind = check && check.kind;
    var o = opts || {};
    var pct = Number(r.scorePercent) || 0;
    var mood = kind === 'FREE_QUIZ' && isWeekly(check) ? pct >= PASS_PERCENT : r.passed;
    var celCls = mood === false ? ' jp-celebrate jp-result--retry' : ' jp-celebrate jp-result--pass';
    var html = '<section class="jp-result jp-card' + celCls + '" aria-labelledby="jp-result-title">'
      + celebrateArtHtml(mood);
    var tone = r.passed === true ? ' jp-ok' : (r.passed === false ? ' jp-bad' : '');
    if (kind === 'GROUP_EXERCISE') {
      html += '<h2 class="jp-result__title" id="jp-result-title" tabindex="-1">Xong bài tập nhóm. Câu sai sẽ được ôn lại trong lượt này.</h2>'
        + '<p class="jp-result__count">Đúng ' + esc(r.correct) + '/' + esc(r.total) + '</p>';
    } else if (kind === 'TOPIC_KNOWN') {
      var ok = r.passed === true;
      html += '<div class="jp-result__score' + (ok ? ' jp-ok' : '') + '">' + esc(pct) + '%</div>'
        + '<p class="jp-result__title" id="jp-result-title" tabindex="-1">' + (ok
          ? esc('Bạn đúng ' + r.correct + '/' + r.total + ' câu: các từ trả lời đúng đã vào Đã biết, các từ chưa hỏi tới sẽ được ôn sớm để xác nhận.')
          : esc('Bạn đúng ' + r.correct + '/' + r.total + ' câu nên chưa đổi gì; cứ học chủ đề này như bình thường.')) + '</p>';
    } else if (kind === 'WEEK_KNOWN') {
      var wk = r.passed === true;
      html += '<div class="jp-result__score' + (wk ? ' jp-ok' : '') + '">' + esc(pct) + '%</div>'
        + '<p class="jp-result__title" id="jp-result-title" tabindex="-1">' + (wk
          ? esc('Bạn đúng ' + r.correct + '/' + r.total + ' câu: tuần này được bỏ qua. Mục trả lời đúng vào Đã biết, mục chưa hỏi tới sẽ được ôn sớm để xác nhận.')
          : esc('Bạn đúng ' + r.correct + '/' + r.total + ' câu, chưa tới 90% nên tuần này vẫn học như bình thường.')) + '</p>';
    } else if (kind === 'FREE_QUIZ' && isWeekly(check)) {
      html += weeklySummaryHtml(r, check, pct);
    } else if (kind === 'FREE_QUIZ') {
      html += '<div class="jp-result__score">' + esc(pct) + '%</div>'
        + '<p class="jp-result__title" id="jp-result-title" tabindex="-1">Đúng ' + esc(r.correct) + '/' + esc(r.total) + ' câu. Quiz tự do không tính tiến độ và CP.</p>';
    } else {
      html += '<div class="jp-result__score' + tone + '">' + esc(pct) + '%</div><p class="jp-result__title" id="jp-result-title" tabindex="-1">';
      if (r.passed === true) html += '<b>Đạt</b> (cần ≥ ' + PASS_PERCENT + '%)';
      else if (r.passed === false) html += '<b>Chưa đạt</b> (cần ≥ ' + PASS_PERCENT + '%)';
      else html += 'Đúng ' + esc(r.correct) + '/' + esc(r.total);
      if (Number(r.cpAwarded) > 0) html += ' · +' + esc(r.cpAwarded) + ' CP';
      html += '</p>';
    }
    if (r.tooFast) html += '<p class="jp-result__note">Bạn trả lời quá nhanh nên lần này không cộng CP.</p>';
    html += wrongLine(r, check);
    html += '<div class="jp-result__actions">';
    var n = r.next;
    // The server decides: RETRY = a new set is offered; every other next (also a wrong SKIP_ONE kept on its card) continues.
    var failed = !!(n && n.action === 'RETRY');
    if (!o.embedded && n && n.action !== 'RETRY' && !failed && (n.url || !o.inline)) {
      html += '<button type="button" class="jp-btn jp-btn--primary" data-action="continue">' + esc(n.label || 'Tiếp tục') + ' ' + icon('arrow-right') + '</button>';
    }
    if (failed && !o.embedded) {
      html += '<button type="button" class="jp-btn" data-action="review-cards">Xem lại thẻ các mục sai</button>'
        + '<button type="button" class="jp-btn jp-btn--primary" data-action="retry">Làm đề mới</button>';
    }
    if ((r.wrongItems || []).length) {
      html += '<button type="button" class="jp-link-btn" data-action="review-wrong" aria-expanded="' + (o.reviewOpen ? 'true' : 'false')
        + '">Xem lại câu sai</button>';
    }
    if (!n && !failed && !o.inline && !o.embedded) html += '<a class="jp-btn" href="/learning">Về lộ trình học</a>';
    return html + '</div><p class="jp-status" role="status" data-part="result-status"></p></section>';
  }

  function optionLabel(q, id) {
    var o = (q.options || []).filter(function (x) { return x.id === id; })[0];
    return o ? o.label : '';
  }

  /** Wrong answers of this check: question, options (picked = jp-bad, right = jp-ok), explanation and report target. */
  function reviewWrongHtml(check, state) {
    var answered = {};
    ((check && check.answered) || []).forEach(function (a) { answered[a.questionId] = a; });
    var boardNotes = [];
    var html = '';
    questionsOf(check).forEach(function (q) {
      if (isBoard(q)) {
        var b = state && state.boards[q.board.boardId];
        var errors = b ? b.errors : ((answered[q.id] || {}).errors || 0);
        if (errors > 0) boardNotes.push('<p class="jp-review__board-note">Bảng nối: ' + esc(errors) + ' lần chọn sai</p>');
        return;
      }
      var a = (state && state.answers[q.id]) || answered[q.id];
      if (!a || a.correct) return;
      var report = a.report || (answered[q.id] && answered[q.id].report);
      html += '<li class="jp-review__item"' + (report && report.targetType && report.targetCode ? ' data-jpath-report="'
        + esc(JSON.stringify({ targetType: report.targetType, targetCode: report.targetCode, pageContext: 'CHECK_REVIEW' })) + '"' : '') + '>';
      html += '<div class="jp-review__head"><span class="jp-review__dir">' + esc(cleanDirectionLabel(q) || 'Xem lại câu sai') + '</span><span class="jp-review__flag-slot" data-report-slot></span></div>';
      html += promptHtml(q, true).replace(/<button[^>]*data-action="play-audio"[^>]*>[\s\S]*?<\/button>/, '');
      if (q.type === 'ORDER') {
        var pickedOrd = String(a.value || '').split(',').filter(Boolean).map(function (p) { return optionLabel(q, p); }).join(' ') || '—';
        var rightOrd = String(a.correctValue || '').split(',').filter(Boolean).map(function (p) { return optionLabel(q, p); }).join(' ');
        html += '<div class="jp-review__rows">'
          + '<div class="jp-review__row jp-review__row--picked jp-bad"><span class="jp-review__tag">Bạn xếp</span><span class="jp-review__val" lang="ja">' + esc(pickedOrd) + '</span>' + icon('circle-x') + '</div>'
          + '<div class="jp-review__row jp-review__row--answer jp-ok"><span class="jp-review__tag">Đáp án</span><span class="jp-review__val" lang="ja">' + esc(rightOrd) + '</span>' + icon('circle-check') + '</div>'
          + '</div>';
      } else if (q.type === 'FILL_TEXT') {
        html += '<div class="jp-review__rows">'
          + '<div class="jp-review__row jp-review__row--picked jp-bad"><span class="jp-review__tag">Bạn điền</span><span class="jp-review__val" lang="ja">' + esc(a.value || '—') + '</span>' + icon('circle-x') + '</div>'
          + '<div class="jp-review__row jp-review__row--answer jp-ok"><span class="jp-review__tag">Đáp án</span><span class="jp-review__val" lang="ja">' + esc(a.correctValue || '') + '</span>' + icon('circle-check') + '</div>'
          + '</div>';
      } else {
        var pickedLbl = (a.value === null || a.value === undefined) ? 'Hết giờ' : (optionLabel(q, a.value) || String(a.value || '—'));
        var rightLbl = optionLabel(q, a.correctValue) || String(a.correctValue || '');
        html += '<div class="jp-review__rows">'
          + '<div class="jp-review__row jp-review__row--picked jp-bad"><span class="jp-review__tag">Bạn chọn</span><span class="jp-review__val" lang="ja">' + esc(pickedLbl) + '</span>' + icon('circle-x') + '</div>'
          + '<div class="jp-review__row jp-review__row--answer jp-ok"><span class="jp-review__tag">Đáp án</span><span class="jp-review__val" lang="ja">' + esc(rightLbl) + '</span>' + icon('circle-check') + '</div>'
          + '</div>';
        if ((q.options || []).length > 2) {
          html += '<details class="jp-review__more"><summary class="jp-link-btn">Xem cả câu</summary><ul class="jp-review__options">' + (q.options || []).map(function (o) {
            var cls = 'jp-check-opt' + (o.id === a.correctValue ? ' jp-ok' : (o.id === a.value ? ' jp-bad' : ''));
            var oJa = /[\u3040-\u30ff\u4e00-\u9fff]/.test(String(o.label || ''));
            return '<li class="' + cls + '"><span' + (oJa ? ' lang="ja"' : '') + '>' + esc(o.label) + '</span>' + (o.id === a.correctValue ? ' ' + icon('circle-check') : (o.id === a.value ? ' ' + icon('circle-x') : '')) + '</li>';
          }).join('') + '</ul></details>';
        }
      }
      if (has(a.explanation)) html += '<p class="jp-check__explain">' + esc(a.explanation) + '</p>';
      html += '</li>';
    });
    if (!boardNotes.length && !html) return '<p class="jp-status">Không có câu sai.</p>';
    return '<section class="jp-review" aria-label="Câu sai"><h2 class="jp-review__title">Câu sai</h2>'
      + boardNotes.join('')
      + (html ? '<ol class="jp-review__list">' + html + '</ol>' : '')
      + '</section>';
  }

  function wrongCardsHtml(result) {
    var items = (result && result.wrongItems) || [];
    return '<h2 id="jp-dialog-title">Các mục sai</h2><ul class="jp-review__cards">' + items.map(function (w) {
      return '<li class="jp-review__card"><span class="jp-review__ja" lang="ja">' + esc(w.expression || w.code) + '</span>'
        + (has(w.reading) ? '<span class="jp-review__reading" lang="ja">' + esc(w.reading) + '</span>' : '')
        + (has(w.meaning) ? '<span class="jp-review__meaning">' + esc(w.meaning) + '</span>' : '') + '</li>';
    }).join('') + '</ul>';
  }

  // ---- mounting ---------------------------------------------------------------------------------------------

  function envOf(o) {
    var e = o || {};
    var w = root || {};
    return {
      api: e.api || w.JpathApi,
      doc: e.document || w.document,
      now: e.now || function () { return Date.now(); },
      setTimeout: e.setTimeout || function (fn, ms) { return setTimeout(fn, ms); },
      clearTimeout: e.clearTimeout || function (id) { clearTimeout(id); },
      setInterval: e.setInterval || function (fn, ms) { return setInterval(fn, ms); },
      clearInterval: e.clearInterval || function (id) { clearInterval(id); },
      saver: e.saver || null,
      storage: e.storage !== undefined ? e.storage : (function () { try { return w.sessionStorage; } catch (x) { return null; } })(),
      uuid: e.uuid || null,
      location: e.location || w.location,
      navigate: e.navigate || function (url) { w.location.href = url; },
      playAudio: e.playAudio || function (url) { return D() ? D().playAudio(url) : Promise.resolve(false); },
      tone: e.tone || function (kind, display) { return D() ? D().tone(kind, display) : false; },
      onDone: e.onDone || null,
      onResult: typeof e.onResult === 'function' ? e.onResult : null,
      embedded: !!e.embedded,
      inline: !!e.inline
    };
  }

  function closestAttr(target, attr, el) {
    var n = target;
    while (n && n !== el) {
      if (n.getAttribute && n.getAttribute(attr) !== null && n.getAttribute(attr) !== undefined) return n;
      n = n.parentNode;
    }
    return null;
  }

  /** Draws one check into el and plays it through. Resolves with the JpathCheckResult once it is submitted. */
  function mount(el, token, options) {
    if (el.__jpathCheck && typeof el.__jpathCheck.destroy === 'function') el.__jpathCheck.destroy();
    var env = envOf(options);
    var alive = true;
    var abort = typeof AbortController === 'function' ? new AbortController() : null;
    var listenOpts = abort ? { signal: abort.signal } : undefined;
    var focusWanted = null;
    var autoTries = 0;
    var check = null;
    var state = null;
    var display = {};
    var ticker = null;
    var resolveDone;
    var done = new Promise(function (r) { resolveDone = r; });
    var result = null;
    var submitting = false;
    var retryTimer = null;
    var reviewOpen = false;

    el.setAttribute('aria-busy', 'true');
    el.innerHTML = '<p class="jp-status" role="status">Đang tải bài kiểm tra…</p>';

    function saver() {
      if (!env.saver && D()) env.saver = D().createSaver({ send: function (m, u, b) { return env.api.send(m, u, b); } });
      return env.saver;
    }

    function stopTicker() { if (ticker !== null) { env.clearInterval(ticker); ticker = null; } }

    /** A persistent live region next to el (outside the redrawn part), so verdicts are announced. */
    function liveRegion() {
      if (el.__jpathCheckLive) return el.__jpathCheckLive;
      var d = env.doc;
      if (!d || typeof d.createElement !== 'function' || !el.parentNode || !el.parentNode.insertBefore) return null;
      var p = d.createElement('p');
      p.className = 'jp-sr-only';
      p.setAttribute('aria-live', 'polite');
      p.setAttribute('data-jpath-check-live', '');
      el.parentNode.insertBefore(p, el.nextSibling || null);
      el.__jpathCheckLive = p;
      return p;
    }

    function announce(msg) {
      var p = liveRegion();
      if (p) { p.textContent = ''; p.textContent = msg; }
    }

    function focusFirst(selectors) {
      if (!el.querySelector) return;
      for (var i = 0; i < selectors.length; i++) {
        var n = el.querySelector(selectors[i]);
        if (n && typeof n.focus === 'function') { n.focus(); return; }
      }
    }

    /** After a redraw: the same cell/option when still open, else the next open one; a verdict → "Tiếp →". */
    function restoreFocus() {
      var q = current(state);
      if (q && state.answers[q.id]) { focusFirst(['[data-action="next"]']); focusWanted = null; return; }
      if (!focusWanted) return;
      var w = focusWanted;
      focusWanted = null;
      focusFirst(['[' + w.attr + '="' + String(w.value).replace(/["\\]/g, '') + '"]:not([disabled])',
        '[' + w.attr + ']:not([disabled])', '[data-left]:not([disabled])', '[data-right]:not([disabled])',
        '[data-option]:not([disabled])']);
    }

    function teardown() {
      alive = false;
      stopTicker();
      if (retryTimer !== null) { env.clearTimeout(retryTimer); retryTimer = null; }
      if (abort) abort.abort();
      el.removeEventListener && el.removeEventListener('click', onClick);
      if (env.doc && env.doc.removeEventListener) env.doc.removeEventListener('keydown', onKey);
    }

    function stopKeys() {
      if (env.doc && env.doc.removeEventListener) env.doc.removeEventListener('keydown', onKey);
    }

    function draw() {
      if (state.index >= state.questions.length) { stopTicker(); submit(); return; }
      var q = current(state);
      if (q && !finished(state, q)) state.qStartedMs = questionStart(env.storage, token, q.id, state.qStartedMs);
      el.innerHTML = '<div class="jp-check">' + headerHtml(check, state) + questionHtml(q, questionState(state, q), display)
        + '<p class="jp-status" role="status" data-part="status"></p></div>';
      el.setAttribute('aria-busy', 'false');
      if (D()) D().rendered(el, env.doc);
      restoreFocus();
      if (timerActive(check, state)) { if (ticker === null) ticker = env.setInterval(tick, 250); tick(); }
      else stopTicker();
    }

    function status(msg) {
      var p = el.querySelector && el.querySelector('[data-part="status"]');
      if (p) p.textContent = msg;
    }

    function tick() {
      if (!timerActive(check, state)) { stopTicker(); return; }
      var q = current(state);
      var left = timerRemaining(q.timerSec, state.qStartedMs, env.now());
      var t = el.querySelector && el.querySelector('[data-part="timer"]');
      if (t) t.innerHTML = icon('timer') + ' ' + left + ' giây';
      if (left <= 0) sendAnswer({ type: 'TIMEOUT', questionId: q.id, nowMs: env.now() });
    }

    function sendAnswer(ev) {
      var r = answerReduce(state, ev);
      state = r.state;
      if (!r.request) return;
      draw();
      env.api.send('POST', BASE + '/checks/' + encodeURIComponent(token) + '/answers', r.request).then(function (res) {
        if (!alive) return;
        announce(res && res.correct ? 'Đúng rồi!' : (r.request.value === null ? 'Hết giờ.' : 'Chưa đúng.'));
        state = answerReduce(state, { type: 'RESULT', questionId: r.request.questionId, result: res }).state;
        env.tone(res && res.correct ? 'correct' : 'wrong', display);
        draw();
      }, function () {
        if (!alive) return;
        state = answerReduce(state, { type: 'FAIL', questionId: r.request.questionId }).state;
        draw();
        status(MSG_SEND_FAILED);
      });
    }

    function pick(ev) {
      var r = boardReduce(state, ev);
      state = r.state;
      draw();
      if (!r.request) return;
      var req = r.request;
      env.api.send('POST', BASE + '/checks/' + encodeURIComponent(token) + '/pick', req).then(function (res) {
        if (!alive) return;
        announce(res && res.correct ? 'Đúng một cặp.' : 'Sai cặp, chọn lại.');
        state = boardReduce(state, { type: 'PICK_RESULT', boardId: req.boardId, leftId: req.leftId, rightId: req.rightId,
          result: res }).state;
        env.tone(res && res.correct ? 'correct' : 'wrong', display);
        draw();
        if (res && !res.correct) {
          env.setTimeout(function () { if (!alive) return; state = boardReduce(state, { type: 'FLASH_END', boardId: req.boardId }).state; draw(); }, FLASH_MS);
        } else if (finished(state, current(state))) {
          env.setTimeout(function () { if (alive) next(); }, BOARD_DONE_MS);
        }
      }, function () {
        if (!alive) return;
        state = boardReduce(state, { type: 'PICK_FAIL', boardId: req.boardId }).state;
        draw();
        status(MSG_SEND_FAILED);
      });
    }

    function next() {
      var q = current(state);
      if (!q || !finished(state, q)) return;
      state = advance(state, env.now());
      draw();
    }

    function act(target) {
      var q = current(state);
      if (!q) return;
      var n;
      ['data-left', 'data-right', 'data-option', 'data-piece'].some(function (a) {
        var c = closestAttr(target, a, el);
        if (c) focusWanted = { attr: a, value: c.getAttribute(a) };
        return !!c;
      });
      if ((n = closestAttr(target, 'data-left', el))) return pick({ type: 'PICK_LEFT', boardId: n.getAttribute('data-board'), leftId: n.getAttribute('data-left') });
      if ((n = closestAttr(target, 'data-right', el))) return pick({ type: 'PICK_RIGHT', boardId: n.getAttribute('data-board'), rightId: n.getAttribute('data-right') });
      if ((n = closestAttr(target, 'data-option', el))) return sendAnswer({ type: 'CHOOSE', questionId: q.id, value: n.getAttribute('data-option'), nowMs: env.now() });
      if ((n = closestAttr(target, 'data-piece', el))) { state = orderReduce(state, { type: 'TAP_PIECE', questionId: q.id, pieceId: n.getAttribute('data-piece') }).state; return draw(); }
      if ((n = closestAttr(target, 'data-placed', el))) { state = orderReduce(state, { type: 'TAP_PLACED', questionId: q.id, pieceId: n.getAttribute('data-placed') }).state; return draw(); }
      if (!(n = closestAttr(target, 'data-action', el))) return;
      var action = n.getAttribute('data-action');
      if (action === 'next') return next();
      if (action === 'play-audio') return env.playAudio(n.getAttribute('data-audio'));
      if (action === 'submit-order') return sendAnswer({ type: 'CHOOSE', questionId: q.id, value: (state.placed[q.id] || []).join(','), nowMs: env.now() });
      if (action === 'submit-fill') {
        var input = el.querySelector('[data-part="fill"]');
        return sendAnswer({ type: 'CHOOSE', questionId: q.id, value: input ? input.value : '', nowMs: env.now() });
      }
      if (action === 'timer-off') {
        var r = answerReduce(state, { type: 'TIMER_OFF' });
        state = r.state;
        if (r.save && saver()) saver().save(r.save);
        return draw();
      }
    }

    function onKey(ev) {
      if (!state || state.index >= state.questions.length) return;
      if (env.inline && env.doc && el.contains && !el.contains(env.doc.activeElement)) return;
      var t = ev.target;
      var tag = t && t.tagName ? String(t.tagName).toUpperCase() : '';
      var q = current(state);
      if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') {
        if (ev.key === 'Enter' && q && q.type === 'FILL_TEXT' && !state.answers[q.id]) {
          ev.preventDefault();
          sendAnswer({ type: 'CHOOSE', questionId: q.id, value: t.value, nowMs: env.now() });
        }
        return;
      }
      if (/^[1-9]$/.test(ev.key) && q && q.options && q.type !== 'ORDER' && !isBoard(q)) {
        var o = q.options[Number(ev.key) - 1];
        if (o) { ev.preventDefault(); focusWanted = { attr: 'data-option', value: o.id }; sendAnswer({ type: 'CHOOSE', questionId: q.id, value: o.id, nowMs: env.now() }); }
      } else if (ev.key === 'Enter' && q) {
        if (state.answers[q.id]) { ev.preventDefault(); next(); }
        else if (q.type === 'ORDER' && (state.placed[q.id] || []).length === (q.options || []).length) {
          ev.preventDefault();
          sendAnswer({ type: 'CHOOSE', questionId: q.id, value: state.placed[q.id].join(','), nowMs: env.now() });
        }
      }
    }

    function load() {
      el.setAttribute('aria-busy', 'true');
      var settings = env.api.get(BASE + '/settings').then(null, function () { return null; });
      return Promise.all([env.api.get(BASE + '/checks/' + encodeURIComponent(token)), settings]).then(function (res) {
        if (!alive) return;
        check = res[0] || {};
        display = (res[1] && res[1].display) || {};
        if (D()) D().applyDisplay(el, display);
        state = initState(check, env.now());
        draw();
      }, function (err) {
        if (!alive) return;
        el.setAttribute('aria-busy', 'false');
        el.innerHTML = errorHtml(err);
      });
    }

    function failedSoft(err) {
      var e = err || {};
      return e.code === 'NETWORK' || e.status === 0 || e.status === 408 || e.status === 429 || e.status >= 500;
    }

    function submit(manual) {
      if (!alive || result || submitting) return;
      if (manual) autoTries = 0;
      submitting = true;
      if (retryTimer !== null) { env.clearTimeout(retryTimer); retryTimer = null; }
      el.setAttribute('aria-busy', 'true');
      el.innerHTML = '<p class="jp-status" role="status">Đang nộp bài…</p>';
      var body = { submitKey: submitKey(token, env.storage, env.uuid) };
      env.api.send('POST', BASE + '/checks/' + encodeURIComponent(token) + '/submit', body).then(function (res) {
        if (!alive) return;
        submitting = false;
        showResult(res || {});
      }, function (err) {
        if (!alive) return;
        submitting = false;
        el.setAttribute('aria-busy', 'false');
        if (failedSoft(err)) {
          var auto = autoTries < SUBMIT_RETRY_MS.length;
          el.innerHTML = '<div class="jp-check__error jp-card" role="alert"><p>'
            + (auto ? 'Chưa nộp được, đang thử lại…' : 'Chưa nộp được. Kiểm tra kết nối rồi bấm Thử lại.') + '</p>'
            + '<button type="button" class="jp-btn jp-btn--primary" data-action="submit-retry">Thử lại</button></div>';
          if (auto) {
            retryTimer = env.setTimeout(function () { retryTimer = null; submit(); }, SUBMIT_RETRY_MS[autoTries]);
            autoTries++;
          }
        } else if (err && err.code === 'JPATH_CHECK_INCOMPLETE') {
          load();
        } else if (err && err.code === 'JPATH_CHECK_CLOSED') {
          el.innerHTML = '<div class="jp-check__error jp-card" role="alert"><p>Bài kiểm tra này đã đóng.</p>'
            + '<a class="jp-btn" href="/learning">Về lộ trình học</a></div>';
        } else {
          el.innerHTML = errorHtml(err);
        }
      });
    }

    function drawResult() {
      var html = resultHtml(result, check, { inline: env.inline, embedded: env.embedded, reviewOpen: reviewOpen });
      if (reviewOpen) html += reviewWrongHtml(check, state);
      el.innerHTML = '<div class="jp-check jp-check--result">' + html + '</div>';
      if (D()) D().rendered(el, env.doc);
      focusFirst(['#jp-result-title']);
    }

    function showResult(res) {
      result = res;
      stopKeys();
      el.setAttribute('aria-busy', 'false');
      drawResult();
      if (env.onDone) env.onDone(res);
      if (env.onResult) env.onResult(res);
      resolveDone(res);
    }

    function retry(button) {
      if (button) button.disabled = true;
      env.api.send('POST', BASE + '/checks', retryRequest(check)).then(function (view) {
        var next = view && (view.token || view.checkToken);
        if (!alive) return;
        if (!next) throw new Error('no token');
        if (env.inline) {
          mount(el, next, { api: env.api, document: env.doc, onDone: env.onDone, onResult: env.onResult,
            embedded: env.embedded, inline: true, storage: env.storage, uuid: env.uuid, location: env.location,
            navigate: env.navigate });
        } else {
          env.navigate(checkPageUrl(next, env.location && env.location.search));
        }
      }).catch(function (err) {
        if (button) button.disabled = false;
        var p = el.querySelector && el.querySelector('[data-part="result-status"]');
        if (p) p.textContent = (err && err.message) || MSG_LOAD_FAILED;
      });
    }

    function resultAction(n) {
      var action = n.getAttribute('data-action');
      if (action === 'continue') env.navigate(nextHref(result, env.location && env.location.search));
      else if (action === 'review-wrong') { reviewOpen = !reviewOpen; drawResult(); }
      else if (action === 'review-cards') {
        var dlg = root && root.JpathDialog;
        if (dlg && env.doc && env.doc.body) dlg.open(env.doc, wrongCardsHtml(result), n);
        else { reviewOpen = true; drawResult(); }
      } else if (action === 'retry') retry(n);
    }

    function onClick(ev) {
      if (!alive) return;
      var n = closestAttr(ev.target, 'data-action', el);
      var action = n && n.getAttribute('data-action');
      if (action === 'reload') { load(); return; }
      if (action === 'submit-retry') { submit(true); return; }
      if (result) { if (n) resultAction(n); return; }
      if (state) act(ev.target);
    }

    el.addEventListener('click', onClick, listenOpts);
    if (env.doc && env.doc.addEventListener) env.doc.addEventListener('keydown', onKey, listenOpts);
    el.__jpathCheck = { token: token, destroy: teardown };
    load();
    return done;
  }

  function mountInline(el, token, options) {
    var o = copy(options);
    o.inline = true;
    return mount(el, token, o);
  }

  function mountPage(doc) {
    var d = doc || (root && root.document);
    var el = d && d.getElementById && d.getElementById('jpathCheckRoot');
    if (!el) return null;
    if (d && d.querySelector && root && root.location) {
      var exitLink = d.querySelector('[data-check-exit]');
      if (exitLink) {
        var backUrl = nextHref(null, root.location.search);
        exitLink.setAttribute('href', backUrl);
        if (/\/learning\/jlpt\/study/.test(backUrl)) {
          exitLink.textContent = '← Quay lại buổi học';
        }
      }
    }
    return mount(el, el.getAttribute('data-check-token') || '', {});
  }

  if (root && root.document && typeof module === 'undefined') {
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', function () { mountPage(); });
    else mountPage();
  }

  return { KIND_TITLE: KIND_TITLE, initState: initState, parts: parts, headerHtml: headerHtml, boardReduce: boardReduce,
    answerReduce: answerReduce, orderReduce: orderReduce, questionState: questionState, questionHtml: questionHtml,
    timerRemaining: timerRemaining, timerActive: timerActive, advance: advance, errorHtml: errorHtml,
    timerOffPatch: timerOffPatch, audioUrl: audioUrl, questionStart: questionStart, submitKey: submitKey, resultHtml: resultHtml,
    reviewWrongHtml: reviewWrongHtml, retryRequest: retryRequest, nextHref: nextHref, checkPageUrl: checkPageUrl,
    safeReturn: safeReturn, mount: mount, mountPage: mountPage, mountInline: mountInline };
});
