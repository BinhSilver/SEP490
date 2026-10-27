(function (global) {
  'use strict';
  var esc = global.JlasArena.esc;
  var FIELDS = ['KANJI', 'HIRAGANA', 'MEANING', 'HAN_VIET'];
  var LABELS = { KANJI: 'Kanji', HIRAGANA: 'Cách đọc', MEANING: 'Nghĩa', HAN_VIET: 'Hán Việt' };
  var DURATIONS = [60, 90, 120, 180, 300, 600, 900, 1200, 1800, 3600];
  function value(card, field) { return card[{ KANJI: 'kanji', HIRAGANA: 'reading', MEANING: 'meaning', HAN_VIET: 'hanViet' }[field]]; }
  function durationLabel(seconds) { return seconds < 180 ? seconds + 's' : seconds / 60 + ' phút'; }
  function recommendDuration(count, game) {
    if (game === 'MEMORY_MATCH') {
      var targetPairs = Math.max(6, Math.min(count, 10)) * 15;
      return [180, 300, 600].reduce(function (best, time) { return Math.abs(time - targetPairs) < Math.abs(best - targetPairs) ? time : best; });
    }
    var choices = game === 'AIR_DEFENSE' ? [120, 180, 300] : DURATIONS;
    var target = count * (game === 'AIR_DEFENSE' ? 24 : 14);
    return choices.reduce(function (best, time) { return Math.abs(time - target) < Math.abs(best - target) ? time : best; });
  }
  function memoryPairs(cards, question, answer) {
    var prompts = new Set(), answers = new Set(), count = 0;
    cards.forEach(function (card) {
      var prompt = value(card, question), response = value(card, answer);
      if (!prompt || !response) return;
      prompt = prompt.toLowerCase(); response = response.toLowerCase();
      if (prompts.has(prompt) || answers.has(response)) return;
      prompts.add(prompt); answers.add(response); count++;
    });
    return Math.min(count, 10);
  }
  function render(root, config) {
    var previous = root.querySelector('[data-room-cards]');
    var top = previous ? previous.scrollTop : 0;
    var previousRules = root.querySelector('.ar-room-setup__versus');
    var rulesOpen = previousRules && previousRules.open;
    var focused = document.activeElement;
    var focusKey = root.contains(focused) ? focused.getAttribute('data-focus-key') : null;
    if (!config.cards) { root.innerHTML = '<p class="ar-muted">Đang tải thẻ…</p>'; return { problem: 'Đang tải thẻ…' }; }
    var options = Object.assign({}, config.options), cards = config.cards;
    var wanted = options.cardIds == null ? null : new Set(options.cardIds.map(String));
    var usable = cards.filter(function (card) { return value(card, config.question) && value(card, config.answer); });
    var selected = usable.filter(function (card) { return wanted === null || wanted.has(String(card.id)); });
    var memory = config.gameType === 'MEMORY_MATCH';
    var pairs = memory ? memoryPairs(selected, config.question, config.answer) : 0;
    var suggested = recommendDuration(memory ? pairs : selected.length, config.gameType);
    options.cardIds = selected.map(function (card) { return card.id; });
    var maxFish = Math.max(2, Math.min(global.matchMedia('(max-width: 900px)').matches ? 5 : 7,
      new Set(selected.map(function (card) { return value(card, config.answer); })).size));
    if (config.gameType === 'NEKO_CATCH') {
      options.difficulty = options.difficulty || 'NORMAL';
      options.lives = options.lives == null ? 3 : options.lives;
      options.choices = Math.min(options.choices == null ? 5 : options.choices, maxFish);
    }
    if (memory) {
      options.boardSize = Math.max(12, pairs * 2);
      options.distinguishCardTypes = !!options.distinguishCardTypes;
    } else if (!config.manualDuration) options.durationSeconds = suggested;
    config.options = options;
    function fields(key, label, current, other) {
      return '<div class="ar-room-setup__field"><span>' + label + '</span><div role="group" aria-label="' + label + '">'
        + FIELDS.map(function (field) { return '<button type="button" data-' + key + '="' + field
          + '" data-focus-key="' + key + '-' + field + '" aria-pressed="' + (field === current) + '"'
          + (field === other ? ' disabled' : '') + '>' + LABELS[field] + '</button>'; }).join('') + '</div></div>';
    }
    function select(key, label, choices, current, format) {
      return '<label>' + label + '<select data-option="' + key + '" data-focus-key="' + key + '" aria-label="' + label + '">'
        + choices.map(function (number) { return '<option value="' + number + '"' + (number === current ? ' selected' : '') + '>'
          + format(number) + '</option>'; }).join('') + '</select></label>';
    }
    root.innerHTML = '<div class="ar-room-setup__fields">'
      + fields('question', 'Hỏi bằng', config.question, config.answer) + fields('answer', 'Đáp bằng', config.answer, config.question) + '</div>'
      + '<div class="ar-room-setup__cards-head"><strong>Thẻ · ' + selected.length + '/' + cards.length + ' đã chọn</strong>'
      + '<span><button type="button" data-all>Chọn tất cả</button><button type="button" data-none>Bỏ chọn hết</button></span></div>'
      + '<div class="ar-room-setup__cards" data-room-cards>' + cards.map(function (card) {
        var ok = !!value(card, config.question) && !!value(card, config.answer);
        var checked = selected.some(function (entry) { return entry.id === card.id; });
        return '<label><input type="checkbox" data-card="' + card.id + '" data-focus-key="card-' + card.id + '"'
          + (checked ? ' checked' : '') + (ok ? '' : ' disabled') + '><strong>' + esc(value(card, config.question) || card.kanji || '—')
          + '</strong><small>' + esc(ok ? value(card, config.answer) : 'Thiếu dữ liệu') + '</small></label>';
      }).join('') + '</div>'
      + (memory ? '<p class="ar-room-setup__board" aria-live="polite">Bàn ' + pairs * 2 + ' thẻ · ' + pairs + ' cặp</p>' : '')
      + '<div class="ar-room-setup__options' + (memory ? ' ar-room-setup__options--memory' : config.gameType === 'AIR_DEFENSE' ? ' ar-room-setup__options--air' : '') + '">'
      + (memory ? select('distinguishCardTypes', 'Màu thẻ', [0, 1], options.distinguishCardTypes ? 1 : 0,
          function (color) { return color ? 'Hai màu' : 'Cùng màu'; })
        + select('memoryTime', 'Thời gian', [0, 180, 300, 600], options.objective === 'TIME_ATTACK' ? options.totalSeconds || 300 : 0,
          function (time) { return time ? durationLabel(time) + (time === suggested ? ' · Gợi ý' : '') : 'Không giới hạn'; })
        : select('durationSeconds', 'Thời gian', config.gameType === 'AIR_DEFENSE' ? [120, 180, 300] : DURATIONS,
        options.durationSeconds, function (time) { return durationLabel(time) + (time === suggested ? ' · Gợi ý' : ''); })
      + (config.gameType === 'NEKO_CATCH' ? select('lives', 'Số tim', [1, 2, 3, 4, 5], options.lives, String)
        + select('choices', 'Số cá / lượt', Array.from({ length: maxFish - 1 }, function (_, index) { return index + 2; }), options.choices, String) : ''))
      + '</div><p class="ar-room-setup__suggestion">Gợi ý: ' + durationLabel(suggested) + ' cho ' + (memory ? pairs + ' cặp.' : selected.length + ' thẻ.') + '</p>'
      + (memory ? '<details class="ar-room-setup__versus"' + (rulesOpen ? ' open' : '') + '><summary>Luật đấu hai người</summary><div class="ar-room-setup__options ar-room-setup__options--air">'
        + select('turnSeconds', 'Thời gian mỗi lượt', [10, 15, 20], options.turnSeconds || 15, function (time) { return time + ' giây'; })
        + '</div><label class="ar-check"><input type="checkbox" data-keep-turn' + (options.keepTurnOnMatch !== false ? ' checked' : '')
        + '> Ghép đúng được lật tiếp</label></details>' : '');
    root.querySelector('[data-room-cards]').scrollTop = top;
    if (focusKey) {
      var target = Array.from(root.querySelectorAll('[data-focus-key]')).find(function (node) { return node.dataset.focusKey === focusKey; });
      if (target) target.focus({ preventScroll: true });
    }
    function emit(changes) { config.onChange(Object.assign({ question: config.question, answer: config.answer,
      options: options, manualDuration: config.manualDuration }, changes)); }
    root.querySelectorAll('[data-question],[data-answer]').forEach(function (button) {
      button.onclick = function () { var change = {}; change[button.dataset.question ? 'question' : 'answer'] = button.dataset.question || button.dataset.answer; emit(change); };
    });
    root.querySelector('[data-all]').onclick = function () { emit({ options: Object.assign({}, options, { cardIds: null }) }); };
    root.querySelector('[data-none]').onclick = function () { emit({ options: Object.assign({}, options, { cardIds: [] }) }); };
    root.querySelectorAll('[data-card]').forEach(function (input) {
      input.onchange = function () {
        var next = new Set(options.cardIds);
        if (input.checked) next.add(Number(input.dataset.card)); else next.delete(Number(input.dataset.card));
        emit({ options: Object.assign({}, options, { cardIds: Array.from(next) }) });
      };
    });
    root.querySelectorAll('[data-option]').forEach(function (input) {
      input.onchange = function () { var next = Object.assign({}, options);
        if (input.dataset.option === 'memoryTime') { next.objective = Number(input.value) ? 'TIME_ATTACK' : 'CLASSIC'; next.totalSeconds = Number(input.value) || null; }
        else if (input.dataset.option === 'distinguishCardTypes') next.distinguishCardTypes = input.value === '1';
        else next[input.dataset.option] = Number(input.value);
        emit({ options: next, manualDuration: input.dataset.option === 'durationSeconds' ? true : config.manualDuration }); };
    });
    var keepTurn = root.querySelector('[data-keep-turn]');
    if (keepTurn) keepTurn.onchange = function () { emit({ options: Object.assign({}, options, { keepTurnOnMatch: keepTurn.checked }) }); };
    var min = memory ? 6 : config.gameType === 'AIR_DEFENSE' ? 5 : 4;
    return { options: options, problem: (memory ? pairs : selected.length) < min ? 'Chọn ít nhất ' + min + ' thẻ khác nhau, đủ nội dung hỏi và đáp.' : null };
  }
  global.JlasRoomSettings = { render: render, recommendDuration: recommendDuration, memoryPairs: memoryPairs };
})(window);
