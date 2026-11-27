/* [JLAS-W0108] Owner 27/11: "Cấp độ của bạn" shows only the level being studied in full.
   Other levels fold into small chips; clicking a chip opens its actions (Ôn lại, Xem tổng kết, Xem trước). */
(function () {
  'use strict';

  function enhance(bar) {
    if (!bar || bar.dataset.rfCompact === '1') return;
    bar.dataset.rfCompact = '1';
    bar.classList.add('rf-levels');
    bar.querySelectorAll('.jp-level-cell').forEach(function (cell) {
      if (cell.classList.contains('jp-step--now')) return;
      cell.classList.add('rf-level--folded');
      var name = cell.querySelector('.jp-level-cell__name');
      if (!name) return;
      name.setAttribute('role', 'button');
      name.setAttribute('tabindex', '0');
      name.setAttribute('aria-expanded', 'false');
      function toggle() {
        var open = !cell.classList.contains('is-open');
        bar.querySelectorAll('.rf-level--folded.is-open').forEach(function (c) {
          c.classList.remove('is-open');
          var n = c.querySelector('.jp-level-cell__name');
          if (n) n.setAttribute('aria-expanded', 'false');
        });
        cell.classList.toggle('is-open', open);
        name.setAttribute('aria-expanded', open ? 'true' : 'false');
      }
      name.addEventListener('click', toggle);
      name.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(); }
      });
    });
  }

  function scan() { document.querySelectorAll('.jp-path .jp-level-bar').forEach(enhance); }

  var root = document.querySelector('.jp-path__levels') || document.body;
  new MutationObserver(scan).observe(root, { childList: true, subtree: true });
  scan();
})();
