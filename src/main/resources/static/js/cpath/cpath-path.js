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

  // [JLAS-W0108] B2: spots drawn at 64px or less load the 128px copy
  function kitsuSmall(file) { return kitsuSrc(String(file).replace(/\.webp$/, '-128.webp')); }
  function kitsuSrc(file) {
    return '/images/mascots/kitsu/' + file;
  }

  function pageUrl(page, params) {
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
  // Banner icon per comm goal: BrSE = laptop + speech bubble, Công việc = briefcase + bubble, Du lịch = suitcase,
  // Đời sống = house; anything else keeps the plain dialogue bubble
  function svgGoalIcon(goal) {
    var open = '<svg class="jp-parts__icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">';
    var st = ' stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"';
    var tint = ' fill="currentColor" fill-opacity="0.14"';
    if (goal === 'BA') return open
      + '<rect x="2.5" y="8" width="11.5" height="8.5" rx="1.6"' + tint + st + '/>'
      + '<path d="M1.5 19.5h13.5"' + st + '/>'
      + '<path d="M6.8 10.8l-1.5 1.4 1.5 1.4M9.7 10.8l1.5 1.4-1.5 1.4"' + st + '/>'
      + '<path d="M16.4 2h4.1A1.5 1.5 0 0 1 22 3.5v3.4a1.5 1.5 0 0 1-1.5 1.5h-1.4L17.4 10.4V8.4h-1A1.5 1.5 0 0 1 14.9 6.9V3.5A1.5 1.5 0 0 1 16.4 2z"' + tint + st + '/>'
      + '</svg>';
    if (goal === 'WORK') return open
      + '<rect x="2.5" y="9" width="15" height="10.5" rx="2"' + tint + st + '/>'
      + '<path d="M7 9V7.3A1.3 1.3 0 0 1 8.3 6h3.4A1.3 1.3 0 0 1 13 7.3V9M2.5 13.5h15"' + st + '/>'
      + '<path d="M15.5 2.5h5a1.5 1.5 0 0 1 1.5 1.5v2.8a1.5 1.5 0 0 1-1.5 1.5H19L17.4 9.8V8.3h-1.9A1.5 1.5 0 0 1 14 6.8V4a1.5 1.5 0 0 1 1.5-1.5z"' + tint + st + '/>'
      + '</svg>';
    if (goal === 'TRAVEL') return open
      + '<rect x="5" y="7" width="14" height="12.5" rx="2.2"' + tint + st + '/>'
      + '<path d="M9 7V4.8A1.3 1.3 0 0 1 10.3 3.5h3.4A1.3 1.3 0 0 1 15 4.8V7M9.5 10.5v6M14.5 10.5v6M8 19.5v1.2M16 19.5v1.2"' + st + '/>'
      + '</svg>';
    if (goal === 'DAILY_LIFE') return open
      + '<path d="M3.5 11L12 4l8.5 7"' + st + '/>'
      + '<path d="M5.5 9.5V19a1 1 0 0 0 1 1h11a1 1 0 0 0 1-1V9.5"' + tint + st + '/>'
      + '<path d="M10 20v-5h4v5"' + st + '/>'
      + '</svg>';
    return svgDialogueBubble();
  }

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
      + '<img class="jp-parts__mascot-img" src="' + esc(kitsuSmall(k.file)) + '" width="52" height="52" loading="lazy" alt="' + esc(k.alt) + '"/>'
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
      + '<svg viewBox="0 0 52 52" width="46" height="46">'
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

  /* 1. Khối "Hôm nay của bạn" (owner 08/11: gọn, không bày vẽ; ngày theo đồng hồ của app, không theo trình duyệt) */
  var DOW_VI = ['Chủ nhật', 'Thứ hai', 'Thứ ba', 'Thứ tư', 'Thứ năm', 'Thứ sáu', 'Thứ bảy'];

  function isoDay(d) {
    var m = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(d || ''));
    return m ? new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])) : null;
  }

  /**
   * F46/F69 (owner 11/11): a learner who said "Chưa biết chữ cái" and skipped the Kana suggestion (full romaji on)
   * is reminded to learn Hiragana/Katakana; only a suggestion, hidden for good with "Để sau".
   */
  function kanaTipHtml(pathData, state) {
    var disp = (pathData && pathData.display) || {};
    if (!disp.fullRomaji || (state && state.kanaTipHidden)) return '';
    return '<div class="cp-kana-tip" role="note" data-cp-kana-tip>'
      + '<p><strong>Bạn chưa học chữ cái?</strong> Học Hiragana và Katakana khoảng 1–2 tuần sẽ giúp đọc câu thoại '
      + 'dễ hơn và bớt phụ thuộc romaji.</p>'
      + '<div class="cp-kana-tip__actions">'
      + '<a class="jp-btn jp-btn--sm jp-btn--primary" href="/kana-connect">Học chữ cái</a>'
      + '<button type="button" class="jp-btn jp-btn--sm" data-cp-kana-tip-hide>Để sau</button>'
      + '</div></div>';
  }

  function fmtFullDate(isoStr) {
    var m = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(isoStr || ''));
    return m ? m[3] + '/' + m[2] + '/' + m[1] : '';
  }

  /** F125: the small "Đã hoàn thành" label of a finished path (map banner, side card). */
  function doneChipHtml() {
    return '<span class="cp-done-chip">Đã hoàn thành</span>';
  }

  /**
   * F125 (owner 14/11): the main path's final AI test is passed. A summary card (score, situations, topics, dates, CP,
   * certificate) and the next step chosen by the learner, never automatic: promote the secondary goal (keeps its
   * progress), choose a new goal, or keep reviewing this path ("Tiếp tục ôn" hides the choices, the summary stays).
   */
  function finishedCardHtml(pathData, state) {
    var d = pathData || {};
    var f = d.finished;
    if (!f) return '';
    var st = state || {};
    var sec = d.secondary;
    var dates = f.startedAt && f.finishedAt ? fmtFullDate(f.startedAt) + ' – ' + fmtFullDate(f.finishedAt) : fmtFullDate(f.finishedAt);
    var stat = function (label, value) {
      return '<div class="cp-finished__stat"><dt>' + esc(label) + '</dt><dd>' + esc(value) + '</dd></div>';
    };
    var choices = st.finishedChoiceHidden ? '' : '<div class="cp-finished__next" role="group" aria-labelledby="cp-finished-next">'
      + '<p class="cp-finished__next-title" id="cp-finished-next">Bạn muốn học tiếp thế nào?</p>'
      + '<div class="cp-finished__actions">'
      + (sec ? '<button type="button" class="cp-btn-primary" data-cp-promote>Nâng ' + esc(sec.nameVi) + ' lên làm chính</button>' : '')
      + '<a class="jp-btn" href="/survey?edit=goal">Chọn mục tiêu mới</a>'
      + '<button type="button" class="jp-btn" data-cp-finished-stay>Tiếp tục ôn lộ trình này</button>'
      + '</div>'
      + (sec ? '<p class="cp-finished__hint">Nâng mục tiêu phụ giữ nguyên ' + esc(sec.doneSituations || 0) + '/' + esc(sec.totalSituations || 0)
        + ' tình huống đã học; lộ trình ' + esc(f.nameVi || d.nameVi) + ' tạm dừng, chứng nhận vẫn còn.</p>' : '')
      + '</div>';
    return '<section class="jp-card cp-finished" data-cp-finished aria-labelledby="cp-finished-title">'
      + '<div class="cp-finished__head">'
      + '<img class="cp-finished__kitsu" src="' + esc(kitsuSmall('kitsu-celebrate-30.webp')) + '" width="64" height="64" alt="" />'
      + '<div><span class="cp-done-chip">Đã hoàn thành</span>'
      + '<h2 class="cp-finished__title" id="cp-finished-title">Chúc mừng! Bạn đã hoàn thành lộ trình ' + esc(f.nameVi || d.nameVi) + '</h2>'
      + (f.score != null ? '<p class="cp-finished__lead">Bài test cuối đạt <strong>' + esc(f.score) + '</strong> điểm.</p>' : '')
      + '</div></div>'
      + '<dl class="cp-finished__stats">'
      + stat('Tình huống đã học', (f.doneSituations || 0) + '/' + (f.totalSituations || 0))
      + stat('Chủ đề đã đạt', (f.passedTopics || 0) + '/' + (f.totalTopics || 0))
      + (dates ? stat('Thời gian học', dates) : '')
      + stat('CP từ lộ trình', '+' + (Number(f.cp) || 0) + ' CP')
      + '</dl>'
      + '<p class="cp-finished__cert"><a class="cp-btn-primary" href="' + esc(f.certificateUrl || '/learning/comm/certificate') + '">Xem chứng nhận</a>'
      + '</p>'
      + choices
      + '</section>';
  }

  function todayBlockHtml(pathData, state) {
    var d = pathData || {};
    var st = state || {};
    var finished = !!d.finished;
    var todayObj = d.today || {};
    var situations = list(todayObj.situations);
    var dayIso = String(todayObj.date || '').slice(0, 10);
    var day = isoDay(dayIso);
    var dateLine = day ? DOW_VI[day.getDay()] + ', ' + String(day.getDate()).padStart(2, '0') + '/'
      + String(day.getMonth() + 1).padStart(2, '0') : '';

    var isDone = function (s) { return s.status === 'DONE' || s.status === 'TODAY_DONE'; };
    var doneCount = situations.filter(isDone).length;
    var allDone = situations.length > 0 && doneCount === situations.length;
    var firstActive = situations.filter(function (s) { return !isDone(s); })[0] || null;

    // owner 17/11: same rows as the JLPT "Hôm nay" (ring, title, line, button or medal)
    var jctl = root && root.JpathPathControls;
    var ring = function (done) {
      return jctl && jctl.progressRingSvg ? jctl.progressRingSvg(done ? 1 : 0, 1, done)
        : '<span class="cp-today__icon" aria-hidden="true">' + (done ? svgMedal() : svgDialogueBubble()) + '</span>';
    };
    var medal = function (txt) {
      return '<span class="jp-todo__done">' + (jctl && jctl.medalMiniSvg ? jctl.medalMiniSvg() : '') + ' ' + txt + '</span>';
    };
    var pill = function (done, title, sub, href, cls) {
      return '<li class="jp-today__pill jp-todo__item cp-today__item' + (cls || '') + (done ? ' is-done' : '') + '">'
        + ring(done)
        + '<span class="jp-today__pill-body jp-todo__text"><strong>' + title + '</strong>'
        + '<span class="jp-today__pill-sub jp-todo__sub">' + sub + '</span></span>'
        + (done ? medal('Đã học') + '<a class="jp-btn jp-btn--sm" href="' + esc(href) + '">Ôn lại</a>'
          : '<a class="jp-btn jp-btn--sm" href="' + esc(href) + '">Học</a>')
        + '</li>';
    };
    var items = situations.map(function (s, idx) {
      return pill(isDone(s), esc(s.titleVi), esc(topicLabel(s.topicCode, s.topicName)) + ' · Tình huống ' + (idx + 1),
        pageUrl('situation', { id: s.id }), '');
    }).join('');

    // F70 (owner 12/11): the base lesson comes first, then the situations of the day that use its items
    var core = st.core && st.core.enabled ? st.core : null;
    var coreToday = core && core.today ? core.today : null;
    var coreDone = !!(coreToday && coreToday.count > 0 && coreToday.done >= coreToday.count);
    var coreItem = coreToday && coreToday.count > 0
      ? pill(coreDone, 'Bài nền · ' + coreToday.count + ' từ và mẫu câu',
        (coreDone ? 'Đã thuộc ' + coreToday.done + '/' + coreToday.count : 'Học trước để dùng trong tình huống hôm nay')
          + ' · ' + esc(core.depthLabel || '') + ' ' + Number(core.done || 0) + '/' + Number(core.total || 0),
        '/learning/comm/core', ' cp-today__item--core')
      : '';
    if (coreItem && !coreDone) firstActive = { coreLesson: true };
    var check = core && core.knownCheck && core.knownCheck.open > 0
      ? '<p class="cp-today__check">Bạn đã có trình độ ' + esc(core.knownCheck.level) + ': ' + Number(core.knownCheck.open)
        + ' mục dễ hơn được để riêng. <a class="jp-btn jp-btn--sm" href="/learning/comm/core?check=1">Làm bài Đã biết – ôn nhanh</a></p>'
      : '';

    var body;
    if (situations.length || coreItem) {
      body = '<ol class="jp-today__pills jp-todo cp-today__list" aria-label="Tiến độ từng phần hôm nay">' + coreItem + items + '</ol>' + check;
    } else if (finished) {
      // F125: a finished path gets no new day; any situation stays open on the map for review
      body = '<p class="cp-today__empty">Lộ trình đã hoàn thành nên không giao bài mới mỗi ngày. '
        + 'Bấm vào tình huống trên bản đồ để ôn lại, hoặc ôn từ và mẫu câu đến lượt.</p>';
    } else {
      body = '<p class="cp-today__empty">' + (todayObj.restDay
        ? 'Hôm nay là ngày nghỉ của bạn. Muốn học thêm thì bấm "Học thêm 1 tình huống".'
        : 'Hôm nay chưa có tình huống mới. Bạn có thể học thêm 1 tình huống hoặc ôn lại bài cũ.') + '</p>';
    }

    var extra = '';
    if (!finished && (!situations.length || allDone)) {
      extra = st.extraExhausted
        ? '<p class="cp-today__empty" role="status" data-cp-extra-exhausted>Bạn đã học hết các tình huống của mục tiêu này.</p>'
        : '<button type="button" class="jp-btn" data-cp-add-extra>+ Học thêm 1 tình huống</button>';
    }

    // F44: the server says whether the weekly test is open (test day reached, or this week's share already done)
    var weeklyOpen = typeof todayObj.weeklyOpen === 'boolean' ? todayObj.weeklyOpen
      : (!todayObj.weeklyTestDay || (dayIso && String(todayObj.weeklyTestDay).slice(0, 10) <= dayIso));
    var reviewHref = pageUrl('weekly') + (pageUrl('weekly').indexOf('?') >= 0 ? '&' : '?') + 'mode=review';
    var chest = jctl && jctl.chestMiniSvg ? '<span class="jp-today__weekly-icon">' + jctl.chestMiniSvg() + '</span>' : '';
    var weeklyBox = finished ? '' : (weeklyOpen
      ? '<div class="jp-today__weekly jp-today__weekly--due">' + chest
        + '<div class="jp-today__weekly-info"><strong>Kiểm tra tuần · Hôm nay</strong></div>'
        + '<div class="jp-today__weekly-btns"><a class="jp-btn jp-btn--sm" href="' + esc(pageUrl('weekly')) + '">Làm bài</a></div></div>'
      : '<div class="jp-today__weekly jp-today__weekly--upcoming">' + chest
        + '<div class="jp-today__weekly-info"><strong>Kiểm tra tuần · ' + esc(fmtShortDate(todayObj.weeklyTestDay)) + '</strong>'
        + '<span class="jp-today__weekly-countdown">hoặc khi học xong phần của tuần</span></div></div>');
    var actions = '<div class="cp-today__actions">'
      + extra
      // owner 17/11: no review button before anything is due (a new learner saw "Ôn tập hôm nay" on day one)
      + (typeof todayObj.reviewDue === 'number' && todayObj.reviewDue <= 0 ? ''
        : '<a class="jp-btn" href="' + esc(reviewHref) + '" title="Từ và mẫu câu đến lượt ôn và mục trong Sổ tay">Ôn tập hôm nay'
          + (typeof todayObj.reviewDue === 'number' ? ' (' + todayObj.reviewDue + ')' : '') + '</a>')
      + '</div>';

    var lead = situations.length
      ? (allDone ? 'Đã xong ' + doneCount + '/' + situations.length + ' tình huống hôm nay.'
        : doneCount + '/' + situations.length + ' tình huống đã xong.')
      : '';
    // [JLAS-W0108] F151: explain when today's planned count differs from the newly saved daily pace
    if (!finished && situations.length > 0) {
      var dailyTarget = Number(d.dailySituations) || 0;
      if (dailyTarget > 0 && situations.length !== dailyTarget) {
        var progToday = d.progress || {};
        var totalSit = Number(progToday.totalSituations) || 0;
        var doneSit = Number(progToday.doneSituations) || 0;
        var remainingAfterToday = totalSit - doneSit - Math.max(0, situations.length - doneCount);
        if (dailyTarget < situations.length || remainingAfterToday > 0) {
          lead += (lead ? ' ' : '') + 'Nhịp ' + dailyTarget + ' tình huống/ngày áp dụng từ ngày mai.';
        }
      }
    }

    // Kitsu stays (owner 08/11); its line always matches the day: rest day, nothing planned, done, or what is left
    var left = situations.length - doneCount;
    var kitsuFile = allDone || (finished && !situations.length) ? 'kitsu-celebrate-30.webp' : (situations.length ? 'kitsu-cheer-30.webp' : 'kitsu-resting-30.webp');
    var kitsuSay = !situations.length
      ? (finished ? 'Bạn đã hoàn thành lộ trình rồi!' : todayObj.restDay ? 'Nghỉ ngơi chút nhé!' : 'Ôn lại bài cũ cùng mình nhé!') // F119: the line below says what to do
      : (allDone ? 'Tuyệt vời! Bạn đã xong bài giao tiếp hôm nay rồi!'
        : 'Còn ' + left + ' tình huống hôm nay, mình cùng vào vai nhé!');
    // [JLAS-W0108] C1: same head as the JLPT "Hôm nay" block - calendar stamp, title, Kitsu - then the main button
    var ctl = root && root.JpathPathControls;
    var cal = ctl && typeof ctl.calendarStampHtml === 'function' && dayIso
      ? ctl.calendarStampHtml(dayIso, { allDone: allDone, tabs: [{ tab: 'COMM', newTarget: situations.length, newDone: doneCount }] })
      : '';
    return '<section class="jp-card jp-today cp-today" data-tour="cp-today" aria-labelledby="cp-today-title">'
      + '<div class="jp-today__top">'
      + cal
      + '<div class="jp-today__lead"><div class="jp-today__title-row">'
      + '<h1 class="jp-section-title" id="cp-today-title">Hôm nay của bạn</h1>'
      + (allDone ? '<span class="jp-today__stamp" role="img" tabindex="0" aria-label="Bạn đã hoàn thành toàn bộ bài học hôm nay!" data-tip="Bạn đã hoàn thành toàn bộ bài học hôm nay!"><svg viewBox="0 0 100 100" aria-hidden="true"><defs><path id="jpStampArc" d="M50 50 m-37 0 a37 37 0 1 1 74 0 a37 37 0 1 1 -74 0"></path></defs><circle class="jp-today__stamp-o" cx="50" cy="50" r="47"></circle><circle class="jp-today__stamp-i" cx="50" cy="50" r="29"></circle><text class="jp-today__stamp-arc"><textPath href="#jpStampArc" startOffset="0">KOTSUKOTSU ・ ĐÃ HỌC HÔM NAY ・</textPath></text><text class="jp-today__stamp-jp" x="50" y="58" text-anchor="middle" lang="ja">済</text></svg></span>' : '')
      + '</div>'
      // F119: the goal name is already the map banner's title; the subtitle keeps the date (no stamp) and the count
      + ((cal ? '' : dateLine) || lead ? '<p class="jp-today__subtitle">' + esc(cal ? '' : dateLine) + (!cal && dateLine && lead ? ' · ' : '')
        + esc(lead) + '</p>' : '') + '</div>'
      + '<div class="jp-today__kitsu" aria-hidden="true"><span class="jp-today__kitsu-bubble">' + esc(kitsuSay) + '</span>'
      + '<img class="jp-today__kitsu-img" src="' + esc(kitsuSrc(kitsuFile)) + '" width="76" height="76" alt="" /></div>'
      + '</div>'
      + '<div class="jp-today__body"><div class="jp-today__main">'
      + (firstActive ? '<div class="jp-today__go"><a class="jp-btn jp-btn--primary jp-btn--lg" href="'
        + esc(firstActive.coreLesson ? '/learning/comm/core' : pageUrl('situation', { id: firstActive.id })) + '">'
        + (doneCount > 0 || coreDone ? 'Học tiếp' : 'Học hôm nay') + '</a></div>' : '')
      + kanaTipHtml(d, st)
      + body
      + actions
      + '</div></div>'
      + (weeklyBox ? '<div class="jp-today__bottom">' + weeklyBox + '</div>' : '')
      + travelExtrasHtml(st.stamps, d.goal)
      + '</section>';
  }

  /** F70 Du lịch: the trip passport (one stamp per finished place) and the two fun pages. */
  function travelExtrasHtml(stamps, goal) {
    if (goal !== 'TRAVEL') return '';
    var list2 = stamps && Array.isArray(stamps.stamps) ? stamps.stamps : [];
    var earned = list2.filter(function (x) { return x.stamped; }).length;
    return '<div class="cp-trip" aria-labelledby="cp-trip-title">'
      + '<div class="cp-trip__head"><h2 class="cp-trip__title" id="cp-trip-title">Hộ chiếu chuyến đi</h2>'
      + '<span class="cp-trip__count">' + earned + '/' + list2.length + ' con dấu</span></div>'
      + '<ul class="cp-trip__stamps">' + list2.map(function (x) {
        var tip = x.stamped ? 'Đã đóng dấu ' + fmtShortDate(x.stampedAt) : 'Xong ' + x.done + '/' + x.total + ' tình huống để nhận dấu';
        return '<li class="cp-trip__stamp' + (x.stamped ? ' is-stamped' : '') + '" title="' + esc(tip) + '">'
          + '<span class="cp-trip__seal" aria-hidden="true" lang="ja">' + esc(x.stamped ? (String(x.nameJa || x.nameVi).charAt(0)) : '?') + '</span>'
          + '<span class="cp-trip__name">' + esc(x.nameVi) + '</span>'
          + '<span class="cp-trip__state">' + esc(x.stamped ? 'Đã đóng dấu' : x.done + '/' + x.total) + '</span></li>';
      }).join('') + '</ul>'
      + '<div class="cp-trip__links"><a class="jp-btn jp-btn--sm" href="/learning/comm/phrasebook">Sổ câu cấp tốc</a>'
      + '<a class="jp-btn jp-btn--sm" href="/learning/comm/game">Trò chơi nghe nhanh 60 giây</a></div></div>';
  }

  // F68: the goal of the map being drawn (Du lịch shows its listen-and-speak step 1 in the node bubble)
  var mapGoal = null;

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
    var actionLabel = isDone ? 'Ôn lại tình huống' : (item.missed ? 'Học bù (4 bước)' : 'Bắt đầu học (4 bước)');

    if (item.type === 'SITUATION') {
      stepsSummary = '<ul class="jp-parts__pills-grid">'
        + (mapGoal === 'TRAVEL'
          ? '<li class="jp-parts__word-pill"><span class="jp-parts__pill-rd">Bước 1</span><strong class="jp-parts__pill-ja">Nghe & chọn nghĩa</strong><span class="jp-parts__pill-mean">Nghe câu, chọn nghĩa đúng</span></li>'
          : '<li class="jp-parts__word-pill"><span class="jp-parts__pill-rd">Bước 1</span><strong class="jp-parts__pill-ja">Từ & mẫu câu</strong><span class="jp-parts__pill-mean">Thẻ lật + phát âm</span></li>')
        + '<li class="jp-parts__word-pill"><span class="jp-parts__pill-rd">Bước 2</span><strong class="jp-parts__pill-ja">Nghe hội thoại</strong><span class="jp-parts__pill-mean">Nghe từng câu thoại</span></li>'
        + '<li class="jp-parts__word-pill"><span class="jp-parts__pill-rd">Bước 3</span><strong class="jp-parts__pill-ja">Nhập vai</strong><span class="jp-parts__pill-mean">Chọn câu đáp hợp tình huống</span></li>'
        + '<li class="jp-parts__word-pill"><span class="jp-parts__pill-rd">Bước 4</span><strong class="jp-parts__pill-ja">Luyện nói</strong><span class="jp-parts__pill-mean">Chấm phát âm từng câu</span></li>'
        + '</ul>';
    } else if (item.type === 'TOPIC_TEST') {
      actionHref = pageUrl('weekly') + '?mode=topic&code=' + encodeURIComponent(item.topicCode);
      actionLabel = isDone ? 'Luyện lại chủ đề' : 'Làm bài nhập vai chủ đề';
      stepsSummary = '<p class="jp-parts__pop-desc">Chủ đề đạt khi bài nhập vai từ <strong>80%</strong> và điểm nói trung bình của mọi tình huống từ <strong>70</strong>.</p>'
        + (item.missingRoleplay ? '<p class="jp-parts__pop-desc">Còn thiếu: bài nhập vai chủ đề từ 80%.</p>' : '')
        + (item.missingSpeak ? '<p class="jp-parts__pop-desc">Còn thiếu: điểm nói trung bình từ 70; hãy luyện nói các tình huống trong chủ đề.</p>' : '')
        + (item.missingSpeak && item.targetSituation ? '<p><a href="' + esc(pageUrl('situation', { id: item.targetSituation })) + '">Đến tình huống cần luyện nói</a></p>' : '');
    } else {
      var retryLater = item.retryAt && String(item.retryAt) > new Date().toISOString().slice(0, 19);
      if (item.certificateUrl) {
        actionHref = item.certificateUrl;
        actionLabel = 'Xem chứng nhận';
      } else {
        actionHref = retryLater ? pageUrl('path') : (item.aiAvailable ? item.url : (item.fallbackUrl || pageUrl('weekly') + '?mode=fallback'));
        actionLabel = retryLater ? 'Thử lại sau 24 giờ' : (item.aiAvailable ? 'Vào Bài test với AI' : 'Làm bài nhập vai thay thế');
      }
      var weak = (item.weakTopics || []).filter(Boolean);
      stepsSummary = '<p class="jp-parts__pop-desc">Cuộc gọi theo kịch bản tổng hợp của toàn bộ mục tiêu. Đạt từ <strong>70 điểm</strong> để nhận chứng nhận hoàn thành lộ trình.</p>'
        + (item.certificateUrl ? '<p class="jp-parts__pop-desc">Bạn đã đạt. Có thể thêm mục tiêu phụ khác hoặc quay lại Lộ trình JLPT.</p>' : '')
        + (!item.certificateUrl && item.score != null ? '<p class="jp-parts__pop-desc">Lần trước: <strong>' + esc(item.score) + '</strong> điểm.'
          + (item.retryAt ? ' Thử lại từ ' + esc(String(item.retryAt).slice(11, 16) + ' ' + fmtShortDate(String(item.retryAt).slice(0, 10))) + '.' : '') + '</p>' : '')
        + (!item.certificateUrl && weak.length ? '<p class="jp-parts__pop-desc">Nên ôn trước: ' + esc(weak.join(', ')) + '.</p>' : '');
      if (item.certificateUrl) {
        stepsSummary += '<nav aria-label="Bước học tiếp"><p><a href="/learning/comm/survey">Chọn mục tiêu giao tiếp khác</a></p>'
          + (item.id === 'WORK' || item.id === 'BA' ? '<p><a href="/learning/comm/survey?goal=WORK">Chọn lĩnh vực Công việc khác</a></p>' : '')
          + '<p><a href="/learning/jlpt/start">Bắt đầu lộ trình JLPT</a></p></nav>';
      }
    }

    return '<div class="jp-parts__popover ' + sideCls + ' ' + toneCls + '" data-parts-popover role="dialog" aria-label="' + esc(item.titleVi) + '">'
      + '<span class="jp-parts__popover-arrow" aria-hidden="true"></span>'
      + '<div class="jp-parts__pop-head">'
      + '<img class="jp-parts__pop-kitsu" src="' + esc(kitsuSmall(kitsuFile)) + '" width="46" height="46" alt="" />'
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
  function mapNodeHtml(item, pos, nextPos, hasNext, stepInWeek, weekObj, isCurrentWeek, isPastWeek, isUpcomingWeek, isSelected, globalIdx, deco) {
    var status = String(item.status || 'NEXT');
    var isDone = status === 'DONE' || status === 'TODAY_DONE';
    var isToday = status === 'TODAY';
    var isTodayDone = status === 'TODAY_DONE';
    var isLocked = isUpcomingWeek || status === 'LOCKED';
    var labelSide = pos === 'right' ? 'left' : 'right';
    // F98: margin art only sits in the free margin left of a centre node (its title is on the right), so it never
    // covers a title or runs off the map edge; deco = { slot: order of centre nodes in the week, todayKitsu: bool }
    var d = deco || {};
    var showTodayKitsu = !!d.todayKitsu && pos === 'center';
    var marginExtras = '';
    if (pos === 'center') {
      if (d.slot === 0) {
        marginExtras += weekSignpostHtml(weekObj, 'left');
      } else if (!showTodayKitsu && d.slot === 1) {
        marginExtras += weekThemeSceneHtml(weekObj, 'left');
      } else if (!showTodayKitsu && d.slot === 2) {
        marginExtras += weekSideKitsuHtml(weekObj.index, 'left');
      }
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
      ? ('Cuối chủ đề · ' + (item.score != null ? (item.score + '%') : 'Cần ≥ 80%'))
      : (item.type === 'GOAL_TEST'
        ? 'Kiểm tra tổng hợp cuối mục tiêu'
        : (topicLabel(item.topicCode, item.topicNameVi) + (item.score != null ? (' · ' + item.score + 'đ') : '')));

    if (isLocked) {
      var tipText = 'Mở vào tuần ' + fmtShortDate(weekObj.from) + '–' + fmtShortDate(weekObj.to)
        + ' hoặc học trước bằng nút Học thêm';
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
      : (isTodayDone ? '<span class="jp-parts__bubble jp-parts__bubble--done">Đã xong</span>'
        : (item.missed && !isDone ? '<span class="jp-parts__bubble jp-parts__bubble--missed">Chưa học · Học bù</span>' : ''));
    var todayKitsu = (isToday || isTodayDone) && showTodayKitsu
      ? '<span class="jp-parts__mascot jp-parts__mascot--today jp-parts__mascot--left" aria-hidden="true">'
        + '<span class="jp-parts__mascot-speech">' + (isTodayDone ? 'Giỏi quá!' : 'Vào tình huống này nhé!') + '</span>'
        + '<img class="jp-parts__mascot-img" src="' + esc(kitsuSmall(isTodayDone ? 'kitsu-celebrate-30.webp' : 'kitsu-cheer-30.webp')) + '" width="58" height="58" alt="" />'
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
    var currentWeek = (Number(m.currentWeek) || 0) + 1;
    var focusedWeek = state.focusedWeek || currentWeek;

    mapGoal = p.goal || null;
    var sceneKey = p.goal === 'DAILY_LIFE' ? 'grammar' : (p.goal === 'WORK' ? 'kanji' : 'vocab');

    var bannerHtml = '<header class="jp-parts__banner jp-parts__bar jp-parts__banner--' + sceneKey + '" aria-label="Biểu ngữ lộ trình giao tiếp">'
      + '<div class="jp-parts__banner-top">'
      + '<div class="jp-parts__banner-left">'
      + '<span class="jp-parts__banner-icon" aria-hidden="true">' + svgGoalIcon(p.goal) + '</span>'
      + '<div class="jp-parts__banner-titles">'
      + '<h2 class="jp-parts__title">Giao tiếp: ' + esc(p.nameVi || 'Du lịch')
      + (p.finished || m.finished ? ' ' + doneChipHtml() : '') + '</h2>'
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
      // F119: 'Xem chi tiết ngày học' dropped - the 'Chi tiết ngày' tab is the one way in
      + '</div>'
      + '</header>';

    // F74: situations are numbered 1, 2, 3… on their own; the topic check and the AI test nodes carry no number
    var flat = [];
    var sitNo = 0;
    weeks.forEach(function (w) {
      list(w.items).forEach(function (it, idx) {
        if (it.type === 'SITUATION') sitNo++;
        flat.push({ item: it, weekObj: w, stepInWeek: idx, sitNo: sitNo });
      });
    });
    // F98: number the centre nodes of each week (margin art slots) and keep one "Vào tình huống này nhé!" mascot
    var centreInWeek = {};
    var kitsuPlaced = false;
    flat.forEach(function (entry, s) {
      entry.deco = { slot: -1, todayKitsu: false };
      if (WAVE_POS[s % WAVE_POS.length] !== 'center') return;
      var wk = String(entry.weekObj.index);
      entry.deco.slot = centreInWeek[wk] = (centreInWeek[wk] == null ? 0 : centreInWeek[wk] + 1);
      var st = String(entry.item.status || '');
      if (!kitsuPlaced && (st === 'TODAY' || st === 'TODAY_DONE')) {
        entry.deco.todayKitsu = true;
        kitsuPlaced = true;
      }
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
      return mapNodeHtml(entry.item, pos, nextPos, hasNext, entry.stepInWeek, entry.weekObj, isCurrent, isPast, isUpcoming, isSel, entry.sitNo - 1, entry.deco);
    }).join('');

    var currentItems = list(weeks[currentWeek - 1] && weeks[currentWeek - 1].items)
      .filter(function (item) { return item.type === 'SITUATION'; });
    var weekDone = currentWeek < totalWeeks && currentItems.length > 0
      && currentItems.every(function (item) { return item.status === 'DONE' || item.status === 'TODAY_DONE'; });
    var aheadHtml = weekDone
      ? '<div class="jp-card" role="note"><strong>Học trước tuần sau</strong>'
        + '<p>Bạn đã học xong tuần này. Có thể học thêm tình huống tiếp theo; nếu cần, hãy làm kiểm tra tuần trước.</p>'
        + '<button type="button" class="jp-btn" data-cp-add-extra>Học thêm 1 tình huống</button></div>'
      : '';
    return '<div class="jp-parts__container" data-tab-scene="' + sceneKey + '">'
      + bannerHtml
      + aheadHtml
      + '<div class="jp-parts__stage jp-parts__stage--' + sceneKey + '" data-tab-scene="' + sceneKey + '">'
      + sceneBackdropHtml(sceneKey)
      + '<ol class="jp-parts__list jp-parts__map" data-tour="cp-map">' + nodesHtml + '</ol>'
      + '</div>'
      + '</div>';
  }

  /* Khối lộ trình chính / phụ ở thanh trái (owner 08/11: gọn, một hàng chọn nhịp, link chữ thay vì nút to) */
  function sideGoalHtml(pathData) {
    var p = pathData || {};
    var sec = p.secondary;
    var progress = p.progress || {};
    var passed = Number(progress.passedTopics || 0);
    var total = Number(progress.totalTopics || 0);
    var pct = total ? Math.round(100 * passed / total) : 0;
    var fin = p.finished;
    var others = list(p.finishedPaths);
    return '<div class="cp-goal-card">'
      + '<span class="cp-goal-card__tag">Lộ trình chính</span>' + (fin ? ' ' + doneChipHtml() : '')
      + '<strong class="cp-goal-card__name">' + esc(p.nameVi || 'Lộ trình giao tiếp') + '</strong>'
      + '<span class="cp-goal-card__meta">' + esc(LEVEL_VI[p.selfLevel] || 'Sơ cấp')
      + (fin && fin.finishedAt ? ' · xong ' + esc(fmtFullDate(fin.finishedAt)) : '') + '</span>'
      + '<a class="cp-goal-card__progress" href="' + esc(fin && fin.certificateUrl || '/learning/comm/certificate') + '" title="Xem chứng nhận">'
      + '<span>Chứng nhận</span><span>' + passed + '/' + total + ' chủ đề</span>'
      + '<span class="cp-goal-card__bar" aria-hidden="true"><span style="width:' + pct + '%"></span></span></a>'
      + '<div class="cp-goal-card__pace" data-tour="cp-pace" role="group" aria-label="Số tình huống mỗi ngày">'
      + '<span class="cp-goal-card__pace-label">Tình huống mỗi ngày</span>'
      + '<span class="cp-goal-card__pace-options">'
      + [1, 2].map(function (n) {
        return '<button type="button" class="cp-seg" data-cp-daily="' + n + '" aria-pressed="'
          + (Number(p.dailySituations) === n ? 'true' : 'false') + '">' + n + '</button>';
      }).join('')
      + '</span></div>'
      + '<a class="cp-goal-card__link" data-tour="cp-goal" href="/learning/comm/settings">Cài đặt lộ trình</a>'
      + (sec
        ? '<div class="cp-goal-card__sec"><span class="cp-goal-card__tag">Học tự do · lộ trình phụ</span>'
          + '<strong class="cp-goal-card__name">' + esc(sec.nameVi) + '</strong>'
          + (sec.totalSituations ? '<span class="cp-goal-card__meta">' + esc(sec.doneSituations || 0) + '/' + esc(sec.totalSituations) + ' tình huống</span>' : '')
          + (sec.next ? '<a class="cp-goal-card__link" href="' + esc(pageUrl('situation', { id: sec.next.id })) + '">Học tiếp: ' + esc(sec.next.titleVi) + '</a>' : '')
          // F125: a secondary takes no final test; every situation learned → offer to make it the main path
          + (sec.allDone && !fin ? '<span class="cp-goal-card__meta">Đã học hết tình huống. Bài test cuối và chứng nhận làm khi đây là lộ trình chính.</span>'
            + '<button type="button" class="cp-goal-card__link cp-goal-card__promote" data-cp-promote>Nâng lên làm chính để làm bài test cuối</button>' : '')
          + '</div>'
        : '')
      // F125: paths finished earlier (paused after a promotion or a goal change) keep their certificate
      + (others.length ? '<div class="cp-goal-card__sec cp-goal-card__done"><span class="cp-goal-card__tag">Lộ trình đã hoàn thành</span>'
        + '<ul class="cp-goal-card__done-list">' + others.map(function (o) {
          return '<li><strong>' + esc(o.nameVi || o.goal) + '</strong>'
            + '<span class="cp-goal-card__meta">' + (o.finishedAt ? 'Xong ' + esc(fmtFullDate(o.finishedAt)) : '')
            + (o.score != null ? ' · ' + esc(o.score) + ' điểm' : '') + '</span>'
            + '<a class="cp-goal-card__link" href="' + esc(o.certificateUrl || '/learning/comm/certificate') + '">Chứng nhận</a></li>';
        }).join('') + '</ul></div>' : '')
      + '</div>';
  }

  /* [JLAS-W0108] ghi-chu #74: thẻ nhanh Lộ trình phụ ngay dưới Hôm nay trên màn nhỏ (dưới 900px cột trái nằm cuối trang) */
  function mobileSecondaryHtml(pathData) {
    var p = pathData || {};
    var sec = p.secondary;
    if (!sec) return '';
    var progress = sec.totalSituations
      ? '<span class="cp-mobile-sec__meta">' + esc(sec.doneSituations || 0) + '/' + esc(sec.totalSituations) + ' tình huống</span>'
      : '';
    var next = sec.next
      ? '<a class="jp-btn jp-btn--sm" href="' + esc(pageUrl('situation', { id: sec.next.id })) + '">Học tiếp: ' + esc(sec.next.titleVi) + '</a>'
      : '';
    var promote = sec.allDone && !p.finished
      ? '<button type="button" class="jp-btn jp-btn--sm" data-cp-promote>Nâng lên làm chính</button>'
      : '';
    return '<aside class="jp-card cp-mobile-sec" data-cp-mobile-sec aria-label="Lộ trình phụ">'
      + '<div class="cp-mobile-sec__body">'
      + '<span class="cp-goal-card__tag">Lộ trình phụ · ' + esc(sec.nameVi || '') + '</span>'
      + progress
      + '</div>'
      + (next || promote ? '<div class="cp-mobile-sec__actions">' + next + promote + '</div>' : '')
      + '</aside>';
  }

  /** The last /path response, for the "Lộ trình JLPT" notice in the side nav. */
  var lastPathData = null;

  /** "Lộ trình JLPT" from the communication path: the main goal is communication, so say so and offer the switch. */
  function jlptSwitchHtml(pathData) {
    var name = (pathData && pathData.nameVi) || 'Giao tiếp';
    return '<div class="cp-switch" role="dialog" aria-modal="false" aria-labelledby="cpSwitchTitle" data-cp-switch>'
      + '<h2 id="cpSwitchTitle" class="cp-switch__title" tabindex="-1">Chuyển sang lộ trình JLPT?</h2>'
      + '<p>Lộ trình chính của bạn đang là <strong>' + esc(name) + '</strong>. Đổi mục tiêu chính sang JLPT để học theo lộ trình JLPT; '
      + 'tiến độ giao tiếp vẫn được giữ và bạn có thể quay lại bất cứ lúc nào.</p>'
      + '<div class="cp-switch__actions"><a class="cp-btn-primary" href="/survey?edit=goal">Đổi sang JLPT</a>'
      + '<button type="button" class="jp-btn" data-cp-switch-close>Ở lại</button></div></div>';
  }

  function bindJlptSwitch() {
    if (!root || !root.document || root.__cpSwitchBound) return;
    root.__cpSwitchBound = true;
    root.document.addEventListener('click', function (ev) {
      var t = ev.target;
      if (!t || !t.closest) return;
      var open = root.document.querySelector('[data-cp-switch]');
      if (t.closest('[data-cp-switch-close]')) { if (open) open.remove(); return; }
      if (!t.closest('[data-cp-to-jlpt]')) return;
      ev.preventDefault();
      if (open) { open.remove(); return; }
      var holder = root.document.querySelector('[data-slot="today"]') || root.document.body;
      holder.insertAdjacentHTML('afterbegin', jlptSwitchHtml(lastPathData));
      var title = root.document.getElementById('cpSwitchTitle');
      if (title && title.focus) title.focus();
    });
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
    bindJlptSwitch();

    var todaySlot = rootEl.querySelector('[data-slot="today"]');
    var mapSlot = rootEl.querySelector('[data-slot="parts"]');
    var sideSlot = rootEl.querySelector('[data-region="levels"]');
    var state = { selectedId: '', focusedWeek: 1, extraExhausted: false };
    var KANA_TIP_KEY = 'cpath.kanaTip.hidden';
    try { state.kanaTipHidden = root.localStorage.getItem(KANA_TIP_KEY) === '1'; } catch (e) { state.kanaTipHidden = false; }
    var pathData = null;
    var mapData = null;
    var FINISHED_KEY = 'cpath.finished.stay.';
    function readFlag(key) {
      try { return root.localStorage.getItem(key) === '1'; } catch (e) { return false; }
    }
    var ask = root.JlasConfirm || function (msg) { return Promise.resolve(root.confirm ? root.confirm(msg) : true); };

    function showActionError(error, retry) {
      var slot = todaySlot || mapSlot;
      if (!slot) return;
      var alert = doc.createElement('p');
      alert.className = 'jp-alert';
      alert.setAttribute('role', 'alert');
      alert.textContent = error && error.message || 'Không thực hiện được thao tác.';
      var code = error && error.code;
      var button;
      if (code === 'CPATH_PREMIUM_REQUIRED') {
        button = doc.createElement('a');
        button.className = 'jp-btn jp-btn--primary';
        button.href = '/premium';
        button.textContent = 'Nâng cấp Premium';
      } else if (code === 'CPATH_WEEK_TEST_REQUIRED') {
        // F63: learning ahead needs this week's test first
        button = doc.createElement('a');
        button.className = 'jp-btn';
        button.href = '/learning/comm/weekly';
        button.textContent = 'Làm kiểm tra tuần';
      } else if (code === 'CPATH_AHEAD_LIMIT') {
        button = doc.createElement('a');
        button.className = 'jp-btn';
        button.href = '/learning/comm/notebook';
        button.textContent = 'Ôn lại trong sổ tay';
      } else {
        button = doc.createElement('button');
        button.type = 'button';
        button.className = 'jp-btn';
        button.textContent = 'Thử lại';
        button.addEventListener('click', function () { alert.remove(); retry(); });
      }
      alert.appendChild(button);
      slot.prepend(alert);
    }

    // [JLAS-W0108] C5: first visit (or ?tour=1) gets a 4-step guide; "seen" is a per-browser convenience
    var TOUR_KEY = 'cpath.tourDone';
    var TOUR_STEPS = [
      { target: '[data-tour="cp-today"]', text: 'Mỗi ngày có 1–2 tình huống giao tiếp. Bấm Học tiếp để vào tình huống kế tiếp.' },
      { target: '[data-tour="cp-map"]', text: 'Bản đồ theo tuần: mỗi tình huống có 4 bước, cuối tuần có bài kiểm tra để mở tuần sau.' },
      { target: '[data-tour="cp-pace"]', text: 'Chọn học 1 hay 2 tình huống mỗi ngày; đổi lúc nào cũng được.' },
      { target: '[data-tour="cp-goal"]', text: 'Vào Cài đặt lộ trình để đổi mục tiêu chính hoặc thêm mục tiêu phụ; tiến độ đã học vẫn được giữ.' }
    ];
    function startTour() {
      var tour = root.JpathTour;
      if (!tour || !root.document) return;
      var forced = /(^|[?&])tour=1(&|$)/.test((root.location && root.location.search) || '');
      var seen = false;
      try { seen = root.localStorage.getItem(TOUR_KEY) === '1'; } catch (e) { seen = false; }
      if (seen && !forced) return;
      // owner 17/11: the guide shows once (first visit after the path is made); later only from "Xem hướng dẫn"
      try { root.localStorage.setItem(TOUR_KEY, '1'); } catch (e) { /* shows again next time */ }
      // a finished path hands out no new situation: step 1 says how to review instead of "Học tiếp"
      var steps = pathData && pathData.finished
        ? [{ target: TOUR_STEPS[0].target, text: 'Lộ trình này đã xong nên không có tình huống mới. Bấm Ôn tập hôm nay hoặc một tình huống trên bản đồ để ôn lại.' }].concat(TOUR_STEPS.slice(1))
        : TOUR_STEPS;
      tour.create({ doc: root.document, steps: steps, onFinish: function () {} }).start();
    }

    function renderAll() {
      rootEl.setAttribute('aria-busy', 'false');
      if (sideSlot && pathData) sideSlot.innerHTML = sideGoalHtml(pathData);
      if (todaySlot && pathData) {
        // F125: "Tiếp tục ôn lộ trình này" is remembered per finished goal in this browser (a convenience only)
        state.finishedChoiceHidden = !!(pathData.finished && readFlag(FINISHED_KEY + pathData.goal));
        todaySlot.innerHTML = finishedCardHtml(pathData, state) + todayBlockHtml(pathData, state) + mobileSecondaryHtml(pathData);
      }
      if (mapSlot && pathData && mapData) {
        mapSlot.innerHTML = mapSectionHtml(pathData, mapData, state);
        if (state.selectedId) adjustPopoverInViewport(mapSlot);
      }
    }

    rootEl.addEventListener('click', function (ev) {
      var t = ev.target;
      if (!t || typeof t.closest !== 'function') return;

      if (t.closest('[data-cp-kana-tip-hide]')) {
        state.kanaTipHidden = true;
        try { root.localStorage.setItem(KANA_TIP_KEY, '1'); } catch (e) { /* hidden for this visit only */ }
        renderAll();
        return;
      }

      if (t.closest('[data-cp-finished-stay]') && pathData) {
        try { root.localStorage.setItem(FINISHED_KEY + pathData.goal, '1'); } catch (e) { /* hidden for this visit only */ }
        state.finishedChoiceHidden = true;
        renderAll();
        return;
      }

      var promoteBtn = t.closest('[data-cp-promote]');
      if (promoteBtn && pathData && pathData.secondary && root.JpathApi.send) {
        // F125: never automatic; the learner confirms, the secondary row becomes the main path with its progress
        var secName = pathData.secondary.nameVi || 'mục tiêu phụ';
        Promise.resolve(ask('Nâng ' + secName + ' lên làm lộ trình chính? Tình huống đã học của ' + secName
          + ' được giữ nguyên; lộ trình ' + (pathData.nameVi || 'hiện tại') + ' tạm dừng và vẫn giữ tiến độ, chứng nhận.')).then(function (yes) {
          if (!yes) return null;
          promoteBtn.disabled = true;
          return Promise.resolve(root.JpathApi.send('POST', '/api/learning/comm/path/promote-secondary', {})).then(function () {
            if (root.location) root.location.href = pageUrl('path');
          });
        }).catch(function (error) {
          promoteBtn.disabled = false;
          showActionError(error, function () { promoteBtn.click(); });
        });
        return;
      }

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
          // today's plan and the map both change with the pace: reload both
          return Promise.all([root.JpathApi.get('/api/learning/comm/path'), root.JpathApi.get('/api/learning/comm/map')])
            .then(function (res) {
              pathData = res[0] || pathData;
              lastPathData = pathData;
              mapData = res[1] || mapData;
              renderAll();
            });
        }).catch(function (error) { showActionError(error, function () { dailyBtn.click(); }); });
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
          }, function (error) {
            extraBtn.disabled = false;
            showActionError(error, function () { extraBtn.click(); });
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
      root.JpathApi.get('/api/learning/comm/map'),
      root.JpathApi.get('/api/learning/comm/goal-test').catch(function () { return null; })
    ]).then(function (res) {
      if (!res[0] || !res[1]) throw new Error('Dữ liệu lộ trình chưa đầy đủ.');
      pathData = res[0];
      lastPathData = pathData;
      mapData = res[1];
      if (res[2] && Array.isArray(mapData.weeks)) {
        mapData.weeks.forEach(function (week) {
          (week.items || []).forEach(function (item) {
            if (item.type === 'GOAL_TEST') {
              item.aiAvailable = res[2].aiAvailable;
              item.fallbackUrl = res[2].fallbackUrl;
            }
          });
        });
      }
      state.focusedWeek = (Number(mapData.currentWeek) || 0) + 1;
      renderAll();
      // F70: base lesson of the day (Đời sống / Công việc / BrSE) or the trip passport (Du lịch); both optional
      if (pathData.goal === 'TRAVEL') {
        Promise.resolve(root.JpathApi.get('/api/learning/comm/travel/stamps')).then(function (x) { state.stamps = x; renderAll(); }, function () { /* none */ });
      } else {
        Promise.resolve(root.JpathApi.get('/api/learning/comm/core')).then(function (x) { state.core = x; renderAll(); }, function () { /* none */ });
      }
      // owner 11/11: the communication path is Premium; a free learner sees an upgrade box above the path
      Promise.resolve(root.JpathApi.get('/api/learning/comm/premium-gate')).then(function (gate) {
        if (!gate || !gate.locked || rootEl.querySelector('.jp-premium-box')) return;
        rootEl.insertAdjacentHTML('afterbegin', '<section class="jp-premium-box" role="note">'
          + '<img src="/images/mascots/kitsu/kitsu-announcing.webp" alt="" width="56" height="56">'
          + '<div><p class="jp-premium-box__title">Lộ trình Giao tiếp dành cho tài khoản Premium</p>'
          + '<p class="jp-premium-box__text">Bạn xem được bản đồ và cài đặt. Nâng cấp Premium để học các tình huống, nhập vai và luyện nói.</p></div>'
          + '<a class="jp-btn jp-btn--primary" href="/premium">Nâng cấp Premium</a></section>');
      }, function () { /* the server still blocks the study calls */ });
      startTour();
      // a goal AI call taken since the last visit: read its score (kaiwa, read only) and redraw when it passed
      if (root.JpathApi.send) {
        Promise.resolve(root.JpathApi.send('POST', '/api/learning/comm/goal-test/check')).then(function (g) {
          if (g && g.passed) {
            return root.JpathApi.get('/api/learning/comm/map').then(function (m) { mapData = m || mapData; renderAll(); });
          }
          return null;
        }).catch(function () { /* no score yet */ });
      }
    }).catch(function (e) {
      // no communication path yet: the survey makes one
      if (e && e.status === 404) {
        Promise.resolve(root.JpathApi.get('/api/assessment/survey')).then(function (survey) {
          var savedSurvey = survey && (survey.survey || (survey.data && survey.data.survey));
          var goal = savedSurvey && savedSurvey.goal;
          root.location.replace(pageUrl('survey', goal ? { goal: goal } : {}));
        }).catch(function () {
          root.location.replace(pageUrl('survey'));
        });
        return;
      }
      if (mapSlot) {
        mapSlot.innerHTML = '<p class="jp-alert" role="alert">' + esc(e && e.message || 'Không tải được lộ trình giao tiếp.')
          + ' <button type="button" class="jp-btn" data-cp-retry>Thử lại</button></p>';
        mapSlot.querySelector('[data-cp-retry]').addEventListener('click', function () { root.location.reload(); });
      }
    });
  }

  return {
    todayBlockHtml: todayBlockHtml,
    finishedCardHtml: finishedCardHtml,
    sideGoalHtml: sideGoalHtml,
    mobileSecondaryHtml: mobileSecondaryHtml,
    kanaTipHtml: kanaTipHtml,
    mapSectionHtml: mapSectionHtml,
    nodePopoverHtml: nodePopoverHtml,
    adjustPopoverInViewport: adjustPopoverInViewport,
    mount: mount
  };
});
