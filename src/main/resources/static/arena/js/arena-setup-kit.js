/**
 * Bộ component thiết lập trận dùng chung (trang /games/lobby và hộp "Đổi cài đặt" trong phòng):
 * - DeckPicker: tìm bộ flashcard theo tên, tab Bộ của tôi / Xem gần đây / Tất cả.
 * - Field picker: chọn nội dung câu hỏi và nội dung đáp án như trang kiểm tra flashcard (4 trường).
 * - Options: tuỳ chọn riêng từng game, lấy danh sách giá trị hợp lệ từ /api/games/catalog.
 */
(function (global) {
  'use strict';

  var arena = global.JlasArena;
  var esc = arena.esc;
  var FIELDS = ['KANJI', 'HIRAGANA', 'MEANING', 'HAN_VIET'];
  var TABS = [
    { key: 'MINE', label: 'Bộ của tôi' },
    { key: 'RECENT', label: 'Xem gần đây' },
    { key: 'ALL', label: 'Tất cả' }
  ];
  var DIFFICULTY_LABELS = {
    EASY: 'Dễ · 5 tim · 3 cá',
    NORMAL: 'Vừa · 3 tim · 5 cá',
    HARD: 'Khó · 2 tim · 7 cá'
  };

  function minWords(gameType, options) {
    if (gameType === 'MEMORY_MATCH') return Math.max(6, Math.round(((options && options.boardSize) || 20) / 2));
    if (gameType === 'AIR_DEFENSE') return 5;
    return 4;
  }

  function usable(detail, question, answer) {
    if (!detail || !question || !answer || question === answer) return 0;
    var pair = (detail.pairs || []).find(function (item) {
      return item.questionField === question && item.answerField === answer;
    });
    return pair ? pair.usableCards : 0;
  }

  /** Cặp hỏi/đáp mặc định: Kanji → Nghĩa nếu đủ thẻ, không thì cặp có nhiều thẻ dùng được nhất. */
  function bestPair(detail, gameType, options) {
    var need = minWords(gameType, options);
    if (usable(detail, 'KANJI', 'MEANING') >= need) return { question: 'KANJI', answer: 'MEANING' };
    var best = null;
    (detail.pairs || []).forEach(function (pair) {
      if (!best || pair.usableCards > best.usableCards) best = pair;
    });
    return best ? { question: best.questionField, answer: best.answerField } : { question: 'KANJI', answer: 'MEANING' };
  }

  function relativeTime(value) {
    if (!value) return '';
    var then = new Date(value).getTime();
    if (!Number.isFinite(then)) return '';
    var minutes = Math.round((Date.now() - then) / 60000);
    if (minutes < 1) return 'vừa xong';
    if (minutes < 60) return minutes + ' phút trước';
    var hours = Math.round(minutes / 60);
    if (hours < 24) return hours + ' giờ trước';
    var days = Math.round(hours / 24);
    return days < 30 ? days + ' ngày trước' : new Date(then).toLocaleDateString('vi-VN');
  }

  /** Radio group dạng nút (role=radio), hỗ trợ phím mũi tên. */
  function segmented(name, label, choices, value, onPick) {
    var id = 'ar-seg-' + name + '-' + Math.random().toString(36).slice(2, 7);
    var html = '<div class="ar-option-row"><span class="ar-label" id="' + id + '">' + esc(label) + '</span>'
      + '<div class="ar-seg" role="radiogroup" aria-labelledby="' + id + '" data-seg="' + esc(name) + '">'
      + choices.map(function (choice) {
        var checked = String(choice.value) === String(value);
        return '<button type="button" role="radio" aria-checked="' + checked + '" tabindex="' + (checked ? '0' : '-1') + '"'
          + ' data-value="' + esc(choice.value) + '"' + (choice.disabled ? ' disabled title="' + esc(choice.reason || '') + '"' : '') + '>'
          + esc(choice.label) + '</button>';
      }).join('') + '</div></div>';
    return { html: html, bind: function (root) { bindSeg(root.querySelector('[data-seg="' + name + '"]'), onPick); } };
  }

  function bindSeg(group, onPick) {
    if (!group) return;
    var buttons = Array.prototype.slice.call(group.querySelectorAll('button[role="radio"]'));
    buttons.forEach(function (button, index) {
      button.addEventListener('click', function () {
        if (button.disabled) return;
        onPick(button.dataset.value);
      });
      button.addEventListener('keydown', function (event) {
        if (['ArrowRight', 'ArrowDown', 'ArrowLeft', 'ArrowUp'].indexOf(event.key) < 0) return;
        event.preventDefault();
        var step = event.key === 'ArrowRight' || event.key === 'ArrowDown' ? 1 : -1;
        for (var i = 1; i <= buttons.length; i++) {
          var next = buttons[(index + step * i + buttons.length * 2) % buttons.length];
          if (!next.disabled) {
            next.focus();
            onPick(next.dataset.value);
            return;
          }
        }
      });
    });
  }

  // ------------------------------------------------------------------ Deck picker

  function DeckPicker(container, handlers) {
    this.container = container;
    this.onSelect = handlers.onSelect || function () {};
    /** Chỉ hiện bộ chơi được game này (null = mọi bộ). */
    this.gameType = handlers.gameType || null;
    this.tab = 'MINE';
    this.query = '';
    this.page = 0;
    this.items = [];
    this.hasMore = false;
    this.selected = null;
    this.detail = null;
    this.loading = false;
    this.searchTimer = null;
    this.requestSeq = 0;
    this.renderShell();
  }

  DeckPicker.prototype.renderShell = function () {
    var self = this;
    var tabs = TABS.map(function (tab) {
      return '<button type="button" role="tab" aria-selected="' + (tab.key === self.tab) + '" data-tab="' + tab.key + '">'
        + esc(tab.label) + '</button>';
    }).join('');
    self.container.innerHTML = ''
      + '<div class="ar-deck-tools">'
      + '  <label class="ar-search"><span class="sr-only">Tìm bộ flashcard theo tên</span>'
      + '    <i class="fa-solid fa-magnifying-glass" aria-hidden="true"></i>'
      + '    <input class="ar-input input--with-icon" type="search" placeholder="Tìm bộ theo tên…" autocomplete="off" data-deck-search>'
      + '  </label>'
      + '  <label class="ar-deck-filter" title="Nguồn bộ flashcard: Bộ của tôi">'
      + '    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 4h16l-6 7v7l-4 2v-9L4 4z"/></svg>'
      + '    <select aria-label="Lọc nguồn bộ flashcard" data-deck-filter>'
      + TABS.map(function (tab) {
        return '<option value="' + tab.key + '"' + (tab.key === self.tab ? ' selected' : '') + '>' + esc(tab.label) + '</option>';
      }).join('')
      + '    </select>'
      + '  </label>'
      + '  <div class="ar-seg" role="tablist" aria-label="Nguồn bộ flashcard">' + tabs + '</div>'
      + '</div>'
      + '<ul class="ar-deck-list" role="radiogroup" aria-label="Danh sách bộ flashcard" data-deck-list></ul>'
      + '<div class="ar-more" data-deck-more hidden><button type="button" class="ar-btn ar-btn--sm">Xem thêm</button></div>'
      + '<div class="ar-deck-detail" data-deck-detail hidden></div>';

    self.container.querySelector('[data-deck-search]').addEventListener('input', function (event) {
      global.clearTimeout(self.searchTimer);
      var value = event.target.value;
      self.searchTimer = global.setTimeout(function () {
        self.query = value.trim();
        self.load(true);
      }, 300);
    });
    self.container.querySelectorAll('[data-tab]').forEach(function (button) {
      button.addEventListener('click', function () {
        self.setTab(button.dataset.tab);
        self.load(true);
      });
    });
    self.container.querySelector('[data-deck-filter]').addEventListener('change', function (event) {
      self.setTab(event.target.value);
      self.load(true);
    });
    self.container.querySelector('[data-deck-more] button').addEventListener('click', function () {
      self.page += 1;
      self.load(false);
    });
    self.container.querySelector('[data-deck-list]').addEventListener('click', function (event) {
      var button = event.target.closest('[data-deck-id]');
      if (!button || button.disabled) return;
      var deck = self.items.find(function (item) { return String(item.id) === button.dataset.deckId; });
      if (deck) self.select(deck);
    });
  };

  /** Đổi game thì tải lại danh sách: bộ chơi được mỗi game một khác. */
  DeckPicker.prototype.setGame = function (gameType) {
    if (this.gameType === gameType) return;
    this.gameType = gameType;
    this.load(true);
  };

  DeckPicker.prototype.setTab = function (tab) {
    this.tab = tab;
    var filter = this.container.querySelector('[data-deck-filter]');
    filter.value = tab;
    filter.parentElement.title = 'Nguồn bộ flashcard: ' + TABS.find(function (item) { return item.key === tab; }).label;
    this.container.querySelectorAll('[data-tab]').forEach(function (button) {
      button.setAttribute('aria-selected', String(button.dataset.tab === tab));
    });
  };

  DeckPicker.prototype.load = function (reset) {
    var self = this;
    if (reset) {
      self.page = 0;
      self.items = [];
    }
    var seq = ++self.requestSeq;
    var list = self.container.querySelector('[data-deck-list]');
    if (reset) list.innerHTML = '<li class="ar-skeleton" aria-hidden="true"></li><li class="ar-skeleton" aria-hidden="true"></li>';
    return arena.api.decks(self.tab, self.query, self.page, self.gameType).then(function (page) {
      if (seq !== self.requestSeq) return;
      // Lần đầu mà người chơi chưa có bộ nào: chuyển sang tab "Tất cả" để có bộ công khai mà chọn.
      if (!self.triedFallback && self.tab === 'MINE' && !self.query && !page.items.length) {
        self.triedFallback = true;
        self.setTab('ALL');
        return self.load(true);
      }
      self.triedFallback = true;
      self.items = reset ? page.items : self.items.concat(page.items);
      self.hasMore = page.hasMore;
      self.renderList();
    }).catch(function (error) {
      if (seq !== self.requestSeq) return;
      list.innerHTML = '<li class="ar-empty"><strong>Không tải được bộ flashcard.</strong>' + esc(error.message) + '</li>';
    });
  };

  DeckPicker.prototype.renderList = function () {
    var self = this;
    var list = self.container.querySelector('[data-deck-list]');
    self.container.querySelector('[data-deck-more]').hidden = !self.hasMore;
    if (!self.items.length) {
      list.innerHTML = '<li class="ar-empty">' + self.emptyText() + '</li>';
      return;
    }
    // Bộ đang chọn (ví dụ mở từ link) luôn có trong danh sách để người chơi thấy mình đã chọn bộ nào.
    var shown = self.items;
    if (self.selected && self.selected.coverage
        && !shown.some(function (deck) { return deck.id === self.selected.id; })) {
      shown = [self.selected].concat(shown);
    }
    list.innerHTML = shown.map(function (deck) {
      var checked = self.selected && self.selected.id === deck.id;
      var meta = [];
      meta.push(deck.ownedByMe ? 'Bộ của bạn' : 'của ' + esc(deck.ownerName || '—'));
      meta.push(deck.visibility === 'PUBLIC' ? '<i class="fa-solid fa-earth-asia" aria-hidden="true"></i> Công khai'
        : '<i class="fa-solid fa-lock" aria-hidden="true"></i> Riêng tư');
      if (self.tab === 'RECENT' && deck.lastUsedAt) meta.push('mở ' + esc(relativeTime(deck.lastUsedAt)));
      if (deck.draft) meta.push('<span class="ar-chip ar-chip--gold">Bản nháp · xuất bản rồi mới chơi được</span>');
      var thumb = deck.thumbnail
        ? '<img src="' + esc(deck.thumbnail) + '" alt="" loading="lazy">'
        : esc((deck.title || '?').trim().charAt(0).toUpperCase());
      return '<li><button type="button" class="ar-deck" role="radio" aria-checked="' + !!checked + '"'
        + ' data-deck-id="' + deck.id + '"' + (deck.draft || !deck.cardCount ? ' disabled' : '') + '>'
        + '<span class="ar-deck__thumb" aria-hidden="true">' + thumb + '</span>'
        + '<span class="ar-deck__main"><span class="ar-deck__title">' + esc(deck.title) + '</span>'
        + '<span class="ar-deck__meta">' + meta.join(' · ') + '</span>'
        + '<span class="ar-coverage"><span class="ar-chip">' + (deck.cardCount || 0) + ' từ</span></span></span>'
        + '</button></li>';
    }).join('');
  };

  DeckPicker.prototype.emptyText = function () {
    if (this.query) {
      return '<strong>Không có bộ nào tên chứa “' + esc(this.query) + '”.</strong>Thử tên khác hoặc chọn mục "Tất cả".';
    }
    if (this.gameType) {
      return this.tab === 'MINE'
        ? '<strong>Bạn chưa có bộ nào đủ thẻ để chơi game này.</strong>'
          + '<a href="/flashcard/library.html">Thêm thẻ vào bộ</a> hoặc chọn mục "Tất cả" để dùng bộ của người khác.'
        : '<strong>Chưa có bộ nào đủ thẻ để chơi game này.</strong>';
    }
    if (this.tab === 'RECENT') {
      return '<strong>Chưa có bộ nào gần đây.</strong>Bộ bạn mở ở trang Flashcard, đã học, làm kiểm tra hoặc đã chơi sẽ hiện ở đây.';
    }
    if (this.tab === 'ALL') {
      return '<strong>Chưa có bộ flashcard công khai nào.</strong>';
    }
    return '<strong>Bạn chưa có bộ flashcard nào.</strong>'
      + '<a href="/flashcard/library.html">Tạo bộ flashcard</a> hoặc chọn mục "Tất cả" để dùng bộ của người khác.';
  };

  /** Chọn một bộ (từ danh sách hoặc theo id có sẵn); tải chi tiết để biết cặp hỏi/đáp nào đủ thẻ. */
  DeckPicker.prototype.select = function (deckOrId) {
    var self = this;
    var setId = typeof deckOrId === 'object' ? deckOrId.id : deckOrId;
    if (typeof deckOrId === 'object') self.selected = deckOrId;
    self.renderList();
    var panel = self.container.querySelector('[data-deck-detail]');
    panel.hidden = false;
    panel.innerHTML = '<p class="ar-muted">Đang mở bộ thẻ…</p>';
    return arena.api.deck(setId).then(function (detail) {
      self.selected = detail.deck;
      self.detail = detail;
      self.renderList();
      self.renderDetail();
      // Chỉ cuộn khi bộ đang chọn bị khuất (vd. mở từ link); bấm chọn trong danh sách thì giữ nguyên chỗ đang xem.
      var list = self.container.querySelector('[data-deck-list]');
      var current = list.querySelector('[aria-checked="true"]');
      if (current) {
        var box = list.getBoundingClientRect();
        var item = current.getBoundingClientRect();
        if (item.top < box.top) list.scrollTop -= box.top - item.top + 4;
        else if (item.bottom > box.bottom) list.scrollTop += item.bottom - box.bottom + 4;
      }
      self.onSelect(detail.deck, detail);
      return detail;
    }).catch(function (error) {
      panel.innerHTML = '<p class="ar-error-text">' + esc(error.message) + '</p>';
      throw error;
    });
  };

  DeckPicker.prototype.renderDetail = function () {
    var detail = this.detail;
    var panel = this.container.querySelector('[data-deck-detail]');
    if (!detail) {
      panel.hidden = true;
      return;
    }
    var samples = (detail.samples || []).map(function (card) {
      return '<div class="ar-sample"><strong>' + esc(card.kanji || card.reading || '—') + '</strong>'
        + (card.reading ? esc(card.reading) + '<br>' : '')
        + esc(card.meaning || '') + (card.hanViet ? ' · <span class="ar-muted">' + esc(card.hanViet) + '</span>' : '')
        + '</div>';
    }).join('');
    panel.hidden = false;
    panel.innerHTML = '<h3>Đã chọn: ' + esc(detail.deck.title) + ' <span class="ar-muted">· ' + detail.deck.cardCount + ' thẻ</span></h3>'
      + (samples ? '<div class="ar-samples">' + samples + '</div>' : '');
  };

  // ------------------------------------------------------------------ Field picker

  function renderFieldPicker(container, config) {
    var detail = config.detail;
    var need = minWords(config.gameType, config.options);
    function options(selected, exclude) {
      return FIELDS.filter(function (field) { return field !== exclude; }).map(function (field) {
        var count = detail ? (detail.deck.coverage[field] || 0) : 0;
        return '<option value="' + field + '"' + (field === selected ? ' selected' : '') + '>'
          + esc(arena.fieldLabel(field)) + (detail ? ' (' + count + ' thẻ có)' : '') + '</option>';
      }).join('');
    }
    var count = usable(detail, config.question, config.answer);
    var enough = count >= need;
    container.innerHTML = ''
      + '<div class="ar-fields">'
      + '  <div><label class="ar-label" for="ar-q-field">Nội dung câu hỏi</label>'
      + '    <select id="ar-q-field" class="ar-select" data-q>' + options(config.question, null) + '</select></div>'
      + '  <button type="button" class="ar-icon-btn ar-swap" data-swap aria-label="Đổi chỗ câu hỏi và đáp án" title="Đổi chỗ câu hỏi và đáp án">'
      + '    <i class="fa-solid fa-right-left" aria-hidden="true"></i></button>'
      + '  <div><label class="ar-label" for="ar-a-field">Nội dung đáp án</label>'
      + '    <select id="ar-a-field" class="ar-select" data-a>' + options(config.answer, config.question) + '</select></div>'
      + '</div>'
      + (detail ? '<p class="ar-pair-note' + (enough ? '' : ' is-bad') + '" role="status">'
        + (enough
          ? count + ' thẻ có đủ ' + esc(arena.fieldLabel(config.question)) + ' và ' + esc(arena.fieldLabel(config.answer)) + '.'
          : 'Chỉ ' + count + ' thẻ có đủ ' + esc(arena.fieldLabel(config.question)) + ' và ' + esc(arena.fieldLabel(config.answer))
            + '; cần ít nhất ' + need + ' thẻ. Chọn cặp khác hoặc bộ khác.')
        + '</p>' : '');

    var q = container.querySelector('[data-q]');
    var a = container.querySelector('[data-a]');
    q.addEventListener('change', function () {
      var answer = config.answer === q.value ? FIELDS.find(function (f) { return f !== q.value; }) : config.answer;
      config.onChange(q.value, answer);
    });
    a.addEventListener('change', function () { config.onChange(config.question, a.value); });
    container.querySelector('[data-swap]').addEventListener('click', function () {
      config.onChange(config.answer, config.question);
    });
  }

  // ------------------------------------------------------------------ Options

  function renderOptions(container, config) {
    var item = config.catalogItem;
    var options = config.options || {};
    var parts = [];
    var usableCards = usable(config.detail, config.question, config.answer);
    function pick(key) {
      return function (value) {
        var next = Object.assign({}, options);
        next[key] = /^\d+$/.test(value) ? Number(value) : value;
        if (key === 'objective' && value !== 'TIME_ATTACK') next.totalSeconds = null;
        if (key === 'objective' && value === 'TIME_ATTACK' && !next.totalSeconds) next.totalSeconds = 300;
        config.onChange(next);
      };
    }

    if (item.type === 'NEKO_CATCH') {
      parts.push(segmented('difficulty', 'Độ khó', item.choices.difficulty.map(function (value) {
        return { value: value, label: DIFFICULTY_LABELS[value] || value };
      }), options.difficulty, pick('difficulty')));
      parts.push(segmented('durationSeconds', 'Thời gian mỗi người', item.choices.durationSeconds.map(function (value) {
        return { value: value, label: value + ' giây' };
      }), options.durationSeconds, pick('durationSeconds')));
    } else if (item.type === 'MEMORY_MATCH') {
      parts.push(segmented('boardSize', 'Số thẻ trên bàn', item.choices.boardSize.map(function (value) {
        var enough = !config.detail || usableCards >= value / 2;
        return { value: value, label: value + ' thẻ', disabled: !enough,
          reason: 'Bộ này chỉ có ' + usableCards + ' thẻ dùng được, chưa đủ cho bàn này.' };
      }), options.boardSize, pick('boardSize')));
      parts.push(segmented('turnSeconds', 'Thời gian mỗi lượt', item.choices.turnSeconds.map(function (value) {
        return { value: value, label: value + ' giây' };
      }), options.turnSeconds, pick('turnSeconds')));
      parts.push(segmented('objective', 'Mục tiêu', [
        { value: 'CLASSIC', label: 'Lật hết bàn' },
        { value: 'TIME_ATTACK', label: 'Có giới hạn giờ' }
      ], options.objective || 'CLASSIC', pick('objective')));
      if (options.objective === 'TIME_ATTACK') {
        parts.push(segmented('totalSeconds', 'Tổng thời gian', item.choices.totalSeconds.map(function (value) {
          return { value: value, label: (value / 60) + ' phút' };
        }), options.totalSeconds || 300, pick('totalSeconds')));
      }
    } else if (item.type === 'AIR_DEFENSE') {
      if (config.mode === 'VERSUS') {
        parts.push(segmented('durationSeconds', 'Thời gian trận', item.choices.durationSeconds.map(function (value) {
          return { value: value, label: (value / 60) + ' phút' };
        }), options.durationSeconds, pick('durationSeconds')));
      }
    }

    container.innerHTML = '<div class="ar-options">' + parts.map(function (part) { return part.html; }).join('')
      + (item.type === 'MEMORY_MATCH'
        ? '<label class="ar-check"><input type="checkbox" data-keep-turn' + (options.keepTurnOnMatch !== false ? ' checked' : '')
          + '> Ghép đúng thì được lật tiếp</label>' : '')
      + '</div>';
    parts.forEach(function (part) { part.bind(container); });
    var keep = container.querySelector('[data-keep-turn]');
    if (keep) {
      keep.addEventListener('change', function () {
        config.onChange(Object.assign({}, options, { keepTurnOnMatch: keep.checked }));
      });
    }
  }

  /** Giá trị mặc định + kẹp tuỳ chọn vào danh sách cho phép của game (vd. bàn Memory theo số thẻ có). */
  function normalizeOptions(item, mode, current, detail, question, answer) {
    var defaults = item.defaults || {};
    var options = Object.assign({}, defaults, current || {});
    if (item.type === 'NEKO_CATCH') {
      return { difficulty: options.difficulty || 'NORMAL', durationSeconds: options.durationSeconds || 60 };
    }
    if (item.type === 'MEMORY_MATCH') {
      var cards = usable(detail, question, answer);
      var sizes = item.choices.boardSize.slice();
      var board = options.boardSize || 20;
      if (detail && cards < board / 2) {
        var fitting = sizes.filter(function (size) { return size / 2 <= cards; });
        board = fitting.length ? fitting[fitting.length - 1] : sizes[0];
      }
      return {
        boardSize: board,
        turnSeconds: options.turnSeconds || 15,
        objective: options.objective || 'CLASSIC',
        totalSeconds: options.objective === 'TIME_ATTACK' ? (options.totalSeconds || 300) : null,
        keepTurnOnMatch: options.keepTurnOnMatch !== false
      };
    }
    return { durationSeconds: mode === 'VERSUS' ? (options.durationSeconds || 180) : 0 };
  }

  function describeOptions(gameType, options, mode) {
    options = options || {};
    if (gameType === 'NEKO_CATCH') {
      var preset = options.difficulty === 'EASY' ? [5, 3] : options.difficulty === 'HARD' ? [2, 7] : [3, 5];
      return (options.lives == null ? preset[0] : options.lives) + ' tim · '
        + (options.choices == null ? preset[1] : options.choices) + ' cá · ' + (options.durationSeconds || 60) + ' giây'
        + (options.cardIds ? ' · ' + options.cardIds.length + ' thẻ' : '');
    }
    if (gameType === 'MEMORY_MATCH') {
      return (options.boardSize || 20) + ' thẻ · ' + (options.turnSeconds || 15) + ' giây/lượt'
        + (options.objective === 'TIME_ATTACK' ? ' · tổng ' + Math.round((options.totalSeconds || 300) / 60) + ' phút' : '')
        + (options.keepTurnOnMatch === false ? ' · ghép đúng vẫn đổi lượt' : ' · ghép đúng được lật tiếp')
        + (options.distinguishCardTypes ? ' · hai màu' : ' · cùng màu');
    }
    return mode === 'SOLO' || !options.durationSeconds ? 'Không giới hạn' : Math.round(options.durationSeconds / 60) + ' phút';
  }

  global.JlasArenaSetup = {
    DeckPicker: DeckPicker,
    renderFieldPicker: renderFieldPicker,
    renderOptions: renderOptions,
    normalizeOptions: normalizeOptions,
    describeOptions: describeOptions,
    bestPair: bestPair,
    usable: usable,
    minWords: minWords,
    FIELDS: FIELDS
  };
})(window);
