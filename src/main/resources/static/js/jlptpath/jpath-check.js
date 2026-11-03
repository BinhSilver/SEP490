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
 *                                    "⏱ Tắt" while a timed question is open
 *   boardReduce(state, ev)           PICK_LEFT | PICK_RIGHT → {request:{boardId,leftId,rightId}} once both sides are
 *                                    picked; PICK_RESULT (wrong → flash + errors, selection cleared; right → matched);
 *                                    PICK_FAIL; FLASH_END. A matched left or a pending pick sends nothing.
 *   answerReduce(state, ev)          CHOOSE (FILL_TEXT: trim + NFKC, empty ignored) / TIMEOUT (value null) →
 *                                    {request:{questionId,value,elapsedMs}}; nothing while locked or pending;
 *                                    RESULT, FAIL; TIMER_OFF → {save:{grammarTimerOn|quizTimerOn:false}}
 *   orderReduce(state, ev)           TAP_PIECE | TAP_PLACED → {value:'p3,p1'}
 *   questionState(state, q), questionHtml(q, qState, display)
 *                                    PAIR_BOARD, MCQ, AUDIO_MCQ (▶), ORDER, FILL_TEXT; results jp-ok / jp-bad,
 *                                    explanation, compare (.jp-compare-col ×2)
 *   timerRemaining(timerSec, startedMs, nowMs), timerActive(check, state), advance(state, now), errorHtml(err)
 *   mountPage()                      mounts #jpathCheckRoot[data-check-token]
 *   mountInline(el, token, {onDone}) → Promise<JpathCheckResult>
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
    TOPIC_KNOWN: 'Tôi biết chủ đề này', FREE_QUIZ: 'Quiz tự do', CONFUSABLE: 'Phân biệt chữ dễ nhầm',
    KANA_ROW: 'Kiểm hàng chữ', GRAMMAR_PRACTICE: 'Luyện ngay', GRAMMAR_EXTRA: 'Luyện thêm', GRAMMAR_REVIEW: 'Ôn ngữ pháp'
  });
  var DIRECTION_LABEL = { JA_VI: 'NHẬT → VIỆT · CHỌN NGHĨA ĐÚNG', VI_JA: 'VIỆT → NHẬT · CHỌN TỪ ĐÚNG' };
  var MSG_NOT_FOUND = 'Không tìm thấy bài kiểm tra này.';
  var MSG_LOAD_FAILED = 'Chưa tải được bài kiểm tra. Thử lại sau.';
  var MSG_SEND_FAILED = 'Chưa gửi được câu trả lời. Hãy thử lại.';
  var BOARD_HINT = 'Chọn 1 ô trái rồi 1 ô phải. Sai thì nháy đỏ, tính 1 lỗi, chọn lại.';

  function D() { return root && root.JpathDisplay; }
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
      html += '<span class="jp-check__timer" data-part="timer" aria-live="off">⏱ ' + esc(q.timerSec) + ' giây</span>'
        + '<button type="button" class="jp-link-btn jp-check__timer-off" data-action="timer-off">⏱ Tắt</button>';
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
        + (isLeft ? ' lang="ja"' : '') + ' aria-pressed="' + (sel ? 'true' : 'false') + '"' + (matched ? ' disabled' : '')
        + '>' + esc(o.label) + (matched ? ' ✓' : '') + (flash ? ' ✗' : '') + '</button>';
    }
    return '<div class="jp-board" data-board-id="' + esc(bid) + '">'
      + '<div class="jp-board__col" role="group" aria-label="Cột trái">' + (q.board.left || []).map(function (o) { return cell('left', o); }).join('') + '</div>'
      + '<div class="jp-board__col" role="group" aria-label="Cột phải">' + (q.board.right || []).map(function (o) { return cell('right', o); }).join('') + '</div>'
      + '</div><p class="jp-check__hint">' + esc(BOARD_HINT) + '</p>';
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
    var html = '<div class="jp-check__feedback ' + (a.correct ? 'jp-ok' : 'jp-bad') + '" role="status">'
      + (a.correct ? 'Đúng rồi!' : (a.value === null ? 'Hết giờ.' : 'Chưa đúng.')) + (extra || '') + '</div>';
    if (has(a.explanation)) html += '<p class="jp-check__explain">' + esc(a.explanation) + '</p>';
    html += compareHtml(a.compare);
    return html + '<button type="button" class="jp-btn jp-btn--primary jp-check__next" data-action="next">Tiếp →</button>';
  }

  function promptHtml(q) {
    var label = DIRECTION_LABEL[q.direction] || q.instructionVi || '';
    var ja = q.direction !== 'VI_JA';
    var html = '<div class="jp-check__prompt jp-card">';
    if (label) html += '<div class="jp-check__dir">' + esc(label) + '</div>';
    if (q.type === 'AUDIO_MCQ' && q.audioSlot != null) {
      html += '<button type="button" class="jp-check__play" data-action="play-audio" aria-label="Nghe">▶</button>';
    }
    if (has(q.prompt)) {
      html += '<div class="jp-check__text"' + (ja ? ' lang="ja"' : '') + '>' + (ja ? jaHtml(q.prompt, q.promptFurigana) : esc(q.prompt)) + '</div>';
    }
    return html + '</div>';
  }

  function optionsHtml(q, qs) {
    var a = qs.answer;
    return '<div class="jp-check__options" role="group" aria-label="Các lựa chọn">' + (q.options || []).map(function (o, i) {
      var cls = 'jp-check-opt';
      if (a && a.correctValue === o.id) cls += ' jp-ok';
      else if (a && a.value === o.id) cls += ' jp-bad';
      return '<button type="button" class="' + cls + '" data-option="' + esc(o.id) + '"'
        + (q.direction === 'JA_VI' ? '' : ' lang="ja"') + (a || qs.pending ? ' disabled' : '') + '>'
        + (i < 9 ? '<span class="jp-key" aria-hidden="true">' + (i + 1) + '</span> ' : '') + esc(o.label) + '</button>';
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

  function questionHtml(q, qs, display) {
    var st = qs || {};
    if (!q) return '';
    var html = '<section class="jp-check__q" data-qid="' + esc(q.id) + '" data-type="' + esc(q.type) + '">';
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
    var env = envOf(options);
    var check = null;
    var state = null;
    var display = {};
    var ticker = null;
    var resolveDone;
    var done = new Promise(function (r) { resolveDone = r; });
    var hooks = mount.hooks;

    el.classList.add('no-inline-dictionary');
    el.setAttribute('aria-busy', 'true');
    el.innerHTML = '<p class="jp-status" role="status">Đang tải bài kiểm tra…</p>';

    function saver() {
      if (!env.saver && D()) env.saver = D().createSaver({ send: function (m, u, b) { return env.api.send(m, u, b); } });
      return env.saver;
    }

    function stopTicker() { if (ticker !== null) { env.clearInterval(ticker); ticker = null; } }

    function draw() {
      if (state.index >= state.questions.length) { stopTicker(); hooks.finish(ctx); return; }
      var q = current(state);
      el.innerHTML = '<div class="jp-check">' + headerHtml(check, state) + questionHtml(q, questionState(state, q), display)
        + '<p class="jp-status" role="status" data-part="status"></p></div>';
      el.setAttribute('aria-busy', 'false');
      if (D()) D().rendered(el, env.doc);
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
      if (t) t.textContent = '⏱ ' + left + ' giây';
      if (left <= 0) sendAnswer({ type: 'TIMEOUT', questionId: q.id, nowMs: env.now() });
    }

    function sendAnswer(ev) {
      var r = answerReduce(state, ev);
      state = r.state;
      if (!r.request) return;
      draw();
      env.api.send('POST', BASE + '/checks/' + encodeURIComponent(token) + '/answers', r.request).then(function (res) {
        state = answerReduce(state, { type: 'RESULT', questionId: r.request.questionId, result: res }).state;
        env.tone(res && res.correct ? 'correct' : 'wrong', display);
        draw();
      }, function () {
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
        state = boardReduce(state, { type: 'PICK_RESULT', boardId: req.boardId, leftId: req.leftId, rightId: req.rightId,
          result: res }).state;
        env.tone(res && res.correct ? 'correct' : 'wrong', display);
        draw();
        if (res && !res.correct) {
          env.setTimeout(function () { state = boardReduce(state, { type: 'FLASH_END', boardId: req.boardId }).state; draw(); }, FLASH_MS);
        } else if (finished(state, current(state))) {
          env.setTimeout(function () { next(); }, BOARD_DONE_MS);
        }
      }, function () {
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
      if (hooks.action) hooks.action(ctx, action, n);
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
        if (o) { ev.preventDefault(); sendAnswer({ type: 'CHOOSE', questionId: q.id, value: o.id, nowMs: env.now() }); }
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
        check = res[0] || {};
        display = (res[1] && res[1].display) || {};
        if (D()) D().applyDisplay(el, display);
        state = initState(check, env.now());
        draw();
      }, function (err) {
        el.setAttribute('aria-busy', 'false');
        el.innerHTML = errorHtml(err);
      });
    }

    var ctx = {
      el: el, token: token, env: env,
      get check() { return check; }, get state() { return state; }, get display() { return display; },
      resolve: function (result) { if (env.onDone) env.onDone(result); resolveDone(result); },
      reload: load
    };

    el.addEventListener('click', function (ev) {
      var n = closestAttr(ev.target, 'data-action', el);
      if (n && n.getAttribute('data-action') === 'reload') { load(); return; }
      if (state) act(ev.target);
    });
    if (env.doc && env.doc.addEventListener) env.doc.addEventListener('keydown', onKey);
    load();
    return done;
  }

  /** Extension points filled further down (submit and result screen). */
  mount.hooks = {
    finish: function (ctx) {
      ctx.el.innerHTML = '<p class="jp-status" role="status">Đã trả lời hết các câu.</p>';
    },
    action: null
  };

  function mountInline(el, token, options) {
    var o = copy(options);
    o.inline = true;
    return mount(el, token, o);
  }

  function mountPage(doc) {
    var d = doc || (root && root.document);
    var el = d && d.getElementById && d.getElementById('jpathCheckRoot');
    if (!el) return null;
    return mount(el, el.getAttribute('data-check-token') || '', {});
  }

  if (root && root.document && typeof module === 'undefined') {
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', function () { mountPage(); });
    else mountPage();
  }

  return { KIND_TITLE: KIND_TITLE, initState: initState, parts: parts, headerHtml: headerHtml, boardReduce: boardReduce,
    answerReduce: answerReduce, orderReduce: orderReduce, questionState: questionState, questionHtml: questionHtml,
    timerRemaining: timerRemaining, timerActive: timerActive, advance: advance, errorHtml: errorHtml,
    timerOffPatch: timerOffPatch, audioUrl: audioUrl, mount: mount, mountPage: mountPage, mountInline: mountInline };
});
