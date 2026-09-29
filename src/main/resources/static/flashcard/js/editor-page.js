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
    document.getElementById('btn-import-file').addEventListener('click', function () {
      document.getElementById('import-file').click();
    });
    document.getElementById('import-file').addEventListener('change', importFromFile);
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
    var name = prompt('Tên thư mục mới:');
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
                '<div class="edit-card__field"><label class="label" for="card-front-' + i + '">Kanji <span class="required-mark">*</span></label><div class="input-wrap"><input id="card-front-' + i + '" class="input" aria-label="Kanji thẻ ' + (i + 1) + '" data-f="front" data-i="' + i + '" type="text" placeholder="例: 日本" value="' + FlashShell.esc(c.front) + '" ' + ro + '/></div></div>' +
                '<div class="edit-card__field"><label class="label" for="card-reading-' + i + '">Cách đọc <span class="required-mark">*</span></label><div class="input-wrap"><input id="card-reading-' + i + '" class="input" aria-label="Cách đọc thẻ ' + (i + 1) + '" data-f="reading" data-i="' + i + '" type="text" placeholder="にほん" value="' + FlashShell.esc(c.reading) + '" ' + ro + '/></div></div>' +
                '<div class="edit-card__field"><label class="label" for="card-meaning-' + i + '">Nghĩa <span class="required-mark">*</span></label><div class="input-wrap"><input id="card-meaning-' + i + '" class="input" aria-label="Nghĩa thẻ ' + (i + 1) + '" data-f="meaning" data-i="' + i + '" type="text" placeholder="Nhật Bản" value="' + FlashShell.esc(c.meaning) + '" ' + ro + '/></div></div>' +
              '</div>' +
              '<div class="edit-card__fields edit-card__fields--secondary">' +
                '<div class="edit-card__field"><label class="label" for="card-hanviet-' + i + '">Hán Việt <span class="optional-label">Tùy chọn</span></label><div class="input-wrap"><input id="card-hanviet-' + i + '" class="input" aria-label="Hán Việt thẻ ' + (i + 1) + '" data-f="hanViet" data-i="' + i + '" type="text" placeholder="NHẬT BẢN" value="' + FlashShell.esc(c.hanViet) + '" ' + ro + '/></div></div>' +
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
      throw new Error('Cần Kanji (Mặt trước). Chỉ có Nghĩa hoặc Hán Việt thì không tạo được.');
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

    if (returnedKanji && compactTerm(returnedKanji) !== compactTerm(originalKanji)) {
      warnings.push('AI trả Kanji khác; hệ thống đã giữ nguyên Kanji gốc.');
    }
    if (reading && !looksLikeJapaneseReading(reading)) {
      warnings.push('Cách đọc AI trả về không phải kana thuần; hãy kiểm tra trước khi áp dụng.');
    }
    if (!hanViet) warnings.push('AI không trả Hán Việt cho mục này.');

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
        '<div class="ai-review-item__value"><span>Kanji gốc</span><strong>' + FlashShell.esc(change.kanji) + '</strong></div>' +
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
      FlashShell.showToast('Cần Kanji (Mặt trước). Chỉ Nghĩa/Hán Việt không tạo được.', 'err');
      return;
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

    var validTargets = selected.filter(function (c) { return c.front && c.front.trim(); });
    var skipCount = selected.length - validTargets.length;

    if (!validTargets.length) {
      FlashShell.showToast('Không có thẻ nào có Kanji hợp lệ để AI điền', 'err');
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
  var LINE_RE = /^\s*(.+?)\s*-\s*(.+?)\s*:\s*(.+?)(?:\s*\(([^)]*)\))?\s*$/;

  function openImport() {
    document.getElementById('import-modal').hidden = false;
    document.getElementById('import-text').focus();
  }

  function closeImport() {
    document.getElementById('import-modal').hidden = true;
  }

  function parseImportLines(text) {
    var out = [];
    if (!text) return out;
    text.split(/\r?\n/).forEach(function (raw) {
      if (!raw.trim()) return;
      var cells = raw.split('\t').map(function (cell) { return cell.trim(); });
      if (cells.length >= 2 && cells[0] && cells[1]) {
        out.push({
          front: cells[0],
          back: cells[1],
          hanViet: cells[2] || '',
          image: '',
          _selected: false
        });
        return;
      }
      var m = raw.trim().match(LINE_RE);
      if (!m) return;
      var kanji = m[1].trim();
      var reading = m[2].trim();
      var meaning = m[3].trim();
      var hanViet = m[4] ? m[4].trim() : '';
      // Front = Kanji · Back = hiragana — nghĩa · Hán Việt optional
      out.push({
        front: kanji,
        back: reading ? (reading + ' — ' + meaning) : meaning,
        hanViet: hanViet,
        image: '',
        _selected: false
      });
    });
    return out;
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
      var textarea = document.getElementById('import-text');
      textarea.value = textarea.value.trim() ? textarea.value.trim() + '\n' + res.text : res.text;
      FlashShell.showToast('Đã đọc ' + res.lineCount + ' dòng từ ' + file.name + '. Kiểm tra rồi bấm Áp dụng', 'ok');
    } catch (e) {
      FlashShell.showToast(e.message || 'Không đọc được file', 'err');
    }
  }

  async function importGenerateAi() {
    var text = document.getElementById('import-text').value;
    if (!text.trim()) {
      FlashShell.showToast('Nhập danh sách từ trước', 'err');
      return;
    }

    if (window.JlasUI && JlasUI.showAiProgress) {
      JlasUI.showAiProgress({
        title: 'Tạo Hán Việt bằng AI 🌸',
        category: 'IMPORT ASSISTANT',
        subtitle: 'Đang phân tích danh sách từ vựng và tự động bổ sung Hán Việt...',
        total: 100,
        unit: '%',
        current: 25,
        percent: 25,
        status: 'Đang gửi văn bản tới AI...'
      });
    }

    try {
      if (window.JlasUI && JlasUI.updateAiProgress) {
        JlasUI.updateAiProgress({ percent: 28, status: 'AI đang phân tích và dịch Hán Việt...' });
      }

      var res = await FlashApi.post('/api/flashcards/import/generate-hanviet', { text: text });
      document.getElementById('import-text').value = res.text || text;

      if (window.JlasUI && JlasUI.completeAiProgress) {
        JlasUI.completeAiProgress('Đã bổ sung Hán Việt! 🌸', function () {
          FlashShell.showToast('Đã bổ sung Hán Việt (AI)', 'ok');
        });
      } else {
        FlashShell.showToast('Đã bổ sung Hán Việt (AI)', 'ok');
      }
    } catch (e) {
      if (window.JlasUI && JlasUI.hideAiProgress) JlasUI.hideAiProgress();
      FlashShell.showToast(e.message || 'Tạo Hán Việt thất bại', 'err');
    }
  }

  function applyImport() {
    var text = document.getElementById('import-text').value;
    var parsed = parseImportLines(text);
    if (!parsed.length) {
      FlashShell.showToast('Không có dòng hợp lệ. Dán 2–3 cột Excel hoặc dùng mẫu Kanji - Hiragana : Nghĩa', 'err');
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
      if (!c.front || !c.front.trim() || !c.reading || !c.reading.trim() || !c.meaning || !c.meaning.trim()) {
        var listEl = document.getElementById('card-list');
        var cardEl = listEl.querySelector('[data-i="' + idx + '"]');
        if (cardEl) {
          var missingField = !c.front || !c.front.trim()
            ? 'front'
            : ((!c.reading || !c.reading.trim()) ? 'reading' : 'meaning');
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
      FlashShell.showToast('Mỗi thẻ cần có Kanji, cách đọc và nghĩa', 'err');
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
      FlashShell.showToast('Cần ít nhất 1 thẻ có Mặt trước (Kanji)', 'err');
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
