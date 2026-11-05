/* [JLAS-W0108] jpath-parts.js — winding study-part map of the Từ vựng / Kanji / Ngữ pháp tabs (owner 05/11, round 4).
 *
 * Round 4 updates:
 * 1. Continuous winding road of circular coins & chests (no text signposts):
 *    - Past weeks: compact purple-coin nodes (clickable, opens popover).
 *    - Current week: full-size nodes + today's bouncing lavender-purple coin + Kitsu ("Bấm vào đây để học").
 *    - Upcoming weeks: renders up to 2 upcoming weeks as dimmed (45% opacity) lavender-grey locked coins & chests
 *      (not clickable, cursor: not-allowed, hover tooltip "Mở vào tuần dd/mm–dd/mm").
 *    - Beyond 2 upcoming weeks: a dimmed horizon milestone ("… còn N tuần nữa").
 * 2. Popover does not cover today's node:
 *    - Closed by default on load; opens to the left or right of the clicked node on wide screens (bottom sheet on phones).
 * 3 & 4. Purple-centered palette & smooth dark-mode transitions via CSS variables (no hardcoded SVG hex colors).
 * 5. Sticky unified Tab Banner at the top of the map:
 *    - Left: large tab icon (book / brush / scroll) in a soft purple circle + "Từ vựng N5" + "800 từ · xong dự kiến ...".
 *    - Center: circular SVG progress ring (pct%, learned/total).
 *    - Right: pill week selector "‹ Tuần 1/16 ›" (scrolls to past/current weeks) + icon-pill legend + outlined list button.
 *
 * window.JpathParts = { partsHtml(data, tabKey, page, opts), weeklyHtml(week), savedNoticeHtml(msg), mount(partsEl, listEl, tabKey, opts) }
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathParts = api;
  if (root && root.location && /[?&]saved=1\b/.test(String(root.location.search || '')) && root.document) {
    var notice = function () {
      var msg = 'Đã lưu tiến trình hôm nay! Kitsu chúc mừng bạn hoàn thành bài học. Xem lại ở "Xem ngày học hôm nay".';
      if (root.JlasToast && typeof root.JlasToast.show === 'function') root.JlasToast.show(msg);
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
  var WAVE_X = { left: 10, center: 210, right: 410 };

  var SIDE_KITSUS = [
    { src: '/images/mascots/kitsu/kitsu-thinking-30.webp', alt: 'Kitsu suy nghĩ', say: 'Nhớ ôn kỹ nhé!' },
    { src: '/images/mascots/kitsu/kitsu-cheer-30.webp', alt: 'Kitsu cổ vũ', say: 'Bạn làm tốt lắm!' },
    { src: '/images/mascots/kitsu/kitsu-resting-30.webp', alt: 'Kitsu nghỉ ngơi', say: 'Đều đặn mỗi ngày!' },
    { src: '/images/mascots/kitsu/kitsu-preparing.webp', alt: 'Kitsu chăm chỉ', say: 'Sắp tới đích rồi!' }
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

  function savedNoticeHtml(msg) {
    var pieces = [];
    for (var i = 0; i < 16; i++) {
      pieces.push('<span class="jp-saved-note__confetti jp-saved-note__confetti--' + (i % 4)
        + '" style="--cf-i:' + i + '" aria-hidden="true"></span>');
    }
    return '<div class="jp-saved-note__confetti-wrap" aria-hidden="true">' + pieces.join('') + '</div>'
      + '<img class="jp-saved-note__kitsu" src="/images/mascots/kitsu/kitsu-celebrate-30.webp" width="56" height="56" alt="Kitsu ăn mừng"/>'
      + '<div class="jp-saved-note__body">'
      + '<strong class="jp-saved-note__title">Hoàn thành xuất sắc!</strong>'
      + '<span class="jp-saved-note__text">' + esc(msg || 'Đã lưu tiến trình hôm nay. Xem lại ở "Xem ngày học hôm nay".') + '</span>'
      + '</div>';
  }

  function svgCheck() {
    return '<svg class="jp-parts__icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">'
      + '<path d="M5 12.5l4.5 4.5L19 7.5" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>'
      + '</svg>';
  }

  function svgStar() {
    return '<svg class="jp-parts__icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">'
      + '<path d="M12 3.2l2.7 5.45 6.05.88-4.38 4.26 1.03 6.01L12 17l-5.4 2.8 1.03-6.01-4.38-4.26 6.05-.88L12 3.2z"'
      + ' fill="currentColor" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round"/>'
      + '</svg>';
  }

  function svgLock() {
    return '<svg class="jp-parts__icon" viewBox="0 0 24 24" fill="none" aria-hidden="true">'
      + '<rect x="5" y="11" width="14" height="10" rx="2.5" stroke="currentColor" stroke-width="2.2"/>'
      + '<path d="M8 11V8a4 4 0 018 0v3" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"/>'
      + '<circle cx="12" cy="16" r="1.5" fill="currentColor"/>'
      + '</svg>';
  }

  /* Chest SVGs use CSS variables (--jp-chest-*) so they adapt to the purple palette and dark mode */
  function svgChestClosed() {
    return '<svg class="jp-parts__icon jp-parts__icon--chest" viewBox="0 0 36 36" fill="none" aria-hidden="true">'
      + '<rect x="5" y="15" width="26" height="15" rx="3.5" fill="var(--jp-chest-body)" stroke="var(--jp-chest-stroke)" stroke-width="1.8"/>'
      + '<path d="M5 16C5 11.5 9 9 18 9s13 2.5 13 7H5z" fill="var(--jp-chest-lid)" stroke="var(--jp-chest-stroke)" stroke-width="1.8"/>'
      + '<rect x="5" y="15" width="26" height="3.2" fill="var(--jp-chest-band)"/>'
      + '<rect x="15" y="13.5" width="6" height="7" rx="1.5" fill="var(--jp-chest-lock)" stroke="var(--jp-chest-stroke)" stroke-width="1.4"/>'
      + '<circle cx="18" cy="17" r="1.1" fill="var(--jp-chest-stroke)"/>'
      + '</svg>';
  }

  function svgChestOpen() {
    return '<svg class="jp-parts__icon jp-parts__icon--chest" viewBox="0 0 36 36" fill="none" aria-hidden="true">'
      + '<circle cx="18" cy="14" r="10" fill="var(--jp-chest-glow)" fill-opacity="0.45"/>'
      + '<path d="M6 12C6 7.8 10 5.5 18 5.5S30 7.8 30 12H6z" fill="var(--jp-chest-lid)" stroke="var(--jp-chest-stroke)" stroke-width="1.6"/>'
      + '<ellipse cx="14" cy="16" rx="4" ry="2.6" fill="var(--jp-chest-band)"/>'
      + '<ellipse cx="21" cy="15.5" rx="4.5" ry="2.8" fill="var(--jp-chest-lid)"/>'
      + '<polygon points="18,10 21,14 18,18 15,14" fill="var(--jp-primary)" stroke="var(--jp-surface)" stroke-width="1"/>'
      + '<rect x="5" y="16" width="26" height="14" rx="3.5" fill="var(--jp-chest-body)" stroke="var(--jp-chest-stroke)" stroke-width="1.8"/>'
      + '<rect x="5" y="16" width="26" height="3" fill="var(--jp-chest-band)"/>'
      + '<rect x="15" y="15" width="6" height="6" rx="1.4" fill="var(--jp-chest-lock)" stroke="var(--jp-chest-stroke)" stroke-width="1.3"/>'
      + '<path d="M10 6l1-2M26 6l-1-2M18 3V1" stroke="var(--jp-chest-band)" stroke-width="1.8" stroke-linecap="round"/>'
      + '</svg>';
  }

  /* Large tab icons for the unified top banner (book, brush, scroll) */
  function tabBannerIconSvg(sceneKey) {
    if (sceneKey === 'kanji') {
      // Calligraphy brush (bút lông)
      return '<svg class="jp-parts__banner-svg" viewBox="0 0 28 28" fill="none" aria-hidden="true">'
        + '<path d="M20.5 4.5l3 3-9.5 9.5-3.5.5.5-3.5 9.5-9.5z" fill="var(--jp-primary-soft)" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/>'
        + '<path d="M11 17.5c-2.2.5-3.8 2.2-4.5 4.5 2.5.2 4.5-1 5.2-3.2" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>'
        + '<path d="M6 23.5h14" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>'
        + '</svg>';
    }
    if (sceneKey === 'grammar') {
      // Scroll (cuộn giấy)
      return '<svg class="jp-parts__banner-svg" viewBox="0 0 28 28" fill="none" aria-hidden="true">'
        + '<rect x="6" y="5" width="16" height="18" rx="3" fill="var(--jp-primary-soft)" stroke="currentColor" stroke-width="2"/>'
        + '<path d="M9.5 10.5h9M9.5 14.5h9M9.5 18.5h6" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>'
        + '</svg>';
    }
    // Open book (sách)
    return '<svg class="jp-parts__banner-svg" viewBox="0 0 28 28" fill="none" aria-hidden="true">'
      + '<path d="M4.5 7.5c3-1 6.5-.5 9.5 1.5 3-2 6.5-2.5 9.5-1.5v13c-3-1-6.5-.5-9.5 1.5-3-2-6.5-2.5-9.5-1.5v-13z" fill="var(--jp-primary-soft)" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/>'
      + '<path d="M14 9v13" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>'
      + '</svg>';
  }

  function svgListIcon() {
    return '<svg class="jp-parts__btn-ic" viewBox="0 0 20 20" width="16" height="16" fill="none" aria-hidden="true">'
      + '<path d="M6 5h10M6 10h10M6 15h10M3 5h.01M3 10h.01M3 15h.01" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>'
      + '</svg>';
  }

  function smallCloudSvg(cls) {
    return '<svg class="jp-parts__cloud ' + cls + '" viewBox="0 0 96 38" fill="none" aria-hidden="true">'
      + '<path d="M18 32h60a11 11 0 000-22 16 16 0 00-30-6 13 13 0 00-30 28z" fill="currentColor"/>'
      + '</svg>';
  }

  function sceneBackdropHtml(sceneKey) {
    return '<div class="jp-parts__backdrop jp-parts__backdrop--' + esc(sceneKey) + '" aria-hidden="true">'
      + '<div class="jp-parts__sky-pattern"></div>'
      + '<div class="jp-parts__clouds">'
      + smallCloudSvg('jp-parts__cloud--1')
      + smallCloudSvg('jp-parts__cloud--2')
      + smallCloudSvg('jp-parts__cloud--3')
      + smallCloudSvg('jp-parts__cloud--4')
      + '</div>'
      + '<svg class="jp-parts__hills" viewBox="0 0 1200 140" preserveAspectRatio="none">'
      + '<path class="jp-parts__hill jp-parts__hill--far" d="M0 78 Q240 24 520 68 T1040 52 L1200 66 L1200 140 L0 140 Z"/>'
      + '<path class="jp-parts__hill jp-parts__hill--mid" d="M0 96 Q320 48 660 92 T1200 74 L1200 140 L0 140 Z"/>'
      + '<path class="jp-parts__hill jp-parts__hill--near" d="M0 114 Q260 82 580 112 T1200 98 L1200 140 L0 140 Z"/>'
      + '</svg>'
      + '</div>';
  }

  /* Decorative side SVGs use CSS variables (--jp-deco-*) so they adapt to dark mode */
  function sideDecoSvg(index) {
    var kind = index % 4;
    if (kind === 0) {
      return '<svg class="jp-parts__deco-svg" viewBox="0 0 24 24" fill="none" aria-hidden="true">'
        + '<circle cx="12" cy="7" r="3.6" fill="var(--jp-deco-petal-1)"/><circle cx="17" cy="10.5" r="3.6" fill="var(--jp-deco-petal-2)"/>'
        + '<circle cx="15" cy="16.5" r="3.6" fill="var(--jp-deco-petal-1)"/><circle cx="9" cy="16.5" r="3.6" fill="var(--jp-deco-petal-2)"/>'
        + '<circle cx="7" cy="10.5" r="3.6" fill="var(--jp-deco-petal-1)"/><circle cx="12" cy="12" r="2.2" fill="var(--jp-chest-band)"/>'
        + '</svg>';
    }
    if (kind === 1) {
      return '<svg class="jp-parts__deco-svg" viewBox="0 0 24 24" fill="none" aria-hidden="true">'
        + '<rect x="10.5" y="16.5" width="3" height="5" rx="1" fill="var(--jp-chest-stroke)"/>'
        + '<polygon points="12,3.5 5,12.5 19,12.5" fill="var(--jp-deco-leaf-1)"/>'
        + '<polygon points="12,8 4,17.5 20,17.5" fill="var(--jp-deco-leaf-2)"/>'
        + '</svg>';
    }
    if (kind === 2) {
      return '<svg class="jp-parts__deco-svg" viewBox="0 0 24 24" fill="none" aria-hidden="true">'
        + '<line x1="12" y1="2" x2="12" y2="5.5" stroke="var(--jp-chest-stroke)" stroke-width="1.6"/>'
        + '<rect x="8.5" y="5.5" width="7" height="2" rx="1" fill="var(--jp-chest-stroke)"/>'
        + '<ellipse cx="12" cy="12" rx="5" ry="5.5" fill="var(--jp-deco-lantern)" stroke="var(--jp-chest-stroke)" stroke-width="1.2"/>'
        + '<rect x="8.5" y="17" width="7" height="2" rx="1" fill="var(--jp-chest-stroke)"/>'
        + '</svg>';
    }
    return '<svg class="jp-parts__deco-svg" viewBox="0 0 24 24" fill="none" aria-hidden="true">'
      + '<path d="M3 9h18M5 9v11M19 9v11M2 6c6 1.8 14 1.8 20 0" stroke="var(--jp-deco-torii)" stroke-width="2" stroke-linecap="round"/>'
      + '</svg>';
  }

  function curveSvg(fromPos, toPos, isDone, isActiveRun, isCompact) {
    var x1 = WAVE_X[fromPos] || 210;
    var x2 = WAVE_X[toPos] || 210;
    var cls = 'jp-parts__curve' + (isDone ? ' jp-parts__curve--done' : '')
      + (isActiveRun ? ' jp-parts__curve--run' : '')
      + (isCompact ? ' jp-parts__curve--compact' : '');
    return '<svg class="' + cls + '" viewBox="0 0 420 62" preserveAspectRatio="none" aria-hidden="true">'
      + '<path class="jp-parts__curve-track" d="M' + x1 + ' 2 C' + x1 + ' 32, ' + x2 + ' 30, ' + x2 + ' 60" fill="none"'
      + ' stroke="currentColor" stroke-width="6" stroke-linecap="round"' + (isDone && !isActiveRun ? '' : ' stroke-dasharray="8 8"') + '/>'
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
    var r = 21;
    var c = 2 * Math.PI * r;
    var offset = (c - (pct / 100) * c).toFixed(1);
    return '<div class="jp-parts__banner-progress" aria-label="Tiến độ đã học ' + esc(learnedNum) + '/' + esc(totalNum) + '">'
      + '<span class="jp-parts__banner-ring" aria-hidden="true">'
      + '<svg viewBox="0 0 52 52" width="52" height="52">'
      + '<circle class="jp-parts__banner-ring-bg" cx="26" cy="26" r="' + r + '" fill="none" stroke-width="4.5"/>'
      + '<circle class="jp-parts__banner-ring-fg" cx="26" cy="26" r="' + r + '" fill="none" stroke-width="4.5"'
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

  function wordPillHtml(it) {
    var rd = it.reading && it.reading !== it.label ? it.reading : '';
    return '<li class="jp-parts__word-pill">'
      + (rd ? '<span class="jp-parts__pill-rd" lang="ja">' + esc(rd) + '</span>' : '<span class="jp-parts__pill-rd" aria-hidden="true">&nbsp;</span>')
      + '<span class="jp-parts__pill-ja" lang="ja">' + esc(it.label) + '</span>'
      + (it.meaning ? '<span class="jp-parts__pill-mean">' + esc(it.meaning) + '</span>' : '')
      + '</li>';
  }

  function pillsGridHtml(items, unit, expanded) {
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
      + first.map(wordPillHtml).join('')
      + moreBtn
      + '</ul>';
  }

  function weeklyButtonsHtml() {
    return '<div class="jp-parts__actions jp-parts__weekly-btns">' + ['VOCAB', 'KANJI', 'GRAMMAR'].map(function (t) {
      return '<button type="button" class="jp-btn jp-btn--sm" data-parts-weekly="' + t + '">'
        + { VOCAB: 'Từ vựng', KANJI: 'Kanji', GRAMMAR: 'Ngữ pháp' }[t] + '</button>';
    }).join('') + '</div>';
  }

  function kitsuBesideTodayHtml(isTodayDone, labelSide) {
    var src = isTodayDone ? '/images/mascots/kitsu/kitsu-celebrate-30.webp' : '/images/mascots/kitsu/kitsu-waving.svg';
    var say = isTodayDone ? 'Đã xong! Bấm xem lại' : 'Bấm vào đây để học';
    var sideCls = labelSide === 'left' ? 'jp-parts__mascot--right' : 'jp-parts__mascot--left';
    return '<span class="jp-parts__mascot jp-parts__mascot--today ' + sideCls + '">'
      + '<span class="jp-parts__mascot-speech">' + esc(say) + '</span>'
      + '<img class="jp-parts__mascot-img" src="' + src + '" width="52" height="52" loading="lazy" alt="Kitsu"/>'
      + '</span>';
  }

  function kitsuSideHtml(weekNum, pos) {
    var k = SIDE_KITSUS[(weekNum - 1) % SIDE_KITSUS.length];
    var side = pos === 'left' ? 'right' : 'left';
    return '<span class="jp-parts__mascot jp-parts__mascot--side jp-parts__mascot--' + side + '">'
      + '<img class="jp-parts__mascot-img" src="' + k.src + '" width="44" height="44" loading="lazy" alt="' + esc(k.alt) + '"/>'
      + '<span class="jp-parts__mascot-speech jp-parts__mascot-speech--sm">' + esc(k.say) + '</span>'
      + '</span>';
  }

  /* Round 4 Item 2: Place popover to the left or right of the node where space is available */
  function popoverSideForPos(pos) {
    return pos === 'right' ? 'left' : 'right';
  }

  function partPopoverHtml(p, unit, tabKey, pos, pillsExpanded) {
    var items = list(p.items);
    var n = items.length;
    var status = String(p.status || 'NEXT');
    var isToday = status === 'TODAY';
    var isTodayDone = status === 'TODAY_DONE';
    var isDone = status === 'DONE';
    var tone = (isDone || isTodayDone) ? 'done' : (isToday ? 'today' : 'next');
    var popSide = popoverSideForPos(pos);

    var mascotSrc = '/images/mascots/kitsu/kitsu-encouraging.webp';
    var statusLabel = 'Hôm nay';
    if (isTodayDone) {
      mascotSrc = '/images/mascots/kitsu/kitsu-celebrate-30.webp';
      statusLabel = 'Đã xong hôm nay';
    } else if (isDone) {
      mascotSrc = '/images/mascots/kitsu/kitsu-cheer-30.webp';
      statusLabel = 'Đã học';
    } else if (!isToday) {
      mascotSrc = '/images/mascots/kitsu/kitsu-thinking-30.webp';
      statusLabel = 'Sắp tới';
    }
    var dateStr = p.day ? dayLabel(p.day) : '';
    var doneItems = (isDone || isTodayDone) ? n : 0;

    var actions = '';
    if (isToday) {
      actions = '<button type="button" class="jp-btn jp-btn--primary jp-parts__pop-main-btn" data-action="start-mode"'
        + ' data-mode="SPLIT" data-tab-key="' + esc(tabKey) + '">Học phần này</button>';
    } else if (isTodayDone || isDone) {
      var first = items[0];
      actions = (first ? '<button type="button" class="jp-btn jp-btn--primary jp-parts__pop-main-btn" data-parts-relearn="' + esc(first.code)
        + '" data-parts-count="' + n + '">Học lại phần này</button>' : '')
        + (p.day ? '<a class="jp-btn jp-btn--sm" href="/learning/jlpt/day?d=' + esc(p.day) + '">Kết quả lần trước</a>' : '');
    } else {
      actions = '<span class="jp-parts__locked-note">Học xong phần hôm nay để mở khóa</span>';
    }

    return '<div class="jp-parts__popover jp-parts__popover--' + tone + ' jp-parts__popover--side-' + popSide + '"'
      + ' data-parts-popover="part-' + esc(p.index) + '" role="dialog" aria-label="Chi tiết Phần ' + esc(p.index) + '">'
      + '<span class="jp-parts__popover-arrow" aria-hidden="true"></span>'
      + '<div class="jp-parts__pop-head">'
      + '<img class="jp-parts__pop-kitsu" src="' + mascotSrc + '" width="56" height="56" alt="Kitsu"/>'
      + '<div class="jp-parts__pop-meta">'
      + '<div class="jp-parts__pop-badges">'
      + '<span class="jp-parts__pop-pill jp-parts__pop-pill--' + tone + '">' + esc(statusLabel) + '</span>'
      + (dateStr ? '<span class="jp-parts__pop-date">' + esc(dateStr) + '</span>' : '')
      + '</div>'
      + '<h3 class="jp-parts__pop-title">Phần ' + esc(p.index) + ' · ' + n + ' ' + esc(unit) + '</h3>'
      + '</div>'
      + '<button type="button" class="jp-parts__pop-close" data-parts-pop-close aria-label="Đóng bong bóng">×</button>'
      + '</div>'
      + '<div class="jp-parts__pop-body">'
      + pillsGridHtml(items, unit, pillsExpanded)
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
    var statusLabel = unlocked ? 'Rương đã mở ✓' : 'Mốc kiểm tra tuần';
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
      + '<img class="jp-parts__pop-kitsu" src="' + mascotSrc + '" width="56" height="56" alt="Kitsu"/>'
      + '<div class="jp-parts__pop-meta">'
      + '<div class="jp-parts__pop-badges">'
      + '<span class="jp-parts__pop-pill jp-parts__pop-pill--' + tone + '">' + esc(statusLabel) + '</span>'
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

  function partNodeHtml(p, unit, tabKey, pos, nextPos, hasNext, nextStatus, stepIdx, weekObj, isSelected, pillsExpanded, isFirstInWeek) {
    var items = list(p.items);
    var n = items.length;
    var status = String(p.status || 'NEXT');
    var isToday = status === 'TODAY';
    var isTodayDone = status === 'TODAY_DONE';
    var isDone = status === 'DONE';
    var isPastWeek = weekObj.state === 'past';
    var isUpcomingWeek = weekObj.state === 'upcoming';

    var when = isToday ? 'Hôm nay' : (isTodayDone ? 'Đã xong hôm nay'
      : (isDone ? (p.day ? dayLabel(p.day) : 'Đã học') : 'Sắp tới'));
    var stepMod = isTodayDone ? 'today-done' : status.toLowerCase();
    var icon = (isDone || isTodayDone) ? svgCheck() : (isToday ? svgStar() : svgLock());
    var bubble = isToday
      ? '<span class="jp-parts__bubble">Bắt đầu</span>'
      : (isTodayDone ? '<span class="jp-parts__bubble jp-parts__bubble--done">Đã xong</span>' : '');

    var labelSide = pos === 'right' ? 'left' : 'right';

    var mascotHtml = '';
    if (isToday || isTodayDone) {
      mascotHtml = kitsuBesideTodayHtml(isTodayDone, labelSide);
    } else if (!isUpcomingWeek && stepIdx === 2) {
      mascotHtml = kitsuSideHtml(weekObj.weekNum, pos);
    }

    var decoSide = pos === 'left' ? 'right' : 'left';
    var decoHtml = '<span class="jp-parts__deco jp-parts__deco--' + decoSide + '" aria-hidden="true">' + sideDecoSvg(weekObj.weekNum + stepIdx) + '</span>';

    var isLeadingToToday = (isDone || isTodayDone) && (nextStatus === 'TODAY' || nextStatus === 'TODAY_DONE' || isToday);
    var connector = hasNext ? curveSvg(pos, nextPos, isDone || isTodayDone, isLeadingToToday, isPastWeek) : '';
    var selectKey = 'part-' + p.index;
    var weekAnchorAttr = isFirstInWeek ? ' data-parts-week-anchor="' + weekObj.weekNum + '"' : '';

    // Round 4 Item 1: Upcoming weeks are dimmed locked coins, not clickable, with hover tooltip
    if (isUpcomingWeek) {
      var tipText = 'Mở vào tuần ' + weekObj.dateRange;
      return '<li class="jp-parts__step jp-parts__step--next jp-parts__step--upcoming-week jp-parts__step--pos-' + pos + '"'
        + weekAnchorAttr + ' style="--step-i:' + stepIdx + '">'
        + decoHtml
        + '<div class="jp-parts__node-row">'
        + '<div class="jp-parts__head jp-parts__head--locked" aria-disabled="true" title="' + esc(tipText) + '">'
        + '<span class="jp-parts__node-wrap">'
        + '<span class="jp-parts__orb jp-parts__orb--next" aria-hidden="true">'
        + '<span class="jp-parts__orb-shine"></span>'
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
      + decoHtml
      + '<div class="jp-parts__node-row">'
      + '<button type="button" class="jp-parts__head' + (isSelected ? ' is-selected' : '') + '"'
      + ' data-parts-node="' + esc(selectKey) + '" data-parts-select="' + esc(selectKey) + '"'
      + ' aria-expanded="' + (isSelected ? 'true' : 'false') + '">'
      + '<span class="jp-parts__node-wrap">'
      + bubble
      + '<span class="jp-parts__orb jp-parts__orb--' + stepMod + '" aria-hidden="true">'
      + '<span class="jp-parts__orb-shine"></span>'
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
    var stateCls = unlocked ? 'jp-parts__milestone--unlocked' : 'jp-parts__milestone--locked';
    var chestSvg = unlocked ? svgChestOpen() : svgChestClosed();
    var labelSide = pos === 'right' ? 'left' : 'right';
    var selectKey = 'milestone-' + m.weekNum;
    var connector = hasNext ? curveSvg(pos, nextPos, unlocked, false, isPastWeek) : '';

    if (isUpcomingWeek) {
      var tipText = 'Mở vào tuần ' + weekObj.dateRange;
      return '<li class="jp-parts__step jp-parts__step--milestone jp-parts__step--upcoming-week ' + stateCls + ' jp-parts__step--pos-' + pos + '"'
        + ' style="--step-i:' + stepIdx + '">'
        + '<div class="jp-parts__node-row">'
        + '<div class="jp-parts__head jp-parts__milestone-head jp-parts__head--locked" aria-disabled="true" title="' + esc(tipText) + '">'
        + '<span class="jp-parts__node-wrap">'
        + '<span class="jp-parts__orb jp-parts__orb--milestone" aria-hidden="true">'
        + '<span class="jp-parts__orb-shine"></span>'
        + chestSvg
        + '<span class="jp-parts__orb-lock">' + svgLock() + '</span>'
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
      + (isPastWeek ? ' jp-parts__step--past-week' : '')
      + ' jp-parts__step--pos-' + pos
      + (isSelected ? ' is-selected' : '') + '" style="--step-i:' + stepIdx + '">'
      + '<div class="jp-parts__node-row">'
      + '<button type="button" class="jp-parts__head jp-parts__milestone-head' + (isSelected ? ' is-selected' : '') + '"'
      + ' data-parts-node="' + esc(selectKey) + '" data-parts-select="' + esc(selectKey) + '"'
      + ' aria-expanded="' + (isSelected ? 'true' : 'false') + '">'
      + '<span class="jp-parts__node-wrap">'
      + '<span class="jp-parts__orb jp-parts__orb--milestone" aria-hidden="true">'
      + '<span class="jp-parts__orb-shine"></span>'
      + chestSvg
      + '</span>'
      + '</span>'
      + '<span class="jp-parts__label jp-parts__label--' + labelSide + '">'
      + '<span class="jp-parts__no">Rương tuần ' + esc(m.weekNum) + '</span>'
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
      + '<span class="jp-parts__horizon-icon" aria-hidden="true">' + sideDecoSvg(3) + '</span>'
      + '<span class="jp-parts__horizon-text">… còn ' + esc(remainingWeeks) + ' tuần nữa</span>'
      + '</div>'
      + '</li>';
  }

  /**
   * Kept for backwards compatibility (`window.JpathParts.weeklyHtml`).
   */
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
    // Round 4 Item 2: By default, no popover is open on initial load so today's node is never covered
    var activeKey = o.selectedKey != null ? String(o.selectedKey) : '';
    var pillsExpanded = !!o.pillsExpanded;

    var totalNum = Number(d.total) || 0;
    var learnedNum = Number(d.learned) || 0;
    var pct = totalNum > 0 ? Math.min(100, Math.round((learnedNum / totalNum) * 100)) : 0;
    var est = estimateText(d, o);

    // Round 4 Item 5: Unified sticky Tab Banner on top of the map
    var bannerHtml = '<header class="jp-parts__banner jp-parts__bar" aria-label="Thông tin bản đồ ' + esc(t[2]) + '">'
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
      + (focusedWeek <= 1 ? ' disabled' : '') + ' aria-label="Tuần trước">‹</button>'
      + '<span class="jp-parts__week-ind">Tuần <strong>' + focusedWeek + '/' + totalWeeks + '</strong></span>'
      + '<button type="button" class="jp-parts__week-btn" data-parts-week-nav="next"'
      + (focusedWeek >= currentWeekNum ? ' disabled' : '') + ' aria-label="Tuần tiếp theo">›</button>'
      + '</div>'
      + '</div>'
      + '</div>'
      + '<div class="jp-parts__banner-sub">'
      + '<div class="jp-parts__legend" aria-label="Chú giải trạng thái">'
      + '<span class="jp-parts__legend-pill jp-parts__legend-pill--done"><span class="jp-parts__legend-ic">✓</span>Đã học</span>'
      + '<span class="jp-parts__legend-pill jp-parts__legend-pill--today"><span class="jp-parts__legend-ic">★</span>Hôm nay</span>'
      + '<span class="jp-parts__legend-pill jp-parts__legend-pill--next"><span class="jp-parts__legend-ic">🔒</span>Sắp tới</span>'
      + '<span class="jp-parts__legend-pill jp-parts__legend-pill--chest"><span class="jp-parts__legend-ic">🎁</span>Rương tuần</span>'
      + '</div>'
      + '<button type="button" class="jp-btn jp-parts__all-btn" data-parts-all aria-expanded="false">'
      + svgListIcon() + '<span>Xem toàn bộ danh sách</span></button>'
      + '</div>'
      + '</header>';

    // Round 4 Item 1: Continuous winding road through past weeks, current week, and up to 2 upcoming weeks
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
        mapItemsHtml += partNodeHtml(cur.part, t[1], sceneKey, pos, nextPos, hasNext, nextStatus, cur.stepInWeek, cur.weekObj, isSel, isSel && pillsExpanded, cur.isFirstInWeek);
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

    return '<div class="jp-parts__container">' + bannerHtml + stageHtml + '</div>';
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
      wk.insertAdjacentHTML('afterend', '<span class="jp-parts__err">' + esc((e && e.message) || 'Chưa mở được bài kiểm tra.') + '</span>');
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
  }

  function prefersReducedMotion() {
    return !!(root && root.matchMedia && root.matchMedia('(prefers-reduced-motion: reduce)').matches);
  }

  function scrollToTarget(el) {
    if (!el || typeof el.scrollIntoView !== 'function') return;
    var behavior = prefersReducedMotion() ? 'auto' : 'smooth';
    try { el.scrollIntoView({ block: 'center', behavior: behavior }); } catch (e) { el.scrollIntoView(); }
  }

  function mount(partsEl, listEl, tabKey, opts) {
    if (!partsEl) return null;
    bindGlobalEvents();
    var key = String(tabKey || '').toLowerCase();
    var o = opts || {};
    var client = o.api || root.JpathApi;
    if (!TABS[key] || o.ownLevel === false || !client) {
      partsEl.innerHTML = '';
      partsEl.hidden = true;
      if (listEl) listEl.hidden = false;
      return null;
    }
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
    }
    partsEl.__renderCurrent = renderCurrent;

    if (!partsEl.__bound) {
      partsEl.__bound = true;
      partsEl.addEventListener('click', function (ev) {
        var t = ev.target;
        if (!t || typeof t.closest !== 'function') return;

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
          }, function () { re.disabled = false; });
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

    partsEl.innerHTML = '<p class="jp-status" role="status">Đang tải phần học…</p>';
    return Promise.resolve(client.get('/api/learning/jlpt/parts?tab=' + TABS[key][0])).then(function (data) {
      if (partsEl.__seq !== mine) return;
      partsEl.__data = data;
      var weeks = buildWeeks(list(data && data.parts));
      var curW = 1;
      for (var i = 0; i < weeks.length; i++) {
        if (weeks[i].isCurrent) { curW = weeks[i].weekNum; break; }
      }
      partsEl.__focusedWeek = curW;
      // Round 4 Item 2: Do not open popover automatically on load
      partsEl.__selectedKey = '';
      partsEl.__pillsExpanded = false;
      renderCurrent();
      if (root && typeof root.requestAnimationFrame === 'function') {
        root.requestAnimationFrame(function () {
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

  return { partsHtml: partsHtml, weeklyHtml: weeklyHtml, savedNoticeHtml: savedNoticeHtml, mount: mount };
});
