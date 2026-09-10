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
  const status = document.getElementById('practice-status');
  const customCount = document.getElementById('practice-custom-count');
  let selectedMode = 'SHADOWING';
  let subtitleCount = 0;

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

  document.querySelectorAll('input[name="practice-count"]').forEach(function (input) {
    input.addEventListener('change', function () {
      customCount.hidden = input.value !== 'CUSTOM';
      if (!customCount.hidden) customCount.focus();
    });
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
      customCount.max = String(Math.max(1, subtitleCount));
    } catch (error) {
      message(error.message || 'Không tải được bài học', true);
      startButton.disabled = true;
    }
  }

  function selectedCount() {
    const choice = document.querySelector('input[name="practice-count"]:checked')?.value || '10';
    if (choice === 'ALL') return { allQuestions: true, questionCount: null };
    const raw = choice === 'CUSTOM' ? Number(customCount.value) : Number(choice);
    const value = Math.max(1, Math.min(subtitleCount || 500, Number.isFinite(raw) ? raw : 10));
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
    message('AI chỉ phân tích các câu chưa có trong ngân hàng; bước đầu có thể mất một lúc.');
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
