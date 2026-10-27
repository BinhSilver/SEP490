window.KanjiMapNotebook = {
  create: function (options) {
    'use strict';
    var $ = function (id) { return document.getElementById('km-notebook-' + id); };
    var books = [], selected = null, saved = new Set(), pending = [], busy = false, ready = false, generation = 0;
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
        var option = document.createElement('label'); option.className = 'km-notebook-option';
        var radio = document.createElement('input'); radio.type = 'radio'; radio.name = 'km-notebook-choice';
        radio.value = book.id; radio.checked = !!selected && String(selected.id) === String(book.id); radio.disabled = busy;
        var icon = document.createElement('span'); icon.className = 'km-notebook-option-icon'; icon.setAttribute('aria-hidden', 'true');
        icon.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><path d="M5 3h13a1 1 0 0 1 1 1v17H6a3 3 0 0 1-3-3V5a2 2 0 0 1 2-2Z"/><path d="M3 17h16M8 3v14M11 7h5M11 10h4"/></svg>';
        var text = document.createElement('span'); text.className = 'km-notebook-option-text';
        var title = document.createElement('strong'); title.textContent = book.name;
        var meta = document.createElement('small'); meta.textContent = (book.wordCount || 0) + ' từ vựng · ' + (book.kanjiCount || 0) + ' Kanji';
        text.append(title, meta);
        var check = document.createElement('span'); check.className = 'km-notebook-option-check'; check.textContent = '✓'; check.setAttribute('aria-hidden', 'true');
        option.append(radio, icon, text, check); select.appendChild(option);
      });
      if (!books.length) {
        var empty = document.createElement('p'); empty.className = 'km-notebook-empty';
        empty.textContent = busy ? 'Đang tải sổ tay…' : 'Chưa có sổ tay. Tạo một sổ mới ở bên dưới để bắt đầu.'; select.appendChild(empty);
      }
      select.setAttribute('aria-busy', String(busy));
      $('characters').textContent = pending.join(' · ');
      $('characters').hidden = !pending.length;
      $('save').hidden = !pending.length;
      $('save').disabled = busy || !selected || !ready;
      $('context').textContent = busy ? 'Đang xử lý…' : selected && ready ? 'Sổ nhận: ' + selected.name : 'Chọn sổ tay để lưu Kanji.';
      $('create').disabled = busy; $('name').disabled = busy; $('close').disabled = busy;
      updateLink();
    }
    async function choose(id) {
      var revision = ++generation;
      var book = books.find(function (b) { return String(b.id) === String(id); });
      selected = book || null; saved = new Set(); ready = false; changed(); updateLink();
      if (!selected) return;
      var entries = await request(base + '/' + selected.id + '/kanji');
      if (revision !== generation) return;
      saved = new Set(entries.map(function (entry) { return entry.character; })); ready = true; changed();
    }
    async function load() {
      if (!options.loggedIn()) return;
      books = await request(base);
      var id = selected && selected.id || remembered();
      await choose(id);
    }
    async function open(characters) {
      if (!options.loggedIn()) { options.notice('Đăng nhập để lưu Kanji vào sổ tay.', true); return; }
      if (busy) return;
      pending = Array.from(new Set(characters || [])); busy = true; ready = false; error('');
      $('create-section').open = false; $('name').value = '';
      if (!$('dialog').open) $('dialog').showModal(); render();
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
      if (!selected || !ready) { await open(unique); return; }
      busy = true; changed();
      try {
        await persist(unique); options.notice('Đã lưu vào ' + selected.name + '.');
      } catch (e) {
        selected = null; saved.clear(); options.notice(e.message); changed();
      } finally { busy = false; changed(); }
    }
    $('close').addEventListener('click', function () { if (!busy) $('dialog').close(); });
    $('dialog').addEventListener('cancel', function (e) { if (busy) e.preventDefault(); });
    $('select').addEventListener('change', async function (event) {
      var id = event.target.value;
      if (!id || busy) return;
      busy = true; error(''); render();
      try { await choose(id); } catch (e) { error(e.message); }
      finally { busy = false; render(); }
      var checked = $('select').querySelector('input:checked'); if (checked) checked.focus();
    });
    $('form').addEventListener('submit', async function (event) {
      event.preventDefault(); if (busy || !selected || !ready || !pending.length) return;
      busy = true; error(''); render();
      try {
        await persist(pending); $('dialog').close(); options.notice('Đã lưu vào ' + selected.name + '.');
      } catch (e) { error(e.message); }
      finally { busy = false; render(); changed(); }
    });
    $('create-form').addEventListener('submit', async function (event) {
      event.preventDefault(); if (busy) return;
      var name = $('name').value.trim(); if (!name) { error('Nhập tên cho sổ tay mới.'); $('name').focus(); return; }
      busy = true; error(''); render(); $('create').disabled = true;
      try {
        var book = await request(base, {method:'POST', headers:Object.assign({},options.headers(),{'Content-Type':'application/json'}), body:JSON.stringify({name:name})});
        books.push(book); await choose(book.id); $('name').value = ''; $('create-section').open = false;
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
