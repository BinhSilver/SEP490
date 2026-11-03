/* [JLAS-W0108] jpath-study.js — the study card screen of the JLPT path (/learning/jlpt/study?session=).
 *
 * Draws one card of GET /api/learning/jlpt/sessions/{id} (JpathSessionView: steps, minutesLeft, current, upcoming;
 * JpathCardView per card). Every text goes through JpathDisplay.esc / JpathFurigana.toRuby.
 *
 * window.JpathStudy:
 *   RATINGS, RATING_LABEL, STEP_LABEL
 *   intervalLabel(rating, days)        FORGOT or < 1 → "< 1 ngày"; < 30 "{d} ngày"; < 365 "{round(d/30)} tháng";
 *                                      else "{1 decimal, comma} năm"
 *   cardHtml(card, display, flipped, state?)
 *                                      the card body; state.retype = {message, verdict} once a RETYPE was answered
 *   ratingButtonsHtml(card, flipped, state?)
 *                                      .jp-rate-bar: NEW 2 buttons, REVIEW "Lật thẻ (Space)" then 4 buttons with the
 *                                      spaced interval, GRAMMAR "Tiếp", RETYPE "Tiếp" after the answer
 *   stepsHtml(steps, minutesLeft, tab) "Ôn 18 ✓ → Từ mới 6/20 → … · ~22 phút còn lại" (split tab: "Từ vựng")
 *   keyAction(key, ctx)                {type: AUDIO|FLIP|RATE|TOGGLE_FURIGANA|SUBMIT, rating?} | null;
 *                                      ctx = {card, flipped, inputFocused, modifier, retypeDone}
 *   controlPatch(display, control, font) → {display, patch}; bindControls(el, {display, saver, onChange})
 *   mountPage(doc)                     wires #jpathStudyRoot
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathStudy = api;
  if (root && root.document && typeof root.document.getElementById === 'function') {
    var start = function () { if (root.document.getElementById('jpathStudyRoot')) api.mountPage(root.document); };
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', start);
    else start();
  }
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var BASE = '/api/learning/jlpt';
  var RATINGS = Object.freeze({ NEW: Object.freeze(['FORGOT', 'REMEMBERED']),
    REVIEW: Object.freeze(['FORGOT', 'HARD', 'GOOD', 'EASY']) });
  var RATING_LABEL = Object.freeze({ FORGOT: 'Chưa nhớ', REMEMBERED: 'Nhớ rồi', HARD: 'Khó', GOOD: 'Nhớ tốt',
    EASY: 'Rất dễ' });
  var STEP_LABEL = Object.freeze({ REVIEW: 'Ôn', VOCAB: 'Từ mới', KANJI: 'Kanji', GRAMMAR: 'Ngữ pháp', KANA: 'Chữ cái',
    SKILL_QUIZ: 'Quiz tự do' });
  var SPLIT_VOCAB_LABEL = 'Từ vựng';
  var CHECK_KINDS = { MICRO_CHECK: true, GROUP_EXERCISE: true };
  var PAGE_CONTEXT = 'STUDY_CARD';

  function D() { return root.JpathDisplay; }
  function F() { return root.JpathFurigana; }
  function esc(v) { return D().esc(v); }
  function has(v) { return v !== null && v !== undefined && v !== ''; }
  function list(v) { return Array.isArray(v) ? v : []; }
  function isCheck(card) { return !!card && !!CHECK_KINDS[card.kind]; }

  // ---- labels ---------------------------------------------------------------------------------------------

  function intervalLabel(rating, days) {
    var d = Number(days);
    if (rating === 'FORGOT' || days === null || days === undefined || isNaN(d) || d < 1) return '< 1 ngày';
    if (d < 30) return Math.round(d) + ' ngày';
    if (d < 365) return Math.round(d / 30) + ' tháng';
    return (Math.round(d / 36.5) / 10).toFixed(1).replace('.', ',') + ' năm';
  }

  // ---- card body ------------------------------------------------------------------------------------------

  function attrJson(obj) { return D().dataJson(obj); }

  function line(cls, html) { return html ? '<div class="' + cls + '">' + html + '</div>' : ''; }

  function examplesHtml(card) {
    var html = '';
    list(card.examples).forEach(function (ex) {
      if (!ex) return;
      var body = F().exampleHtml(ex);
      if (!body) return;
      var report = has(ex.code)
        ? ' data-jpath-report="' + attrJson({ targetType: 'EXAMPLE', targetCode: ex.code, pageContext: PAGE_CONTEXT }) + '"'
        : '';
      html += '<div class="jp-ex"' + report + '>' + body + '</div>';
    });
    return html ? '<div class="jp-study-examples">' + html + '</div>' : '';
  }

  function mnemonicHtml(card) {
    if (!has(card.mnemonic)) return '';
    return '<details' + (card.mnemonicAutoOpen ? ' open' : '') + ' class="jp-mnemonic"><summary>💡 Mẹo nhớ</summary>'
      + '<p>' + esc(card.mnemonic) + '</p></details>';
  }

  function wordListHtml(words, title, cls) {
    var items = list(words).filter(function (w) { return w && has(w.expression); });
    if (!items.length) return '';
    return '<div class="' + cls + '"><div class="jp-study-label">' + esc(title) + '</div><ul>'
      + items.map(function (w) {
        return '<li><span class="jp-ja">' + esc(w.expression) + '</span>'
          + (has(w.reading) ? ' <span class="jp-study-muted">' + esc(w.reading) + '</span>' : '')
          + (has(w.meaning) ? ' — ' + esc(w.meaning) : '')
          + (w.learned ? ' <span class="jp-ok" aria-label="Đã học">✓</span>' : '') + '</li>';
      }).join('') + '</ul></div>';
  }

  function toolsHtml(card, display) {
    var html = '';
    if (has(card.audioUrl)) {
      html += '<button type="button" class="jp-study-icon" data-jp-audio="' + esc(card.audioUrl)
        + '" aria-label="Nghe phát âm">▶</button>';
    }
    if (card.itemType === 'VOCAB' || card.itemType === 'KANJI') html += D().controlsHtml(display);
    return html ? '<div class="jp-study-tools">' + html + '</div>' : '';
  }

  function chipsHtml(text) {
    if (!has(text)) return '';
    return '<div class="jp-study-chips">' + String(text).split(' + ').filter(has).map(function (p) {
      return '<span class="jp-chip">' + esc(p) + '</span>';
    }).join('') + '</div>';
  }

  function kanjiReadingsHtml(k) {
    var all = list(k.readingsOn).concat(list(k.readingsKun)).filter(has);
    var levels = list(k.levelReadings);
    var main = [];
    var other = [];
    all.forEach(function (r) {
      var bold = F().kanjiReadingHtml(r, levels);
      if (bold.indexOf('<b>') === 0) main.push(bold); else other.push(F().kanjiReadingHtml(r, null));
    });
    var html = main.length ? '<div class="jp-study-readings jp-ja">' + main.join('、') + '</div>' : '';
    if (other.length) {
      html += '<details class="jp-study-more"><summary>Xem thêm</summary><div class="jp-ja">' + other.join('、')
        + '</div></details>';
    }
    return html;
  }

  function headChips(card, flippedOrNew) {
    var html = '';
    if (card.notebook && card.kind !== 'NEW') html += '<span class="jp-study-flag jp-study-flag--notebook">Sổ tay điểm yếu</span>';
    if (card.hard && flippedOrNew) html += '<span class="jp-study-flag jp-study-flag--hard">Khó nhớ</span>';
    return html ? '<div class="jp-study-flags">' + html + '</div>' : '';
  }

  function vocabBody(card, flipped) {
    var word = line('jp-study-word jp-ja', esc(card.expression));
    var reading = line('jp-study-reading jp-ja', esc(card.reading));
    var romaji = has(card.romaji) ? '<div class="jp-romaji">' + esc(card.romaji) + '</div>' : '';
    var meaning = line('jp-study-meaning', esc(card.meaning));
    if (card.kind === 'NEW') {
      var html = '';
      if (has(card.meaning)) html += '<div class="jp-study-label">Ý NGHĨA</div>' + meaning;
      if (has(card.topic)) html += '<div class="jp-study-label">CHỦ ĐỀ · ' + esc(String(card.topic).toUpperCase()) + '</div>';
      return html + word + reading + romaji + examplesHtml(card) + mnemonicHtml(card);
    }
    if (card.kind === 'RETYPE') return word + meaning;
    if (!flipped) return word;
    return word + reading + romaji + meaning + examplesHtml(card) + mnemonicHtml(card)
      + (card.hard ? wordListHtml(card.confusables, 'Dễ nhầm với', 'jp-study-confusables') : '');
  }

  function kanjiBody(card, flipped) {
    var k = card.kanji || {};
    var glyph = line('jp-study-glyph jp-kanji', esc(card.expression));
    if (card.kind !== 'NEW' && !flipped) return glyph;
    var html = glyph;
    if (has(k.hanViet)) html += '<div class="jp-study-hanviet">' + esc(String(k.hanViet).toUpperCase()) + '</div>';
    html += line('jp-study-meaning', esc(card.meaning));
    html += kanjiReadingsHtml(k);
    if (k.strokeCount !== null && k.strokeCount !== undefined && !isNaN(Number(k.strokeCount))) {
      html += '<div class="jp-study-muted">' + esc(Number(k.strokeCount)) + ' nét</div>';
    }
    html += wordListHtml(card.compounds, 'Từ ghép', 'jp-study-compounds');
    html += examplesHtml(card) + mnemonicHtml(card);
    if (card.hard) html += wordListHtml(card.confusables, 'Dễ nhầm với', 'jp-study-confusables');
    return html;
  }

  function grammarBody(card) {
    var g = card.grammar || {};
    var html = line('jp-study-word jp-ja', esc(has(g.pattern) ? g.pattern : card.expression));
    html += line('jp-study-meaning', esc(card.meaning));
    html += chipsHtml(g.formation);
    html += line('jp-study-usage', esc(g.usage));
    html += line('jp-study-muted', esc(g.nuance));
    return html + examplesHtml(card) + mnemonicHtml(card);
  }

  function kanaBody(card, flipped) {
    var k = card.kana || {};
    var glyph = line('jp-study-glyph jp-kanji', esc(has(k.kana) ? k.kana : card.expression));
    if (card.kind !== 'NEW' && !flipped) return glyph;
    var romaji = has(k.romaji) ? k.romaji : card.romaji;
    return glyph + line('jp-study-reading', esc(romaji)) + mnemonicHtml(card);
  }

  function retypeHtml(state) {
    var r = state && state.retype;
    var answered = !!r;
    var html = '<form class="jp-retype" data-jp-retype>'
      + '<input class="jp-input jp-ja" type="text" name="typed" autocomplete="off" autocapitalize="off" spellcheck="false"'
      + ' lang="ja" aria-label="Gõ cách đọc"' + (answered ? ' disabled' : '') + ' value="' + esc(r && r.typed) + '">'
      + '<button type="submit" class="jp-btn jp-btn--primary"' + (answered ? ' disabled' : '') + '>Kiểm tra</button>'
      + '</form><p class="jp-retype-msg' + (r && r.verdict ? ' jp-retype-msg--' + esc(r.verdict) : '')
      + '" role="status" aria-live="polite">' + esc(r && r.message) + '</p>';
    return html;
  }

  function cardHtml(card, display, flipped, state) {
    if (!card) return '';
    if (isCheck(card)) {
      return '<div class="jp-study-card jp-study-card--check"><p class="jp-study-muted" role="status">Đang mở bài kiểm tra…</p></div>';
    }
    var type = card.itemType;
    var body;
    if (type === 'KANJI') body = kanjiBody(card, flipped);
    else if (type === 'GRAMMAR') body = grammarBody(card);
    else if (type === 'KANA') body = kanaBody(card, flipped);
    else body = vocabBody(card, flipped);
    if (card.kind === 'RETYPE') body += retypeHtml(state);
    var newOrFlipped = card.kind === 'NEW' || !!flipped;
    var attrs = ' data-jp-code="' + esc(card.code) + '"';
    if (has(card.code) && has(type)) {
      attrs += ' data-jpath-report="' + attrJson({ targetType: type, targetCode: card.code, pageContext: PAGE_CONTEXT }) + '"';
      if (type === 'VOCAB' || type === 'KANJI') attrs += ' data-jpath-writing="' + attrJson({ itemType: type, code: card.code }) + '"';
    }
    var skip = card.kind === 'NEW' && type === 'VOCAB'
      ? '<p class="jp-study-skip">Đã biết từ này? <button type="button" class="jp-link-btn" data-jp-skip>Bỏ qua (kiểm 1 câu)</button></p>'
      : '';
    return '<article class="jp-study-card jp-study-card--' + esc(String(card.kind || '').toLowerCase()) + '"' + attrs + '>'
      + toolsHtml(card, display) + headChips(card, newOrFlipped) + '<div class="jp-study-face">' + body + '</div>'
      + '</article>' + skip;
  }

  // ---- buttons --------------------------------------------------------------------------------------------

  function hint(text) { return '<span class="jp-key-hint">' + esc(text) + '</span>'; }

  function rateButton(rating, index, interval) {
    return '<button type="button" class="jp-rate jp-rate--' + rating.toLowerCase() + '" data-jp-rate="' + rating + '">'
      + '<span class="jp-rate__label">' + esc(RATING_LABEL[rating]) + '</span>'
      + (interval !== null ? '<span class="jp-rate__ivl">' + esc(interval) + '</span>' : '')
      + hint('phím ' + (index + 1)) + '</button>';
  }

  function bar(cls, inner) { return '<div class="jp-rate-bar ' + cls + '">' + inner + '</div>'; }

  function nextButton() {
    return bar('jp-rate-bar--next', '<button type="button" class="jp-rate jp-rate--next" data-jp-next>'
      + '<span class="jp-rate__label">Tiếp</span>' + hint('phím Enter') + '</button>');
  }

  function ratingButtonsHtml(card, flipped, state) {
    if (!card || isCheck(card)) return '';
    if (card.kind === 'RETYPE') return state && state.retype && state.retype.done ? nextButton() : '';
    if (card.itemType === 'GRAMMAR') return nextButton();
    if (card.kind === 'NEW') {
      return bar('jp-rate-bar--two', RATINGS.NEW.map(function (r, i) { return rateButton(r, i, null); }).join(''));
    }
    if (!flipped) {
      return bar('jp-rate-bar--flip', '<button type="button" class="jp-rate jp-rate--flip" data-jp-flip>'
        + '<span class="jp-rate__label">Lật thẻ (Space)</span></button>');
    }
    var days = card.intervalDays || {};
    return bar('jp-rate-bar--four', RATINGS.REVIEW.map(function (r, i) {
      return rateButton(r, i, intervalLabel(r, days[r]));
    }).join(''));
  }

  // ---- step bar -------------------------------------------------------------------------------------------

  function stepsHtml(steps, minutesLeft, tab) {
    var items = list(steps).filter(function (s) { return s && STEP_LABEL[s.key]; });
    var done = 0;
    var total = 0;
    var parts = items.map(function (s) {
      var label = s.key === 'VOCAB' && tab && tab !== 'COMBINED' ? SPLIT_VOCAB_LABEL : STEP_LABEL[s.key];
      var d = Number(s.done) || 0;
      var t = Number(s.total) || 0;
      done += Math.min(d, t);
      total += t;
      var text = s.complete ? label + ' ' + t + ' ✓' : label + ' ' + d + '/' + t;
      return '<span class="jp-step' + (s.complete ? ' jp-step--done' : '') + (s.active ? ' jp-step--active' : '') + '"'
        + (s.active ? ' aria-current="step"' : '') + '>' + esc(text) + '</span>';
    });
    var pct = total > 0 ? Math.round(done * 100 / total) : 0;
    var minutes = Math.max(0, Math.round(Number(minutesLeft) || 0));
    return '<div class="jp-study-steps"><p class="jp-study-steps__line">'
      + parts.join('<span class="jp-step-sep" aria-hidden="true"> → </span>')
      + '<span class="jp-study-steps__left"> · ~' + minutes + ' phút còn lại</span></p>'
      + '<div class="jp-study-progress" role="progressbar" aria-label="Tiến độ lượt học" aria-valuemin="0"'
      + ' aria-valuemax="100" aria-valuenow="' + pct + '"><span style="width:' + pct + '%"></span></div></div>';
  }

  // ---- keys -----------------------------------------------------------------------------------------------

  function keyAction(key, ctx) {
    var c = ctx || {};
    var card = c.card;
    if (!card || isCheck(card) || c.inputFocused || c.modifier) return null;
    if (key === 'f' || key === 'F') return { type: 'TOGGLE_FURIGANA' };
    if (key === 'a' || key === 'A') return has(card.audioUrl) ? { type: 'AUDIO' } : null;
    if (key === 'Enter') {
      if (card.kind === 'RETYPE') return c.retypeDone ? { type: 'SUBMIT' } : null;
      return card.itemType === 'GRAMMAR' ? { type: 'SUBMIT' } : null;
    }
    if (card.itemType === 'GRAMMAR' || card.kind === 'RETYPE') return null;
    if (key === ' ' || key === 'Spacebar') {
      return card.kind === 'REVIEW' && !c.flipped ? { type: 'FLIP' } : null;
    }
    var n = /^[1-4]$/.test(String(key)) ? Number(key) - 1 : -1;
    if (n < 0) return null;
    var choices = card.kind === 'NEW' ? RATINGS.NEW : (c.flipped ? RATINGS.REVIEW : []);
    return n < choices.length ? { type: 'RATE', rating: choices[n] } : null;
  }

  // ---- display controls -----------------------------------------------------------------------------------

  function controlPatch(display, control, font) {
    var d = Object.assign({}, display || {});
    var patch = null;
    if (control === 'furigana') {
      d.furiganaHiddenAll = !d.furiganaHiddenAll;
      patch = { furiganaHiddenAll: d.furiganaHiddenAll };
    } else if (control === 'romaji') {
      d.romajiOn = !D().romajiOn(d);
      patch = { romajiOn: d.romajiOn };
    } else if (control === 'font' && (font === 'S' || font === 'M' || font === 'L')) {
      d.fontSize = font;
      patch = { fontSize: font };
    }
    return { display: d, patch: patch };
  }

  function bindControls(el, options) {
    var o = options || {};
    var state = { display: Object.assign({}, o.display || {}) };
    el.addEventListener('click', function (ev) {
      var t = ev && ev.target;
      var btn = t && typeof t.closest === 'function' ? t.closest('[data-jp-control]') : null;
      if (!btn) return;
      var r = controlPatch(state.display, btn.getAttribute('data-jp-control'), btn.getAttribute('data-jp-font'));
      if (!r.patch) return;
      state.display = r.display;
      if (o.saver) o.saver.save(r.patch);
      if (typeof o.onChange === 'function') o.onChange(Object.assign({}, state.display));
    });
    return {
      get: function () { return Object.assign({}, state.display); },
      set: function (d) { state.display = Object.assign({}, d || {}); }
    };
  }

  // ---- page -----------------------------------------------------------------------------------------------

  function isTyping(doc) {
    var a = doc && doc.activeElement;
    if (!a) return false;
    var tag = String(a.tagName || '').toUpperCase();
    return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || !!a.isContentEditable;
  }

  function mountPage(doc) {
    var d = doc || root.document;
    var el = d && d.getElementById('jpathStudyRoot');
    if (!el) return null;
    var sessionId = Number(el.getAttribute('data-session-id'));
    var page = { el: el, doc: d, sessionId: sessionId, display: {}, session: null, card: null, flipped: false,
      state: {} };
    var saver = D().createSaver({});
    var controls = bindControls(el, { display: page.display, saver: saver, onChange: function (disp) {
      page.display = disp;
      draw(page);
    } });
    page.controls = controls;
    page.saver = saver;
    el.addEventListener('click', function (ev) { onClick(page, ev); });
    d.addEventListener('keydown', function (ev) { onKey(page, ev); });

    var api = root.JpathApi;
    var settings = api.get(BASE + '/settings').then(function (s) { return (s && s.display) || {}; },
      function () { return {}; });
    Promise.all([settings, api.get(BASE + '/sessions/' + sessionId)]).then(function (res) {
      page.display = res[0];
      controls.set(page.display);
      show(page, res[1]);
    }, function (err) {
      el.setAttribute('aria-busy', 'false');
      el.innerHTML = '<p class="jp-alert" role="alert">' + esc((err && err.message) || 'Không tải được lượt học.')
        + '</p><a class="jp-btn" href="/learning">Về lộ trình</a>';
    });
    return page;
  }

  function show(page, session) {
    page.session = session || null;
    page.card = session && session.current ? session.current : null;
    page.flipped = false;
    page.state = {};
    page.shownAt = Date.now();
    draw(page);
  }

  function draw(page) {
    var el = page.el;
    var s = page.session || {};
    el.setAttribute('aria-busy', 'false');
    if (!page.card) {
      el.innerHTML = '<p class="jp-study-muted" role="status">Đã học xong lượt này.</p>';
      return;
    }
    el.innerHTML = stepsHtml(s.steps, s.minutesLeft, s.tab)
      + '<div class="jp-study-main">' + cardHtml(page.card, page.display, page.flipped, page.state) + '</div>'
      + ratingButtonsHtml(page.card, page.flipped, page.state)
      + '<p class="jp-study-status" role="status" aria-live="polite" data-jp-status></p>';
    D().applyDisplay(el, page.display);
    var cardEl = el.querySelector('.jp-study-card');
    if (cardEl) page.furigana = F().bindToggle(cardEl, !!page.display.furiganaHiddenAll);
    D().rendered(el, page.doc);
  }

  function playCurrent(page) {
    if (page.card && page.card.audioUrl) D().playAudio(page.card.audioUrl);
  }

  function toggleFurigana(page) {
    var r = controlPatch(page.display, 'furigana');
    page.display = r.display;
    page.controls.set(page.display);
    page.saver.save(r.patch);
    draw(page);
  }

  function flip(page) {
    if (!page.card || page.card.kind !== 'REVIEW' || page.flipped) return;
    page.flipped = true;
    draw(page);
    var btn = page.el.querySelector('[data-jp-rate]');
    if (btn && typeof btn.focus === 'function') btn.focus();
  }

  function onClick(page, ev) {
    var t = ev.target;
    if (!t || typeof t.closest !== 'function') return;
    if (t.closest('[data-jp-audio]')) { playCurrent(page); return; }
    if (t.closest('[data-jp-flip]')) { flip(page); return; }
    var rate = t.closest('[data-jp-rate]');
    if (rate) { page.onRate && page.onRate(rate.getAttribute('data-jp-rate')); return; }
    if (t.closest('[data-jp-next]')) { page.onNext && page.onNext(); }
  }

  function onKey(page, ev) {
    if (root.JpathDialog && root.JpathDialog.isOpen && root.JpathDialog.isOpen()) return;
    var act = keyAction(ev.key, { card: page.card, flipped: page.flipped, inputFocused: isTyping(page.doc),
      modifier: !!(ev.ctrlKey || ev.metaKey || ev.altKey), retypeDone: !!(page.state.retype && page.state.retype.done) });
    if (!act) return;
    ev.preventDefault();
    if (act.type === 'AUDIO') playCurrent(page);
    else if (act.type === 'FLIP') flip(page);
    else if (act.type === 'TOGGLE_FURIGANA') toggleFurigana(page);
    else if (act.type === 'RATE') { if (page.onRate) page.onRate(act.rating); }
    else if (act.type === 'SUBMIT') { if (page.onNext) page.onNext(); }
  }

  return { RATINGS: RATINGS, RATING_LABEL: RATING_LABEL, STEP_LABEL: STEP_LABEL, intervalLabel: intervalLabel,
    cardHtml: cardHtml, ratingButtonsHtml: ratingButtonsHtml, stepsHtml: stepsHtml, keyAction: keyAction,
    controlPatch: controlPatch, bindControls: bindControls, mountPage: mountPage };
});
