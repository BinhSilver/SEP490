/* [JLAS-W0108] cpath-stats.js — the communication path block of the Stats page (owner 06/11: same frame as JLPT).
 *
 * GET /api/learning/comm/path → {nameVi, progress:{doneSituations, totalSituations, passedTopics, totalTopics}, secondary}
 * Hidden when the learner has no communication path or the feature is off (404).
 *
 * window.CpathStats = { statsHtml(p), mount() }
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.CpathStats = api;
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

  function tile(label, value) {
    // same markup as the JLPT tiles (jpath-stats.js)
    return '<div class="jp-side-tile"><strong>' + esc(value) + '</strong><span>' + esc(label) + '</span></div>';
  }

  function statsHtml(p) {
    var g = (p && p.progress) || {};
    var sec = p && p.secondary;
    return '<p class="jp-side__note">Mục tiêu chính: <strong>' + esc(p.nameVi || p.goal) + '</strong>'
      + (sec ? ' · Mục tiêu phụ: <strong>' + esc(sec.nameVi) + '</strong>' : '') + '</p>'
      + '<div class="jp-side-tiles">'
      + tile('Tình huống đã xong', (g.doneSituations || 0) + '/' + (g.totalSituations || 0))
      + tile('Chủ đề đã đạt', (g.passedTopics || 0) + '/' + (g.totalTopics || 0))
      + (sec ? tile('Mục tiêu phụ', (sec.doneSituations || 0) + '/' + (sec.totalSituations || 0)) : '')
      + '</div>'
      + '<p><a class="jp-link-btn" href="/learning/comm/path">Mở lộ trình giao tiếp</a></p>';
  }

  function mount() {
    var doc = root && root.document;
    var card = doc && doc.querySelector('[data-cpath-stats]');
    var client = root && root.JpathApi;
    if (!card || !client) return null;
    return Promise.resolve(client.get('/api/learning/comm/path')).then(function (p) {
      if (!p || !p.goal) return;
      card.querySelector('[data-role="comm"]').innerHTML = statsHtml(p);
      card.hidden = false;
    }, function () { /* no communication path */ });
  }

  return { statsHtml: statsHtml, mount: mount };
});
