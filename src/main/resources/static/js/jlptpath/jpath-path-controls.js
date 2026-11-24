/* [JLAS-W0108] jpath-path-controls.js — pure renderers of the JLPT path page controls (Task 45, Round 5):
 * daily sliders, "Hôm nay của bạn" (type A: Gộp / Tách), level preview, pace warnings, session start requests.
 * Keeps full Round 4 structure (calendar stamp, circular progress ring pills per tab, weekly check box, Kitsu mascot)
 * upgraded with Round 5 harmonious per-tab palette variables and Morphicons-style thin rounded SVG icons.
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

  function todayTab(today, tab) {
    return list(today && today.tabs).filter(function (x) { return x && x.tab === tab; })[0] || null;
  }

  function chipsHtml(today) {
    var chips = list(today && today.tabs).filter(function (t) {
      return t && t.tab !== 'SKILL' && num(t.newTarget) > 0;
    }).map(function (t) {
      return '<li class="jp-chip' + (t.done ? ' jp-chip--done' : '') + '" data-pill-tab="' + esc(String(t.tab || '').toLowerCase()) + '">'
        + esc((CODE_LABEL[t.tab] || t.tab) + ' ' + num(t.newDone) + '/' + num(t.newTarget) + (t.tab === 'KANA' ? ' hàng' : ''))
        + (t.done ? ' ' + medalMiniSvg() : '') + '</li>';
    }).join('');
    return chips ? '<ul class="jp-chips" aria-label="Tiến độ hôm nay">' + chips + '</ul>' : '';
  }

  function target(today, tab) {
    var t = todayTab(today, tab);
    return t ? num(t.newTarget) : 0;
  }

  function reviewDue(today) {
    // walkthrough 25/11: "Ôn 131 thẻ" while the session holds 38 — count what today's review will actually ask
    return list(today && today.tabs).reduce(function (sum, t) {
      var due = num(t && t.reviewDue);
      var cap = num(t && t.reviewCap);
      return sum + (cap > 0 ? Math.min(due, Math.max(0, cap - num(t && t.reviewDone))) : due);
    }, 0);
  }

  function quizRequired(today) {
    var s = todayTab(today, 'SKILL');
    return !!(s && !s.checkPassed);
  }

  function kanaRows(today) {
    return target(today, 'KANA');
  }

  /** Something left to do today in the tab {@code key} (new items or due reviews). */
  function hasWork(today, key) {
    var t = todayTab(today, code(key));
    return !!t && !t.done && (num(t.newTarget) > num(t.newDone) || num(t.reviewDue) > 0);
  }

  /**
   * Owner 23/11: the tab "Học riêng … (Tách)" opens: the active tab when it has work today, else the first one that
   * has (kana stage: Chữ cái, not Từ vựng with 0 new words); null when no tab has anything to do.
   */
  function splitTabFor(today, activeTab) {
    var first = activeTab === 'skills' ? 'vocab' : (activeTab || 'vocab');
    if (!today || hasWork(today, first)) return first;
    var order = ['kana', 'vocab', 'kanji', 'grammar'];
    for (var i = 0; i < order.length; i++) if (hasWork(today, order[i])) return order[i];
    return null;
  }

  function modeButtonsHtml(today, path, activeTab) {
    var p = path || {};
    var suggested = p.suggestedMode || 'COMBINED';
    var splitTab = splitTabFor(today, activeTab);
    var defs = [
      { mode: 'COMBINED', label: 'Học hôm nay (Gộp)',
        desc: 'Một lượt học liền mạch: ôn thẻ đến hạn → từ mới → Kanji mới → ngữ pháp mới.' }];
    if (splitTab) {
      defs.push({ mode: 'SPLIT', label: 'Học riêng ' + TAB_LABEL[splitTab] + ' (Tách)',
        desc: 'Học từng phần riêng, bắt đầu với tab ' + TAB_LABEL[splitTab] + '.' });
    }
    var html = defs.map(function (def) {
      var on = def.mode === suggested;
      var out = '<div class="jp-mode' + (on ? ' jp-suggested' : '') + '">'
        + '<button type="button" class="jp-btn' + (on ? ' jp-btn--primary jp-suggested' : '') + '" data-action="start-mode" data-mode="' + def.mode
        + '"' + (def.mode === 'SPLIT' ? ' data-tab-key="' + esc(splitTab) + '"' : '')
        + ' data-tip="' + esc(def.desc) + '" title="' + esc(def.desc) + '">' + esc(def.label) + '</button>'
        + (on ? '<span class="jp-mode__tag">Gợi ý cho bạn</span>' : '');
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

  var STUDY_ROWS = [
    ['VOCAB', 'vocab', 'Từ vựng', 'book-open', 'từ'],
    ['KANJI', 'kanji', 'Kanji', 'pen-line', 'chữ'],
    ['GRAMMAR', 'grammar', 'Ngữ pháp', 'list', 'mẫu']
  ];
  var VI_WEEKDAY_FULL = ['Chủ nhật', 'Thứ 2', 'Thứ 3', 'Thứ 4', 'Thứ 5', 'Thứ 6', 'Thứ 7'];
  var VI_WEEKDAY_SHORT = ['CN', 'Thứ 2', 'Thứ 3', 'Thứ 4', 'Thứ 5', 'Thứ 6', 'Thứ 7'];

  function medalMiniSvg() {
    return '<svg class="jp-morph-icon jp-morph-icon--medal jp-today__medal-svg" viewBox="0 0 20 20" width="16" height="16" fill="none" aria-hidden="true">'
      + '<path d="M7.5 11.5L6 17l3-1.3 1-3.2M12.5 11.5L14 17l-3-1.3-1-3.2" fill="var(--jp-c-peach-500)" stroke="var(--jp-c-peach-900)" stroke-width="1.2" stroke-linejoin="round"/>'
      + '<circle cx="10" cy="8.2" r="4.6" fill="var(--jp-c-peach-100)" stroke="var(--jp-c-peach-900)" stroke-width="1.3"/>'
      + '<circle cx="10" cy="8.2" r="1.8" fill="var(--jp-c-peach-500)"/>'
      + '</svg>';
  }

  function progressRingSvg(doneCount, totalCount, isDone) {
    var tot = Math.max(1, num(totalCount));
    var dn = Math.min(tot, Math.max(0, num(doneCount)));
    var pct = isDone ? 100 : Math.round((dn / tot) * 100);
    var r = 15;
    var c = 2 * Math.PI * r;
    var offset = (c - (pct / 100) * c).toFixed(1);
    var ringCls = 'jp-today__ring' + (isDone ? ' is-done' : (dn > 0 ? ' is-active' : ''));
    var inner = isDone ? medalMiniSvg() : esc(dn + '/' + num(totalCount));
    return '<span class="' + ringCls + '" aria-hidden="true">'
      + '<svg viewBox="0 0 38 38" width="38" height="38">'
      + '<circle class="jp-today__ring-bg" cx="19" cy="19" r="' + r + '" fill="none" stroke-width="3"/>'
      + '<circle class="jp-today__ring-fg" cx="19" cy="19" r="' + r + '" fill="none" stroke-width="3"'
      + ' stroke-dasharray="' + c.toFixed(1) + '" stroke-dashoffset="' + offset + '" stroke-linecap="round"/>'
      + '</svg>'
      + '<span class="jp-today__ring-txt">' + inner + '</span>'
      + '</span>';
  }

  function computeTodayPct(t) {
    if (!t) return 0;
    if (t.allDone) return 100;
    var tabs = list(t.tabs).filter(function (x) { return x && x.tab !== 'SKILL' && num(x.newTarget) > 0; });
    if (!tabs.length) return 0;
    var done = 0;
    var total = 0;
    tabs.forEach(function (x) {
      var tg = num(x.newTarget);
      total += tg;
      done += Math.min(tg, Math.max(0, num(x.newDone)));
    });
    return total > 0 ? Math.max(0, Math.min(100, Math.round((done * 100) / total))) : 0;
  }

  function calendarStampHtml(iso, t) {
    var m = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(iso || ''));
    if (!m) {
      var now = new Date();
      m = ['', String(now.getFullYear()), String(now.getMonth() + 1).padStart(2, '0'), String(now.getDate()).padStart(2, '0')];
    }
    var dowIdx = new Date(Date.UTC(+m[1], +m[2] - 1, +m[3])).getUTCDay();
    var dow = VI_WEEKDAY_SHORT[dowIdx] || '';
    var kanjiDow = ['日', '月', '火', '水', '木', '金', '土'][dowIdx] || '日';
    var monthNum = +m[2];
    var pct = computeTodayPct(t);
    var r = 34;
    var circum = (2 * Math.PI * r).toFixed(1);
    var offset = (circum * (1 - pct / 100)).toFixed(1);
    var tip = VI_WEEKDAY_FULL[dowIdx] + ', ngày ' + m[3] + '/' + m[2] + ' (' + monthNum + '月 ' + kanjiDow + ') · hoàn thành ' + pct + '%';
    return '<div class="jp-today__cal" data-pct="' + pct + '" data-tip="' + esc(tip) + '" aria-label="' + esc(tip) + '">'
      + '<div class="jp-today__cal-water" style="height:' + pct + '%" aria-hidden="true">'
      + '<svg class="jp-today__cal-waves" viewBox="0 0 160 20" preserveAspectRatio="none" aria-hidden="true">'
      + '<path class="jp-today__cal-wave-back" d="M0,10 C20,16 40,4 60,10 C80,16 100,4 120,10 C140,16 150,7 160,10 L160,20 L0,20 Z"></path>'
      + '<path class="jp-today__cal-wave-front" d="M0,11 C25,4 50,17 80,11 C110,5 135,17 160,11 L160,20 L0,20 Z"></path>'
      + '</svg></div>'
      + '<svg class="jp-today__cal-ring" viewBox="0 0 76 76" aria-hidden="true">'
      + '<circle class="jp-today__cal-ring-bg" cx="38" cy="38" r="' + r + '"></circle>'
      + '<circle class="jp-today__cal-ring-fg" cx="38" cy="38" r="' + r + '" stroke-dasharray="' + circum + '" stroke-dashoffset="' + offset + '"></circle>'
      + '</svg>'
      + '<div class="jp-today__cal-inner">'
      + '<strong class="jp-today__cal-day">' + esc(m[3]) + '</strong>'
      + '<span class="jp-today__cal-jp" lang="ja"><span class="jp-today__cal-kanji">' + kanjiDow + '</span></span>'
      + '</div>'
      // owner 10/11 (F31): Kitsu sits by the ring and wraps its tail round it
      + '<img class="jp-today__cal-kitsu" src="/images/mascots/fox/kitsu-cheer-30-128.webp" alt="" width="60" height="60" loading="lazy">'
      + '</div>';
  }

  function chestMiniSvg() {
    return '<svg class="jp-today__chest-svg jp-morph-icon jp-morph-icon--chest" viewBox="0 0 28 28" width="24" height="24" fill="none" aria-hidden="true">'
      + '<path class="jp-chest__lid" d="M5 12C5 8.8 7.8 7 14 7s9 1.8 9 5H5z" fill="var(--jp-c-peach-100)" stroke="var(--jp-c-peach-900)" stroke-width="1.6" stroke-linejoin="round"/>'
      + '<rect class="jp-chest__box" x="5" y="12" width="18" height="10" rx="2.5" fill="var(--jp-c-peach-100)" stroke="var(--jp-c-peach-900)" stroke-width="1.6"/>'
      + '<path d="M5 14.5h18" stroke="var(--jp-c-peach-500)" stroke-width="1.4"/>'
      + '<rect class="jp-chest__clasp" x="12" y="12" width="4" height="4.5" rx="1.1" fill="var(--jp-c-peach-500)" stroke="var(--jp-c-peach-900)" stroke-width="1.3"/>'
      + '</svg>';
  }

  function parseUtcMs(iso) {
    var m = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(iso || ''));
    return m ? Date.UTC(+m[1], +m[2] - 1, +m[3]) : NaN;
  }

  function viShortDate(iso) {
    var m = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(iso || ''));
    if (!m) return '';
    var dow = VI_WEEKDAY_SHORT[new Date(Date.UTC(+m[1], +m[2] - 1, +m[3])).getUTCDay()];
    return dow + ' ' + m[3] + '/' + m[2];
  }

  function todayWeeklyHtml(week, todayIso) {
    var days = list(week).filter(function (d) { return d && d.kind !== 'REST'; });
    var btns = '<div class="jp-today__weekly-btns">' + ['VOCAB', 'KANJI', 'GRAMMAR'].map(function (tk) {
      return '<button type="button" class="jp-btn jp-btn--sm" data-parts-weekly="' + tk + '">'
        + { VOCAB: 'Từ vựng', KANJI: 'Kanji', GRAMMAR: 'Ngữ pháp' }[tk] + '</button>';
    }).join('') + '</div>';
    if (!days.length) {
      return '<div class="jp-today__weekly" data-tip="10 câu mỗi kỹ năng trong 7 ngày qua" title="10 câu mỗi kỹ năng trong 7 ngày qua">'
        + '<span class="jp-today__weekly-icon">' + chestMiniSvg() + '</span>'
        + '<div class="jp-today__weekly-info"><strong>Kiểm tra tuần</strong></div>'
        + btns + '</div>';
    }
    var last = days[days.length - 1];
    var todayEntry = list(week).filter(function (d) { return d && d.today; })[0];
    var curIso = (todayEntry && todayEntry.day) || todayIso || '';
    if (curIso && last && last.day && curIso < last.day) {
      var diffDays = Math.max(1, Math.round((parseUtcMs(last.day) - parseUtcMs(curIso)) / 86400000));
      return '<div class="jp-today__weekly jp-today__weekly--upcoming">'
        + '<span class="jp-today__weekly-icon">' + chestMiniSvg() + '</span>'
        + '<div class="jp-today__weekly-info">'
        + '<strong>Kiểm tra tuần · ' + esc(viShortDate(last.day)) + '</strong>'
        + '<span class="jp-today__weekly-countdown">còn ' + esc(diffDays) + ' ngày</span>'
        + '</div>'
        + '</div>';
    }
    return '<div class="jp-today__weekly jp-today__weekly--due" data-tip="10 câu mỗi kỹ năng đã học trong tuần" title="10 câu mỗi kỹ năng đã học trong tuần">'
      + '<span class="jp-today__weekly-icon">' + chestMiniSvg() + '</span>'
      + '<div class="jp-today__weekly-info"><strong>Kiểm tra tuần · Hôm nay</strong></div>'
      + btns + '</div>';
  }

  function studySummaryText(t) {
    var parts = [];
    var v = target(t, 'VOCAB');
    var k = target(t, 'KANJI');
    var g = target(t, 'GRAMMAR');
    if (v > 0) parts.push(v + ' từ');
    if (k > 0) parts.push(k + ' kanji');
    if (g > 0) parts.push(g + ' mẫu');
    var line = parts.length ? ('Hôm nay học ' + parts.join(' · ')) : 'Hôm nay ôn tập các mục đến hạn';
    var mins = num(t && t.estimatedMinutes);
    if (mins > 0) line += ', ' + minutesWords(t, mins);
    return line;
  }

  /** Owner 23/11: the server gives what is left (the study page's minutes), so a started day says "còn khoảng". */
  function minutesWords(t, mins) {
    var started = list(t && t.tabs).some(function (x) { return x && num(x.newDone) > 0; });
    return (started ? 'còn khoảng ' : 'khoảng ') + mins + ' phút';
  }

  /**
   * Three daily progress pills (Từ vựng / Kanji / Ngữ pháp) with circular x/y ring + review chip.
   * Each pill carries data-pill-tab="vocab|kanji|grammar|review" so it gets its harmonious tab color.
   */
  function studyPillsHtml(t) {
    var due = reviewDue(t);
    var pills = STUDY_ROWS.map(function (r) {
      var tab = todayTab(t, r[0]);
      if (!tab || num(tab.newTarget) <= 0) return '';
      var done = !!tab.done || num(tab.newDone) >= num(tab.newTarget);
      return '<li class="jp-today__pill jp-todo__item' + (done ? ' is-done' : '') + '" data-pill-tab="' + r[1] + '">'
        + progressRingSvg(tab.newDone, tab.newTarget, done)
        + '<span class="jp-today__pill-body jp-todo__text">'
        + '<strong>' + esc(r[2]) + '</strong>'
        + '<span class="jp-today__pill-sub jp-todo__sub">' + esc(num(tab.newDone) + '/' + num(tab.newTarget) + ' ' + r[4] + ' mới') + '</span>'
        + '</span>'
        + (done
          ? '<span class="jp-todo__done">' + medalMiniSvg() + ' Đã học</span>'
          : '<button type="button" class="jp-btn jp-btn--sm" data-action="start-mode" data-mode="SPLIT" data-tab-key="' + r[1] + '">Học riêng</button>')
        + '</li>';
    }).join('');
    var reviewPill = '<li class="jp-today__pill jp-today__pill--review jp-todo__item' + (due === 0 ? ' is-done' : '') + '" data-pill-tab="review">'
      + progressRingSvg(due === 0 ? 1 : 0, 1, due === 0)
      + '<span class="jp-today__pill-body jp-todo__text">'
      + '<strong>Ôn thẻ</strong>'
      + '<span class="jp-today__pill-sub jp-todo__sub">' + esc(due ? due + ' thẻ đến hạn' : 'Không có thẻ đến hạn') + '</span>'
      + '</span>'
      + (due === 0 ? '<span class="jp-todo__done">' + medalMiniSvg() + ' Xong</span>' : '<span class="jp-todo__sub">Trong lượt học</span>')
      + '</li>';
    return '<ol class="jp-today__pills jp-todo" aria-label="Tiến độ từng phần hôm nay">' + pills + reviewPill + '</ol>';
  }

  function studyDayHtml(t) {
    var tip = 'Ôn thẻ đến hạn rồi học mới liền một lượt';
    return '<div class="jp-today__go">'
      + '<button type="button" class="jp-btn jp-btn--primary jp-btn--lg" data-action="start-mode" data-mode="COMBINED" data-tip="' + esc(tip) + '">Học hôm nay</button>'
      + '</div>'
      + studyPillsHtml(t);
  }

  var SKILL_NAME = { READING: 'Đọc', LISTENING: 'Nghe' };

  function skillTask(card, skill) {
    var name = SKILL_NAME[skill];
    var c = card || {};
    var what = c.typeNameVi ? ' · ' + c.typeNameVi : '';
    var floor = skill === 'LISTENING'
      ? (c.floorQuestions ? 'Nghe ≥ ' + num(c.floorQuestions) + ' câu, chép ' + num(c.dictationFloor) + ' câu' : '')
      : (c.floorQuestions ? 'Đọc ≥ ' + num(c.floorPassages) + ' đoạn, làm ≥ ' + num(c.floorQuestions) + ' câu' : '');
    var isDone = !!c.doneToday;
    var action;
    if (card && !c.typeCode) {
      action = '<span class="jp-muted">Chưa có bài</span>';
    } else if (isDone) {
      action = '<span class="jp-todo__done">' + medalMiniSvg() + ' Đã học</span>';
    } else if (c.openSessionId != null) {
      action = '<a class="jp-btn jp-btn--primary jp-btn--sm" href="/learning/jlpt/skills/session/' + encodeURIComponent(c.openSessionId)
        + '">Làm tiếp</a>';
    } else {
      action = '<button type="button" class="jp-btn jp-btn--primary jp-btn--sm" data-action="start-skill" data-skill="' + skill + '">Bắt đầu '
        + esc(name) + '</button>';
    }
    return '<li class="jp-today__pill jp-todo__item' + (isDone ? ' is-done' : '') + '" data-pill-tab="skills">'
      + progressRingSvg(isDone ? 1 : (c.openSessionId != null ? 1 : 0), isDone ? 1 : 2, isDone)
      + '<span class="jp-today__pill-body jp-todo__text"><span><strong>' + esc(name) + '</strong>' + esc(what) + '</span>'
      + (floor ? '<span class="jp-today__pill-sub jp-todo__sub">' + esc(floor) + '</span>' : '') + '</span>' + action + '</li>';
  }

  /**
   * Owner 22/11 (F229): the steps of a skill day (make-up days too) still to do, in the order one "Học hôm nay"
   * runs them: Đọc → Nghe → Ôn (và quiz). A card not loaded yet counts as to do; a card with no exercise is skipped.
   */
  function skillDaySteps(t, extra) {
    var o = (extra && extra.skills) || {};
    var out = [];
    [['READING', o.reading], ['LISTENING', o.listening]].forEach(function (pair) {
      var c = pair[1];
      if (c && (!c.typeCode || c.doneToday)) return;
      out.push({ key: pair[0], label: SKILL_NAME[pair[0]], openSessionId: c && c.openSessionId != null ? c.openSessionId : null });
    });
    var quiz = quizRequired(t);
    if (reviewDue(t) > 0 || quiz) out.push({ key: 'REVIEW', label: quiz ? 'Ôn và quiz' : 'Ôn thẻ' });
    return out;
  }

  function skillGoHtml(t, extra) {
    var steps = skillDaySteps(t, extra);
    if (!steps.length) return '';
    var order = steps.map(function (s) { return s.label; }).join(' → ');
    var started = steps.length < 3 || steps.some(function (s) { return s.openSessionId != null; });
    var tip = 'Học lần lượt ' + order + '; xong phần nào tự sang phần sau';
    return '<div class="jp-today__go">'
      + '<button type="button" class="jp-btn jp-btn--primary jp-btn--lg" data-action="run-day" data-tip="' + esc(tip) + '">'
      + (started ? 'Học tiếp hôm nay' : 'Học hôm nay') + '</button>'
      + '<span class="jp-today__go-sub">' + esc(order) + '</span>'
      + '</div>';
  }

  function skillDayHtml(t, extra) {
    var o = (extra && extra.skills) || null;
    var due = reviewDue(t);
    var quiz = quizRequired(t);
    var reviewDone = due === 0 && !quiz;
    var reviewText = (due ? 'Ôn ' + due + ' thẻ đến hạn' : 'Không có thẻ đến hạn') + (quiz ? ' · quiz 10 câu' : '');
    return (t.skillMakeup ? '<p class="jp-today__summary">Hôm nay bù ngày kỹ năng đã lỡ.</p>' : '')
      + skillGoHtml(t, extra)
      + '<ol class="jp-today__pills jp-todo">' + skillTask(o && o.reading, 'READING') + skillTask(o && o.listening, 'LISTENING')
      + '<li class="jp-today__pill jp-todo__item' + (reviewDone ? ' is-done' : '') + '" data-pill-tab="review">'
      + progressRingSvg(reviewDone ? 1 : 0, 1, reviewDone)
      + '<span class="jp-today__pill-body jp-todo__text"><strong>' + (quiz ? 'Ôn và quiz' : 'Ôn thẻ') + '</strong>'
      + '<span class="jp-today__pill-sub jp-todo__sub">' + esc(reviewText) + '</span></span>'
      + (reviewDone ? '<span class="jp-todo__done">' + medalMiniSvg() + ' Xong</span>'
        : '<button type="button" class="jp-btn jp-btn--sm" data-action="start-skill-review">Bắt đầu ôn</button>') + '</li></ol>';
  }

  var JA_WEEKDAY = ['日曜日', '月曜日', '火曜日', '水曜日', '木曜日', '金曜日', '土曜日'];

  function jaWeekday(iso) {
    var m = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(iso || ''));
    return m ? JA_WEEKDAY[new Date(Date.UTC(+m[1], +m[2] - 1, +m[3])).getUTCDay()] : '';
  }

  function dayLine(t) {
    if (t.kind === 'SKILL') return 'Ngày kỹ năng · Đọc, Nghe và ôn tập';
    if (t.kind === 'REST') return 'Ngày nghỉ · không có bài bắt buộc';
    if (t.kind === 'REVIEW_ONLY') return 'Ngày ôn · chỉ ôn thẻ đến hạn';
    if (t.kanaPhase) return 'Hôm nay học ' + kanaRows(t) + ' hàng chữ cái' + (target(t, 'VOCAB') > 0 ? ' · ' + target(t, 'VOCAB') + ' từ' : '');
    return studySummaryText(t);
  }

  function todayKitsuHtml(t) {
    var src = '/images/mascots/fox/kitsu-encouraging.webp';
    var say = 'Cùng Kitsu chinh phục bài hôm nay nhé!';
    if (t && t.allDone) {
      src = '/images/mascots/fox/kitsu-celebrate-30.webp';
      say = 'Hoàn thành xuất sắc hôm nay!';
    } else if (t && t.kind === 'REST') {
      src = '/images/mascots/fox/kitsu-resting-30.webp';
      say = 'Nghỉ ngơi nạp năng lượng nhé!';
    } else if (t && t.kind === 'SKILL') {
      src = '/images/mascots/fox/kitsu-preparing.webp';
      say = 'Cùng rèn Đọc · Nghe nào!';
    }
    return '<div class="jp-today__kitsu" aria-hidden="true">'
      + '<span class="jp-today__kitsu-bubble">' + esc(say) + '</span>'
      + '<img class="jp-today__kitsu-img" src="' + src + '" width="76" height="76" loading="lazy" alt=""/>'
      + '</div>';
  }

  function todayHtml(today, path, activeTab, extra) {
    if (!today) return '';
    var t = today;
    var p = path || {};
    var x = extra || {};
    var compactSide = x.side ? String(x.side).replace('jp-stepper--vertical', '') : '';
    var weeklyBox = todayWeeklyHtml(p.week, t.day);

    var html = '<section class="jp-card jp-today" data-tour="today" aria-labelledby="jp-today-title">'
      + '<div class="jp-today__top">'
      + calendarStampHtml(t.day, t)
      + '<div class="jp-today__lead">'
      + '<div class="jp-today__title-row">'
      + '<h2 class="jp-section-title" id="jp-today-title">Hôm nay của bạn</h2>'
      + (t.allDone ? '<span class="jp-today__stamp-wrap"><span class="jp-today__stamp" role="img" tabindex="0" aria-label="Bạn đã hoàn thành toàn bộ bài học hôm nay!" data-tip="Bạn đã hoàn thành toàn bộ bài học hôm nay!"><svg viewBox="0 0 100 100" aria-hidden="true"><defs><path id="jpStampArc" d="M50 50 m-37 0 a37 37 0 1 1 74 0 a37 37 0 1 1 -74 0"></path></defs><circle class="jp-today__stamp-o" cx="50" cy="50" r="47"></circle><circle class="jp-today__stamp-i" cx="50" cy="50" r="29"></circle><text class="jp-today__stamp-arc"><textPath href="#jpStampArc" startOffset="0">KOTSUKOTSU ・ ĐÃ HỌC HÔM NAY ・</textPath></text><text class="jp-today__stamp-jp" x="50" y="58" text-anchor="middle" lang="ja">済</text></svg></span><a class="jp-today__stamp-bubble" href="/learning/jlpt/day">Xem lại phần đã học hôm nay</a></span>' : '')
      + '</div>'
      + '<p class="jp-today__subtitle">' + esc(dayLine(t)) + '</p>'
      + (x.why ? '<p class="jp-today__why">' + esc(x.why) + '</p>' : '')
      + '</div>'
      + todayKitsuHtml(t)
      + '</div>'
      + '<div class="jp-today__body"><div class="jp-today__main">';

    var foot = '<div class="jp-today__bottom">'
      + weeklyBox
      + (compactSide ? '<aside class="jp-today__side" aria-label="Tiến trình">' + compactSide + '</aside>' : '')
      + '</div>';
    var end = '</div></div>' + foot + '</section>';
    var missed = t.skillDayMissed ? '<p class="jp-warn">Tuần này bạn còn thiếu ngày kỹ năng.</p>' : '';

    if (t.allDone) {
      return html
        + (t.kind !== 'REST' && t.kind !== 'SKILL' && !t.kanaPhase ? studyPillsHtml(t) : '')
        + missed + end;
    }
    if (t.kind === 'REST') {
      return html + '<p class="jp-today__rest-note">Hôm nay là ngày nghỉ của bạn: không có phần bắt buộc.</p>' + missed + end;
    }
    if (t.kind === 'SKILL') {
      return html + skillDayHtml(t, extra) + missed
        + '<p class="jp-alert" role="alert" data-part="today-error" hidden></p>' + end;
    }
    if (t.kind === 'REVIEW_ONLY') {
      var revTip = 'Hôm nay là ngày ôn: chỉ ôn ' + reviewDue(t) + ' thẻ, không học mới.';
      return html + '<div class="jp-today__go">'
        + '<button type="button" class="jp-btn jp-btn--primary jp-btn--lg" data-action="start-mode" data-mode="COMBINED" data-tip="' + esc(revTip) + '" title="' + esc(revTip) + '">Bắt đầu ôn</button>'
        + '</div>' + missed + end;
    }
    if (t.kanaPhase) {
      var summary = kanaRows(t) + ' hàng chữ cái' + (target(t, 'VOCAB') > 0 ? ' · ' + target(t, 'VOCAB') + ' từ mới' : '')
        + (num(t.estimatedMinutes) > 0 ? ' · ' + minutesWords(t, num(t.estimatedMinutes)) : '');
      html += '<p class="jp-today__summary">' + esc(summary) + '</p>' + chipsHtml(t) + missed;
      return html + modeButtonsHtml(t, path, activeTab)
        + '<p class="jp-alert" role="alert" data-part="today-error" hidden></p>' + end;
    }
    html += studyDayHtml(t) + missed;
    if (t.reviewDayHint) {
      html += '<div class="jp-today__hint"><p>Bạn đang dồn nhiều thẻ ôn. Hôm nay chỉ ôn, không học mới?</p>'
        + '<button type="button" class="jp-btn" data-action="review-day">Chỉ ôn hôm nay</button></div>';
    }
    return html + '<p class="jp-alert" role="alert" data-part="today-error" hidden></p>' + end;
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
      var id = res && res.sessionId;
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

  return { splitTabFor: splitTabFor, UNIT: UNIT, CODE_LABEL: CODE_LABEL, newDraft: newDraft, changeDraft: changeDraft, sliderBody: sliderBody, valueText: valueText, minutesLine: minutesLine, appliesNote: appliesNote, sliderHtml: sliderHtml, reasonText: reasonText, todayHtml: todayHtml, calendarStampHtml: calendarStampHtml, progressRingSvg: progressRingSvg, medalMiniSvg: medalMiniSvg, chestMiniSvg: chestMiniSvg, skillDayHtml: skillDayHtml, skillDaySteps: skillDaySteps, studyDayHtml: studyDayHtml, jaWeekday: jaWeekday, reviewDue: reviewDue, quizRequired: quizRequired, kanaRows: kanaRows, levelPreviewHtml: levelPreviewHtml, comingSoonHtml: comingSoonHtml, warningsHtml: warningsHtml, startRequest: startRequest, onStart: onStart, localIso: localIso, isStale: isStale, estimateBody: estimateBody };
});
