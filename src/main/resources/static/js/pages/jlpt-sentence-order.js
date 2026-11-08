(function () {
  'use strict';
  var blankPattern = /[_＿―—]+[★☆](?:[ \t]*[_＿―—]+)?|[★☆][_＿―—]*|[_＿―—]{2,}/g;
  function starPosition(question) {
    var prompt = String(question.text || question.html || '').replace(/<rt\b[^>]*>[\s\S]*?<\/rt>|<rp\b[^>]*>[\s\S]*?<\/rp>/gi, '').replace(/<[^>]+>/g, '');
    var blanks = prompt.match(blankPattern) || [];
    return blanks.length === 4 && blanks.filter(function (item) { return /[★☆]/.test(item); }).length === 1
      ? blanks.findIndex(function (item) { return /[★☆]/.test(item); }) : -1;
  }
  function enabled(question) {
    return String(question.questionType || question.typeCode || '').toUpperCase() === 'SENTENCE_COMPOSITION'
      && (question.options || []).length === 4 && starPosition(question) >= 0;
  }
  function key(question) {
    var order = question.correctOrder, numbers = question.options.map(function (option) { return Number(option.number); });
    if (!Array.isArray(order) || order.length !== 4 || new Set(order).size !== 4
      || !order.every(function (number) { return numbers.includes(number); })) return null;
    var star = starPosition(question);
    if (star < 0 || (question.correctOption != null && Number(question.correctOption) !== order[star])) return null;
    return order;
  }
  function create() { return { order: [null, null, null, null], checked: false, showCorrect: false }; }
  function complete(state) { return state.order.length === 4 && state.order.every(function (number) { return number != null; }) && new Set(state.order).size === 4; }
  function check(question, state) {
    if (!complete(state)) return { error: 'Hãy xếp đủ bốn mảnh câu trước khi xem đáp án.' };
    var correct = key(question);
    if (!correct) return { error: 'Câu này chưa có thứ tự đúng đầy đủ để chấm từng vị trí.' };
    var matches = state.order.map(function (number, index) { return number === correct[index]; });
    return { matches: matches, correct: matches.every(Boolean), selectedStar: state.order[starPosition(question)] };
  }
  function place(state, number, target) {
    if (state.checked || target < 0 || target > 3) return;
    var previous = state.order.indexOf(number);
    if (previous >= 0) state.order[previous] = state.order[target];
    state.order[target] = number;
    state.showCorrect = false;
  }
  function render(container, question, state, options) {
    var correct = key(question), display = state.showCorrect && correct ? correct : state.order, star = starPosition(question);
    function content(number) { return options.content(question.options.find(function (option) { return Number(option.number) === number; })); }
    container.innerHTML = '<section class="jlpt-sentence-order">' +
      '<div class="jlpt-sentence-order__slots" role="group" aria-label="Thứ tự câu">' + display.map(function (number, index) {
        var matched = state.checked && correct ? number === correct[index] : null;
        return '<div class="jlpt-sentence-order__position"><small>Vị trí ' + (index + 1) + (index === star ? ' ★' : '') + '</small>' +
          '<button type="button" data-order-slot="' + index + '"' + (state.checked ? ' disabled' : '') + (number != null && !state.checked ? ' draggable="true"' : '') +
          ' class="jlpt-sentence-order__piece' + (matched === true ? ' is-correct' : matched === false ? ' is-wrong' : '') + '" aria-label="Vị trí ' + (index + 1) + (index === star ? ', sao' : '') + '">' +
          (number == null ? '<span class="jlpt-sentence-order__empty">Chọn mảnh câu</span>' : '<b>' + number + '</b><span lang="ja">' + content(number) + '</span>') + '</button></div>';
      }).join('') + '</div><div class="jlpt-sentence-order__bank" role="group" aria-label="Các mảnh câu">' + question.options.filter(function (option) { return !display.includes(Number(option.number)); }).map(function (option) {
        return '<button type="button" class="jlpt-sentence-order__piece" data-order-option="' + option.number + '" draggable="true"' + (state.checked ? ' disabled' : '') + '><b>' + option.number + '</b><span lang="ja">' + options.content(option) + '</span></button>';
      }).join('') + '</div>' + (state.checked && correct && !state.showCorrect ? '<button type="button" class="jlpt-sentence-order__reveal" data-order-reveal>Hiển thị sắp xếp đúng</button>' : '') + '</section>';
    var host = container.firstElementChild;
    function changed(focusIndex) {
      options.change();
      var next = container.querySelector('[data-order-slot="' + focusIndex + '"]');
      if (next) next.focus();
    }
    host.onclick = function (event) {
      var button = event.target.closest('button');
      if (!button || button.disabled) return;
      if (button.hasAttribute('data-order-reveal')) { state.showCorrect = true; options.change(); return; }
      if (state.checked) return;
      if (button.hasAttribute('data-order-option')) {
        var target = state.order.indexOf(null);
        if (target >= 0) { place(state, Number(button.dataset.orderOption), target); changed(target); }
      } else if (button.hasAttribute('data-order-slot')) {
        var index = Number(button.dataset.orderSlot); state.order[index] = null; changed(index);
      }
    };
    host.onkeydown = function (event) {
      var slot = event.target.closest('[data-order-slot]');
      if (!slot || state.checked || !['ArrowLeft', 'ArrowRight'].includes(event.key)) return;
      var index = Number(slot.dataset.orderSlot), next = index + (event.key === 'ArrowLeft' ? -1 : 1);
      if (next < 0 || next > 3) return;
      event.preventDefault();
      var value = state.order[next]; state.order[next] = state.order[index]; state.order[index] = value; changed(next);
    };
    host.ondragstart = function (event) {
      var button = event.target.closest('button');
      if (!button || state.checked) { event.preventDefault(); return; }
      var number = button.hasAttribute('data-order-option') ? Number(button.dataset.orderOption) : state.order[Number(button.dataset.orderSlot)];
      if (number == null) { event.preventDefault(); return; }
      event.dataTransfer.setData('text/plain', String(number));
    };
    host.ondragover = function (event) { if (!state.checked && event.target.closest('[data-order-slot]')) event.preventDefault(); };
    host.ondrop = function (event) {
      var slot = event.target.closest('[data-order-slot]');
      if (!slot || state.checked) return;
      event.preventDefault();
      var number = Number(event.dataTransfer.getData('text/plain'));
      if (!question.options.some(function (option) { return Number(option.number) === number; })) return;
      var index = Number(slot.dataset.orderSlot); place(state, number, index); changed(index);
    };
  }
  window.JlptSentenceOrder = { enabled: enabled, key: key, create: create, complete: complete, check: check, render: render, starPosition: starPosition, place: place };
})();
