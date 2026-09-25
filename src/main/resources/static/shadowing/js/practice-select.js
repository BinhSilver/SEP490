(function () {
  'use strict';

  const videoId = document.getElementById('practice-video-id')?.value;
  if (!videoId) return;

  const title = document.getElementById('practice-video-title');
  const countLabel = document.getElementById('practice-subtitle-count');
  const cards = Array.from(document.querySelectorAll('.practice-mode-card'));
  const options = document.getElementById('practice-options');
  const startButton = document.getElementById('practice-start-button');
  const startRow = document.getElementById('practice-start-row');
  const startLabel = startButton.querySelector('span');
  const startIcon = document.getElementById('practice-start-icon');
  const startKitsu = document.getElementById('practice-start-kitsu');
  const headerKitsu = document.getElementById('practice-kitsu');
  const status = document.getElementById('practice-status');
  const customCount = document.getElementById('practice-custom-count');
  let selectedMode = 'SHADOWING';
  let subtitleCount = 0;
  let creatingSession = false;
  let creationPaused = false;
  let createdSessionId = null;

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
    if (event.inputType.startsWith('insert') && event.data != null && /\D/.test(event.data)) {
      event.preventDefault();
    }
  });

  customCount?.addEventListener('keydown', function (event) {
    if (event.key.length === 1 && !/\d/.test(event.key) && !event.ctrlKey && !event.metaKey) {
      event.preventDefault();
    }
  });

  customCount?.addEventListener('paste', function (event) {
    event.preventDefault();
    const digits = (event.clipboardData?.getData('text') || '').replace(/\D/g, '');
    if (!digits) return;
    this.value = digits.slice(0, 3);
    this.dispatchEvent(new Event('input', { bubbles: true }));
  });

  customCount?.addEventListener('input', function () {
    const digitsOnly = this.value.replace(/\D/g, '').slice(0, 3);
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

  function startText() {
    return selectedMode === 'SHADOWING' ? 'Bắt đầu Shadowing'
      : selectedMode === 'CLOZE' ? 'Bắt đầu điền từ' : 'Bắt đầu điền cả câu';
  }

  function setAction(label, icon) {
    startLabel.textContent = label;
    startIcon.className = 'fa-solid fa-' + icon;
  }

  function showPreparing(show) {
    startKitsu.hidden = !show;
    startRow.classList.toggle('has-mascot', show);
    if (!show) {
      startKitsu.pause();
      return;
    }
    const playback = startKitsu.play();
    if (playback?.catch) playback.catch(function () { /* The poster remains visible. */ });
  }

  function selectMode(mode) {
    if (creatingSession) return;
    selectedMode = mode;
    cards.forEach(function (card) {
      const selected = card.dataset.mode === mode;
      card.classList.toggle('is-selected', selected);
      card.setAttribute('aria-pressed', String(selected));
    });
    options.hidden = mode === 'SHADOWING';
    setAction(startText(), 'play');
    showPreparing(false);
    try { startKitsu.currentTime = 0; } catch (_) { /* metadata may not be loaded yet */ }
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
    if (creatingSession) {
      creationPaused = !creationPaused;
      if (creationPaused) {
        showPreparing(false);
        setAction('Tiếp tục', 'play');
        message(createdSessionId ? 'Phiên học đã sẵn sàng.' : 'Đã dừng chuẩn bị phiên học.');
      } else if (createdSessionId) {
        location.href = '/shadowing/exercise/' + encodeURIComponent(createdSessionId);
      } else {
        showPreparing(true);
        setAction('Dừng', 'stop');
        message('Kitsu-chan đang chuẩn bị...');
      }
      return;
    }
    if (selectedMode === 'SHADOWING') {
      location.href = '/shadowing/player/' + encodeURIComponent(videoId) + '?mode=shadowing';
      return;
    }
    const count = selectedCount();
    const order = document.querySelector('input[name="practice-order"]:checked')?.value || 'PROGRESSIVE';
    creatingSession = true;
    creationPaused = false;
    createdSessionId = null;
    try { startKitsu.currentTime = 0; } catch (_) { /* metadata may not be loaded yet */ }
    showPreparing(true);
    setAction('Dừng', 'stop');
    message('Kitsu-chan đang chuẩn bị...');
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
      createdSessionId = data.sessionId;
      if (creationPaused) {
        message('Phiên học đã sẵn sàng.');
        return;
      }
      location.href = '/shadowing/exercise/' + encodeURIComponent(createdSessionId);
    } catch (error) {
      message(error.message || 'Không tạo được phiên học', true);
      creatingSession = false;
      creationPaused = false;
      createdSessionId = null;
      showPreparing(false);
      setAction(startText(), 'play');
    }
  });

  loadLesson();
}());
