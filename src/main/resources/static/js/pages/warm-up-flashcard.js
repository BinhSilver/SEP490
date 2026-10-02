/* [JLAS-W0108] Màn ôn tập flashcard warm-up — tái dùng FlashApi + flip-card CSS. */
(function (global) {
  'use strict';

  var state = {
    setId: null,
    cards: [],
    index: 0,
    remembered: 0,
    flipped: false,
    saving: false,
    reviewing: false
  };

  function el(id) { return document.getElementById(id); }

  function esc(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function cardBack(card) {
    var parts = [];
    if (card.reading) parts.push('<div class="wu-back__reading" lang="ja">' + esc(card.reading) + '</div>');
    if (card.meaning) parts.push('<div class="wu-back__meaning">' + esc(card.meaning) + '</div>');
    if (card.hanViet) parts.push('<div class="wu-back__hanviet">Hán Việt: ' + esc(card.hanViet) + '</div>');
    return parts.join('');
  }

  function setFlipped(value) {
    state.flipped = value;
    el('wu-flip-inner').classList.toggle('is-flipped', value);
    el('wu-remembered').disabled = !value || state.saving;
    el('wu-not-remembered').disabled = !value || state.saving;
  }

  function render() {
    var card = state.cards[state.index] || {};
    el('wu-face-front').textContent = card.front || card.kanji || '';
    el('wu-face-back').innerHTML = cardBack(card);
    state.remembered = state.cards.filter(function (c) { return c.memoryStatus === 'REMEMBERED'; }).length;
    if (state.reviewing) {
      el('wu-progress-label').textContent = 'Ôn lại ' + (state.index + 1) + ' / ' + state.cards.length;
      el('wu-progress-fill').style.width = (((state.index + 1) / state.cards.length) * 100) + '%';
    } else {
      el('wu-progress-label').textContent = 'Đã nhớ ' + state.remembered + ' / ' + state.cards.length;
      el('wu-progress-fill').style.width = ((state.remembered / state.cards.length) * 100) + '%';
    }
    setFlipped(false);
  }

  async function next(result) {
    if (!state.flipped || state.saving || !state.cards[state.index]) return;
    state.saving = true;
    setFlipped(true);
    el('wu-save-status').textContent = 'Đang lưu…';
    try {
      var card = state.cards[state.index];
      await global.FlashApi.post('/api/learning/warm-up/flashcards/' + encodeURIComponent(state.setId) +
        '/cards/' + encodeURIComponent(card.id) + '/progress', { memoryStatus: result });
      card.memoryStatus = result;
      state.remembered = state.cards.filter(function (c) { return c.memoryStatus === 'REMEMBERED'; }).length;
      state.saving = false;
      if (state.reviewing) {
        // Ôn lại: lướt tuần tự qua mọi thẻ (kể cả thẻ đã nhớ), hết bộ thì quay về màn hoàn thành.
        state.index = state.index + 1;
        if (state.index >= state.cards.length) { finish(); return; }
        render();
        el('wu-save-status').textContent = 'Đã lưu.';
      } else {
        if (state.remembered === state.cards.length) { finish(); return; }
        do { state.index = (state.index + 1) % state.cards.length; }
        while (state.cards[state.index].memoryStatus === 'REMEMBERED');
        render();
        el('wu-save-status').textContent = result === 'NOT_REMEMBERED'
          ? 'Đã lưu. Những thẻ chưa thuộc sẽ tiếp tục được ôn lại.' : 'Đã lưu tiến độ.';
      }
    } catch (err) {
      state.saving = false;
      setFlipped(true);
      el('wu-save-status').textContent = (err.message || 'Không lưu được tiến độ.') + ' Hãy thử lại.';
    }
  }

  function finish() {
    el('wu-stage').hidden = true;
    el('wu-complete').hidden = false;
    el('wu-complete-summary').textContent =
      'Đã lưu: bạn đã nhớ đủ ' + state.cards.length + ' thẻ. Quay lại lộ trình để tiếp tục; bài kiểm tra vẫn được đánh giá riêng.';
  }

  function review() {
    el('wu-complete').hidden = true;
    el('wu-stage').hidden = false;
    state.reviewing = true;
    state.index = 0;
    render();
  }

  function fail(message) {
    el('wu-loading').innerHTML =
      '<p>Không tải được thẻ ôn tập.</p><p class="muted">' + esc(message) + '</p>';
  }

  function boot() {
    state.setId = document.body.getAttribute('data-set-id');
    if (!state.setId) {
      global.location.replace('/learning/path');
      return;
    }
    return global.FlashApi.get('/api/flashcards/' + state.setId)
      .then(function (data) {
        state.cards = (data && data.cards) || [];
        if (!state.cards.length) {
          fail('Bộ thẻ rỗng — quay lại lộ trình và thử lại.');
          return;
        }
        el('wu-loading').hidden = true;
        state.index = state.cards.findIndex(function (card) { return card.memoryStatus !== 'REMEMBERED'; });
        if (state.index < 0) { state.remembered = state.cards.length; finish(); return; }
        el('wu-stage').hidden = false;
        render();
      })
      .catch(function (err) { fail(err && err.message ? err.message : 'Lỗi mạng.'); });
  }

  el('wu-flip-card').addEventListener('click', function () {
    if (!state.flipped) setFlipped(true);
  });
  el('wu-flip-card').addEventListener('keydown', function (event) {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      if (!state.flipped) setFlipped(true);
    }
  });
  el('wu-flip').addEventListener('click', function (event) {
    event.stopPropagation();
    setFlipped(!state.flipped);
  });
  el('wu-remembered').addEventListener('click', function () { return next('REMEMBERED'); });
  el('wu-not-remembered').addEventListener('click', function () { return next('NOT_REMEMBERED'); });
  el('wu-review-again').addEventListener('click', review);

  global.WarmUpFlashcard = { boot: boot };
  document.addEventListener('DOMContentLoaded', boot);
})(window);
