/**
 * Hướng dẫn dùng trang hội thoại AI Call: từng bước chỉ vào phần đang nói tới (nhiệm vụ, lời AI, gợi ý, micro,
 * ô nhập, công cụ học, từ vựng). Tự mở lần đầu ở mỗi loại (kịch bản / trò chuyện tự do); nút "Hướng dẫn" mở lại.
 * Bước nào không thấy trên màn hình (ví dụ thanh bên đã vào Cài đặt trên điện thoại) thì bỏ qua.
 *
 * Phần được chỉ không được nâng lên trên lớp mờ (khung chat có backdrop-filter, nâng z-index không thoát ra được),
 * nên một khung "spotlight" đặt đúng chỗ nó: bóng đổ rất rộng làm mờ phần còn lại, lớp chặn bấm có lỗ đúng khung đó
 * để người học vẫn thử được nút đang được giới thiệu.
 */
(function () {
  'use strict';
  const body = document.body;
  const openButton = document.getElementById('btnConversationGuide');
  if (!openButton) return;
  const freeChat = body.dataset.freeChat === 'true';
  const ended = body.dataset.ended === 'true';
  const key = 'jlas.aicall.conversationGuide.' + (freeChat ? 'free' : 'scenario') + '.v1';
  const GAP = 16, PAD = 6;
  let panel, blocker, spotlight, target, frame = 0, step = 0, steps = [];

  const card = selector => () => document.querySelector(selector)?.closest('.side-card');
  const canType = () => !document.getElementById('messageInput')?.disabled;
  const STEPS = freeChat ? [
    [() => document.getElementById('chatMessages'), 'Trò chuyện tự do với AI',
      'Nói về bất cứ chủ đề nào bằng tiếng Nhật. Bấm “Phát” để nghe lại một câu, “Chép” để sao chép. Bôi đen một từ tiếng Nhật để tra nghĩa và lưu vào sổ tay.'],
    [() => !document.getElementById('btnMic')?.disabled && canType() && document.getElementById('btnMic'), 'Trả lời bằng giọng nói',
      'Bấm micro rồi nói tiếng Nhật. Câu nhận được hiện trong ô nhập để bạn xem lại trước khi gửi.'],
    [() => canType() && document.querySelector('.compose-row'), 'Hoặc gõ rồi gửi',
      'Gõ tiếng Nhật rồi nhấn Enter hoặc nút gửi. Nếu câu có lỗi, thẻ “Sửa lỗi” hiện ngay dưới câu của bạn.'],
    [card('#fcPersonality'), 'Chọn kiểu AI bạn muốn nói chuyện',
      'Đổi tính cách, trình độ, giọng nói và mức chủ động của AI. Chọn chủ đề yêu thích để AI gợi chuyện theo sở thích của bạn.'],
    [card('#btnConversationActive'), 'Công cụ khi trò chuyện',
      'Bật “AI chủ động” để AI dẫn dắt câu chuyện. Bật hoặc tắt furigana, bản dịch tiếng Việt, phản hồi sửa lỗi, tự phát âm; chọn giọng, tốc độ đọc và bấm “Thử giọng” để nghe.'],
    [() => document.getElementById('btnConversationSettings'), 'Kiểu AI và công cụ ở Cài đặt',
      'Trên điện thoại, phần chọn kiểu AI, chủ đề và các công cụ nằm trong nút “Cài đặt”.']
  ] : [
    [card('#missionList'), 'Mục tiêu của cuộc gọi',
      'Đây là các nhiệm vụ cần làm trong hội thoại. Mỗi khi bạn nói được ý đó, ô được đánh dấu và thanh tiến độ tăng. Xong hết thì bạn xem được kết quả.'],
    [() => document.getElementById('chatMessages'), 'Nghe và đọc lời AI',
      'AI đóng vai người bạn đang nói chuyện. Bấm “Phát” để nghe lại câu, “Chép” để sao chép. Bôi đen một từ tiếng Nhật để tra nghĩa và lưu vào sổ tay.'],
    [() => canType() && document.querySelector('#suggestRow:not([hidden]):has(.suggest-chip)'), 'Bí thì dùng gợi ý',
      'Bấm một gợi ý để điền nó vào ô nhập. Bạn sửa lại cho đúng ý mình rồi gửi.'],
    [() => !document.getElementById('btnMic')?.disabled && canType() && document.getElementById('btnMic'), 'Trả lời bằng giọng nói',
      'Bấm micro rồi nói tiếng Nhật. Câu nhận được hiện trong ô nhập để bạn xem lại trước khi gửi.'],
    [() => canType() && document.querySelector('.compose-row'), 'Hoặc gõ rồi gửi',
      'Gõ tiếng Nhật rồi nhấn Enter hoặc nút gửi. Nếu câu có lỗi, thẻ “Sửa lỗi” hiện ngay dưới câu của bạn kèm câu đúng và lý do.'],
    [card('.toggle-list [data-toggle="furigana"]'), 'Chỉnh công cụ học',
      'Bật hoặc tắt furigana, bản dịch tiếng Việt, phản hồi sửa lỗi, gợi ý trả lời và tự phát âm. Chọn giọng, tốc độ đọc và bấm “Thử giọng” để nghe.'],
    [card('#vocabList'), 'Từ vựng của bài',
      'Các từ nên dùng trong cuộc gọi này. Bấm một từ để xem nghĩa và lưu thành thẻ ghi nhớ. Phần “Ngữ pháp” ngay dưới là các mẫu câu nên dùng.'],
    [() => document.getElementById('btnConversationSettings'), 'Nhiệm vụ và công cụ ở Cài đặt',
      'Trên điện thoại, nhiệm vụ, công cụ học, từ vựng và ngữ pháp nằm trong nút “Cài đặt”.']
  ];

  function visible(element) {
    return !!element && element.getClientRects().length > 0 && !element.closest('[hidden]')
      && !element.closest('dialog:not([open])');
  }

  /** Khung của phần đang chỉ, thu lại trong màn hình (khung chat có thể cao hơn màn hình). */
  function box() {
    const rect = target.getBoundingClientRect();
    const left = Math.max(PAD, rect.left - PAD), top = Math.max(PAD, rect.top - PAD);
    const right = Math.min(innerWidth - PAD, rect.right + PAD), bottom = Math.min(innerHeight - PAD, rect.bottom + PAD);
    return { left, top, width: Math.max(0, right - left), height: Math.max(0, bottom - top) };
  }

  function place() {
    if (!panel || !target) return;
    const b = box();
    Object.assign(spotlight.style, { left: b.left + 'px', top: b.top + 'px', width: b.width + 'px', height: b.height + 'px' });
    const hole = 'M0 0H' + innerWidth + 'V' + innerHeight + 'H0Z M' + b.left + ' ' + b.top + 'h' + b.width + 'v' + b.height + 'h-' + b.width + 'Z';
    blocker.style.clipPath = 'path(evenodd, "' + hole + '")';
    const w = panel.offsetWidth, h = panel.offsetHeight;
    const fitsX = x => Math.max(GAP, Math.min(x, innerWidth - w - GAP));
    let x, y;
    if (b.top + b.height + GAP + h <= innerHeight - GAP) { y = b.top + b.height + GAP; x = fitsX(b.left); }
    else if (b.top - GAP - h >= GAP) { y = b.top - GAP - h; x = fitsX(b.left); }
    else if (b.left + b.width + GAP + w <= innerWidth - GAP) { x = b.left + b.width + GAP; y = b.top; }
    else if (b.left - GAP - w >= GAP) { x = b.left - GAP - w; y = b.top; }
    else { x = fitsX(b.left); y = innerHeight - h - GAP; }
    panel.style.left = x + 'px';
    panel.style.top = Math.max(GAP, Math.min(y, innerHeight - h - GAP)) + 'px';
  }

  // Tin nhắn mới, bàn phím điện thoại hay thanh bên đổi cỡ đều làm phần đang chỉ dịch đi, nên đặt lại mỗi khung hình.
  function follow() { place(); frame = requestAnimationFrame(follow); }

  function finish() {
    if (!panel) return;
    cancelAnimationFrame(frame);
    panel.remove(); blocker.remove(); spotlight.remove();
    panel = blocker = spotlight = target = null;
    document.removeEventListener('keydown', keyboard, true);
    try { localStorage.setItem(key, 'done'); } catch (_) { /* Storage may be disabled. */ }
    openButton.setAttribute('aria-expanded', 'false');
    openButton.focus({ preventScroll: true });
  }

  function keyboard(event) {
    if (!panel) return;
    if (event.key === 'Escape') { event.stopPropagation(); finish(); return; }
    if (event.key !== 'Tab') return;
    const selector = 'button:not(:disabled), a[href], input:not(:disabled), select:not([hidden]), [tabindex="0"]';
    const own = target.matches(selector) ? [target] : [...target.querySelectorAll(selector)].filter(visible);
    const controls = [...own, ...panel.querySelectorAll('button')];
    const index = controls.indexOf(document.activeElement);
    const next = index < 0 ? 0 : (index + (event.shiftKey ? -1 : 1) + controls.length) % controls.length;
    event.preventDefault();
    controls[next].focus({ preventScroll: true });
  }

  function show() {
    target = steps[step][0];
    panel.querySelector('small').textContent = 'Bước ' + (step + 1) + ' / ' + steps.length;
    panel.querySelector('h2').textContent = steps[step][1];
    panel.querySelector('p').textContent = steps[step][2];
    panel.querySelector('[data-guide-back]').hidden = step === 0;
    panel.querySelector('[data-guide-next]').textContent = step === steps.length - 1 ? 'Đã hiểu' : 'Tiếp';
    target.scrollIntoView({ block: 'nearest', behavior: 'instant' });
    place();
    panel.querySelector('[data-guide-next]').focus({ preventScroll: true });
  }

  function open() {
    if (panel) return;
    steps = STEPS.map(([find, title, text]) => [find(), title, text]).filter(entry => visible(entry[0]));
    if (!steps.length) return;
    step = 0;
    blocker = document.createElement('div');
    blocker.className = 'aicall-guide-blocker';
    blocker.setAttribute('aria-hidden', 'true');
    spotlight = document.createElement('div');
    spotlight.className = 'aicall-guide-spotlight';
    spotlight.setAttribute('aria-hidden', 'true');
    panel = document.createElement('section');
    panel.className = 'aicall-guide';
    panel.setAttribute('aria-label', 'Hướng dẫn dùng AI Call');
    panel.innerHTML = '<div aria-live="polite" aria-atomic="true"><small></small><h2></h2><p></p></div>'
      + '<nav><button type="button" data-guide-skip>Bỏ qua</button><button type="button" data-guide-back>Quay lại</button>'
      + '<button type="button" data-guide-next>Tiếp</button></nav>';
    document.body.append(blocker, spotlight, panel);
    panel.querySelector('[data-guide-skip]').addEventListener('click', finish);
    panel.querySelector('[data-guide-back]').addEventListener('click', function () { if (step > 0) { step--; show(); } });
    panel.querySelector('[data-guide-next]').addEventListener('click', function () {
      if (step < steps.length - 1) { step++; show(); } else finish();
    });
    document.addEventListener('keydown', keyboard, true);
    openButton.setAttribute('aria-expanded', 'true');
    show();
    follow();
  }

  openButton.addEventListener('click', open);
  // Mở Cài đặt ở bước cuối (điện thoại) là đã làm theo hướng dẫn: đóng hướng dẫn để hộp Cài đặt không bị che.
  document.getElementById('btnConversationSettings')?.addEventListener('click', function () {
    if (panel && target === this) finish();
  }, true);

  window.AiCallGuide = { open, finish };
  if (ended) return;
  let seen = false;
  try { seen = localStorage.getItem(key) === 'done'; } catch (_) { /* Still show the guide. */ }
  if (!seen) {
    // Chờ thanh bên chuyển vào Cài đặt (điện thoại) và lời chào đầu của AI hiện ra rồi mới chỉ.
    const start = () => setTimeout(open, 600);
    if (document.readyState === 'complete') start(); else window.addEventListener('load', start, { once: true });
  }
}());
