/* [JLAS-W0108] jpath-parts.js — winding study-part map of the Từ vựng / Kanji / Ngữ pháp tabs (owner 05/11, round 2).
 *
 * Renders parts from GET /api/learning/jlpt/parts?tab= as a scenic vertical winding path of 3D tactile coin nodes:
 * - DONE: green 3D coin with white check
 * - TODAY / TODAY_DONE: glowing amber-gold 3D coin with Kitsu mascot + speech bubble
 * - NEXT: silver-grey 3D coin with lock
 * - Weekly milestone: custom SVG treasure chest (closed when locked, open with gold/gems when unlocked)
 * - Popover bubble on click with compact 3-5 item preview, actions, and expandable "Xem các mục" list.
 *
 * window.JpathParts = { partsHtml(data, tabKey, page), weeklyHtml(week), savedNoticeHtml(), mount(partsEl, listEl, tabKey, opts) }
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathParts = api;
  // Owner 05/11 + Round 2: a finished day comes back to the path with confetti + celebrating Kitsu notice
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
  var PER_PAGE = 10;
  var WEEK_SPAN = 5;
  // Winding horizontal offsets: center -> left -> center -> right
  var WAVE_POS = ['center', 'left', 'center', 'right'];
  var WAVE_X = { center: 120, left: 68, right: 172 };

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

  function svgChestClosed() {
    return '<svg class="jp-parts__icon jp-parts__icon--chest" viewBox="0 0 36 36" fill="none" aria-hidden="true">'
      + '<rect x="5" y="15" width="26" height="15" rx="3" fill="#92400e" stroke="#78350f" stroke-width="1.8"/>'
      + '<path d="M5 16C5 11.5 9 9 18 9s13 2.5 13 7H5z" fill="#b45309" stroke="#78350f" stroke-width="1.8"/>'
      + '<rect x="5" y="15" width="26" height="3.2" fill="#fbbf24"/>'
      + '<rect x="15" y="13.5" width="6" height="7" rx="1.5" fill="#fde68a" stroke="#92400e" stroke-width="1.4"/>'
      + '<circle cx="18" cy="17" r="1.1" fill="#78350f"/>'
      + '</svg>';
  }

  function svgChestOpen() {
    return '<svg class="jp-parts__icon jp-parts__icon--chest" viewBox="0 0 36 36" fill="none" aria-hidden="true">'
      + '<circle cx="18" cy="14" r="11" fill="#fde68a" fill-opacity="0.45"/>'
      + '<path d="M6 12C6 7.8 10 5.5 18 5.5S30 7.8 30 12H6z" fill="#d97706" stroke="#78350f" stroke-width="1.6"/>'
      + '<ellipse cx="14" cy="16" rx="4" ry="2.6" fill="#fbbf24"/>'
      + '<ellipse cx="21" cy="15.5" rx="4.5" ry="2.8" fill="#f59e0b"/>'
      + '<polygon points="18,10 21,14 18,18 15,14" fill="#38bdf8" stroke="#fff" stroke-width="1"/>'
      + '<rect x="5" y="16" width="26" height="14" rx="3" fill="#b45309" stroke="#78350f" stroke-width="1.8"/>'
      + '<rect x="5" y="16" width="26" height="3" fill="#fbbf24"/>'
      + '<rect x="15" y="15" width="6" height="6" rx="1.4" fill="#fde68a" stroke="#92400e" stroke-width="1.3"/>'
      + '<path d="M10 6l1-2M26 6l-1-2M18 3V1" stroke="#fbbf24" stroke-width="1.8" stroke-linecap="round"/>'
      + '</svg>';
  }

  function sceneBackdropHtml(sceneKey) {
    if (sceneKey === 'kanji') {
      return '<div class="jp-parts__backdrop jp-parts__backdrop--kanji" aria-hidden="true">'
        + '<svg class="jp-parts__scene-svg" viewBox="0 0 800 500" preserveAspectRatio="none">'
        + '<circle cx="620" cy="115" r="56" fill="#fde68a" fill-opacity="0.78"/>'
        + '<circle cx="620" cy="115" r="78" fill="#fb923c" fill-opacity="0.22"/>'
        + '<path d="M70 95h95a18 18 0 000-18 24 24 0 00-44-10 20 20 0 00-51 28z" fill="#fff" fill-opacity="0.45"/>'
        + '<path d="M520 175h110a16 16 0 000-16 22 22 0 00-40-9 18 18 0 00-70 25z" fill="#fff" fill-opacity="0.38"/>'
        + '<polygon points="360,490 540,180 720,490" fill="#9a3412" fill-opacity="0.22"/>'
        + '<polygon points="502,245 540,180 578,245 562,248 548,232 532,248 516,236" fill="#fff" fill-opacity="0.75"/>'
        + '<polygon points="-40,500 180,250 410,500" fill="#7c2d12" fill-opacity="0.16"/>'
        + '<path d="M0 430 Q220 380 440 425 T800 410 L800 500 L0 500 Z" fill="#fed7aa" fill-opacity="0.45"/>'
        + '</svg></div>';
    }
    if (sceneKey === 'grammar') {
      return '<div class="jp-parts__backdrop jp-parts__backdrop--grammar" aria-hidden="true">'
        + '<svg class="jp-parts__scene-svg" viewBox="0 0 800 500" preserveAspectRatio="none">'
        + '<path d="M-20 140 Q260 40 520 180 T820 120" fill="none" stroke="#e9d5ff" stroke-opacity="0.22" stroke-width="58" stroke-linecap="round"/>'
        + '<path d="M645 62a42 42 0 1032 68 34 34 0 01-32-68z" fill="#fef08a" fill-opacity="0.88"/>'
        + '<circle cx="120" cy="78" r="2.5" fill="#fef9c3"/><circle cx="240" cy="52" r="3" fill="#fff"/>'
        + '<circle cx="420" cy="88" r="2.2" fill="#e9d5ff"/><circle cx="530" cy="48" r="2.8" fill="#fef08a"/>'
        + '<circle cx="160" cy="210" r="2.4" fill="#fff"/><circle cx="680" cy="220" r="2.6" fill="#fef08a"/>'
        + '<circle cx="95" cy="340" r="2.2" fill="#e9d5ff"/><circle cx="640" cy="350" r="3" fill="#fff"/>'
        + '<path d="M0 435 Q200 390 420 430 T800 415 L800 500 L0 500 Z" fill="#4c1d95" fill-opacity="0.35"/>'
        + '</svg></div>';
    }
    return '<div class="jp-parts__backdrop jp-parts__backdrop--vocab" aria-hidden="true">'
      + '<svg class="jp-parts__scene-svg" viewBox="0 0 800 500" preserveAspectRatio="none">'
      + '<circle cx="650" cy="88" r="44" fill="#fef08a" fill-opacity="0.82"/>'
      + '<path d="M80 92h105a18 18 0 000-18 26 26 0 00-48-10 22 22 0 00-57 28z" fill="#fff" fill-opacity="0.82"/>'
      + '<path d="M510 145h115a18 18 0 000-18 24 24 0 00-46-9 20 20 0 00-69 27z" fill="#fff" fill-opacity="0.72"/>'
      + '<path d="M60 265h90a15 15 0 000-15 20 20 0 00-38-8 17 17 0 00-52 23z" fill="#fff" fill-opacity="0.65"/>'
      + '<path d="M0 390 Q210 330 430 385 T800 365 L800 500 L0 500 Z" fill="#86efac" fill-opacity="0.42"/>'
      + '<path d="M0 435 Q260 395 510 435 T800 420 L800 500 L0 500 Z" fill="#4ade80" fill-opacity="0.35"/>'
      + '</svg></div>';
  }

  function sideDecoSvg(index) {
    var kind = index % 4;
    if (kind === 0) {
      return '<svg class="jp-parts__deco-svg" viewBox="0 0 32 32" fill="none" aria-hidden="true">'
        + '<circle cx="16" cy="9" r="5" fill="#fbcfe8"/><circle cx="23" cy="14" r="5" fill="#f9a8d4"/>'
        + '<circle cx="20" cy="22" r="5" fill="#fbcfe8"/><circle cx="12" cy="22" r="5" fill="#f9a8d4"/>'
        + '<circle cx="9" cy="14" r="5" fill="#fbcfe8"/><circle cx="16" cy="16" r="3" fill="#fde047"/>'
        + '</svg>';
    }
    if (kind === 1) {
      return '<svg class="jp-parts__deco-svg" viewBox="0 0 32 32" fill="none" aria-hidden="true">'
        + '<rect x="14" y="22" width="4" height="7" rx="1" fill="#92400e"/>'
        + '<polygon points="16,4 6,16 26,16" fill="#4ade80"/>'
        + '<polygon points="16,10 5,23 27,23" fill="#22c55e"/>'
        + '</svg>';
    }
    if (kind === 2) {
      return '<svg class="jp-parts__deco-svg" viewBox="0 0 32 32" fill="none" aria-hidden="true">'
        + '<line x1="16" y1="2" x2="16" y2="7" stroke="#92400e" stroke-width="2"/>'
        + '<rect x="11" y="7" width="10" height="2.5" rx="1" fill="#78350f"/>'
        + '<ellipse cx="16" cy="16" rx="7" ry="7.5" fill="#fb7185" stroke="#be123c" stroke-width="1.4"/>'
        + '<ellipse cx="16" cy="16" rx="3.5" ry="6" fill="#fef08a" fill-opacity="0.7"/>'
        + '<rect x="11" y="23" width="10" height="2.5" rx="1" fill="#78350f"/>'
        + '</svg>';
    }
    return '<svg class="jp-parts__deco-svg" viewBox="0 0 36 26" fill="none" aria-hidden="true">'
      + '<path d="M7 20h21a5 5 0 000-10 7 7 0 00-13-2 6 6 0 00-8 12z" fill="#ffffff" fill-opacity="0.88"/>'
      + '</svg>';
  }

  function curveSvg(fromPos, toPos, isDone, isActiveRun) {
    var x1 = WAVE_X[fromPos] || 120;
    var x2 = WAVE_X[toPos] || 120;
    var cls = 'jp-parts__curve' + (isDone ? ' jp-parts__curve--done' : '')
      + (isActiveRun ? ' jp-parts__curve--run' : '');
    return '<svg class="' + cls + '" viewBox="0 0 240 44" preserveAspectRatio="none" aria-hidden="true">'
      + '<path class="jp-parts__curve-track" d="M' + x1 + ' 0 C' + x1 + ' 22, ' + x2 + ' 22, ' + x2 + ' 44" fill="none"'
      + ' stroke="currentColor" stroke-width="5" stroke-linecap="round"' + (isDone && !isActiveRun ? '' : ' stroke-dasharray="7 7"') + '/>'
      + '</svg>';
  }

  function itemsHtml(items) {
    return '<ul class="jp-parts__items">' + list(items).map(function (it) {
      return '<li><span lang="ja" class="jp-parts__ja">' + esc(it.label) + '</span>'
        + (it.reading && it.reading !== it.label ? '<span class="jp-parts__rd" lang="ja">' + esc(it.reading) + '</span>' : '')
        + (it.meaning ? '<span class="jp-parts__mean">' + esc(it.meaning) + '</span>' : '') + '</li>';
    }).join('') + '</ul>';
  }

  function weeklyButtonsHtml() {
    return '<div class="jp-parts__actions jp-parts__weekly-btns">' + ['VOCAB', 'KANJI', 'GRAMMAR'].map(function (t) {
      return '<button type="button" class="jp-btn" data-parts-weekly="' + t + '">'
        + { VOCAB: 'Từ vựng', KANJI: 'Kanji', GRAMMAR: 'Ngữ pháp' }[t] + '</button>';
    }).join('') + '</div>';
  }

  function kitsuBesideTodayHtml(isTodayDone) {
    var src = isTodayDone ? '/images/mascots/kitsu/kitsu-celebrate-30.webp' : '/images/mascots/kitsu/kitsu-encouraging.webp';
    var say = isTodayDone ? 'Hôm nay xong rồi, giỏi lắm!' : 'Học tiếp nào!';
    return '<span class="jp-parts__mascot jp-parts__mascot--today">'
      + '<span class="jp-parts__mascot-speech">' + esc(say) + '</span>'
      + '<img class="jp-parts__mascot-img" src="' + src + '" width="64" height="64" loading="lazy" alt="Kitsu"/>'
      + '</span>';
  }

  function kitsuSideHtml(stepIdx) {
    var k = SIDE_KITSUS[Math.floor(stepIdx / 3) % SIDE_KITSUS.length];
    var side = (Math.floor(stepIdx / 3) % 2 === 0) ? 'right' : 'left';
    return '<span class="jp-parts__mascot jp-parts__mascot--side jp-parts__mascot--' + side + '">'
      + '<img class="jp-parts__mascot-img" src="' + k.src + '" width="46" height="46" loading="lazy" alt="' + esc(k.alt) + '"/>'
      + '<span class="jp-parts__mascot-speech jp-parts__mascot-speech--sm">' + esc(k.say) + '</span>'
      + '</span>';
  }

  function partNodeHtml(p, unit, tabKey, pos, nextPos, hasNext, nextStatus, stepIdx) {
    var items = list(p.items);
    var n = items.length;
    var status = String(p.status || 'NEXT');
    var isToday = status === 'TODAY';
    var isTodayDone = status === 'TODAY_DONE';
    var isDone = status === 'DONE';
    var when = isToday ? 'Hôm nay' : (isTodayDone ? 'Hôm nay · đã xong'
      : (isDone ? 'Đã học' + (p.day ? ' · ' + dayLabel(p.day) : '') : 'Sắp tới'));
    var mod = isTodayDone ? 'today' : status.toLowerCase();
    var stepMod = isTodayDone ? 'today-done' : status.toLowerCase();
    var cls = 'jp-parts__part jp-parts__part--' + mod + (isTodayDone ? ' jp-parts__part--today-done' : '');
    var icon = (isDone || isTodayDone) ? svgCheck() : (isToday ? svgStar() : svgLock());
    var bubble = isToday
      ? '<span class="jp-parts__bubble">Bắt đầu</span>'
      : (isTodayDone ? '<span class="jp-parts__bubble jp-parts__bubble--done">Đã xong</span>' : '');
    var peekText = items.slice(0, 5).map(function (i) { return i.label; }).join(' · ') + (n > 5 ? '…' : '');

    var mascotHtml = '';
    if (isToday || isTodayDone) {
      mascotHtml = kitsuBesideTodayHtml(isTodayDone);
    } else if (stepIdx > 0 && stepIdx % 3 === 0) {
      mascotHtml = kitsuSideHtml(stepIdx);
    }

    var decoSide = (stepIdx % 2 === 0) ? 'left' : 'right';
    var decoHtml = '<span class="jp-parts__deco jp-parts__deco--' + decoSide + '" aria-hidden="true">' + sideDecoSvg(stepIdx) + '</span>';

    var head = '<summary class="jp-parts__head">'
      + '<span class="jp-parts__node-wrap">'
      + bubble
      + '<span class="jp-parts__orb jp-parts__orb--' + stepMod + '" aria-hidden="true">'
      + '<span class="jp-parts__orb-shine"></span>'
      + icon
      + '<span class="jp-parts__orb-num">' + esc(p.index) + '</span>'
      + '</span>'
      + mascotHtml
      + '</span>'
      + '<span class="jp-parts__caption">'
      + '<span class="jp-parts__no">Phần ' + esc(p.index) + '</span>'
      + '<span class="jp-parts__when">' + esc(when) + '</span>'
      + '<span class="jp-parts__n">' + n + ' ' + unit + '</span>'
      + (peekText ? '<span class="jp-parts__peek" lang="ja">' + esc(peekText) + '</span>' : '')
      + '</span>'
      + '</summary>';

    var actions = '';
    if (isToday) {
      actions = '<div class="jp-parts__actions"><button type="button" class="jp-btn jp-btn--primary" data-action="start-mode"'
        + ' data-mode="SPLIT" data-tab-key="' + esc(tabKey) + '">Học phần này</button></div>';
    } else if (isTodayDone || isDone) {
      var first = items[0];
      actions = '<div class="jp-parts__actions">'
        + (isTodayDone ? '<span class="jp-parts__ok">Đã xong phần hôm nay</span>' : '')
        + (first ? '<button type="button" class="jp-btn jp-btn--primary" data-parts-relearn="' + esc(first.code)
          + '" data-parts-count="' + n + '">Học lại phần này</button>' : '')
        + (p.day ? '<a class="jp-btn" href="/learning/jlpt/day?d=' + esc(p.day) + '">Kết quả lần trước</a>' : '')
        + '</div>';
    } else {
      actions = '<p class="jp-parts__locked-note">Hoàn thành phần hôm nay để mở khóa phần này.</p>';
    }

    var isLeadingToToday = (isDone || isTodayDone) && (nextStatus === 'TODAY' || nextStatus === 'TODAY_DONE' || isToday);
    var connector = hasNext ? curveSvg(pos, nextPos, isDone || isTodayDone, isLeadingToToday) : '';

    return '<li class="jp-parts__step jp-parts__step--' + stepMod + ' jp-parts__step--pos-' + pos + '" style="--step-i:' + stepIdx + '">'
      + decoHtml
      + '<details class="' + cls + '"' + (isToday || isTodayDone ? ' open' : '') + '>'
      + head
      + '<div class="jp-parts__body jp-parts__popover">'
      + '<span class="jp-parts__popover-arrow" aria-hidden="true"></span>'
      + '<div class="jp-parts__body-head">'
      + '<strong>Phần ' + esc(p.index) + ' · ' + n + ' ' + unit + '</strong>'
      + '<span class="jp-parts__body-when">' + esc(when) + '</span>'
      + '</div>'
      + (peekText ? '<p class="jp-parts__preview-line" lang="ja">' + esc(peekText) + '</p>' : '')
      + actions
      + (n > 0 ? '<details class="jp-parts__more"><summary class="jp-parts__more-btn" data-parts-more>Xem các mục (' + n + ')</summary>'
        + itemsHtml(items) + '</details>' : '')
      + '</div></details>'
      + connector
      + '</li>';
  }

  function milestoneNodeHtml(weekNum, fromIndex, toIndex, unlocked, pos, nextPos, hasNext, stepIdx) {
    var stateCls = unlocked ? 'jp-parts__milestone--unlocked' : 'jp-parts__milestone--locked';
    var connector = hasNext ? curveSvg(pos, nextPos, unlocked, unlocked) : '';
    var chestSvg = unlocked ? svgChestOpen() : svgChestClosed();
    return '<li class="jp-parts__step jp-parts__step--milestone jp-parts__step--pos-' + pos + '" style="--step-i:' + stepIdx + '">'
      + '<details class="jp-parts__part jp-parts__milestone ' + stateCls + '">'
      + '<summary class="jp-parts__head jp-parts__milestone-head">'
      + '<span class="jp-parts__node-wrap">'
      + '<span class="jp-parts__orb jp-parts__orb--milestone" aria-hidden="true">'
      + '<span class="jp-parts__orb-shine"></span>'
      + chestSvg
      + '</span>'
      + '</span>'
      + '<span class="jp-parts__caption">'
      + '<span class="jp-parts__no">Rương tuần ' + esc(weekNum) + '</span>'
      + '<span class="jp-parts__when">' + (unlocked ? 'Đã mở rương · ' : 'Kiểm tra tuần · ') + 'Phần ' + esc(fromIndex) + '–' + esc(toIndex) + '</span>'
      + '</span>'
      + '</summary>'
      + '<div class="jp-parts__body jp-parts__popover jp-parts__milestone-body">'
      + '<span class="jp-parts__popover-arrow" aria-hidden="true"></span>'
      + '<p class="jp-parts__weekly-title">Kiểm tra tuần ' + esc(weekNum) + ' (Phần ' + esc(fromIndex) + '–' + esc(toIndex) + ')</p>'
      + '<p class="jp-parts__sub">10 câu mỗi kỹ năng từ các mục đã học trong tuần. Mục sai tự động vào Sổ tay.</p>'
      + weeklyButtonsHtml()
      + '</div>'
      + '</details>'
      + connector
      + '</li>';
  }

  function pagerHtml(at, pages) {
    if (pages <= 1) return '';
    var btn = function (p, label, cur) {
      return '<button type="button" class="jp-parts__pg' + (cur ? ' is-on' : '') + '" data-parts-page="' + p + '"'
        + (cur ? ' aria-current="page"' : '') + (p < 0 || p >= pages ? ' disabled' : '') + '>' + label + '</button>';
    };
    var nums = [];
    for (var i = Math.max(0, at - 2); i <= Math.min(pages - 1, at + 2); i++) nums.push(btn(i, String(i + 1), i === at));
    return '<nav class="jp-parts__pager" aria-label="Trang phần học">' + btn(0, 'Đầu', false) + btn(at - 1, '‹', false)
      + nums.join('') + btn(at + 1, '›', false) + btn(pages - 1, 'Cuối', false)
      + '<span class="jp-parts__pginfo">Trang ' + (at + 1) + '/' + pages + '</span></nav>';
  }

  function defaultPageFor(parts) {
    for (var i = 0; i < parts.length; i++) {
      var st = parts[i] && parts[i].status;
      if (st === 'TODAY' || st === 'TODAY_DONE') return Math.floor(i / PER_PAGE);
    }
    return 0;
  }

  function resolveSceneKey(tabKey, data) {
    var k = String(tabKey || '').toLowerCase();
    if (TABS[k]) return k;
    var raw = String((data && data.tab) || '').toUpperCase();
    if (raw === 'KANJI') return 'kanji';
    if (raw === 'GRAMMAR') return 'grammar';
    return 'vocab';
  }

  function partsHtml(data, tabKey, page) {
    var d = data || {};
    var sceneKey = resolveSceneKey(tabKey, d);
    var t = TABS[sceneKey] || ['', 'mục', ''];
    var parts = list(d.parts);
    var pages = Math.max(1, Math.ceil(parts.length / PER_PAGE));
    var initialPage = page == null ? defaultPageFor(parts) : (page | 0);
    var at = Math.min(Math.max(0, initialPage), pages - 1);
    var startIdx = at * PER_PAGE;
    var shown = parts.slice(startIdx, startIdx + PER_PAGE);

    var totalNum = Number(d.total) || 0;
    var learnedNum = Number(d.learned) || 0;
    var pct = totalNum > 0 ? Math.min(100, Math.round((learnedNum / totalNum) * 100)) : 0;

    // Build sequence of map steps (parts + weekly treasure chest milestones every WEEK_SPAN parts)
    var entries = [];
    for (var i = 0; i < shown.length; i++) {
      var p = shown[i];
      var globalIdx = startIdx + i;
      var pIndex = p.index != null ? Number(p.index) : (globalIdx + 1);
      entries.push({ kind: 'part', part: p, index: pIndex });
      var isWeekBoundary = (pIndex > 0 && pIndex % WEEK_SPAN === 0)
        || (globalIdx === parts.length - 1 && parts.length > 0 && pIndex % WEEK_SPAN !== 0);
      if (isWeekBoundary) {
        var weekNum = Math.ceil(pIndex / WEEK_SPAN);
        var fromIndex = (weekNum - 1) * WEEK_SPAN + 1;
        var unlocked = p.status === 'DONE' || p.status === 'TODAY_DONE';
        entries.push({
          kind: 'milestone',
          weekNum: weekNum,
          fromIndex: fromIndex,
          toIndex: pIndex,
          unlocked: unlocked
        });
      }
    }

    var html = '<div class="jp-parts__top">'
      + '<div class="jp-parts__heading">'
      + '<h2 class="jp-parts__title">Bản đồ phần học ' + esc(t[2]) + (d.level ? ' ' + esc(d.level) : '') + '</h2>'
      + '<p class="jp-parts__sub">' + esc(parts.length) + ' phần · mỗi phần ' + esc(d.perPart) + ' ' + t[1]
      + ' (theo thanh kéo) · đã học ' + esc(d.learned) + '/' + esc(d.total) + '</p>'
      + '</div>'
      + '<div class="jp-parts__meter" aria-hidden="true"><span style="width:' + pct + '%"></span></div>'
      + '<div class="jp-parts__legend" aria-label="Chú giải bản đồ">'
      + '<span class="jp-parts__legend-item"><i class="jp-parts__dot jp-parts__dot--done"></i>Đã học</span>'
      + '<span class="jp-parts__legend-item"><i class="jp-parts__dot jp-parts__dot--today"></i>Hôm nay</span>'
      + '<span class="jp-parts__legend-item"><i class="jp-parts__dot jp-parts__dot--next"></i>Sắp tới</span>'
      + '<span class="jp-parts__legend-item"><i class="jp-parts__dot jp-parts__dot--milestone"></i>Rương tuần</span>'
      + '</div>'
      + '</div>';

    html += '<div class="jp-parts__stage jp-parts__stage--' + sceneKey + '" data-tab-scene="' + sceneKey + '">'
      + sceneBackdropHtml(sceneKey)
      + '<ol class="jp-parts__list jp-parts__map">';
    for (var j = 0; j < entries.length; j++) {
      var cur = entries[j];
      var pos = WAVE_POS[j % WAVE_POS.length];
      var nextPos = j + 1 < entries.length ? WAVE_POS[(j + 1) % WAVE_POS.length] : pos;
      var hasNext = j + 1 < entries.length;
      var nextEntry = hasNext ? entries[j + 1] : null;
      var nextStatus = nextEntry && nextEntry.kind === 'part' ? nextEntry.part.status : '';
      if (cur.kind === 'part') {
        html += partNodeHtml(cur.part, t[1], sceneKey, pos, nextPos, hasNext, nextStatus, j);
      } else {
        html += milestoneNodeHtml(cur.weekNum, cur.fromIndex, cur.toIndex, cur.unlocked, pos, nextPos, hasNext, j);
      }
    }
    html += '</ol></div>';
    html += pagerHtml(at, pages);
    html += '<div class="jp-parts__all"><button type="button" class="jp-btn" data-parts-all aria-expanded="false">'
      + 'Xem toàn bộ danh sách (lọc, tìm, in, quiz)</button></div>';
    return html;
  }

  /**
   * Owner 05/11: one test a week, on the last study day (REST days excluded). On that day the block offers the three
   * tests (items first learned in the last 7 days); before it, it says when the test comes.
   */
  function weeklyHtml(week) {
    var days = list(week).filter(function (d) { return d && d.kind !== 'REST'; });
    if (!days.length) return '';
    var last = days[days.length - 1];
    var today = list(week).filter(function (d) { return d && d.today; })[0];
    if (!today) return '';
    if (today.day !== last.day) {
      return '<p class="jp-parts__weekly-note">Kiểm tra tuần: ' + esc(dayLabel(last.day)) + ' (các mục học trong tuần).</p>';
    }
    return '<div class="jp-parts__weekly"><p class="jp-parts__weekly-title">Hôm nay có kiểm tra tuần</p>'
      + '<p class="jp-parts__sub">10 câu mỗi phần, chỉ các mục bạn học trong 7 ngày qua. Mục sai vào Sổ tay.</p>'
      + weeklyButtonsHtml() + '</div>';
  }

  function mount(partsEl, listEl, tabKey, opts) {
    if (!partsEl) return null;
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
    if (!partsEl.__bound) {
      partsEl.__bound = true;
      partsEl.addEventListener('click', function (ev) {
        var t = ev.target;
        if (!t || typeof t.closest !== 'function') return;
        // Close other open popovers when a node head is clicked
        var head = t.closest('.jp-parts__head');
        if (head && partsEl.querySelectorAll) {
          var parentDetails = head.parentElement;
          Array.prototype.forEach.call(partsEl.querySelectorAll('details.jp-parts__part[open]'), function (dEl) {
            if (dEl !== parentDetails) dEl.removeAttribute('open');
          });
        }
        var wk = t.closest('[data-parts-weekly]');
        if (wk) {
          var tab = wk.getAttribute('data-parts-weekly');
          wk.disabled = true;
          Promise.resolve(client.send('POST', '/api/learning/jlpt/checks', { kind: 'FREE_QUIZ', tab: tab, source: 'WEEK',
            count: 10, direction: tab === 'GRAMMAR' ? null : 'MIXED', timerOn: false })).then(function (res) {
            var token = res && (res.token || res.checkToken);
            if (token) root.location.assign('/learning/jlpt/check/' + encodeURIComponent(token) + '?return=%2Flearning');
            else wk.disabled = false;
          }, function (e) {
            wk.disabled = false;
            wk.insertAdjacentHTML('afterend', '<span class="jp-parts__err">' + esc((e && e.message) || 'Chưa mở được bài kiểm tra.') + '</span>');
          });
          return;
        }
        var re = t.closest('[data-parts-relearn]');
        if (re) {
          var d0 = partsEl.__data || {};
          re.disabled = true;
          Promise.resolve(client.send('POST', '/api/learning/jlpt/sessions', { tab: TABS[partsEl.__tab][0], mode: 'BROWSE',
            level: d0.level, startItemCode: re.getAttribute('data-parts-relearn'),
            count: Number(re.getAttribute('data-parts-count')) || 10 })).then(function (view) {
            if (view && view.sessionId != null) root.location.assign('/learning/jlpt/study?session=' + encodeURIComponent(view.sessionId));
            else re.disabled = false;
          }, function () { re.disabled = false; });
          return;
        }
        var pg = t.closest('[data-parts-page]');
        if (pg && !pg.disabled) {
          partsEl.innerHTML = weeklyHtml(partsEl.__week) + partsHtml(partsEl.__data, partsEl.__tab, Number(pg.getAttribute('data-parts-page')));
          var allBtn = partsEl.querySelector('[data-parts-all]');
          if (allBtn && partsEl.__allOpen) {
            allBtn.setAttribute('aria-expanded', 'true');
            allBtn.textContent = 'Ẩn danh sách đầy đủ';
          }
          if (partsEl.scrollIntoView) partsEl.scrollIntoView({ block: 'start', behavior: 'smooth' });
          return;
        }
        var all = t.closest('[data-parts-all]');
        if (all) {
          partsEl.__allOpen = !partsEl.__allOpen;
          if (listEl) listEl.hidden = !partsEl.__allOpen;
          all.setAttribute('aria-expanded', String(!!partsEl.__allOpen));
          all.textContent = partsEl.__allOpen ? 'Ẩn danh sách đầy đủ' : 'Xem toàn bộ danh sách (lọc, tìm, in, quiz)';
          if (partsEl.__allOpen && listEl && listEl.scrollIntoView) listEl.scrollIntoView({ block: 'start', behavior: 'smooth' });
        }
      });
    }
    partsEl.innerHTML = '<p class="jp-status" role="status">Đang tải phần học…</p>';
    return Promise.resolve(client.get('/api/learning/jlpt/parts?tab=' + TABS[key][0])).then(function (data) {
      if (partsEl.__seq !== mine) return;
      partsEl.__data = data;
      partsEl.innerHTML = weeklyHtml(o.week) + partsHtml(data, key);
      var btn = partsEl.querySelector('[data-parts-all]');
      if (btn && partsEl.__allOpen) {
        btn.setAttribute('aria-expanded', 'true');
        btn.textContent = 'Ẩn danh sách đầy đủ';
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
