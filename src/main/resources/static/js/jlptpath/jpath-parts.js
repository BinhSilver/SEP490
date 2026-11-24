/* [JLAS-W0108] jpath-parts.js — winding study-part map of the Từ vựng / Kanji / Ngữ pháp tabs (owner 06/11, round 6).
 *
 * Round 6 updates (balancing clean minimalism with lively visuals & single-screen ergonomics):
 * 1. Restored lively Kitsu animations:
 *    - Kitsu standing beside Today's node with speech bubble & animated webp/svg + bobbing motion.
 *    - Celebratory Kitsu + short 1-time confetti (< 1.5s, never infinite) on ?saved=1.
 *    - Weekly side Kitsu along the map margin (1 per week, pose varies by week).
 * 2. Restored lively multi-layered backdrop using the harmonious 9-family palette (--jp-c-<name>-{100,500,900}):
 *    - Sky pattern (sakura / stars / breeze), very slow clouds (~60s/cycle, disabled on prefers-reduced-motion),
 *      and 3-layer hills using the tab's 100/500 colors.
 * 3. Filled wide-screen side margins:
 *    - Wider winding path (±220px on wide screens).
 *    - Alternating weekly themed miniature scenes (60–140px, 75–85% opacity, behind the path):
 *      for Vocab, inferred from the week's part topics/titles (Gia đình -> house, Ăn uống -> rice bowl & tea,
 *      Trường học -> school & books, Đi lại -> train & Fuji, Mua sắm -> noren shop, default -> torii & pines).
 *    - Decorative wooden/flag week signpost ("Tuần 2 · 12/11–18/11") at the start of each week (non-clickable).
 * 4. Larger, clearer circular nodes & 6px connector:
 *    - Regular node: 84px, Today node: 100px, Chest / Trophy milestone: 88px.
 *    - Part number badge: 14px; side label title: 15px, subtitle: 13px; connecting curve: 6px.
 * 5. Popover z-index & viewport auto-shift:
 *    - Popover z-index sits above the sticky tab banner (never covered by the banner).
 *    - Automatically shifts down/up into the visible viewport below the sticky banner when opened.
 *    - Max height ~70vh with internal scroll; "10/10" progress ring sits neatly inline in the footer button row.
 *
 * window.JpathParts = { printPartHtml(tab, index), partsHtml(data, tabKey, page, opts), weeklyHtml(week), savedNoticeHtml(msg), mount(partsEl, listEl, tabKey, opts) }
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathParts = api;
  if (root && root.location && /[?&]saved=1\b/.test(String(root.location.search || '')) && root.document) {
    var notice = function () {
      var msg = 'Đã lưu tiến trình hôm nay! Kitsu chúc mừng bạn hoàn thành bài học. Xem lại ở "Xem ngày học hôm nay".';
      if (root.JlasToast && typeof root.JlasToast.show === 'function' && root.JlasToast.show(msg, 'success')) {
        try { root.history.replaceState(null, '', root.location.pathname); } catch (e) { /* keep the url */ }
        return;
      }
      var div = root.document.createElement('div');
      div.className = 'jp-saved-note';
      div.setAttribute('role', 'status');
      div.innerHTML = api.savedNoticeHtml(msg);
      var main = root.document.querySelector('main');
      if (main) main.insertBefore(div, main.firstChild);
      try { root.history.replaceState(null, '', root.location.pathname); } catch (e) { /* keep the url */ }
    };
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', notice);
    else notice();
  }
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var TABS = { vocab: ['VOCAB', 'từ', 'Từ vựng'], kanji: ['KANJI', 'chữ', 'Kanji'], grammar: ['GRAMMAR', 'mẫu', 'Ngữ pháp'] };
  var WEEKDAY = ['CN', 'T2', 'T3', 'T4', 'T5', 'T6', 'T7'];
  var WEEK_SPAN = 5;
  var UPCOMING_WEEKS_SHOWN = 2;
  var PILL_PREVIEW_COUNT = 6;
  var WAVE_POS = ['center', 'left', 'center', 'right'];
  var WAVE_X = { left: 20, center: 240, right: 460 };

  // [JLAS-W0108] B2: spots drawn at 64px or less load the 128px copy (about a third of the bytes)
  function small(src) { return String(src).replace(/\.webp$/, '-128.webp'); }

  var SIDE_KITSUS = [
    { src: '/images/mascots/kitsu/kitsu-cheer-30.webp', alt: 'Kitsu cổ vũ', say: 'Cố lên nhé!' },
    { src: '/images/mascots/kitsu/kitsu-thinking-30.webp', alt: 'Kitsu suy nghĩ', say: 'Nhớ ôn kỹ nhé!' },
    { src: '/images/mascots/kitsu/kitsu-encouraging.webp', alt: 'Kitsu khích lệ', say: 'Cùng tiến lên nào!' },
    { src: '/images/mascots/kitsu/kitsu-preparing.webp', alt: 'Kitsu chăm chỉ', say: 'Từng bước một!' },
    { src: '/images/mascots/kitsu/kitsu-resting-30.webp', alt: 'Kitsu nghỉ ngơi', say: 'Đều đặn mỗi ngày!' },
    { src: '/images/mascots/kitsu/kitsu-celebrate-30.webp', alt: 'Kitsu ăn mừng', say: 'Giữ vững phong độ!' }
  ];

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function list(v) { return Array.isArray(v) ? v : []; }
  function dayLabel(iso) {
    var m = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(iso || ''));
    if (!m) return '';
    return WEEKDAY[new Date(Date.UTC(+m[1], +m[2] - 1, +m[3])).getUTCDay()] + ' ' + m[3] + '/' + m[2];
  }
  function shortDdMm(ms) {
    var dt = new Date(ms);
    return String(dt.getUTCDate()).padStart(2, '0') + '/' + String(dt.getUTCMonth() + 1).padStart(2, '0');
  }

  /* Round 6 Item 1b: Celebratory Kitsu + short 1-time confetti (<= 1.5s, never infinite) on ?saved=1 */
  function savedNoticeHtml(msg) {
    var pieces = [];
    for (var i = 0; i < 14; i++) {
      pieces.push('<span class="jp-saved-note__confetti jp-saved-note__confetti--' + (i % 4)
        + '" style="--cf-i:' + i + '" aria-hidden="true"></span>');
    }
    return '<div class="jp-saved-note__confetti-wrap" aria-hidden="true">' + pieces.join('') + '</div>'
      + '<img class="jp-saved-note__kitsu" src="/images/mascots/kitsu/kitsu-celebrate-30-128.webp" width="56" height="56" alt="Kitsu ăn mừng"/>'
      + '<div class="jp-saved-note__body">'
      + '<strong class="jp-saved-note__title">Hoàn thành xuất sắc!</strong>'
      + '<span class="jp-saved-note__text">' + esc(msg || 'Đã lưu tiến trình hôm nay. Xem lại ở "Xem ngày học hôm nay".') + '</span>'
      + '</div>';
  }

  /* ---- Morphicons-style thin rounded SVG icons ---- */

  function svgMedal(extraCls) {
    var cls = 'jp-parts__icon jp-morph-icon jp-morph-icon--medal' + (extraCls ? ' ' + extraCls : '');
    return '<svg class="' + cls + '" viewBox="0 0 28 28" fill="none" aria-hidden="true">'
      + '<path class="jp-medal__shackle" d="M10.5 11V8.5a3.5 3.5 0 0 1 7 0V11" stroke="var(--jp-c-peach-100)" stroke-width="1.6" stroke-linecap="round"/>'
      + '<path class="jp-medal__ribbon jp-medal__ribbon--l" d="M10.2 16.2L8 23.8l4.2-1.8L13.5 17.8" fill="var(--jp-c-peach-500)" stroke="var(--jp-c-peach-900)" stroke-width="1.4" stroke-linejoin="round"/>'
      + '<path class="jp-medal__ribbon jp-medal__ribbon--r" d="M17.8 16.2L20 23.8l-4.2-1.8L14.5 17.8" fill="var(--jp-c-peach-500)" stroke="var(--jp-c-peach-900)" stroke-width="1.4" stroke-linejoin="round"/>'
      + '<circle class="jp-medal__disc" cx="14" cy="11.5" r="6.4" fill="var(--jp-c-peach-100)" stroke="var(--jp-c-peach-900)" stroke-width="1.6"/>'
      + '<path class="jp-medal__star" d="M14 7.8l1.15 2.3 2.55.37-1.85 1.8.44 2.53L14 13.6l-2.29 1.2.44-2.53-1.85-1.8 2.55-.37L14 7.8z" fill="var(--jp-c-peach-500)" stroke="var(--jp-c-peach-900)" stroke-width="1.1" stroke-linejoin="round"/>'
      + '<path class="jp-medal__sparkle" d="M4.8 6.5h1.8M5.7 5.6v1.8M21.4 6.5h1.8M22.3 5.6v1.8" stroke="var(--jp-c-peach-100)" stroke-width="1.5" stroke-linecap="round"/>'
      + '</svg>';
  }

  function svgTrophyCup(extraCls) {
    var cls = 'jp-parts__icon jp-parts__icon--chest jp-morph-icon jp-morph-icon--trophy' + (extraCls ? ' ' + extraCls : '');
    return '<svg class="' + cls + '" viewBox="0 0 32 32" fill="none" aria-hidden="true">'
      + '<path class="jp-trophy__handles" d="M9.5 9H6.5a2.2 2.2 0 0 0-2.2 2.2v1.2a4.8 4.8 0 0 0 4.8 4.8h.8M22.5 9h3a2.2 2.2 0 0 1 2.2 2.2v1.2a4.8 4.8 0 0 1-4.8 4.8h-.8" stroke="var(--jp-c-peach-900)" stroke-width="1.8" stroke-linecap="round"/>'
      + '<path class="jp-trophy__cup" d="M9.5 6.5h13v7.2a6.5 6.5 0 0 1-13 0V6.5z" fill="var(--jp-c-peach-100)" stroke="var(--jp-c-peach-900)" stroke-width="1.8" stroke-linejoin="round"/>'
      + '<path class="jp-trophy__stem" d="M16 20.2v4.8M11 25.2h10" stroke="var(--jp-c-peach-900)" stroke-width="1.9" stroke-linecap="round"/>'
      + '<path class="jp-trophy__star" d="M16 9.6l1.05 2.1 2.3.34-1.67 1.62.4 2.3L16 14.85l-2.08 1.11.4-2.3-1.67-1.62 2.3-.34L16 9.6z" fill="var(--jp-c-peach-500)" stroke="var(--jp-c-peach-900)" stroke-width="1" stroke-linejoin="round"/>'
      + '<path class="jp-trophy__sparkle" d="M5.5 5.5h1.8M6.4 4.6v1.8M24.7 5.5h1.8M25.6 4.6v1.8" stroke="var(--jp-c-peach-500)" stroke-width="1.5" stroke-linecap="round"/>'
      + '</svg>';
  }

  function svgStar() {
    return '<svg class="jp-parts__icon jp-morph-icon jp-morph-icon--star" viewBox="0 0 24 24" fill="none" aria-hidden="true">'
      + '<path class="jp-star__path" d="M12 3.6l2.55 5.15 5.68.83-4.11 4.01.97 5.66L12 16.58l-5.09 2.67.97-5.66-4.11-4.01 5.68-.83L12 3.6z"'
      + ' fill="currentColor" fill-opacity="0.22" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>'
      + '</svg>';
  }

  function svgLock() {
    return '<svg class="jp-parts__icon jp-morph-icon jp-morph-icon--lock" viewBox="0 0 24 24" fill="none" aria-hidden="true">'
      + '<rect class="jp-lock__body" x="5.5" y="11" width="13" height="9.5" rx="2.5" stroke="currentColor" stroke-width="1.8"/>'
      + '<path class="jp-lock__shackle" d="M8.2 11V8.2a3.8 3.8 0 0 1 7.6 0V11" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>'
      + '<circle cx="12" cy="15.8" r="1.3" fill="currentColor"/>'
      + '</svg>';
  }

  function svgChestClosed() {
    return '<svg class="jp-parts__icon jp-parts__icon--chest jp-morph-icon jp-morph-icon--chest" viewBox="0 0 32 32" fill="none" aria-hidden="true">'
      + '<path class="jp-chest__lid" d="M6 13.5C6 9.8 9.2 7.8 16 7.8s10 2 10 5.7H6z" fill="var(--jp-c-peach-100)" stroke="var(--jp-c-peach-500)" stroke-width="1.8" stroke-linejoin="round"/>'
      + '<rect class="jp-chest__box" x="6" y="13.5" width="20" height="11.5" rx="2.8" fill="var(--jp-c-peach-100)" stroke="var(--jp-c-peach-500)" stroke-width="1.8"/>'
      + '<path d="M6 16.2h20" stroke="var(--jp-c-peach-500)" stroke-width="1.6"/>'
      + '<rect class="jp-chest__clasp" x="13.8" y="13.2" width="4.4" height="5.2" rx="1.3" fill="var(--jp-c-peach-500)" stroke="var(--jp-c-peach-900)" stroke-width="1.5"/>'
      + '</svg>';
  }

  /* Round 6 Bổ sung 3: Large 96px custom SVG treasure chest standing directly on the path with ground shadow */
  function svgBigChest(state) {
    if (state === 'open') {
      return '<svg class="jp-parts__big-chest jp-parts__big-chest--open" viewBox="0 0 96 96" width="96" height="96" fill="none" aria-hidden="true">'
        + '<ellipse class="jp-chest-shadow" cx="48" cy="84" rx="34" ry="7" fill="var(--jp-chest-shadow)"/>'
        + '<ellipse class="jp-chest-glow" cx="48" cy="36" rx="28" ry="17" fill="var(--jp-c-peach-100)"/>'
        + '<path class="jp-chest-rays" d="M48 7v13M28 12l6.5 10.5M68 12l-6.5 10.5M17 24l10 6.5M79 24l-10 6.5" stroke="var(--jp-c-peach-500)" stroke-width="2.4" stroke-linecap="round"/>'
        + '<path class="jp-chest-sparkles" d="M35 15l1.5 3 3.2.5-2.3 2.2.5 3.2-2.9-1.5-2.9 1.5.5-3.2-2.3-2.2 3.2-.5L35 15zM61 13l1.3 2.6 2.8.4-2 2 .5 2.8-2.6-1.4-2.6 1.4.5-2.8-2-2 2.8-.4L61 13z" fill="var(--jp-c-peach-100)" stroke="var(--jp-c-peach-500)" stroke-width="1.4" stroke-linejoin="round"/>'
        + '<path class="jp-chest-lid-open" d="M20 40C20 26 31 19 48 19s28 7 28 21H20z" fill="var(--jp-c-peach-500)" stroke="var(--jp-chest-outline)" stroke-width="2.5" stroke-linejoin="round"/>'
        + '<path d="M33 20.5v19M63 20.5v19" stroke="var(--jp-c-peach-100)" stroke-width="3" stroke-linecap="round"/>'
        + '<ellipse cx="48" cy="42.5" rx="24" ry="5.5" fill="var(--jp-c-peach-100)" stroke="var(--jp-c-peach-500)" stroke-width="1.75"/>'
        + '<rect x="18" y="42" width="60" height="36" rx="7" fill="var(--jp-c-peach-500)" stroke="var(--jp-chest-outline)" stroke-width="2.5"/>'
        + '<rect x="21" y="45" width="54" height="12" rx="3" fill="var(--jp-c-peach-100)" fill-opacity="0.42"/>'
        + '<path d="M19.5 58h57" stroke="var(--jp-chest-outline)" stroke-width="2"/>'
        + '<rect x="30" y="42" width="6" height="36" rx="1.5" fill="var(--jp-c-peach-100)" stroke="var(--jp-chest-outline)" stroke-width="1.8"/>'
        + '<rect x="60" y="42" width="6" height="36" rx="1.5" fill="var(--jp-c-peach-100)" stroke="var(--jp-chest-outline)" stroke-width="1.8"/>'
        + '<rect x="42" y="39.5" width="12" height="15" rx="3.5" fill="var(--jp-c-peach-100)" stroke="var(--jp-chest-outline)" stroke-width="2.2"/>'
        + '<circle cx="48" cy="47" r="2.2" fill="var(--jp-c-peach-500)"/>'
        + '</svg>';
    }
    if (state === 'locked') {
      return '<svg class="jp-parts__big-chest jp-parts__big-chest--locked" viewBox="0 0 96 96" width="96" height="96" fill="none" aria-hidden="true">'
        + '<ellipse class="jp-chest-shadow" cx="48" cy="84" rx="32" ry="6.5" fill="var(--jp-chest-shadow)"/>'
        + '<path d="M18 46C18 29 30 22 48 22s30 7 30 24H18z" fill="var(--jp-c-slate-100)" stroke="var(--jp-c-slate-500)" stroke-width="2.4" stroke-linejoin="round"/>'
        + '<rect x="18" y="45" width="60" height="33" rx="7" fill="var(--jp-c-slate-100)" stroke="var(--jp-c-slate-500)" stroke-width="2.4"/>'
        + '<path d="M19.5 61h57M33 24v54M63 24v54" stroke="var(--jp-c-slate-500)" stroke-width="1.8" stroke-linecap="round" stroke-opacity="0.5"/>'
        + '<path d="M43 44v-4.2a5 5 0 0 1 10 0V44" stroke="var(--jp-c-slate-500)" stroke-width="2.5" stroke-linecap="round"/>'
        + '<rect x="39" y="44" width="18" height="16" rx="4" fill="var(--jp-white)" stroke="var(--jp-c-slate-500)" stroke-width="2.2"/>'
        + '<circle cx="48" cy="50.8" r="2" fill="var(--jp-c-slate-500)"/>'
        + '<path d="M48 52.8v3" stroke="var(--jp-c-slate-500)" stroke-width="2" stroke-linecap="round"/>'
        + '</svg>';
    }
    return '<svg class="jp-parts__big-chest jp-parts__big-chest--current" viewBox="0 0 96 96" width="96" height="96" fill="none" aria-hidden="true">'
      + '<ellipse class="jp-chest-shadow" cx="48" cy="84" rx="34" ry="7" fill="var(--jp-chest-shadow)"/>'
      + '<path class="jp-chest-lid" d="M18 46C18 29 30 22 48 22s30 7 30 24H18z" fill="var(--jp-c-peach-500)" stroke="var(--jp-chest-outline)" stroke-width="2.5" stroke-linejoin="round"/>'
      + '<path d="M25 38c5-7.5 13.5-11.5 23-11.5S66 30.5 71 38" stroke="var(--jp-c-peach-100)" stroke-width="2.4" stroke-linecap="round" stroke-opacity="0.78"/>'
      + '<path d="M33 23.5v22M63 23.5v22" stroke="var(--jp-c-peach-100)" stroke-width="3" stroke-linecap="round"/>'
      + '<rect x="18" y="45" width="60" height="33" rx="7" fill="var(--jp-c-peach-500)" stroke="var(--jp-chest-outline)" stroke-width="2.5"/>'
      + '<rect x="21" y="48" width="54" height="11" rx="3" fill="var(--jp-c-peach-100)" fill-opacity="0.38"/>'
      + '<path d="M19.5 61h57" stroke="var(--jp-chest-outline)" stroke-width="2"/>'
      + '<rect x="30" y="45" width="6" height="33" rx="1.5" fill="var(--jp-c-peach-100)" stroke="var(--jp-chest-outline)" stroke-width="1.8"/>'
      + '<rect x="60" y="45" width="6" height="33" rx="1.5" fill="var(--jp-c-peach-100)" stroke="var(--jp-chest-outline)" stroke-width="1.8"/>'
      + '<rect x="41" y="39" width="14" height="17" rx="4" fill="var(--jp-c-peach-100)" stroke="var(--jp-chest-outline)" stroke-width="2.3"/>'
      + '<circle cx="48" cy="46.2" r="2.2" fill="var(--jp-chest-outline)"/>'
      + '<path d="M48 48.4v3.4" stroke="var(--jp-chest-outline)" stroke-width="2" stroke-linecap="round"/>'
      + '</svg>';
  }

  function tabBannerIconSvg(sceneKey) {
    if (sceneKey === 'kanji') {
      return '<svg class="jp-parts__banner-svg jp-morph-icon jp-morph-icon--brush" viewBox="0 0 28 28" fill="none" aria-hidden="true">'
        + '<path class="jp-morph-stroke" d="M19.8 4.8l3.4 3.4-9.2 9.2-3.8.6.6-3.8 9-9.4z" fill="currentColor" fill-opacity="0.14" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>'
        + '<path class="jp-morph-stroke" d="M10.8 18c-2 .5-3.5 2-4.2 4.2 2.4.2 4.2-1 4.9-3" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>'
        + '<path class="jp-morph-detail" d="M6.5 23.5h13" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>'
        + '</svg>';
    }
    if (sceneKey === 'grammar') {
      return '<svg class="jp-parts__banner-svg jp-morph-icon jp-morph-icon--scroll" viewBox="0 0 28 28" fill="none" aria-hidden="true">'
        + '<rect class="jp-morph-stroke" x="6.5" y="5" width="15" height="18" rx="3" fill="currentColor" fill-opacity="0.12" stroke="currentColor" stroke-width="1.8"/>'
        + '<path class="jp-morph-detail" d="M10 10.5h8M10 14.5h8M10 18.5h5" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>'
        + '</svg>';
    }
    return '<svg class="jp-parts__banner-svg jp-morph-icon jp-morph-icon--book" viewBox="0 0 28 28" fill="none" aria-hidden="true">'
      + '<path class="jp-book__page-l" d="M14 8.2C11.4 6.6 8 6.2 4.8 7v12.8c3.2-.8 6.6-.4 9.2 1.2" fill="currentColor" fill-opacity="0.12" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>'
      + '<path class="jp-book__page-r" d="M14 8.2c2.6-1.6 6-2 9.2-1.2v12.8c-3.2-.8-6.6-.4-9.2 1.2" fill="currentColor" fill-opacity="0.12" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>'
      + '<path d="M14 8.2V21" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>'
      + '</svg>';
  }

  function svgListIcon() {
    return '<svg class="jp-parts__btn-ic jp-morph-icon jp-morph-icon--list" viewBox="0 0 20 20" width="16" height="16" fill="none" aria-hidden="true">'
      + '<path class="jp-morph-detail" d="M6.5 5.5h9.5M6.5 10h9.5M6.5 14.5h9.5" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/>'
      + '<circle cx="3.5" cy="5.5" r="1" fill="currentColor"/><circle cx="3.5" cy="10" r="1" fill="currentColor"/><circle cx="3.5" cy="14.5" r="1" fill="currentColor"/>'
      + '</svg>';
  }

  function svgChevron(dir) {
    var d = dir === 'left' ? 'M11.5 4.5L6.5 9.5L11.5 14.5' : 'M7.5 4.5L12.5 9.5L7.5 14.5';
    return '<svg class="jp-morph-icon jp-morph-icon--chevron jp-morph-icon--chevron-' + dir + '" viewBox="0 0 18 18" width="16" height="16" fill="none" aria-hidden="true">'
      + '<path class="jp-chevron__head" d="' + d + '" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/>'
      + '</svg>';
  }

  function svgHorizonFlag() {
    return '<svg class="jp-morph-icon jp-morph-icon--flag" viewBox="0 0 20 20" width="18" height="18" fill="none" aria-hidden="true">'
      + '<path d="M4.5 3.5v13M4.5 4.2h10l-2.2 3.4 2.2 3.4h-10" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/>'
      + '</svg>';
  }

  /* Round 6 Item 2: Lively backdrop with slow clouds (~60s/cycle), sky pattern (stars on grammar, sakura on kanji, breeze on vocab),
   * and multi-layer hills using the harmonious 100/500 palette variables */
  function smallCloudSvg(cls) {
    return '<svg class="jp-parts__cloud ' + cls + '" viewBox="0 0 128 52" fill="none" aria-hidden="true">'
      + '<path d="M26 42h76a13 13 0 0 0 1.5-25.9A20 20 0 0 0 66 8a17 17 0 0 0-31.2 6.8A14.5 14.5 0 0 0 26 42z"'
      + ' fill="var(--jp-cloud-fill)" stroke="var(--jp-cloud-stroke)" stroke-width="1.25" stroke-linecap="round" stroke-linejoin="round"/>'
      + '</svg>';
  }

  var SCENE_MARK = { vocab: '語', kanji: '漢', grammar: '文', kana: 'あ', skills: '読' };

  function sceneBackdropHtml(sceneKey) {
    return '<div class="jp-parts__backdrop jp-parts__backdrop--' + esc(sceneKey) + '" aria-hidden="true">'
      + '<div class="jp-parts__sky-pattern jp-parts__sky-pattern--' + esc(sceneKey) + '"></div>'
      // F23: a faint treasure-map layer (grid + contour rings) and one big kanji watermark per tab
      + '<div class="jp-parts__mapgrid"></div>'
      + '<span class="jp-parts__watermark" lang="ja">' + (SCENE_MARK[sceneKey] || '学') + '</span>'
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

  /* Round 6 Bổ sung 3: Decorative side vignettes & Weekly themed scenes in a unified thin-rounded style
   * using ONLY shade 100 & 500 palette tokens (no dark 900 blocks on light backgrounds) */
  function sideDecoSvg(index, sceneKey) {
    var kind = index % 4;
    if (sceneKey === 'grammar' && kind === 0) {
      return '<svg class="jp-parts__deco-svg" viewBox="0 0 88 68" fill="none" aria-hidden="true">'
        + '<ellipse cx="44" cy="58" rx="30" ry="5" fill="var(--jp-tab-100)"/>'
        + '<path d="M24 16l2.4 4.8 5.2.8-3.8 3.6.9 5.2-4.7-2.5-4.7 2.5.9-5.2-3.8-3.6 5.2-.8L24 16z" fill="var(--jp-c-peach-100)" stroke="var(--jp-tab-500)" stroke-width="1.75" stroke-linejoin="round"/>'
        + '<path d="M58 24l1.8 3.6 4 .6-2.9 2.8.7 4-3.6-1.9-3.6 1.9.7-4-2.9-2.8 4-.6L58 24z" fill="var(--jp-tab-100)" stroke="var(--jp-tab-500)" stroke-width="1.75" stroke-linejoin="round"/>'
        + '<path d="M18 52c16-9 36-9 52 0" stroke="var(--jp-tab-500)" stroke-width="1.75" stroke-linecap="round"/>'
        + '</svg>';
    }
    if (kind === 0) {
      /* Soft sakura blossom branch */
      return '<svg class="jp-parts__deco-svg" viewBox="0 0 88 68" fill="none" aria-hidden="true">'
        + '<ellipse cx="44" cy="58" rx="28" ry="5" fill="var(--jp-c-mauve-100)"/>'
        + '<circle cx="34" cy="22" r="6.5" fill="var(--jp-c-mauve-100)" stroke="var(--jp-c-mauve-500)" stroke-width="1.75"/>'
        + '<circle cx="43" cy="28" r="6.5" fill="var(--jp-c-mauve-100)" stroke="var(--jp-c-mauve-500)" stroke-width="1.75"/>'
        + '<circle cx="39" cy="37" r="6.5" fill="var(--jp-c-mauve-100)" stroke="var(--jp-c-mauve-500)" stroke-width="1.75"/>'
        + '<circle cx="28" cy="35" r="6.5" fill="var(--jp-c-mauve-100)" stroke="var(--jp-c-mauve-500)" stroke-width="1.75"/>'
        + '<circle cx="26" cy="26" r="6.5" fill="var(--jp-c-mauve-100)" stroke="var(--jp-c-mauve-500)" stroke-width="1.75"/>'
        + '<circle cx="34.5" cy="30" r="3" fill="var(--jp-c-peach-100)" stroke="var(--jp-c-peach-500)" stroke-width="1.5"/>'
        + '<circle cx="60" cy="42" r="5" fill="var(--jp-c-mauve-100)" stroke="var(--jp-c-mauve-500)" stroke-width="1.6"/>'
        + '</svg>';
    }
    if (kind === 1) {
      /* Layered rounded cedar & pine trees (using 100/500 shades only) */
      return '<svg class="jp-parts__deco-svg" viewBox="0 0 88 68" fill="none" aria-hidden="true">'
        + '<ellipse cx="44" cy="58" rx="32" ry="5.5" fill="var(--jp-c-mint-100)"/>'
        + '<path d="M32 42v14M58 45v11" stroke="var(--jp-c-peach-500)" stroke-width="2.2" stroke-linecap="round"/>'
        + '<path d="M32 14L17 33h30L32 14z" fill="var(--jp-c-mint-100)" stroke="var(--jp-c-mint-500)" stroke-width="1.75" stroke-linejoin="round"/>'
        + '<path d="M32 24L15 44h34L32 24z" fill="var(--jp-c-mint-100)" stroke="var(--jp-c-mint-500)" stroke-width="1.75" stroke-linejoin="round"/>'
        + '<circle cx="58" cy="36" r="10.5" fill="var(--jp-c-teal-100)" stroke="var(--jp-c-teal-500)" stroke-width="1.75"/>'
        + '</svg>';
    }
    if (kind === 2) {
      /* Warm Chochin lantern (100/500 shades only) */
      return '<svg class="jp-parts__deco-svg" viewBox="0 0 88 68" fill="none" aria-hidden="true">'
        + '<ellipse cx="44" cy="59" rx="26" ry="4.5" fill="var(--jp-c-peach-100)"/>'
        + '<path d="M26 11h32M42 11v6" stroke="var(--jp-c-purple-500)" stroke-width="1.75" stroke-linecap="round"/>'
        + '<rect x="34" y="17" width="16" height="3.5" rx="1.75" fill="var(--jp-c-purple-100)" stroke="var(--jp-c-purple-500)" stroke-width="1.6"/>'
        + '<ellipse cx="42" cy="31" rx="12" ry="12.5" fill="var(--jp-c-peach-100)" stroke="var(--jp-c-peach-500)" stroke-width="1.75"/>'
        + '<path d="M31 27h22M31 35h22" stroke="var(--jp-c-peach-500)" stroke-width="1.5" stroke-linecap="round"/>'
        + '<rect x="35" y="42.5" width="14" height="3.5" rx="1.75" fill="var(--jp-c-purple-100)" stroke="var(--jp-c-purple-500)" stroke-width="1.6"/>'
        + '<path d="M42 46v7" stroke="var(--jp-c-peach-500)" stroke-width="1.75" stroke-linecap="round"/>'
        + '</svg>';
    }
    /* Torii gate with soft sun disc */
    return '<svg class="jp-parts__deco-svg" viewBox="0 0 88 68" fill="none" aria-hidden="true">'
      + '<ellipse cx="44" cy="58" rx="32" ry="5" fill="var(--jp-c-coral-100)"/>'
      + '<circle cx="44" cy="34" r="14" fill="var(--jp-c-peach-100)"/>'
      + '<path d="M18 20c16 3.5 36 3.5 52 0" stroke="var(--jp-c-coral-500)" stroke-width="2.4" stroke-linecap="round"/>'
      + '<path d="M22 27h44M29 21v34M59 21v34" stroke="var(--jp-c-coral-500)" stroke-width="2" stroke-linecap="round"/>'
      + '</svg>';
  }

  function detectWeekTheme(weekObj, sceneKey) {
    if (sceneKey === 'kanji') return (weekObj.weekNum % 2 === 1) ? 'brush' : 'torii';
    if (sceneKey === 'grammar') return (weekObj.weekNum % 2 === 1) ? 'stars' : 'bridge';
    var blob = '';
    list(weekObj && weekObj.parts).forEach(function (p) {
      if (!p) return;
      blob += ' ' + (p.topic || '') + ' ' + (p.title || '') + ' ' + (p.name || '');
      list(p.items).slice(0, 6).forEach(function (it) {
        if (it) blob += ' ' + (it.topic || '') + ' ' + (it.meaning || '') + ' ' + (it.meaningVi || '');
      });
    });
    var s = blob.toLowerCase();
    if (/gia đình|nhà|bố|mẹ|anh|chị|em|ông|bà|phòng|family|house|home/.test(s)) return 'house';
    if (/ăn|uống|ẩm thực|cơm|trà|món|bữa|nhà hàng|quán|food|drink|meal/.test(s)) return 'food';
    if (/trường|học|lớp|sách|giáo viên|sinh viên|bài|thi|school|study|book/.test(s)) return 'school';
    if (/đi|tàu|ga|xe|du lịch|đường|phố|sân bay|travel|train|station/.test(s)) return 'travel';
    if (/mua|bán|cửa hàng|siêu thị|tiền|áo|quần|shop|store/.test(s)) return 'shop';
    var cycle = ['house', 'food', 'school', 'travel', 'shop'];
    return cycle[((weekObj && weekObj.weekNum) ? weekObj.weekNum - 1 : 0) % cycle.length];
  }

  function weekThemeSceneHtml(weekObj, sceneKey, side) {
    var theme = detectWeekTheme(weekObj, sceneKey);
    var svg = '';
    var caption = '';
    if (theme === 'house') {
      caption = 'Gia đình & Nhà cửa';
      svg = '<svg class="jp-parts__week-scene-svg jp-parts__scene-svg" viewBox="0 0 112 78" fill="none" aria-hidden="true">'
        + '<ellipse cx="56" cy="68" rx="46" ry="6.5" fill="var(--jp-tab-100)"/>'
        + '<circle cx="84" cy="20" r="7" fill="var(--jp-c-peach-100)" stroke="var(--jp-c-peach-500)" stroke-width="1.5"/>'
        + '<rect x="24" y="36" width="48" height="31" rx="5" fill="var(--jp-c-peach-100)" stroke="var(--jp-c-peach-500)" stroke-width="1.75"/>'
        + '<path d="M64 24v-8h6v12.5" fill="var(--jp-c-coral-100)" stroke="var(--jp-c-coral-500)" stroke-width="1.75" stroke-linejoin="round"/>'
        + '<path d="M18 37L46 15.5c1.2-.9 2.8-.9 4 0L78 37z" fill="var(--jp-c-coral-100)" stroke="var(--jp-c-coral-500)" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"/>'
        + '<circle cx="48" cy="26.5" r="4" fill="var(--jp-white)" stroke="var(--jp-c-coral-500)" stroke-width="1.6"/>'
        + '<rect x="42.5" y="46" width="11" height="21" rx="2.5" fill="var(--jp-white)" stroke="var(--jp-c-peach-500)" stroke-width="1.75"/>'
        + '<path d="M48 46v21M42.5 54h11" stroke="var(--jp-c-peach-500)" stroke-width="1.35"/>'
        + '<rect x="29" y="44" width="9" height="9" rx="2" fill="var(--jp-c-sky-100)" stroke="var(--jp-c-sky-500)" stroke-width="1.6"/>'
        + '<rect x="58" y="44" width="9" height="9" rx="2" fill="var(--jp-c-sky-100)" stroke="var(--jp-c-sky-500)" stroke-width="1.6"/>'
        + '<path d="M86 50v17" stroke="var(--jp-c-peach-500)" stroke-width="2" stroke-linecap="round"/>'
        + '<circle cx="86" cy="42" r="9.5" fill="var(--jp-c-mint-100)" stroke="var(--jp-c-mint-500)" stroke-width="1.75"/>'
        + '<circle cx="92" cy="46" r="6.5" fill="var(--jp-c-mint-100)" stroke="var(--jp-c-mint-500)" stroke-width="1.6"/>'
        + '</svg>';
    } else if (theme === 'food') {
      caption = 'Ẩm thực & Ăn uống';
      svg = '<svg class="jp-parts__week-scene-svg jp-parts__scene-svg" viewBox="0 0 112 78" fill="none" aria-hidden="true">'
        + '<ellipse cx="56" cy="68" rx="46" ry="6.5" fill="var(--jp-tab-100)"/>'
        + '<path d="M24 38h44M26 33.5h40" stroke="var(--jp-c-peach-500)" stroke-width="1.75" stroke-linecap="round"/>'
        + '<path d="M28 43c0-8.5 7.5-14 17-14s17 5.5 17 14H28z" fill="var(--jp-white)" stroke="var(--jp-c-indigo-500)" stroke-width="1.75" stroke-linejoin="round"/>'
        + '<path d="M24 43h42c-2 14-9.5 21-21 21S26 57 24 43z" fill="var(--jp-c-coral-100)" stroke="var(--jp-c-coral-500)" stroke-width="1.75" stroke-linejoin="round"/>'
        + '<rect x="37" y="64" width="16" height="3.5" rx="1.75" fill="var(--jp-c-coral-100)" stroke="var(--jp-c-coral-500)" stroke-width="1.5"/>'
        + '<rect x="74" y="44" width="17" height="20" rx="4" fill="var(--jp-c-mint-100)" stroke="var(--jp-c-mint-500)" stroke-width="1.75"/>'
        + '<path d="M74 50h17" stroke="var(--jp-c-mint-500)" stroke-width="1.4"/>'
        + '<path d="M38 23c0-3.5 2.5-4.5 2.5-8M48 22c0-3.5 2.5-4.5 2.5-8M82.5 39c0-3 2-4 2-6.5" stroke="var(--jp-tab-500)" stroke-width="1.6" stroke-linecap="round"/>'
        + '</svg>';
    } else if (theme === 'school') {
      caption = 'Trường học & Từ mới';
      svg = '<svg class="jp-parts__week-scene-svg jp-parts__scene-svg" viewBox="0 0 112 78" fill="none" aria-hidden="true">'
        + '<ellipse cx="56" cy="68" rx="46" ry="6.5" fill="var(--jp-tab-100)"/>'
        + '<rect x="22" y="33" width="52" height="34" rx="4.5" fill="var(--jp-c-indigo-100)" stroke="var(--jp-c-indigo-500)" stroke-width="1.75"/>'
        + '<path d="M16 34L48 16l32 18H16z" fill="var(--jp-c-purple-100)" stroke="var(--jp-c-purple-500)" stroke-width="1.75" stroke-linejoin="round"/>'
        + '<circle cx="48" cy="27.5" r="5" fill="var(--jp-white)" stroke="var(--jp-c-indigo-500)" stroke-width="1.6"/>'
        + '<path d="M48 25v2.8l2 1.2" stroke="var(--jp-c-indigo-500)" stroke-width="1.5" stroke-linecap="round"/>'
        + '<rect x="42.5" y="49" width="11" height="18" rx="2" fill="var(--jp-white)" stroke="var(--jp-c-indigo-500)" stroke-width="1.6"/>'
        + '<rect x="28" y="40" width="8" height="7" rx="1.5" fill="var(--jp-white)" stroke="var(--jp-c-indigo-500)" stroke-width="1.5"/>'
        + '<rect x="60" y="40" width="8" height="7" rx="1.5" fill="var(--jp-white)" stroke="var(--jp-c-indigo-500)" stroke-width="1.5"/>'
        + '<path d="M88 50v17" stroke="var(--jp-c-peach-500)" stroke-width="2" stroke-linecap="round"/>'
        + '<circle cx="88" cy="41" r="9.5" fill="var(--jp-c-mauve-100)" stroke="var(--jp-c-mauve-500)" stroke-width="1.75"/>'
        + '</svg>';
    } else if (theme === 'travel') {
      caption = 'Đi lại & Hành trình';
      svg = '<svg class="jp-parts__week-scene-svg jp-parts__scene-svg" viewBox="0 0 112 78" fill="none" aria-hidden="true">'
        + '<ellipse cx="56" cy="68" rx="46" ry="6.5" fill="var(--jp-tab-100)"/>'
        + '<path d="M16 62L46 20l30 42H16z" fill="var(--jp-c-sky-100)" stroke="var(--jp-c-sky-500)" stroke-width="1.75" stroke-linejoin="round"/>'
        + '<path d="M36.5 33.5L46 20l9.5 13.5-4.8-2.6-4.7 3.6-4.7-3.6-4.8 2.6z" fill="var(--jp-white)" stroke="var(--jp-c-sky-500)" stroke-width="1.5" stroke-linejoin="round"/>'
        + '<rect x="30" y="48" width="64" height="17" rx="6" fill="var(--jp-c-indigo-100)" stroke="var(--jp-c-indigo-500)" stroke-width="1.75"/>'
        + '<rect x="37" y="52" width="10" height="6.5" rx="1.5" fill="var(--jp-white)" stroke="var(--jp-c-indigo-500)" stroke-width="1.4"/>'
        + '<rect x="52" y="52" width="10" height="6.5" rx="1.5" fill="var(--jp-white)" stroke="var(--jp-c-indigo-500)" stroke-width="1.4"/>'
        + '<rect x="67" y="52" width="10" height="6.5" rx="1.5" fill="var(--jp-white)" stroke="var(--jp-c-indigo-500)" stroke-width="1.4"/>'
        + '</svg>';
    } else if (theme === 'shop') {
      caption = 'Đời sống & Phố phường';
      svg = '<svg class="jp-parts__week-scene-svg jp-parts__scene-svg" viewBox="0 0 112 78" fill="none" aria-hidden="true">'
        + '<ellipse cx="56" cy="68" rx="46" ry="6.5" fill="var(--jp-tab-100)"/>'
        + '<rect x="24" y="30" width="52" height="37" rx="4.5" fill="var(--jp-c-peach-100)" stroke="var(--jp-c-peach-500)" stroke-width="1.75"/>'
        + '<rect x="20" y="25" width="60" height="12" rx="3.5" fill="var(--jp-c-purple-100)" stroke="var(--jp-c-purple-500)" stroke-width="1.75"/>'
        + '<path d="M35 25v12M50 25v12M65 25v12" stroke="var(--jp-c-purple-500)" stroke-width="1.6"/>'
        + '<rect x="32" y="44" width="16" height="23" rx="2.5" fill="var(--jp-white)" stroke="var(--jp-c-peach-500)" stroke-width="1.6"/>'
        + '<rect x="54" y="44" width="14" height="12" rx="2" fill="var(--jp-c-sky-100)" stroke="var(--jp-c-sky-500)" stroke-width="1.6"/>'
        + '<ellipse cx="89" cy="43" rx="6.5" ry="8.5" fill="var(--jp-c-coral-100)" stroke="var(--jp-c-coral-500)" stroke-width="1.6"/>'
        + '</svg>';
    } else if (theme === 'brush' || theme === 'torii') {
      caption = 'Nét chữ Kanji';
      svg = '<svg class="jp-parts__week-scene-svg jp-parts__scene-svg" viewBox="0 0 112 78" fill="none" aria-hidden="true">'
        + '<ellipse cx="56" cy="68" rx="46" ry="6.5" fill="var(--jp-c-mauve-100)"/>'
        + '<circle cx="84" cy="26" r="8" fill="var(--jp-c-peach-100)" stroke="var(--jp-c-peach-500)" stroke-width="1.6"/>'
        + '<path d="M22 25c16 3.8 44 3.8 60 0" stroke="var(--jp-c-mauve-500)" stroke-width="2.4" stroke-linecap="round"/>'
        + '<path d="M27 33h50M34 26v40M70 26v40" stroke="var(--jp-c-mauve-500)" stroke-width="2" stroke-linecap="round"/>'
        + '</svg>';
    } else {
      caption = 'Cấu trúc & Ngữ cảnh';
      svg = '<svg class="jp-parts__week-scene-svg jp-parts__scene-svg" viewBox="0 0 112 78" fill="none" aria-hidden="true">'
        + '<ellipse cx="56" cy="68" rx="46" ry="6.5" fill="var(--jp-c-teal-100)"/>'
        + '<path d="M18 58c20-16 56-16 76 0" stroke="var(--jp-c-teal-500)" stroke-width="2.4" stroke-linecap="round"/>'
        + '<path d="M30 51v14M48 46v16M64 46v16M82 51v14" stroke="var(--jp-c-teal-500)" stroke-width="1.8" stroke-linecap="round"/>'
        + '<path d="M32 20l1.8 3.6 4 .6-2.9 2.8.7 4-3.6-1.9-3.6 1.9.7-4-2.9-2.8 4-.6L32 20z" fill="var(--jp-c-peach-100)" stroke="var(--jp-c-teal-500)" stroke-width="1.5" stroke-linejoin="round"/>'
        + '<path d="M80 16l1.5 3.1 3.4.5-2.5 2.4.6 3.4-3-1.6-3 1.6.6-3.4-2.5-2.4 3.4-.5L80 16z" fill="var(--jp-c-peach-100)" stroke="var(--jp-c-teal-500)" stroke-width="1.5" stroke-linejoin="round"/>'
        + '</svg>';
    }
    return '<div class="jp-parts__week-scene jp-parts__week-scene--' + side + '" aria-hidden="true">'
      + svg
      + '<span class="jp-parts__week-scene-cap">' + esc(caption) + '</span>'
      + '</div>';
  }

  /* Round 6 Item 3c: Large decorative week signpost at the margin ("Tuần 2 · 12/11–18/11"), non-clickable */
  function weekSignpostHtml(weekObj, side) {
    return '<div class="jp-parts__week-sign jp-parts__week-sign--' + side + '" aria-hidden="true">'
      + '<span class="jp-parts__week-sign-post"></span>'
      + '<span class="jp-parts__week-sign-board">'
      + '<strong class="jp-parts__week-sign-title">Tuần ' + esc(weekObj.weekNum) + '</strong>'
      + '<span class="jp-parts__week-sign-dates">' + esc(weekObj.dateRange) + '</span>'
      + '</span>'
      + '</div>';
  }

  /* Round 6 Item 1d: 1 side Kitsu per week, pose varies by week */
  // [JLAS-W0108] C2: the side Kitsu follows the week's progress - celebrates a finished week, points at the
  // current one, wonders about the weeks ahead (the old per-week pose stays when the state is unknown)
  var K = '/images/mascots/kitsu/';
  var MOOD_KITSUS = {
    past: [{ src: K + 'kitsu-celebrate-30.webp', alt: 'Kitsu ăn mừng', say: 'Xong tuần này rồi!' },
      { src: K + 'kitsu-cheer-30.webp', alt: 'Kitsu cổ vũ', say: 'Giỏi lắm!' }],
    current: [{ src: K + 'kitsu-encouraging.webp', alt: 'Kitsu khích lệ', say: 'Tuần này đây!' },
      { src: K + 'kitsu-cheer-30.webp', alt: 'Kitsu cổ vũ', say: 'Từng bước một!' }],
    upcoming: [{ src: K + 'kitsu-thinking-30.webp', alt: 'Kitsu suy nghĩ', say: 'Sắp tới nè!' },
      { src: K + 'kitsu-preparing.webp', alt: 'Kitsu chuẩn bị', say: 'Chờ mình nhé!' }]
  };
  function weekSideKitsuHtml(weekNum, side, state, second) {
    var mood = MOOD_KITSUS[state];
    var k = mood ? mood[second ? 1 : 0] : SIDE_KITSUS[(Math.max(1, Number(weekNum) || 1) - 1) % SIDE_KITSUS.length];
    return '<span class="jp-parts__mascot jp-parts__mascot--side jp-parts__mascot--' + side + '" aria-hidden="true">'
      + '<img class="jp-parts__mascot-img" src="' + small(k.src) + '" width="56" height="56" loading="lazy" alt="' + esc(k.alt) + '"/>'
      + '<span class="jp-parts__mascot-speech jp-parts__mascot-speech--sm">' + esc(k.say) + '</span>'
      + '</span>';
  }

  /* Round 6 Item 4d: 6px connecting curve */
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

  function miniRingSvg(doneCount, totalCount, isDone) {
    var tot = Math.max(1, Number(totalCount) || 1);
    var dn = Math.min(tot, Math.max(0, Number(doneCount) || 0));
    var pct = isDone ? 100 : Math.round((dn / tot) * 100);
    var r = 14;
    var c = 2 * Math.PI * r;
    var offset = (c - (pct / 100) * c).toFixed(1);
    return '<span class="jp-parts__pop-ring' + (isDone ? ' is-done' : '') + '" title="Tiến độ phần: ' + dn + '/' + tot + '">'
      + '<svg viewBox="0 0 36 36" width="36" height="36" aria-hidden="true">'
      + '<circle class="jp-parts__pop-ring-bg" cx="18" cy="18" r="' + r + '" fill="none" stroke-width="3.2"/>'
      + '<circle class="jp-parts__pop-ring-fg" cx="18" cy="18" r="' + r + '" fill="none" stroke-width="3.2"'
      + ' stroke-dasharray="' + c.toFixed(1) + '" stroke-dashoffset="' + offset + '" stroke-linecap="round"/>'
      + '</svg>'
      + '<span class="jp-parts__pop-ring-txt">' + dn + '/' + tot + '</span>'
      + '</span>';
  }

  function bannerProgressRingHtml(learnedNum, totalNum, pct) {
    var r = 20;
    var c = 2 * Math.PI * r;
    var offset = (c - (pct / 100) * c).toFixed(1);
    return '<div class="jp-parts__banner-progress" aria-label="Tiến độ đã học ' + esc(learnedNum) + '/' + esc(totalNum) + '">'
      + '<span class="jp-parts__banner-ring" aria-hidden="true">'
      + '<svg viewBox="0 0 48 48" width="48" height="48">'
      + '<circle class="jp-parts__banner-ring-bg" cx="24" cy="24" r="' + r + '" fill="none" stroke-width="3.5"/>'
      + '<circle class="jp-parts__banner-ring-fg" cx="24" cy="24" r="' + r + '" fill="none" stroke-width="3.5"'
      + ' stroke-dasharray="' + c.toFixed(1) + '" stroke-dashoffset="' + offset + '" stroke-linecap="round"/>'
      + '</svg>'
      + '<strong class="jp-parts__banner-pct">' + pct + '%</strong>'
      + '</span>'
      + '<div class="jp-parts__banner-counts">'
      + '<span class="jp-parts__banner-frac"><strong>' + esc(learnedNum) + '</strong>/' + esc(totalNum) + '</span>'
      + '<span class="jp-parts__banner-lbl">Đã học</span>'
      + '</div>'
      + '</div>';
  }

  /* owner 25/11: only kana and kanji have Luyện viết, never words */ var WRITE_TYPES = { kanji: 'KANJI', kana: 'KANA' };

  /** owner 22/11: a word / Kanji / kana pill opens "Luyện viết" (stroke order, tracing cells, In phiếu viết). */
  function wordPillHtml(it, tabKey) {
    var label = (it && (it.label || it.text || it.expression || '')) || '';
    var rd = it && it.reading && it.reading !== label ? it.reading : '';
    var mean = (it && (it.meaning || it.meaningVi || '')) || '';
    var type = WRITE_TYPES[String(tabKey || '').toLowerCase()];
    // F216 (owner 22/11): an item picked for this learner wears a "Dành cho bạn" tag (the why is never shown)
    var mine = it && it.forYou ? '<span class="jp-for-you" title="Mục này được chọn riêng cho bạn">Dành cho bạn</span>' : '';
    var inner = mine + (rd ? '<span class="jp-parts__pill-rd" lang="ja">' + esc(rd) + '</span>' : '<span class="jp-parts__pill-rd" aria-hidden="true">&nbsp;</span>')
      + '<span class="jp-parts__pill-ja" lang="ja">' + esc(label) + '</span>'
      + (mean ? '<span class="jp-parts__pill-mean">' + esc(mean) + '</span>' : '');
    if (type && it && it.code) {
      return '<li class="jp-parts__word-pill jp-parts__word-pill--write">'
        + '<button type="button" class="jp-parts__pill-btn" title="Bấm để luyện viết" data-jpath-writing="'
        + esc(JSON.stringify({ itemType: type, code: it.code })) + '">' + inner + '</button></li>';
    }
    return '<li class="jp-parts__word-pill">' + inner + '</li>';
  }

  function pillsGridHtml(items, unit, expanded, tabKey) {
    var arr = list(items);
    if (!arr.length) return '<p class="jp-parts__pop-empty">Chưa có mục nào trong phần này.</p>';
    var showAll = !!expanded || arr.length <= PILL_PREVIEW_COUNT;
    var first = showAll ? arr : arr.slice(0, PILL_PREVIEW_COUNT);
    var restCount = arr.length - first.length;
    var moreBtn = restCount > 0
      ? '<li class="jp-parts__word-pill jp-parts__word-pill--more">'
        + '<button type="button" class="jp-parts__more-pills" data-parts-more-pills>'
        + '+' + restCount + ' ' + esc(unit)
        + '</button></li>'
      : '';
    return '<ul class="jp-parts__pills-grid jp-parts__items">'
      + first.map(function (it) { return wordPillHtml(it, tabKey); }).join('')
      + moreBtn
      + '</ul>';
  }

  function weeklyButtonsHtml() {
    return '<div class="jp-parts__actions jp-parts__weekly-btns">' + ['VOCAB', 'KANJI', 'GRAMMAR'].map(function (t) {
      return '<button type="button" class="jp-btn jp-btn--sm" data-parts-weekly="' + t + '">'
        + { VOCAB: 'Từ vựng', KANJI: 'Kanji', GRAMMAR: 'Ngữ pháp' }[t] + '</button>';
    }).join('') + '</div>';
  }

  /* Round 6 Item 1a: Animated Kitsu beside Today's node with speech bubble */
  function kitsuBesideTodayHtml(isTodayDone, labelSide) {
    var src = isTodayDone ? '/images/mascots/kitsu/kitsu-celebrate-30.webp' : '/images/mascots/kitsu/kitsu-cheer-30.webp';
    var say = isTodayDone ? 'Đã xong! Bấm xem lại' : 'Bấm vào đây để học';
    var sideCls = labelSide === 'left' ? 'jp-parts__mascot--right' : 'jp-parts__mascot--left';
    return '<span class="jp-parts__mascot jp-parts__mascot--today ' + sideCls + '">'
      + '<span class="jp-parts__mascot-speech">' + esc(say) + '</span>'
      + '<img class="jp-parts__mascot-img" src="' + src + '" width="72" height="72" loading="lazy" alt="Kitsu"/>'
      + '</span>';
  }

  function popoverSideForPos(pos) {
    return pos === 'right' ? 'left' : 'right';
  }

  /** F226 (owner 22/11): "In cả phần" — the writing sheet of every item of the part, in a new tab. */
  function printPartHtml(tab, index) {
    return '<a class="jp-btn jp-btn--sm" target="_blank" rel="noopener" href="/learning/jlpt/print?scope=PART&tab='
      + encodeURIComponent(tab) + '&part=' + encodeURIComponent(index) + '">In cả phần</a>';
  }

  // owner 24/11: set by mount — the tab is still closed behind the kana stage ("Sau chữ cái")
  var lockedByKana = false;
  var TAB_NAME = { vocab: 'Từ vựng', kanji: 'Kanji', grammar: 'Ngữ pháp' };

  function partPopoverHtml(p, unit, tabKey, pos, pillsExpanded) {
    var items = list(p.items).length ? list(p.items) : list(p.preview);
    var n = Number(p.totalCount) || items.length || 10;
    var status = String(p.status || 'NEXT');
    var isToday = status === 'TODAY';
    var isTodayDone = status === 'TODAY_DONE';
    var isDone = status === 'DONE';
    var tone = (isDone || isTodayDone) ? 'done' : (isToday ? 'today' : 'next');
    var popSide = popoverSideForPos(pos);

    var mascotSrc = '/images/mascots/kitsu/kitsu-encouraging.webp';
    var statusLabel = 'Hôm nay';
    var statusIcon = svgStar();
    if (isTodayDone) {
      mascotSrc = '/images/mascots/kitsu/kitsu-celebrate-30.webp';
      statusLabel = 'Đã học hôm nay';
      statusIcon = svgMedal('jp-parts__pop-pill-ic');
    } else if (isDone) {
      mascotSrc = '/images/mascots/kitsu/kitsu-cheer-30.webp';
      statusLabel = 'Đã học';
      statusIcon = svgMedal('jp-parts__pop-pill-ic');
    } else if (!isToday) {
      mascotSrc = '/images/mascots/kitsu/kitsu-thinking-30.webp';
      statusLabel = 'Sắp tới';
      statusIcon = svgLock();
    }
    var dateStr = p.day ? dayLabel(p.day) : (p.date ? dayLabel(p.date) : '');
    var mineCount = items.filter(function (it) { return it && it.forYou; }).length;
    var doneItems = (isDone || isTodayDone) ? n : Math.min(n, Math.max(0, Number(p.learnedCount) || 0));

    var actions = '';
    if (isToday) {
      actions = '<button type="button" class="jp-btn jp-btn--primary jp-parts__pop-main-btn" data-action="start-mode"'
        + ' data-mode="SPLIT" data-tab-key="' + esc(tabKey) + '">Học phần này</button>';
    } else if (isTodayDone || isDone) {
      var first = items[0];
      var firstCode = (first && first.code) || p.firstItemCode || '';
      actions = (firstCode ? '<button type="button" class="jp-btn jp-btn--primary jp-parts__pop-main-btn" data-parts-relearn="' + esc(firstCode)
        + '" data-parts-count="' + n + '">Học lại phần này</button>'
        + '<span class="jp-parts__err" role="status" data-parts-relearn-error></span>' : '')
        + (p.day ? '<a class="jp-btn jp-btn--sm" href="/learning/jlpt/day?d=' + esc(p.day) + '">Kết quả lần trước</a>' : '');
    } else if (lockedByKana) {
      // owner 24/11: the real reason — kanji and grammar open after the kana stage, not after today's part
      actions = '<span class="jp-parts__locked-note">' + esc(TAB_NAME[String(tabKey || '').toLowerCase()] || 'Phần này')
        + ' mở sau khi học xong Nhập môn (chữ cái)</span>'
        + '<a class="jp-btn jp-btn--primary jp-parts__pop-main-btn" href="/learning?tab=kana">Học Nhập môn</a>';
    } else {
      actions = '<span class="jp-parts__locked-note">Học xong phần hôm nay để mở khóa</span>';
    }
    // owner 18/11: every vocab/kanji part can be studied with the team's flashcard (upcoming: set made on press)
    if (/^(vocab|kanji)$/i.test(String(tabKey || '')) && items.length) {
      actions += '<button type="button" class="jp-btn jp-btn--sm" data-parts-fc="' + esc(p.index) + '">Học bằng flashcard</button>'
        + printPartHtml(String(tabKey).toUpperCase(), p.index)
        + '<span class="jp-parts__err" role="status" data-parts-fc-error></span>';
    }

    return '<div class="jp-parts__popover jp-parts__popover--' + tone + ' jp-parts__popover--side-' + popSide + '"'
      + ' data-parts-popover="part-' + esc(p.index) + '" role="dialog" aria-label="Chi tiết Phần ' + esc(p.index) + '">'
      + '<span class="jp-parts__popover-arrow" aria-hidden="true"></span>'
      + '<div class="jp-parts__pop-head">'
      + '<img class="jp-parts__pop-kitsu" src="' + small(mascotSrc) + '" width="48" height="48" alt="Kitsu"/>'
      + '<div class="jp-parts__pop-meta">'
      + '<div class="jp-parts__pop-badges">'
      + '<span class="jp-parts__pop-pill jp-parts__pop-pill--' + tone + '">' + statusIcon + '<span>' + esc(statusLabel) + '</span></span>'
      + (dateStr ? '<span class="jp-parts__pop-date">' + esc(dateStr) + '</span>' : '')
      + (mineCount ? '<span class="jp-for-you">' + mineCount + ' mục dành cho bạn</span>' : '')
      + '</div>'
      + '<h3 class="jp-parts__pop-title">Phần ' + esc(p.index) + ' · ' + n + ' ' + esc(unit) + '</h3>'
      + '</div>'
      + '<button type="button" class="jp-parts__pop-close" data-parts-pop-close aria-label="Đóng bong bóng">×</button>'
      + '</div>'
      + '<div class="jp-parts__pop-body">'
      + pillsGridHtml(items, unit, pillsExpanded, tabKey)
      + '</div>'
      + '<div class="jp-parts__pop-foot">'
      + '<div class="jp-parts__pop-actions">' + actions + '</div>'
      + miniRingSvg(doneItems, n || 1, isDone || isTodayDone)
      + '</div>'
      + '</div>';
  }

  function milestonePopoverHtml(m, pos) {
    var unlocked = !!m.unlocked;
    var tone = unlocked ? 'done' : 'today';
    var popSide = popoverSideForPos(pos);
    var mascotSrc = unlocked
      ? '/images/mascots/kitsu/kitsu-celebrate-30.webp'
      : '/images/mascots/kitsu/kitsu-cheer-30.webp';
    var statusLabel = unlocked ? 'Đã nhận cúp tuần' : 'Mốc kiểm tra tuần';
    var statusIcon = unlocked ? svgTrophyCup('jp-parts__pop-pill-ic') : svgChestClosed();
    var partPills = [];
    for (var idx = m.fromIndex; idx <= m.toIndex; idx++) {
      partPills.push('<li class="jp-parts__word-pill"><span class="jp-parts__pill-rd">Chặng tuần ' + esc(m.weekNum) + '</span>'
        + '<span class="jp-parts__pill-ja">Phần ' + idx + '</span>'
        + '<span class="jp-parts__pill-mean">' + (unlocked ? 'Đã hoàn thành' : 'Trong chặng') + '</span></li>');
    }

    return '<div class="jp-parts__popover jp-parts__popover--' + tone + ' jp-parts__popover--side-' + popSide + '"'
      + ' data-parts-popover="milestone-' + esc(m.weekNum) + '" role="dialog" aria-label="Rương tuần ' + esc(m.weekNum) + '">'
      + '<span class="jp-parts__popover-arrow" aria-hidden="true"></span>'
      + '<div class="jp-parts__pop-head">'
      + '<img class="jp-parts__pop-kitsu" src="' + small(mascotSrc) + '" width="48" height="48" alt="Kitsu"/>'
      + '<div class="jp-parts__pop-meta">'
      + '<div class="jp-parts__pop-badges">'
      + '<span class="jp-parts__pop-pill jp-parts__pop-pill--' + tone + '">' + statusIcon + '<span>' + esc(statusLabel) + '</span></span>'
      + (m.dateRange ? '<span class="jp-parts__pop-date">' + esc(m.dateRange) + '</span>' : '')
      + '</div>'
      + '<h3 class="jp-parts__pop-title">Kiểm tra tuần ' + esc(m.weekNum) + ' · Phần ' + esc(m.fromIndex) + '–' + esc(m.toIndex) + '</h3>'
      + '</div>'
      + '<button type="button" class="jp-parts__pop-close" data-parts-pop-close aria-label="Đóng bong bóng">×</button>'
      + '</div>'
      + '<div class="jp-parts__pop-body">'
      + '<p class="jp-parts__pop-desc">Ôn 10 câu từ các mục thuộc Phần ' + esc(m.fromIndex) + '–' + esc(m.toIndex) + '. Mục sai tự động vào Sổ tay.</p>'
      + '<ul class="jp-parts__pills-grid">' + partPills.join('') + '</ul>'
      + '</div>'
      + '<div class="jp-parts__pop-foot">'
      + '<div class="jp-parts__pop-actions">' + weeklyButtonsHtml() + '</div>'
      + miniRingSvg(m.doneParts, m.totalParts, unlocked)
      + '</div>'
      + '</div>';
  }

  function partNodeHtml(p, unit, tabKey, pos, nextPos, hasNext, nextStatus, stepIdx, weekObj, isSelected, pillsExpanded, isFirstInWeek, globalStepIdx) {
    var items = list(p.items);
    var n = Number(p.totalCount) || items.length || 10;
    var status = String(p.status || 'NEXT');
    var isToday = status === 'TODAY';
    var isTodayDone = status === 'TODAY_DONE';
    var isDone = status === 'DONE';
    var isPastWeek = weekObj.state === 'past';
    var isUpcomingWeek = weekObj.state === 'upcoming';

    var when = isToday ? 'Hôm nay' : (isTodayDone ? 'Đã xong hôm nay'
      : (isDone ? (p.day ? dayLabel(p.day) : 'Đã học') : 'Sắp tới'));
    var stepMod = isTodayDone ? 'today-done' : status.toLowerCase();
    var icon = (isDone || isTodayDone) ? svgMedal() : (isToday ? svgStar() : svgLock());
    var bubble = isToday
      ? '<span class="jp-parts__bubble">Bắt đầu</span>'
      : (isTodayDone ? '<span class="jp-parts__bubble jp-parts__bubble--done">Đã xong</span>' : '');

    var labelSide = pos === 'right' ? 'left' : 'right';
    var signSide = (weekObj.weekNum % 2 === 1) ? 'left' : 'right';
    var sceneSide = signSide === 'left' ? 'right' : 'left';

    /* Side margin ornaments per week:
     * - Step 0 in week (isFirstInWeek): decorative Week Signpost ("Tuần X · dd/mm–dd/mm")
     * - Step 1 in week: Weekly themed miniature scene (house / food / school / travel / shop / torii / stars)
     * - Step 3 in week: Weekly side Kitsu (1 per week, pose varies by week)
     * - Other steps: small tree / lantern / sakura / torii deco */
    var marginExtras = '';
    var kitsuStepIdx = weekObj.parts && weekObj.parts.length >= 4 ? 3 : Math.max(0, (weekObj.parts ? weekObj.parts.length : 1) - 1);
    if (isFirstInWeek) {
      marginExtras += weekSignpostHtml(weekObj, signSide);
      if (weekObj.parts && weekObj.parts.length === 1) {
        marginExtras += weekThemeSceneHtml(weekObj, tabKey, sceneSide);
      }
    }
    if (stepIdx === 1) {
      marginExtras += weekThemeSceneHtml(weekObj, tabKey, sceneSide);
    }
    // [JLAS-W0108] F102: the side Kitsu (104px + speech) stands in the margin away from the node: a node on the right
    // has its margin on the left, a node on the left has it on the right, and a centre node keeps its label on the right,
    // so its Kitsu goes left. Before, a Kitsu on the node's own side covered the orb and its number on wide screens.
    var kitsuSide = pos === 'left' ? 'right' : 'left';
    if (stepIdx === kitsuStepIdx && (!isToday && !isTodayDone || kitsuStepIdx === 0)) {
      marginExtras += weekSideKitsuHtml(weekObj.weekNum, kitsuSide, weekObj.state, false);
    } else if (stepIdx === 5 && !isToday && !isTodayDone) {
      marginExtras += weekSideKitsuHtml(Number(weekObj.weekNum || 1) + 3, kitsuSide, weekObj.state, true);
    } else if (stepIdx === 2 || stepIdx === 4) {
      var decoSide = (globalStepIdx % 2 === 0) ? 'left' : 'right';
      marginExtras += '<span class="jp-parts__deco jp-parts__deco--' + decoSide + '" aria-hidden="true">'
        + sideDecoSvg(globalStepIdx, tabKey) + '</span>';
    }

    var mascotHtml = (isToday || isTodayDone) ? kitsuBesideTodayHtml(isTodayDone, labelSide) : '';

    var isLeadingToToday = (isDone || isTodayDone) && (nextStatus === 'TODAY' || nextStatus === 'TODAY_DONE' || isToday);
    var connector = hasNext ? curveSvg(pos, nextPos, isDone || isTodayDone, isLeadingToToday, isPastWeek) : '';
    var selectKey = 'part-' + p.index;
    var weekAnchorAttr = isFirstInWeek ? ' data-parts-week-anchor="' + weekObj.weekNum + '"' : '';

    if (isUpcomingWeek) {
      var tipText = 'Mở vào tuần ' + weekObj.dateRange + ' hoặc học trước bằng nút Học thêm';
      return '<li class="jp-parts__step jp-parts__step--next jp-parts__step--upcoming-week jp-parts__step--pos-' + pos + '"'
        + weekAnchorAttr + ' style="--step-i:' + stepIdx + '">'
        + '<div class="jp-parts__node-row">'
        + marginExtras
        + '<div class="jp-parts__head jp-parts__head--locked" aria-disabled="true" title="' + esc(tipText) + '">'
        + '<span class="jp-parts__node-wrap">'
        + '<span class="jp-parts__orb jp-parts__orb--next" aria-hidden="true">'
        + svgLock()
        + '<span class="jp-parts__orb-num">' + esc(p.index) + '</span>'
        + '</span>'
        + '</span>'
        + '<span class="jp-parts__label jp-parts__label--' + labelSide + '">'
        + '<span class="jp-parts__no">Phần ' + esc(p.index) + '</span>'
        + '<span class="jp-parts__when">' + esc(tipText) + '</span>'
        + '</span>'
        + '<span class="jp-parts__lock-tip" role="tooltip">' + esc(tipText) + '</span>'
        + '</div>'
        + '</div>'
        + connector
        + '</li>';
    }

    var popHtml = isSelected ? partPopoverHtml(p, unit, tabKey, pos, pillsExpanded) : '';

    return '<li class="jp-parts__step jp-parts__step--' + stepMod
      + (isPastWeek ? ' jp-parts__step--past-week' : '')
      + ' jp-parts__step--pos-' + pos
      + (isSelected ? ' is-selected' : '') + '"'
      + weekAnchorAttr
      + (isToday || isTodayDone ? ' data-parts-today="true"' : '')
      + ' style="--step-i:' + stepIdx + '">'
      + '<div class="jp-parts__node-row">'
      + marginExtras
      + '<button type="button" class="jp-parts__head' + (isSelected ? ' is-selected' : '') + '"'
      + ' data-parts-node="' + esc(selectKey) + '" data-parts-select="' + esc(selectKey) + '"'
      + ' aria-expanded="' + (isSelected ? 'true' : 'false') + '">'
      + '<span class="jp-parts__node-wrap">'
      + bubble
      + '<span class="jp-parts__orb jp-parts__orb--' + stepMod + '" aria-hidden="true">'
      + icon
      + '<span class="jp-parts__orb-num">' + esc(p.index) + '</span>'
      + '</span>'
      + mascotHtml
      + '</span>'
      + '<span class="jp-parts__label jp-parts__label--' + labelSide + '">'
      + '<span class="jp-parts__no">Phần ' + esc(p.index) + '</span>'
      + '<span class="jp-parts__when">' + esc(when) + ' · ' + n + ' ' + unit + '</span>'
      + '</span>'
      + '</button>'
      + popHtml
      + '</div>'
      + connector
      + '</li>';
  }

  function milestoneNodeHtml(m, pos, nextPos, hasNext, stepIdx, weekObj, isSelected) {
    var unlocked = !!m.unlocked;
    var isPastWeek = weekObj.state === 'past';
    var isUpcomingWeek = weekObj.state === 'upcoming';
    var isOpenChest = unlocked || isPastWeek;
    var chestState = isUpcomingWeek ? 'locked' : (isOpenChest ? 'open' : 'current');
    var stateCls = isOpenChest ? 'jp-parts__milestone--unlocked' : 'jp-parts__milestone--locked';
    var chestSvg = svgBigChest(chestState);
    var labelSide = pos === 'right' ? 'left' : 'right';
    var selectKey = 'milestone-' + m.weekNum;
    var connector = hasNext ? curveSvg(pos, nextPos, isOpenChest, false, isPastWeek) : '';

    if (isUpcomingWeek) {
      var tipText = 'Mở vào tuần ' + weekObj.dateRange + ' hoặc học trước bằng nút Học thêm';
      return '<li class="jp-parts__step jp-parts__step--milestone jp-parts__step--upcoming-week ' + stateCls + ' jp-parts__step--pos-' + pos + '"'
        + ' style="--step-i:' + stepIdx + '">'
        + '<div class="jp-parts__node-row">'
        + '<div class="jp-parts__head jp-parts__milestone-head jp-parts__head--locked" aria-disabled="true" title="' + esc(tipText) + '">'
        + '<span class="jp-parts__node-wrap">'
        + '<span class="jp-parts__orb jp-parts__orb--milestone jp-parts__orb--chest-locked" aria-hidden="true">'
        + chestSvg
        + '</span>'
        + '</span>'
        + '<span class="jp-parts__label jp-parts__label--' + labelSide + '">'
        + '<span class="jp-parts__no">Rương tuần ' + esc(m.weekNum) + '</span>'
        + '<span class="jp-parts__when">' + esc(tipText) + '</span>'
        + '</span>'
        + '<span class="jp-parts__lock-tip" role="tooltip">' + esc(tipText) + '</span>'
        + '</div>'
        + '</div>'
        + connector
        + '</li>';
    }

    var popHtml = isSelected ? milestonePopoverHtml(m, pos) : '';

    return '<li class="jp-parts__step jp-parts__step--milestone ' + stateCls
      + (isPastWeek ? ' jp-parts__step--past-week' : ' jp-parts__step--current-week')
      + ' jp-parts__step--pos-' + pos
      + (isSelected ? ' is-selected' : '') + '" style="--step-i:' + stepIdx + '">'
      + '<div class="jp-parts__node-row">'
      + '<button type="button" class="jp-parts__head jp-parts__milestone-head' + (isSelected ? ' is-selected' : '') + '"'
      + ' data-parts-node="' + esc(selectKey) + '" data-parts-select="' + esc(selectKey) + '"'
      + ' aria-expanded="' + (isSelected ? 'true' : 'false') + '">'
      + '<span class="jp-parts__node-wrap">'
      + '<span class="jp-parts__orb jp-parts__orb--milestone ' + (isOpenChest ? 'jp-parts__orb--chest-open' : 'jp-parts__orb--chest-current') + '" aria-hidden="true">'
      + chestSvg
      + '</span>'
      + '</span>'
      + '<span class="jp-parts__label jp-parts__label--' + labelSide + '">'
      + '<span class="jp-parts__no">' + (isOpenChest ? 'Rương tuần ' : 'Rương tuần ') + esc(m.weekNum) + '</span>'
      + '<span class="jp-parts__when">Phần ' + esc(m.fromIndex) + '–' + esc(m.toIndex) + '</span>'
      + '</span>'
      + '</button>'
      + popHtml
      + '</div>'
      + connector
      + '</li>';
  }

  function horizonMilestoneHtml(remainingWeeks) {
    if (remainingWeeks <= 0) return '';
    return '<li class="jp-parts__step jp-parts__step--horizon" aria-disabled="true">'
      + '<div class="jp-parts__horizon" role="note">'
      + '<span class="jp-parts__horizon-icon" aria-hidden="true">' + svgHorizonFlag() + '</span>'
      + '<span class="jp-parts__horizon-text">… còn ' + esc(remainingWeeks) + ' tuần nữa</span>'
      + '</div>'
      + '</li>';
  }

  function weeklyHtml(week) {
    var days = list(week).filter(function (d) { return d && d.kind !== 'REST'; });
    if (!days.length) {
      return '<div class="jp-parts__weekly"><p class="jp-parts__weekly-title">Kiểm tra tuần</p>'
        + '<p class="jp-parts__sub">10 câu mỗi kỹ năng từ các mục học trong 7 ngày qua.</p>'
        + weeklyButtonsHtml() + '</div>';
    }
    var last = days[days.length - 1];
    var today = list(week).filter(function (d) { return d && d.today; })[0];
    if (today && today.day !== last.day) {
      return '<div class="jp-parts__weekly jp-parts__weekly--upcoming">'
        + '<p class="jp-parts__weekly-title">Kiểm tra tuần</p>'
        + '<p class="jp-parts__weekly-note">Mở vào <strong>' + esc(dayLabel(last.day)) + '</strong> (các mục học trong tuần).</p>'
        + weeklyButtonsHtml()
        + '</div>';
    }
    return '<div class="jp-parts__weekly"><p class="jp-parts__weekly-title">Hôm nay có kiểm tra tuần</p>'
      + '<p class="jp-parts__sub">10 câu mỗi phần, chỉ các mục bạn học trong 7 ngày qua. Mục sai vào Sổ tay.</p>'
      + weeklyButtonsHtml() + '</div>';
  }

  function estimateText(data, opts) {
    if (opts && opts.estimate) return opts.estimate;
    if (root && root.document) {
      var statVal = root.document.querySelector('[data-region="numbers"] .jp-stat__value');
      if (statVal && statVal.textContent && statVal.textContent.trim() !== '—') {
        return statVal.textContent.trim();
      }
    }
    var parts = list(data && data.parts);
    var rem = parts.filter(function (p) { return p && p.status !== 'DONE' && p.status !== 'TODAY_DONE'; }).length;
    if (!rem) return 'Đã hoàn thành';
    var dt = new Date(Date.now() + rem * 86400000);
    return String(dt.getDate()).padStart(2, '0') + '/' + String(dt.getMonth() + 1).padStart(2, '0') + '/' + dt.getFullYear();
  }

  function resolveSceneKey(tabKey, data) {
    var k = String(tabKey || '').toLowerCase();
    if (TABS[k]) return k;
    var raw = String((data && data.tab) || '').toUpperCase();
    if (raw === 'KANJI') return 'kanji';
    if (raw === 'GRAMMAR') return 'grammar';
    return 'vocab';
  }

  function buildWeeks(parts) {
    var weeks = [];
    var totalParts = parts.length;
    if (!totalParts) return weeks;

    var currentWeekIdx = 0;
    for (var idx = 0; idx < totalParts; idx++) {
      var st = parts[idx] && parts[idx].status;
      if (st === 'TODAY' || st === 'TODAY_DONE') {
        currentWeekIdx = Math.floor(idx / WEEK_SPAN);
        break;
      }
      if (st === 'DONE') {
        currentWeekIdx = Math.min(Math.floor((totalParts - 1) / WEEK_SPAN), Math.floor((idx + 1) / WEEK_SPAN));
      }
    }

    var baseMs = Date.now();
    var totalW = Math.ceil(totalParts / WEEK_SPAN);
    for (var w = 0; w < totalW; w++) {
      var slice = parts.slice(w * WEEK_SPAN, (w + 1) * WEEK_SPAN);
      var normalized = [];
      var doneCount = 0;
      var hasToday = false;
      for (var j = 0; j < slice.length; j++) {
        var rawP = slice[j] || {};
        var pIndex = rawP.index != null ? Number(rawP.index) : (w * WEEK_SPAN + j + 1);
        var copy = Object.assign({}, rawP, { index: pIndex });
        normalized.push(copy);
        if (copy.status === 'DONE' || copy.status === 'TODAY_DONE') doneCount++;
        if (copy.status === 'TODAY' || copy.status === 'TODAY_DONE') hasToday = true;
      }
      var fromIndex = normalized[0].index;
      var toIndex = normalized[normalized.length - 1].index;
      var weekNum = w + 1;
      var allDone = doneCount === normalized.length && normalized.length > 0;
      var isCurrent = w === currentWeekIdx;
      var state = w < currentWeekIdx ? 'past' : (isCurrent ? 'current' : 'upcoming');

      var weekOffset = w - currentWeekIdx;
      var startMs = baseMs + weekOffset * 7 * 86400000;
      var endMs = startMs + 6 * 86400000;
      var dateRange = shortDdMm(startMs) + '–' + shortDdMm(endMs);

      weeks.push({
        weekNum: weekNum,
        state: state,
        parts: normalized,
        fromIndex: fromIndex,
        toIndex: toIndex,
        doneCount: doneCount,
        totalParts: normalized.length,
        allDone: allDone,
        hasToday: hasToday,
        isCurrent: isCurrent,
        dateRange: dateRange,
        milestone: {
          kind: 'milestone',
          key: 'milestone-' + weekNum,
          weekNum: weekNum,
          fromIndex: fromIndex,
          toIndex: toIndex,
          doneParts: doneCount,
          totalParts: normalized.length,
          dateRange: dateRange,
          unlocked: allDone
        }
      });
    }
    return weeks;
  }

  function partsHtml(data, tabKey, page, opts) {
    void page;
    var d = data || {};
    var o = opts || {};
    var sceneKey = resolveSceneKey(tabKey, d);
    var t = TABS[sceneKey] || ['', 'mục', ''];
    var parts = list(d.parts);
    var weeks = buildWeeks(parts);
    var totalWeeks = Math.max(1, weeks.length);

    var currentWeekNum = 1;
    for (var i = 0; i < weeks.length; i++) {
      if (weeks[i].isCurrent) { currentWeekNum = weeks[i].weekNum; break; }
    }

    var focusedWeek = Math.min(currentWeekNum, Math.max(1, o.focusedWeek || currentWeekNum));
    var activeKey = o.selectedKey != null ? String(o.selectedKey) : '';
    var pillsExpanded = !!o.pillsExpanded;

    var totalNum = Number(d.total) || 0;
    var learnedNum = Number(d.learned) || 0;
    var pct = totalNum > 0 ? Math.min(100, Math.round((learnedNum / totalNum) * 100)) : 0;
    var est = estimateText(d, o);

    var bannerHtml = '<header class="jp-parts__banner jp-parts__bar jp-parts__banner--' + esc(sceneKey) + '" data-tab-scene="' + esc(sceneKey) + '" aria-label="Thông tin bản đồ ' + esc(t[2]) + '">'
      + '<div class="jp-parts__banner-top">'
      + '<div class="jp-parts__banner-left">'
      + '<span class="jp-parts__banner-icon" aria-hidden="true">' + tabBannerIconSvg(sceneKey) + '</span>'
      + '<div class="jp-parts__banner-titles">'
      + '<h2 class="jp-parts__title">' + esc(t[2]) + (d.level ? ' ' + esc(d.level) : '') + '</h2>'
      + '<p class="jp-parts__sub">' + esc(totalNum) + ' ' + esc(t[1]) + ' · xong dự kiến <strong>' + esc(est) + '</strong></p>'
      + '</div>'
      + '</div>'
      + '<div class="jp-parts__banner-center">'
      + bannerProgressRingHtml(learnedNum, totalNum, pct)
      + '</div>'
      + '<div class="jp-parts__banner-right">'
      + '<div class="jp-parts__week-nav" role="group" aria-label="Chọn tuần học">'
      + '<button type="button" class="jp-parts__week-btn" data-parts-week-nav="prev"'
      + (focusedWeek <= 1 ? ' disabled' : '') + ' aria-label="Tuần trước">' + svgChevron('left') + '</button>'
      + '<span class="jp-parts__week-ind">Tuần <strong>' + focusedWeek + '/' + totalWeeks + '</strong></span>'
      + '<button type="button" class="jp-parts__week-btn" data-parts-week-nav="next"'
      + (focusedWeek >= currentWeekNum ? ' disabled' : '') + ' aria-label="Tuần tiếp theo">' + svgChevron('right') + '</button>'
      + '</div>'
      + '</div>'
      + '</div>'
      + '<div class="jp-parts__banner-sub">'
      + '<div class="jp-parts__legend" aria-label="Chú giải trạng thái">'
      + '<span class="jp-parts__legend-pill jp-parts__legend-pill--done"><span class="jp-parts__legend-ic">' + svgMedal('jp-parts__legend-svg') + '</span>Đã học</span>'
      + '<span class="jp-parts__legend-pill jp-parts__legend-pill--today"><span class="jp-parts__legend-ic">' + svgStar() + '</span>Hôm nay</span>'
      + '<span class="jp-parts__legend-pill jp-parts__legend-pill--next"><span class="jp-parts__legend-ic">' + svgLock() + '</span>Sắp tới</span>'
      + '<span class="jp-parts__legend-pill jp-parts__legend-pill--chest"><span class="jp-parts__legend-ic">' + svgTrophyCup('jp-parts__legend-svg') + '</span>Rương / Cúp tuần</span>'
      + '</div>'
      + '<button type="button" class="jp-btn jp-parts__all-btn" data-parts-all aria-expanded="false">'
      + svgListIcon() + '<span>Xem toàn bộ danh sách</span></button>'
      + '</div>'
      + '</header>';

    var maxVisibleWeek = Math.min(totalWeeks, currentWeekNum + UPCOMING_WEEKS_SHOWN);
    var remainingWeeks = Math.max(0, totalWeeks - maxVisibleWeek);
    var flatSteps = [];
    for (var wIdx = 0; wIdx < maxVisibleWeek; wIdx++) {
      var wObj = weeks[wIdx];
      if (!wObj) continue;
      for (var pIdx = 0; pIdx < wObj.parts.length; pIdx++) {
        flatSteps.push({
          kind: 'part',
          part: wObj.parts[pIdx],
          weekObj: wObj,
          stepInWeek: pIdx,
          isFirstInWeek: pIdx === 0
        });
      }
      flatSteps.push({
        kind: 'milestone',
        milestone: wObj.milestone,
        weekObj: wObj,
        stepInWeek: wObj.parts.length,
        isFirstInWeek: false
      });
    }

    var mapItemsHtml = '';
    for (var s = 0; s < flatSteps.length; s++) {
      var cur = flatSteps[s];
      var pos = WAVE_POS[s % WAVE_POS.length];
      var hasNext = (s + 1 < flatSteps.length) || (remainingWeeks > 0);
      var nextPos = (s + 1 < flatSteps.length) ? WAVE_POS[(s + 1) % WAVE_POS.length] : 'center';
      var nextEntry = (s + 1 < flatSteps.length) ? flatSteps[s + 1] : null;
      var nextStatus = nextEntry && nextEntry.kind === 'part' ? nextEntry.part.status : '';
      if (cur.kind === 'part') {
        var pKey = 'part-' + cur.part.index;
        var isSel = activeKey === pKey;
        mapItemsHtml += partNodeHtml(cur.part, t[1], sceneKey, pos, nextPos, hasNext, nextStatus, cur.stepInWeek, cur.weekObj, isSel, isSel && pillsExpanded, cur.isFirstInWeek, s);
      } else {
        var mKey = cur.milestone.key;
        var mSel = activeKey === mKey;
        mapItemsHtml += milestoneNodeHtml(cur.milestone, pos, nextPos, hasNext, cur.stepInWeek, cur.weekObj, mSel);
      }
    }
    if (remainingWeeks > 0) {
      mapItemsHtml += horizonMilestoneHtml(remainingWeeks);
    }

    var stageHtml = '<div class="jp-parts__stage jp-parts__stage--' + sceneKey + '" data-tab-scene="' + sceneKey + '">'
      + sceneBackdropHtml(sceneKey)
      + '<ol class="jp-parts__list jp-parts__map">' + mapItemsHtml + '</ol>'
      + '</div>';

    var completedWeek = weeks.some(function (week, idx) {
      return week.allDone && idx < weeks.length - 1 && (week.isCurrent || (weeks[idx + 1] && weeks[idx + 1].isCurrent));
    });
    var aheadHtml = completedWeek
      ? '<div class="jp-card" role="note"><strong>Học trước tuần sau</strong>'
        + '<p>Bạn đã học xong các mục của tuần này. Vào Học thêm để bắt đầu các mục tiếp theo.</p>'
        + '<button type="button" class="jp-btn" data-parts-extra="' + esc(TABS[sceneKey][0]) + '">Học thêm ' + esc(t[1]) + '</button>'
        + '<span class="jp-parts__err" role="status" data-parts-extra-error></span></div>'
      : '';
    return '<div class="jp-parts__container" data-tab-scene="' + esc(sceneKey) + '">' + bannerHtml + aheadHtml + stageHtml + '</div>';
  }

  /** Owner 24/11: true when the node is hidden behind the fixed site header or below the screen. */
  function nodeOffScreen(node) {
    if (!node || typeof node.getBoundingClientRect !== 'function') return true;
    var r = node.getBoundingClientRect();
    var header = root.document.getElementById('jlabBaseHeader');
    var top = header && typeof header.getBoundingClientRect === 'function' ? Math.max(0, header.getBoundingClientRect().bottom) : 0;
    return r.bottom < top || r.top > (Number(root.innerHeight) || 760);
  }

  /* Round 6 Bổ sung 1: Keep popover strictly inside viewport & below sticky banner so it is never covered */
  function adjustPopoverInViewport(partsEl) {
    if (!partsEl || !root || !root.document) return;
    var pop = partsEl.querySelector('[data-parts-popover]');
    if (!pop || typeof pop.getBoundingClientRect !== 'function') return;
    var vw = Number(root.innerWidth) || 1280;
    var vh = Number(root.innerHeight) || 760;
    if (vw <= 640) return;

    pop.style.setProperty('--pop-shift-y', '0px');
    var arrow = pop.querySelector('.jp-parts__popover-arrow');
    if (arrow) arrow.style.setProperty('--arrow-shift-y', '0px');

    var banner = partsEl.querySelector('.jp-parts__banner');
    var bannerBottom = 64;
    if (banner && typeof banner.getBoundingClientRect === 'function') {
      var bRect = banner.getBoundingClientRect();
      if (bRect.bottom > 0 && bRect.top < vh * 0.45) {
        bannerBottom = Math.max(bannerBottom, bRect.bottom);
      }
    }
    // owner 24/11 (reported many times): the site's fixed header counts too, so the popover never slides under it
    var header = root.document.getElementById('jlabBaseHeader');
    var headerBottom = 0;
    if (header && typeof header.getBoundingClientRect === 'function') {
      var hRect = header.getBoundingClientRect();
      if (hRect.bottom > 0 && hRect.top <= 1) headerBottom = hRect.bottom;
    }
    var minTop = Math.max(Math.min(vh * 0.42, bannerBottom + 10), headerBottom + 10);
    var maxBottom = vh - 14;
    pop.style.maxHeight = Math.max(200, Math.floor(maxBottom - minTop)) + 'px';
    pop.style.overflowY = 'auto';
    var rect = pop.getBoundingClientRect();
    var shiftY = 0;

    if (rect.top < minTop) {
      shiftY = Math.round(minTop - rect.top);
    } else if (rect.bottom > maxBottom) {
      shiftY = -Math.round(rect.bottom - maxBottom);
      if (rect.top + shiftY < minTop) {
        shiftY = Math.round(minTop - rect.top);
      }
    }

    if (shiftY !== 0) {
      pop.style.setProperty('--pop-shift-y', shiftY + 'px');
      if (arrow) {
        var maxArrowShift = Math.max(0, Math.floor((rect.height || 220) / 2) - 22);
        var arrowShift = Math.max(-maxArrowShift, Math.min(maxArrowShift, -shiftY));
        arrow.style.setProperty('--arrow-shift-y', arrowShift + 'px');
      }
    }
  }

  function triggerWeeklyCheck(wk, client) {
    var apiClient = client || (root && root.JpathApi);
    if (!apiClient || !wk) return;
    var tab = wk.getAttribute('data-parts-weekly');
    wk.disabled = true;
    Promise.resolve(apiClient.send('POST', '/api/learning/jlpt/checks', {
      kind: 'FREE_QUIZ',
      tab: tab,
      source: 'WEEK',
      count: 10,
      direction: tab === 'GRAMMAR' ? null : 'MIXED',
      timerOn: false
    })).then(function (res) {
      var token = res && (res.token || res.checkToken);
      if (token) root.location.assign('/learning/jlpt/check/' + encodeURIComponent(token) + '?return=%2Flearning');
      else wk.disabled = false;
    }, function (e) {
      wk.disabled = false;
      // one message per button placed at the end of the weekly row so it wraps onto its own line
      var row = (typeof wk.closest === 'function' && (wk.closest('.jp-today__weekly') || wk.closest('.jp-parts__weekly') || wk.closest('.jp-parts__pop-actions')))
        || wk.parentNode;
      var box = row && typeof row.querySelector === 'function' ? row.querySelector('.jp-parts__err[data-for="' + tab + '"]') : null;
      if (!box && row) {
        if (typeof row.insertAdjacentHTML === 'function') {
          row.insertAdjacentHTML('beforeend', '<span class="jp-parts__err" role="status" data-for="' + esc(tab) + '"></span>');
          box = typeof row.querySelector === 'function' ? row.querySelector('.jp-parts__err[data-for="' + tab + '"]') : null;
        } else if (row.ownerDocument && typeof row.ownerDocument.createElement === 'function' && typeof row.appendChild === 'function') {
          box = row.ownerDocument.createElement('span');
          box.className = 'jp-parts__err';
          box.setAttribute('role', 'status');
          box.setAttribute('data-for', tab);
          row.appendChild(box);
        }
      }
      var notYet = e && (e.status === 409 || e.code === 'JPATH_CONTENT_NOT_LEARNABLE');
      var name = tab === 'KANJI' ? 'Kanji' : tab === 'GRAMMAR' ? 'ngữ pháp' : 'từ vựng';
      if (box) {
        box.textContent = notYet ? 'Tuần này bạn chưa học mục ' + name + ' nào. Học trước rồi kiểm tra nhé.'
          : ((e && (e.code || e.status) && e.message) || 'Chưa mở được bài kiểm tra. Thử lại sau.');
      }
    });
  }

  function bindGlobalEvents() {
    if (!root || !root.document || root.document.__jpathPartsGlobalBound) return;
    root.document.__jpathPartsGlobalBound = true;
    root.document.addEventListener('click', function (ev) {
      var t = ev.target;
      if (!t || typeof t.closest !== 'function') return;
      var wk = t.closest('[data-parts-weekly]');
      if (wk && !wk.closest('[data-slot="parts"]')) {
        triggerWeeklyCheck(wk, root.JpathApi);
        return;
      }
      var activePartsEl = root.document.querySelector('[data-slot="parts"]');
      if (activePartsEl && activePartsEl.__selectedKey && activePartsEl.__renderCurrent) {
        if (!t.closest('[data-parts-popover]') && !t.closest('[data-parts-node]')) {
          activePartsEl.__selectedKey = '';
          activePartsEl.__pillsExpanded = false;
          activePartsEl.__renderCurrent();
        }
      }
    });
    root.document.addEventListener('keydown', function (ev) {
      if (ev.key !== 'Escape') return;
      var activePartsEl = root.document.querySelector('[data-slot="parts"]');
      if (activePartsEl && activePartsEl.__selectedKey && activePartsEl.__renderCurrent) {
        activePartsEl.__selectedKey = '';
        activePartsEl.__pillsExpanded = false;
        activePartsEl.__renderCurrent();
      }
    });
    if (typeof root.addEventListener === 'function') {
      var onScrollOrResize = function () {
        var activePartsEl = root.document && root.document.querySelector('[data-slot="parts"]');
        if (activePartsEl && activePartsEl.__selectedKey) adjustPopoverInViewport(activePartsEl);
      };
      // Owner 18/11: the popover stays beside its node while scrolling. Owner 24/11: it never slides under the site
      // header — while scrolling it is fitted again, and once its node is hidden behind the header or below the
      // screen the popover closes.
      root.addEventListener('resize', onScrollOrResize, { passive: true });
      var scrollFrame = 0;
      root.addEventListener('scroll', function () {
        if (scrollFrame || typeof root.requestAnimationFrame !== 'function') return;
        scrollFrame = root.requestAnimationFrame(function () {
          scrollFrame = 0;
          var el = root.document && root.document.querySelector('[data-slot="parts"]');
          if (!el || !el.__selectedKey) return;
          if (nodeOffScreen(el.querySelector('[data-parts-node="' + String(el.__selectedKey).replace(/"/g, '') + '"]'))) {
            el.__selectedKey = '';
            el.__pillsExpanded = false;
            if (el.__renderCurrent) el.__renderCurrent();
            return;
          }
          adjustPopoverInViewport(el);
        });
      }, { passive: true });
    }
  }

  function prefersReducedMotion() {
    return !!(root && root.matchMedia && root.matchMedia('(prefers-reduced-motion: reduce)').matches);
  }

  function scrollToTarget(el) {
    if (!el || typeof el.scrollIntoView !== 'function') return;
    var behavior = prefersReducedMotion() ? 'auto' : 'smooth';
    try { el.scrollIntoView({ block: 'center', behavior: behavior }); } catch (e) { el.scrollIntoView(); }
  }

  var LIST_QUERY_KEYS = ['topic', 'state', 'q', 'radical', 'sort', 'view', 'unit', 'open'];

  function hasListQuery() {
    var search = root && root.location && typeof root.location.search === 'string' ? root.location.search : '';
    if (!search || typeof URLSearchParams !== 'function') return false;
    var params = new URLSearchParams(search);
    return LIST_QUERY_KEYS.some(function (k) {
      var v = params.get(k);
      return v !== null && v !== '';
    });
  }

  function mount(partsEl, listEl, tabKey, opts) {
    if (!partsEl) return null;
    bindGlobalEvents();
    var key = String(tabKey || '').toLowerCase();
    var o = opts || {};
    var client = o.api || root.JpathApi;
    lockedByKana = !!o.afterKana;
    if (!TABS[key] || o.ownLevel === false || !client) {
      partsEl.innerHTML = '';
      partsEl.hidden = true;
      if (listEl) listEl.hidden = false;
      return null;
    }
    if (partsEl.__allOpen === undefined && hasListQuery()) partsEl.__allOpen = true;
    partsEl.hidden = false;
    if (listEl) listEl.hidden = !partsEl.__allOpen;
    var mine = (partsEl.__seq || 0) + 1;
    partsEl.__seq = mine;
    partsEl.__tab = key;
    partsEl.__week = o.week;
    partsEl.__estimate = o.estimate;

    function renderCurrent() {
      partsEl.innerHTML = partsHtml(partsEl.__data, partsEl.__tab, 0, {
        week: partsEl.__week,
        estimate: partsEl.__estimate,
        focusedWeek: partsEl.__focusedWeek,
        selectedKey: partsEl.__selectedKey,
        pillsExpanded: partsEl.__pillsExpanded
      });
      var allBtn = partsEl.querySelector('[data-parts-all]');
      if (allBtn && partsEl.__allOpen) {
        allBtn.setAttribute('aria-expanded', 'true');
        allBtn.innerHTML = svgListIcon() + '<span>Ẩn danh sách đầy đủ</span>';
      }
      if (partsEl.__selectedKey) {
        adjustPopoverInViewport(partsEl);
        if (root && typeof root.requestAnimationFrame === 'function') {
          root.requestAnimationFrame(function () { adjustPopoverInViewport(partsEl); });
        }
      }
    }
    partsEl.__renderCurrent = renderCurrent;

    if (!partsEl.__bound) {
      partsEl.__bound = true;
      partsEl.addEventListener('click', function (ev) {
        var t = ev.target;
        if (!t || typeof t.closest !== 'function') return;

        // pills are drawn after the page's bind ran: wire the pressed one now and let its own handler open the dialog
        var writeEl = t.closest('[data-jpath-writing]');
        if (writeEl && writeEl.getAttribute('data-jpath-writing-bound') !== '1' && root.JpathWriting && root.JpathWriting.bind) {
          root.JpathWriting.bind(writeEl);
          writeEl.click();
          return;
        }

        var popClose = t.closest('[data-parts-pop-close]');
        if (popClose) {
          partsEl.__selectedKey = '';
          partsEl.__pillsExpanded = false;
          renderCurrent();
          return;
        }

        var morePills = t.closest('[data-parts-more-pills]');
        if (morePills) {
          partsEl.__pillsExpanded = true;
          renderCurrent();
          return;
        }

        var nodeBtn = t.closest('[data-parts-node]');
        if (nodeBtn) {
          var nextKey = nodeBtn.getAttribute('data-parts-node');
          if (partsEl.__selectedKey === nextKey) {
            partsEl.__selectedKey = '';
            partsEl.__pillsExpanded = false;
          } else {
            partsEl.__selectedKey = nextKey;
            partsEl.__pillsExpanded = false;
          }
          renderCurrent();
          return;
        }

        var weekNav = t.closest('[data-parts-week-nav]');
        if (weekNav && !weekNav.disabled) {
          var dir = weekNav.getAttribute('data-parts-week-nav');
          var weeks = buildWeeks(list(partsEl.__data && partsEl.__data.parts));
          var curWeekLimit = 1;
          for (var i = 0; i < weeks.length; i++) {
            if (weeks[i].isCurrent) { curWeekLimit = weeks[i].weekNum; break; }
          }
          var curW = partsEl.__focusedWeek || curWeekLimit;
          var targetW = dir === 'prev' ? Math.max(1, curW - 1) : Math.min(curWeekLimit, curW + 1);
          partsEl.__focusedWeek = targetW;
          partsEl.__selectedKey = '';
          partsEl.__pillsExpanded = false;
          renderCurrent();
          var anchor = partsEl.querySelector('[data-parts-week-anchor="' + targetW + '"]');
          if (anchor) scrollToTarget(anchor);
          return;
        }

        var wk = t.closest('[data-parts-weekly]');
        if (wk) {
          triggerWeeklyCheck(wk, client);
          return;
        }

        var extra = t.closest('[data-parts-extra]');
        if (extra) {
          var tab = extra.getAttribute('data-parts-extra');
          var errorBox = extra.parentNode.querySelector('[data-parts-extra-error]');
          extra.disabled = true;
          Promise.resolve(client.send('POST', '/api/learning/jlpt/sessions', { tab: tab, mode: 'EXTRA', count: 5 }))
            .then(function (view) {
              if (!view || view.sessionId == null) throw new Error('Chưa mở được lượt học thêm.');
              root.location.assign('/learning/jlpt/study?session=' + encodeURIComponent(view.sessionId));
            }).catch(function (error) {
              extra.disabled = false;
              if (errorBox) errorBox.textContent = error && (error.code || error.status) && error.message || 'Chưa mở được lượt học thêm. Thử lại sau.';
              if (error && error.code === 'JPATH_EXTRA_CHECK_REQUIRED') {
                Promise.resolve(client.send('POST', '/api/learning/jlpt/checks', {
                  kind: 'FREE_QUIZ', tab: tab, source: 'WEEK', count: 10,
                  direction: tab === 'GRAMMAR' ? null : 'MIXED', timerOn: false
                })).then(function (res) {
                  var token = res && (res.token || res.checkToken);
                  if (token) root.location.assign('/learning/jlpt/check/' + encodeURIComponent(token)
                    + '?return=' + encodeURIComponent('/learning?tab=' + partsEl.__tab));
                }).catch(function (checkError) {
                  if (errorBox) errorBox.textContent = checkError && checkError.message || 'Chưa mở được bài kiểm tra tuần.';
                });
              }
            });
          return;
        }

        var fcBtn = t.closest('[data-parts-fc]');
        if (fcBtn) {
          fcBtn.disabled = true;
          fcBtn.setAttribute('aria-busy', 'true');
          fcBtn.textContent = 'Đang mở';
          Promise.resolve(client.send('POST', '/api/learning/jlpt/flashcards/open?tab=' + encodeURIComponent(TABS[partsEl.__tab][0])
            + '&part=' + encodeURIComponent(fcBtn.getAttribute('data-parts-fc')))).then(function (set) {
            if (set && set.url) {
              root.location.assign(set.url + '&back=' + encodeURIComponent(root.location.pathname + root.location.search));
              return;
            }
            throw new Error('Chưa mở được bộ thẻ, thử lại sau ít phút');
          }).catch(function (error) {
            fcBtn.disabled = false;
            fcBtn.removeAttribute('aria-busy');
            fcBtn.textContent = 'Thử lại';
            var pop = fcBtn.closest('[data-parts-popover]');
            var box = pop && pop.querySelector('[data-parts-fc-error]');
            if (box) box.textContent = (error && (error.code || error.status) && error.message) || 'Chưa mở được bộ thẻ, thử lại sau ít phút';
          });
          return;
        }

        var re = t.closest('[data-parts-relearn]');
        if (re) {
          var d0 = partsEl.__data || {};
          re.disabled = true;
          Promise.resolve(client.send('POST', '/api/learning/jlpt/sessions', {
            tab: TABS[partsEl.__tab][0],
            mode: 'BROWSE',
            level: d0.level,
            startItemCode: re.getAttribute('data-parts-relearn'),
            count: Number(re.getAttribute('data-parts-count')) || 10
          })).then(function (view) {
            if (view && view.sessionId != null) root.location.assign('/learning/jlpt/study?session=' + encodeURIComponent(view.sessionId));
            else re.disabled = false;
          }, function (error) {
            re.disabled = false;
            var popover = re.closest('[data-parts-popover]');
            var errorBox = popover && popover.querySelector('[data-parts-relearn-error]');
            if (errorBox) errorBox.textContent = error && (error.code || error.status) && error.message || 'Chưa mở được phần học này. Thử lại sau.';
          });
          return;
        }

        var all = t.closest('[data-parts-all]');
        if (all) {
          partsEl.__allOpen = !partsEl.__allOpen;
          if (listEl) listEl.hidden = !partsEl.__allOpen;
          all.setAttribute('aria-expanded', String(!!partsEl.__allOpen));
          all.innerHTML = svgListIcon() + '<span>' + (partsEl.__allOpen ? 'Ẩn danh sách đầy đủ' : 'Xem toàn bộ danh sách') + '</span>';
          if (partsEl.__allOpen && listEl && listEl.scrollIntoView) {
            listEl.scrollIntoView({ block: 'start', behavior: prefersReducedMotion() ? 'auto' : 'smooth' });
          }
        }
      });
    }

    partsEl.innerHTML = '<p class="jp-skel jp-skel--list" role="status" aria-busy="true"><span class="jp-skel__sr">Đang tải phần học…</span><i class="jp-skel__b jp-skel__b--title"></i><i class="jp-skel__b jp-skel__b--line"></i><i class="jp-skel__b jp-skel__b--line"></i><i class="jp-skel__b jp-skel__b--short"></i></p>';
    return Promise.resolve(client.get('/api/learning/jlpt/parts?tab=' + TABS[key][0])).then(function (data) {
      if (partsEl.__seq !== mine) return;
      partsEl.__data = data;
      var weeks = buildWeeks(list(data && data.parts));
      var curW = 1;
      for (var i = 0; i < weeks.length; i++) {
        if (weeks[i].isCurrent) { curW = weeks[i].weekNum; break; }
      }
      partsEl.__focusedWeek = curW;
      partsEl.__selectedKey = '';
      partsEl.__pillsExpanded = false;
      renderCurrent();
      if (root && typeof root.requestAnimationFrame === 'function') {
        root.requestAnimationFrame(function () {
          if (partsEl.__allOpen && listEl) { scrollToTarget(listEl); return; }
          var todayNode = partsEl.querySelector('[data-parts-today="true"]')
            || partsEl.querySelector('[data-parts-week-anchor="' + curW + '"]');
          if (todayNode) scrollToTarget(todayNode);
        });
      }
    }, function () {
      if (partsEl.__seq !== mine) return;
      partsEl.innerHTML = '';
      partsEl.hidden = true;
      if (listEl) listEl.hidden = false;
    });
  }

  return {
    printPartHtml: printPartHtml,
    wordPillHtml: wordPillHtml,
    partPopoverHtml: partPopoverHtml,
    partsHtml: partsHtml,
    weeklyHtml: weeklyHtml,
    savedNoticeHtml: savedNoticeHtml,
    adjustPopoverInViewport: adjustPopoverInViewport,
    hasListQuery: hasListQuery,
    triggerWeeklyCheck: triggerWeeklyCheck,
    mount: mount
  };
});
