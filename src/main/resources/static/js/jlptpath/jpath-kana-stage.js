/* [JLAS-W0108] jpath-kana-stage.js — the kana stage at the start of the Từ vựng map (owner 18/11).
 *
 * A learner who chose "chưa biết chữ" learns kana first: Hiragana (basic, dakuten, yoon) then Katakana, cut into
 * the day's parts (rows per day follow free time and exam date). There is no kana tab any more: this block sits above
 * the Từ vựng map. Each part shows its kana, a "Học bằng flashcard" button (one private set per part in the team
 * flashcard) and one "Học hàng …" button per row (the row's kana plus the row check, ≥ 80%). A skip test per table
 * (20 random kana, ≥ 80%) skips Hiragana or Katakana. When the stage is done the block folds to one line and stays
 * open for review.
 *
 * GET  /api/learning/jlpt/kana-stage → {on, completed, rowsPerDay, hiraganaDone, katakanaDone, parts:[{index, status,
 *      script, rows, rowCodes, passedRows, items:[{code, label, reading}]}]}
 * POST /api/learning/jlpt/flashcards/open?tab=KANA&part=i → {url}
 * POST /api/learning/jlpt/sessions {tab:'KANA', mode:'BROWSE', startItemCode: rowCode} → {sessionId}
 * POST /api/learning/jlpt/week-skip/KANA/check?script=HIRAGANA|KATAKANA → {token}
 *
 * window.JpathKanaStage = { stageHtml(data, state), mount(el, opts) }
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathKanaStage = api;
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var BASE = '/api/learning/jlpt';
  var SCRIPT = { HIRAGANA: 'Hiragana', KATAKANA: 'Katakana' };
  var SKIP_TIP = '20 chữ ngẫu nhiên, đúng từ 16 chữ là bỏ qua cả bảng; chữ sai vào phần ôn hằng ngày';
  var STATUS = { DONE: 'Đã học', TODAY: 'Hôm nay', TODAY_DONE: 'Hôm nay', NEXT: 'Sắp tới' };

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function list(v) { return Array.isArray(v) ? v : []; }

  function partPanelHtml(p, noKana) {
    var passed = list(p.passedRows);
    var kana = list(p.items).map(function (it) {
      // owner 22/11: each kana cell opens "Luyện viết" again (as the old kana table did)
      var inner = '<span lang="ja">' + esc(it.label) + '</span><span class="jp-kstage__romaji">' + esc(it.reading) + '</span>';
      if (!it.code) return '<span class="jp-kstage__kana">' + inner + '</span>';
      return '<button type="button" class="jp-kstage__kana jp-kstage__kana--write" title="Bấm để luyện viết" data-jpath-writing="'
        + esc(JSON.stringify({ itemType: 'KANA', code: it.code })) + '">' + inner + '</button>';
    }).join('');
    var codes = list(p.items).filter(function (it) { return it.code; }).map(function (it) { return it.code; });
    var writeBtn = codes.length ? '<button type="button" class="jp-btn" data-jpath-writing="'
      + esc(JSON.stringify({ itemType: 'KANA', codes: codes })) + '">Luyện viết ' + codes.length + ' chữ</button>' : '';
    var rows = list(p.rowCodes).map(function (code, i) {
      var title = String(list(p.rows)[i] || code).replace(/^Hàng\s*/, '');
      var ok = passed.indexOf(code) >= 0;
      return '<button type="button" class="jp-btn jp-kstage__row" data-kstage-row="' + esc(code) + '">'
        + (ok ? 'Ôn hàng ' : 'Học hàng ') + '<span lang="ja">' + esc(title) + '</span></button>';
    }).join('');
    return '<div class="jp-kstage__panel" id="jp-kstage-panel-' + esc(p.index) + '">'
      + (noKana ? '' : '<div class="jp-kstage__kanas">' + kana + '</div>')
      + '<div class="jp-kstage__actions">'
      + '<button type="button" class="jp-btn jp-btn--primary" data-kstage-fc="' + esc(p.index) + '">Học bằng flashcard</button>'
      + writeBtn
      // F226 (owner 22/11): "In cả phần" — the writing sheet of the part's kana, in a new tab
      + (noKana ? '' : '<a class="jp-btn" target="_blank" rel="noopener" href="/learning/jlpt/print?scope=PART&tab=KANA&part='
        + encodeURIComponent(p.index) + '">In cả phần</a>')
      + rows + '</div>'
      + '<p class="jp-kstage__err" role="status" data-kstage-err></p></div>';
  }

  function partHtml(p, open) {
    var st = String(p.status || 'NEXT');
    var cls = st === 'DONE' ? 'done' : (st.indexOf('TODAY') === 0 ? 'today' : 'next');
    return '<li class="jp-kstage__part jp-kstage__part--' + cls + '">'
      + '<button type="button" class="jp-kstage__node" data-kstage-part="' + esc(p.index) + '" aria-expanded="' + open
      + '" aria-controls="jp-kstage-panel-' + esc(p.index) + '"' + (cls === 'today' ? ' data-kstage-today="true"' : '') + '>'
      + '<span class="jp-kstage__num">Phần ' + esc(p.index) + '</span>'
      + '<span class="jp-kstage__what">' + esc(SCRIPT[p.script] || p.script) + ' · <span lang="ja">'
      + esc(list(p.rows).join('・')) + '</span></span>'
      + '<span class="jp-kstage__state">' + esc(STATUS[st] || st) + '</span></button>'
      + (open ? partPanelHtml(p) : '') + '</li>';
  }

  var VOWELS = 'aiueo';
  var FOLD_KEY = 'jlasKanaStageFold';
  function partClass(st) {
    st = String(st || 'NEXT');
    return st === 'DONE' ? 'done' : (st.indexOf('TODAY') === 0 ? 'today' : 'next');
  }

  /** owner 22/11: the gojūon board (columns a i u e o), one table per script; each cell opens Luyện viết. */
  function boardRows(parts) {
    var rows = [];
    var cur = null;
    var last = -1;
    parts.forEach(function (p) {
      list(p.items).forEach(function (it) {
        var r = String(it.reading || '').toLowerCase();
        var col = VOWELS.indexOf(r.charAt(r.length - 1));
        if (col < 0) { rows.push({ cells: [{ it: it, p: p }], solo: true }); cur = null; last = -1; return; }
        if (!cur || col <= last) { cur = { cells: [null, null, null, null, null] }; rows.push(cur); }
        cur.cells[col] = { it: it, p: p };
        last = col;
      });
    });
    return rows;
  }

  function cellHtml(c) {
    if (!c) return '<td class="jp-kboard__cell jp-kboard__cell--empty" aria-hidden="true"></td>';
    var it = c.it;
    var inner = '<span lang="ja">' + esc(it.label) + '</span><span class="jp-kstage__romaji">' + esc(it.reading) + '</span>';
    var cls = 'jp-kboard__cell jp-kboard__cell--' + partClass(c.p.status);
    if (!it.code) return '<td class="' + cls + '"><span class="jp-kboard__kana">' + inner + '</span></td>';
    return '<td class="' + cls + '"><button type="button" class="jp-kboard__kana" title="Luyện viết ' + esc(it.label)
      + '" data-jpath-writing="' + esc(JSON.stringify({ itemType: 'KANA', code: it.code })) + '">' + inner + '</button></td>';
  }

  function boardHtml(parts) {
    var byScript = {};
    var order = [];
    parts.forEach(function (p) {
      var k = p.script || 'HIRAGANA';
      if (!byScript[k]) { byScript[k] = []; order.push(k); }
      byScript[k].push(p);
    });
    return '<div class="jp-kboard">' + order.map(function (k) {
      var rows = boardRows(byScript[k]);
      if (!rows.length) return '';
      return '<table class="jp-kboard__table"><caption>' + esc(SCRIPT[k] || k) + '</caption><thead><tr>'
        + VOWELS.split('').map(function (v) { return '<th scope="col">' + v + '</th>'; }).join('') + '</tr></thead><tbody>'
        + rows.map(function (r) {
          if (r.solo) return '<tr>' + cellHtml(r.cells[0]) + '<td class="jp-kboard__cell jp-kboard__cell--empty" colspan="4" aria-hidden="true"></td></tr>';
          return '<tr>' + r.cells.map(cellHtml).join('') + '</tr>';
        }).join('') + '</tbody></table>';
    }).join('') + '</div>'
      + '<p class="jp-kboard__legend"><span class="jp-kboard__dot jp-kboard__dot--done"></span>Đã học'
      + '<span class="jp-kboard__dot jp-kboard__dot--today"></span>Hôm nay'
      + '<span class="jp-kboard__dot jp-kboard__dot--next"></span>Sắp tới'
      + '<span class="jp-kboard__hint">Bấm vào chữ bất kỳ để luyện viết</span></p>';
  }

  function readFold() {
    // owner 22/11: folded by default (today's part stays shown); only an explicit "Thu gọn"=off opens it
    try { return !(root && root.localStorage && root.localStorage.getItem(FOLD_KEY) === '0'); } catch (e) { return true; }
  }
  function saveFold(v) {
    try { if (root && root.localStorage) root.localStorage.setItem(FOLD_KEY, v ? '1' : '0'); } catch (e) { /* private mode */ }
  }

  /** state: {open: part index shown, expanded: shown after the stage is done, collapsed: folded while learning,
   *  view: 'board' (default, the gojūon table) | 'parts' (the day parts)}. */
  function stageHtml(data, state) {
    var d = data || {};
    var s = state || {};
    if (!d.on) return '';
    var parts = list(d.parts);
    var done = parts.filter(function (p) { return p.status === 'DONE'; }).length;
    var folded = d.completed ? !s.expanded : !!s.collapsed;
    var view = s.view === 'parts' ? 'parts' : 'board';
    var skips = '';
    if (!d.hiraganaDone) skips += '<button type="button" class="jp-btn" data-kstage-skip="HIRAGANA" title="' + SKIP_TIP + '">Tôi đã biết Hiragana</button>';
    if (!d.katakanaDone) skips += '<button type="button" class="jp-btn" data-kstage-skip="KATAKANA" title="' + SKIP_TIP + '">Tôi đã biết Katakana</button>';
    var sub = d.completed ? 'Đã học xong ' + parts.length + ' phần'
      : done + '/' + parts.length + ' phần · ' + esc(d.rowsPerDay) + ' hàng mỗi ngày';
    var body = '';
    var openPart = parts.filter(function (p) { return String(s.open) === String(p.index); })[0];
    if (folded && !d.completed) {
      // owner 22/11: folded still shows today's part; "Xem cả bảng chữ cái" opens the whole board
      var todayPart = parts.filter(function (p) { return String(p.status).indexOf('TODAY') === 0; })[0] || openPart;
      if (todayPart) {
        body = '<div class="jp-kstage__focus jp-kstage__part--' + partClass(todayPart.status) + '">'
          + '<h3 class="jp-kstage__focus-title">Hôm nay · Phần ' + esc(todayPart.index) + ' · ' + esc(SCRIPT[todayPart.script] || todayPart.script)
          + ' · <span lang="ja">' + esc(list(todayPart.rows).join('・')) + '</span></h3>' + partPanelHtml(todayPart) + '</div>';
      }
    }
    if (!folded) {
      body = '<div class="jp-kstage__views" role="group" aria-label="Cách xem">'
        + '<button type="button" class="jp-btn' + (view === 'board' ? ' jp-btn--primary' : '') + '" data-kstage-view="board" aria-pressed="' + (view === 'board') + '">Bảng chữ cái</button>'
        + '<button type="button" class="jp-btn' + (view === 'parts' ? ' jp-btn--primary' : '') + '" data-kstage-view="parts" aria-pressed="' + (view === 'parts') + '">Theo từng phần</button></div>';
      if (view === 'board') {
        body += boardHtml(parts);
        if (openPart) {
          body += '<div class="jp-kstage__focus jp-kstage__part--' + partClass(openPart.status) + '">'
            + '<h3 class="jp-kstage__focus-title">Phần ' + esc(openPart.index) + ' · ' + esc(SCRIPT[openPart.script] || openPart.script)
            + ' · <span lang="ja">' + esc(list(openPart.rows).join('・')) + '</span></h3>' + partPanelHtml(openPart, true) + '</div>';
        }
      } else {
        body += '<ol class="jp-kstage__list">' + parts.map(function (p) {
          return partHtml(p, String(s.open) === String(p.index));
        }).join('') + '</ol>';
      }
    }
    var toggleText = folded ? (d.completed ? 'Ôn chữ cái' : 'Xem cả bảng chữ cái') : 'Thu gọn';
    return '<section class="jp-kstage' + (d.completed ? ' jp-kstage--done' : '') + '" aria-label="Chặng chữ cái">'
      + '<div class="jp-kstage__head"><div class="jp-kstage__titles"><h2 class="jp-kstage__title">Chặng chữ cái</h2>'
      + '<p class="jp-kstage__sub">' + sub + '</p></div>'
      + '<div class="jp-kstage__tools">' + skips
      + '<button type="button" class="jp-btn" data-kstage-toggle aria-expanded="' + !folded + '">' + toggleText + '</button>'
      + '</div></div>'
      + '<p class="jp-kstage__err" role="status" data-kstage-skip-err></p>'
      + body
      + '</section>';
  }

  function goCheck(token) {
    root.location.assign('/learning/jlpt/check/' + encodeURIComponent(token) + '?return=' + encodeURIComponent('/learning?tab=vocab'));
  }

  function mount(el, opts) {
    if (!el) return null;
    var o = opts || {};
    var client = o.api || root.JpathApi;
    if (String(o.tab || '').toLowerCase() !== 'vocab' || o.ownLevel === false || !client || !(o.path && o.path.kanaTab)) {
      el.hidden = true;
      el.innerHTML = '';
      return null;
    }
    var seq = (el.__seq || 0) + 1;
    el.__seq = seq;
    el.__state = el.__state || { collapsed: readFold() };

    function render() {
      var html = stageHtml(el.__data, el.__state);
      el.innerHTML = html;
      el.hidden = !html;
    }

    if (!el.__bound) {
      el.__bound = true;
      el.addEventListener('click', function (ev) {
        var t = ev.target;
        if (!t || typeof t.closest !== 'function') return;
        var writeEl = t.closest('[data-jpath-writing]');
        if (writeEl && writeEl.getAttribute('data-jpath-writing-bound') !== '1' && root && root.JpathWriting && root.JpathWriting.bind) {
          root.JpathWriting.bind(writeEl);
          writeEl.click();
          return;
        }
        var node = t.closest('[data-kstage-part]');
        if (node) {
          var idx = node.getAttribute('data-kstage-part');
          el.__state.open = String(el.__state.open) === idx ? null : idx;
          render();
          return;
        }
        if (t.closest('[data-kstage-toggle]')) {
          if (el.__data && el.__data.completed) el.__state.expanded = !el.__state.expanded;
          else { el.__state.collapsed = !el.__state.collapsed; saveFold(el.__state.collapsed); }
          render();
          return;
        }
        var viewBtn = t.closest('[data-kstage-view]');
        if (viewBtn) {
          el.__state.view = viewBtn.getAttribute('data-kstage-view');
          render();
          return;
        }
        var fc = t.closest('[data-kstage-fc]');
        var row = t.closest('[data-kstage-row]');
        var skip = t.closest('[data-kstage-skip]');
        var btn = fc || row || skip;
        if (!btn) return;
        var errBox = skip ? el.querySelector('[data-kstage-skip-err]')
          : (btn.closest('.jp-kstage__panel') || el).querySelector('[data-kstage-err]');
        btn.disabled = true;
        btn.setAttribute('aria-busy', 'true');
        var call;
        if (fc) {
          call = client.send('POST', BASE + '/flashcards/open?tab=KANA&part=' + encodeURIComponent(fc.getAttribute('data-kstage-fc')))
            .then(function (set) {
              if (!set || !set.url) throw new Error('Chưa mở được bộ thẻ, thử lại sau ít phút');
              root.location.assign(set.url + '&back=' + encodeURIComponent(root.location.pathname + root.location.search));
            });
        } else if (row) {
          call = client.send('POST', BASE + '/sessions', { tab: 'KANA', mode: 'BROWSE', startItemCode: row.getAttribute('data-kstage-row') })
            .then(function (res) {
              var id = res && (res.sessionId != null ? res.sessionId : res.id);
              if (id == null) throw new Error('Chưa mở được hàng chữ này. Thử lại sau.');
              root.location.assign('/learning/jlpt/study?session=' + encodeURIComponent(id));
            });
        } else {
          call = client.send('POST', BASE + '/week-skip/KANA/check?script=' + encodeURIComponent(skip.getAttribute('data-kstage-skip')))
            .then(function (res) {
              var token = res && (res.token || res.checkToken);
              if (!token) throw new Error('Chưa mở được bài kiểm tra. Thử lại sau.');
              goCheck(token);
            });
        }
        Promise.resolve(call).catch(function (e) {
          btn.disabled = false;
          btn.removeAttribute('aria-busy');
          if (errBox) errBox.textContent = (e && (e.code || e.status) && e.message) || 'Chưa làm được, thử lại sau.';
        });
      });
    }

    return Promise.resolve(client.get(BASE + '/kana-stage')).then(function (data) {
      if (el.__seq !== seq) return;
      el.__data = data;
      if (el.__state.open == null && data && !data.completed) {
        var today = list(data.parts).filter(function (p) { return String(p.status).indexOf('TODAY') === 0; })[0];
        if (today) el.__state.open = String(today.index);
      }
      render();
    }, function () {
      if (el.__seq !== seq) return;
      el.hidden = true;
      el.innerHTML = '';
    });
  }

  return { stageHtml: stageHtml, mount: mount };
});
