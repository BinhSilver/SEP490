/**
 * Tra Kanji theo bộ thủ (multi-radical lookup, dữ liệu RADKFILE).
 * Chọn một hoặc nhiều bộ phận -> Kanji chứa tất cả bộ phận đó; bộ phận không thể kết hợp thêm bị
 * làm mờ. Chọn Kanji sẽ đưa ký tự về ô tìm kiếm và dùng luồng tìm kiếm có sẵn (giống viết tay).
 */
(function (global) {
  'use strict';

  var MODAL_ID = 'dict-radical-modal';
  var els = {};
  var components = null;      // [{id, character, strokeCount, nameVi}]
  var byId = {};
  var selected = [];          // id theo thứ tự chọn
  var available = null;       // Set id còn chọn được; null = chưa chọn gì
  var loading = null;
  var controller = null;
  var requestSeq = 0;

  function escapeHtml(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, function (ch) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch];
    });
  }

  /** Bỏ dấu tiếng Việt để lọc: "thuỷ", "thủy", "thuy" đều khớp. */
  function fold(value) {
    return String(value || '').normalize('NFD').replace(/[̀-ͯ]/g, '')
      .replace(/đ/g, 'd').replace(/Đ/g, 'D').toLowerCase().trim();
  }

  function setStatus(text, isError) {
    els.status.textContent = text;
    els.status.classList.toggle('is-error', !!isError);
  }

  function loadComponents() {
    if (components) return Promise.resolve(components);
    if (loading) return loading;
    els.picker.innerHTML = '<p class="dict-detail__empty">Đang tải bộ thủ...</p>';
    loading = global.DictApi.kanjiComponents().then(function (list) {
      components = list || [];
      components.forEach(function (component) { byId[component.id] = component; });
      renderPicker();
      return components;
    }).catch(function (error) {
      loading = null;
      els.picker.innerHTML = '<p class="dict-detail__empty">Không tải được danh sách bộ thủ. Hãy thử lại.</p>';
      throw error;
    });
    return loading;
  }

  function renderPicker() {
    if (!components) return;
    var filter = fold(els.filter.value);
    var groups = [];
    var current = null;
    components.forEach(function (component) {
      var matches = !filter || fold(component.nameVi).indexOf(filter) !== -1 || component.character === els.filter.value.trim();
      if (!matches) return;
      if (!current || current.strokes !== component.strokeCount) {
        current = { strokes: component.strokeCount, items: [] };
        groups.push(current);
      }
      current.items.push(component);
    });
    if (!groups.length) {
      els.picker.innerHTML = '<p class="dict-detail__empty">Không có bộ thủ nào khớp "' + escapeHtml(els.filter.value) + '".</p>';
      return;
    }
    els.picker.innerHTML = groups.map(function (group) {
      return '<div class="dict-radical-group">' +
        '<span class="dict-radical-group__strokes" title="' + group.strokes + ' nét">' + group.strokes + '</span>' +
        group.items.map(componentButtonHtml).join('') +
      '</div>';
    }).join('');
  }

  function componentButtonHtml(component) {
    var isSelected = selected.indexOf(component.id) !== -1;
    var isUnavailable = !isSelected && available !== null && !available.has(component.id);
    var label = component.character + (component.nameVi ? ' (' + component.nameVi + ')' : '') + ', ' + component.strokeCount + ' nét';
    return '<button type="button" class="dict-radical-option' + (isSelected ? ' is-selected' : '') + '"' +
      ' data-component-id="' + component.id + '" aria-pressed="' + isSelected + '"' +
      (isUnavailable ? ' disabled' : '') +
      ' title="' + escapeHtml(label) + '" aria-label="' + escapeHtml(label) + '">' +
      escapeHtml(component.character) + '</button>';
  }

  function renderSelection() {
    els.selected.innerHTML = selected.length
      ? selected.map(function (id) {
          var component = byId[id];
          return '<button type="button" class="dict-radical-chip" data-component-id="' + id + '" aria-label="Bỏ chọn ' +
            escapeHtml(component.character) + '">' +
            '<span class="dict-radical-chip__char">' + escapeHtml(component.character) + '</span>' +
            (component.nameVi ? '<span class="dict-radical-chip__name">' + escapeHtml(component.nameVi) + '</span>' : '') +
            '<i class="fa-solid fa-xmark" aria-hidden="true"></i></button>';
        }).join('')
      : '<span class="dict-radical-selected__empty">Chưa chọn bộ thủ nào</span>';
    els.clear.hidden = selected.length === 0;
  }

  function renderResults(result) {
    var kanji = (result && result.kanji) || [];
    if (!kanji.length) {
      els.results.innerHTML = '';
      els.count.textContent = '';
      return;
    }
    var groups = [];
    var current = null;
    kanji.forEach(function (item) {
      if (!current || current.strokes !== item.strokeCount) {
        current = { strokes: item.strokeCount, items: [] };
        groups.push(current);
      }
      current.items.push(item);
    });
    els.results.innerHTML = groups.map(function (group) {
      return '<div class="dict-radical-result-group">' +
        '<span class="dict-radical-group__strokes">' + (group.strokes || '?') + '</span>' +
        group.items.map(function (item) {
          return '<button type="button" class="dict-radical-kanji" data-kanji="' + escapeHtml(item.character) + '"' +
            ' title="' + escapeHtml(item.character + (item.hanViet ? ' – ' + item.hanViet : '')) + '">' +
            '<span class="dict-radical-kanji__char">' + escapeHtml(item.character) + '</span>' +
            (item.hanViet ? '<small>' + escapeHtml(item.hanViet) + '</small>' : '') +
          '</button>';
        }).join('') +
      '</div>';
    }).join('');
    els.count.textContent = result.total > kanji.length
      ? 'Hiện ' + kanji.length + '/' + result.total + ' chữ'
      : result.total + ' chữ';
  }

  function runSearch() {
    if (controller) controller.abort();
    renderSelection();
    if (!selected.length) {
      available = null;
      renderResults(null);
      renderPicker();
      setStatus('Chọn bộ thủ để xem Kanji.');
      return;
    }
    var seq = ++requestSeq;
    controller = typeof AbortController === 'function' ? new AbortController() : null;
    els.results.classList.add('is-loading');
    setStatus('Đang tìm Kanji...');
    global.DictApi.searchByComponents(selected, controller ? controller.signal : undefined)
      .then(function (result) {
        if (seq !== requestSeq) return;
        available = new Set(result.availableComponentIds || []);
        renderResults(result);
        renderPicker();
        setStatus(result.total
          ? 'Chọn chữ Kanji cần tra, hoặc chọn thêm bộ thủ để thu hẹp kết quả.'
          : 'Không có Kanji nào chứa đủ các bộ thủ đã chọn.');
      })
      .catch(function (error) {
        if (error && error.name === 'AbortError') return;
        if (seq !== requestSeq) return;
        setStatus((error && error.message) || 'Không tra được Kanji, hãy thử lại.', true);
      })
      .then(function () {
        if (seq === requestSeq) els.results.classList.remove('is-loading');
      });
  }

  function toggle(id) {
    var index = selected.indexOf(id);
    if (index === -1) selected.push(id); else selected.splice(index, 1);
    runSearch();
  }

  function selectKanji(character) {
    var searchInput = document.getElementById('dict-search-input');
    if (!searchInput) return;
    searchInput.value = character;
    if (global.JlasUI) global.JlasUI.closeModal(MODAL_ID);
    searchInput.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    if (searchInput.focus) searchInput.focus();
  }

  function reset() {
    if (controller) controller.abort();
    selected = [];
    available = null;
    if (els.filter) els.filter.value = '';
    if (els.results) {
      renderResults(null);
      renderSelection();
      renderPicker();
      setStatus('Chọn bộ thủ để xem Kanji.');
    }
  }

  function open() {
    if (global.JlasUI) global.JlasUI.openModal(MODAL_ID);
    loadComponents().catch(function () {});
  }

  function init() {
    els.picker = document.getElementById('dict-radical-picker');
    if (!els.picker) return;
    els.filter = document.getElementById('dict-radical-filter');
    els.selected = document.getElementById('dict-radical-selected');
    els.clear = document.getElementById('dict-radical-clear');
    els.results = document.getElementById('dict-radical-results');
    els.status = document.getElementById('dict-radical-status');
    els.count = document.getElementById('dict-radical-count');

    els.picker.addEventListener('click', function (event) {
      var button = event.target.closest('[data-component-id]');
      if (button && !button.disabled) toggle(Number(button.getAttribute('data-component-id')));
    });
    els.selected.addEventListener('click', function (event) {
      var chip = event.target.closest('[data-component-id]');
      if (chip) toggle(Number(chip.getAttribute('data-component-id')));
    });
    els.results.addEventListener('click', function (event) {
      var button = event.target.closest('[data-kanji]');
      if (button) selectKanji(button.getAttribute('data-kanji'));
    });
    els.clear.addEventListener('click', reset);
    els.filter.addEventListener('input', renderPicker);
    renderSelection();
  }

  document.addEventListener('DOMContentLoaded', init);

  global.DictRadical = { open: open, reset: reset };
})(window);
