/**
 * Behaviour shared by the Onyomi pages: Japanese read-aloud buttons, folding stages on the path page,
 * the lesson sidebar that follows the section in view, the flashcard deck, and links that open once a
 * quiz is passed.
 */
(function () {
  'use strict';

  /* Read-aloud: any [data-speak] element reads its text in Japanese. */
  function speak(text) {
    if (!text || !('speechSynthesis' in window)) return;
    window.speechSynthesis.cancel();
    var utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = 'ja-JP';
    utterance.rate = 0.85;
    window.speechSynthesis.speak(utterance);
  }

  document.addEventListener('click', function (event) {
    var button = event.target.closest('[data-speak]');
    if (button) speak(button.dataset.speak);
  });

  /* Path page on phones: a stage's lessons stay folded until its header is tapped. */
  document.addEventListener('click', function (event) {
    var toggle = event.target.closest('.oy-stage__toggle');
    if (!toggle) return;
    var open = toggle.closest('.oy-stage').classList.toggle('is-open');
    toggle.setAttribute('aria-expanded', String(open));
  });

  /* Sidebar: highlight the section currently in view. */
  var links = Array.prototype.slice.call(document.querySelectorAll('.oy-sidebar nav a[href^="#"]'));
  var sections = links
    .map(function (link) { return document.getElementById(link.getAttribute('href').slice(1)); })
    .filter(Boolean);

  function updateSidebar() {
    var marker = window.innerHeight * 0.35;
    var current = sections[0];
    sections.forEach(function (section) {
      if (section.getBoundingClientRect().top <= marker) current = section;
    });
    links.forEach(function (link) {
      link.classList.toggle('is-active', link.getAttribute('href') === '#' + current.id);
    });
  }

  if (sections.length) {
    window.addEventListener('scroll', updateSidebar, { passive: true });
    updateSidebar();
  }

  /* Flashcards: one card at a time; click to flip, arrows to move. */
  var deck = document.querySelector('[data-flashcards]');
  if (deck) {
    var cards = Array.prototype.slice.call(deck.querySelectorAll('.oy-card'));
    var counter = deck.querySelector('[data-card-counter]');
    var previous = deck.querySelector('[data-card="prev"]');
    var following = deck.querySelector('[data-card="next"]');
    var speakCard = deck.querySelector('[data-card-speak]');
    var index = 0;

    var show = function (target) {
      if (!cards.length) return;
      index = Math.max(0, Math.min(cards.length - 1, target));
      cards.forEach(function (card, i) {
        card.classList.toggle('is-active', i === index);
        if (i !== index) {
          card.classList.remove('is-flipped');
          card.querySelector('.oy-card__flip').setAttribute('aria-pressed', 'false');
        }
      });
      if (counter) counter.textContent = (index + 1) + ' / ' + cards.length;
      if (previous) previous.disabled = index === 0;
      if (following) following.disabled = index === cards.length - 1;
      if (speakCard) speakCard.dataset.speak = cards[index].dataset.reading || '';
    };

    deck.addEventListener('click', function (event) {
      var flip = event.target.closest('.oy-card__flip');
      if (flip) {
        var flipped = flip.closest('.oy-card').classList.toggle('is-flipped');
        flip.setAttribute('aria-pressed', String(flipped));
        return;
      }
      var move = event.target.closest('[data-card]');
      if (move) show(index + (move.dataset.card === 'next' ? 1 : -1));
    });

    deck.addEventListener('keydown', function (event) {
      if (event.key === 'ArrowRight') show(index + 1);
      if (event.key === 'ArrowLeft') show(index - 1);
    });

    show(0);
  }

  /* Links marked data-unlock-on="<quiz kind>" open once that quiz is passed on this page. */
  document.addEventListener('onyomi:quiz-passed', function (event) {
    var kind = event.detail && event.detail.kind;
    if (!kind) return;
    document.querySelectorAll('[data-unlock-on="' + kind + '"]').forEach(function (link) {
      link.setAttribute('href', link.dataset.href);
      link.removeAttribute('aria-disabled');
      link.removeAttribute('tabindex');
    });
    document.querySelectorAll('[data-unlock-hint="' + kind + '"]').forEach(function (hint) {
      hint.hidden = true;
    });
    if (kind === 'practice') {
      document.querySelectorAll('[data-lesson-status]').forEach(function (status) {
        status.textContent = 'Hoàn thành';
        status.className = 'oy-pill oy-pill--done';
      });
    }
  });
})();
