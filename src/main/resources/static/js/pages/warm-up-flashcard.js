/* [JLAS-W0108] Màn ôn tập flashcard warm-up — tái dùng FlashApi + flip-card CSS. */
(function (global) {
  'use strict';

  var state = {
    setId: null,
    cards: [],
    index: 0,
    remembered: 0,
    flipped: false
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
  }

  function render() {
    var card = state.cards[state.index] || {};
    el('wu-face-front').textContent = card.front || card.kanji || '';
    el('wu-face-back').innerHTML = cardBack(card);
    el('wu-progress-label').textContent = (state.index + 1) + ' / ' + state.cards.length;
    el('wu-progress-fill').style.width = ((state.index / state.cards.length) * 100) + '%';
    setFlipped(false);
  }

  function next(result) {
    if (result === 'REMEMBERED') state.remembered++;
    state.index++;
    if (state.index >= state.cards.length) { finish(); return; }
    render();
  }

  function finish() {
    el('wu-stage').hidden = true;
    el('wu-complete').hidden = false;
    el('wu-complete-summary').textContent =
      'Bạn đã ôn xong ' + state.cards.length + ' thẻ, nhớ ' + state.remembered + ' thẻ.';
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
    global.FlashApi.get('/api/flashcards/' + state.setId)
      .then(function (data) {
        state.cards = (data && data.cards) || [];
        if (!state.cards.length) {
          fail('Bộ thẻ rỗng — quay lại lộ trình và thử lại.');
          return;
        }
        el('wu-loading').hidden = true;
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
  el('wu-remembered').addEventListener('click', function () { next('REMEMBERED'); });
  el('wu-not-remembered').addEventListener('click', function () { next('NOT_REMEMBERED'); });

  global.WarmUpFlashcard = { boot: boot };
  document.addEventListener('DOMContentLoaded', boot);
})(window);
