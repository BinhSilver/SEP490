/* [JLAS-W0108] my-paths-page.js (owner 24/11): the "Lộ trình của tôi" dashboard (/learning/my).
 * Reads GET /api/learning/my-paths, draws it with MyPathsCore, and runs the card buttons:
 *   - Tạm dừng: POST /api/learning/my-paths/{key}/pause after a confirm; only that path stops.
 *   - Học tiếp: POST /api/learning/my-paths/{key}/resume. With the active places full the dialog asks which path to
 *     pause first (409 MY_PATHS_FULL from the server asks the same), then sends {pauseKey}.
 * Every change shows a toast and redraws from the server's answer (the server is the only source of truth).
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root && root.document) {
    var run = function () { api.mount(root.document.querySelector('[data-mp-root]')); };
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', run);
    else run();
  }
})(typeof window !== 'undefined' ? window : null, function (root) {
  'use strict';

  var URL_VIEW = '/api/learning/my-paths';
  function core() { return root.MyPathsCore; }
  function http() { return root.JpathApi; }
  function toast(msg, type) {
    if (!(root.JlasToast && root.JlasToast.show(msg, type))) {
      var live = root.document.querySelector('[data-mp-status]');
      if (live) live.textContent = msg;
    }
  }

  function titleOf(view, key) {
    var p = (view && view.paths || []).filter(function (x) { return x.key === key; })[0];
    return p ? p.title : 'Lộ trình';
  }

  /** A small dialog with one button per choice plus Huỷ; resolves the chosen key or null. */
  function choose(message, choices) {
    var doc = root.document;
    return new Promise(function (resolve) {
      var prev = doc.activeElement;
      var el = doc.createElement('div');
      el.className = 'mp-dialog';
      el.setAttribute('role', 'dialog');
      el.setAttribute('aria-modal', 'true');
      el.setAttribute('aria-labelledby', 'mp-dialog-msg');
      var esc = core().esc;
      el.innerHTML = '<div class="mp-dialog__backdrop" data-mp-cancel></div><div class="mp-dialog__panel">'
        + '<p id="mp-dialog-msg" class="mp-dialog__msg">' + esc(message) + '</p><div class="mp-dialog__actions">'
        + choices.map(function (c) { return '<button type="button" class="jp-btn" data-mp-choice="' + esc(c.key) + '">' + esc(c.label) + '</button>'; }).join('')
        + '<button type="button" class="jp-btn mp-dialog__cancel" data-mp-cancel>Huỷ</button></div></div>';
      function close(v) {
        doc.removeEventListener('keydown', onKey, true);
        el.remove();
        if (prev && prev.focus) prev.focus();
        resolve(v);
      }
      function onKey(e) { if (e.key === 'Escape') { e.preventDefault(); close(null); } }
      el.addEventListener('click', function (e) {
        var c = e.target.closest('[data-mp-choice]');
        if (c) close(c.getAttribute('data-mp-choice'));
        else if (e.target.closest('[data-mp-cancel]')) close(null);
      });
      doc.addEventListener('keydown', onKey, true);
      doc.body.appendChild(el);
      var first = el.querySelector('[data-mp-choice]');
      if (first) first.focus();
    });
  }

  /** F240: the popup of one level or need (counts + can-do lines); Esc, backdrop or Đóng close it. */
  function showInfo(html) {
    var doc = root.document;
    var prev = doc.activeElement;
    var el = doc.createElement('div');
    el.className = 'mp-dialog';
    el.setAttribute('role', 'dialog');
    el.setAttribute('aria-modal', 'true');
    el.innerHTML = '<div class="mp-dialog__backdrop" data-mp-cancel></div><div class="mp-dialog__panel mp-io__panel">' + html
      + '<div class="mp-dialog__actions"><button type="button" class="jp-btn" data-mp-cancel>Đóng</button></div></div>';
    function close() {
      doc.removeEventListener('keydown', onKey, true);
      el.remove();
      if (prev && prev.focus) prev.focus();
    }
    function onKey(e) { if (e.key === 'Escape') { e.preventDefault(); close(); } }
    el.addEventListener('click', function (e) { if (e.target.closest('[data-mp-cancel]')) close(); });
    doc.addEventListener('keydown', onKey, true);
    doc.body.appendChild(el);
    var btn = el.querySelector('button[data-mp-cancel]');
    if (btn) btn.focus();
  }

  function confirmPause(title) {
    var msg = 'Tạm dừng ' + title + '? Tiến độ được giữ, các lộ trình khác không đổi. Bấm Học tiếp để học lại đúng chỗ đã dừng.';
    return typeof root.JlasConfirm === 'function'
      ? root.JlasConfirm(msg, { confirmText: 'Tạm dừng', cancelText: 'Huỷ', label: 'Tạm dừng lộ trình' })
      : Promise.resolve(root.confirm ? root.confirm(msg) : true);
  }

  function mount(el) {
    if (!el || el.__mpBound) return;
    el.__mpBound = true;
    var view = null;
    var busy = false;

    var io = null; // F240: entry/exit per path key, read once per page load
    var ioPromise = Promise.resolve(http().get('/api/learning/path-io')).then(function (x) { io = x || null; return io; },
      function () { return null; });
    function withIo(v) {
      if (!v || !io || !io.paths) return v;
      (v.paths || []).forEach(function (p) { if (io.paths[p.key]) p.io = io.paths[p.key]; });
      return v;
    }
    function draw(v) {
      view = withIo(v);
      el.setAttribute('aria-busy', 'false');
      el.innerHTML = core().dashboardHtml(v);
    }
    function fail(err) {
      el.setAttribute('aria-busy', 'false');
      el.innerHTML = '<section class="mp-error jp-card" role="alert"><h2>Chưa tải được lộ trình của bạn</h2><p>'
        + core().esc(err && err.message ? err.message : 'Thử lại sau.') + '</p>'
        + '<button type="button" class="jp-btn jp-btn--primary" data-mp-reload>Thử lại</button></section>';
    }
    function load() {
      el.setAttribute('aria-busy', 'true');
      return Promise.all([http().get(URL_VIEW), ioPromise]).then(function (r) { draw(r[0]); }, fail);
    }

    function resume(key, pauseKey) {
      return http().send('POST', URL_VIEW + '/' + encodeURIComponent(key) + '/resume', pauseKey ? { pauseKey: pauseKey } : undefined)
        .then(function (v) {
          draw(v);
          toast(titleOf(v, key) + ' học tiếp từ chỗ đã dừng.' + (pauseKey ? ' Đã tạm dừng ' + titleOf(view, pauseKey) + '.' : ''));
        }, function (err) {
          if (err && err.code === 'MY_PATHS_FULL' && !pauseKey) return askAndResume(key);
          toast(err && err.message ? err.message : 'Chưa học tiếp được. Thử lại sau.', 'error');
        });
    }
    function askAndResume(key) {
      var c = core().fullChoice(view, key);
      return choose(c.message, c.choices).then(function (pick) { if (pick) return resume(key, pick); });
    }

    el.addEventListener('click', function (e) {
      var t = e.target;
      if (t.closest('[data-mp-reload]')) { load(); return; }
      var lv = t.closest('[data-io-level]');
      if (lv && root.PathIoCore) {
        showInfo(root.PathIoCore.popupHtml(lv.getAttribute('data-io-level'), io && io.levels));
        return;
      }
      var pause = t.closest('[data-mp-pause]');
      var res = t.closest('[data-mp-resume]');
      if ((!pause && !res) || busy) return;
      var key = (pause || res).getAttribute(pause ? 'data-mp-pause' : 'data-mp-resume');
      var btn = pause || res;
      busy = true;
      btn.disabled = true;
      var job = pause
        ? confirmPause(titleOf(view, key)).then(function (ok) {
          if (!ok) return;
          return http().send('POST', URL_VIEW + '/' + encodeURIComponent(key) + '/pause').then(function (v) {
            draw(v);
            toast('Đã tạm dừng ' + titleOf(v, key) + ', tiến độ được giữ.');
          }, function (err) { toast(err && err.message ? err.message : 'Chưa tạm dừng được. Thử lại sau.', 'error'); });
        })
        : (core().needsChoice(view) ? askAndResume(key) : resume(key));
      Promise.resolve(job).then(function () { busy = false; btn.disabled = false; }, function () { busy = false; btn.disabled = false; });
    });

    load();
  }

  return { mount: mount, choose: choose };
});
