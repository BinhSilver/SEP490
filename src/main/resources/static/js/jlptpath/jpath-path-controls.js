/* [JLAS-W0108] jpath-path-controls.js — pure renderers of the JLPT path page controls (Task 45): daily sliders,
 * "Hôm nay của bạn" (type A: Gộp / Tách), level preview, pace warnings, session start requests. No DOM access;
 * every text goes through JpathDisplay.esc. window.JpathPathControls (re-exported by JpathPath).
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathPathControls = api;
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var SESSIONS_URL = '/api/learning/jlpt/sessions';
  var TAB_KEY = { kana: 'KANA', vocab: 'VOCAB', kanji: 'KANJI', grammar: 'GRAMMAR', skills: 'SKILL' };
  var TAB_LABEL = { kana: 'Chữ cái', vocab: 'Từ vựng', kanji: 'Kanji', grammar: 'Ngữ pháp', skills: 'Đọc · Nghe' };
  var UNIT = { VOCAB: 'từ', KANJI: 'Kanji', GRAMMAR: 'mẫu' };
  var CODE_LABEL = { KANA: 'Chữ cái', VOCAB: 'Từ vựng', KANJI: 'Kanji', GRAMMAR: 'Ngữ pháp', SKILL: 'Đọc · Nghe' };

  function D() { return root.JpathDisplay; }
  function esc(v) { return D().esc(v); }
  function icon(name) {
    var nav = (root && root.JpathNav) || (typeof require === 'function' ? require('./jpath-nav.js') : null);
    return nav && nav.iconHtml ? nav.iconHtml(name) : '';
  }
  function list(v) { return Array.isArray(v) ? v : []; }

  var SLIDER_CODES = ['VOCAB', 'KANJI', 'GRAMMAR'];
  var REASON = {
    LOW_MINUTES: function (m) { return 'bạn rảnh ' + (Number(m) || 0) + ' phút/ngày, học liền một lượt sẽ gọn hơn.'; },
    EVEN_SKILLS: function () { return '3 kỹ năng Smart Test của bạn khá đều.'; },
    WEAK_SKILL: function () { return 'một kỹ năng Smart Test thấp hơn hẳn, học riêng giúp bạn tập trung phần đó.'; },
    NO_SMART_TEST: function () { return 'bạn bắt đầu từ đầu, học liền một lượt giúp giữ nhịp.'; }
  };

  function code(tab) {
    var s = String(tab || '');
    return TAB_KEY[s.toLowerCase()] || s.toUpperCase();
  }
  function num(v) { return Number(v) || 0; }

  function newDraft(sliders) {
    var s = sliders || {};
    var base = { VOCAB: num(s.vocab), KANJI: num(s.kanji), GRAMMAR: num(s.grammar) };
    return { base: base, values: Object.assign({}, base), dirty: false };
  }

  function changeDraft(draft, tab, value) {
    var d = draft || newDraft(null);
    var values = Object.assign({}, d.values);
    var c = code(tab);
    if (SLIDER_CODES.indexOf(c) >= 0) values[c] = Math.round(num(value));
    var dirty = SLIDER_CODES.some(function (k) { return values[k] !== d.base[k]; });
    return { base: d.base, values: values, dirty: dirty };
  }

  function sliderBody(draft) {
    var v = (draft || newDraft(null)).values;
    return { vocab: v.VOCAB, kanji: v.KANJI, grammar: v.GRAMMAR };
  }

  function minutesLine(sliders) {
    var s = sliders || {};
    var line = 'Khoảng ' + num(s.estimatedMinutes) + ' phút/ngày';
    if (!s.overBudget) return '<p class="jp-minutes" data-part="minutes">' + esc(line) + '</p>';
    return '<p class="jp-minutes jp-warn" data-part="minutes">' + esc(line + ' · nhiều hơn ' + num(s.dailyMinutes)
      + ' phút bạn đã khai') + '</p>';
  }

  function appliesNote(view) {
    var tabs = list(view && view.appliesFromTomorrow).map(function (t) { return CODE_LABEL[t] || ''; }).filter(Boolean);
    if (!tabs.length) return '';
    return '<p class="jp-muted jp-applies">' + esc(tabs.join(', ') + ' áp dụng từ ngày mai vì hôm nay bạn đã bắt đầu học.')
      + '</p>';
  }

  function valueText(c, value) { return value + ' ' + UNIT[c] + ' mỗi ngày'; }

  function sliderHtml(sliders, tab, level, draft) {
    var c = code(tab);
    var s = sliders || {};
    var head = '<section class="jp-card jp-slider" data-tour="slider" aria-labelledby="jp-slider-title">'
      + '<h2 class="jp-section-title" id="jp-slider-title">Nhịp học mỗi ngày</h2>';
    if (c === 'SKILL') return '';
    if (c === 'KANA') return head + '<p class="jp-slider__value">2 hàng mỗi ngày</p></section>';
    if (SLIDER_CODES.indexOf(c) < 0) return '';
    var d = draft || newDraft(s);
    var value = d.values[c];
    var b = (s.bounds || {})[c] || {};
    var locked = num(s.adjustmentsLeft) <= 0;
    var id = 'jp-slider-' + c.toLowerCase();
    var html = head + '<label class="jp-slider__value" for="' + id + '" data-part="slider-value">'
      + esc('Mỗi ngày học ' + value + ' ' + UNIT[c]) + '</label>'
      + '<input type="range" class="jp-slider__range" id="' + id + '" data-slider="' + c + '" aria-valuetext="'
      + esc(valueText(c, value)) + '" min="' + esc(num(b.min))
      + '" max="' + esc(num(b.max)) + '" step="1" value="' + esc(value) + '"' + (locked ? ' disabled' : '') + '>'
      + '<p class="jp-slider__bounds jp-muted" aria-hidden="true"><span>' + esc(num(b.min)) + '</span><span>'
      + esc(num(b.max)) + '</span></p>'
      + '<div data-part="minutes-wrap">' + minutesLine(s) + '</div>'
      + '<p class="jp-slider__left' + (locked ? ' jp-warn' : '') + '">' + esc(locked
        ? 'Đã dùng hết 2 lần chỉnh của cấp ' + (level || '')
        : 'Còn ' + num(s.adjustmentsLeft) + ' lần chỉnh trong cấp ' + (level || '')) + '</p>';
    if (!locked && s.nextChangeCounted === false) html += '<p class="jp-muted">Lần chỉnh này không tính</p>';
    html += appliesNote(s)
      + '<button type="button" class="jp-btn jp-btn--primary" data-action="save-sliders"'
      + (d.dirty && !locked ? '' : ' disabled') + '>Cập nhật lộ trình</button>'
      + '<p class="jp-slider__error jp-alert" role="alert" data-part="slider-error" hidden></p></section>';
    return html;
  }

  function reasonText(reason, m) {
    var f = REASON[reason];
    return f ? f(m) : '';
  }

  // JpathTodayView tabs: {tab, newTarget, newDone, reviewDue, reviewCap, reviewDone, checkPassed, done, required}
  function todayTab(today, tab) {
    return list(today && today.tabs).filter(function (x) { return x && x.tab === tab; })[0] || null;
  }

  function chipsHtml(today) {
    var chips = list(today && today.tabs).filter(function (t) {
      return t && t.tab !== 'SKILL' && num(t.newTarget) > 0;
    }).map(function (t) {
      return '<li class="jp-chip' + (t.done ? ' jp-chip--done' : '') + '">'
        + esc((CODE_LABEL[t.tab] || t.tab) + ' ' + num(t.newDone) + '/' + num(t.newTarget)) + (t.done ? ' ' + icon('check') : '') + '</li>';
    }).join('');
    return chips ? '<ul class="jp-chips" aria-label="Tiến độ hôm nay">' + chips + '</ul>' : '';
  }

  function target(today, tab) {
    var t = todayTab(today, tab);
    return t ? num(t.newTarget) : 0;
  }

  // cards due now over every tab
  function reviewDue(today) {
    return list(today && today.tabs).reduce(function (sum, t) { return sum + num(t && t.reviewDue); }, 0);
  }

  // skill day: a quiz is still needed while the SKILL tab has no passed check (none to quiz → passed from the start)
  function quizRequired(today) {
    var s = todayTab(today, 'SKILL');
    return !!(s && !s.checkPassed);
  }

  // kana phase: the KANA tab's effective target counts rows (0 when none is left)
  function kanaRows(today) {
    return target(today, 'KANA');
  }

  function modeButtonsHtml(today, path, activeTab) {
    var p = path || {};
    var suggested = p.suggestedMode || 'COMBINED';
    var splitTab = activeTab === 'skills' ? 'vocab' : (activeTab || 'vocab');
    var defs = [
      { mode: 'COMBINED', label: 'Học hôm nay (Gộp)',
        desc: 'Một lượt học liền mạch: ôn thẻ đến hạn → từ mới → Kanji mới → ngữ pháp mới.' },
      { mode: 'SPLIT', label: 'Học riêng ' + TAB_LABEL[splitTab] + ' (Tách)',
        desc: 'Học từng phần riêng, bắt đầu với tab ' + TAB_LABEL[splitTab] + '.' }];
    var html = defs.map(function (def) {
      var on = def.mode === suggested;
      var out = '<div class="jp-mode' + (on ? ' jp-suggested' : '') + '">'
        + '<button type="button" class="jp-btn' + (on ? ' jp-btn--primary jp-suggested' : '') + '" data-action="start-mode" data-mode="' + def.mode
        + '"' + (def.mode === 'SPLIT' ? ' data-tab-key="' + esc(splitTab) + '"' : '') + '>' + esc(def.label) + '</button>'
        + (on ? '<span class="jp-mode__tag">Gợi ý cho bạn</span>' : '')
        + '<p class="jp-muted">' + esc(def.desc) + '</p>';
      if (def.mode !== (p.mode || 'COMBINED')) {
        out += '<button type="button" class="jp-link-btn" data-action="default-mode" data-mode="' + def.mode + '">'
          + 'Đặt làm cách học mặc định</button>';
      }
      return out + '</div>';
    }).join('');
    var why = reasonText(p.suggestionReason, p.sliders && p.sliders.dailyMinutes);
    if (why) html += '<p class="jp-muted jp-why">' + esc('Vì sao gợi ý: ' + why) + '</p>';
    return '<div class="jp-modes">' + html + '</div>';
  }

  function todayHtml(today, path, activeTab) {
    if (!today) return '';
    var t = today;
    var html = '<section class="jp-card jp-today" data-tour="today" aria-labelledby="jp-today-title">'
      + '<h2 class="jp-section-title" id="jp-today-title">Hôm nay của bạn</h2>';
    var end = '</section>';
    var missed = t.skillDayMissed ? '<p class="jp-warn">Tuần này bạn còn thiếu ngày kỹ năng.</p>' : '';
    if (t.allDone) {
      return html + '<p class="jp-today__done">' + icon('check') + ' Bạn đã xong phần hôm nay</p>'
        + '<a class="jp-btn" href="/learning/jlpt/done">Xem tổng kết hôm nay</a>' + missed + end;
    }
    if (t.kind === 'REST') {
      return html + '<p>Hôm nay là ngày nghỉ của bạn: không có phần bắt buộc.</p>' + missed + end;
    }
    if (t.kind === 'SKILL') {
      var line = 'Hôm nay là ngày kỹ năng: không học mới, ôn ' + reviewDue(t) + ' thẻ'
        + (quizRequired(t) ? ' + 1 quiz tự do từ 10 câu.' : '.');
      if (t.skillMakeup) line += ' Hôm nay bù ngày kỹ năng đã lỡ.';
      return html + '<p>' + esc(line) + '</p>' + missed
        + '<button type="button" class="jp-btn jp-btn--primary" data-action="start-tab" data-start-tab="skills">Bắt đầu ngày kỹ năng</button>' + end;
    }
    if (t.kind === 'REVIEW_ONLY') {
      return html + '<p>' + esc('Hôm nay là ngày ôn: chỉ ôn ' + reviewDue(t) + ' thẻ, không học mới.') + '</p>' + missed
        + '<button type="button" class="jp-btn jp-btn--primary" data-action="start-mode" data-mode="COMBINED">Bắt đầu ôn</button>' + end;
    }
    var summary = t.kanaPhase
      ? kanaRows(t) + ' hàng chữ cái · ' + target(t, 'VOCAB') + ' từ mới · khoảng ' + num(t.estimatedMinutes) + ' phút'
      : 'Ôn ' + reviewDue(t) + ' thẻ · ' + target(t, 'VOCAB') + ' từ mới · ' + target(t, 'KANJI') + ' Kanji mới · '
        + target(t, 'GRAMMAR') + ' mẫu ngữ pháp · khoảng ' + num(t.estimatedMinutes) + ' phút';
    html += '<p class="jp-today__summary">' + esc(summary) + '</p>' + chipsHtml(t) + missed;
    if (t.reviewDayHint) {
      html += '<div class="jp-today__hint"><p>Bạn đang dồn nhiều thẻ ôn. Hôm nay chỉ ôn, không học mới?</p>'
        + '<button type="button" class="jp-btn" data-action="review-day">Chỉ ôn hôm nay</button></div>';
    }
    return html + modeButtonsHtml(t, path, activeTab)
      + '<p class="jp-alert" role="alert" data-part="today-error" hidden></p>' + end;
  }

  function safeUrl(url) {
    var u = String(url || '');
    return /^\/(?!\/)/.test(u) || /^https?:\/\//i.test(u) ? u : '';
  }

  function levelPreviewHtml(preview, openLevel) {
    var p = preview || {};
    var counts = p.counts || {};
    var countLine = SLIDER_CODES.map(function (c) { return D().fmtNumber(num(counts[c])) + ' ' + UNIT[c]; }).join(' · ');
    var items = list(p.previewItems).slice(0, 5).map(function (it) {
      return '<li class="jp-preview-item"><span lang="ja">' + esc(it.expression) + '</span> <span class="jp-muted" lang="ja">'
        + esc(it.reading) + '</span> <span>' + esc(it.meaning) + '</span></li>';
    }).join('');
    var html = '<h2 class="jp-section-title" id="jp-dialog-title">' + esc((p.level || '') + ' · Đang khóa') + '</h2>'
      + '<p>' + esc(countLine) + '</p>' + (items ? '<ul class="jp-preview-list">' + items + '</ul>' : '')
      + (p.conditionVi ? '<p>' + esc(p.conditionVi) + '</p>' : '')
      + '<p class="jp-muted">' + esc('Tiến độ ' + (openLevel || '') + ' hiện tại: ' + num(p.currentPercent) + '%') + '</p>';
    var mock = safeUrl(p.mockUrl);
    if (mock) html += '<a class="jp-btn jp-btn--primary" href="' + esc(mock) + '">' + esc('Thi thử JLPT ' + (openLevel || '')) + '</a>';
    return html;
  }

  function comingSoonHtml(level) {
    return '<p id="jp-dialog-title">' + esc((level || '') + ' sắp có: cấp này đang được biên soạn.') + '</p>';
  }

  function warningsHtml(warnings) {
    var rows = list(warnings).map(function (w) {
      var action = '';
      if (w.code === 'LATE' || w.code === 'OVER_BUDGET') {
        action = ' <button type="button" class="jp-btn" data-action="rebalance">Cân lại</button>';
      } else if (w.code === 'EXAM_DATE_PASSED') {
        action = ' <a class="jp-btn" href="/learning/jlpt/settings">Đặt ngày thi mới</a>';
      }
      return '<li class="jp-warning"><span class="jp-warn">' + esc(w.messageVi) + '</span>' + action + '</li>';
    }).join('');
    return rows ? '<ul class="jp-warnings" aria-label="Lưu ý về lộ trình">' + rows + '</ul>'
      + '<p class="jp-alert" role="alert" data-part="rebalance-error" hidden></p>' : '';
  }

  function startRequest(kind, tabKey, itemCode) {
    if (kind === 'COMBINED') return { tab: 'COMBINED', mode: 'TODAY' };
    var req = { tab: code(tabKey || 'vocab'), mode: 'TODAY' };
    if (kind === 'ITEM' && itemCode) req.startItemCode = itemCode;
    return req;
  }

  function onStart(req, deps) {
    var d = deps || {};
    var apiClient = d.api || root.JpathApi;
    var go = d.navigate || function (u) { root.location.assign(u); };
    return apiClient.send('POST', SESSIONS_URL, req).then(function (res) {
      var id = res && res.sessionId; // JpathSessionView
      go('/learning/jlpt/study?session=' + encodeURIComponent(id));
      return res;
    });
  }

  function localIso(date) {
    var dt = date || new Date();
    var pad = function (n) { return (n < 10 ? '0' : '') + n; };
    return dt.getFullYear() + '-' + pad(dt.getMonth() + 1) + '-' + pad(dt.getDate());
  }

  function isStale(today, todayIso) {
    return !!(today && today.day && today.day !== todayIso);
  }

  function estimateBody(path, draft) {
    var p = path || {};
    return { openLevel: p.openLevel, targetLevel: p.targetLevel, examDate: p.examDate || null,
      dailyMinutes: num(p.sliders && p.sliders.dailyMinutes), skillDays: list(p.skillDays), restDays: list(p.restDays),
      sliders: sliderBody(draft), kanaTab: !!p.kanaTab, mode: p.mode || 'COMBINED' };
  }

  return { UNIT: UNIT, CODE_LABEL: CODE_LABEL, newDraft: newDraft, changeDraft: changeDraft, sliderBody: sliderBody, valueText: valueText, minutesLine: minutesLine, appliesNote: appliesNote, sliderHtml: sliderHtml, reasonText: reasonText, todayHtml: todayHtml, reviewDue: reviewDue, quizRequired: quizRequired, kanaRows: kanaRows, levelPreviewHtml: levelPreviewHtml, comingSoonHtml: comingSoonHtml, warningsHtml: warningsHtml, startRequest: startRequest, onStart: onStart, localIso: localIso, isStale: isStale, estimateBody: estimateBody };
});
