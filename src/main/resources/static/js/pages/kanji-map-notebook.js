window.KanjiMapNotebook = {
  create: function (options) {
    'use strict';
    var $ = function (id) { return document.getElementById('km-notebook-' + id); };
    var books = [], selected = null, saved = new Set(), pending = [], busy = false, generation = 0;
    var request = options.request, base = '/api/dictionary/notebooks';
    function key() {
      var user = window.JlasAuth && window.JlasAuth.getUser();
      var identity = user && (user.id || user.userId || user.email);
      return identity ? 'jlas-kanji-notebook:' + identity : null;
    }
    function remembered() { try { return key() ? localStorage.getItem(key()) : null; } catch (_) { return null; } }
    function remember() { try { if (key()) localStorage.setItem(key(), selected.id); } catch (_) {} }
    function changed() { options.onChange(); }
    function error(text) { $('error').textContent = text || ''; $('error').hidden = !text; }
    function updateLink() { $('link').href = '/learning/notebook' + (selected ? '?book=' + selected.id : ''); }
    function render() {
      var select = $('select'); select.replaceChildren();
      books.forEach(function (book) {
        var option = document.createElement('option'); option.value = book.id; option.textContent = book.name; select.appendChild(option);
      });
      if (selected) select.value = selected.id;
      $('characters').textContent = pending.join(' · ');
      $('characters').hidden = !pending.length;
      $('save').hidden = !pending.length;
      $('save').disabled = busy || !selected;
      $('select').disabled = busy;
      updateLink();
    }
    async function choose(id) {
      var revision = ++generation;
      var book = books.find(function (b) { return String(b.id) === String(id); });
      selected = book || null; saved = new Set(); changed(); updateLink();
      if (!selected) return;
      var entries = await request(base + '/' + selected.id + '/kanji');
      if (revision !== generation) return;
      saved = new Set(entries.map(function (entry) { return entry.character; })); changed();
    }
    async function load() {
      if (!options.loggedIn()) return;
      books = await request(base);
      var id = selected && selected.id || remembered();
      await choose(id);
    }
    async function open(characters) {
      if (!options.loggedIn()) { options.notice('Đăng nhập để lưu Kanji vào sổ tay.', true); return; }
      pending = Array.from(new Set(characters || [])); busy = true; error('');
      $('dialog').showModal(); render();
      try {
        await load();
        if (!selected && books.length) await choose((books.find(function (b) { return b.type === 'PERSONAL'; }) || books[0]).id);
      } catch (e) { error(e.message); }
      finally { busy = false; render(); }
    }
    async function persist(characters) {
      await request(base + '/' + selected.id + '/kanji', {method:'POST', headers:Object.assign({}, options.headers(), {'Content-Type':'application/json'}), body:JSON.stringify({characters:characters})});
      characters.forEach(function (ch) { saved.add(ch); }); remember(); changed();
    }
    async function save(characters) {
      var unique = Array.from(new Set(characters));
      if (!options.loggedIn()) { options.notice('Đăng nhập để lưu Kanji vào sổ tay.', true); return; }
      if (busy) return;
      if (!selected) { await open(unique); return; }
      busy = true; changed();
      try {
        await persist(unique); options.notice('Đã lưu vào ' + selected.name + '.');
      } catch (e) {
        selected = null; saved.clear(); options.notice(e.message); changed();
      } finally { busy = false; changed(); }
    }
    $('close').addEventListener('click', function () { if (!busy) $('dialog').close(); });
    $('dialog').addEventListener('cancel', function (e) { if (busy) e.preventDefault(); });
    $('select').addEventListener('change', async function () {
      var id = $('select').value;
      busy = true; error(''); render();
      try { await choose(id); } catch (e) { error(e.message); }
      finally { busy = false; render(); }
    });
    $('form').addEventListener('submit', async function (event) {
      event.preventDefault(); if (busy || !selected || !pending.length) return;
      busy = true; error(''); render();
      try {
        await persist(pending); $('dialog').close(); options.notice('Đã lưu vào ' + selected.name + '.');
      } catch (e) { error(e.message); }
      finally { busy = false; render(); changed(); }
    });
    $('create').addEventListener('click', async function () {
      var name = $('name').value.trim(); if (!name || busy) return;
      busy = true; error(''); render(); $('create').disabled = true;
      try {
        var book = await request(base, {method:'POST', headers:Object.assign({},options.headers(),{'Content-Type':'application/json'}), body:JSON.stringify({name:name})});
        books.push(book); await choose(book.id); $('name').value = '';
      } catch (e) { error(e.message); }
      finally { busy = false; $('create').disabled = false; render(); }
    });
    return { load:load, open:open, save:save,
      has:function (character) { return saved.has(character); },
      busy:function () { return busy; },
      book:function () { return selected; }
    };
  }
};
