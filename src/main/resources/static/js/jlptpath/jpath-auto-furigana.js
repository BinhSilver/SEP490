/* [JLAS-W0108] jpath-auto-furigana.js — the Furigana toggle of the Đọc · Nghe pages (skill session, section practice).
 *
 * Skill passages, questions, options and scripts carry no reviewed furigana, so the toggle asks
 * POST /api/learning/jlpt/furigana {texts} for a generated reading aid and draws it with JpathFurigana.toRuby.
 *
 * window.JpathAutoFurigana = { toggleHtml(on), targets(root), mount(root, deps) }
 *   - targets(root): the [lang="ja"] elements whose content is only text (a line with inputs or markup is left as is),
 *     not done yet, with at least one Kanji.
 *   - mount(root, {api, slot, storage}): draws the toggle into slot (default: the page head), annotates what is on the
 *     page and everything drawn later (MutationObserver), remembers on/off per viewer (localStorage, safe when blocked).
 *     Off keeps the ruby but hides the readings (jp-furigana-off); a click on one word still shows its reading.
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathAutoFurigana = api;
  if (root && root.document && typeof root.document.querySelector === 'function') {
    var start = function () {
      var page = root.document.querySelector('[data-jpath-auto-furigana]');
      if (page) api.mount(page, {});
    };
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', start);
    else start();
  }
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var URL = '/api/learning/jlpt/furigana';
  var KEY = 'jpath.skill.furigana';
  var KANJI = /[㐀-鿿々ヶ]/;
  var BATCH = 100;

  function F() {
    return (root && root.JpathFurigana) || (typeof require === 'function' ? require('./jpath-furigana.js') : null);
  }
  function icon(name) {
    var nav = (root && root.JpathNav) || (typeof require === 'function' ? require('./jpath-nav.js') : null);
    return nav && nav.iconHtml ? nav.iconHtml(name) : '';
  }

  function toggleHtml(on) {
    return '<button type="button" class="jp-furi-toggle" data-furi-toggle aria-pressed="' + (on ? 'true' : 'false') + '">'
      + icon('languages') + '<span>Furigana</span><span class="jp-furi-toggle__state">' + (on ? 'Bật' : 'Tắt') + '</span></button>';
  }

  function onlyText(el) {
    var kids = el.childNodes || [];
    for (var i = 0; i < kids.length; i++) if (kids[i].nodeType !== 3) return false;
    return true;
  }

  function targets(rootEl) {
    var all = rootEl.querySelectorAll ? rootEl.querySelectorAll('[lang="ja"]') : [];
    var out = [];
    Array.prototype.forEach.call(all, function (el) {
      if (el.hasAttribute('data-furi')) return;
      if (!onlyText(el)) return;
      if (!KANJI.test(el.textContent || '')) { el.setAttribute('data-furi', 'none'); return; }
      out.push(el);
    });
    return out;
  }

  function read(storage) {
    try { return storage && storage.getItem(KEY) === 'off' ? false : true; } catch (e) { return true; }
  }
  function write(storage, on) {
    try { if (storage) storage.setItem(KEY, on ? 'on' : 'off'); } catch (e) { /* per-viewer convenience only */ }
  }

  function mount(rootEl, deps) {
    var d = deps || {};
    var apiClient = d.api || (root && root.JpathApi);
    var storage = d.storage !== undefined ? d.storage : (function () { try { return root.localStorage; } catch (e) { return null; } })();
    var doc = d.document || (root && root.document);
    if (!rootEl || !apiClient) return null;
    var on = read(storage);
    var cache = {};
    var busy = false;
    var again = false;

    var slot = d.slot || rootEl.querySelector('.jp-page-head') || rootEl;
    var holder = doc && doc.createElement ? doc.createElement('div') : null;
    if (holder) {
      holder.className = 'jp-furi-toggle-wrap';
      holder.innerHTML = toggleHtml(on);
      slot.appendChild(holder);
    }

    function paint() {
      if (on) rootEl.classList.remove('jp-furigana-off');
      else rootEl.classList.add('jp-furigana-off');
      var btn = holder && holder.querySelector('[data-furi-toggle]');
      if (btn) {
        btn.setAttribute('aria-pressed', on ? 'true' : 'false');
        var st = btn.querySelector('.jp-furi-toggle__state');
        if (st) st.textContent = on ? 'Bật' : 'Tắt';
      }
    }

    function draw(el, markup) {
      el.setAttribute('data-furi', markup ? 'done' : 'none');
      if (markup) el.innerHTML = F().toRuby(markup);
    }

    function annotate() {
      if (busy) { again = true; return Promise.resolve(); }
      var els = targets(rootEl);
      var ask = [];
      els.forEach(function (el) {
        var text = el.textContent;
        if (Object.prototype.hasOwnProperty.call(cache, text)) draw(el, cache[text]);
        else if (ask.indexOf(text) < 0) ask.push(text);
      });
      if (!ask.length) return Promise.resolve();
      busy = true;
      var chunks = [];
      for (var i = 0; i < ask.length; i += BATCH) chunks.push(ask.slice(i, i + BATCH));
      return Promise.all(chunks.map(function (texts) {
        return apiClient.send('POST', URL, { texts: texts }).then(function (res) {
          texts.forEach(function (t, k) { cache[t] = res && res[k] ? res[k] : null; });
        }, function () { texts.forEach(function (t) { cache[t] = null; }); });
      })).then(function () {
        busy = false;
        targets(rootEl).forEach(function (el) {
          if (Object.prototype.hasOwnProperty.call(cache, el.textContent)) draw(el, cache[el.textContent]);
        });
        if (again) { again = false; return annotate(); }
        return null;
      });
    }

    if (holder) holder.addEventListener('click', function (ev) {
      if (!ev.target.closest('[data-furi-toggle]')) return;
      on = !on;
      write(storage, on);
      paint();
    });
    // a click on one word shows (or hides) just its reading
    rootEl.addEventListener('click', function (ev) {
      var ruby = ev.target && typeof ev.target.closest === 'function' ? ev.target.closest('ruby') : null;
      if (ruby && !ev.target.closest('button')) ruby.classList.toggle('jp-rt-flip');
    });
    if (root && typeof root.MutationObserver === 'function') {
      var timer = null;
      new root.MutationObserver(function () {
        if (timer) root.clearTimeout(timer);
        timer = root.setTimeout(annotate, 60);
      }).observe(rootEl, { childList: true, subtree: true });
    }
    paint();
    var first = annotate();
    return { annotate: annotate, isOn: function () { return on; }, ready: first };
  }

  return { toggleHtml: toggleHtml, targets: targets, mount: mount, KEY: KEY };
});
