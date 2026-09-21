(function () {
  'use strict';

  const videoId = document.getElementById('practice-video-id')?.value;
  if (!videoId) return;

  const title = document.getElementById('practice-video-title');
  const countLabel = document.getElementById('practice-subtitle-count');
  const cards = Array.from(document.querySelectorAll('.practice-mode-card'));
  const options = document.getElementById('practice-options');
  const startButton = document.getElementById('practice-start-button');
  const startLabel = startButton.querySelector('span');
  const startKitsu = document.getElementById('practice-start-kitsu');
  const headerKitsu = document.getElementById('practice-kitsu');
  const status = document.getElementById('practice-status');
  const customCount = document.getElementById('practice-custom-count');
  let selectedMode = 'SHADOWING';
  let subtitleCount = 0;

  function playHeaderKitsu() {
    if (!headerKitsu || window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return;

    const playback = headerKitsu.play();
    if (playback && typeof playback.catch === 'function') {
      playback.catch(function () {
        // Muted autoplay is normally allowed; the next browser interaction retries playback.
      });
    }
  }

  if (headerKitsu) {
    headerKitsu.addEventListener('loadeddata', playHeaderKitsu, { once: true });
    headerKitsu.addEventListener('canplay', playHeaderKitsu, { once: true });
    document.addEventListener('pointerdown', playHeaderKitsu, { once: true, passive: true });
    playHeaderKitsu();
  }

  function validQuestionCount(value) {
    const numeric = Number(String(value || '').replace(/\D/g, ''));
    const fallback = 5;
    const limit = subtitleCount || 500;
    return Math.max(1, Math.min(limit, Number.isFinite(numeric) && numeric > 0 ? numeric : fallback));
  }

  customCount?.addEventListener('beforeinput', function (event) {
    if (event.data && !/^\d+$/.test(event.data)) event.preventDefault();
  });

  customCount?.addEventListener('input', function () {
    const digitsOnly = this.value.replace(/\D/g, '');
    if (this.value !== digitsOnly) this.value = digitsOnly;
  });

  customCount?.addEventListener('blur', function () {
    this.value = String(validQuestionCount(this.value));
  });

  function headers() {
    return window.authHeaders ? window.authHeaders(true) : { 'Content-Type': 'application/json' };
  }

  function message(value, error) {
    status.textContent = value || '';
    status.style.color = error ? '#a71919' : '';
  }

  function selectMode(mode) {
    selectedMode = mode;
    cards.forEach(function (card) {
      const selected = card.dataset.mode === mode;
      card.classList.toggle('is-selected', selected);
      card.setAttribute('aria-pressed', String(selected));
    });
    options.hidden = mode === 'SHADOWING';
    startLabel.textContent = mode === 'SHADOWING'
      ? 'Bắt đầu Shadowing'
      : mode === 'CLOZE' ? 'Bắt đầu điền từ' : 'Bắt đầu điền cả câu';
    startKitsu.pause();
    try { startKitsu.currentTime = 0; } catch (_) { /* metadata may not be loaded yet */ }
    startKitsu.hidden = true;
    message('');
  }

  cards.forEach(function (card) {
    card.addEventListener('click', function () { selectMode(card.dataset.mode); });
  });

  async function loadLesson() {
    try {
      const responses = await Promise.all([
        fetch('/api/videos/' + encodeURIComponent(videoId), { headers: headers() }),
        fetch('/api/videos/' + encodeURIComponent(videoId) + '/subtitles', { headers: headers() })
      ]);
      if (!responses[0].ok || !responses[1].ok) throw new Error('Không tải được bài học');
      const video = await responses[0].json();
      const subtitles = await responses[1].json();
      title.textContent = video.title || 'Bài luyện nghe';
      subtitleCount = Array.isArray(subtitles) ? subtitles.filter(item => item.text?.trim()).length : 0;
      countLabel.textContent = subtitleCount + ' câu';
    } catch (error) {
      message(error.message || 'Không tải được bài học', true);
      startButton.disabled = true;
    }
  }

  function selectedCount() {
    const value = validQuestionCount(customCount?.value);
    return { allQuestions: false, questionCount: value };
  }

  startButton.addEventListener('click', async function () {
    if (selectedMode === 'SHADOWING') {
      location.href = '/shadowing/player/' + encodeURIComponent(videoId) + '?mode=shadowing';
      return;
    }
    const count = selectedCount();
    const order = document.querySelector('input[name="practice-order"]:checked')?.value || 'PROGRESSIVE';
    startButton.disabled = true;
    startKitsu.hidden = false;
    try { startKitsu.currentTime = 0; } catch (_) { /* metadata may not be loaded yet */ }
    const mascotPlayback = startKitsu.play();
    if (mascotPlayback?.catch) mascotPlayback.catch(function () { /* poster remains visible */ });
    startLabel.textContent = 'Kitsu-chan đang chuẩn bị…';
    message('');
    try {
      const response = await fetch('/api/shadowing-practice/sessions', {
        method: 'POST',
        headers: headers(),
        body: JSON.stringify({
          videoId: Number(videoId),
          mode: selectedMode,
          order: order,
          questionCount: count.questionCount,
          allQuestions: count.allQuestions
        })
      });
      const data = await response.json().catch(function () { return {}; });
      if (!response.ok) throw new Error(data.message || data.error || 'Không tạo được phiên học');
      location.href = '/shadowing/exercise/' + encodeURIComponent(data.sessionId);
    } catch (error) {
      message(error.message || 'Không tạo được phiên học', true);
      startButton.disabled = false;
      startKitsu.pause();
      startKitsu.hidden = true;
      startLabel.textContent = selectedMode === 'CLOZE' ? 'Bắt đầu điền từ' : 'Bắt đầu điền cả câu';
    }
  });

  loadLesson();
}());
