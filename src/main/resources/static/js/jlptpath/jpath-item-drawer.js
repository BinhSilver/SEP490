/* [JLAS-W0108] jpath-item-drawer.js — item detail drawer and free quiz dialog of the JLPT path lists, plus the small
 * helpers the list and lesson modules share (state labels, error text, safe return links).
 *
 * Reads GET /api/learning/jlpt/items/{type}/{code}; writes POST /items/{type}/{code}/known,
 * POST|DELETE /items/{type}/{code}/notebook and POST /checks (FREE_QUIZ). Every text goes through JpathDisplay.esc.
 * Both dialogs open through JpathDialog (appended to <body>), so they get the learner's display classes
 * (JpathDisplay.applyDisplay) and the furigana toggle (JpathFurigana.bindToggle) on their own panel.
 *
 * window.JpathItemDrawer:
 *   STATE_LABEL, stateOf(s), stateChip(s)
 *   messageOf(e, fallback, scope)       scope 'list': a 400 says the filter is invalid; any other scope: a 400 gets the
 *                                       generic action text (the server only says "Bad request")
 *   safeReturn(v), checkUrl(token, returnTo), itemUrl(type, code), readingsHtml(readings, levelReadings)
 *   itemDrawerHtml(detail)              item detail; buttons follow the learner state
 *   freeQuizDialogHtml(tab, ctx), freeQuizRequest(values), readQuizForm(form)
 *   openItem(opts)                      opts = {api, document, type, code, opener, display, navigate, returnTo,
 *                                       rendered, onChange, onError}; Promise<dialog handle | null>
 *   openFreeQuiz(opts)                  opts = {api, document, tab, opener, display, skillDay, units, unit, level,
 *                                       topicKey, navigate, returnTo}; dialog handle | null
 * Needs jpath-display.js, jpath-furigana.js and jpath-dialog.js loaded first.
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathItemDrawer = api;
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var BASE = '/api/learning/jlpt';
  var STATE_LABEL = { NEW: 'Chưa học', LEARNING: 'Đang học', NOTEBOOK: 'Sổ tay', KNOWN: 'Đã biết' };
  var TAB_CODE = { kana: 'KANA', vocab: 'VOCAB', kanji: 'KANJI', grammar: 'GRAMMAR', skills: 'SKILL' };
  var MSG_BAD_FILTER = 'Bộ lọc không hợp lệ. Hãy đổi bộ lọc rồi thử lại.';
  var MSG_ACTION = 'Chưa làm được thao tác này. Thử lại sau.';

  function D() {
    return (root && root.JpathDisplay) || (typeof require === 'function' ? require('./jpath-display.js') : null);
  }
  function F() {
    return (root && root.JpathFurigana) || (typeof require === 'function' ? require('./jpath-furigana.js') : null);
  }
  function DLG() {
    return (root && root.JpathDialog) || (typeof require === 'function' ? require('./jpath-dialog.js') : null);
  }
  function esc(v) { return D().esc(v); }
  function icon(name) {
    var nav = (root && root.JpathNav) || (typeof require === 'function' ? require('./jpath-nav.js') : null);
    return nav && nav.iconHtml ? nav.iconHtml(name) : '';
  }
  function dataJson(o) { return D().dataJson(o); }
  function list(v) { return Array.isArray(v) ? v : []; }
  function has(v) { return v !== null && v !== undefined && v !== ''; }
  function tabCode(tab) {
    var s = String(tab || '');
    return TAB_CODE[s.toLowerCase()] || s.toUpperCase();
  }
  function stateOf(s) { return STATE_LABEL[s] ? s : 'NEW'; }

  function messageOf(e, fallback, scope) {
    if (e && (e.status === 400 || e.code === 'BAD_REQUEST')) return scope === 'list' ? MSG_BAD_FILTER : MSG_ACTION;
    return (e && e.message) || fallback || MSG_ACTION;
  }

  function safeReturn(v) {
    if (typeof v !== 'string') return '/learning';
    if (!/^\/learning(?:[/?#]|$)/.test(v) || v.indexOf('//') >= 0 || v.indexOf('\\') >= 0) return '/learning';
    return v;
  }

  function checkUrl(token, returnTo) {
    return '/learning/jlpt/check/' + encodeURIComponent(String(token || '')) + '?return='
      + encodeURIComponent(safeReturn(returnTo));
  }

  function itemUrl(type, code) {
    return BASE + '/items/' + encodeURIComponent(String(type || '').toLowerCase()) + '/' + encodeURIComponent(code);
  }

  function stateChip(state) {
    var s = stateOf(state);
    return '<span class="jp-state jp-state--' + s.toLowerCase() + '">' + esc(STATE_LABEL[s]) + '</span>';
  }

  function optionHtml(value, label, selected) {
    return '<option value="' + esc(value) + '"' + (selected ? ' selected' : '') + '>' + esc(label) + '</option>';
  }

  function readingsHtml(readings, levelReadings) {
    return list(readings).filter(has).map(function (r) {
      return '<span lang="ja">' + F().kanjiReadingHtml(r, levelReadings) + '</span>';
    }).join('、');
  }

  // ------------------------------------------------------------------------------------------------ drawer html

  function section(label, body) {
    return body ? '<section class="jp-drawer__part"><h3 class="jp-drawer__label">' + esc(label) + '</h3>' + body
      + '</section>' : '';
  }

  function wordListHtml(words) {
    var rows = list(words).map(function (w) {
      var x = w || {};
      return '<li><span class="jp-ja" lang="ja">' + esc(x.expression) + '</span>'
        + (has(x.reading) ? ' <span class="jp-muted" lang="ja">' + esc(x.reading) + '</span>' : '')
        + (has(x.meaning) ? ' · ' + esc(x.meaning) : '') + (x.learned ? ' <span class="jp-ok">' + icon('check') + '</span>' : '') + '</li>';
    }).join('');
    return rows ? '<ul class="jp-word-list">' + rows + '</ul>' : '';
  }

  function factRow(label, value) {
    return value ? '<div class="jp-fact"><dt>' + esc(label) + '</dt><dd>' + value + '</dd></div>' : '';
  }

  function drawerButtons(type, state) {
    var noNotebook = type === 'GRAMMAR' || type === 'KANA';
    if (state === 'NEW') {
      return '<button type="button" class="jp-btn jp-btn--primary" data-action="item-known">Đã biết (kiểm 1 câu)</button>';
    }
    if (state === 'LEARNING') {
      return '<button type="button" class="jp-btn jp-btn--primary" data-action="item-known">Đánh dấu Đã biết</button>'
        + (noNotebook ? '' : '<button type="button" class="jp-btn" data-action="notebook-add">Thêm vào sổ tay</button>');
    }
    if (state === 'NOTEBOOK') {
      return '<button type="button" class="jp-btn" data-action="notebook-remove">Bỏ khỏi sổ tay</button>';
    }
    return '';
  }

  function itemDrawerHtml(detail) {
    var d = detail || {};
    var type = String(d.itemType || 'VOCAB').toUpperCase();
    var state = stateOf(d.state);
    var k = d.kanji || {};
    var g = d.grammar || {};
    var kana = d.kana || {};
    var title = type === 'GRAMMAR' ? (g.pattern || d.expression) : (type === 'KANA' ? (kana.kana || d.expression)
      : d.expression);
    var kanjiInWord = list(d.kanjiInWord);
    var html = '<div class="jp-drawer" data-part="drawer" data-jpath-report="'
      + dataJson({ targetType: type, targetCode: d.code, pageContext: 'LIST' }) + '">';
    html += '<div class="jp-drawer__head">' + stateChip(state) + (d.hard ? ' <span class="jp-badge">Khó nhớ</span>' : '')
      + '<button type="button" class="jp-drawer__x" data-dialog-close aria-label="Đóng">' + icon('x') + '</button></div>';
    html += '<div class="jp-drawer__top"><div class="jp-drawer__id">';
    html += '<h2 class="jp-drawer__title jp-kanji" id="jp-dialog-title" lang="ja" tabindex="-1">' + esc(title) + '</h2>';
    if (has(d.reading) && d.reading !== title) html += '<p class="jp-drawer__reading jp-ja" lang="ja">' + esc(d.reading) + '</p>';
    var romaji = d.romaji || (type === 'KANA' ? kana.romaji : null);
    if (has(romaji)) html += '<p class="jp-romaji">' + esc(romaji) + '</p>';
    if (has(d.meaning)) html += '<p class="jp-drawer__meaning">' + esc(d.meaning) + '</p>';
    var meta = [d.wordType, d.topic].filter(has).join(' · ');
    if (meta) html += '<p class="jp-muted">' + esc(meta) + '</p>';
    if (has(d.audioUrl)) {
      html += '<button type="button" class="jp-btn" data-action="play" data-audio="' + esc(d.audioUrl)
        + '" aria-label="Nghe phát âm">' + icon('volume-2') + ' Nghe</button>';
    }
    html += '</div>';
    var factsHtml = '';
    if (type === 'KANJI') {
      var facts = factRow('Hán Việt', esc([k.hanViet].concat(list(k.hanVietAlt)).filter(has).join(', ').toUpperCase()))
        + factRow('Âm On', readingsHtml(k.readingsOn, k.levelReadings))
        + factRow('Âm Kun', readingsHtml(k.readingsKun, k.levelReadings))
        + factRow('Số nét', has(k.strokeCount) ? esc(k.strokeCount) : '')
        + factRow('Bộ', has(k.radicalNumber) || has(k.radicalName)
          ? esc([k.radicalName, has(k.radicalNumber) ? '(' + k.radicalNumber + ')' : null].filter(has).join(' ')) : '');
      if (facts) factsHtml = '<dl class="jp-facts">' + facts + '</dl>';
    }
    html += factsHtml + '</div><div class="jp-drawer__body">';
    if (type === 'GRAMMAR') {
      html += section('Cấu trúc', has(g.formation) ? '<p lang="ja">' + esc(g.formation) + '</p>' : '')
        + section('Cách dùng', has(g.usage) ? '<p>' + esc(g.usage) + '</p>' : '')
        + section('Sắc thái', has(g.nuance) ? '<p>' + esc(g.nuance) + '</p>' : '');
    }
    html += section('Kanji trong từ', kanjiInWord.length ? '<ul class="jp-word-list">' + kanjiInWord.map(function (c) {
      var x = c || {};
      return '<li><span class="jp-kanji jp-drawer__char" lang="ja">' + esc(x.character) + '</span>'
        + (has(x.hanViet) ? ' ' + esc(String(x.hanViet).toUpperCase()) : '') + (has(x.meaning) ? ' · ' + esc(x.meaning) : '')
        + '</li>';
    }).join('') + '</ul>' : '');
    html += section('Từ có chữ này', wordListHtml(d.compounds));
    var examples = list(d.examples).map(function (e) {
      var ex = F().exampleHtml(e);
      if (!ex) return '';
      return '<li class="jp-example">' + (e && has(e.audioUrl) ? '<button type="button" class="jp-icon-btn" data-action="play" data-audio="'
        + esc(e.audioUrl) + '" aria-label="Nghe câu ví dụ">' + icon('volume-2') + '</button>' : '') + '<div>' + ex + '</div></li>';
    }).filter(Boolean).join('');
    html += section('Ví dụ', examples ? '<ul class="jp-examples">' + examples + '</ul>' : '');
    html += section('Mẹo nhớ', has(d.mnemonic) ? '<p>' + esc(d.mnemonic) + '</p>' : '');
    html += section('Dễ nhầm với', wordListHtml(d.confusables));
    var writable = type === 'KANJI' || type === 'KANA' || (type === 'VOCAB' && kanjiInWord.length > 0);
    if (writable && has(d.code)) {
      html += '<div class="jp-drawer__writing" data-jpath-writing="' + dataJson({ itemType: type, code: d.code }) + '"></div>';
    }
    html += '</div>';
    var buttons = drawerButtons(type, state);
    if (buttons) html += '<div class="jp-drawer__actions">' + buttons + '</div>';
    html += '<p class="jp-alert" role="alert" data-part="drawer-error" hidden></p>';
    return html + '</div>';
  }

  // ------------------------------------------------------------------------------------------------ free quiz html

  function radio(name, value, label, checked, disabled) {
    return '<label class="jp-choice"><input type="radio" name="' + name + '" value="' + esc(value) + '"'
      + (checked ? ' checked' : '') + (disabled ? ' disabled' : '') + '><span>' + esc(label) + '</span></label>';
  }

  function freeQuizDialogHtml(tab, ctx) {
    var c = ctx || {};
    var t = tabCode(tab);
    var grammar = t === 'GRAMMAR';
    var display = c.display || {};
    var timerOn = grammar ? display.grammarTimerOn !== false : !!display.quizTimerOn;
    var html = '<h2 class="jp-dialog__title" id="jp-dialog-title">Luyện quiz</h2>'
      + '<form class="jp-quiz-form" data-part="quiz-form" data-tab="' + esc(t) + '">';
    html += '<fieldset class="jp-fieldset"><legend>Số câu</legend><div class="jp-choices">'
      + [5, 10, 20].map(function (n) { return radio('count', n, n + ' câu', n === 10, n === 5 && !!c.skillDay); }).join('')
      + '</div>' + (c.skillDay ? '<p class="jp-muted">Ngày kỹ năng cần quiz từ 10 câu.</p>' : '') + '</fieldset>';
    if (grammar) {
      html += '<label class="jp-field"><span class="jp-field__label">Bài</span><select class="jp-input" name="unit">'
        + optionHtml('', 'Tất cả bài', !has(c.unit))
        + list(c.units).map(function (u) {
          return optionHtml(u.unitNo, 'Bài ' + u.unitNo, String(u.unitNo) === String(c.unit));
        }).join('') + '</select></label>';
    }
    var sources = grammar ? [['LEARNED', 'Đã học'], ['ALL', 'Tất cả']]
      : [['LEARNED', 'Đã học'], ['NOTEBOOK', 'Sổ tay'], ['ALL', 'Tất cả']];
    html += '<fieldset class="jp-fieldset"><legend>Nguồn câu hỏi</legend><div class="jp-choices">'
      + sources.map(function (s, i) { return radio('source', s[0], s[1], i === 0); }).join('') + '</div></fieldset>';
    if (!grammar) {
      html += '<fieldset class="jp-fieldset"><legend>Chiều hỏi</legend><div class="jp-choices">'
        + radio('direction', 'JA_VI', 'Nhật → Việt', false) + radio('direction', 'VI_JA', 'Việt → Nhật', false)
        + radio('direction', 'MIXED', 'Xen kẽ', true) + '</div></fieldset>';
    }
    html += '<label class="jp-choice"><input type="checkbox" name="timer"' + (timerOn ? ' checked' : '')
      + '><span>Bật đồng hồ</span></label>';
    html += '<p class="jp-alert" role="alert" data-part="quiz-error" hidden></p>'
      + '<button type="submit" class="jp-btn jp-btn--primary" data-action="quiz-start">Bắt đầu</button></form>';
    return html;
  }

  function freeQuizRequest(values) {
    var v = values || {};
    var tab = tabCode(v.tab);
    var grammar = tab === 'GRAMMAR';
    var count = Number(v.count) || 10;
    if (v.skillDay && count < 10) count = 10;
    var req = { kind: 'FREE_QUIZ', tab: tab, level: v.level || null, topicKey: grammar ? null : (v.topicKey || null),
      source: v.source || 'LEARNED', count: count, direction: grammar ? null : (v.direction || 'MIXED'),
      timerOn: !!v.timerOn };
    if (grammar && has(v.unitNo)) req.unitNo = Number(v.unitNo);
    return req;
  }

  function readQuizForm(form) {
    function checked(name) {
      var el = form.querySelector('input[name="' + name + '"]:checked');
      return el ? el.value : null;
    }
    var unit = form.querySelector('select[name="unit"]');
    var timer = form.querySelector('input[name="timer"]');
    return { count: checked('count'), source: checked('source'), direction: checked('direction'),
      unitNo: unit ? unit.value : null, timerOn: !!(timer && timer.checked) };
  }

  // ------------------------------------------------------------------------------------------------ dialogs

  function showError(container, part, message) {
    var el = container && typeof container.querySelector === 'function'
      ? container.querySelector('[data-part="' + part + '"]') : null;
    if (!el) return;
    el.textContent = message;
    el.hidden = false;
  }

  /** The dialog lives in <body>, outside the page's display classes: give its panel the same ones. */
  function decorate(el, display) {
    var d = display || {};
    try { D().applyDisplay(el, d); } catch (e) { /* the panel keeps the defaults */ }
    var f = F();
    if (f && typeof f.bindToggle === 'function') f.bindToggle(el, !!d.furiganaHiddenAll);
  }

  function open(o, html) {
    var dlg = DLG();
    return dlg ? dlg.open(o.document || (root && root.document), html, o.opener) : null;
  }

  function focusFirst(el) {
    if (!el || typeof el.querySelector !== 'function') return;
    var target = el.querySelector('.jp-drawer__actions .jp-btn') || el.querySelector('#jp-dialog-title');
    if (target && typeof target.focus === 'function') target.focus();
  }

  function openItem(opts) {
    var o = opts || {};
    var apiClient = o.api || root.JpathApi;
    var rendered = o.rendered || function (x) { D().rendered(x); };
    var navigate = o.navigate || function (u) { root.location.assign(u); };
    var onError = o.onError || function () { /* nothing to show it in */ };
    return Promise.resolve().then(function () { return apiClient.get(itemUrl(o.type, o.code)); }).then(function (detail) {
      var handle = open(o, itemDrawerHtml(detail));
      if (!handle) return null;
      var panel = handle.el;
      if (panel.classList) panel.classList.add('jp-dialog--drawer');
      decorate(panel, o.display);
      var current = detail || {};
      function redraw() {
        var box = panel.querySelector('[data-part="drawer"]');
        if (box) box.outerHTML = itemDrawerHtml(current);
        rendered(panel);
        focusFirst(panel);
      }
      panel.addEventListener('click', function (ev) {
        var t = ev.target;
        var el = t && typeof t.closest === 'function' ? t.closest('[data-action]') : null;
        if (!el || el.disabled) return;
        var action = el.getAttribute('data-action');
        var url = itemUrl(current.itemType, current.code);
        var call = null;
        if (action === 'play') { D().playAudio(el.getAttribute('data-audio')); return; }
        if (action === 'item-known') {
          call = apiClient.send('POST', url + '/known').then(function (res) {
            if (res && res.checkToken) { navigate(checkUrl(res.checkToken, o.returnTo)); return false; }
            if (res && res.state) current.state = res.state;
            redraw();
            return true;
          });
        } else if (action === 'notebook-add' || action === 'notebook-remove') {
          call = apiClient.send(action === 'notebook-add' ? 'POST' : 'DELETE', url + '/notebook').then(function (res) {
            if (res && res.state) current.state = res.state;
            redraw();
            return true;
          });
        }
        if (!call) return;
        el.disabled = true;
        call.then(function (changed) {
          if (changed && typeof o.onChange === 'function') o.onChange(current);
        }, function (e) {
          el.disabled = false;
          showError(panel, 'drawer-error', messageOf(e));
        });
      });
      rendered(panel);
      return handle;
    }, function (e) { onError(messageOf(e, 'Không tải được mục này.')); return null; });
  }

  function openFreeQuiz(opts) {
    var o = opts || {};
    var apiClient = o.api || root.JpathApi;
    var navigate = o.navigate || function (u) { root.location.assign(u); };
    var handle = open(o, freeQuizDialogHtml(o.tab, { display: o.display, skillDay: !!o.skillDay, units: o.units,
      unit: o.unit }));
    if (!handle) return null;
    decorate(handle.el, o.display);
    var form = handle.el.querySelector('[data-part="quiz-form"]');
    if (!form) return handle;
    form.addEventListener('submit', function (ev) {
      ev.preventDefault();
      var values = readQuizForm(form);
      values.tab = o.tab;
      values.level = o.level;
      values.topicKey = o.topicKey;
      values.skillDay = !!o.skillDay;
      var btn = form.querySelector('[data-action="quiz-start"]');
      if (btn) btn.disabled = true;
      apiClient.send('POST', BASE + '/checks', freeQuizRequest(values)).then(function (res) {
        var token = res && (res.token || res.checkToken);
        if (token) navigate(checkUrl(token, o.returnTo));
      }, function (e) {
        if (btn) btn.disabled = false;
        showError(handle.el, 'quiz-error', messageOf(e));
      });
    });
    return handle;
  }

  return {
    STATE_LABEL: STATE_LABEL, stateOf: stateOf, stateChip: stateChip, messageOf: messageOf, safeReturn: safeReturn,
    checkUrl: checkUrl, itemUrl: itemUrl, readingsHtml: readingsHtml, optionHtml: optionHtml,
    itemDrawerHtml: itemDrawerHtml, freeQuizDialogHtml: freeQuizDialogHtml, freeQuizRequest: freeQuizRequest,
    readQuizForm: readQuizForm, openItem: openItem, openFreeQuiz: openFreeQuiz, showError: showError, decorate: decorate
  };
});
