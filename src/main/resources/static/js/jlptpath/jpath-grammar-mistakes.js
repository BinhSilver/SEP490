/* [JLAS-W0108] Q6 (owner 24/11): jpath-grammar-mistakes.js — the "Ngữ pháp hay sai" tab of the "Lộ trình học"
 * notebook. Read only: pattern, how many wrong answers, the latest wrong question, a link back to the lesson.
 * It never makes review cards. Shown only when the open book is the learner's "Lộ trình học" notebook. */
(function (root, factory) {
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root && root.document) root.JpathGrammarMistakes = api;
  if (root && root.document && root.document.getElementById('notebookDetail')) api.mount(root.document);
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var API = '/api/learning/jlpt/grammar-mistakes';

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function ddmm(iso) {
    var m = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(iso || ''));
    return m ? m[3] + '/' + m[2] + '/' + m[1] : '';
  }

  function tabsHtml(active) {
    function tab(key, label) {
      var on = key === active;
      return '<button type="button" role="tab" class="jp-gm-tab' + (on ? ' is-active' : '') + '" data-gm-tab="' + key
        + '" aria-selected="' + on + '">' + label + '</button>';
    }
    return tab('words', 'Từ đã lưu') + tab('grammar', 'Ngữ pháp hay sai');
  }

  function listHtml(items) {
    items = items || [];
    if (!items.length) {
      return '<p class="jp-gm-empty">Chưa có mẫu ngữ pháp nào làm sai trong các bài kiểm tra của lộ trình.</p>';
    }
    return '<p class="jp-gm-note">Chỉ để xem lại. Mẫu làm sai đã tự quay về lượt ôn trong lộ trình.</p>'
      + '<ul class="jp-gm-list">' + items.map(function (it) {
        var last = it.lastPrompt
          ? '<p class="jp-gm-last"><span>Câu sai gần nhất' + (it.lastWrongAt ? ' (' + esc(ddmm(it.lastWrongAt)) + ')' : '') + ':</span> '
            + '<span lang="ja">' + esc(it.lastPrompt) + '</span>'
            + (it.lastChosen ? ' <span class="jp-gm-chosen">Bạn chọn: <span lang="ja">' + esc(it.lastChosen) + '</span></span>' : '') + '</p>'
          : (it.lastWrongAt ? '<p class="jp-gm-last">Sai gần nhất ngày ' + esc(ddmm(it.lastWrongAt)) + '</p>' : '');
        return '<li class="jp-gm-item" data-code="' + esc(it.code) + '">'
          + '<div class="jp-gm-head"><strong lang="ja">' + esc(it.pattern || it.code) + '</strong>'
          + (it.level ? ' <span class="jp-gm-level">' + esc(it.level) + '</span>' : '')
          + '<span class="jp-gm-count">Sai ' + esc(it.wrongCount) + ' lần</span></div>'
          + (it.meaningVi ? '<p class="jp-gm-meaning">' + esc(it.meaningVi) + '</p>' : '')
          + last
          + '<a class="jp-gm-link" href="' + esc(it.lessonHref || ('/learning/jlpt/grammar/' + encodeURIComponent(it.code))) + '">Xem lại bài</a>'
          + '</li>';
      }).join('') + '</ul>';
  }

  function mount(doc) {
    var detail = doc.getElementById('notebookDetail');
    var state = { data: null, loading: null, tab: 'words' };
    var bar = doc.createElement('div');
    bar.className = 'jp-gm-tabs'; bar.setAttribute('role', 'tablist'); bar.setAttribute('aria-label', 'Nội dung sổ Lộ trình học');
    bar.hidden = true;
    var panel = doc.createElement('section');
    panel.className = 'jp-gm-panel'; panel.hidden = true; panel.setAttribute('aria-live', 'polite');
    var anchor = detail.querySelector('.notebook-vocabulary-heading') || doc.getElementById('notebookEntries');
    anchor.parentNode.insertBefore(bar, anchor);
    detail.appendChild(panel);
    var wordParts = ['notebookFlashcardToolbar', 'notebookEntries'].map(function (id) { return doc.getElementById(id); })
      .concat([detail.querySelector('.notebook-vocabulary-heading'), detail.querySelector('.notebook-search')])
      .filter(Boolean);

    function load() {
      if (state.loading) return state.loading;
      var headers = root.JlasAuth ? root.JlasAuth.authHeaders() : {};
      state.loading = fetch(API, { headers: headers }).then(function (r) { return r.json(); })
        .then(function (p) { state.data = (p && p.data) || { items: [] }; return state.data; })
        .catch(function () { state.loading = null; return null; });
      return state.loading;
    }

    function show(tab) {
      state.tab = tab;
      bar.innerHTML = tabsHtml(tab);
      var grammar = tab === 'grammar';
      wordParts.forEach(function (el) { if (grammar) { el.dataset.gmHidden = String(!el.hidden); el.hidden = true; } else if (el.dataset.gmHidden) { el.hidden = el.dataset.gmHidden !== 'true'; delete el.dataset.gmHidden; } });
      panel.hidden = !grammar;
      if (grammar) {
        panel.innerHTML = '<p class="jp-gm-empty">Đang tải…</p>';
        load().then(function (d) { if (state.tab === 'grammar') panel.innerHTML = d ? listHtml(d.items) : '<p class="jp-gm-empty">Không tải được danh sách. Thử tải lại trang.</p>'; });
      }
    }

    function sync() {
      var book = detail.dataset.book;
      if (!book) return;
      load().then(function (d) {
        var ours = !!(d && d.notebookId != null && String(d.notebookId) === book);
        if (detail.dataset.book !== book) return;
        if (state.tab === 'grammar') show('words');
        bar.hidden = !ours;
        if (ours) bar.innerHTML = tabsHtml('words');
      });
    }

    bar.addEventListener('click', function (e) {
      var b = e.target.closest && e.target.closest('[data-gm-tab]');
      if (b) show(b.getAttribute('data-gm-tab'));
    });
    if (root.MutationObserver) new root.MutationObserver(sync).observe(detail, { attributes: true, attributeFilter: ['data-book'] });
    sync();
    return { show: show, sync: sync };
  }

  return { listHtml: listHtml, tabsHtml: tabsHtml, mount: mount };
});
