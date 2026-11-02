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
 *   stageLineHtml(path), weekHtml(week)
 *   estimateHtml(path, tab), volumeHtml(path, tab), skillsTabHtml(path, today)
 *   UNIT, sliderHtml/newDraft/changeDraft/sliderBody, minutesLine, appliesNote   daily sliders (PUT /path/sliders)
 *   todayHtml(today, path, activeTab), reasonText   "Hôm nay của bạn" (type A: Gộp / Tách buttons)
 *   levelPreviewHtml, comingSoonHtml, warningsHtml   level dialog + pace warnings (Cân lại → POST /path/rebalance)
 *   startRequest(kind, tabKey, itemCode), onStart(req, deps)   POST /sessions → /learning/jlpt/study?session=<id>
 *   isStale(today, todayIso)         the page reloads /today when it becomes visible on a new day
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
  var TODAY_URL = '/api/learning/jlpt/today';
  var SESSIONS_URL = '/api/learning/jlpt/sessions';
  var ESTIMATE_URL = '/api/learning/jlpt/estimate';
  var SLIDERS_URL = '/api/learning/jlpt/path/sliders';
  var REBALANCE_URL = '/api/learning/jlpt/path/rebalance';
  var MODE_URL = '/api/learning/jlpt/path/mode';
  var REVIEW_DAY_URL = '/api/learning/jlpt/today/review-day';
  var PREVIEW_URL = '/api/learning/jlpt/levels/';
  var TAB_KEY = { kana: 'KANA', vocab: 'VOCAB', kanji: 'KANJI', grammar: 'GRAMMAR', skills: 'SKILL' };
  var TAB_LABEL = { kana: 'Chữ cái', vocab: 'Từ vựng', kanji: 'Kanji', grammar: 'Ngữ pháp', skills: 'Đọc · Nghe' };
  var VOLUME_UNIT = { kana: 'chữ', vocab: 'từ', kanji: 'chữ Kanji', grammar: 'mẫu' };
  var UNIT = { VOCAB: 'từ', KANJI: 'Kanji', GRAMMAR: 'mẫu' };
  var CODE_LABEL = { KANA: 'Chữ cái', VOCAB: 'Từ vựng', KANJI: 'Kanji', GRAMMAR: 'Ngữ pháp', SKILL: 'Đọc · Nghe' };
  var WEEKDAYS = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'];
  var SKILL_OPENS_AT = 80;
  var MSG_LOAD_FAILED = 'Không tải được lộ trình. Thử tải lại trang.';

  function D() { return root.JpathDisplay; }
  function esc(v) { return D().esc(v); }
  function list(v) { return Array.isArray(v) ? v : []; }
  function dayList(codes) { return list(codes).map(function (c) { return D().dayShort(c); }).filter(Boolean).join(', '); }

  function tabKeys(path) {
    var keys = ['vocab', 'kanji', 'grammar', 'skills'];
    return path && path.kanaTab ? ['kana'].concat(keys) : keys;
  }

  function defaultTab(path) {
    return path && path.kanaTab && !path.kanaCompleted ? 'kana' : 'vocab';
  }

  function resolveTab(param, path) {
    var key = typeof param === 'string' ? param.trim().toLowerCase() : '';
    if (!TAB_KEY[key]) return defaultTab(path);
    if (key === 'kana' && !(path && path.kanaTab)) return 'vocab';
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
      var soon = key === 'skills' ? ' <span class="jp-tab__soon">Sắp mở</span>' : '';
      var tour = key === 'skills' ? ' data-tour="skills"' : '';
      var info = tabInfo(path, key);
      if (key !== 'skills' && key !== 'kana' && info && info.open === false) soon = ' <span class="jp-tab__later">Sau chữ cái</span>';
      return '<button type="button" class="jp-tab' + (on ? ' jp-tab--active' : '') + '" role="tab" id="jp-tab-' + key
        + '" data-tab="' + key + '" aria-selected="' + on + '" aria-controls="jp-tab-panel" tabindex="'
        + (on ? '0' : '-1') + '"' + tour + '>' + esc(TAB_LABEL[key]) + soon + '</button>';
    }).join('');
    return '<div class="jp-tabs" role="tablist" aria-label="Phần học">' + html + '</div>';
  }

  // ------------------------------------------------------------------------------------------------ hero

  function targetLine(path) {
    var p = path || {};
    var line = 'Đích: ' + (p.targetLevel || '');
    if (!p.examDate) return line;
    return line + ' trước ' + D().fmtMonth(p.examDate) + (p.onTrack ? ' ✓ kịp' : ' ⚠ chưa kịp');
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
    var html = '<div class="jp-hero__text"><h1 class="jp-hero__title">Lộ trình của tôi</h1>'
      + '<p class="jp-hero__greet">Chào mừng trở lại, ' + esc(p.displayName || 'bạn') + '</p>'
      + '<p class="jp-hero__target' + tone + '">' + esc(targetLine(p)) + '</p>';
    if (t.resume && t.resume.total) {
      html += '<p class="jp-hero__resume">' + esc('Tiếp tục từ thẻ ' + t.resume.position + '/' + t.resume.total) + '</p>';
    }
    html += '</div>';
    if (cards.length) {
      html += '<div class="jp-hero__stack"><p class="jp-hero__motto">Một từ, một khởi đầu</p>'
        + '<ul class="jp-hero-cards">' + cards.map(heroCardHtml).join('') + '</ul>'
        + '<button type="button" class="jp-btn jp-btn--primary" data-action="start" data-item="'
        + esc(cards[0].itemCode) + '">Học từ này</button></div>';
    }
    return html;
  }

  // ------------------------------------------------------------------------------------------------ levels

  function levelCellHtml(cell) {
    var c = cell || {};
    var state = String(c.state || 'COMING_SOON');
    var name = esc(c.level);
    var body;
    if (state === 'PASSED') body = '<span class="jp-level-cell__name">' + name + '</span>'
      + '<button type="button" class="jp-level-cell__preview" data-view-level="' + name + '">✓ Đã vượt qua</button>';
    else if (state === 'OPEN') body = '<span class="jp-level-cell__name">' + name + '</span><span class="jp-level-cell__state">Đang học · '
      + esc(Number(c.percent) || 0) + '%</span>';
    else if (state === 'LOCKED') body = '<span class="jp-level-cell__name">🔒 ' + name + '</span>'
      + '<button type="button" class="jp-level-cell__preview" data-preview-level="' + name + '">Xem trước</button>';
    else body = '<span class="jp-level-cell__name">' + name + '</span>'
      + '<button type="button" class="jp-level-cell__preview" data-coming-soon="' + name + '">Sắp có</button>';
    if (c.note) body += '<span class="jp-level-cell__note">' + esc(c.note) + '</span>';
    return '<li class="jp-level-cell jp-level-cell--' + esc(state.toLowerCase()) + '"'
      + (state === 'OPEN' ? ' aria-current="step"' : '') + '>' + body + '</li>';
  }

  function levelBarHtml(path) {
    return '<section class="jp-card jp-levels" data-tour="level" aria-labelledby="jp-levels-title">'
      + '<h2 class="jp-section-title" id="jp-levels-title">Cấp độ của bạn</h2>'
      + '<ol class="jp-level-bar">' + list(path && path.levels).map(levelCellHtml).join('') + '</ol></section>';
  }

  // ------------------------------------------------------------------------------------------------ stage + week

  function stageLineHtml(path) {
    var p = path || {};
    var order = ['STUDY', 'SKILL_PRACTICE', 'EXAM'];
    var at = p.stage === 'PASSED' ? 3 : Math.max(0, order.indexOf(p.stage));
    var cell = openCell(p);
    var labels = ['Học' + (at === 0 ? ' ' + ((cell && Number(cell.percent)) || 0) + '%' : ''),
      'Rèn kỹ năng' + (at === 0 ? ' (mở ở ' + SKILL_OPENS_AT + '%)' : ''), 'Thi thử ' + (p.openLevel || '')];
    var steps = labels.map(function (label, i) {
      var mark = i < at ? '✓' : (i === at ? '●' : '○');
      return '<span class="jp-stage__step' + (i === at ? ' jp-stage__step--now' : '') + '">' + mark + ' ' + esc(label) + '</span>';
    }).join(' → ');
    var days = list(p.skillDays).length ? ' · Ngày kỹ năng: ' + dayList(p.skillDays) : '';
    if (list(p.restDays).length) days += ' · Nghỉ: ' + dayList(p.restDays);
    return '<p class="jp-stage">Chặng ' + esc(p.openLevel) + ': ' + steps + '<span class="jp-stage__days">'
      + esc(days) + '</span></p>';
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
      return '<li class="' + cls + '"' + (d.today ? ' aria-current="date"' : '') + title + '>'
        + esc(D().dayShort(weekday(d.day)) + (d.done ? ' ✓' : '')) + '</li>';
    }).join('');
    return '<ol class="jp-week" aria-label="Tuần này">' + chips + '</ol>';
  }

  // ------------------------------------------------------------------------------------------------ numbers

  function estimateHtml(path, tab) {
    var p = path || {};
    var est = (p.estimates || {})[TAB_KEY[tab]];
    return '<section class="jp-card jp-stat"><h2 class="jp-stat__title"><span aria-hidden="true">🕒</span> Dự kiến xong tab này</h2>'
      + '<p class="jp-stat__value">' + esc(est ? D().fmtDate(est) : '—') + '</p>'
      + '<p class="jp-stat__sub">' + esc('Cả cấp ' + (p.openLevel || '') + ' (muộn nhất 3 tab): '
        + (p.levelFinish ? D().fmtDate(p.levelFinish) : '—')) + '</p></section>';
  }

  function volumeHtml(path, tab) {
    var p = path || {};
    var info = tabInfo(p, tab) || {};
    return '<section class="jp-card jp-stat"><h2 class="jp-stat__title"><span aria-hidden="true">🎯</span> Khối lượng '
      + esc(p.openLevel) + '</h2><p class="jp-stat__value">' + esc(D().fmtNumber(Number(info.total) || 0) + ' ' + (VOLUME_UNIT[tab] || ''))
      + '</p><p class="jp-stat__sub">' + esc('Đã biết ' + D().fmtNumber(Number(info.known) || 0) + ' · Sổ tay '
        + D().fmtNumber(Number(info.notebook) || 0)) + '</p></section>';
  }

  function skillsTabHtml(path) {
    return '<section class="jp-card jp-skills" aria-labelledby="jp-skills-title">'
      + '<h2 class="jp-section-title" id="jp-skills-title">Đọc · Nghe <span class="jp-tab__soon">Sắp mở</span></h2>'
      + '<p>Phần luyện Đọc và Nghe đang được chuẩn bị.</p>'
      + (list(path && path.skillDays).length
        ? '<p class="jp-skills__days">' + esc('Ngày kỹ năng: ' + dayList(path && path.skillDays)) + '</p>' : '')
      + '<p class="jp-muted">Trong lúc chờ, ngày kỹ năng dùng luật tạm: ôn thẻ đến hạn và 1 quiz tự do từ 10 câu.</p></section>';
  }

  function numbersHtml(path, tab, today) {
    if (tab === 'skills') return skillsTabHtml(path, today);
    return estimateHtml(path, tab) + volumeHtml(path, tab);
  }

  // ------------------------------------------------------------------------------------------------ controls (Task 45)

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
      + '<input type="range" class="jp-slider__range" id="' + id + '" data-slider="' + c + '" min="' + esc(num(b.min))
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

  function chipsHtml(today) {
    var chips = list(today && today.tabs).map(function (t) {
      return '<li class="jp-chip">' + esc((CODE_LABEL[t.tab] || t.tab) + ' ' + num(t.done) + '/' + num(t.target)) + '</li>';
    }).join('');
    return chips ? '<ul class="jp-chips" aria-label="Tiến độ hôm nay">' + chips + '</ul>' : '';
  }

  function target(today, tab) {
    var t = list(today && today.tabs).filter(function (x) { return x && x.tab === tab; })[0];
    return t ? num(t.target) : 0;
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
        + '<button type="button" class="jp-btn' + (on ? ' jp-btn--primary jp-suggested' : '') + '" data-mode="' + def.mode
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
      return html + '<p class="jp-today__done">Bạn đã xong phần hôm nay ✓</p>'
        + '<a class="jp-btn" href="/learning/jlpt/done">Xem tổng kết hôm nay</a>' + missed + end;
    }
    if (t.kind === 'REST') {
      return html + '<p>Hôm nay là ngày nghỉ của bạn: không có phần bắt buộc.</p>' + missed + end;
    }
    if (t.kind === 'SKILL') {
      var line = 'Hôm nay là ngày kỹ năng: không học mới, ôn ' + num(t.reviewDue) + ' thẻ'
        + (t.quizRequired === false ? '.' : ' + 1 quiz tự do từ 10 câu.');
      if (t.skillMakeup) line += ' Hôm nay bù ngày kỹ năng đã lỡ.';
      return html + '<p>' + esc(line) + '</p>' + missed
        + '<button type="button" class="jp-btn jp-btn--primary" data-start-tab="skills">Bắt đầu ngày kỹ năng</button>' + end;
    }
    if (t.kind === 'REVIEW_ONLY') {
      return html + '<p>' + esc('Hôm nay là ngày ôn: chỉ ôn ' + num(t.reviewDue) + ' thẻ, không học mới.') + '</p>' + missed
        + '<button type="button" class="jp-btn jp-btn--primary" data-mode="COMBINED">Bắt đầu ôn</button>' + end;
    }
    var summary = t.kanaPhase
      ? num(t.kanaRows || 2) + ' hàng chữ cái · ' + target(t, 'VOCAB') + ' từ mới · khoảng ' + num(t.estimatedMinutes) + ' phút'
      : 'Ôn ' + num(t.reviewDue) + ' thẻ · ' + target(t, 'VOCAB') + ' từ mới · ' + target(t, 'KANJI') + ' Kanji mới · '
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
    return rows ? '<ul class="jp-warnings" aria-label="Lưu ý về lộ trình">' + rows + '</ul>' : '';
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
      var id = res && (res.sessionId != null ? res.sessionId : res.id);
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

  function openDialog(doc, html, opener) {
    if (!doc || !doc.body) return null;
    var box = doc.createElement('div');
    box.className = 'jp-dialog-backdrop';
    box.innerHTML = '<div class="jp-dialog jp-card" role="dialog" aria-modal="true" aria-labelledby="jp-dialog-title"'
      + ' tabindex="-1">' + html + '<button type="button" class="jp-btn" data-dialog-close>Đóng</button></div>';
    var panel = box.firstChild;
    function close() {
      doc.removeEventListener('keydown', onKey, true);
      if (box.parentNode) box.parentNode.removeChild(box);
      if (opener && typeof opener.focus === 'function') opener.focus();
    }
    function onKey(ev) { if (ev.key === 'Escape') { ev.preventDefault(); close(); } }
    box.addEventListener('click', function (ev) {
      if (ev.target === box || (ev.target.closest && ev.target.closest('[data-dialog-close]'))) close();
    });
    doc.addEventListener('keydown', onKey, true);
    doc.body.appendChild(box);
    panel.focus();
    return { close: close, el: panel };
  }

  function mount(rootEl, deps) {
    var d = deps || {};
    var apiClient = d.api || root.JpathApi;
    var loc = d.location || root.location;
    var doc = d.document || root.document;
    var rendered = d.rendered || function (el) { D().rendered(el); };
    var state = { path: null, today: null, tab: null, draft: null, viewLevel: null };
    var env = { location: loc, history: d.history || root.history };
    var estimateTimer = null;
    var estimateSeq = 0;

    function paintControls() {
      var p = state.path;
      setSlot(rootEl, 'slider', sliderHtml(p.sliders, state.tab, p.openLevel, state.draft) + warningsHtml(p.warnings));
      setSlot(rootEl, 'today', todayHtml(state.today, p, state.tab));
    }

    function paintTab(keepTabs) {
      var buttons = keepTabs ? all(rootEl, '[role="tab"]') : [];
      if (buttons.length) activateTab(buttons, state.tab, true);
      else setHtml(rootEl, 'tabs', tabsBarHtml(state.path, state.tab));
      setHtml(rootEl, 'numbers', numbersHtml(state.path, state.tab, state.today));
      paintControls();
      if (rootEl.setAttribute) rootEl.setAttribute('data-jpath-tab', state.tab);
    }

    function selectTab(key) {
      var next = resolveTab(key, state.path);
      if (next === state.tab) return;
      state.tab = next;
      writeTab(env, next);
      paintTab(true);
    }

    function paintAll() {
      var p = state.path;
      if (p.display && rootEl.classList) D().applyDisplay(displayTarget(rootEl), p.display);
      setHtml(rootEl, 'hero', heroHtml(p, state.today));
      setHtml(rootEl, 'stage', stageLineHtml(p) + weekHtml(p.week));
      setHtml(rootEl, 'levels', levelBand() + levelBarHtml(p));
      paintTab(false);
    }

    function levelBand() {
      if (!state.viewLevel) return '';
      return '<p class="jp-band" role="status">' + esc('Đang xem ' + state.viewLevel + ' (đã vượt qua): ôn tự do, không tính tiến độ.')
        + ' <button type="button" class="jp-link-btn" data-action="back-to-open">Về cấp đang học</button></p>';
    }

    function viewLevel(level) {
      state.viewLevel = level;
      setHtml(rootEl, 'levels', levelBand() + levelBarHtml(state.path));
      if (rootEl.dispatchEvent && typeof root.CustomEvent === 'function') {
        rootEl.dispatchEvent(new root.CustomEvent('jpath:view-level', { detail: { level: level || state.path.openLevel,
          passed: !!level }, bubbles: true }));
      }
    }

    function reload() {
      return Promise.all([apiClient.get(PATH_URL), apiClient.get(TODAY_URL).catch(function () { return null; })])
        .then(function (res) {
          state.path = res[0] || {};
          state.today = res[1];
          state.draft = newDraft(state.path.sliders);
          paintAll();
        });
    }

    function scheduleEstimate() {
      if (estimateTimer) clearTimeout(estimateTimer);
      estimateTimer = setTimeout(function () {
        var seq = ++estimateSeq;
        apiClient.send('POST', ESTIMATE_URL, estimateBody(state.path, state.draft)).then(function (pace) {
          if (seq !== estimateSeq || !pace) return;
          var wrap = part(rootEl, 'minutes-wrap');
          if (wrap) wrap.innerHTML = minutesLine(pace);
          state.path.estimates = pace.tabFinish || state.path.estimates;
          state.path.levelFinish = pace.levelFinish || state.path.levelFinish;
          setHtml(rootEl, 'numbers', numbersHtml(state.path, state.tab, state.today));
        }, function () { /* the saved estimate stays */ });
      }, 300);
    }

    function startWith(req, errorPart) {
      return api.onStart(req, { api: apiClient }).catch(function (e) {
        showError(part(rootEl, errorPart || 'today-error'), e && e.message);
      });
    }

    rootEl.addEventListener('click', function (ev) {
      var t = ev.target;
      if (!t || typeof t.closest !== 'function' || !state.path) return;
      var tabBtn = t.closest('[data-tab]');
      if (tabBtn) { selectTab(tabBtn.getAttribute('data-tab')); return; }
      var flip = t.closest('[data-flip]');
      if (flip) {
        var on = flip.getAttribute('aria-pressed') !== 'true';
        flip.setAttribute('aria-pressed', String(on));
        return;
      }
      var startBtn = t.closest('[data-action="start"]');
      if (startBtn) { startWith(startRequest('ITEM', 'vocab', startBtn.getAttribute('data-item'))); return; }
      var save = t.closest('[data-action="save-sliders"]');
      if (save) {
        save.disabled = true;
        apiClient.send('PUT', SLIDERS_URL, sliderBody(state.draft)).then(reload, function (e) {
          save.disabled = false;
          showError(part(rootEl, 'slider-error'), e && e.message);
        });
        return;
      }
      var rebalance = t.closest('[data-action="rebalance"]');
      if (rebalance) {
        rebalance.disabled = true;
        apiClient.send('POST', REBALANCE_URL).then(reload, function (e) {
          rebalance.disabled = false;
          showError(part(rootEl, 'slider-error'), e && e.message);
        });
        return;
      }
      var defMode = t.closest('[data-action="default-mode"]');
      if (defMode) {
        apiClient.send('PUT', MODE_URL, { mode: defMode.getAttribute('data-mode') }).then(reload, function (e) {
          showError(part(rootEl, 'today-error'), e && e.message);
        });
        return;
      }
      var reviewDay = t.closest('[data-action="review-day"]');
      if (reviewDay) {
        reviewDay.disabled = true;
        apiClient.send('POST', REVIEW_DAY_URL).then(reload, function (e) {
          reviewDay.disabled = false;
          showError(part(rootEl, 'today-error'), e && e.message);
        });
        return;
      }
      var modeBtn = t.closest('[data-mode]');
      if (modeBtn) {
        modeBtn.disabled = true;
        var req = modeBtn.getAttribute('data-mode') === 'SPLIT'
          ? startRequest('TAB', modeBtn.getAttribute('data-tab-key') || state.tab) : startRequest('COMBINED');
        startWith(req).then(function () { modeBtn.disabled = false; });
        return;
      }
      var tabStart = t.closest('[data-start-tab]');
      if (tabStart) { startWith(startRequest('TAB', tabStart.getAttribute('data-start-tab'))); return; }
      var previewBtn = t.closest('[data-preview-level]');
      if (previewBtn) {
        var lv = previewBtn.getAttribute('data-preview-level');
        apiClient.get(PREVIEW_URL + encodeURIComponent(lv) + '/preview').then(function (pv) {
          openDialog(doc, levelPreviewHtml(pv, state.path.openLevel), previewBtn);
        }, function (e) { openDialog(doc, '<p id="jp-dialog-title">' + esc(e && e.message) + '</p>', previewBtn); });
        return;
      }
      var soon = t.closest('[data-coming-soon]');
      if (soon) { openDialog(doc, comingSoonHtml(soon.getAttribute('data-coming-soon')), soon); return; }
      var passed = t.closest('[data-view-level]');
      if (passed) { viewLevel(passed.getAttribute('data-view-level')); return; }
      if (t.closest('[data-action="back-to-open"]')) viewLevel(null);
    });

    rootEl.addEventListener('input', function (ev) {
      var t = ev.target;
      if (!t || typeof t.getAttribute !== 'function' || !t.getAttribute('data-slider') || !state.path) return;
      var c = t.getAttribute('data-slider');
      state.draft = changeDraft(state.draft, c, t.value);
      var label = part(rootEl, 'slider-value');
      if (label) label.textContent = 'Mỗi ngày học ' + state.draft.values[c] + ' ' + UNIT[c];
      var save = rootEl.querySelector('[data-action="save-sliders"]');
      if (save) save.disabled = !state.draft.dirty;
      scheduleEstimate();
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

    if (doc && typeof doc.addEventListener === 'function') {
      doc.addEventListener('visibilitychange', function () {
        if (doc.visibilityState === 'visible' && state.path && isStale(state.today, localIso())) reload().catch(function () {});
      });
    }

    var todayLoad = Promise.resolve().then(function () { return apiClient.get(TODAY_URL); })
      .catch(function () { return null; });
    var pathLoad = Promise.resolve().then(function () { return apiClient.get(PATH_URL); });
    return Promise.all([pathLoad, todayLoad]).then(function (res) {
      state.path = res[0] || {};
      state.today = res[1];
      state.draft = newDraft(state.path.sliders);
      state.tab = resolveTab(readParam(loc) || (rootEl.getAttribute && rootEl.getAttribute('data-jpath-tab')), state.path);
      writeTab(env, state.tab);
      paintAll();
      api.current = state;
      rootEl.setAttribute('aria-busy', 'false');
      rendered(rootEl);
    }, function () {
      setHtml(rootEl, 'hero', '<p class="jp-alert" role="alert">' + esc(MSG_LOAD_FAILED) + '</p>');
      rootEl.setAttribute('aria-busy', 'false');
    });
  }

  var api = {
    TAB_KEY: TAB_KEY, UNIT: UNIT, resolveTab: resolveTab, tabsBarHtml: tabsBarHtml, heroHtml: heroHtml,
    levelBarHtml: levelBarHtml, levelCellHtml: levelCellHtml, stageLineHtml: stageLineHtml, weekHtml: weekHtml,
    estimateHtml: estimateHtml, volumeHtml: volumeHtml, skillsTabHtml: skillsTabHtml,
    sliderHtml: sliderHtml, newDraft: newDraft, changeDraft: changeDraft, sliderBody: sliderBody, minutesLine: minutesLine,
    appliesNote: appliesNote, todayHtml: todayHtml, reasonText: reasonText, levelPreviewHtml: levelPreviewHtml,
    comingSoonHtml: comingSoonHtml, warningsHtml: warningsHtml, startRequest: startRequest, onStart: onStart,
    isStale: isStale, localIso: localIso, estimateBody: estimateBody, activateTab: activateTab,
    displayTarget: displayTarget, writeUrl: writeUrl, mount: mount, current: null
  };
  return api;
});
