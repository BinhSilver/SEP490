/* Manual create / edit — Quizlet-like rows */
(function () {
  'use strict';

  var params = new URLSearchParams(location.search);
  var setId = params.get('id') ? Number(params.get('id')) : null;
  var viewOnly = params.get('view') === '1';
  var cards = [];
  var ownedByMe = true;
  var validationTimer = null;
  var realtimeValid = true;
  var autosaveTimer = null;
  var autosavePromise = null;
  var autosaveQueued = false;
  var hasUnsavedChanges = false;
  var lastDraftSnapshot = '';
  var pendingAiChanges = [];
  var editorFolders = [];

  document.addEventListener('DOMContentLoaded', async function () {
    if (!viewOnly && !FlashShell.requireLogin('Đăng nhập để tạo / sửa bộ thẻ')) return;

    var addBtnTop = document.getElementById('btn-add-card');
    if (addBtnTop) addBtnTop.addEventListener('click', addCardAndFocus);
    document.getElementById('btn-add-card-bottom').addEventListener('click', addCardAndFocus);
    document.getElementById('btn-save').addEventListener('click', save);
    ['set-title', 'set-desc', 'set-visibility', 'set-folder'].forEach(function (id) {
      document.getElementById(id).addEventListener(id === 'set-visibility' ? 'change' : 'input', function () {
        scheduleRealtimeValidation();
        scheduleDraftSave();
      });
    });
    document.getElementById('set-folder').addEventListener('change', scheduleDraftSave);
    document.getElementById('btn-create-folder').addEventListener('click', createFolder);
    document.getElementById('btn-gen-batch').addEventListener('click', genBatch);
    document.getElementById('btn-delete-batch').addEventListener('click', deleteBatch);
    document.getElementById('btn-appeal').addEventListener('click', function () {
      document.getElementById('appeal-modal').hidden = false;
      document.getElementById('appeal-reason').focus();
    });
    document.getElementById('cancel-appeal').addEventListener('click', function () {
      document.getElementById('appeal-modal').hidden = true;
    });
    document.getElementById('submit-appeal').addEventListener('click', submitAppeal);
    document.getElementById('chk-all').addEventListener('change', function (e) {
      cards.forEach(function (c) { c._selected = e.target.checked; });
      render();
    });

    // BR-11 — Pre-validation realtime cho set-title & set-desc
    if (typeof PreValidation !== 'undefined') {
      PreValidation.bind(document.getElementById('set-title'), ['required', 'min:3', 'max:100']);
      PreValidation.bind(document.getElementById('set-desc'), 'max:500');
    }

    // Nhập từ — modal
    document.getElementById('btn-import').addEventListener('click', openImport);
    document.getElementById('btn-close-import').addEventListener('click', closeImport);
    document.getElementById('btn-cancel-import').addEventListener('click', closeImport);
    document.getElementById('btn-import-ai').addEventListener('click', importGenerateAi);
    document.getElementById('import-text').addEventListener('input', function () {
      clearImportAiPreview();
      updateImportPreview();
    });
    document.getElementById('btn-import-file').addEventListener('click', function () {
      document.getElementById('import-file').click();
    });
    document.getElementById('import-file').addEventListener('change', importFromFile);
    setupImportSeparators();
    document.getElementById('btn-apply-import').addEventListener('click', applyImport);
    document.getElementById('btn-close-ai-review').addEventListener('click', closeAiReview);
    document.getElementById('btn-cancel-ai-review').addEventListener('click', closeAiReview);
    document.getElementById('btn-apply-ai-review').addEventListener('click', applyAiReview);

    await loadFolders();
    if (setId) {
      await loadSet(setId);
    } else {
      cards = [{ front: '', back: '', hanViet: '', image: '', _selected: false }];
      render();
    }

    if (viewOnly) {
      document.getElementById('btn-save').hidden = true;
      if (addBtnTop) addBtnTop.hidden = true;
      document.getElementById('btn-add-card-bottom').hidden = true;
      document.getElementById('btn-gen-batch').hidden = true;
      document.getElementById('btn-import').hidden = true;
      document.getElementById('draft-save-status').hidden = true;
      document.getElementById('editor-title').innerHTML =
        '<i class="fa-solid fa-eye"></i> Xem bộ thẻ';
    }
    window.addEventListener('beforeunload', function (event) {
      if (!hasUnsavedChanges && !autosavePromise) return;
      event.preventDefault();
      event.returnValue = '';
    });
    window.addEventListener('pagehide', function () {
      if (!hasUnsavedChanges || !setId || viewOnly) return;
      var headers = window.JlasAuth && typeof JlasAuth.authHeaders === 'function'
        ? JlasAuth.authHeaders()
        : { 'Content-Type': 'application/json' };
      var token = localStorage.getItem('accessToken');
      if (token && !headers.Authorization) headers.Authorization = 'Bearer ' + token;
      fetch('/api/flashcards/' + setId + '/draft', {
        method: 'PUT',
        headers: headers,
        body: JSON.stringify(buildDraftPayload()),
        keepalive: true
      }).catch(function () {});
    });
  });

  async function loadFolders(selectedId) {
    try {
      editorFolders = await FlashApi.get('/api/flashcards/folders') || [];
      var select = document.getElementById('set-folder');
      select.innerHTML = '<option value="0">Chưa phân loại</option>' + editorFolders.map(function (folder) {
        return '<option value="' + folder.id + '">' + FlashShell.esc(folder.name) + '</option>';
      }).join('');
      select.value = selectedId == null ? (select.value || '0') : String(selectedId || 0);
    } catch (e) {
      editorFolders = [];
    }
  }

  async function createFolder() {
    var name = await FlashShell.requestFolderName();
    if (!name || !name.trim()) return;
    try {
      var folder = await FlashApi.post('/api/flashcards/folders', { name: name.trim() });
      await loadFolders(folder.id);
      scheduleDraftSave();
    } catch (e) {
      FlashShell.showToast(e.message || 'Không tạo được thư mục', 'err');
    }
  }

  async function loadSet(id) {
    try {
      var set = await FlashApi.get('/api/flashcards/' + id);
      document.getElementById('set-title').value = set.title || '';
      document.getElementById('set-desc').value = set.description || '';
      document.getElementById('set-visibility').value = set.visibility || 'PRIVATE';
      document.getElementById('set-folder').value = String(set.folderId || 0);
      ownedByMe = !!set.ownedByMe;
      setDraftStatus(set.draft ? 'Đã tải bản nháp' : 'Đã lưu', 'saved');
      document.getElementById('btn-appeal').hidden = !ownedByMe || (set.moderationStatus !== 'REVIEW' && set.moderationStatus !== 'BLOCKED');
      cards = (set.cards || []).map(function (c) {
        return {
          id: c.id,
          front: c.front || '',
          back: c.back || '',
          reading: c.reading || c.hiragana || '',
          meaning: c.meaning || '',
          hanViet: c.hanViet || '',
          image: c.image || '',
          _selected: false
        };
      });
      if (!cards.length) cards.push({ front: '', back: '', hanViet: '', image: '', _selected: false });
      lastDraftSnapshot = JSON.stringify(buildDraftPayload());
      document.getElementById('editor-title').innerHTML =
        '<i class="fa-solid fa-pen-to-square"></i> ' + FlashShell.esc(set.title || 'Sửa bộ thẻ');
      var studyBtn = document.getElementById('btn-study');
      studyBtn.hidden = !!set.draft;
      studyBtn.href = '/flashcard/study.html?setId=' + id;
      if (!ownedByMe) {
        viewOnly = true;
        document.getElementById('btn-save').hidden = true;
        if (addBtnTop) addBtnTop.hidden = true;
        document.getElementById('btn-add-card-bottom').hidden = true;
        document.getElementById('btn-gen-batch').hidden = true;
        document.getElementById('btn-import').hidden = true;
      }
      render();
    } catch (e) {
      FlashShell.showToast(e.message || 'Không tải được bộ', 'err');
    }
  }

  // A word without kanji (katakana loanword like コーヒー, or a hiragana-only word) has no Kanji and no
  // Hán Việt: its front is simply the reading. Same rule as the server (com.jlas.flashcard.util.KanaWords).
  var HAN_CHAR = /\p{Script=Han}/u;
  var KANA_ONLY = /^[\u3040-\u309f\u30a0-\u30ff\u30fc\u30fb\s]+$/;
  function hasKanji(value) { return HAN_CHAR.test(String(value || '')); }
  function isKanaWord(value) {
    var v = String(value || '').trim();
    return Boolean(v) && !hasKanji(v) && KANA_ONLY.test(v);
  }
  /** Fills the reading of a kana word from its front. Only used when the learner presses an AI button. */
  function applyKanaRule(card) {
    if (!isKanaWord(card.front)) return false;
    ensureBackFields(card);
    if (card.reading && card.reading.trim()) return false;
    card.reading = card.front.trim();
    syncBack(card);
    return true;
  }

  /**
   * Fields the AI could still fill for this card. A kana word needs no Hán Việt and gets its reading
   * locally, so only its meaning can be missing. An empty list means: do not call the AI at all.
   */
  function aiMissingFields(card) {
    ensureBackFields(card);
    var missing = [];
    var kana = isKanaWord(card.front);
    if (!kana && !(card.reading && card.reading.trim())) missing.push('reading');
    if (!(card.meaning && card.meaning.trim())) missing.push('meaning');
    if (hasKanji(card.front) && !(card.hanViet && card.hanViet.trim())) missing.push('hanViet');
    return missing;
  }

  function splitBack(value) {
    var back = String(value || '').trim();
    var parts = back.split(/\s+[—-]\s+/, 2);
    return parts.length > 1
      ? { reading: parts[0].trim(), meaning: parts[1].trim() }
      : { reading: '', meaning: back };
  }

  function ensureBackFields(card) {
    if (typeof card.reading === 'string' && typeof card.meaning === 'string') return;
    var parts = splitBack(card.back);
    card.reading = parts.reading;
    card.meaning = parts.meaning;
  }

  function syncBack(card) {
    card.back = [card.reading, card.meaning].filter(function (value) {
      return value && value.trim();
    }).join(' — ');
  }

  function setBack(card, value) {
    card.back = value || '';
    var parts = splitBack(card.back);
    card.reading = parts.reading;
    card.meaning = parts.meaning;
  }

  function render() {
    var list = document.getElementById('card-list');
    var ro = viewOnly ? 'readonly' : '';
    var dis = viewOnly ? 'disabled' : '';
    document.getElementById('card-count').textContent = cards.length + (cards.length === 1 ? ' thẻ' : ' thẻ');
    list.innerHTML =
      cards.map(function (c, i) {
          ensureBackFields(c);
          return (
            '<article class="edit-card" data-i="' + i + '">' +
              '<div class="edit-card__head">' +
                '<div class="edit-card__identity">' +
                  '<span class="edit-card__number">' + String(i + 1).padStart(2, '0') + '</span>' +
                  '<label class="edit-card__select"><input aria-label="Chọn thẻ ' + (i + 1) + '" type="checkbox" data-sel="' + i + '" ' + (c._selected ? 'checked' : '') + ' ' + dis + '/> Chọn</label>' +
                '</div>' +
                '<div class="edit-card__actions">' +
                  (!viewOnly ? '<button type="button" class="table-icon-btn table-icon-btn--ai" data-ai="' + i + '" title="AI gợi ý cách đọc, nghĩa và Hán Việt"><i class="fa-solid fa-wand-magic-sparkles"></i> AI điền</button>' : '') +
                  (!viewOnly ? '<button type="button" class="table-icon-btn table-icon-btn--danger" data-rm="' + i + '" title="Xóa thẻ"><i class="fa-solid fa-trash-can"></i><span class="sr-only">Xóa thẻ</span></button>' : '') +
                '</div>' +
              '</div>' +
              '<div class="edit-card__fields edit-card__fields--primary">' +
                '<div class="edit-card__field"><label class="label" for="card-front-' + i + '">Từ vựng <span class="required-mark">*</span></label><div class="input-wrap"><input id="card-front-' + i + '" class="input" aria-label="Từ vựng thẻ ' + (i + 1) + '" title="Kanji, hoặc Katakana/Hiragana nếu từ không có Kanji" data-f="front" data-i="' + i + '" type="text" placeholder="例: 日本 / コーヒー" value="' + FlashShell.esc(c.front) + '" ' + ro + '/></div></div>' +
                '<div class="edit-card__field"><label class="label" for="card-reading-' + i + '">Cách đọc <span class="optional-label">Tùy chọn</span></label><div class="input-wrap"><input id="card-reading-' + i + '" class="input" aria-label="Cách đọc thẻ ' + (i + 1) + '" data-f="reading" data-i="' + i + '" type="text" placeholder="にほん" value="' + FlashShell.esc(c.reading) + '" ' + ro + '/></div></div>' +
                '<div class="edit-card__field"><label class="label" for="card-meaning-' + i + '">Nghĩa <span class="required-mark">*</span></label><div class="input-wrap"><input id="card-meaning-' + i + '" class="input" aria-label="Nghĩa thẻ ' + (i + 1) + '" data-f="meaning" data-i="' + i + '" type="text" placeholder="Nhật Bản" value="' + FlashShell.esc(c.meaning) + '" ' + ro + '/></div></div>' +
              '</div>' +
              '<div class="edit-card__fields edit-card__fields--secondary">' +
                '<div class="edit-card__field"><label class="label" for="card-hanviet-' + i + '">Hán Việt <span class="optional-label">Tùy chọn</span></label><div class="input-wrap"><input id="card-hanviet-' + i + '" class="input" aria-label="Hán Việt thẻ ' + (i + 1) + '" data-f="hanViet" data-i="' + i + '" type="text" placeholder="' + (isKanaWord(c.front) ? 'Từ không có Kanji nên không có Hán Việt' : 'NHẬT BẢN') + '" value="' + FlashShell.esc(c.hanViet) + '" ' + ro + '/></div></div>' +
                '<div class="edit-card__field edit-card__field--image"><label class="label">Ảnh <span class="optional-label">Tùy chọn</span></label><div class="edit-card__image-control">' +
                  (c.image ? '<img src="' + FlashShell.esc(c.image) + '" class="edit-thumb" alt="Ảnh thẻ ' + (i + 1) + '"/>' : '') +
                  (!viewOnly ? '<label class="table-icon-btn" title="Thêm ảnh minh họa"><i class="fa-solid fa-image"></i> ' + (c.image ? 'Đổi ảnh' : 'Thêm ảnh') + '<input type="file" accept="image/*" data-img="' + i + '"/></label>' : '') +
                  (c.image && !viewOnly ? '<button type="button" class="table-icon-btn" data-rmimg="' + i + '" title="Xóa ảnh"><i class="fa-solid fa-image-slash"></i><span class="sr-only">Xóa ảnh</span></button>' : '') +
                '</div></div>' +
              '</div>' +
            '</article>'
          );
        }).join('');

    list.querySelectorAll('[data-f]').forEach(function (inp) {
      inp.addEventListener('input', function () {
        var i = Number(inp.getAttribute('data-i'));
        var f = inp.getAttribute('data-f');
        if (f === 'reading' || f === 'meaning') {
          cards[i][f] = inp.value;
          syncBack(cards[i]);
        } else {
          cards[i][f] = inp.value;
        }
        scheduleRealtimeValidation();
        scheduleDraftSave();
        // BR-11 — realtime pre-validation
        if (f === 'front') {
          if (!inp.value.trim()) {
            inp.classList.add('is-invalid');
            var wrap = inp.closest('.input-wrap');
            if (wrap) wrap.classList.add('is-invalid');
          } else {
            inp.classList.remove('is-invalid');
            var wrap2 = inp.closest('.input-wrap');
            if (wrap2) wrap2.classList.remove('is-invalid');
          }
        }
      });
      inp.addEventListener('keydown', function (event) {
        if (event.key !== 'Enter' || event.shiftKey || viewOnly) return;
        event.preventDefault();
        var fields = ['front', 'reading', 'meaning', 'hanViet'];
        var current = fields.indexOf(inp.getAttribute('data-f'));
        var row = inp.getAttribute('data-i');
        if (current < fields.length - 1) {
          var next = list.querySelector('[data-f="' + fields[current + 1] + '"][data-i="' + row + '"]');
          if (next) next.focus();
        } else {
          addCardAndFocus();
        }
      });
    });
    list.querySelectorAll('[data-sel]').forEach(function (chk) {
      chk.addEventListener('change', function () {
        cards[Number(chk.getAttribute('data-sel'))]._selected = chk.checked;
        updateBatchToolbar();
      });
    });
    list.querySelectorAll('[data-rm]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        cards.splice(Number(btn.getAttribute('data-rm')), 1);
        render();
        scheduleDraftSave();
      });
    });
    list.querySelectorAll('[data-ai]').forEach(function (btn) {
      btn.addEventListener('click', function () { genOne(Number(btn.getAttribute('data-ai'))); });
    });
    list.querySelectorAll('[data-img]').forEach(function (inp) {
      inp.addEventListener('change', function () { uploadImg(Number(inp.getAttribute('data-img')), inp.files[0]); });
    });
    list.querySelectorAll('[data-rmimg]').forEach(function (btn) {
      btn.addEventListener('click', function () {
        cards[Number(btn.getAttribute('data-rmimg'))].image = '';
        render();
        scheduleDraftSave();
      });
    });

    updateBatchToolbar();
  }

  function updateBatchToolbar() {
    var selectedCards = cards.filter(function (c) { return c._selected; });
    var count = selectedCards.length;
    var batchGroup = document.getElementById('batch-actions');
    var badge = document.getElementById('selected-count-badge');
    var deleteBtn = document.getElementById('btn-delete-batch');
    var chkAll = document.getElementById('chk-all');

    if (chkAll) {
      chkAll.checked = cards.length > 0 && count === cards.length;
      chkAll.indeterminate = count > 0 && count < cards.length;
    }

    if (batchGroup) {
      if (count > 0 && !viewOnly) {
        batchGroup.hidden = false;
        if (badge) badge.textContent = 'Đã chọn ' + count;
        if (deleteBtn) deleteBtn.innerHTML = '<i class="fa-solid fa-trash"></i> Xóa (' + count + ') đã chọn';
      } else {
        batchGroup.hidden = true;
      }
    }
  }

  function deleteBatch() {
    var selectedCards = cards.filter(function (c) { return c._selected; });
    var selectedCount = selectedCards.length;
    if (!selectedCount) return;
    if (!confirm('Bạn có chắc muốn xóa ' + selectedCount + ' thẻ đã chọn?')) return;

    cards = cards.filter(function (c) { return !c._selected; });
    if (!cards.length) {
      cards.push({ front: '', back: '', hanViet: '', image: '', _selected: false });
    }
    render();
    scheduleDraftSave();
    FlashShell.showToast('Đã xóa ' + selectedCount + ' thẻ', 'ok');
  }

  function addCardAndFocus() {
    cards.push({ front: '', back: '', hanViet: '', image: '', _selected: false });
    render();
    scheduleDraftSave();
    var row = cards.length - 1;
    window.setTimeout(function () {
      var input = document.querySelector('[data-f="front"][data-i="' + row + '"]');
      if (input) input.focus();
    }, 0);
  }

  async function uploadImg(i, file) {
    if (!file) return;
    try {
      var res = await FlashApi.upload('/api/uploads/image', file);
      cards[i].image = res.url;
      render();
      scheduleDraftSave();
      FlashShell.showToast('Đã tải ảnh', 'ok');
    } catch (e) {
      FlashShell.showToast(e.message || 'Tải ảnh thất bại', 'err');
    }
  }

  /**
   * Kanji = key. AI fills missing Back (reading — nghĩa) + Hán Việt.
   * Only meaning or only Hán Việt  rejected.
   */
  async function enrichCardFromKanji(c) {
    if (!c.front || !c.front.trim()) {
      throw new Error('Cần nhập từ vựng ở mặt trước.');
    }
    // Preview must be non-mutating: saved cards use the same lookup endpoint
    // and are persisted only after the learner accepts the result below.
    return FlashApi.post('/api/flashcards/cards/enrich', { kanji: c.front.trim() });
  }

  function compactTerm(value) {
    return String(value || '').replace(/\s+/g, '').trim();
  }

  function looksLikeJapaneseReading(value) {
    return !value || /^[\u3040-\u309f\u30a0-\u30ff\u30fc\u30fb\s]+$/.test(value);
  }

  function prepareAiChange(card, result) {
    var originalKanji = String(card.front || '').trim();
    var returnedKanji = String(result && result.front || '').trim();
    var reading = String(result && (result.reading || result.hiragana) || '').trim();
    var meaning = String(result && (result.meaning || result.translation) || '').trim();
    var hanViet = String(result && (result.hanViet || result.han_viet) || '').trim();
    var warnings = [];

    var kanaWord = isKanaWord(originalKanji);
    if (kanaWord) {
      // No kanji → the reading is the word itself and there is no Hán Việt.
      reading = originalKanji;
      hanViet = '';
    }
    if (returnedKanji && compactTerm(returnedKanji) !== compactTerm(originalKanji)) {
      warnings.push('AI trả từ khác; hệ thống đã giữ nguyên từ gốc.');
    }
    if (reading && !looksLikeJapaneseReading(reading)) {
      warnings.push('Cách đọc AI trả về không phải kana thuần; hãy kiểm tra trước khi áp dụng.');
    }
    if (!hanViet && hasKanji(originalKanji)) warnings.push('AI không trả Hán Việt cho mục này.');

    return {
      card: card,
      kanji: originalKanji,
      result: { front: originalKanji, reading: reading, meaning: meaning, hanViet: hanViet },
      warnings: warnings,
      hasSuggestion: Boolean(reading || meaning || hanViet)
    };
  }

  function showAiReview(changes) {
    pendingAiChanges = changes.filter(function (change) { return change.hasSuggestion; });
    if (!pendingAiChanges.length) {
      FlashShell.showToast('AI không trả dữ liệu có thể áp dụng.', 'err');
      return;
    }

    var list = document.getElementById('ai-review-list');
    list.innerHTML = pendingAiChanges.map(function (change, index) {
      var warning = change.warnings.length
        ? '<p class="ai-review-item__warning"><i class="fa-solid fa-triangle-exclamation"></i> ' + FlashShell.esc(change.warnings.join(' ')) + '</p>'
        : '';
      return '<label class="ai-review-item">' +
        '<input type="checkbox" data-ai-review-index="' + index + '" ' + (change.warnings.length ? '' : 'checked') + ' />' +
        '<div class="ai-review-item__value"><span>Từ gốc</span><strong>' + FlashShell.esc(change.kanji) + '</strong></div>' +
        '<div class="ai-review-item__value"><span>Cách đọc AI</span><strong>' + FlashShell.esc(change.result.reading || '—') + '</strong></div>' +
        '<div class="ai-review-item__value"><span>Hán Việt AI</span><strong>' + FlashShell.esc(change.result.hanViet || '—') + '</strong></div>' +
        '<div class="ai-review-item__value ai-review-item__value--meaning"><span>Nghĩa AI</span><strong>' + FlashShell.esc(change.result.meaning || '—') + '</strong></div>' +
        warning +
      '</label>';
    }).join('');
    document.getElementById('btn-apply-ai-review').innerHTML =
      '<i class="fa-solid fa-check"></i> Áp dụng kết quả đã chọn (' + pendingAiChanges.length + ')';
    document.getElementById('ai-review-modal').hidden = false;
  }

  function closeAiReview() {
    document.getElementById('ai-review-modal').hidden = true;
    pendingAiChanges = [];
  }

  function applyAiReview() {
    var chosen = Array.prototype.slice.call(document.querySelectorAll('[data-ai-review-index]:checked'));
    var changedCount = 0;
    chosen.forEach(function (checkbox) {
      var change = pendingAiChanges[Number(checkbox.getAttribute('data-ai-review-index'))];
      if (change && applyEnrichment(change.card, change.result)) changedCount++;
    });
    closeAiReview();
    if (!changedCount) {
      FlashShell.showToast('Không có ô trống mới để áp dụng. Dữ liệu hiện có được giữ nguyên.', 'info');
      return;
    }
    render();
    scheduleDraftSave();
    FlashShell.showToast('Đã áp dụng kết quả AI cho ' + changedCount + ' thẻ', 'ok');
  }

  // AI only fills blank fields. Kanji and data the learner already entered are
  // never overwritten without an explicit edit by the learner.
  function applyEnrichment(card, result) {
    if (!result) return false;
    ensureBackFields(card);
    var changed = false;
    var reading = result.reading || result.hiragana || '';
    var meaning = result.meaning || result.translation || '';
    var hanViet = result.hanViet || result.han_viet || '';
    if (isKanaWord(card.front)) {
      reading = card.front.trim();
      hanViet = '';
    }

    if (reading && !card.reading) {
        card.reading = reading;
        changed = true;
    }
    if (meaning && !card.meaning) {
        card.meaning = meaning;
        changed = true;
    }
    if (changed) syncBack(card);
    if (hanViet && !card.hanViet) {
      card.hanViet = hanViet;
      changed = true;
    }
    return changed;
  }

  async function genOne(i) {
    var c = cards[i];
    if (!c.front || !c.front.trim()) {
      FlashShell.showToast('Cần nhập từ vựng ở mặt trước.', 'err');
      return;
    }
    // A kana word's reading is the word itself: fill it here, no AI needed.
    var filledLocally = applyKanaRule(c);
    if (!aiMissingFields(c).length) {
      if (filledLocally) {
        render();
        scheduleDraftSave();
        FlashShell.showToast('Đã điền cách đọc. Thẻ đã đủ thông tin, không cần AI.', 'ok');
      } else {
        FlashShell.showToast('Thẻ đã đủ thông tin, không cần AI điền.', 'info');
      }
      return; // nothing left for the AI: never send a request
    }

    if (window.JlasUI && JlasUI.showAiProgress) {
      JlasUI.showAiProgress({
        title: 'AI điền ô trống 🌸',
        category: 'FLASHCARD AI',
        subtitle: 'Đang suy luận và tạo Nghĩa & Hán Việt cho: ' + c.front,
        total: 1,
        unit: 'thẻ',
        current: 0,
        percent: 25,
        status: 'Đang gửi yêu cầu đến AI...'
      });
    }

    try {
      if (window.JlasUI && JlasUI.updateAiProgress) {
        JlasUI.updateAiProgress({ percent: 28, status: 'AI đang sinh dữ liệu cho ' + c.front });
      }

      var res = await enrichCardFromKanji(c);
      var change = prepareAiChange(c, res);
      if (!change.hasSuggestion) {
        throw new Error('AI không trả dữ liệu mới để điền vào thẻ này.');
      }

      if (window.JlasUI && JlasUI.completeAiProgress) {
        JlasUI.completeAiProgress('Đã có kết quả AI 🌸', function () { showAiReview([change]); });
      } else {
        showAiReview([change]);
      }
    } catch (e) {
      if (window.JlasUI && JlasUI.hideAiProgress) JlasUI.hideAiProgress();
      FlashShell.showToast(e.message || 'Tạo bằng AI thất bại', 'err');
    }
  }

  async function genBatch() {
    var selected = cards.filter(function (c) { return c._selected; });
    if (!selected.length) {
      FlashShell.showToast('Chọn ít nhất một thẻ', 'err');
      return;
    }

    var withFront = selected.filter(function (c) { return c.front && c.front.trim(); });
    if (!withFront.length) {
      FlashShell.showToast('Cần nhập từ vựng ở mặt trước.', 'err');
      return;
    }
    // Kana readings are filled here; cards with nothing left to fill are not sent to the AI.
    var filledLocally = withFront.filter(applyKanaRule).length;
    var validTargets = withFront.filter(function (c) { return aiMissingFields(c).length > 0; });
    var skipCount = selected.length - validTargets.length;
    if (filledLocally) { render(); scheduleDraftSave(); }

    if (!validTargets.length) {
      FlashShell.showToast(filledLocally
        ? 'Đã điền cách đọc. Các thẻ đã chọn đều đủ thông tin, không cần AI.'
        : 'Các thẻ đã chọn đều đã đủ thông tin, không cần AI điền.', filledLocally ? 'ok' : 'info');
      return;
    }

    if (window.JlasUI && JlasUI.showAiProgress) {
      JlasUI.showAiProgress({
        title: 'AI điền ô hàng loạt 🌸',
        category: 'FLASHCARD BATCH AI',
        subtitle: 'Đang gửi ' + validTargets.length + ' từ đến AI để xử lý đồng thời...',
        total: validTargets.length,
        unit: 'thẻ',
        current: 0,
        percent: 25,
        status: 'AI đang phân tích ' + validTargets.length + ' từ...'
      });
    }

    try {
      var kanjiList = validTargets.map(function (c) { return c.front.trim(); });
      if (window.JlasUI && JlasUI.updateAiProgress) {
        JlasUI.updateAiProgress({ percent: 28, status: 'AI đang sinh dữ liệu cho toàn bộ thẻ...' });
      }

      var batchRes = await FlashApi.post('/api/flashcards/cards/enrich-batch', { kanjis: kanjiList });
      var changes = [];
      if (Array.isArray(batchRes)) {
        for (var i = 0; i < validTargets.length; i++) {
          var item = batchRes[i];
          if (item) {
            var change = prepareAiChange(validTargets[i], item);
            if (change.hasSuggestion) changes.push(change);
          }
        }
      }

      if (!changes.length) {
        throw new Error('AI did not return new data for the selected cards.');
      }

      if (window.JlasUI && JlasUI.completeAiProgress) {
        JlasUI.completeAiProgress('Đã có kết quả AI 🌸', function () { showAiReview(changes); });
      } else {
        showAiReview(changes);
      }
    } catch (e) {
      if (window.JlasUI && JlasUI.hideAiProgress) JlasUI.hideAiProgress();
      FlashShell.showToast(e.message || 'Tạo bằng AI thất bại', 'err');
    }
  }

  /* ── Nhập từ ───────────────────────────────────────────── */
  // Two paste-data modes (see import-separators.js header): 'pair' is Quizlet-style — one separator
  // between term and definition. 'quad' lets the user build the line template (which field comes after
  // which separator), since our card has 4 fields, not 2.
  var importSepState = {
    mode: 'pair',
    term: Object.assign({}, FlashImportSeparators.DEFAULT_TERM),
    card: Object.assign({}, FlashImportSeparators.DEFAULT_CARD),
    template: FlashImportSeparators.DEFAULT_TEMPLATE.map(function (slot) { return Object.assign({}, slot); })
  };

  function openImport() {
    document.getElementById('import-modal').hidden = false;
    document.getElementById('import-text').focus();
    updateImportPreview();
  }

  function closeImport() {
    importAiRequest++;
    setImportAiBusy(false);
    clearImportAiPreview();
    document.getElementById('import-modal').hidden = true;
  }

  function setupImportSeparators() {
    // "Tùy chỉnh" reveals a box for the user's own separator; the other choices hide it again.
    [['term', importSepState.term], ['card', importSepState.card]].forEach(function (pair) {
      var kind = pair[0], state = pair[1];
      var select = document.getElementById('import-' + kind + '-sep');
      var customInput = document.getElementById('import-' + kind + '-sep-custom');
      select.addEventListener('change', function () {
        state.type = select.value;
        customInput.hidden = select.value !== 'custom';
        if (select.value === 'custom') customInput.focus();
        updateImportPreview();
      });
      customInput.addEventListener('input', function () {
        state.custom = customInput.value;
        updateImportPreview();
      });
    });

    // Mode shows only the options relevant to it: 'pair' → term separator, 'quad' → line template;
    // data-import-mode="both" options always stay visible.
    document.getElementById('import-source').addEventListener('change', function (event) {
      setImportSource(event.target.value);
    });
    document.getElementById('import-mode').addEventListener('change', function (event) {
      setImportMode(event.target.value);
      updateImportPreview();
    });
    // Pasting switches the mode to what the data looks like; typing never does, so it can't flip mid-edit.
    document.getElementById('import-text').addEventListener('paste', function () {
      if (importSource !== 'manual') return;
      setTimeout(function () { autoImportMode(); updateImportPreview(); }, 0);
    });

    document.getElementById('btn-template-add').addEventListener('click', function () {
      var used = importSepState.template.map(function (slot) { return slot.field; });
      var free = FlashImportSeparators.FIELDS.filter(function (field) { return used.indexOf(field.value) < 0; })[0];
      if (!free) return;
      importSepState.template.push({ sep: '', field: free.value });
      renderImportTemplate();
      updateImportPreview();
    });
    renderImportTemplate();
  }

  function setImportMode(mode) {
    importSepState.mode = mode;
    document.getElementById('import-mode').value = mode;
    document.querySelectorAll('#import-modal [data-import-mode]').forEach(function (element) {
      element.hidden = !['both', mode].includes(element.dataset.importMode);
    });
  }

  /* Column data (spreadsheets, exported files) → 2 cột; "Kanji - Cách đọc : Nghĩa (Hán Việt)" lines → 4 trường. */
  function autoImportMode() {
    var detected = FlashImportSeparators.detectMode(document.getElementById('import-text').value, importSepState);
    if (!detected || detected === importSepState.mode) return;
    setImportMode(detected);
    FlashShell.showToast(detected === 'quad' ? 'Đã tự chuyển sang kiểu 4 trường theo dữ liệu' : 'Đã tự chuyển sang kiểu 2 cột theo dữ liệu', 'ok');
  }

  /* One row per slot: "[ký tự] → [trường]"; the first slot starts the line, so it has no separator. */
  function renderImportTemplate() {
    var slots = importSepState.template;
    var list = document.getElementById('import-template-slots');
    list.replaceChildren();
    slots.forEach(function (slot, index) {
      var item = document.createElement('li');
      item.className = 'import-template-slot';
      if (index === 0) {
        var start = document.createElement('span');
        start.className = 'import-template-start';
        start.textContent = 'Đầu dòng';
        item.appendChild(start);
      } else {
        var sep = document.createElement('input');
        sep.type = 'text';
        sep.className = 'input import-template-sep';
        sep.value = slot.sep;
        sep.placeholder = 'khoảng trắng';
        sep.setAttribute('aria-label', 'Ký tự đứng trước trường thứ ' + (index + 1));
        sep.addEventListener('input', function () {
          slot.sep = sep.value;
          updateImportPreview();
        });
        item.appendChild(sep);
      }
      var select = document.createElement('select');
      select.className = 'select import-template-field';
      select.setAttribute('aria-label', 'Trường thứ ' + (index + 1));
      FlashImportSeparators.FIELDS.forEach(function (field) {
        var option = document.createElement('option');
        option.value = field.value;
        option.textContent = field.label;
        option.selected = field.value === slot.field;
        select.appendChild(option);
      });
      select.addEventListener('change', function () {
        // Each field once per line: picking a field another slot holds swaps the two slots' fields.
        var taken = slots.filter(function (other) { return other !== slot && other.field === select.value; })[0];
        if (taken) taken.field = slot.field;
        slot.field = select.value;
        renderImportTemplate();
        updateImportPreview();
      });
      item.appendChild(select);
      if (index > 1) {
        var remove = document.createElement('button');
        remove.type = 'button';
        remove.className = 'btn-icon import-template-remove';
        remove.setAttribute('aria-label', 'Bỏ trường thứ ' + (index + 1));
        remove.innerHTML = '<i class="fa-solid fa-xmark" aria-hidden="true"></i>';
        remove.addEventListener('click', function () {
          slots.splice(index, 1);
          renderImportTemplate();
          updateImportPreview();
        });
        item.appendChild(remove);
      } else {
        // Rows that can't be removed keep an empty slot of the same width, so every field picker ends flush.
        var spacer = document.createElement('span');
        spacer.className = 'import-template-remove-slot';
        spacer.setAttribute('aria-hidden', 'true');
        item.appendChild(spacer);
      }
      list.appendChild(item);
    });
    document.getElementById('btn-template-add').hidden = slots.length >= FlashImportSeparators.MAX_SLOTS;
  }

  // Three independent ways in ("Cách nhập"): manual paste with the separator options, AI generation,
  // or a file. Files are always read as tab columns, whatever the manual separators are set to.
  var importSource = 'manual';
  var FILE_PARSE_OPTIONS = { mode: 'pair', term: { type: 'tab', custom: '' }, card: { type: 'newline', custom: '' } };

  function importParseOptions() {
    return importSource === 'file' ? FILE_PARSE_OPTIONS : importSepState;
  }

  function setImportSource(source) {
    importSource = source;
    document.getElementById('import-source').value = source;
    document.querySelector('#import-modal .modal-body').dataset.source = source;
    updateImportPreview();
  }

  function parseImportLines(text) {
    return FlashImportSeparators.parse(text, importParseOptions()).cards;
  }

  function updateImportPreview() {
    var text = document.getElementById('import-text').value;
    var result = FlashImportSeparators.parse(text, importParseOptions());
    var templateError = document.getElementById('import-template-error');
    templateError.hidden = !result.templateError;
    templateError.textContent = result.templateError;
    var countLabel = document.getElementById('import-preview-count');
    var list = document.getElementById('import-preview-list');
    countLabel.textContent = result.cards.length + ' thẻ'
      + (result.invalidCount ? ' · ' + result.invalidCount + ' dòng không nhận ra' : '');
    var issueLabel = document.getElementById('import-preview-issues');
    issueLabel.hidden = !result.issueCount;
    issueLabel.textContent = result.issueCount ? result.issueCount + ' dòng cần xem lại' : '';
    if (!result.cards.length) {
      list.replaceChildren();
      var empty = document.createElement('p');
      empty.className = 'muted';
      empty.textContent = 'Không có nội dung để xem trước';
      list.appendChild(empty);
      return;
    }
    // One column per card field, so a field landing in the wrong place is visible before applying.
    // Both modes fill the same four card fields, so the preview always shows all four.
    var columns = [['Kanji', 'front'], ['Cách đọc', 'reading'], ['Nghĩa', 'meaning'], ['Hán Việt', 'hanViet']];
    // The list scrolls, so show enough rows that a flagged one is never hidden behind "+ N thẻ khác".
    var SHOWN = 200;
    var cell = function (value, key, issue) {
      var text = String(value || '').trim();
      var classes = [text ? '' : 'is-empty', issue ? 'is-suspect' : ''].filter(Boolean).join(' ');
      return '<td' + (key === 'front' || key === 'reading' ? ' lang="ja"' : '') + (classes ? ' class="' + classes + '"' : '')
        + (issue ? ' title="' + FlashShell.esc(issue) + '"' : '') + '>'
        + (issue ? '<i class="fa-solid fa-triangle-exclamation" aria-hidden="true"></i> ' : '')
        + (text ? FlashShell.esc(text) : '—')
        + (issue ? '<span class="sr-only"> (' + FlashShell.esc(issue) + ')</span>' : '') + '</td>';
    };
    list.innerHTML = '<table class="import-preview-table" data-columns="' + columns.length + '"><thead><tr>'
      + columns.map(function (column) { return '<th scope="col">' + column[0] + '</th>'; }).join('')
      + '</tr></thead><tbody>'
      + result.cards.slice(0, SHOWN).map(function (card, index) {
        var issues = (result.issues && result.issues[index]) || {};
        return '<tr' + (Object.keys(issues).length ? ' class="has-issue"' : '') + '>'
          + columns.map(function (column) { return cell(card[column[1]], column[1], issues[column[1]]); }).join('') + '</tr>';
      }).join('') + '</tbody></table>'
      + (result.cards.length > SHOWN
        ? '<p class="muted import-preview-more">+ ' + (result.cards.length - SHOWN) + ' thẻ khác</p>' : '');
  }

  /* Server converts CSV/TXT/XLSX into the same text the textarea accepts, so the user can review before applying. */
  async function importFromFile(event) {
    var input = event.target;
    var file = input.files && input.files[0];
    input.value = '';
    if (!file) return;
    if (!/\.(xlsx|csv|txt|tsv)$/i.test(file.name)) {
      FlashShell.showToast('Chỉ hỗ trợ file .xlsx, .csv hoặc .txt', 'err');
      return;
    }
    if (file.size > 1024 * 1024) {
      FlashShell.showToast('File không được vượt quá 1MB', 'err');
      return;
    }
    try {
      var res = await FlashApi.upload('/api/flashcards/import/file', file);
      if (!res || !res.text) {
        FlashShell.showToast('File không có dòng dữ liệu nào', 'err');
        return;
      }
      // The file replaces the source data: in "File" mode the textarea is hidden and only the preview shows.
      document.getElementById('import-text').value = res.text;
      clearImportAiPreview();
      updateImportPreview();
      FlashShell.showToast('Đã đọc ' + res.lineCount + ' dòng từ ' + file.name + '. Kiểm tra rồi bấm Áp dụng', 'ok');
    } catch (e) {
      FlashShell.showToast(e.message || 'Không đọc được file', 'err');
    }
  }

  var pendingImportCards = null;
  var importGeneratedFrom = null;
  var importAiBusy = false;
  var importAiRequest = 0;

  function clearImportAiPreview() {
    pendingImportCards = null;
    importGeneratedFrom = null;
    document.getElementById('import-ai-preview').hidden = true;
    document.getElementById('import-ai-cards').replaceChildren();
    document.getElementById('import-ai-status').textContent = '';
    document.getElementById('btn-apply-import').innerHTML = '<i class="fa-solid fa-check"></i> Áp dụng vào danh sách thẻ';
  }

  function setImportAiBusy(busy) {
    importAiBusy = busy;
    ['btn-import-ai', 'btn-apply-import', 'btn-import-file', 'import-text'].forEach(function (id) {
      document.getElementById(id).disabled = busy;
    });
    document.getElementById('btn-import-ai').setAttribute('aria-busy', String(busy));
    document.getElementById('btn-import-ai').innerHTML = busy
      ? '<i class="fa-solid fa-spinner fa-spin" aria-hidden="true"></i> Đang tạo thẻ…'
      : '<i class="fa-solid fa-wand-magic-sparkles" aria-hidden="true"></i> AI tạo thẻ từ dữ liệu';
  }

  async function importGenerateAi() {
    if (importAiBusy) return;
    var text = document.getElementById('import-text').value;
    clearImportAiPreview();
    var status = document.getElementById('import-ai-status');
    if (!text.trim()) { status.textContent = 'Nhập dữ liệu nguồn trước khi tạo thẻ.'; return; }
    if (text.length > 12000) { status.textContent = 'Dữ liệu không được quá 12.000 ký tự.'; return; }
    var request = ++importAiRequest;
    setImportAiBusy(true);
    status.textContent = 'AI đang đọc dữ liệu và tạo các thẻ…';
    try {
      var res = await FlashApi.post('/api/flashcards/import/generate-cards', { text: text });
      if (request !== importAiRequest) return;
      if (document.getElementById('import-text').value !== text) throw new Error('Dữ liệu nguồn đã thay đổi. Hãy tạo thẻ lại từ dữ liệu mới.');
      if (!Array.isArray(res) || !res.length || res.length > 60) throw new Error('AI chưa trả về thẻ hợp lệ.');
      var generated = res.map(function (item) {
        if (!item || typeof item.front !== 'string' || typeof item.reading !== 'string' || typeof item.meaning !== 'string'
            || !item.front.trim() || !item.reading.trim() || !item.meaning.trim()) throw new Error('Thẻ AI thiếu từ vựng, cách đọc hoặc nghĩa.');
        var front = item.front.trim(), reading = item.reading.trim(), meaning = item.meaning.trim();
        return { front: front, reading: reading, meaning: meaning,
          back: reading + ' — ' + meaning,
          hanViet: hasKanji(front) ? String(item.hanViet || '').trim() : '', image: '', _selected: false };
      });
      pendingImportCards = generated;
      importGeneratedFrom = text;
      document.getElementById('import-ai-cards').innerHTML = generated.map(function (card, index) {
        return '<li class="import-ai-card"><span class="import-ai-card__number">' + (index + 1) + '</span><div>' +
          '<strong lang="ja">' + FlashShell.esc(card.front) + '</strong><span lang="ja">' + FlashShell.esc(card.reading) + '</span>' +
          '<p>' + FlashShell.esc(card.meaning) + '</p><small>Hán Việt: ' + FlashShell.esc(card.hanViet || '—') + '</small></div></li>';
      }).join('');
      document.getElementById('import-ai-preview').hidden = false;
      status.textContent = 'Đã tạo ' + generated.length + ' thẻ. Kiểm tra kết quả rồi áp dụng vào danh sách.';
      document.getElementById('btn-apply-import').innerHTML = '<i class="fa-solid fa-check"></i> Thêm ' + generated.length + ' thẻ vào danh sách';
    } catch (e) {
      if (request === importAiRequest) status.textContent = e.message || 'Không tạo được thẻ. Hãy thử lại.';
    } finally {
      if (request === importAiRequest) setImportAiBusy(false);
    }
  }

  function applyImport() {
    if (importAiBusy) return;
    var text = document.getElementById('import-text').value;
    var aiReady = pendingImportCards && importGeneratedFrom === text;
    if (importSource === 'ai' && !aiReady) {
      FlashShell.showToast('Bấm "AI tạo thẻ từ dữ liệu" để tạo thẻ trước khi áp dụng', 'err');
      return;
    }
    var parsed = importSource === 'ai'
      ? pendingImportCards.map(function (card) { return Object.assign({}, card); })
      : parseImportLines(text);
    if (!parsed.length) {
      FlashShell.showToast('Không có dòng hợp lệ. Dán 2–3 cột Excel hoặc dùng mẫu Kanji : Nghĩa (có thể thêm cách đọc và Hán Việt)', 'err');
      return;
    }

    // Bỏ các thẻ trống hiện có rồi append
    var nonEmpty = cards.filter(function (c) {
      return (c.front && c.front.trim()) || (c.back && c.back.trim()) || (c.hanViet && c.hanViet.trim());
    });
    cards = nonEmpty.concat(parsed);
    render();
    scheduleDraftSave();
    closeImport();
    FlashShell.showToast('Đã tạo ' + parsed.length + ' thẻ từ danh sách', 'ok');
  }

  function buildDraftPayload() {
    return {
      title: document.getElementById('set-title').value,
      description: document.getElementById('set-desc').value,
      visibility: document.getElementById('set-visibility').value,
      folderId: Number(document.getElementById('set-folder').value) || 0,
      cards: cards.map(function (c, idx) {
        ensureBackFields(c);
        return {
          id: c.id || null,
          front: c.front || '',
          back: c.back || '',
          reading: c.reading || '',
          meaning: c.meaning || '',
          hanViet: c.hanViet || '',
          image: c.image || null,
          position: idx
        };
      })
    };
  }

  function hasDraftContent(payload) {
    if (payload.title.trim() || payload.description.trim() || payload.cards.length > 1) return true;
    return payload.cards.some(function (c) {
      return c.front.trim() || c.reading.trim() || c.meaning.trim() || c.hanViet.trim() || c.image;
    });
  }

  function setDraftStatus(message, state) {
    var status = document.getElementById('draft-save-status');
    if (!status) return;
    status.textContent = message;
    status.className = 'draft-save-status' + (state ? ' is-' + state : '');
  }

  function scheduleDraftSave() {
    if (viewOnly) return;
    hasUnsavedChanges = true;
    setDraftStatus('Chưa lưu thay đổi', '');
    clearTimeout(autosaveTimer);
    autosaveTimer = setTimeout(saveDraftNow, setId ? 900 : 0);
  }

  async function saveDraftNow() {
    clearTimeout(autosaveTimer);
    var payload = buildDraftPayload();
    var snapshot = JSON.stringify(payload);
    if (!hasDraftContent(payload) && !setId) {
      hasUnsavedChanges = false;
      setDraftStatus('Chưa có nội dung để lưu', '');
      return;
    }
    if (snapshot === lastDraftSnapshot) {
      hasUnsavedChanges = false;
      return;
    }
    if (autosavePromise) {
      autosaveQueued = true;
      return autosavePromise;
    }

    setDraftStatus('Đang lưu bản nháp...', 'saving');
    var running = (async function () {
      var saved = setId
        ? await FlashApi.put('/api/flashcards/' + setId + '/draft', payload)
        : await FlashApi.post('/api/flashcards/drafts', payload);
      setId = saved.id;
      (saved.cards || []).forEach(function (savedCard, idx) {
        if (cards[idx]) cards[idx].id = savedCard.id;
        if (payload.cards[idx]) payload.cards[idx].id = savedCard.id;
      });
      var nextParams = new URLSearchParams(location.search);
      nextParams.set('id', String(setId));
      history.replaceState(null, '', location.pathname + '?' + nextParams.toString());
      lastDraftSnapshot = JSON.stringify(payload);
      hasUnsavedChanges = false;
      setDraftStatus('Đã lưu bản nháp', 'saved');
    })();
    autosavePromise = running;
    try {
      await running;
    } catch (e) {
      hasUnsavedChanges = true;
      setDraftStatus('Lưu bản nháp thất bại', 'error');
    } finally {
      if (autosavePromise === running) autosavePromise = null;
      if (autosaveQueued) {
        autosaveQueued = false;
        scheduleDraftSave();
      }
    }
  }

  async function save() {
    if (!FlashShell.requireLogin()) return;
    var title = document.getElementById('set-title').value.trim();
    if (title.length < 3 || title.length > 100) {
      // BR-11 — highlight trước khi báo lỗi
      PreValidation.validateForm(document.querySelector('.editor-meta'));
      FlashShell.showToast('Tiêu đề phải từ 3 đến 100 ký tự', 'err');
      return;
    }
    // BR-11 — kiểm tra tất cả thẻ có front chưa
    var firstMissing = null;
    cards.forEach(function (c, idx) {
      ensureBackFields(c);
      if (!c.front || !c.front.trim() || !c.meaning || !c.meaning.trim()) {
        var listEl = document.getElementById('card-list');
        var cardEl = listEl.querySelector('[data-i="' + idx + '"]');
        if (cardEl) {
          var missingField = !c.front || !c.front.trim() ? 'front' : 'meaning';
          var inp = cardEl.querySelector('input[data-f="' + missingField + '"]');
          if (inp) {
            inp.classList.add('is-invalid');
            var wrap = inp.closest('.input-wrap');
            if (wrap) wrap.classList.add('is-invalid');
          }
        }
        if (firstMissing === null) firstMissing = idx;
      }
    });
    if (firstMissing !== null) {
      FlashShell.showToast('Mỗi thẻ cần có từ vựng và nghĩa', 'err');
      var listEl2 = document.getElementById('card-list');
      var cardEl2 = listEl2.querySelector('[data-i="' + firstMissing + '"]');
      if (cardEl2) cardEl2.scrollIntoView({ behavior: 'smooth', block: 'center' });
      return;
    }
    var payload = {
      title: title,
      description: document.getElementById('set-desc').value.trim(),
      visibility: document.getElementById('set-visibility').value,
      folderId: Number(document.getElementById('set-folder').value) || 0,
      cards: cards
        .filter(function (c) { return c.front && c.front.trim(); })
        .map(function (c, idx) {
          return {
            id: c.id || null,
            front: c.front.trim(),
            back: c.back.trim(),
            reading: c.reading.trim(),
            meaning: c.meaning.trim(),
            hanViet: (c.hanViet || '').trim() || null,
            image: c.image || null,
            position: idx
          };
        })
    };
    if (!payload.cards.length) {
      FlashShell.showToast('Cần ít nhất 1 thẻ có từ vựng ở mặt trước', 'err');
      return;
    }
    if (payload.visibility === 'PUBLIC' && !(await validateRealtime(true))) return;
    try {
      clearTimeout(autosaveTimer);
      if (autosavePromise) await autosavePromise;
      payload.cards.forEach(function (card, idx) {
        if (cards[idx] && cards[idx].id) card.id = cards[idx].id;
      });
      var saved;
      if (setId) {
        saved = await FlashApi.put('/api/flashcards/' + setId, payload);
      } else {
        saved = await FlashApi.post('/api/flashcards', payload);
      }
      setId = saved.id;
      hasUnsavedChanges = false;
      lastDraftSnapshot = JSON.stringify(buildDraftPayload());
      setDraftStatus('Đã lưu hoàn chỉnh', 'saved');
      if (saved.pendingReview) {
        FlashShell.showToast(saved.moderationMessage ||
          'Đã lưu · nội dung đang chờ kiểm duyệt và chỉ mình bạn nhìn thấy.', 'ok');
      } else {
        FlashShell.showToast(
          saved.visibility === 'PUBLIC' ? 'Đã lưu · công khai' : 'Đã lưu',
          'ok'
        );
      }
      setTimeout(function () {
        location.href = '/flashcard/library.html';
      }, 500);
    } catch (e) {
      FlashShell.showToast(e.message || 'Lưu thất bại', 'err');
    }
  }

  async function submitAppeal() {
    var reason = document.getElementById('appeal-reason').value.trim();
    if (!setId || reason.length < 10 || reason.length > 500) {
      FlashShell.showToast('Lý do kháng cáo phải từ 10 đến 500 ký tự', 'err');
      return;
    }
    try {
      await FlashApi.post('/api/flashcards/' + setId + '/appeal', { reason: reason });
      document.getElementById('appeal-modal').hidden = true;
      document.getElementById('btn-appeal').hidden = true;
      FlashShell.showToast('Đã gửi kháng cáo đến admin', 'ok');
    } catch (e) {
      FlashShell.showToast(e.message || 'Không gửi được kháng cáo', 'err');
    }
  }

  function scheduleRealtimeValidation() {
    clearTimeout(validationTimer);
    validationTimer = setTimeout(function () { validateRealtime(false); }, 350);
  }

  async function validateRealtime(focusFirst) {
    var isPublic = document.getElementById('set-visibility').value === 'PUBLIC';
    try {
      var result = await FlashApi.post('/api/v1/flashcards/validate-realtime', {
        title: document.getElementById('set-title').value,
        description: document.getElementById('set-desc').value,
        cards: cards.map(function (c) { return { front: c.front, back: c.back }; })
      });
      realtimeValid = !!result.valid;
      document.querySelectorAll('.denylist-invalid').forEach(function (el) { el.classList.remove('denylist-invalid'); });
      var banner = document.getElementById('validation-banner');
      var violations = result.violations || [];
      function inputFieldName(field) {
        return field === 'back' ? 'meaning' : field;
      }
      banner.hidden = !isPublic || !violations.length;
      document.getElementById('validation-message').innerHTML = violations.map(function (v) {
        var label = v.cardIndex == null ? v.field : ('Thẻ ' + (v.cardIndex + 1) + ' · ' + v.field);
        return '<div>' + FlashShell.esc(label) + ': <mark>' + FlashShell.esc(v.term) + '</mark></div>';
      }).join('');
      violations.forEach(function (v) {
        var el = v.cardIndex == null
          ? document.getElementById(v.field === 'title' ? 'set-title' : 'set-desc')
          : document.querySelector('[data-i="' + v.cardIndex + '"][data-f="' + inputFieldName(v.field) + '"]');
        if (el) el.classList.add('denylist-invalid');
      });
      document.getElementById('btn-save').disabled = isPublic && !realtimeValid;
      if (focusFirst && isPublic && violations.length) {
        var first = violations[0];
        var input = first.cardIndex == null
          ? document.getElementById(first.field === 'title' ? 'set-title' : 'set-desc')
          : document.querySelector('[data-i="' + first.cardIndex + '"][data-f="' + inputFieldName(first.field) + '"]');
        if (input) { input.focus(); if (input.setSelectionRange) input.setSelectionRange(first.start, first.end); }
        FlashShell.showToast('Hãy sửa từ được đánh dấu trước khi công khai', 'err');
        return false;
      }
      return !isPublic || realtimeValid;
    } catch (e) {
      if (focusFirst && isPublic) FlashShell.showToast('Không thể kiểm tra nội dung lúc này', 'err');
      return !isPublic;
    }
  }
})();
