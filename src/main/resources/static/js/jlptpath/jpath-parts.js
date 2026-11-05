/* [JLAS-W0108] jpath-parts.js — the study parts of the Từ vựng / Kanji / Ngữ pháp tabs (owner 05/11).
 *
 * A tab no longer opens on its whole list: it shows parts of the size set by the tab's slider (one day's new items),
 * from GET /api/learning/jlpt/parts?tab=. Today's part is open with "Học phần này" (data-action="start-mode"
 * data-mode="SPLIT", handled by jpath-path.js); past parts show their day; coming parts open on click. The full list
 * (filters, search, print, quiz) of jpath-lists.js stays in the list slot, hidden until "Xem toàn bộ danh sách".
 *
 * window.JpathParts = { partsHtml(data, showAllNext), mount(partsEl, listEl, tabKey, opts) }
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathParts = api;
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var TABS = { vocab: ['VOCAB', 'từ', 'Từ vựng'], kanji: ['KANJI', 'chữ', 'Kanji'], grammar: ['GRAMMAR', 'mẫu', 'Ngữ pháp'] };
  var NEXT_SHOWN = 4;
  var WEEKDAY = ['CN', 'T2', 'T3', 'T4', 'T5', 'T6', 'T7'];

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

  function itemsHtml(items) {
    return '<ul class="jp-parts__items">' + list(items).map(function (it) {
      return '<li><span lang="ja" class="jp-parts__ja">' + esc(it.label) + '</span>'
        + (it.reading && it.reading !== it.label ? '<span class="jp-parts__rd" lang="ja">' + esc(it.reading) + '</span>' : '')
        + (it.meaning ? '<span class="jp-parts__mean">' + esc(it.meaning) + '</span>' : '') + '</li>';
    }).join('') + '</ul>';
  }

  function partHtml(p, unit, tabKey) {
    var n = list(p.items).length;
    var when = p.status === 'TODAY' ? 'Hôm nay' : (p.status === 'TODAY_DONE' ? 'Hôm nay · đã xong'
      : (p.status === 'DONE' ? 'Đã học · ' + dayLabel(p.day) : 'Sắp tới'));
    var cls = 'jp-parts__part jp-parts__part--' + (p.status === 'TODAY_DONE' ? 'today' : String(p.status).toLowerCase());
    var head = '<summary class="jp-parts__head"><span class="jp-parts__no">Phần ' + esc(p.index) + '</span>'
      + '<span class="jp-parts__when">' + esc(when) + '</span><span class="jp-parts__n">' + n + ' ' + unit + '</span>'
      + '<span class="jp-parts__peek" lang="ja">' + esc(list(p.items).slice(0, 5).map(function (i) { return i.label; }).join(' · '))
      + (n > 5 ? ' …' : '') + '</span></summary>';
    var actions = '';
    if (p.status === 'TODAY') {
      actions = '<div class="jp-parts__actions"><button type="button" class="jp-btn jp-btn--primary" data-action="start-mode"'
        + ' data-mode="SPLIT" data-tab-key="' + esc(tabKey) + '">Học phần này</button></div>';
    } else if (p.status === 'TODAY_DONE') {
      actions = '<div class="jp-parts__actions"><span class="jp-parts__ok">Đã xong phần hôm nay</span>'
        + '<a class="jp-btn" href="/learning/jlpt/day?d=' + esc(p.day) + '">Xem ngày học</a></div>';
    } else if (p.status === 'DONE' && p.day) {
      actions = '<div class="jp-parts__actions"><a class="jp-btn" href="/learning/jlpt/day?d=' + esc(p.day) + '">Xem ngày học</a></div>';
    }
    return '<li><details class="' + cls + '"' + (p.status === 'TODAY' || p.status === 'TODAY_DONE' ? ' open' : '') + '>' + head
      + '<div class="jp-parts__body">' + itemsHtml(p.items) + actions + '</div></details></li>';
  }

  /** The parts block: done parts folded into one line, today's part, then the next few coming parts. */
  var PER_PAGE = 8;

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

  function partsHtml(data, tabKey, page) {
    var d = data || {};
    var t = TABS[tabKey] || ['', 'mục', ''];
    var parts = list(d.parts);
    var done = parts.filter(function (p) { return p.status === 'DONE'; });
    var today = parts.filter(function (p) { return p.status === 'TODAY' || p.status === 'TODAY_DONE'; });
    var next = parts.filter(function (p) { return p.status === 'NEXT'; });
    // page 0 = done group + today + first coming parts; later pages = coming parts only
    var pages = Math.max(1, Math.ceil(next.length / PER_PAGE));
    var at = Math.min(Math.max(0, page | 0), pages - 1);
    var shown = next.slice(at * PER_PAGE, (at + 1) * PER_PAGE);
    if (at > 0) { done = []; today = []; }
    var html = '<div class="jp-parts__top"><h2 class="jp-parts__title">Phần học ' + esc(t[2]) + (d.level ? ' ' + esc(d.level) : '') + '</h2>'
      + '<p class="jp-parts__sub">' + esc(parts.length) + ' phần · mỗi phần ' + esc(d.perPart) + ' ' + t[1]
      + ' (theo thanh kéo) · đã học ' + esc(d.learned) + '/' + esc(d.total) + '</p></div>';
    html += '<ol class="jp-parts__list">';
    if (done.length) {
      html += '<li><details class="jp-parts__part jp-parts__part--donegroup"><summary class="jp-parts__head">'
        + '<span class="jp-parts__no">Phần 1–' + done.length + '</span><span class="jp-parts__when">Đã học</span>'
        + '<span class="jp-parts__n">' + done.reduce(function (s, p) { return s + list(p.items).length; }, 0) + ' ' + t[1]
        + '</span></summary><ol class="jp-parts__list">' + done.map(function (p) { return partHtml(p, t[1], tabKey); }).join('')
        + '</ol></details></li>';
    }
    html += today.concat(shown).map(function (p) { return partHtml(p, t[1], tabKey); }).join('') + '</ol>';
    html += pagerHtml(at, pages);
    html += '<div class="jp-parts__all"><button type="button" class="jp-btn" data-parts-all aria-expanded="false">'
      + 'Xem toàn bộ danh sách (lọc, tìm, in, quiz)</button></div>';
    return html;
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
    if (!partsEl.__bound) {
      partsEl.__bound = true;
      partsEl.addEventListener('click', function (ev) {
        var t = ev.target;
        var pg = t.closest && t.closest('[data-parts-page]');
        if (pg && !pg.disabled) {
          partsEl.innerHTML = partsHtml(partsEl.__data, partsEl.__tab, Number(pg.getAttribute('data-parts-page')));
          if (partsEl.scrollIntoView) partsEl.scrollIntoView({ block: 'start', behavior: 'smooth' });
          return;
        }
        var all = t.closest && t.closest('[data-parts-all]');
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
      partsEl.innerHTML = partsHtml(data, key, 0);
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

  return { partsHtml: partsHtml, mount: mount };
});
