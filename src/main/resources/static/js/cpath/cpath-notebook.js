/* [JLAS-W0108] cpath-notebook.js — Sổ tay of the communication path.
 *
 * GET /api/learning/comm/notebook → {items:[{id, kind ITEM|LINE, ja, reading, vi, audioUrl, situation, wrongCount}], total}
 * Lists the missed words, phrases and learner lines; each can be played and opens its situation.
 *
 * window.CpathNotebook = { listHtml(data), mount(el) }
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.CpathNotebook = api;
  if (root && root.document && typeof module === 'undefined') {
    var start = function () { api.mount(root.document.getElementById('cpathNotebookRoot')); };
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', start);
    else start();
  }
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function listHtml(data) {
    var items = (data && data.items) || [];
    if (!items.length) {
      return '<p class="jp-side__note">Sổ tay đang trống. Mục bạn trả lời sai ở kiểm tra tuần sẽ hiện ở đây.</p>';
    }
    return '<ul class="jp-todo">' + items.map(function (x) {
      return '<li class="jp-today__pill jp-todo__item"><div class="jp-today__pill-body">'
        + '<div class="jp-today__pill-head"><strong class="jp-today__pill-title" lang="ja">' + esc(x.ja) + '</strong>'
        + '<span class="jp-today__pill-count">' + (x.kind === 'LINE' ? 'Câu thoại' : 'Từ · mẫu câu')
        + ' · sai ' + esc(x.wrongCount) + ' lần</span></div>'
        + (x.reading && x.reading !== x.ja ? '<div class="cp-survey__sub" lang="ja">' + esc(x.reading) + '</div>' : '')
        + '<div class="cp-survey__sub">' + esc(x.vi) + '</div></div>'
        + (x.audioUrl ? '<button type="button" class="jp-btn jp-btn--sm" data-note-play="' + esc(x.audioUrl) + '" aria-label="Nghe phát âm ' + esc(x.ja) + '">Nghe</button>' : '')
        + (x.situation ? '<a class="cp-btn-ghost" href="/learning/comm/situation?id=' + encodeURIComponent(x.situation) + '">Mở tình huống</a>' : '')
        + '</li>';
    }).join('') + '</ul>';
  }

  function mount(el) {
    var client = root && root.JpathApi;
    if (!el || !client) return null;
    el.addEventListener('click', function (ev) {
      var b = ev.target && ev.target.closest && ev.target.closest('[data-note-play]');
      if (b && root.Audio) {
        try {
          if (root.CpathAudio) root.CpathAudio.play(b.getAttribute('data-note-play'), '');
          else new root.Audio(b.getAttribute('data-note-play')).play();
        } catch (e) { /* no audio */ }
      }
      var retry = ev.target && ev.target.closest && ev.target.closest('[data-note-retry]');
      if (retry) load();
    });
    function load() {
      if (el.setAttribute) el.setAttribute('aria-busy', 'true');
      return Promise.resolve(client.get('/api/learning/comm/notebook')).then(function (d) {
        if (el.setAttribute) el.setAttribute('aria-busy', 'false');
        if (root.CpathAudio && d) root.CpathAudio.fromDisplay(d.display); // F70: listening speed
        el.innerHTML = listHtml(d);
      }, function (e) {
        if (el.setAttribute) el.setAttribute('aria-busy', 'false');
        el.innerHTML = e && e.status === 404
          ? '<p class="jp-side__note">Bạn chưa có lộ trình giao tiếp. <a href="/learning/comm/survey">Làm khảo sát</a> để bắt đầu.</p>'
          : '<p class="jp-alert" role="alert">Chưa tải được sổ tay. <button type="button" class="jp-btn" data-note-retry>Thử lại</button></p>';
      });
    }
    return load();
  }

  return { listHtml: listHtml, mount: mount };
});
