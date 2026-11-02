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
 *   onStart                          set by the study flow; called with the item code of "Học từ này"
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
  var TAB_KEY = { kana: 'KANA', vocab: 'VOCAB', kanji: 'KANJI', grammar: 'GRAMMAR', skills: 'SKILL' };
  var TAB_LABEL = { kana: 'Chữ cái', vocab: 'Từ vựng', kanji: 'Kanji', grammar: 'Ngữ pháp', skills: 'Đọc · Nghe' };
  var UNIT = { kana: 'chữ', vocab: 'từ', kanji: 'chữ Kanji', grammar: 'mẫu' };
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
    return '<li class="jp-hero-card jp-hero-card--' + index + '" data-start-item="' + esc(c.itemCode) + '">'
      + '<button type="button" class="jp-hero-card__face" data-flip aria-pressed="false" aria-label="Lật xem nghĩa">'
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
    if (state === 'PASSED') body = '<span class="jp-level-cell__name">' + name + '</span><span class="jp-level-cell__state">✓ Đã vượt qua</span>';
    else if (state === 'OPEN') body = '<span class="jp-level-cell__name">' + name + '</span><span class="jp-level-cell__state">Đang học · '
      + esc(Number(c.percent) || 0) + '%</span>';
    else if (state === 'LOCKED') body = '<span class="jp-level-cell__name">🔒 ' + name + '</span>'
      + '<button type="button" class="jp-level-cell__preview" data-preview-level="' + name + '">Xem trước</button>';
    else body = '<span class="jp-level-cell__name">' + name + '</span><span class="jp-level-cell__state">Sắp có</span>';
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
    var days = ' · Ngày kỹ năng: ' + dayList(p.skillDays);
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
        + (d.kind === 'SKILL' ? ' jp-day--skill' : '') + (d.kind === 'REST' ? ' jp-day--rest' : '');
      var title = d.kind === 'SKILL' ? ' title="Ngày kỹ năng"' : (d.kind === 'REST' ? ' title="Ngày nghỉ"' : '');
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
      + esc(p.openLevel) + '</h2><p class="jp-stat__value">' + esc(D().fmtNumber(Number(info.total) || 0) + ' ' + (UNIT[tab] || ''))
      + '</p><p class="jp-stat__sub">' + esc('Đã biết ' + D().fmtNumber(Number(info.known) || 0) + ' · Sổ tay '
        + D().fmtNumber(Number(info.notebook) || 0)) + '</p></section>';
  }

  function skillsTabHtml(path) {
    return '<section class="jp-card jp-skills" aria-labelledby="jp-skills-title">'
      + '<h2 class="jp-section-title" id="jp-skills-title">Đọc · Nghe <span class="jp-tab__soon">Sắp mở</span></h2>'
      + '<p>Phần luyện Đọc và Nghe đang được chuẩn bị.</p>'
      + '<p class="jp-skills__days">' + esc('Ngày kỹ năng: ' + dayList(path && path.skillDays)) + '</p>'
      + '<p class="jp-muted">Trong lúc chờ, ngày kỹ năng dùng luật tạm: ôn thẻ đến hạn và 1 quiz tự do từ 10 câu.</p></section>';
  }

  function numbersHtml(path, tab, today) {
    if (tab === 'skills') return skillsTabHtml(path, today);
    return estimateHtml(path, tab) + volumeHtml(path, tab);
  }

  // ------------------------------------------------------------------------------------------------ mount

  function region(rootEl, name) { return rootEl.querySelector('[data-region="' + name + '"]'); }
  function setHtml(rootEl, name, html) { var el = region(rootEl, name); if (el) el.innerHTML = html; }

  function writeTab(deps, tab) {
    var loc = deps.location;
    if (!deps.history || !loc) return;
    try { deps.history.replaceState(null, '', (loc.pathname || '/learning') + '?tab=' + tab); } catch (e) { /* ignore */ }
  }

  function readParam(loc) {
    var m = /[?&]tab=([^&#]*)/.exec((loc && loc.search) || '');
    try { return m ? decodeURIComponent(m[1]) : null; } catch (e) { return null; }
  }

  function mount(rootEl, deps) {
    var d = deps || {};
    var apiClient = d.api || root.JpathApi;
    var loc = d.location || root.location;
    var rendered = d.rendered || function (el) { D().rendered(el); };
    var state = { path: null, today: null, tab: null };
    var env = { location: loc, history: d.history || root.history };

    function paintTab() {
      setHtml(rootEl, 'tabs', tabsBarHtml(state.path, state.tab));
      setHtml(rootEl, 'numbers', numbersHtml(state.path, state.tab, state.today));
      if (rootEl.setAttribute) rootEl.setAttribute('data-jpath-tab', state.tab);
    }

    function selectTab(key, focus) {
      var next = resolveTab(key, state.path);
      if (next === state.tab) return;
      state.tab = next;
      writeTab(env, next);
      paintTab();
      if (focus && root.document) { var b = root.document.getElementById('jp-tab-' + next); if (b) b.focus(); }
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
      if (startBtn && typeof api.onStart === 'function') api.onStart(startBtn.getAttribute('data-item'));
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
      if (next) { ev.preventDefault(); selectTab(next, true); }
    });

    var todayLoad = Promise.resolve().then(function () { return apiClient.get(TODAY_URL); })
      .catch(function () { return null; });
    var pathLoad = Promise.resolve().then(function () { return apiClient.get(PATH_URL); });
    return Promise.all([pathLoad, todayLoad]).then(function (res) {
      state.path = res[0] || {};
      state.today = res[1];
      state.tab = resolveTab(readParam(loc) || (rootEl.getAttribute && rootEl.getAttribute('data-jpath-tab')), state.path);
      if (state.path.display && rootEl.classList) D().applyDisplay(rootEl, state.path.display);
      setHtml(rootEl, 'hero', heroHtml(state.path, state.today));
      setHtml(rootEl, 'stage', stageLineHtml(state.path) + weekHtml(state.path.week));
      setHtml(rootEl, 'levels', levelBarHtml(state.path));
      writeTab(env, state.tab);
      paintTab();
      api.current = state;
      rootEl.setAttribute('aria-busy', 'false');
      rendered(rootEl);
    }, function () {
      setHtml(rootEl, 'hero', '<p class="jp-alert" role="alert">' + esc(MSG_LOAD_FAILED) + '</p>');
      rootEl.setAttribute('aria-busy', 'false');
    });
  }

  var api = {
    TAB_KEY: TAB_KEY, resolveTab: resolveTab, tabsBarHtml: tabsBarHtml, heroHtml: heroHtml, levelBarHtml: levelBarHtml,
    levelCellHtml: levelCellHtml, stageLineHtml: stageLineHtml, weekHtml: weekHtml, estimateHtml: estimateHtml,
    volumeHtml: volumeHtml, skillsTabHtml: skillsTabHtml, mount: mount, onStart: null, current: null
  };
  return api;
});
