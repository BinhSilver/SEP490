(function () {
  'use strict';
  const key = 'jlas.shadowing.sessionGuide.v1';
  let panel, scrim, target, step = 0, steps = [];
  const openButton = document.getElementById('practice-guide-open');
  function place() {
    if (!panel || !target) return;
    const rect = target.getBoundingClientRect();
    const height = panel.offsetHeight;
    const below = rect.bottom + 16;
    const above = rect.top - height - 16;
    const y = below + height <= innerHeight - 16 ? below : above >= 16 ? above : innerHeight - height - 16;
    panel.style.top = Math.max(16, y) + 'px';
    panel.style.left = Math.max(16, Math.min(rect.left, innerWidth - panel.offsetWidth - 16)) + 'px';
  }
  function finish() {
    if (!panel) return;
    target?.classList.remove('session-guide-target');
    panel.remove(); scrim.remove(); panel = null; target = null;
    window.removeEventListener('resize', place);
    window.removeEventListener('scroll', place, true);
    document.removeEventListener('keydown', keyboard);
    try { localStorage.setItem(key, 'done'); } catch (_) { /* Storage may be disabled. */ }
    openButton.focus();
  }
  function keyboard(event) {
    if (event.key === 'Escape') { finish(); return; }
    if (event.key !== 'Tab' || !panel) return;
    const selector = 'button:not(:disabled), a[href], input:not(:disabled), [tabindex="0"]';
    const controls = [...(target.matches(selector) ? [target] : target.querySelectorAll(selector)),
      ...panel.querySelectorAll('button')];
    const index = controls.indexOf(document.activeElement);
    const nextIndex = index < 0 ? 0 : (index + (event.shiftKey ? -1 : 1) + controls.length) % controls.length;
    event.preventDefault(); controls[nextIndex].focus({ preventScroll: true });
  }
  function show() {
    target?.classList.remove('session-guide-target');
    const entry = steps[step];
    target = document.querySelector(entry[0]);
    target.classList.add('session-guide-target');
    panel.querySelector('small').textContent = 'Bước ' + (step + 1) + ' / ' + steps.length;
    panel.querySelector('h2').textContent = entry[1];
    panel.querySelector('p').textContent = entry[2];
    panel.querySelector('[data-guide-next]').textContent = step === steps.length - 1 ? 'Đã hiểu' : 'Tiếp';
    target.scrollIntoView({ block: 'center', behavior: 'instant' });
    place();
    const focusable = target.matches('button, a') ? target : target.querySelector('input:not(:disabled), button:not(:disabled)');
    (focusable || panel.querySelector('[data-guide-next]')).focus({ preventScroll: true });
  }
  function next() { if (step < steps.length - 1) { step++; show(); } else finish(); }
  function open() {
    if (panel) return;
    const hasQuestions = !!document.querySelector('.practice-kind-card input:not(:disabled)');
    steps = hasQuestions ? [
      ['.practice-kind-card:has(input:not(:disabled))', 'Chọn dạng câu và mức nghe', 'Bấm vào Dễ, Vừa hoặc Khó trong dạng câu bạn muốn luyện. Mỗi ô cho biết số câu có sẵn.'],
      ['.practice-selection-actions', 'Chọn nhanh khi cần', '“Chọn tất cả” chọn các mức có câu hỏi. “Bỏ chọn tất cả” giúp bạn chọn lại từ đầu. Bạn có thể thử nút hoặc bấm Tiếp.'],
      ['.practice-selected-count', 'Kiểm tra số câu của phiên học', 'Mỗi mức bạn chọn bắt đầu với 1 câu; bấm + hoặc − bên cạnh để đổi số câu, tối đa bằng số câu mức đó có. Số câu cập nhật theo lựa chọn; trong cùng một mức, các câu nghe trùng đoạn chỉ tính một lần.'],
      ['#practice-start-button', 'Bắt đầu khi đã chọn xong', 'Chọn ít nhất một mức có câu hỏi, rồi bấm “Bắt đầu luyện nghe” để vào bài.']
    ] : [
      ['.practice-kind-card', 'Video chưa có câu hỏi để luyện', 'Các mức đang hiển thị 0 câu sẽ chưa chọn được. Khi câu hỏi được chuẩn bị và duyệt, chúng sẽ xuất hiện tại đây.'],
      ['#practice-contribute-link', 'Chuẩn bị câu hỏi cho video', 'Bấm “Đóng góp câu hỏi” để mở phần chuẩn bị. Sau khi có câu được duyệt, quay lại trang này để luyện nghe.']
    ];
    step = 0;
    scrim = document.createElement('div'); scrim.className = 'session-guide-scrim'; scrim.setAttribute('aria-hidden', 'true');
    panel = document.createElement('section'); panel.className = 'session-guide'; panel.setAttribute('aria-label', 'Hướng dẫn thiết lập phiên học');
    panel.innerHTML = '<div aria-live="polite" aria-atomic="true"><small></small><h2></h2><p></p></div><nav><button type="button" data-guide-skip>Bỏ qua</button><button type="button" data-guide-next>Tiếp</button></nav>';
    document.body.append(scrim, panel);
    panel.querySelector('[data-guide-skip]').addEventListener('click', finish);
    panel.querySelector('[data-guide-next]').addEventListener('click', next);
    window.addEventListener('resize', place);
    window.addEventListener('scroll', place, true);
    document.addEventListener('keydown', keyboard);
    show();
  }
  openButton?.addEventListener('click', open);
  document.getElementById('practice-kind-options')?.addEventListener('change', function () { if (panel && step === 0 && steps.length === 4) next(); });
  ['practice-select-all', 'practice-clear-all'].forEach(id => document.getElementById(id)?.addEventListener('click', function () { if (panel && step === 1) next(); }));
  window.ShadowingSessionGuide = {
    finish,
    init: function ({ autoOpen = true } = {}) {
      openButton.disabled = false;
      if (!autoOpen) return;
      try { if (localStorage.getItem(key) === 'done') return; } catch (_) { /* Still allow the guide. */ }
      open();
    }
  };
}());
