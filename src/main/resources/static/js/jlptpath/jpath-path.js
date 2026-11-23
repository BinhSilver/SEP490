/* [JLAS-W0108] jpath-path.js — overview of the JLPT path page (/learning).
 *
 * Reads GET /api/learning/jlpt/path (JpathPathView) and GET /api/learning/jlpt/today (JpathTodayView; optional —
 * the page still renders without it). Every text goes through JpathDisplay.esc.
 *
 * window.JpathPath:
 *   TAB_KEY                          url key → JpathTab ({kana:'KANA', vocab:'VOCAB', kanji:'KANJI', ...})
 *   resolveTab(param, path)          default kana while the kana tab is on and not completed, otherwise vocab
 *   tabsBarHtml(path, active)        role="tablist"; Chữ cái (when kanaTab), Từ vựng, Kanji, Ngữ pháp, Đọc · Nghe (Sắp mở)
 *   heroHtml(path, today)            greeting, target line, stack of 3 hero cards, resume line
 *   levelBarHtml(path), levelCellHtml(cell)
 *   stageLineHtml(path), weekHtml(week), weekGuideHtml(path, today)  what each kind of day holds, why today is what it is
 *   estimateHtml(path, tab), volumeHtml(path, tab), skillsTabHtml(path, today)
 *   (re-exported) every JpathPathControls renderer: sliders, today block, level preview, warnings, start requests
 *   activateTab, displayTarget, writeUrl   tab focus, display-class target, ?tab= with other params kept
 *   Needs jpath-display.js, jpath-path-controls.js and jpath-dialog.js loaded first; jpath-lists.js (optional) draws
 *   the [data-slot="list"] area of each tab.
 *   menuHtml()                       page menu: Xem lại hướng dẫn (/learning?tour=1), Cài đặt lộ trình
 *   mount(root, deps)                loads both views in parallel, keeps ?tab= in the url, fires JpathDisplay.rendered
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathPath = api;
  if (root && root.document && typeof root.document.addEventListener === 'function') {
    var start = function () {
      var el = root.document.getElementById('jpathPathRoot');
      if (el) api.mount(el, {});
    };
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', start);
    else start();
  }
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var PATH_URL = '/api/learning/jlpt/path';
  var SKILLS_OVERVIEW_URL = '/api/learning/jlpt/skills/overview';
  var SKILL_SESSIONS_URL = '/api/learning/jlpt/skills/sessions';
  var SKILL_CARRY_URL = '/api/learning/jlpt/skills/carry';
  // owner 22/11 (F229): "Học hôm nay" on a skill day runs Đọc → Nghe → Ôn in a row; the run lives for this tab and day
  var DAY_RUN_KEY = 'jlasDayRun';
  var DAY_RUN_DELAY_MS = 2500;
  var TODAY_URL = '/api/learning/jlpt/today';
  var REBALANCE_URL = '/api/learning/jlpt/path/rebalance';
  var MODE_URL = '/api/learning/jlpt/path/mode';
  var REVIEW_DAY_URL = '/api/learning/jlpt/today/review-day';
  var PREVIEW_URL = '/api/learning/jlpt/levels/';
  var TAB_KEY = { kana: 'KANA', vocab: 'VOCAB', kanji: 'KANJI', grammar: 'GRAMMAR', skills: 'SKILL' };
  var TAB_LABEL = { kana: 'Chữ cái', vocab: 'Từ vựng', kanji: 'Kanji', grammar: 'Ngữ pháp', skills: 'Đọc · Nghe' };
  var VOLUME_UNIT = { kana: 'chữ', vocab: 'từ', kanji: 'chữ Kanji', grammar: 'mẫu' };
  var WEEKDAYS = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'];
  var SKILL_OPENS_AT = 80;
  var MSG_LOAD_FAILED = 'Không tải được lộ trình. Kiểm tra mạng rồi bấm Thử lại.';

  var C = (root && root.JpathPathControls)
    || (typeof require === 'function' ? require('./jpath-path-controls.js') : null);
  function DLG() {
    return (root && root.JpathDialog) || (typeof require === 'function' ? require('./jpath-dialog.js') : null);
  }
  function D() { return root.JpathDisplay; }
  function esc(v) { return D().esc(v); }
  function icon(name, label) {
    var nav = root.JpathNav || (typeof require === 'function' ? require('./jpath-nav.js') : null);
    return nav ? nav.iconHtml(name, label) : '';
  }
  function list(v) { return Array.isArray(v) ? v : []; }
  function dayList(codes) { return list(codes).map(function (c) { return D().dayShort(c); }).filter(Boolean).join(', '); }

  function tabLearnedCount(info, todayTab) {
    // F150 review: the path tab (JpathTabProgressView) says counted/learning/known; "learned" does not exist and a
    // rounded percent is 0 for 4 of 800 words, which hid a weekly check that was valid
    var known = info ? Number(info.counted || 0) + Number(info.learning || 0) + Number(info.known || 0)
      + Number(info.learned || 0) : 0;
    var pct = info ? Number(info.percent || 0) : 0;
    var td = todayTab || {};
    var doneToday = Number(td.newDone || 0) + Number(td.reviewDone || 0) + (td.done ? 1 : 0);
    var hasReview = Number(td.reviewDue || 0) > 0;
    return known + pct + doneToday + (hasReview ? 1 : 0);
  }

  function filterWeeklyButtons(todayBox, path, today) {
    if (!todayBox || typeof todayBox.querySelector !== 'function' || typeof todayBox.querySelectorAll !== 'function') return;
    var weeklyEl = todayBox.querySelector('.jp-today__weekly');
    if (!weeklyEl) return;
    var btns = Array.prototype.slice.call(weeklyEl.querySelectorAll('[data-parts-weekly]') || []);
    if (!btns.length) return;
    var pTabs = list(path && path.tabs);
    var tTabs = list(today && today.tabs);
    if (!pTabs.length && !tTabs.length) return;
    var visible = 0;
    btns.forEach(function (btn) {
      var code = String(btn.getAttribute('data-parts-weekly') || '').toUpperCase();
      var info = pTabs.filter(function (x) { return x && x.tab === code; })[0] || null;
      var tInfo = tTabs.filter(function (x) { return x && x.tab === code; })[0] || null;
      var locked = !!(info && info.open === false);
      var empty = info ? tabLearnedCount(info, tInfo) <= 0
        : (tInfo ? (Number(tInfo.newDone || 0) <= 0 && Number(tInfo.reviewDone || 0) <= 0 && !tInfo.done && Number(tInfo.reviewDue || 0) <= 0) : false);
      var hide = locked || empty;
      btn.hidden = hide;
      if (!hide) visible += 1;
    });
    weeklyEl.hidden = visible === 0;
  }

  function tabKeys(path) {
    // owner 18/11: no kana tab — the kana stage is the first nodes of the Từ vựng map
    return ['vocab', 'kanji', 'grammar', 'skills'];
  }

  function defaultTab(path) {
    return 'vocab';
  }

  function resolveTab(param, path) {
    var key = typeof param === 'string' ? param.trim().toLowerCase() : '';
    if (!TAB_KEY[key]) return defaultTab(path);
    if (key === 'kana') return 'vocab';
    return key;
  }

  function tabInfo(path, key) {
    var code = TAB_KEY[key];
    return list(path && path.tabs).filter(function (t) { return t && t.tab === code; })[0] || null;
  }

  function openCell(path) {
    var level = path && path.openLevel;
    return list(path && path.levels).filter(function (c) { return c && c.level === level; })[0] || null;
  }

  // ------------------------------------------------------------------------------------------------ tabs

  function tabsBarHtml(path, active) {
    var html = tabKeys(path).map(function (key) {
      var on = key === active;
      // the skills tab module decides per level; only without it the tab stays "Sắp mở"
      var soon = key === 'skills' && !root.JpathSkillsTab ? ' <span class="jp-tab__soon">Sắp mở</span>' : '';
      var tour = key === 'skills' ? ' data-tour="skills"' : '';
      var info = tabInfo(path, key);
      if (key !== 'skills' && key !== 'kana' && info && info.open === false) soon = ' <span class="jp-tab__later">Sau chữ cái</span>';
      return '<button type="button" class="jp-tab' + (on ? ' jp-tab--active' : '') + '" role="tab" id="jp-tab-' + key
        + '" data-tab="' + key + '" aria-selected="' + on + '" aria-controls="jp-tab-panel" tabindex="'
      + (on ? '0' : '-1') + '"' + tour + '>' + icon({ kana: 'languages', vocab: 'book-open', kanji: 'pen-line', grammar: 'list', skills: 'headphones' }[key]) + ' ' + esc(TAB_LABEL[key]) + soon + '</button>';
    }).join('');
    return '<div class="jp-tabs" role="tablist" aria-label="Phần học">' + html + '</div>';
  }

  // ------------------------------------------------------------------------------------------------ hero

  function targetLine(path) {
    var p = path || {};
    var line = 'Đích: ' + (p.targetLevel || '');
    if (!p.examDate) return line;
    return line + ' trước ' + D().fmtMonth(p.examDate) + (p.onTrack ? ' · kịp tiến độ' : ' · chậm tiến độ');
  }

  function heroCardHtml(card, index) {
    var c = card || {};
    var back = index > 0;
    return '<li class="jp-hero-card jp-hero-card--' + index + '" data-start-item="' + esc(c.itemCode) + '"'
      + (back ? ' aria-hidden="true"' : '') + '>'
      + '<button type="button" class="jp-hero-card__face" data-flip aria-pressed="false" aria-label="Lật xem nghĩa"'
      + (back ? ' tabindex="-1"' : '') + '>'
      + '<span class="jp-hero-card__word" lang="ja">' + esc(c.expression) + '</span>'
      + '<span class="jp-hero-card__reading" lang="ja">' + esc(c.reading) + '</span>'
      + '<span class="jp-hero-card__meaning">' + esc(c.meaning) + '</span></button></li>';
  }

  function heroHtml(path, today) {
    var p = path || {};
    var t = today || {};
    var cards = list(t.heroCards).slice(0, 3);
    var tone = !p.examDate ? '' : (p.onTrack ? ' jp-hero__target--ok' : ' jp-hero__target--late');
    var html = '<div class="jp-hero__text"><h1 class="jp-hero__title">Lộ trình của ' + esc(p.displayName || 'bạn') + '</h1>'
      + '<p class="jp-hero__target' + tone + '">' + esc(targetLine(p)) + '</p>';
    if (t.resume && t.resume.total) {
      html += '<p class="jp-hero__resume">' + esc('Tiếp tục từ thẻ ' + t.resume.position + '/' + t.resume.total) + '</p>';
    }
    html += '</div>';
    // owner 04/11: the suggested-word stack is gone from the hero; "Học hôm nay" in the today panel starts the day
    void cards;
    return html;
  }

  /** F86 (owner 12/11): the path page is the top of Lộ trình JLPT, so it has no trail and no "Quay về" link. */
  function crumbsHtml() {
    return '';
  }

  // page menu (top right of the hero)
  function menuHtml() {
    return '<details class="jp-menu"><summary class="jp-menu__btn" aria-label="Menu trang">' + icon('more-horizontal', 'Menu trang') + '</summary>'
      + '<ul class="jp-menu__list">'
      + '<li><a class="jp-menu__item" href="/learning?tour=1">Xem lại hướng dẫn</a></li>'
      + '<li><a class="jp-menu__item" href="/learning/jlpt/settings">Cài đặt lộ trình</a></li></ul></details>';
  }

  /** The url without tour=1, other params kept. */
  function withoutTour(loc) {
    var base = (loc && loc.pathname) || '/learning';
    var rest = String((loc && loc.search) || '').replace(/^\?/, '').split('&').filter(function (p) {
      return p && p !== 'tour=1';
    });
    return base + (rest.length ? '?' + rest.join('&') : '');
  }

  // ------------------------------------------------------------------------------------------------ levels

  var LEVEL_STEP = { PASSED: 'done', OPEN: 'now', LOCKED: 'todo', COMING_SOON: 'off' };

  function levelCellHtml(cell) {
    var c = cell || {};
    var state = String(c.state || 'COMING_SOON');
    var name = esc(c.level);
    var step = LEVEL_STEP[state] || 'off';
    var mark = state === 'PASSED' ? icon('check') : (state === 'LOCKED' ? icon('lock') : '');
    var sub;
    if (state === 'PASSED') sub = '<button type="button" class="jp-level-cell__preview" data-action="view-level" data-view-level="'
      + name + '">' + icon('check') + ' Đã vượt qua · ôn lại</button>'
      // AI 1 #13: a passed level also opens its level summary
      + '<a class="jp-level-cell__summary" href="/learning/jlpt/levels/' + encodeURIComponent(c.level) + '/summary">Xem tổng kết</a>';
    else if (state === 'OPEN') sub = '<span class="jp-level-cell__state">Đang học · ' + esc(Number(c.percent) || 0) + '%</span>'
      + '<span class="jp-level-cell__bar" aria-hidden="true"><span style="width:' + Math.max(0, Math.min(100, Number(c.percent) || 0))
      + '%"></span></span>';
    else if (state === 'LOCKED') sub = '<button type="button" class="jp-level-cell__preview" data-action="preview" data-preview-level="'
      + name + '">Xem trước</button>';
    else sub = '<button type="button" class="jp-level-cell__preview" data-action="coming-soon" data-coming-soon="' + name
      + '">Sắp có</button>';
    // the server note often repeats the state ("Đang học", "Sắp có"): show it only when it adds something
    var said = state === 'OPEN' ? 'Đang học' : (state === 'COMING_SOON' ? 'Sắp có' : '');
    // a locked level already shows a "Xem trước" button: its note keeps only the condition ("Thi thử N5 để mở")
    var note = String(c.note || '').trim();
    if (state === 'LOCKED') note = note.replace(/^Xem trước\s*·\s*/, '');
    if (note && note !== said) sub += '<span class="jp-level-cell__note">' + esc(note) + '</span>';
    var tipAttr = (state === 'LOCKED' && note && note !== said) ? ' data-tip="' + esc(note) + '" title="' + esc(note) + '"' : '';
    return '<li class="jp-step jp-step--' + step + ' jp-level-cell jp-level-cell--' + esc(state.toLowerCase()) + '"' + (state === 'OPEN' ? ' aria-current="step"' : '') + tipAttr + '><span class="jp-step__mark" aria-hidden="true">' + mark
      + '</span><span class="jp-step__body"><span class="jp-level-cell__name">' + name
      + '</span>' + sub + '</span></li>';
  }

  function levelBarHtml(path) {
    return '<section class="jp-levels" data-tour="level" aria-labelledby="jp-levels-title">'
      + '<h2 class="jp-sidenav__title" id="jp-levels-title">Cấp độ của bạn</h2>'
      + '<ol class="jp-stepper jp-stepper--vertical jp-level-bar">' + list(path && path.levels).map(levelCellHtml).join('')
      + '</ol></section>';
  }

  // ------------------------------------------------------------------------------------------------ stage + week

  function stageLineHtml(path) {
    var p = path || {};
    var order = ['STUDY', 'SKILL_PRACTICE', 'EXAM'];
    var at = p.stage === 'PASSED' ? 3 : Math.max(0, order.indexOf(p.stage));
    var cell = openCell(p);
    var pct = (cell && Number(cell.percent)) || 0;
    var steps = [
      { label: 'Học', sub: at === 0 ? pct + '% cấp ' + (p.openLevel || '') : (at > 0 ? 'Đã xong' : '') },
      { label: 'Rèn kỹ năng', sub: at === 0 ? 'Mở khi học được ' + SKILL_OPENS_AT + '%' : (at > 1 ? 'Đã xong' : 'Đang rèn') },
      { label: 'Thi thử ' + (p.openLevel || ''), sub: at === 2 ? 'Sẵn sàng thi' : (at > 2 ? 'Đã đậu' : 'Sau khi rèn') }
    ].map(function (st, i) {
      return { label: st.label, sub: st.sub, state: i < at ? 'done' : (i === at ? 'now' : 'todo') };
    });
    var marks = steps.map(function (st, i) {
      var mark = st.state === 'done' ? icon('check') : String(i + 1);
      var isTodo = st.state === 'todo';
      var tipAttr = (isTodo && st.sub) ? ' data-tip="' + esc(st.sub) + '" title="' + esc(st.sub) + '" tabindex="0"' : '';
      return '<li class="jp-step jp-step--' + st.state + ' jp-stage__step' + (st.state === 'now' ? ' jp-stage__step--now' : '') + '"'
        + (st.state === 'now' ? ' aria-current="step"' : '') + tipAttr + '><span class="jp-step__mark" aria-hidden="true">' + mark
        + '</span><span class="jp-step__body"><span class="jp-step__label">' + esc(st.label) + '</span>'
        + (st.sub ? '<span class="jp-step__sub' + (isTodo ? ' jp-step__sub--tip' : '') + '">' + esc(st.sub) + '</span>' : '') + '</span></li>';
    }).join('');
    return '<div class="jp-stage"><h2 class="jp-sr">Chặng ' + esc(p.openLevel) + '</h2>'
      + '<ol class="jp-stepper jp-stage__steps" aria-label="' + esc('Chặng ' + (p.openLevel || '')) + '">' + marks + '</ol>'
      + (at > 2 && p.openLevel ? passedLinksHtml(p.openLevel) : '')
      + '</div>';
  }

  /** Owner 18/11: a passed mock test shows where its result and the certificate are. */
  function passedLinksHtml(level) {
    var lv = encodeURIComponent(level);
    return '<p class="jp-stage__passed">Bạn đã đậu thi thử ' + esc(level)
      + '<a href="/learning/jlpt/levels/' + lv + '/summary">Xem kết quả</a>'
      + '<a href="/learning/jlpt/levels/' + lv + '/certificate">Xem chứng nhận</a></p>';
  }

  function weekday(iso) {
    var m = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(iso || ''));
    if (!m) return '';
    return WEEKDAYS[new Date(Date.UTC(+m[1], +m[2] - 1, +m[3])).getUTCDay()];
  }

  function weekHtml(week) {
    var chips = list(week).map(function (d) {
      var cls = 'jp-day' + (d.today ? ' jp-day--today' : '') + (d.done ? ' jp-day--done' : '')
        + (d.kind === 'SKILL' ? ' jp-day--skill' : '') + (d.kind === 'REST' ? ' jp-day--rest' : '')
        + (d.kind === 'REVIEW_ONLY' ? ' jp-day--review' : '');
      var title = d.kind === 'SKILL' ? ' title="Ngày kỹ năng"' : (d.kind === 'REST' ? ' title="Ngày nghỉ"'
        : (d.kind === 'REVIEW_ONLY' ? ' title="Ngày ôn"' : ''));
      var label = esc(D().dayShort(weekday(d.day))) + (d.done ? ' ' + icon('check') : '');
      // Owner 05/11: a past day or today opens its detail page
      var past = d.today || (d.day && d.day <= new Date().toISOString().slice(0, 10));
      return '<li class="' + cls + '"' + (d.today ? ' aria-current="date"' : '') + title + '>'
        + (past && d.day ? '<a class="jp-day__link" href="/learning/jlpt/day?d=' + esc(d.day) + '">' + label + '</a>' : label)
        + '</li>';
    }).join('');
    return '<ol class="jp-week" aria-label="Tuần này">' + chips + '</ol>';
  }

  var WEEK_CODES = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'];

  /**
   * "Mỗi ngày học gì?": what a study, skill and rest day hold with this path's days and sliders, and why today is the
   * kind it is (a make-up skill day, or a plan made before the week schedule changed).
   */
  /** Why today is the kind it is, or '' when the week schedule already says so. */
  function todayReason(path, today) {
    var p = path || {};
    var skill = list(p.skillDays);
    var t = today || {};
    var todayCode = t.day ? weekday(t.day) : null;
    if (t.kind === 'SKILL' && t.skillMakeup) return '';
    if (todayCode && t.kind === 'SKILL' && skill.indexOf(todayCode) < 0) {
      return 'Kế hoạch hôm nay được tạo trước khi bạn đổi lịch nên vẫn là ngày kỹ năng; lịch mới áp dụng từ ngày mai.';
    }
    if (todayCode && t.kind === 'STUDY' && skill.indexOf(todayCode) >= 0) {
      return 'Kế hoạch hôm nay được tạo trước khi bạn đổi lịch nên vẫn là ngày học; lịch mới áp dụng từ ngày mai.';
    }
    return '';
  }

  /**
   * F216 (owner 22/11): "Vì sao hôm nay" — two to four plain reasons for today's plan, in the learner's words (the day,
   * the pace toward the exam or finish date, the cards due, a schedule change). It never names a rule or a formula.
   */
  function whyTodayHtml(path, today) {
    var p = path || {};
    var t = today || {};
    if (!t.day) return '';
    var fmt = function (iso) { return D() && D().fmtDate ? D().fmtDate(iso) : String(iso || ''); };
    var tabs = list(t.tabs);
    var fresh = tabs.reduce(function (n, x) { return n + (Number(x && x.newTarget) || 0); }, 0);
    var due = tabs.reduce(function (n, x) { return n + (Number(x && x.reviewDue) || 0); }, 0);
    var level = p.openLevel || '';
    var out = [];
    if (t.kanaPhase) out.push('Bạn đang học bảng chữ cái trước, vì bạn chọn bắt đầu từ chữ cái. Xong chữ cái là vào từ vựng.');
    if (t.kind === 'SKILL') {
      out.push(t.skillMakeup ? 'Tuần này bạn lỡ ngày kỹ năng, nên hôm nay học bù Đọc và Nghe.'
        : 'Hôm nay là ngày kỹ năng: luyện Đọc và Nghe bằng những gì bạn đã học, không có mục mới.');
    } else if (t.kind === 'STUDY' && fresh > 0 && !t.kanaPhase) {
      var goal = 'Hôm nay có ' + fresh + ' mục mới';
      if (p.examDate && p.onTrack !== false) out.push(goal + ' để bạn kịp xong ' + level + ' trước ngày thi ' + fmt(p.examDate) + '.');
      else if (p.examDate) out.push(goal + '. Với nhịp này bạn chưa kịp ngày thi ' + fmt(p.examDate) + '; có thể tăng số từ mỗi ngày trong Cài đặt lộ trình.');
      else if (p.levelFinish) out.push(goal + ', giữ nhịp này bạn xong ' + level + ' khoảng ' + fmt(p.levelFinish) + '.');
      else out.push(goal + '.');
    }
    if (due > 0) out.push('Có ' + due + ' thẻ đến lúc ôn: ôn đúng lúc giúp bạn nhớ lâu hơn.');
    var change = todayReason(p, t);
    if (change) out.push(change);
    if (t.allDone && out.length) out.push('Bạn đã xong hôm nay. Ngày mai lộ trình đi tiếp từ phần kế.');
    if (!out.length) return '';
    return '<section class="jp-card jp-why-today" aria-labelledby="jp-why-today-title">'
      + '<h3 class="jp-why-today__title" id="jp-why-today-title">Vì sao hôm nay</h3><ul class="jp-why-today__list">'
      + out.slice(0, 4).map(function (r) { return '<li>' + esc(r) + '</li>'; }).join('') + '</ul></section>';
  }

  /**
   * F238 (owner 23/11): Đọc · Nghe sessions left from earlier weeks are carried, not dropped on Monday; a normal study
   * day takes one of them after the main lesson. Empty when nothing is owed or today is not a normal study day.
   */
  function carryHtml(carry) {
    var c = carry || {};
    var owed = Number(c.owed) || 0;
    if (owed <= 0 || (!c.offerToday && !c.doneToday)) return '';
    var skillName = c.skill === 'LISTENING' ? 'Nghe hiểu' : 'Đọc hiểu';
    if (!c.offerToday) {
      return '<section class="jp-card jp-carry" aria-label="Bài kỹ năng còn nợ"><p class="jp-carry__text">Hôm nay bạn đã làm bù 1 bài kỹ năng. '
        + 'Còn ' + owed + ' bài Đọc, Nghe từ tuần trước, ngày học sau làm tiếp.</p></section>';
    }
    return '<section class="jp-card jp-carry" aria-labelledby="jp-carry-title">'
      + '<h3 class="jp-carry__title" id="jp-carry-title">Bù bài kỹ năng tuần trước</h3>'
      + '<p class="jp-carry__text">Tuần trước còn ' + owed + ' bài Đọc, Nghe chưa làm. Mỗi ngày học bạn làm thêm 1 bài sau bài chính, '
      + 'hôm nay là bài ' + skillName + '.</p>'
      + '<button type="button" class="jp-btn jp-btn--sm" data-action="start-skill" data-skill="' + (c.skill === 'LISTENING' ? 'LISTENING' : 'READING')
      + '">Làm bài ' + skillName + '</button></section>';
  }

  function weekGuideHtml(path, today) {
    var p = path || {};
    var skill = list(p.skillDays);
    var rest = list(p.restDays);
    var study = WEEK_CODES.filter(function (c) { return skill.indexOf(c) < 0 && rest.indexOf(c) < 0; });
    var s = p.sliders || {};
    var names = function (codes) { return codes.length ? ' (' + dayList(codes) + ')' : ''; };
    var items = '<li><strong>Ngày học</strong>' + esc(names(study)) + ': học mới ' + esc((Number(s.vocab) || 0) + ' từ, '
        + (Number(s.kanji) || 0) + ' Kanji, ' + (Number(s.grammar) || 0) + ' mẫu ngữ pháp') + ', rồi ôn các thẻ đến hạn.</li>'
      + '<li><strong>Ngày kỹ năng</strong>' + esc(names(skill)) + ': không học mới; làm 1 buổi Đọc và 1 buổi Nghe, ôn các thẻ đến hạn.</li>'
      + (rest.length ? '<li><strong>Ngày nghỉ</strong>' + esc(names(rest)) + ': không có bài.</li>' : '')
      + '<li>Bỏ lỡ ngày kỹ năng thì ngày học kế tiếp trong tuần thành ngày kỹ năng bù. Hết tuần vẫn còn thì sang tuần sau, mỗi ngày học làm thêm 1 bài.</li>';
    return '<details class="jp-weekguide"><summary>Mỗi ngày học gì?</summary><ul class="jp-weekguide__list">' + items + '</ul>'
      + '<p class="jp-muted">Đổi số từ mới mỗi ngày, ngày học, ngày kỹ năng, ngày nghỉ ở <a href="/learning/jlpt/settings#jp-settings-schedule-title">'
      + 'Cài đặt lộ trình</a>.</p></details>';
  }

  // ------------------------------------------------------------------------------------------------ numbers

  function estimateHtml(path, tab) {
    var p = path || {};
    var est = (p.estimates || {})[TAB_KEY[tab]];
    return '<section class="jp-card jp-stat"><h2 class="jp-stat__title">Dự kiến xong tab này</h2>'
      + '<p class="jp-stat__value">' + esc(est ? D().fmtDate(est) : '—') + '</p>'
      + '<p class="jp-stat__sub">' + esc('Cả cấp ' + (p.openLevel || '') + ' (muộn nhất 3 tab): '
        + (p.levelFinish ? D().fmtDate(p.levelFinish) : '—')) + '</p></section>';
  }

  function volumeHtml(path, tab) {
    var p = path || {};
    var info = tabInfo(p, tab) || {};
    return '<section class="jp-card jp-stat"><h2 class="jp-stat__title">Khối lượng '
      + esc(p.openLevel) + '</h2><p class="jp-stat__value">' + esc(D().fmtNumber(Number(info.total) || 0) + ' ' + (VOLUME_UNIT[tab] || ''))
      + '</p><p class="jp-stat__sub">' + esc('Đã biết ' + D().fmtNumber(Number(info.known) || 0) + ' · Sổ tay '
        + D().fmtNumber(Number(info.notebook) || 0)) + '</p></section>';
  }

  function skillsTabHtml(path) {
    var days = '';
    // Plan 3 Task 19: jpath-skills-tab.js fills the real tab from GET /skills/overview
    if (root.JpathSkillsTab) {
      return '<section class="jp-card jp-skills" data-jpath-skills-tab aria-live="polite">'
        + '<p class="jp-skel jp-skel--list" role="status" aria-busy="true"><span class="jp-skel__sr">Đang tải phần Đọc · Nghe…</span><i class="jp-skel__b jp-skel__b--title"></i><i class="jp-skel__b jp-skel__b--line"></i><i class="jp-skel__b jp-skel__b--line"></i><i class="jp-skel__b jp-skel__b--short"></i></p></section>' + days;
    }
    return '<section class="jp-card jp-skills" aria-labelledby="jp-skills-title">'
      + '<h2 class="jp-section-title" id="jp-skills-title">Đọc · Nghe <span class="jp-tab__soon">Sắp mở</span></h2>'
      + '<p>Phần luyện Đọc và Nghe đang được chuẩn bị.</p>' + days + '</section>';
  }

  function numbersHtml(path, tab, today) {
    if (tab === 'skills') return skillsTabHtml(path, today);
    if (root && root.JpathParts && (tab === 'vocab' || tab === 'kanji' || tab === 'grammar')) {
      return lockedTabNoteHtml(path, tab)
        + '<div class="jp-sr" aria-hidden="true">' + estimateHtml(path, tab) + volumeHtml(path, tab) + '</div>';
    }
    // [JLAS-W0108] F13: the kana tab has no finish estimate; show only its volume instead of "—"
    var est = (path && path.estimates || {})[TAB_KEY[tab]];
    return (tab === 'kana' && !est ? '' : estimateHtml(path, tab)) + volumeHtml(path, tab);
  }

  /** [JLAS-W0108] F12: a tab that opens after kana says why its map is locked and links to the kana tab. */
  function lockedTabNoteHtml(path, tab) {
    var info = tabInfo(path, tab);
    var kanaFirst = !!(path && path.kanaTab && !path.kanaCompleted);
    // owner 18/11: the kana stage sits at the start of the Từ vựng map, so that map needs no note
    if (!info || tab === 'vocab' || (info.open !== false && !kanaFirst)) return '';
    var text = 'Học xong <strong>chặng Chữ cái</strong> để mở tab này.';
    return '<p class="jp-band jp-locked-note" role="note">' + text + ' <a href="/learning?tab=vocab">Về chặng Chữ cái</a></p>';
  }

  // tab buttons are updated in place so focus stays on the activated tab
  function activateTab(buttons, key, focus) {
    var active = null;
    list(buttons).forEach(function (b) {
      var on = b.getAttribute('data-tab') === key;
      b.setAttribute('aria-selected', String(on));
      b.setAttribute('tabindex', on ? '0' : '-1');
      if (b.classList) b.classList.toggle('jp-tab--active', on);
      if (on) active = b;
    });
    if (active && focus) active.focus();
    return active;
  }

  // the font-scale rule sits on the page <main>; display classes go there too
  function displayTarget(rootEl) {
    var main = rootEl && typeof rootEl.closest === 'function' ? rootEl.closest('.jp-path') : null;
    return main || rootEl;
  }

  function writeUrl(loc, tab) {
    var search = String((loc && loc.search) || '');
    var base = (loc && loc.pathname) || '/learning';
    if (typeof URLSearchParams === 'function') {
      var params = new URLSearchParams(search);
      params.set('tab', tab);
      return base + '?' + params.toString();
    }
    return base + '?tab=' + tab;
  }

  // ------------------------------------------------------------------------------------------------ mount

  function region(rootEl, name) { return rootEl.querySelector('[data-region="' + name + '"]'); }
  function slot(rootEl, name) { return rootEl.querySelector('[data-slot="' + name + '"]'); }
  function setHtml(rootEl, name, html) { var el = region(rootEl, name); if (el) el.innerHTML = html; }
  function setSlot(rootEl, name, html) { var el = slot(rootEl, name); if (el) el.innerHTML = html; }
  function all(el, sel) {
    return el && typeof el.querySelectorAll === 'function' ? Array.prototype.slice.call(el.querySelectorAll(sel)) : [];
  }
  function part(rootEl, name) { return rootEl.querySelector('[data-part="' + name + '"]'); }

  function writeTab(deps, tab) {
    var loc = deps.location;
    if (!deps.history || !loc) return;
    try { deps.history.replaceState(null, '', writeUrl(loc, tab)); } catch (e) { /* ignore */ }
  }

  function readParam(loc) {
    var m = /[?&]tab=([^&#]*)/.exec((loc && loc.search) || '');
    try { return m ? decodeURIComponent(m[1]) : null; } catch (e) { return null; }
  }

  function showError(el, message) {
    if (!el) return;
    el.textContent = message || 'Có lỗi xảy ra. Thử lại sau.';
    el.hidden = false;
  }

  function mount(rootEl, deps) {
    var d = deps || {};
    var apiClient = d.api || root.JpathApi;
    var loc = d.location || root.location;
    var doc = d.document || root.document;
    var rendered = d.rendered || function (el) { D().rendered(el); };
    var state = { path: null, today: null, todayFailed: false, tab: null, draft: null, viewLevel: null };
    var env = { location: loc, history: d.history || root.history };

    function paintControls() {
      var p = state.path;
      // F227 (owner 22/11): pace and week-skip moved to Cài đặt lộ trình; the path keeps a nudge with a reason only
      if (root.JpathToolsNudge && typeof root.JpathToolsNudge.paint === 'function') {
        root.JpathToolsNudge.paint(slot(rootEl, 'tools-nudge'), { warnings: p.warnings, learning: !p.stage || p.stage === 'STUDY', doc: doc });
      }
      // F216: the schedule-change line moved into the "Vì sao hôm nay" card under the today block
      setSlot(rootEl, 'today', C.todayHtml(state.today, p, state.tab,
        { skills: state.skills, why: '',
          side: stageLineHtml(p).replace('jp-stepper jp-stage__steps', 'jp-stepper jp-stepper--vertical jp-stage__steps') })
        + whyTodayHtml(p, state.today) + carryHtml(state.carry));
      filterWeeklyButtons(slot(rootEl, 'today'), p, state.today);
    }

    // the [data-slot="list"] area belongs to jpath-lists.js (loaded before this file); it keeps its own filters
    function paintList() {
      var lists = root.JpathLists;
      var el = slot(rootEl, 'list');
      if (!lists || !el || typeof lists.mountTab !== 'function') return;
      var p = state.path;
      lists.mountTab(el, state.tab, { level: state.viewLevel || p.openLevel, display: p.display || {},
        skillDay: !!(state.today && state.today.kind === 'SKILL'), api: apiClient, location: loc,
        history: env.history, document: doc });
      // owner 18/11: the kana stage is the first part of the Từ vựng map (no kana tab)
      if (root.JpathKanaStage && typeof root.JpathKanaStage.mount === 'function') {
        root.JpathKanaStage.mount(slot(rootEl, 'kana-stage'), { api: apiClient, tab: state.tab,
          ownLevel: !state.viewLevel || state.viewLevel === p.openLevel, path: p });
      }
      // Owner 05/11: V/K/G tabs open on their study parts; the whole list waits behind "Xem toàn bộ"
      if (root.JpathParts && typeof root.JpathParts.mount === 'function') {
        root.JpathParts.mount(slot(rootEl, 'parts'), el, state.tab, {
          api: apiClient, ownLevel: !state.viewLevel || state.viewLevel === p.openLevel, week: p.week });
      }
    }

    // Plan 3 Task 19: the Đọc · Nghe tab body; with content at the level the tab loses its "Sắp mở" label
    function paintSkills() {
      var el = rootEl.querySelector && rootEl.querySelector('[data-jpath-skills-tab]');
      if (!el || !root.JpathSkillsTab) return;
      root.JpathSkillsTab.mount(el, { api: apiClient, location: loc, document: doc,
        level: state.viewLevel || (state.path && state.path.openLevel) }).then(function (o) {
        if (!o || !root.JpathSkillsTab.hasContent(o)) return;
        all(rootEl, '[data-tour="skills"] .jp-tab__soon').forEach(function (n) { n.remove(); });
      });
    }

    function paintTab(keepTabs) {
      var buttons = keepTabs ? all(rootEl, '[role="tab"]') : [];
      if (buttons.length) activateTab(buttons, state.tab, true);
      else setHtml(rootEl, 'tabs', tabsBarHtml(state.path, state.tab));
      setHtml(rootEl, 'numbers', numbersHtml(state.path, state.tab, state.today));
      if (state.tab === 'skills') paintSkills();
      paintControls();
      paintList();
      if (rootEl.setAttribute) rootEl.setAttribute('data-jpath-tab', state.tab);
      setHtml(rootEl, 'crumbs', crumbsHtml(state.path, state.tab, state.viewLevel));
    }

    function selectTab(key) {
      var next = resolveTab(key, state.path);
      if (next === state.tab) return;
      state.tab = next;
      writeTab(env, next);
      paintTab(true);
    }

    function levelBand() {
      if (!state.viewLevel) return '';
      return '<p class="jp-band" role="status">' + esc('Đang xem ' + state.viewLevel + ' (đã vượt qua): ôn tự do, không tính tiến độ.')
        + ' <button type="button" class="jp-link-btn" data-action="back-to-open">Về cấp đang học</button></p>';
    }

    function paintAll() {
      var p = state.path;
      if (p.display && rootEl.classList) D().applyDisplay(displayTarget(rootEl), p.display);
      setHtml(rootEl, 'hero', heroHtml(p, state.today)); // owner 18/11: the … menu repeated the side links
      // the stage and the week sit in the today panel's footer; this region keeps the week guide
      setHtml(rootEl, 'stage', weekGuideHtml(p, state.today));
      setHtml(rootEl, 'levels', levelBand() + levelBarHtml(p));
      paintTab(false);
    }

    function viewLevel(level) {
      state.viewLevel = level;
      setHtml(rootEl, 'levels', levelBand() + levelBarHtml(state.path));
      setHtml(rootEl, 'crumbs', crumbsHtml(state.path, state.tab, state.viewLevel));
      paintList();
      if (rootEl.dispatchEvent && typeof root.CustomEvent === 'function') {
        rootEl.dispatchEvent(new root.CustomEvent('jpath:view-level', { detail: { level: level || state.path.openLevel,
          passed: !!level }, bubbles: true }));
      }
    }

    function loadToday() {
      return Promise.resolve().then(function () { return apiClient.get(TODAY_URL); }).then(function (t) {
        state.today = t;
        state.todayFailed = false;
        state.carry = null;
        // F238: a normal study day may carry one Đọc · Nghe session left from earlier weeks
        if (t && t.kind === 'STUDY' && !t.kanaPhase) {
          return Promise.resolve().then(function () { return apiClient.get(SKILL_CARRY_URL); })
            .then(function (c) { state.carry = c || null; state.skills = null; }, function () { state.carry = null; state.skills = null; });
        }
        // a skill day lists its Reading and Listening sessions: their type of the day and state come from the overview
        if (!t || t.kind !== 'SKILL') { state.skills = null; return null; }
        return Promise.resolve().then(function () { return apiClient.get(SKILLS_OVERVIEW_URL); })
          .then(function (o) { state.skills = o || null; }, function () { state.skills = null; });
      }, function () { state.todayFailed = true; });
    }

    function reload() {
      return Promise.all([apiClient.get(PATH_URL), loadToday()]).then(function (res) {
        state.path = res[0] || {};
        state.draft = C.newDraft(state.path.sliders);
        paintAll();
      });
    }

    function failInto(name, btn) {
      return function (e) {
        if (btn) btn.disabled = false;
        showError(part(rootEl, name), e && (e.code || e.status) && e.message);
      };
    }

    // owner 23/11: "Học hôm nay (Gộp)" took ~5 s with no sign of life; one start at a time, the button says so
    function start(req, btn) {
      if (state.starting) return Promise.resolve(null);
      state.starting = true;
      setBusy(btn, true);
      return Promise.resolve().then(function () { return api.onStart(req, { api: apiClient }); })
        .catch(function (e) {
          state.starting = false;
          setBusy(btn, false);
          failInto('today-error', btn)(e);
        });
    }

    var later = d.setTimeout || function (fn, ms) { return root.setTimeout(fn, ms); };

    var runTimer = null;
    var session = d.sessionStorage !== undefined ? d.sessionStorage
      : (function () { try { return root.sessionStorage; } catch (e) { return null; } })();

    function dayRun(day) {
      try {
        if (!session) return null;
        if (day === undefined) return session.getItem(DAY_RUN_KEY);
        if (day) session.setItem(DAY_RUN_KEY, String(day)); else session.removeItem(DAY_RUN_KEY);
      } catch (e) { /* private mode: the run simply is not continued */ }
      return null;
    }

    function runStep(step, btn) {
      if (btn) btn.disabled = true;
      var go = d.navigate || function (u) { root.location.assign(u); };
      if (step.key === 'REVIEW') { start({ tab: 'SKILL', mode: 'TODAY' }, btn); return; }
      if (step.openSessionId != null) { go('/learning/jlpt/skills/session/' + encodeURIComponent(step.openSessionId)); return; }
      apiClient.send('POST', SKILL_SESSIONS_URL, { skill: step.key, kind: 'DAY' }).then(function (v) {
        if (v && v.id != null) go('/learning/jlpt/skills/session/' + encodeURIComponent(v.id));
      }).catch(failInto('today-error', btn));
    }

    /** Back on the path page during a run: the next step starts by itself after a short notice ("Dừng" ends the run). */
    function continueDayRun() {
      var t = state.today;
      var day = dayRun();
      if (!day) return;
      if (!t || t.kind !== 'SKILL' || String(t.day) !== day) { dayRun(null); return; }
      var steps = C.skillDaySteps(t, { skills: state.skills });
      var host = slot(rootEl, 'today');
      if (!steps.length) {
        dayRun(null);
        return;
      }
      if (!host || !host.querySelector) return;
      var go = host.querySelector('.jp-today__go');
      if (!go) return;
      var box = doc.createElement('p');
      box.className = 'jp-today__run';
      box.setAttribute('role', 'status');
      box.setAttribute('data-part', 'day-run');
      box.textContent = 'Tiếp theo: ' + steps[0].label + ' · tự bắt đầu sau vài giây. ';
      var stop = doc.createElement('button');
      stop.type = 'button';
      stop.className = 'jp-link-btn';
      stop.setAttribute('data-action', 'stop-day-run');
      stop.textContent = 'Dừng';
      box.appendChild(stop);
      go.parentNode.insertBefore(box, go.nextSibling);
      if (runTimer) clearTimeout(runTimer);
      runTimer = setTimeout(function () {
        runTimer = null;
        if (dayRun() === day) runStep(steps[0], go.querySelector('[data-action="run-day"]'));
      }, DAY_RUN_DELAY_MS);
    }

    function openDialog(html, opener) {
      var dlg = DLG();
      return dlg ? dlg.open(doc, html, opener) : null;
    }

    var actions = {
      'start-item': function (el) {
        start(C.startRequest('ITEM', el.getAttribute('data-start-tab') || 'vocab', el.getAttribute('data-item')), el);
      },
      'start-mode': function (el) {
        var req = el.getAttribute('data-mode') === 'SPLIT'
          ? C.startRequest('TAB', el.getAttribute('data-tab-key') || state.tab) : C.startRequest('COMBINED');
        start(req, el);
      },
      'start-skill': function (el) {
        el.disabled = true;
        apiClient.send('POST', SKILL_SESSIONS_URL, { skill: el.getAttribute('data-skill'), kind: 'DAY' }).then(function (v) {
          if (v && v.id != null) (deps.navigate || function (u) { root.location.assign(u); })('/learning/jlpt/skills/session/' + encodeURIComponent(v.id));
        }).catch(failInto('today-error', el));
      },
      'run-day': function (el) {
        var steps = C.skillDaySteps(state.today, { skills: state.skills });
        if (!steps.length) return;
        dayRun(state.today && state.today.day);
        runStep(steps[0], el);
      },
      'stop-day-run': function () {
        dayRun(null);
        if (runTimer) clearTimeout(runTimer);
        runTimer = null;
        var box = rootEl.querySelector && rootEl.querySelector('[data-part="day-run"]');
        if (box) box.remove();
      },
      'start-skill-review': function (el) {
        start({ tab: 'SKILL', mode: 'TODAY' }, el);
      },
      'start-tab': function (el) {
        var key = el.getAttribute('data-start-tab');
        // a skill day is done in the Đọc · Nghe tab (two cards there start the Reading and Listening sessions)
        if (key === 'skills') {
          selectTab('skills');
          var panel = region(rootEl, 'tabs');
          if (panel && typeof panel.scrollIntoView === 'function') panel.scrollIntoView({ block: 'start', behavior: 'smooth' });
          return;
        }
        start(C.startRequest('TAB', key), el);
      },
      'rebalance': function (el) {
        el.disabled = true;
        apiClient.send('POST', REBALANCE_URL).then(reload).catch(failInto('rebalance-error', el));
      },
      'default-mode': function (el) {
        el.disabled = true;
        apiClient.send('PUT', MODE_URL, { mode: el.getAttribute('data-mode') }).then(reload)
          .catch(failInto('today-error', el));
      },
      'review-day': function (el) {
        el.disabled = true;
        apiClient.send('POST', REVIEW_DAY_URL).then(reload).catch(failInto('today-error', el));
      },
      'preview': function (el) {
        var lv = el.getAttribute('data-preview-level');
        apiClient.get(PREVIEW_URL + encodeURIComponent(lv) + '/preview').then(function (pv) {
          openDialog(C.levelPreviewHtml(pv, state.path.openLevel), el);
        }, function (e) {
          openDialog('<p id="jp-dialog-title">' + esc((e && (e.code || e.status) && e.message) || 'Không tải được.') + '</p>', el);
        });
      },
      'coming-soon': function (el) { openDialog(C.comingSoonHtml(el.getAttribute('data-coming-soon')), el); },
      'view-level': function (el) { viewLevel(el.getAttribute('data-view-level')); },
      'back-to-open': function () { viewLevel(null); }
    };

    rootEl.addEventListener('click', function (ev) {
      var t = ev.target;
      if (!t || typeof t.closest !== 'function' || !state.path) return;
      if (t.closest('[data-slot="list"]')) return; // list controls (e.g. Luyện quiz data-tab) are not tabs
      var tabBtn = t.closest('[data-tab]');
      if (tabBtn) { selectTab(tabBtn.getAttribute('data-tab')); return; }
      var flip = t.closest('[data-flip]');
      if (flip) {
        flip.setAttribute('aria-pressed', String(flip.getAttribute('aria-pressed') !== 'true'));
        return;
      }
      var el = t.closest('[data-action]');
      var handler = el && actions[el.getAttribute('data-action')];
      if (handler) handler(el);
    });

    rootEl.addEventListener('keydown', function (ev) {
      var t = ev.target;
      if (!state.path || !t || typeof t.closest !== 'function' || !t.closest('[role="tab"]')) return;
      var keys = tabKeys(state.path);
      var i = keys.indexOf(state.tab);
      var next = null;
      if (ev.key === 'ArrowRight') next = keys[(i + 1) % keys.length];
      else if (ev.key === 'ArrowLeft') next = keys[(i - 1 + keys.length) % keys.length];
      else if (ev.key === 'Home') next = keys[0];
      else if (ev.key === 'End') next = keys[keys.length - 1];
      if (next) { ev.preventDefault(); selectTab(next); }
    });

    function onVisible() {
      if (doc.visibilityState !== 'visible' || !state.path) return;
      if (state.todayFailed || C.isStale(state.today, C.localIso())) {
        loadToday().then(paintAll).then(function () { if (state.tour) state.tour.refresh(); })
          .catch(function () { /* keep the current page */ });
      }
    }
    if (doc && typeof doc.addEventListener === 'function' && !rootEl.__jpathVisibility) {
      rootEl.__jpathVisibility = true; // one listener per mounted page
      doc.addEventListener('visibilitychange', onVisible);
    }

    // first visit (or ?tour=1): tourDone is saved on done and on skip, and tour=1 leaves the url
    function startTour() {
      var tour = root.JpathTour;
      if (!tour || !tour.shouldStart(state.path, (loc && loc.search) || '')) return;
      // saved at once (no debounce): leaving the page right after the tour must keep the flag
      var saver = d.saver || { save: function (patch) {
        var p = apiClient.send('PUT', D().SETTINGS_URL, patch);
        if (p && typeof p.catch === 'function') p.catch(function () { /* the tour shows again next time */ });
      } };
      // owner 17/11: shown once — the flag is saved when the guide opens, not only on finish/skip
      var savedAtOpen = !state.path.tourDone;
      if (savedAtOpen) { state.path.tourDone = true; saver.save({ tourDone: true }); }
      state.tour = tour.create({ doc: doc, onFinish: function () {
        state.tour = null;
        if (!savedAtOpen) saver.save({ tourDone: true });
        state.path.tourDone = true;
        if (env.history && /(^|[?&])tour=1(&|$)/.test((loc && loc.search) || '')) {
          try { env.history.replaceState(null, '', withoutTour(loc)); } catch (e) { /* ignore */ }
        }
      } });
      state.tour.start();
    }

    // the page menu closes on Esc and on a click outside it
    function closeMenus(except) {
      Array.prototype.forEach.call(rootEl.querySelectorAll ? rootEl.querySelectorAll('details.jp-menu[open]') : [],
        function (m) { if (m !== except) m.removeAttribute('open'); });
    }
    if (doc && typeof doc.addEventListener === 'function' && !rootEl.__jpathMenu) {
      rootEl.__jpathMenu = true;
      doc.addEventListener('keydown', function (ev) { if (ev.key === 'Escape') closeMenus(null); });
      doc.addEventListener('click', function (ev) {
        var t = ev.target;
        closeMenus(t && typeof t.closest === 'function' ? t.closest('details.jp-menu') : null);
      });
    }

    // owner 23/11: back with the browser's Back button the page came from the cache with yesterday's numbers (the
    // "Tự chọn bài" lists too): such a page loads again
    if (typeof root.addEventListener === 'function' && !rootEl.__jpathPageshow) {
      rootEl.__jpathPageshow = true;
      root.addEventListener('pageshow', function (ev) { if (ev && ev.persisted && loc && loc.reload) loc.reload(); });
    }

    function firstLoad(attempt) {
    var pathLoad = Promise.resolve().then(function () { return apiClient.get(PATH_URL); });
    return Promise.all([pathLoad, loadToday()]).then(function (res) {
      state.path = res[0] || {};
      state.draft = C.newDraft(state.path.sliders);
      state.tab = resolveTab(readParam(loc) || (rootEl.getAttribute && rootEl.getAttribute('data-jpath-tab')), state.path);
      writeTab(env, state.tab);
      paintAll();
      api.current = state;
      rootEl.setAttribute('aria-busy', 'false');
      rendered(rootEl);
      startTour();
      premiumBox(rootEl, apiClient);
      continueDayRun();
    }, function () {
      // owner 23/11: a short network hiccup showed a dead error: one quiet retry, then a Thử lại button
      if (!attempt) {
        return new Promise(function (resolve) { later(resolve, LOAD_RETRY_MS); }).then(function () { return firstLoad(1); });
      }
      setHtml(rootEl, 'hero', loadFailedHtml());
      rootEl.setAttribute('aria-busy', 'false');
      var btn = rootEl.querySelector && rootEl.querySelector('[data-jpath-load-retry]');
      if (btn && typeof btn.addEventListener === 'function') {
        btn.addEventListener('click', function () {
          btn.disabled = true;
          rootEl.setAttribute('aria-busy', 'true');
          firstLoad(1);
        });
      }
      return null;
    });
    }
    return firstLoad(d.noLoadRetry ? 1 : 0);
  }

  var LOAD_RETRY_MS = 1500;

  function loadFailedHtml() {
    return '<div class="jp-alert" role="alert"><p>' + esc(MSG_LOAD_FAILED) + '</p>'
      + '<button type="button" class="jp-btn jp-btn--primary" data-jpath-load-retry>Thử lại</button></div>';
  }

  /** A start button while its session opens: disabled, aria-busy, "Đang mở…" (the label comes back on failure). */
  function setBusy(btn, on) {
    if (!btn) return;
    btn.disabled = !!on;
    if (typeof btn.setAttribute !== 'function' || typeof btn.getAttribute !== 'function') return;
    btn.setAttribute('aria-busy', on ? 'true' : 'false');
    var saved = btn.getAttribute('data-label');
    if (on) {
      if (saved === null || saved === undefined) btn.setAttribute('data-label', btn.textContent);
      btn.textContent = 'Đang mở…';
    } else if (saved !== null && saved !== undefined) {
      btn.textContent = saved;
    }
  }

  /** F5 (owner 11/11): N4–N1 need Premium; a locked learner sees an upgrade box above the path. */
  function premiumBoxHtml(gate) {
    if (!gate || !gate.locked) return '';
    return '<section class="jp-premium-box" role="note">'
      + '<img src="/images/mascots/kitsu/kitsu-announcing.webp" alt="" width="56" height="56">'
      + '<div><p class="jp-premium-box__title">' + esc(gate.level || 'N4') + ' dành cho tài khoản Premium</p>'
      + '<p class="jp-premium-box__text">N5 học miễn phí. Từ N4 đến N1, hãy nâng cấp Premium để mở bài học hằng ngày.</p></div>'
      + '<a class="jp-btn jp-btn--primary" href="/premium">Nâng cấp Premium</a></section>';
  }

  function premiumBox(rootEl, apiClient) {
    if (!apiClient || !rootEl || typeof rootEl.insertAdjacentHTML !== 'function') return;
    Promise.resolve(apiClient.get('/api/learning/jlpt/premium-gate')).then(function (gate) {
      var html = premiumBoxHtml(gate);
      if (html && !rootEl.querySelector('.jp-premium-box')) rootEl.insertAdjacentHTML('afterbegin', html);
    }, function () { /* the server still blocks the sessions */ });
  }

  var api = Object.assign({}, C, {
    premiumBoxHtml: premiumBoxHtml, setBusy: setBusy, loadFailedHtml: loadFailedHtml,
    TAB_KEY: TAB_KEY, resolveTab: resolveTab, menuHtml: menuHtml, withoutTour: withoutTour, tabsBarHtml: tabsBarHtml, heroHtml: heroHtml,
    levelBarHtml: levelBarHtml, levelCellHtml: levelCellHtml, stageLineHtml: stageLineHtml, weekHtml: weekHtml, weekGuideHtml: weekGuideHtml, todayReason: todayReason,
    whyTodayHtml: whyTodayHtml, carryHtml: carryHtml, estimateHtml: estimateHtml, volumeHtml: volumeHtml, skillsTabHtml: skillsTabHtml, activateTab: activateTab,
    filterWeeklyButtons: filterWeeklyButtons,
    displayTarget: displayTarget, writeUrl: writeUrl, crumbsHtml: crumbsHtml, mount: mount, current: null
  });
  return api;
});
