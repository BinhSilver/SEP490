(function () {
  'use strict';
  const statuses = { COMPLETED: 'Đã hoàn thành', IN_PROGRESS: 'Đang học', NOT_STARTED: 'Chưa bắt đầu' };
  const esc = value => String(value == null ? '' : value).replace(/[&<>"']/g,
    char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]));
  function summary(items) {
    return { minutes: items.reduce((sum, item) => sum + item.estimatedMinutes, 0),
      days: new Set(items.map(item => item.dayNumber)).size,
      completed: items.filter(item => item.status === 'COMPLETED').length };
  }
  function render(path, state = { phase: 'ALL', status: 'ALL' }) {
    const total = summary(path.items);
    const percent = path.items.length ? Math.round(total.completed / path.items.length * 100) : 0;
    const next = path.items.find(item => item.status !== 'COMPLETED');
    const filtered = path.items.filter(item => (state.phase === 'ALL' || item.phase === state.phase)
      && (state.status === 'ALL' || item.status === state.status));
    return `<section class="pp-hero" aria-labelledby="path-title">
      <div><p class="pp-breadcrumb">Học tập <span aria-hidden="true">/</span> Lộ trình của tôi</p>
      <h1 id="path-title">${esc(path.title)}</h1>
      <p class="pp-intro">Ôn vững nền tảng, rồi tiến tới mục tiêu của bạn.<br>Lộ trình dựa trên khảo sát và kết quả Smart Test.</p>
      <div class="pp-hero-meta"><span>${esc(path.currentLevel)} <span aria-hidden="true">→</span> ${esc(path.targetLevel)}</span>
      <span>${path.items.length} bài học</span><span>${total.days} ngày dự kiến</span></div></div>
      <div class="pp-progress-card"><div class="pp-progress-label"><span>Tiến độ lộ trình</span><strong>${percent}%</strong></div>
      <progress value="${total.completed}" max="${path.items.length || 1}" aria-label="Tiến độ lộ trình"></progress>
      <p>${total.completed}/${path.items.length} bài đã hoàn thành</p>
      ${next ? `<button class="pp-button" data-lesson="${esc(next.id)}">Xem bài tiếp theo <span aria-hidden="true">↗</span></button>`
        : '<p class="pp-success">Bạn đã hoàn thành lộ trình này.</p>'}</div>
    </section>
    <div class="pp-layout"><div class="pp-main">
      <div class="pp-section-title"><h2>Hành trình của bạn</h2><span>2 giai đoạn nối tiếp</span></div>
      <div class="pp-phases">${path.phases.map((phase, i) => {
        const items = path.items.filter(item => item.phase === phase.code);
        const stats = summary(items);
        return `<button class="pp-phase pp-phase-${i + 1}" data-phase="${esc(phase.code)}" aria-pressed="${state.phase === phase.code}">
          <span class="pp-phase-number">${i + 1}</span><span class="pp-phase-body"><strong>${esc(phase.title)}</strong>
          <span>${esc(phase.description)}</span><small>${items.length} bài học · ${stats.days} ngày · ${stats.minutes} phút</small></span>
          <span aria-hidden="true">↗</span></button>`;
      }).join('')}</div>
      ${(path.coverageGaps || []).length ? `<aside class="pp-warning" role="note"><strong>Một phần kiến thức chưa có bài phù hợp</strong>
        ${path.coverageGaps.map(gap => `<p>${esc(gap.message || gap.tagCode)}</p>`).join('')}</aside>` : ''}
      <div class="pp-toolbar"><div class="pp-tabs" role="group" aria-label="Lọc giai đoạn">
        ${[['ALL', 'Tất cả'], ['WARM_UP', 'Khởi động'], ['GOAL_LEARNING', 'Theo mục tiêu']].map(([value, label]) =>
          `<button data-phase="${value}" aria-pressed="${state.phase === value}">${label}</button>`).join('')}</div>
        <label class="pp-status-label"><span class="pp-sr-only">Trạng thái bài học</span><select data-status aria-label="Trạng thái bài học">
          ${[['ALL', 'Mọi trạng thái'], ...Object.entries(statuses)].map(([value, label]) =>
            `<option value="${value}" ${state.status === value ? 'selected' : ''}>${label}</option>`).join('')}</select></label></div>
      <div class="pp-schedule" aria-label="Lịch học">${filtered.length ? path.phases.map(phase => {
        const items = filtered.filter(item => item.phase === phase.code);
        if (!items.length) return '';
        const days = [...new Set(items.map(item => item.dayNumber))].sort((a, b) => a - b);
        return `<section class="pp-phase-section"><h3>${esc(phase.title)}</h3>${days.map(day => {
          const group = items.filter(item => item.dayNumber === day);
          const done = group.every(item => item.status === 'COMPLETED');
          return `<details class="pp-day" ${done ? '' : 'open'}><summary><span class="pp-day-marker ${done ? 'pp-done' : ''}" aria-hidden="true">${done ? '✓' : day}</span>
            <span><strong>Ngày ${day}</strong><small>${group.length} bài học · ${summary(group).minutes} phút</small></span>
            <span class="pp-day-chevron" aria-hidden="true">⌄</span></summary>
            <div class="pp-lessons">${group.map(item => `<article class="pp-lesson"><span class="pp-lesson-symbol" aria-hidden="true">${item.skill === 'Nghe hiểu' ? '♫' : '文'}</span>
              <div class="pp-lesson-body"><div class="pp-lesson-meta"><span>${esc(item.skill)}</span><span>${item.estimatedMinutes} phút</span></div>
              <button class="pp-lesson-title" data-lesson="${esc(item.id)}">${esc(item.title)}</button>
              <p class="pp-reason">${esc(item.reason)}</p></div><span class="pp-status pp-status-${esc(item.status)}">${esc(statuses[item.status] || item.status)}</span>
            </article>`).join('')}</div></details>`;
        }).join('')}</section>`;
      }).join('') : '<div class="pp-no-match"><h3>Không có bài học phù hợp</h3><p>Thử chọn giai đoạn hoặc trạng thái khác.</p><button class="pp-button pp-button-secondary" data-reset>Xóa bộ lọc</button></div>'}
      ${path.phases.filter(phase => !path.items.some(item => item.phase === phase.code) && phase.emptyReason)
        .map(phase => `<p class="pp-note">${esc(phase.emptyReason)}</p>`).join('')}</div>
      </div><aside class="pp-sidebar" aria-label="Thông tin lộ trình"><section class="pp-survey-card"><h2>Đích đến của bạn</h2>
        <dl><div><dt>Nhu cầu</dt><dd>${esc(path.goal)}</dd></div><div><dt>Trình độ mục tiêu</dt><dd>${esc(path.targetLevel)}</dd></div>
        <div><dt>Thời gian mỗi ngày</dt><dd>${esc(path.minutesPerDay)} phút</dd></div><div><dt>Tổng thời lượng</dt><dd>${total.minutes} phút</dd></div></dl>
        <p class="pp-note">Lịch dự kiến giữ nguyên bài học. Một bài dài có thể vượt thời gian học mỗi ngày.</p></section>
        <section class="pp-weakness-card"><span class="pp-side-icon" aria-hidden="true">↺</span><h2>Vì sao bắt đầu bằng ôn tập?</h2>
        <p>Những điểm cần củng cố từ kết quả Smart Test:</p>
        ${(path.weaknesses || []).length ? `<ul>${path.weaknesses.map(tag => `<li><span>${esc(tag.label)}</span><strong>Sai ${tag.wrong}/${tag.assessed}</strong></li>`).join('')}</ul>`
          : '<p>Không có tag yếu đủ điều kiện trong kết quả này.</p>'}
        <p class="pp-note">Ôn đúng chỗ còn hổng để việc học kiến thức mới dễ dàng hơn.</p></section>
        <section class="pp-book"><span aria-hidden="true">本</span><div><small>Nguồn học theo mục tiêu</small><strong>${esc(path.textbook || 'Chương trình đã chọn')}</strong></div></section>
      </aside></div>`;
  }
  function renderEmpty() {
    return `<section class="pp-state"><span class="pp-state-icon" aria-hidden="true">道</span><h1>Bạn chưa có lộ trình học</h1>
      <p>Hoàn thành khảo sát và Smart Test để có lộ trình phù hợp với kiến thức hiện tại và mục tiêu của bạn.</p>
      <ol><li>Chia sẻ nhu cầu và mục tiêu học</li><li>Làm Smart Test để xác định kiến thức cần ôn</li><li>Nhận lộ trình gồm hai giai đoạn</li></ol>
      <p class="pp-note">Khảo sát và Smart Test chưa được kết nối trong bản xem trước này.</p>
      <button class="pp-button" data-demo-active>Xem lộ trình mẫu</button></section>`;
  }
  function renderError(message) {
    return `<section class="pp-state" role="alert"><span class="pp-state-icon" aria-hidden="true">!</span><h1>Chưa tải được lộ trình</h1>
      <p>${esc(message || 'Kiểm tra kết nối và thử lại.')}</p><button class="pp-button" data-retry>Thử tải lại</button></section>`;
  }
  function renderLoading() {
    return '<section class="pp-state" role="status"><div class="pp-spinner" aria-hidden="true"></div><h1>Đang tải lộ trình</h1><p>Đang chuẩn bị các giai đoạn học của bạn…</p></section>';
  }
  function init(root, source) {
    let path = null;
    let state = { phase: 'ALL', status: 'ALL' };
    let requestId = 0;
    const dialog = document.getElementById('pp-lesson-dialog');
    const scenario = document.getElementById('pp-demo-scenario');
    const paint = () => { root.innerHTML = path ? render(path, state) : renderEmpty(); };
    async function load(nextSource) {
      if (nextSource) source = nextSource;
      const current = ++requestId;
      root.setAttribute('aria-busy', 'true');
      root.innerHTML = renderLoading();
      try {
        const result = await source.load();
        if (current !== requestId) return;
        path = result;
        state = { phase: 'ALL', status: 'ALL' };
        paint();
      } catch (error) {
        if (current === requestId) root.innerHTML = renderError(error.message);
      } finally {
        if (current === requestId) root.removeAttribute('aria-busy');
      }
    }
    root.addEventListener('click', event => {
      const button = event.target.closest('button');
      if (!button) return;
      if (button.hasAttribute('data-phase')) {
        state.phase = button.dataset.phase; paint();
        root.querySelector(`.pp-tabs [data-phase="${state.phase}"]`).focus();
      } else if (button.hasAttribute('data-reset')) {
        state = { phase: 'ALL', status: 'ALL' }; paint(); root.querySelector('.pp-tabs button').focus();
      } else if (button.hasAttribute('data-retry')) load();
      else if (button.hasAttribute('data-demo-active')) {
        scenario.value = 'active'; load(window.PersonalizedPathData.demo('active'));
      } else if (button.hasAttribute('data-lesson') && path && dialog) {
        const item = path.items.find(value => String(value.id) === button.dataset.lesson);
        if (!item) return;
        dialog.querySelector('[data-dialog-body]').innerHTML = `<p class="pp-dialog-meta">Ngày ${item.dayNumber} · ${item.estimatedMinutes} phút · ${esc(item.skill)}</p>
          <h2 id="pp-dialog-title">${esc(item.title)}</h2><span class="pp-status pp-status-${esc(item.status)}">${esc(statuses[item.status])}</span>
          <h3>Bạn sẽ học gì?</h3><p>${esc(item.description || 'Nội dung chi tiết sẽ được cập nhật từ học liệu.')}</p>
          <h3>Vì sao bài này nằm trong lộ trình?</h3><p>${esc(item.reason)}</p>
          <p class="pp-note">Đây là thông tin bài học trong lộ trình mẫu. Trang học bài và ghi nhận tiến độ chưa được kết nối.</p>`;
        dialog.showModal();
      }
    });
    root.addEventListener('change', event => {
      if (event.target.matches('[data-status]')) {
        state.status = event.target.value; paint(); root.querySelector('[data-status]').focus();
      }
    });
    if (dialog) dialog.querySelector('[data-close-dialog]').addEventListener('click', () => dialog.close());
    if (scenario) scenario.addEventListener('change', () => {
      if (scenario.value === 'loading') {
        ++requestId; root.innerHTML = renderLoading(); root.setAttribute('aria-busy', 'true');
      } else load(window.PersonalizedPathData.demo(scenario.value));
    });
    return load();
  }
  const api = { render, renderEmpty, renderError, renderLoading, init };
  if (typeof module !== 'undefined') module.exports = api;
  if (typeof window !== 'undefined') {
    window.PersonalizedPath = api;
    document.addEventListener('DOMContentLoaded', () => {
      const root = document.getElementById('pp-root');
      if (root) init(root, window.PersonalizedPathData.demo('active'));
    });
  }
})();
