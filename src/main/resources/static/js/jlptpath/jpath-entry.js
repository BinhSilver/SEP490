/* [JLAS-W0108] jpath-entry.js — "Đầu vào" on the Stats page (owner 06/11): the Smart Test result the path was made
 * from (GET /api/learning/jlpt/entry-result), next to the level the path opened at. Hidden when the path did not come
 * from a Smart Test.
 *
 * window.JpathEntry = { entryHtml(r), mount() }
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathEntry = api;
  if (root && root.document && typeof module === 'undefined') {
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', api.mount);
    else api.mount();
  }
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function date(iso) {
    var m = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(iso || ''));
    return m ? m[3] + '/' + m[2] + '/' + m[1] : '';
  }

  function entryHtml(r) {
    var x = r || {};
    var tiles = [['Từ vựng', x.vocabScore], ['Kanji', x.kanjiScore], ['Ngữ pháp', x.grammarScore]]
      .filter(function (t) { return t[1] !== null && t[1] !== undefined; })
      .map(function (t) {
        return '<div class="jp-side-tile"><strong>' + esc(t[1]) + '</strong><span>' + esc(t[0]) + '</span></div>';
      }).join('');
    return '<p>Kết quả Smart Test: <b>' + esc(x.abilityBand || x.estimatedLevel || '—') + '</b>'
      + (x.partial ? ' (làm chưa hết bài)' : '') + ' · lộ trình mở ở <b>' + esc(x.openLevel || '') + '</b>'
      + (x.pathCreatedOn ? ' từ ' + esc(date(x.pathCreatedOn)) : '') + '.</p>'
      + (tiles ? '<div class="jp-side-tiles">' + tiles + '</div>' : '')
      + '<p class="jp-side__note">Điểm là mức nắm vững của từng kỹ năng lúc làm bài. Tiến độ hiện tại xem ở các số liệu phía trên.</p>';
  }

  function mount() {
    var doc = root && root.document;
    var box = doc && doc.querySelector && doc.querySelector('[data-jpath-entry]');
    if (!box || !root.JpathApi) return null;
    return Promise.resolve(root.JpathApi.get('/api/learning/jlpt/entry-result')).then(function (r) {
      if (!r || !r.fromSmartTest) return;
      box.querySelector('[data-role="entry"]').innerHTML = entryHtml(r);
      box.hidden = false;
    }, function () { /* keep hidden */ });
  }

  return { entryHtml: entryHtml, mount: mount };
});
