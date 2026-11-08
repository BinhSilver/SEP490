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
  function clock(seconds) {
    const total = Math.max(0, Math.round(seconds || 0));
    const h = Math.floor(total / 3600), m = Math.floor(total % 3600 / 60), s = total % 60;
    return (h ? h + ':' + String(m).padStart(2, '0') : m) + ':' + String(s).padStart(2, '0');
  }
  function fact(id, text) {
    const item = document.getElementById(id);
    if (!item || !text) return;
    item.querySelector('span').textContent = text;
    item.hidden = false;
  }
  // Fills the header: thumbnail, length, sentence count, practice estimate.
  function showDetails(video, lines) {
    const length = Number(video.duration) || (lines.length ? Number(lines[lines.length - 1].endTime) || 0 : 0);
    if (video.thumbnailPath) {
      const img = document.getElementById('practice-thumb-img');
      img.addEventListener('load', () => { img.hidden = false; document.getElementById('practice-thumb').classList.add('has-image'); });
      img.src = '/api/videos/' + encodeURIComponent(id) + '/thumbnail';
    }
    if (length > 0) {
      const badge = document.getElementById('practice-duration');
      badge.textContent = clock(length); badge.hidden = false;
      fact('practice-fact-length', 'Video ' + clock(length));
    }
    // Shadowing takes roughly three passes per sentence (listen, repeat, replay); 20 s per line without timing data.
    const minutes = Math.max(1, Math.round(length > 0 ? length * 3 / 60 : lines.length * 20 / 60));
    if (lines.length) fact('practice-fact-practice', 'Luyện khoảng ' + minutes + ' phút');
    if (!lines.length) return;
    // Speaking speed: Japanese characters (no spaces or punctuation) per second of spoken time.
    const chars = line => (line.text || '').replace(/[\s、。，．,.!?！？「」『』（）()…・ー〜~]/g, '').length;
    const totalChars = lines.reduce((sum, line) => sum + chars(line), 0);
    const spoken = lines.reduce((sum, line) => sum + Math.max(0, (Number(line.endTime) || 0) - (Number(line.startTime) || 0)), 0);
    if (spoken > 0 && totalChars > 0) {
      const rate = totalChars / spoken;
      const pace = rate < 4 ? 'Chậm' : rate <= 6.5 ? 'Vừa' : 'Nhanh';
      detail('speed', 'fa-gauge-high', 'Tốc độ nói', pace + ' · ' + rate.toFixed(1).replace('.', ',') + ' ký tự/giây');
    }
    detail('length', 'fa-text-width', 'Độ dài câu', 'Trung bình ' + Math.round(totalChars / lines.length) + ' ký tự');
    const translated = lines.filter(line => line.translation?.trim()).length;
    detail('translation', 'fa-language', 'Bản dịch tiếng Việt',
      translated === lines.length ? 'Đủ ' + lines.length + ' câu' : translated ? translated + '/' + lines.length + ' câu' : 'Chưa có');
    const furigana = lines.some(line => line.furigana?.trim());
    detail('furigana', 'fa-font', 'Furigana', furigana ? 'Có' : 'Chưa có');
  }
  // Label/value pairs under the facts; each key is set once and can be filled in any order.
  function detail(key, icon, label, value) {
    const box = document.getElementById('practice-details');
    let item = box.querySelector('[data-key="' + key + '"]');
    if (!item) {
      item = document.createElement('div'); item.dataset.key = key;
      const dt = document.createElement('dt'), i = document.createElement('i'), dd = document.createElement('dd');
      i.className = 'fa-solid ' + icon; i.setAttribute('aria-hidden', 'true');
      dt.append(i, document.createTextNode(label));
      item.append(dt, dd); box.append(item);
    }
    item.querySelector('dd').textContent = value;
    box.hidden = false;
  }
  // Level, topic tags, views and rating come from the library summary; the header works without them.
  async function showSummary() {
    try {
      const query = window.getClientId ? '?clientId=' + encodeURIComponent(window.getClientId()) : '';
      const response = await fetch('/api/videos/' + encodeURIComponent(id) + '/summary' + query, { headers: window.authHeaders?.(true) || {} });
      if (!response.ok) return;
      const summary = await response.json();
      if (summary.estimatedLevel) {
        fact('practice-fact-level', 'JLPT ' + summary.estimatedLevel);
        const source = summary.levelSource === 'AI' ? ' · AI ước tính' : '';
        if (summary.levelSource) detail('level', 'fa-signal', 'Cấp độ', summary.estimatedLevel + source);
      }
      if (summary.category) detail('category', 'fa-folder-open', 'Thể loại', window.getCategoryLabel ? window.getCategoryLabel(summary.category) : summary.category);
      if (Number(summary.commentCount) > 0) detail('comments', 'fa-comments', 'Bình luận', summary.commentCount + ' bình luận');
      if (summary.createdAt) {
        const created = new Date(summary.createdAt);
        if (!isNaN(created)) detail('created', 'fa-calendar-days', 'Ngày đăng', created.toLocaleDateString('vi-VN'));
      }
      if (summary.favorited) detail('favorite', 'fa-heart', 'Yêu thích', 'Đã lưu vào yêu thích');
      if (Number(summary.viewCount) > 0) fact('practice-fact-views', Number(summary.viewCount).toLocaleString('vi-VN') + ' lượt xem');
      if (Number(summary.ratingCount) > 0 && summary.avgRating != null) {
        fact('practice-fact-rating', Number(summary.avgRating).toFixed(1) + ' (' + summary.ratingCount + ' đánh giá)');
      }
      // The JLPT level already has its own chip, so level tags (N5..N1) are left out here.
      // Level tags have their own chip; the category is shown in the details. Names are shown in Vietnamese.
      const label = name => window.getCategoryLabel ? window.getCategoryLabel(name.trim()) : name.trim();
      const tags = [...new Set((summary.tags || [])
        .filter(name => name && !/^N[1-5]$/i.test(name.trim()) && name.trim() !== summary.category)
        .map(label))].slice(0, 6);
      if (tags.length) {
        const box = document.getElementById('practice-tags');
        box.replaceChildren(...tags.map(name => { const chip = document.createElement('span'); chip.textContent = name; return chip; }));
        box.hidden = false;
      }
    } catch (error) { /* optional details */ }
  }
  (async function () {
    try {
      const responses = await Promise.all([
        fetch('/api/videos/' + encodeURIComponent(id), { headers: window.authHeaders?.(true) || {} }),
        fetch('/api/videos/' + encodeURIComponent(id) + '/subtitles', { headers: window.authHeaders?.(true) || {} })
      ]);
      if (responses.some(res => !res.ok)) throw new Error('Không tải được bài học. Hãy tải lại trang.');
      const video = await responses[0].json();
      const subtitles = await responses[1].json();
      const lines = subtitles.filter(sub => sub.text?.trim());
      document.getElementById('practice-video-title').textContent = video.title || 'Bài luyện nghe';
      document.getElementById('practice-subtitle-count').textContent = lines.length + ' câu';
      showDetails(video, lines);
      showSummary();
      start.disabled = false;
    } catch (error) { status.textContent = error.message; }
  }());
}());
