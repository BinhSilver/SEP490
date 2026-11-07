/* cpath-path.js — Trang Bản đồ & Tổng quan Lộ trình Giao tiếp (path.html).
 * Dạng UMD: window.CpathPath, dùng chung window.JpathApi.get / .send.
 * Dùng cùng phong cách với lộ trình JLPT Lượt 6: bảng màu 9 họ, Kitsu động,
 * mây mềm, đồi 3 lớp, biển gỗ tuần, cảnh lề, nút tròn lớn và bong bóng gắn nút.
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.CpathPath = api;
  if (root && root.document && typeof module === 'undefined') {
    if (root.document.readyState === 'loading') {
      root.document.addEventListener('DOMContentLoaded', function () { api.mount(); });
    } else {
      api.mount();
    }
  }
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var WAVE_POS = ['center', 'right', 'center', 'left'];
  var WAVE_X = { left: 95, center: 240, right: 385 };
  var SIDE_KITSUS = [
    { file: 'kitsu-cheer-30.webp', alt: 'Kitsu vẫy tay', say: 'Cùng luyện giao tiếp nhé!' },
    { file: 'kitsu-thinking-30.webp', alt: 'Kitsu chăm học', say: 'Nghe kỹ từng câu nha!' },
    { file: 'kitsu-cheer-30.webp', alt: 'Kitsu vui vẻ', say: 'Phản xạ tốt lắm!' },
    { file: 'kitsu-celebrate-30.webp', alt: 'Kitsu cổ vũ', say: 'Sắp hoàn thành chủ đề rồi!' }
  ];

  var LEVEL_VI = {
    BEGINNER: 'Mới bắt đầu',
    ELEMENTARY: 'Sơ cấp',
    INTERMEDIATE: 'Trung cấp'
  };

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function list(v) { return Array.isArray(v) ? v : []; }

  function kitsuSrc(file) {
    if (root && root.CpathMock && typeof root.CpathMock.kitsuSrc === 'function') {
      return root.CpathMock.kitsuSrc(file);
    }
    return '/images/mascots/kitsu/' + file;
  }

  function pageUrl(page, params) {
    if (root && root.CpathMock && typeof root.CpathMock.pageUrl === 'function') {
      return root.CpathMock.pageUrl(page, params);
    }
    var base = '/learning/comm/' + page;
    var q = [];
    if (params) {
      Object.keys(params).forEach(function (k) {
        if (params[k] != null && params[k] !== '') q.push(encodeURIComponent(k) + '=' + encodeURIComponent(params[k]));
      });
    }
    return q.length ? (base + '?' + q.join('&')) : base;
  }

  function topicLabel(code, fallbackName) {
    if (fallbackName) return fallbackName;
    if (root && root.CpathMock && root.CpathMock.TOPIC_LABELS && root.CpathMock.TOPIC_LABELS[code]) {
      return root.CpathMock.TOPIC_LABELS[code];
    }
    return code || 'Chủ đề giao tiếp';
  }

  function fmtShortDate(isoStr) {
    var m = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(isoStr || ''));
    if (!m) return String(isoStr || '');
    return m[3] + '/' + m[2];
  }

  /* --- Biểu tượng SVG nét mảnh đồng bộ Lượt 6 --- */
  function svgMedal(extraCls) {
    var cls = 'jp-parts__icon jp-morph-icon jp-morph-icon--medal' + (extraCls ? ' ' + extraCls : '');
    return '<svg class="' + cls + '" viewBox="0 0 28 28" fill="none" aria-hidden="true">'
      + '<path class="jp-medal__shackle" d="M10.5 11V8.5a3.5 3.5 0 0 1 7 0V11" stroke="var(--jp-c-peach-100)" stroke-width="1.6" stroke-linecap="round"/>'
      + '<path class="jp-medal__ribbon" d="M10.2 16.2L8 23.8l4.2-1.8L13.5 17.8M17.8 16.2L20 23.8l-4.2-1.8L14.5 17.8" fill="var(--jp-c-peach-500)" stroke="var(--jp-c-peach-900)" stroke-width="1.4" stroke-linejoin="round"/>'
      + '<circle class="jp-medal__disc" cx="14" cy="11.5" r="6.4" fill="var(--jp-c-peach-100)" stroke="var(--jp-c-peach-900)" stroke-width="1.6"/>'
      + '<path class="jp-medal__star" d="M14 7.8l1.15 2.3 2.55.37-1.85 1.8.44 2.53L14 13.6l-2.29 1.2.44-2.53-1.85-1.8 2.55-.37L14 7.8z" fill="var(--jp-c-peach-500)" stroke="var(--jp-c-peach-900)" stroke-width="1.1" stroke-linejoin="round"/>'
      + '</svg>';
  }

  function svgStar() {
    return '<svg class="jp-parts__icon jp-morph-icon jp-morph-icon--star" viewBox="0 0 24 24" fill="none" aria-hidden="true">'
      + '<path class="jp-star__path" d="M12 3.6l2.55 5.15 5.68.83-4.11 4.01.97 5.66L12 16.58l-5.09 2.67.97-5.66-4.11-4.01 5.68-.83L12 3.6z" fill="currentColor" fill-opacity="0.22" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>'
      + '</svg>';
  }

  function svgLock() {
    return '<svg class="jp-parts__icon jp-morph-icon jp-morph-icon--lock" viewBox="0 0 24 24" fill="none" aria-hidden="true">'
      + '<rect x="5.5" y="11" width="13" height="9.5" rx="2.5" stroke="currentColor" stroke-width="1.8"/>'
      + '<path d="M8.2 11V8.2a3.8 3.8 0 0 1 7.6 0V11" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>'
      + '<circle cx="12" cy="15.8" r="1.3" fill="currentColor"/>'
      + '</svg>';
  }

  function svgDialogueBubble() {
    return '<svg class="jp-parts__icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">'
      + '<path d="M4 6.5A2.5 2.5 0 0 1 6.5 4h11A2.5 2.5 0 0 1 20 6.5v7a2.5 2.5 0 0 1-2.5 2.5H10l-4.5 3.5V16H6.5A2.5 2.5 0 0 1 4 13.5v-7z" fill="currentColor" fill-opacity="0.14" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>'
      + '<path d="M8.5 9h7M8.5 12h4.5" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>'
      + '</svg>';
  }

  /* Mốc cuối chủ đề: Biểu tượng Micro cho Bài nhập vai (TOPIC_TEST) */
  function svgMicRoleplay(extraCls) {
    var cls = 'jp-parts__icon' + (extraCls ? ' ' + extraCls : '');
    return '<svg class="' + cls + '" viewBox="0 0 28 28" fill="none" aria-hidden="true">'
      + '<rect x="10" y="4" width="8" height="12.5" rx="4" fill="currentColor" fill-opacity="0.18" stroke="currentColor" stroke-width="1.9"/>'
      + '<path d="M6.5 12.5a7.5 7.5 0 0 0 15 0M14 20v4M10 24h8" stroke="currentColor" stroke-width="1.9" stroke-linecap="round"/>'
      + '</svg>';
  }

  /* Mốc cuối mục tiêu: Rương / Cúp lớn 96px cho Bài test với AI (GOAL_TEST) */
  function svgGoalTestChest(state) {
    if (state === 'open') {
      return '<svg class="jp-parts__big-chest jp-parts__big-chest--open" viewBox="0 0 96 96" width="96" height="96" fill="none" aria-hidden="true">'
        + '<ellipse cx="48" cy="84" rx="34" ry="7" fill="var(--jp-chest-shadow)"/>'
        + '<ellipse cx="48" cy="36" rx="28" ry="17" fill="var(--jp-c-peach-100)"/>'
        + '<path d="M48 7v13M28 12l6.5 10.5M68 12l-6.5 10.5M17 24l10 6.5M79 24l-10 6.5" stroke="var(--jp-c-peach-500)" stroke-width="2.4" stroke-linecap="round"/>'
        + '<path d="M20 40C20 26 31 19 48 19s28 7 28 21H20z" fill="var(--jp-c-peach-500)" stroke="var(--jp-chest-outline)" stroke-width="2.5" stroke-linejoin="round"/>'
        + '<rect x="18" y="42" width="60" height="36" rx="7" fill="var(--jp-c-peach-500)" stroke="var(--jp-chest-outline)" stroke-width="2.5"/>'
        + '<rect x="40" y="39" width="16" height="17" rx="4" fill="var(--jp-c-peach-100)" stroke="var(--jp-chest-outline)" stroke-width="2.2"/>'
        + '<circle cx="48" cy="47.5" r="3" fill="var(--jp-c-purple-500)"/>'
        + '</svg>';
    }
    return '<svg class="jp-parts__big-chest jp-parts__big-chest--current" viewBox="0 0 96 96" width="96" height="96" fill="none" aria-hidden="true">'
      + '<ellipse cx="48" cy="84" rx="34" ry="7" fill="var(--jp-chest-shadow)"/>'
      + '<path d="M18 46C18 29 30 22 48 22s30 7 30 24H18z" fill="var(--jp-c-purple-500)" stroke="var(--jp-chest-outline)" stroke-width="2.5" stroke-linejoin="round"/>'
      + '<path d="M33 23.5v22M63 23.5v22" stroke="var(--jp-c-peach-100)" stroke-width="3" stroke-linecap="round"/>'
      + '<rect x="18" y="45" width="60" height="33" rx="7" fill="var(--jp-c-purple-500)" stroke="var(--jp-chest-outline)" stroke-width="2.5"/>'
      + '<rect x="21" y="48" width="54" height="11" rx="3" fill="var(--jp-c-purple-100)" fill-opacity="0.38"/>'
      + '<rect x="30" y="45" width="6" height="33" rx="1.5" fill="var(--jp-c-peach-100)" stroke="var(--jp-chest-outline)" stroke-width="1.8"/>'
      + '<rect x="60" y="45" width="6" height="33" rx="1.5" fill="var(--jp-c-peach-100)" stroke="var(--jp-chest-outline)" stroke-width="1.8"/>'
      + '<rect x="39" y="38" width="18" height="19" rx="5" fill="var(--jp-c-peach-100)" stroke="var(--jp-chest-outline)" stroke-width="2.3"/>'
      + '<rect x="45" y="41.5" width="6" height="8.5" rx="3" stroke="var(--jp-c-purple-900)" stroke-width="1.8"/>'
      + '<path d="M43 47a5 5 0 0 0 10 0M48 52v2.5" stroke="var(--jp-c-purple-900)" stroke-width="1.7" stroke-linecap="round"/>'
      + '</svg>';
  }

  function svgChevron(dir) {
    var d = dir === 'left' ? 'M11.5 4.5L6.5 9.5L11.5 14.5' : 'M7.5 4.5L12.5 9.5L7.5 14.5';
    return '<svg class="jp-morph-icon jp-morph-icon--chevron" viewBox="0 0 18 18" width="16" height="16" fill="none" aria-hidden="true">'
      + '<path class="jp-chevron__head" d="' + d + '" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>'
      + '</svg>';
  }

  function smallCloudSvg(cls) {
    return '<svg class="jp-parts__cloud ' + cls + '" viewBox="0 0 128 52" fill="none" aria-hidden="true">'
      + '<path d="M26 42h76a13 13 0 0 0 1.5-25.9A20 20 0 0 0 66 8a17 17 0 0 0-31.2 6.8A14.5 14.5 0 0 0 26 42z"'
      + ' fill="var(--jp-cloud-fill)" stroke="var(--jp-cloud-stroke)" stroke-width="1.25" stroke-linecap="round" stroke-linejoin="round"/>'
      + '</svg>';
  }

  function sceneBackdropHtml(sceneKey) {
    return '<div class="jp-parts__backdrop jp-parts__backdrop--' + esc(sceneKey) + '" aria-hidden="true">'
      + '<div class="jp-parts__sky-pattern jp-parts__sky-pattern--' + esc(sceneKey) + '"></div>'
      + '<div class="jp-parts__clouds">'
      + smallCloudSvg('jp-parts__cloud--1')
      + smallCloudSvg('jp-parts__cloud--2')
      + smallCloudSvg('jp-parts__cloud--3')
      + smallCloudSvg('jp-parts__cloud--4')
      + '</div>'
      + '<svg class="jp-parts__hills" viewBox="0 0 1200 150" preserveAspectRatio="none">'
      + '<path class="jp-parts__hill jp-parts__hill--far" d="M0 78 Q240 22 520 68 T1040 50 L1200 66 L1200 150 L0 150 Z"/>'
      + '<path class="jp-parts__hill jp-parts__hill--mid" d="M0 98 Q320 46 660 94 T1200 74 L1200 150 L0 150 Z"/>'
      + '<path class="jp-parts__hill jp-parts__hill--near" d="M0 118 Q260 84 580 116 T1200 100 L1200 150 L0 150 Z"/>'
      + '</svg>'
      + '</div>';
  }

  function weekSignpostHtml(weekObj, side) {
    var range = fmtShortDate(weekObj.from) + '–' + fmtShortDate(weekObj.to);
    return '<div class="jp-parts__week-sign jp-parts__week-sign--' + side + '" aria-hidden="true">'
      + '<span class="jp-parts__week-sign-post"></span>'
      + '<span class="jp-parts__week-sign-board">'
      + '<strong class="jp-parts__week-sign-title">Tuần ' + esc(weekObj.index) + '</strong>'
      + '<span class="jp-parts__week-sign-dates">' + esc(range) + '</span>'
      + '</span>'
      + '</div>';
  }

  function weekThemeSceneHtml(weekObj, side) {
    var items = list(weekObj && weekObj.items);
    var firstTopic = (items[0] && items[0].topicCode) || 'AIR';
    var caption = topicLabel(firstTopic, items[0] && items[0].topicNameVi);
    var svg = '';
    if (/AIR|TRAIN|TRAVEL/.test(firstTopic)) {
      svg = '<svg class="jp-parts__week-scene-svg jp-parts__scene-svg" viewBox="0 0 112 78" fill="none" aria-hidden="true">'
        + '<ellipse cx="56" cy="68" rx="46" ry="6.5" fill="var(--jp-tab-100)"/>'
        + '<path d="M16 62L46 20l30 42H16z" fill="var(--jp-c-sky-100)" stroke="var(--jp-c-sky-500)" stroke-width="1.75" stroke-linejoin="round"/>'
        + '<path d="M36.5 33.5L46 20l9.5 13.5-4.8-2.6-4.7 3.6-4.7-3.6-4.8 2.6z" fill="var(--jp-white)" stroke="var(--jp-c-sky-500)" stroke-width="1.5" stroke-linejoin="round"/>'
        + '<rect x="30" y="48" width="64" height="17" rx="6" fill="var(--jp-c-indigo-100)" stroke="var(--jp-c-indigo-500)" stroke-width="1.75"/>'
        + '<rect x="37" y="52" width="10" height="6.5" rx="1.5" fill="var(--jp-white)" stroke="var(--jp-c-indigo-500)" stroke-width="1.4"/>'
        + '<rect x="52" y="52" width="10" height="6.5" rx="1.5" fill="var(--jp-white)" stroke="var(--jp-c-indigo-500)" stroke-width="1.4"/>'
        + '<rect x="67" y="52" width="10" height="6.5" rx="1.5" fill="var(--jp-white)" stroke="var(--jp-c-indigo-500)" stroke-width="1.4"/>'
        + '</svg>';
    } else if (/FOOD|MARKET/.test(firstTopic)) {
      svg = '<svg class="jp-parts__week-scene-svg jp-parts__scene-svg" viewBox="0 0 112 78" fill="none" aria-hidden="true">'
        + '<ellipse cx="56" cy="68" rx="46" ry="6.5" fill="var(--jp-tab-100)"/>'
        + '<path d="M24 38h44M26 33.5h40" stroke="var(--jp-c-peach-500)" stroke-width="1.75" stroke-linecap="round"/>'
        + '<path d="M28 43c0-8.5 7.5-14 17-14s17 5.5 17 14H28z" fill="var(--jp-white)" stroke="var(--jp-c-indigo-500)" stroke-width="1.75" stroke-linejoin="round"/>'
        + '<path d="M24 43h42c-2 14-9.5 21-21 21S26 57 24 43z" fill="var(--jp-c-coral-100)" stroke="var(--jp-c-coral-500)" stroke-width="1.75" stroke-linejoin="round"/>'
        + '<rect x="74" y="44" width="17" height="20" rx="4" fill="var(--jp-c-mint-100)" stroke="var(--jp-c-mint-500)" stroke-width="1.75"/>'
        + '</svg>';
    } else {
      svg = '<svg class="jp-parts__week-scene-svg jp-parts__scene-svg" viewBox="0 0 112 78" fill="none" aria-hidden="true">'
        + '<ellipse cx="56" cy="68" rx="46" ry="6.5" fill="var(--jp-tab-100)"/>'
        + '<rect x="24" y="36" width="48" height="31" rx="5" fill="var(--jp-c-peach-100)" stroke="var(--jp-c-peach-500)" stroke-width="1.75"/>'
        + '<path d="M17 37C26 29 37 20 48 14c2.8-1.5 5.2-1.5 8 0 11 6 22 15 23 23H17z" fill="var(--jp-c-coral-100)" stroke="var(--jp-c-coral-500)" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"/>'
        + '<rect x="42.5" y="46" width="11" height="21" rx="2.5" fill="var(--jp-white)" stroke="var(--jp-c-peach-500)" stroke-width="1.75"/>'
        + '<circle cx="86" cy="42" r="9.5" fill="var(--jp-c-mint-100)" stroke="var(--jp-c-mint-500)" stroke-width="1.75"/>'
        + '</svg>';
    }
    return '<div class="jp-parts__week-scene jp-parts__week-scene--' + side + '" aria-hidden="true">'
      + svg
      + '<span class="jp-parts__week-scene-cap">' + esc(caption) + '</span>'
      + '</div>';
  }

  function weekSideKitsuHtml(weekNum, side) {
    var k = SIDE_KITSUS[(Math.max(1, Number(weekNum) || 1) - 1) % SIDE_KITSUS.length];
    return '<span class="jp-parts__mascot jp-parts__mascot--side jp-parts__mascot--' + side + '" aria-hidden="true">'
      + '<img class="jp-parts__mascot-img" src="' + esc(kitsuSrc(k.file)) + '" width="52" height="52" loading="lazy" alt="' + esc(k.alt) + '"/>'
      + '<span class="jp-parts__mascot-speech jp-parts__mascot-speech--sm">' + esc(k.say) + '</span>'
      + '</span>';
  }

  function curveSvg(fromPos, toPos, isDone, isActiveRun, isCompact) {
    var x1 = WAVE_X[fromPos] || 240;
    var x2 = WAVE_X[toPos] || 240;
    var cls = 'jp-parts__curve' + (isDone ? ' jp-parts__curve--done' : '')
      + (isActiveRun ? ' jp-parts__curve--run' : '')
      + (isCompact ? ' jp-parts__curve--compact' : '');
    return '<svg class="' + cls + '" viewBox="0 0 480 62" preserveAspectRatio="none" aria-hidden="true">'
      + '<path class="jp-parts__curve-track" d="M' + x1 + ' 2 C' + x1 + ' 32, ' + x2 + ' 30, ' + x2 + ' 60" fill="none"'
      + ' stroke="currentColor" stroke-width="6" stroke-linecap="round"' + (isDone && !isActiveRun ? '' : ' stroke-dasharray="9 9"') + '/>'
      + '</svg>';
  }

  function bannerProgressRingHtml(doneCount, totalCount, pct) {
    var r = 21;
    var c = Math.round(2 * Math.PI * r * 10) / 10;
    var offset = Math.round(c * (1 - Math.max(0, Math.min(100, pct)) / 100) * 10) / 10;
    return '<div class="jp-parts__banner-ring-wrap">'
      + '<div class="jp-parts__banner-ring" aria-hidden="true">'
      + '<svg viewBox="0 0 52 52" width="52" height="52">'
      + '<circle class="jp-parts__banner-ring-bg" cx="26" cy="26" r="' + r + '" fill="none" stroke-width="5"/>'
      + '<circle class="jp-parts__banner-ring-fg" cx="26" cy="26" r="' + r + '" fill="none" stroke-width="5"'
      + ' stroke-dasharray="' + c + '" stroke-dashoffset="' + offset + '" stroke-linecap="round"/>'
      + '</svg>'
      + '<span class="jp-parts__banner-ring-pct">' + pct + '%</span>'
      + '</div>'
      + '<div class="jp-parts__banner-ring-meta">'
      + '<strong class="jp-parts__banner-ring-count">' + esc(doneCount) + '/' + esc(totalCount) + ' tình huống</strong>'
      + '<span class="jp-parts__banner-ring-sub">Đã hoàn thành</span>'
      + '</div>'
      + '</div>';
  }

  /* 1. Khối "Hôm nay của bạn" (.jp-today) */
  function todayBlockHtml(pathData, state) {
    var d = pathData || {};
    var st = state || {};
    var todayObj = d.today || {};
    var situations = list(todayObj.situations);
    var now = new Date();
    var monthLabel = 'THÁNG ' + (now.getMonth() + 1);
    var dayNum = String(now.getDate()).padStart(2, '0');
    var dowNames = ['Chủ nhật', 'Thứ hai', 'Thứ ba', 'Thứ tư', 'Thứ năm', 'Thứ sáu', 'Thứ bảy'];
    var dowLabel = dowNames[now.getDay()];

    var allDone = situations.length > 0 && situations.every(function (s) {
      return s.status === 'DONE' || s.status === 'TODAY_DONE';
    });
    var firstActive = situations.filter(function (s) { return s.status !== 'DONE' && s.status !== 'TODAY_DONE'; })[0] || situations[0];
    var mainSitHref = pageUrl('situation', { id: firstActive ? firstActive.id : 'TRV-05' });

    var pillsHtml = situations.map(function (s, idx) {
      var isDone = s.status === 'DONE' || s.status === 'TODAY_DONE';
      var sitHref = pageUrl('situation', { id: s.id });
      var tLabel = topicLabel(s.topicCode);
      return '<li class="jp-today__pill jp-todo__item' + (isDone ? ' is-done' : '') + '" data-pill-tab="vocab">'
        + '<span class="jp-today__pill-icon" aria-hidden="true">' + (isDone ? svgMedal() : svgDialogueBubble()) + '</span>'
        + '<div class="jp-today__pill-body">'
        + '<div class="jp-today__pill-head">'
        + '<strong class="jp-today__pill-title">Tình huống ' + (idx + 1) + ': ' + esc(s.titleVi) + '</strong>'
        + '<span class="jp-today__pill-count">' + esc(tLabel) + '</span>'
        + '</div>'
        + '<div class="jp-today__pill-bar"><div class="jp-today__pill-fill" style="width:' + (isDone ? 100 : 35) + '%"></div></div>'
        + '</div>'
        + (isDone
          ? '<a class="jp-btn jp-btn--sm" href="' + esc(sitHref) + '">Ôn lại</a>'
          : '<a class="jp-btn jp-btn--primary jp-btn--sm" href="' + esc(sitHref) + '">Học hôm nay</a>')
        + '</li>';
    }).join('');

    var weeklyDayStr = todayObj.weeklyTestDay ? fmtShortDate(todayObj.weeklyTestDay) : 'Cuối tuần';
    var todayIsoStr = new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 10);
    var weeklyOpen = !todayObj.weeklyTestDay || String(todayObj.weeklyTestDay) <= todayIsoStr;
    var weeklyHtml = '<div class="jp-today__weekly">'
      + '<div class="jp-today__weekly-head">'
      + '<span class="jp-today__weekly-chip">Kiểm tra tuần</span>'
      + (weeklyOpen
        ? '<a class="jp-btn jp-btn--sm" href="' + esc(pageUrl('weekly')) + '">Làm bài kiểm tra tuần</a>'
        : '<span class="jp-today__weekly-time">Mở vào ' + esc(weeklyDayStr) + '</span>')
      + '</div>'
      + '<p class="jp-today__weekly-desc">Ôn lại các từ, mẫu câu và câu hội thoại dưới 70 điểm trong tuần.</p>'
      + '</div>'
      + '<div class="jp-today__weekly">'
      + '<div class="jp-today__weekly-head"><span class="jp-today__weekly-chip">Ôn tập</span>'
      + '<a class="jp-btn jp-btn--sm" href="' + esc(pageUrl('weekly')) + (pageUrl('weekly').indexOf('?') >= 0 ? '&' : '?') + 'mode=review">Ôn tập hôm nay</a></div>'
      + '<p class="jp-today__weekly-desc">Từ và mẫu câu đến lượt ôn (sau 1, 3, 7, 14, 30 ngày) và mục trong Sổ tay.</p>'
      + '</div>';

    var extraHtml = allDone
      ? (st.extraExhausted
        ? '<div class="cp-today-extra cp-today-extra--exhausted" role="status" data-cp-extra-exhausted>'
          + '<strong>Đã học hết tình huống</strong>'
          + '</div>'
        : '<div class="cp-today-extra">'
          + '<span><strong>Bạn đã hoàn thành hết tình huống hôm nay!</strong> Muốn bứt tốc thêm không?</span>'
          + '<button type="button" class="cp-btn-primary" data-cp-add-extra>+ Học thêm 1 tình huống</button>'
          + '</div>')
      : '';

    var kitsuFile = allDone ? 'kitsu-celebrate-30.webp' : 'kitsu-cheer-30.webp';
    var kitsuSay = allDone
      ? 'Tuyệt vời! Bạn đã xong bài giao tiếp hôm nay rồi!'
      : 'Hôm nay mình cùng đóng vai リン trong ' + situations.length + ' tình huống nhé!';

    var doneSits = situations.filter(function (s) { return s.status === 'DONE' || s.status === 'TODAY_DONE'; }).length;
    var pctToday = situations.length > 0 ? Math.max(0, Math.min(100, Math.round((doneSits * 100) / situations.length))) : 0;
    var circum = 232.5;
    var offsetToday = (circum * (1 - pctToday / 100)).toFixed(1);
    var monthNum = now.getMonth() + 1;
    var kanjiDow = ['日', '月', '火', '水', '木', '金', '土'][now.getDay()] || '日';
    var doneSits = situations.filter(function (s) { return s.status === 'DONE' || s.status === 'TODAY_DONE'; }).length;
    var pctToday = situations.length > 0 ? Math.max(0, Math.min(100, Math.round((doneSits * 100) / situations.length))) : 0;
    var circum = 232.5;
    var offsetToday = (circum * (1 - pctToday / 100)).toFixed(1);
    var monthNum = now.getMonth() + 1;
    var kanjiDow = ['日', '月', '火', '水', '木', '金', '土'][now.getDay()] || '日';
    var doneSits = situations.filter(function (s) { return s.status === 'DONE' || s.status === 'TODAY_DONE'; }).length;
    var pctToday = situations.length > 0 ? Math.max(0, Math.min(100, Math.round((doneSits * 100) / situations.length))) : 0;
    var circum = 232.5;
    var offsetToday = (circum * (1 - pctToday / 100)).toFixed(1);
    var monthNum = now.getMonth() + 1;
    var kanjiDow = ['日', '月', '火', '水', '木', '金', '土'][now.getDay()] || '日';
    return '<section class="jp-today" aria-label="Hôm nay của bạn">'
      + '<div class="jp-today__top">'
      + '<div class="jp-today__cal" data-pct="' + pctToday + '" aria-label="Lịch hôm nay · hoàn thành ' + pctToday + '%">'
      + '<span class="jp-today__cal-water" style="height:' + pctToday + '%" aria-hidden="true"><span class="jp-today__cal-wave"></span></span>'
      + '<svg class="jp-today__cal-ring" viewBox="0 0 80 80" aria-hidden="true">'
      + '<circle class="jp-today__cal-ring-bg" cx="40" cy="40" r="37"></circle>'
      + '<circle class="jp-today__cal-ring-fg" cx="40" cy="40" r="37" stroke-dasharray="' + circum + '" stroke-dashoffset="' + offsetToday + '"></circle>'
      + '</svg>'
      + '<div class="jp-today__cal-inner">'
      + '<strong class="jp-today__cal-day">' + esc(dayNum) + '</strong>'
      + '<span class="jp-today__cal-jp" lang="ja"><span class="jp-today__cal-month">' + monthNum + '月</span><span class="jp-today__cal-kanji">' + kanjiDow + '</span></span>'
      + '<span class="jp-today__cal-dow">' + esc(dowLabel) + '</span>'
      + '</div>'
      + '</div>'
      + '<div class="jp-today__lead">'
      + '<h1 class="jp-section-title">Hôm nay của bạn <span class="jp-today__kind">' + esc(d.nameVi || 'Giao tiếp') + '</span></h1>'
      + '<p class="jp-today__why">Mỗi tình huống gồm 4 bước ngắn: Từ & mẫu câu · Nghe · Nhập vai · Nói.</p>'
      + '</div>'
      + '<div class="jp-today__kitsu">'
      + '<img class="jp-today__kitsu-img" src="' + esc(kitsuSrc(kitsuFile)) + '" width="76" height="76" alt="Kitsu" />'
      + '<span class="jp-today__kitsu-bubble">' + esc(kitsuSay) + '</span>'
      + '</div>'
      + '<a class="cp-btn-primary" href="' + esc(mainSitHref) + '">' + (allDone ? 'Ôn lại hôm nay' : 'Học hôm nay ›') + '</a>'
      + '</div>'
      + '<ul class="jp-today__pills jp-todo">' + pillsHtml + '</ul>'
      + extraHtml
      + weeklyHtml
      + '</section>';
  }

  /* 2. Bong bóng gắn nút (.jp-parts__popover) */
  function nodePopoverHtml(item, pos) {
    var sideCls = pos === 'right' ? 'jp-parts__popover--side-left' : 'jp-parts__popover--side-right';
    var isDone = item.status === 'DONE' || item.status === 'TODAY_DONE';
    var isToday = item.status === 'TODAY';
    var toneCls = isDone ? 'jp-parts__popover--done' : (isToday ? 'jp-parts__popover--today' : 'jp-parts__popover--next');
    var kitsuFile = isDone ? 'kitsu-celebrate-30.webp' : (isToday ? 'kitsu-cheer-30.webp' : 'kitsu-thinking-30.webp');
    var typeBadge = item.type === 'TOPIC_TEST'
      ? 'Bài nhập vai chủ đề'
      : (item.type === 'GOAL_TEST' ? 'Bài test với AI' : ('Chủ đề: ' + topicLabel(item.topicCode, item.topicNameVi)));

    var stepsSummary = '';
    var actionHref = pageUrl('situation', { id: item.id });
    var actionLabel = isDone ? 'Ôn lại tình huống' : 'Bắt đầu học (4 bước)';

    if (item.type === 'SITUATION') {
      stepsSummary = '<ul class="jp-parts__pills-grid">'
        + '<li class="jp-parts__word-pill"><span class="jp-parts__pill-rd">Bước 1</span><strong class="jp-parts__pill-ja">Từ & mẫu câu</strong><span class="jp-parts__pill-mean">Thẻ lật + phát âm</span></li>'
        + '<li class="jp-parts__word-pill"><span class="jp-parts__pill-rd">Bước 2</span><strong class="jp-parts__pill-ja">Nghe hội thoại</strong><span class="jp-parts__pill-mean">Nghe từng câu thoại</span></li>'
        + '<li class="jp-parts__word-pill"><span class="jp-parts__pill-rd">Bước 3</span><strong class="jp-parts__pill-ja">Nhập vai</strong><span class="jp-parts__pill-mean">Chọn câu của リン</span></li>'
        + '<li class="jp-parts__word-pill"><span class="jp-parts__pill-rd">Bước 4</span><strong class="jp-parts__pill-ja">Luyện nói</strong><span class="jp-parts__pill-mean">Chấm phát âm từng câu</span></li>'
        + '</ul>';
    } else if (item.type === 'TOPIC_TEST') {
      // the topic passes from its situations' scores: open the situation that needs work most
      actionHref = item.targetSituation ? pageUrl('situation', { id: item.targetSituation }) : pageUrl('path');
      actionLabel = isDone ? 'Ôn lại chủ đề' : 'Luyện tình huống cần cải thiện';
      stepsSummary = '<p class="jp-parts__pop-desc">Chủ đề đạt khi xong cả 3 tình huống, nhập vai đúng từ <strong>80%</strong> và điểm nói trung bình từ <strong>70</strong>.'
        + (item.quizBest != null ? ' Nhập vai hiện tại: <strong>' + esc(item.quizBest) + '%</strong>.' : '')
        + (item.score != null ? ' Điểm nói: <strong>' + esc(item.score) + '</strong>.' : '') + '</p>';
    } else {
      var retryLater = item.retryAt && String(item.retryAt) > new Date().toISOString().slice(0, 19);
      if (item.certificateUrl) {
        actionHref = item.certificateUrl;
        actionLabel = 'Xem chứng nhận';
      } else {
        actionHref = item.url || pageUrl('path');
        actionLabel = !item.url ? 'Bài test với AI chưa sẵn sàng' : (retryLater ? 'Thử lại sau 24 giờ' : 'Vào Bài test với AI');
      }
      var weak = (item.weakTopics || []).filter(Boolean);
      stepsSummary = '<p class="jp-parts__pop-desc">Cuộc gọi theo kịch bản tổng hợp của toàn bộ mục tiêu. Đạt từ <strong>70 điểm</strong> để nhận chứng nhận hoàn thành lộ trình.</p>'
        + (item.certificateUrl ? '<p class="jp-parts__pop-desc">Bạn đã đạt. Có thể thêm mục tiêu phụ khác hoặc quay lại Lộ trình JLPT.</p>' : '')
        + (!item.certificateUrl && item.score != null ? '<p class="jp-parts__pop-desc">Lần trước: <strong>' + esc(item.score) + '</strong> điểm.'
          + (item.retryAt ? ' Thử lại từ ' + esc(String(item.retryAt).slice(11, 16) + ' ' + fmtShortDate(String(item.retryAt).slice(0, 10))) + '.' : '') + '</p>' : '')
        + (!item.certificateUrl && weak.length ? '<p class="jp-parts__pop-desc">Nên ôn trước: ' + esc(weak.join(', ')) + '.</p>' : '');
    }

    return '<div class="jp-parts__popover ' + sideCls + ' ' + toneCls + '" data-parts-popover role="dialog" aria-label="' + esc(item.titleVi) + '">'
      + '<span class="jp-parts__popover-arrow" aria-hidden="true"></span>'
      + '<div class="jp-parts__pop-head">'
      + '<img class="jp-parts__pop-kitsu" src="' + esc(kitsuSrc(kitsuFile)) + '" width="46" height="46" alt="" />'
      + '<div class="jp-parts__pop-meta">'
      + '<div class="jp-parts__pop-badges">'
      + '<span class="jp-parts__pop-pill ' + (isDone ? 'jp-parts__pop-pill--done' : 'jp-parts__pop-pill--today') + '">' + esc(typeBadge) + '</span>'
      + (item.score != null ? '<span class="jp-parts__pop-date">Điểm: <strong>' + esc(item.score) + '</strong></span>' : '')
      + '</div>'
      + '<h3 class="jp-parts__pop-title">' + esc(item.titleVi) + '</h3>'
      + (item.titleJa ? '<span class="jp-parts__pop-date" lang="ja">' + esc(item.titleJa) + '</span>' : '')
      + '</div>'
      + '<button type="button" class="jp-parts__pop-close" data-cp-pop-close aria-label="Đóng bong bóng">×</button>'
      + '</div>'
      + '<div class="jp-parts__pop-body">' + stepsSummary + '</div>'
      + '<div class="jp-parts__pop-foot">'
      + '<div class="jp-parts__pop-actions">'
      + '<a class="cp-btn-primary jp-parts__pop-main-btn" href="' + esc(actionHref) + '">' + esc(actionLabel) + '</a>'
      + '</div>'
      + '</div>'
      + '</div>';
  }

  /* 3. Vẽ từng nút trên bản đồ tuần */
  function mapNodeHtml(item, pos, nextPos, hasNext, stepInWeek, weekObj, isCurrentWeek, isPastWeek, isUpcomingWeek, isSelected, globalIdx) {
    var status = String(item.status || 'NEXT');
    var isDone = status === 'DONE' || status === 'TODAY_DONE';
    var isToday = status === 'TODAY';
    var isTodayDone = status === 'TODAY_DONE';
    var isLocked = isUpcomingWeek || status === 'LOCKED';
    var labelSide = pos === 'right' ? 'left' : 'right';
    var signSide = (weekObj.index % 2 === 1) ? 'left' : 'right';
    var sceneSide = signSide === 'left' ? 'right' : 'left';

    var marginExtras = '';
    if (stepInWeek === 0) {
      marginExtras += weekSignpostHtml(weekObj, signSide);
    } else if (stepInWeek === 1) {
      marginExtras += weekThemeSceneHtml(weekObj, sceneSide);
    } else if (stepInWeek === 2) {
      marginExtras += weekSideKitsuHtml(weekObj.index, signSide);
    }

    var connector = hasNext ? curveSvg(pos, nextPos, isDone, isToday, isPastWeek) : '';
    var weekAnchorAttr = stepInWeek === 0 ? (' data-cp-week-anchor="' + esc(weekObj.index) + '"') : '';

    var orbCls = 'jp-parts__orb';
    var iconHtml = '';
    var badgeText = '';

    if (item.type === 'GOAL_TEST') {
      orbCls += ' jp-parts__orb--milestone jp-parts__orb--goal-test';
      iconHtml = svgGoalTestChest(isDone ? 'open' : 'current');
      badgeText = 'Bài test với AI';
    } else if (item.type === 'TOPIC_TEST') {
      orbCls += ' jp-parts__orb--topic-test' + (isDone ? ' is-done' : '');
      iconHtml = svgMicRoleplay();
      badgeText = 'Nhập vai';
    } else {
      var stepMod = isTodayDone ? 'today-done' : (isToday ? 'today' : (isDone ? 'done' : 'next'));
      orbCls += ' jp-parts__orb--' + stepMod;
      iconHtml = isDone ? svgMedal() : (isToday ? svgStar() : (isLocked ? svgLock() : svgDialogueBubble()));
      badgeText = String(globalIdx + 1);
    }

    var whenText = item.type === 'TOPIC_TEST'
      ? ('Cuối chủ đề · ' + (item.score != null ? (item.score + ' điểm') : 'Cần ≥ 70 điểm'))
      : (item.type === 'GOAL_TEST'
        ? 'Kiểm tra tổng hợp cuối mục tiêu'
        : (topicLabel(item.topicCode, item.topicNameVi) + (item.score != null ? (' · ' + item.score + 'đ') : '')));

    if (isLocked) {
      var tipText = 'Mở vào tuần ' + fmtShortDate(weekObj.from) + '–' + fmtShortDate(weekObj.to);
      return '<li class="jp-parts__step jp-parts__step--next jp-parts__step--upcoming-week jp-parts__step--pos-' + pos + '"'
        + weekAnchorAttr + '>'
        + '<div class="jp-parts__node-row">'
        + marginExtras
        + '<div class="jp-parts__head jp-parts__head--locked" aria-disabled="true" title="' + esc(tipText) + '">'
        + '<span class="jp-parts__node-wrap">'
        + '<span class="' + orbCls + '" aria-hidden="true">'
        + iconHtml
        + (item.type === 'SITUATION' ? '<span class="jp-parts__orb-num">' + esc(badgeText) + '</span>' : '')
        + '</span>'
        + '</span>'
        + '<span class="jp-parts__label jp-parts__label--' + labelSide + '">'
        + '<span class="jp-parts__no">' + esc(item.titleVi) + '</span>'
        + '<span class="jp-parts__when">' + esc(tipText) + '</span>'
        + '</span>'
        + '<span class="jp-parts__lock-tip" role="tooltip">' + esc(tipText) + '</span>'
        + '</div>'
        + '</div>'
        + connector
        + '</li>';
    }

    var bubble = isToday
      ? '<span class="jp-parts__bubble">Bắt đầu</span>'
      : (isTodayDone ? '<span class="jp-parts__bubble jp-parts__bubble--done">Đã xong</span>' : '');
    var todayKitsu = (isToday || isTodayDone)
      ? '<span class="jp-parts__mascot jp-parts__mascot--today jp-parts__mascot--' + (labelSide === 'left' ? 'right' : 'left') + '" aria-hidden="true">'
        + '<span class="jp-parts__mascot-speech">' + (isTodayDone ? 'Giỏi quá!' : 'Vào tình huống này nhé!') + '</span>'
        + '<img class="jp-parts__mascot-img" src="' + esc(kitsuSrc(isTodayDone ? 'kitsu-celebrate-30.webp' : 'kitsu-cheer-30.webp')) + '" width="58" height="58" alt="" />'
        + '</span>'
      : '';
    var popHtml = isSelected ? nodePopoverHtml(item, pos) : '';

    return '<li class="jp-parts__step jp-parts__step--pos-' + pos
      + (isPastWeek ? ' jp-parts__step--past-week' : ' jp-parts__step--current-week')
      + (isSelected ? ' is-selected' : '') + '"'
      + weekAnchorAttr
      + (isToday || isTodayDone ? ' data-parts-today="true"' : '') + '>'
      + '<div class="jp-parts__node-row">'
      + marginExtras
      + '<button type="button" class="jp-parts__head' + (isSelected ? ' is-selected' : '') + '"'
      + ' data-cp-node="' + esc(item.id) + '" aria-expanded="' + (isSelected ? 'true' : 'false') + '">'
      + '<span class="jp-parts__node-wrap">'
      + bubble
      + '<span class="' + orbCls + '" aria-hidden="true">'
      + iconHtml
      + (item.type === 'SITUATION' ? '<span class="jp-parts__orb-num">' + esc(badgeText) + '</span>' : '')
      + '</span>'
      + todayKitsu
      + '</span>'
      + '<span class="jp-parts__label jp-parts__label--' + labelSide + '">'
      + '<span class="jp-parts__no">' + esc(item.titleVi) + '</span>'
      + '<span class="jp-parts__when">' + esc(whenText) + '</span>'
      + '</span>'
      + '</button>'
      + popHtml
      + '</div>'
      + connector
      + '</li>';
  }

  function mapSectionHtml(pathData, mapData, state) {
    var p = pathData || {};
    var m = mapData || {};
    var prog = p.progress || { doneSituations: 0, totalSituations: 18, passedTopics: 0, totalTopics: 6 };
    var pct = prog.totalSituations > 0 ? Math.min(100, Math.round((prog.doneSituations / prog.totalSituations) * 100)) : 0;
    var weeks = list(m.weeks);
    var totalWeeks = Math.max(1, weeks.length);
    var currentWeek = Number(m.currentWeek) || 1;
    var focusedWeek = state.focusedWeek || currentWeek;

    var sceneKey = p.goal === 'DAILY_LIFE' ? 'grammar' : (p.goal === 'WORK' ? 'kanji' : 'vocab');

    var bannerHtml = '<header class="jp-parts__banner jp-parts__bar jp-parts__banner--' + sceneKey + '" aria-label="Biểu ngữ lộ trình giao tiếp">'
      + '<div class="jp-parts__banner-top">'
      + '<div class="jp-parts__banner-left">'
      + '<span class="jp-parts__banner-icon" aria-hidden="true">' + svgDialogueBubble() + '</span>'
      + '<div class="jp-parts__banner-titles">'
      + '<h2 class="jp-parts__title">Giao tiếp: ' + esc(p.nameVi || 'Du lịch') + '</h2>'
      + '<p class="jp-parts__sub">Đã đạt <strong>' + esc(prog.passedTopics) + '/' + esc(prog.totalTopics) + '</strong> chủ đề · '
      + esc(LEVEL_VI[p.selfLevel] || 'Sơ cấp') + ' · ' + esc(p.dailySituations || 2) + ' tình huống/ngày</p>'
      + '</div>'
      + '</div>'
      + '<div class="jp-parts__banner-center">'
      + bannerProgressRingHtml(prog.doneSituations, prog.totalSituations, pct)
      + '</div>'
      + '<div class="jp-parts__banner-right">'
      + '<div class="jp-parts__week-nav" role="group" aria-label="Chọn tuần học">'
      + '<button type="button" class="jp-parts__week-btn" data-cp-week-nav="prev"' + (focusedWeek <= 1 ? ' disabled' : '') + ' aria-label="Tuần trước">' + svgChevron('left') + '</button>'
      + '<span class="jp-parts__week-ind">Tuần <strong>' + focusedWeek + '/' + totalWeeks + '</strong></span>'
      + '<button type="button" class="jp-parts__week-btn" data-cp-week-nav="next"' + (focusedWeek >= totalWeeks ? ' disabled' : '') + ' aria-label="Tuần tiếp theo">' + svgChevron('right') + '</button>'
      + '</div>'
      + '</div>'
      + '</div>'
      + '<div class="jp-parts__banner-sub">'
      + '<div class="jp-parts__legend" aria-label="Chú giải bản đồ">'
      + '<span class="jp-parts__legend-pill jp-parts__legend-pill--done"><span class="jp-parts__legend-ic">' + svgMedal('jp-parts__legend-svg') + '</span>Đã học</span>'
      + '<span class="jp-parts__legend-pill jp-parts__legend-pill--today"><span class="jp-parts__legend-ic">' + svgStar() + '</span>Hôm nay</span>'
      + '<span class="jp-parts__legend-pill jp-parts__legend-pill--next"><span class="jp-parts__legend-ic">' + svgMicRoleplay() + '</span>Bài nhập vai chủ đề</span>'
      + '<span class="jp-parts__legend-pill jp-parts__legend-pill--chest"><span class="jp-parts__legend-ic">' + svgLock() + '</span>Bài test với AI</span>'
      + '</div>'
      + '<a class="jp-btn jp-parts__all-btn" href="' + esc(pageUrl('day')) + '">Xem chi tiết ngày học</a>'
      + '</div>'
      + '</header>';

    var flat = [];
    weeks.forEach(function (w) {
      list(w.items).forEach(function (it, idx) {
        flat.push({ item: it, weekObj: w, stepInWeek: idx });
      });
    });

    var nodesHtml = flat.map(function (entry, s) {
      var pos = WAVE_POS[s % WAVE_POS.length];
      var nextPos = (s + 1 < flat.length) ? WAVE_POS[(s + 1) % WAVE_POS.length] : 'center';
      var hasNext = (s + 1 < flat.length);
      var wIdx = Number(entry.weekObj.index) || 1;
      var isPast = wIdx < currentWeek;
      var isCurrent = wIdx === currentWeek;
      var isUpcoming = wIdx > currentWeek;
      var isSel = state.selectedId === entry.item.id;
      return mapNodeHtml(entry.item, pos, nextPos, hasNext, entry.stepInWeek, entry.weekObj, isCurrent, isPast, isUpcoming, isSel, s);
    }).join('');

    return '<div class="jp-parts__container" data-tab-scene="' + sceneKey + '">'
      + bannerHtml
      + '<div class="jp-parts__stage jp-parts__stage--' + sceneKey + '" data-tab-scene="' + sceneKey + '">'
      + sceneBackdropHtml(sceneKey)
      + '<ol class="jp-parts__list jp-parts__map">' + nodesHtml + '</ol>'
      + '</div>'
      + '</div>';
  }

  function sideGoalHtml(pathData) {
    var p = pathData || {};
    var sec = p.secondary;
    return '<div class="cp-secondary-card">'
      + '<span class="cp-secondary-card__tag">Lộ trình chính</span>'
      + '<strong class="cp-secondary-card__name">' + esc(p.nameVi || 'Du lịch Nhật Bản') + '</strong>'
      + '<span class="jp-survey__sub">' + esc(LEVEL_VI[p.selfLevel] || 'Sơ cấp') + ' · ' + esc(p.dailySituations || 2) + ' tình huống/ngày</span>'
      + '<div class="jp-side__range" role="group" aria-label="Số tình huống mỗi ngày">'
      + [1, 2].map(function (n) {
        return '<button type="button" class="jp-side-btn" data-cp-daily="' + n + '" aria-pressed="'
          + (Number(p.dailySituations) === n ? 'true' : 'false') + '">' + n + ' tình huống/ngày</button>';
      }).join('')
      + '</div>'
      + (sec
        ? '<hr style="border:0;border-top:1px solid var(--cp-line);margin:6px 0;" />'
          + '<span class="cp-secondary-card__tag">Lộ trình phụ (Học tự do)</span>'
          + '<strong class="cp-secondary-card__name">' + esc(sec.nameVi) + '</strong>'
          + (sec.totalSituations ? '<span class="jp-survey__sub">' + esc(sec.doneSituations || 0) + '/' + esc(sec.totalSituations) + ' tình huống</span>' : '')
          + (sec.next ? '<a class="cp-btn-ghost" href="' + esc(pageUrl('situation', { id: sec.next.id })) + '">Học tiếp: ' + esc(sec.next.titleVi) + '</a>' : '')
        : '')
      + '<a class="cp-btn-ghost" style="margin-top:4px;" href="' + esc(pageUrl('survey')) + '">'
      + (sec ? 'Đổi mục tiêu phụ' : 'Thêm mục tiêu phụ') + '</a>'
      + '</div>';
  }

  function adjustPopoverInViewport(containerEl) {
    if (!containerEl || !root || !root.document) return;
    var pop = containerEl.querySelector('[data-parts-popover]');
    if (!pop || typeof pop.getBoundingClientRect !== 'function') return;
    var vw = Number(root.innerWidth) || 1280;
    var vh = Number(root.innerHeight) || 760;
    if (vw <= 640) return;

    pop.style.setProperty('--pop-shift-y', '0px');
    var arrow = pop.querySelector('.jp-parts__popover-arrow');
    if (arrow) arrow.style.setProperty('--arrow-shift-y', '0px');

    var banner = containerEl.querySelector('.jp-parts__banner');
    var bannerBottom = 64;
    if (banner && typeof banner.getBoundingClientRect === 'function') {
      var bRect = banner.getBoundingClientRect();
      if (bRect.bottom > 0 && bRect.top < vh * 0.45) {
        bannerBottom = Math.max(bannerBottom, bRect.bottom);
      }
    }
    var minTop = Math.min(vh * 0.42, bannerBottom + 10);
    var maxBottom = vh - 14;
    var rect = pop.getBoundingClientRect();
    var shiftY = 0;

    if (rect.top < minTop) {
      shiftY = Math.round(minTop - rect.top);
    } else if (rect.bottom > maxBottom) {
      shiftY = -Math.round(rect.bottom - maxBottom);
      if (rect.top + shiftY < minTop) shiftY = Math.round(minTop - rect.top);
    }

    if (shiftY !== 0) {
      pop.style.setProperty('--pop-shift-y', shiftY + 'px');
      if (arrow) {
        var maxArrow = Math.max(0, Math.floor((rect.height || 220) / 2) - 22);
        var arrowShift = Math.max(-maxArrow, Math.min(maxArrow, -shiftY));
        arrow.style.setProperty('--arrow-shift-y', arrowShift + 'px');
      }
    }
  }

  function mount() {
    var doc = root && root.document;
    var rootEl = doc && doc.getElementById && doc.getElementById('cpathPathRoot');
    if (!rootEl || !root.JpathApi) return;

    var todaySlot = rootEl.querySelector('[data-slot="today"]');
    var mapSlot = rootEl.querySelector('[data-slot="parts"]');
    var sideSlot = rootEl.querySelector('[data-region="levels"]');
    var state = { selectedId: '', focusedWeek: 1, extraExhausted: false };
    var pathData = null;
    var mapData = null;

    function renderAll() {
      rootEl.setAttribute('aria-busy', 'false');
      if (sideSlot && pathData) sideSlot.innerHTML = sideGoalHtml(pathData);
      if (todaySlot && pathData) todaySlot.innerHTML = todayBlockHtml(pathData, state);
      if (mapSlot && pathData && mapData) {
        mapSlot.innerHTML = mapSectionHtml(pathData, mapData, state);
        if (state.selectedId) adjustPopoverInViewport(mapSlot);
      }
    }

    rootEl.addEventListener('click', function (ev) {
      var t = ev.target;
      if (!t || typeof t.closest !== 'function') return;

      var nodeBtn = t.closest('[data-cp-node]');
      if (nodeBtn) {
        var id = nodeBtn.getAttribute('data-cp-node');
        state.selectedId = (state.selectedId === id) ? '' : id;
        renderAll();
        return;
      }

      if (t.closest('[data-cp-pop-close]')) {
        state.selectedId = '';
        renderAll();
        return;
      }

      var weekBtn = t.closest('[data-cp-week-nav]');
      if (weekBtn && mapData) {
        var dir = weekBtn.getAttribute('data-cp-week-nav');
        var totalW = list(mapData.weeks).length || 1;
        state.focusedWeek = Math.max(1, Math.min(totalW, state.focusedWeek + (dir === 'prev' ? -1 : 1)));
        renderAll();
        var anchor = mapSlot && mapSlot.querySelector('[data-cp-week-anchor="' + state.focusedWeek + '"]');
        if (anchor && typeof anchor.scrollIntoView === 'function') {
          anchor.scrollIntoView({ behavior: 'smooth', block: 'center' });
        }
        return;
      }

      var dailyBtn = t.closest('[data-cp-daily]');
      if (dailyBtn && root.JpathApi.send) {
        var n = Number(dailyBtn.getAttribute('data-cp-daily'));
        Promise.resolve(root.JpathApi.send('PUT', '/api/learning/comm/path/daily', { dailySituations: n })).then(function () {
          pathData.dailySituations = n;
          return root.JpathApi.get('/api/learning/comm/map').then(function (m) { mapData = m || mapData; renderAll(); });
        }).catch(function () { /* stays as it was */ });
        return;
      }

      var extraBtn = t.closest('[data-cp-add-extra]');
      if (extraBtn) {
        extraBtn.disabled = true;
        root.JpathApi.send('POST', '/api/learning/comm/today/extra', {})
          .then(function (res) {
            if (res && res.id) {
              if (root.location) {
                root.location.href = pageUrl('situation', { id: res.id });
              }
            } else {
              state.extraExhausted = true;
              renderAll();
            }
          }, function () {
            state.extraExhausted = true;
            renderAll();
          });
        return;
      }

      if (state.selectedId && !t.closest('[data-parts-popover]')) {
        state.selectedId = '';
        renderAll();
      }
    });

    Promise.all([
      root.JpathApi.get('/api/learning/comm/path'),
      root.JpathApi.get('/api/learning/comm/map')
    ]).then(function (res) {
      pathData = res[0] || {};
      mapData = res[1] || { weeks: [], currentWeek: 1 };
      state.focusedWeek = Number(mapData.currentWeek) || 1;
      renderAll();
      // a goal AI call taken since the last visit: read its score (kaiwa, read only) and redraw when it passed
      if (root.JpathApi.send) {
        Promise.resolve(root.JpathApi.send('POST', '/api/learning/comm/goal-test/check')).then(function (g) {
          if (g && g.passed) {
            return root.JpathApi.get('/api/learning/comm/map').then(function (m) { mapData = m || mapData; renderAll(); });
          }
          return null;
        }).catch(function () { /* no score yet */ });
      }
    }, function (e) {
      // no communication path yet: the survey makes one
      if (e && e.status === 404) {
        root.location.replace(pageUrl('survey'));
        return;
      }
      if (mapSlot) {
        mapSlot.innerHTML = '<p class="jp-alert" role="alert">Không tải được lộ trình giao tiếp. Tải lại trang hoặc thử lại sau.</p>';
      }
    });
  }

  return {
    todayBlockHtml: todayBlockHtml,
    mapSectionHtml: mapSectionHtml,
    nodePopoverHtml: nodePopoverHtml,
    adjustPopoverInViewport: adjustPopoverInViewport,
    mount: mount
  };
});
