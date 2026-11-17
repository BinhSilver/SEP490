/* [JLAS-W0108] jpath-kana-table.js — the kana tab of the JLPT path: Hiragana/Katakana bar and the compact 50-sound
 * table, one group at a time (Cơ bản / Biến âm / Âm ghép, F22 owner 10/11). Split out of jpath-lists.js.
 * window.JpathKanaTable = { kanaColumns(row), kanaHtml(rows, script), kanaBarHtml(script), kanaRowsHtml(rows),
 *   showKanaGroup(slot, button) }
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathKanaTable = api;
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  function D() {
    return (root && root.JpathDisplay) || (typeof require === 'function' ? require('./jpath-display.js') : null);
  }
  function H() {
    return (root && root.JpathItemDrawer) || (typeof require === 'function' ? require('./jpath-item-drawer.js') : null);
  }
  function esc(v) { return D().esc(v); }
  function dataJson(o) { return D().dataJson(o); }
  function stateOf(s) { return H().stateOf(s); }
  function list(v) { return Array.isArray(v) ? v : []; }
  function icon(name) {
    var nav = root.JpathNav || (typeof require === 'function' ? require('./jpath-nav.js') : null);
    return nav ? nav.iconHtml(name) : '';
  }

  var VOWELS = ['a', 'i', 'u', 'e', 'o'];
  var YOON_VOWELS = ['a', 'u', 'o'];

  function lastVowel(romaji) {
    var m = /([aiueo])$/.exec(String(romaji || '').toLowerCase());
    return m ? m[1] : null;
  }

  /** Grid slots of one kana row: 5 per line a i u e o (ん in its own slot after them), YOON 3 per line a u o. */
  function kanaColumns(row) {
    var r = row || {};
    var cells = list(r.kanas).filter(Boolean);
    if (r.kind === 'YOON') {
      var groups = [];
      var byKey = {};
      cells.forEach(function (c) {
        var key = String(c.romaji || '').replace(/[aiueo]$/, '');
        if (!byKey[key]) { byKey[key] = [null, null, null]; groups.push(byKey[key]); }
        var i = YOON_VOWELS.indexOf(lastVowel(c.romaji));
        if (i >= 0) byKey[key][i] = c;
      });
      return [].concat.apply([], groups);
    }
    var slots = [null, null, null, null, null];
    var extra = [];
    cells.forEach(function (c) {
      var i = VOWELS.indexOf(lastVowel(c.romaji));
      if (i >= 0 && !slots[i]) slots[i] = c; else extra.push(c);
    });
    return slots.concat(extra);
  }

  function kanaCellHtml(c) {
    if (!c) return '<span class="jp-kana-cell jp-kana-cell--empty" aria-hidden="true"></span>';
    return '<button type="button" class="jp-kana-cell jp-kana-cell--' + stateOf(c.state).toLowerCase()
      + '" data-jpath-writing="' + dataJson({ itemType: 'KANA', code: c.code }) + '" aria-label="'
      + esc(c.kana + ' ' + (c.romaji || '') + ', luyện viết') + '"><span class="jp-kanji jp-kana-cell__kana" lang="ja">'
      + esc(c.kana) + '</span><span class="jp-romaji">' + esc(c.romaji) + '</span></button>';
  }

  function kanaBarHtml(script) {
    var s = script === 'KATAKANA' ? 'KATAKANA' : 'HIRAGANA';
    return '<div class="jp-list-bar"><div class="jp-seg" role="group" aria-label="Bảng chữ">'
      + '<button type="button" class="jp-seg__btn" data-action="kana-script" data-script="HIRAGANA" aria-pressed="'
      + (s === 'HIRAGANA') + '">Hiragana</button><button type="button" class="jp-seg__btn" data-action="kana-script"'
      + ' data-script="KATAKANA" aria-pressed="' + (s === 'KATAKANA') + '">Katakana</button></div>'
      + '<button type="button" class="jp-btn" data-jpath-print="' + dataJson({ scope: 'KANA', script: s }) + '">In bảng chữ</button></div>';
  }

  // [JLAS-W0108] F22 (owner 10/11): one compact 50-sound table per group (Cơ bản / Biến âm / Âm ghép) behind a
  // switch instead of one tall card per row; a passed row is tinted, each row keeps its "Học" button.
  var KANA_GROUPS = [
    { key: 'BASIC', label: 'Cơ bản', kinds: ['BASIC'], cols: VOWELS },
    { key: 'VOICED', label: 'Biến âm ゛゜', kinds: ['DAKUTEN', 'HANDAKUTEN'], cols: VOWELS },
    { key: 'YOON', label: 'Âm ghép', kinds: ['YOON'], cols: ['ya', 'yu', 'yo'] }
  ];

  function kanaTd(c) {
    return '<td>' + (c ? kanaCellHtml(c) : '') + '</td>';
  }

  function kanaRowHead(r, span) {
    var title = String(r.title || '').replace(/^Hàng\s+/, '').replace(/^Âm ghép\s+/, '');
    return '<th scope="row"' + (span > 1 ? ' rowspan="' + span + '"' : '') + ' class="jp-kana-table__head">'
      + '<span class="jp-kana-table__title" lang="ja">' + esc(title) + '</span>'
      + (r.passed ? '<span class="jp-kana-table__ok">' + icon('check') + '<span class="jp-sr">Đã đạt</span></span>' : '')
      + (list(r.kanas).filter(Boolean).length ? '<button type="button" class="jp-kana-table__learn" data-action="kana-row" data-row="'
        + esc(r.code) + '" aria-label="' + esc('Học ' + (r.title || '')) + '">Học</button>' : '') + '</th>';
  }

  /** Table lines of one row: 5 slots (ん and other extras on a line of their own), YOON one line per consonant. */
  function kanaRowLines(r) {
    var cols = kanaColumns(r);
    var lines = [];
    var width = r.kind === 'YOON' ? 3 : 5;
    for (var i = 0; i < cols.length; i += width) lines.push(cols.slice(i, i + width));
    var cls = r.passed ? ' class="is-passed"' : '';
    if (r.kind === 'YOON') {
      return lines.map(function (line, k) {
        return '<tr' + cls + '>' + (k === 0 ? kanaRowHead(r, lines.length) : '') + line.map(kanaTd).join('') + '</tr>';
      }).join('');
    }
    var html = '<tr' + cls + '>' + kanaRowHead(r, 1) + lines[0].map(kanaTd).join('') + '</tr>';
    for (var j = 1; j < lines.length; j++) {
      html += '<tr' + cls + '><th scope="row" class="jp-kana-table__head"></th>' + lines[j].map(kanaTd).join('')
        + new Array(width - lines[j].length + 1).join('<td></td>') + '</tr>';
    }
    return html;
  }

  function kanaRowsHtml(rows) {
    var items = list(rows).filter(Boolean);
    if (!items.length) return '<p class="jp-muted jp-list-empty">Chưa có hàng chữ nào.</p>';
    var groups = KANA_GROUPS.map(function (g) {
      return { g: g, rows: items.filter(function (r) { return g.kinds.indexOf(r.kind) >= 0; }) };
    }).filter(function (x) { return x.rows.length; });
    var other = items.filter(function (r) {
      return !KANA_GROUPS.some(function (g) { return g.kinds.indexOf(r.kind) >= 0; });
    });
    if (other.length) {
      if (groups.length) groups[0].rows = groups[0].rows.concat(other);
      else groups.push({ g: KANA_GROUPS[0], rows: other });
    }
    var active = (groups.filter(function (x) { return x.rows.some(function (r) { return !r.passed; }); })[0] || groups[0]).g.key;
    var seg = groups.length > 1 ? '<div class="jp-seg jp-kana-groups" role="group" aria-label="Nhóm chữ">'
      + groups.map(function (x) {
        var done = x.rows.filter(function (r) { return r.passed; }).length;
        return '<button type="button" class="jp-seg__btn" data-action="kana-group" data-group="' + x.g.key + '" aria-pressed="'
          + (x.g.key === active) + '">' + esc(x.g.label) + ' <small>' + done + '/' + x.rows.length + '</small></button>';
      }).join('') + '</div>' : '';
    return seg + groups.map(function (x) {
      return '<div class="jp-kana-panel" data-kana-group="' + x.g.key + '"' + (x.g.key === active ? '' : ' hidden') + '>'
        + '<div class="jp-table-wrap"><table class="jp-kana-table' + (x.g.key === 'YOON' ? ' jp-kana-table--yoon' : '') + '">'
        + '<thead><tr><th scope="col"><span class="jp-sr">Hàng</span></th>' + x.g.cols.map(function (c) {
          return '<th scope="col">' + c + '</th>';
        }).join('') + '</tr></thead><tbody>' + x.rows.map(kanaRowLines).join('') + '</tbody></table></div></div>';
    }).join('');
  }

  /** Shows one kana group panel (no reload: the whole table is already drawn). */
  function showKanaGroup(slot, el) {
    var key = el.getAttribute('data-group');
    Array.prototype.forEach.call(slot.querySelectorAll('[data-kana-group]'), function (p) {
      p.hidden = p.getAttribute('data-kana-group') !== key;
    });
    Array.prototype.forEach.call(slot.querySelectorAll('[data-action="kana-group"]'), function (b) {
      b.setAttribute('aria-pressed', String(b === el));
    });
  }

  function kanaHtml(rows, script) { return kanaBarHtml(script) + kanaRowsHtml(rows); }

  return { kanaColumns: kanaColumns, kanaHtml: kanaHtml, kanaBarHtml: kanaBarHtml, kanaRowsHtml: kanaRowsHtml,
    showKanaGroup: showKanaGroup };
});
