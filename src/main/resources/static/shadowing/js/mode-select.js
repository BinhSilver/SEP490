(function () {
  'use strict';
  const id = document.getElementById('practice-video-id')?.value;
  if (!id) return;
  const start = document.getElementById('practice-start-button');
  const status = document.getElementById('practice-status');
  let mode = 'SHADOWING';
  const cards = Array.from(document.querySelectorAll('.practice-mode-card'));
  cards.forEach(card => card.addEventListener('click', function () {
    mode = card.dataset.mode;
    cards.forEach(item => {
      item.classList.toggle('is-selected', item === card);
      item.setAttribute('aria-pressed', String(item === card));
    });
    start.querySelector('span').textContent = mode === 'LISTENING' ? 'Thiết lập luyện nghe' : 'Bắt đầu Shadowing';
  }));
  start.addEventListener('click', async function () {
    if (mode !== 'LISTENING') {
      location.href = '/shadowing/player/' + encodeURIComponent(id) + '?mode=shadowing';
      return;
    }
    start.disabled = true;
    status.textContent = 'Đang kiểm tra bài tập…';
    try {
      const response = await fetch('/api/videos/' + encodeURIComponent(id) + '/practice-preparation/availability',
        { headers: window.authHeaders?.(true) || {} });
      if (!response.ok) throw new Error('Không tải được bộ câu hỏi đã chuẩn bị. Vui lòng thử lại.');
      const availability = await response.json();
      status.textContent = '';
      if (mode !== 'LISTENING') return;
      if (!window.ShadowingEmptyExercises.hasQuestions(availability)) {
        window.ShadowingEmptyExercises.show();
        return;
      }
      location.href = '/shadowing/practice/' + encodeURIComponent(id) + '/setup';
    } catch (error) {
      status.textContent = error.message || 'Không kiểm tra được bài tập. Vui lòng thử lại.';
    } finally {
      start.disabled = false;
    }
  });
  (async function () {
    try {
      const responses = await Promise.all([
        fetch('/api/videos/' + encodeURIComponent(id), { headers: window.authHeaders?.(true) || {} }),
        fetch('/api/videos/' + encodeURIComponent(id) + '/subtitles', { headers: window.authHeaders?.(true) || {} })
      ]);
      if (responses.some(res => !res.ok)) throw new Error('Không tải được bài học. Hãy tải lại trang.');
      const video = await responses[0].json();
      const subtitles = await responses[1].json();
      document.getElementById('practice-video-title').textContent = video.title || 'Bài luyện nghe';
      document.getElementById('practice-subtitle-count').textContent = subtitles.filter(sub => sub.text?.trim()).length + ' câu';
      start.disabled = false;
    } catch (error) { status.textContent = error.message; }
  }());
}());
