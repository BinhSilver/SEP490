/*
 * Chatbot gia sư J-LAS: nút nổi ở góc các trang học, trả lời câu hỏi tiếng Nhật bằng nội dung giáo trình có trích nguồn.
 * Tách khỏi AI Call. Lịch sử chỉ lưu trong tab hiện tại (sessionStorage); máy chủ không lưu cuộc trò chuyện.
 */
(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JlasTutorWidget = api;
  if (root && root.document) {
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', api.mount, { once: true });
    else api.mount();
  }
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';

  var STORAGE_KEY = 'jlas-tutor-chat';
  var MAX_STORED = 30;
  var HISTORY_SENT = 6;
  // Không hiện ở trang đăng nhập/quản trị, và không hiện trong bài kiểm tra để gia sư không làm bài hộ.
  var HIDDEN_PATHS = /^\/(login|register|forgot-password|verify-email|admin|appeal|smart-test|jlpt\/exam)(\/|$|\.)|\/[a-z-]*quiz(\/|$)/;
  var EXAMPLES = ['「勉強」 đọc và nghĩa là gì?', 'Mẫu N1 は N2 です dùng thế nào?', 'Câu 学校を行きました sai ở đâu?'];

  // Biểu tượng vẽ sẵn bằng SVG để không phụ thuộc font icon tải từ CDN (máy trong LAN có thể không ra Internet).
  function icon(path) {
    return '<svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" focusable="false">' +
      '<path fill="currentColor" d="' + path + '"/></svg>';
  }
  var ICONS = {
    cap: 'M12 3 1 9l11 6 9-4.9V17h2V9L12 3zm-6.8 9.2V16c0 1.9 3 3.5 6.8 3.5s6.8-1.6 6.8-3.5v-3.8L12 16l-6.8-3.8z',
    reset: 'M17.65 6.35A7.96 7.96 0 0 0 12 4a8 8 0 1 0 7.73 10h-2.08A6 6 0 1 1 12 6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z',
    close: 'M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z',
    send: 'M2.01 21 23 12 2.01 3 2 10l15 2-15 2z'
  };

  var state = { messages: [], maxChars: 500, busy: false, open: false };
  var els = {};

  function escapeHtml(value) {
    return String(value == null ? '' : value).replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#039;');
  }

  function inlineMarkup(escaped) {
    return escaped
      .replace(/`([^`\n]+)`/g, '<code>$1</code>')
      .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
      .replace(/(^|[^*])\*([^*\s][^*]*)\*(?!\*)/g, '$1<em>$2</em>')
      .replace(/\[([DS]\d{1,2})\]/g, '<span class="tutor-cite">$1</span>');
  }

  // Model hay trả markdown đơn giản. Thoát HTML trước, rồi chỉ dựng lại tiêu đề, danh sách, đậm/nghiêng, `mã` và mã nguồn [S1];
  // bảng markdown được đổi thành dòng chữ, bỏ hàng kẻ |---|.
  function formatAnswer(text) {
    var html = [];
    var list = null;
    function closeList() {
      if (list) html.push('</' + list + '>');
      list = null;
    }
    String(text == null ? '' : text).split(/\r?\n/).forEach(function (raw) {
      var line = raw.trim();
      var bullet = line.match(/^[-*•]\s+(.*)$/);
      var numbered = line.match(/^\d+[.)]\s+(.*)$/);
      if (bullet || numbered) {
        var type = bullet ? 'ul' : 'ol';
        if (list !== type) { closeList(); html.push('<' + type + '>'); list = type; }
        html.push('<li>' + inlineMarkup(escapeHtml((bullet || numbered)[1])) + '</li>');
        return;
      }
      closeList();
      if (!line) return;
      if (/^\|?\s*:?-{2,}/.test(line) && /^[|:\-\s]+$/.test(line)) return;
      var heading = line.match(/^#{1,6}\s+(.*)$/);
      if (heading) { html.push('<p class="tutor-h">' + inlineMarkup(escapeHtml(heading[1])) + '</p>'); return; }
      if (/^\|.*\|$/.test(line)) {
        line = line.replace(/^\||\|$/g, '').split('|').map(function (cell) { return cell.trim(); }).join(' · ');
      }
      html.push('<p>' + inlineMarkup(escapeHtml(line)) + '</p>');
    });
    closeList();
    return html.join('');
  }

  function isHiddenPath(pathname) {
    return HIDDEN_PATHS.test(String(pathname || ''));
  }

  function historyForRequest(messages) {
    return messages.filter(function (m) { return m.role === 'user' || m.role === 'assistant'; })
      .slice(-HISTORY_SENT).map(function (m) { return { role: m.role, content: m.content }; });
  }

  function load() {
    try {
      var raw = sessionStorage.getItem(STORAGE_KEY);
      var parsed = raw ? JSON.parse(raw) : null;
      state.messages = parsed && Array.isArray(parsed.messages) ? parsed.messages.slice(-MAX_STORED) : [];
    } catch (e) {
      state.messages = [];
    }
  }

  function save() {
    try {
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify({ messages: state.messages.slice(-MAX_STORED) }));
    } catch (e) { /* trình duyệt chặn lưu thì vẫn chat được trong trang này */ }
  }

  function headers() {
    var auth = window.JlasAuth;
    return auth && auth.authHeaders ? auth.authHeaders() : { 'Content-Type': 'application/json' };
  }

  async function request(url, options) {
    var response = await fetch(url, Object.assign({ headers: headers(), credentials: 'same-origin' }, options || {}));
    var payload = null;
    try { payload = await response.json(); } catch (e) { payload = null; }
    if (!response.ok || !payload || !payload.success) {
      throw new Error(payload && payload.message ? payload.message : 'Gia sư chưa trả lời được, bạn thử lại sau nhé.');
    }
    return payload.data;
  }

  function sourcesMarkup(sources) {
    if (!sources || !sources.length) return '';
    return '<details class="tutor-sources"><summary>Nguồn (' + sources.length + ')</summary><ul>' +
      sources.map(function (s) {
        return '<li><span class="tutor-cite">' + escapeHtml(s.code) + '</span> <span lang="ja">' + escapeHtml(s.title) +
          '</span><small>' + escapeHtml(s.detail) + '</small></li>';
      }).join('') + '</ul></details>';
  }

  function messageMarkup(message) {
    if (message.role === 'user') {
      return '<div class="tutor-msg tutor-msg--user" lang="ja">' + escapeHtml(message.content) + '</div>';
    }
    if (message.role === 'error') {
      return '<div class="tutor-msg tutor-msg--error" role="alert">' + escapeHtml(message.content) + '</div>';
    }
    return '<div class="tutor-msg tutor-msg--bot"><div class="tutor-msg__text">' + formatAnswer(message.content) + '</div>' +
      sourcesMarkup(message.sources) + '</div>';
  }

  function introMarkup() {
    return '<div class="tutor-intro"><p>Hỏi mình về từ vựng, kanji, ngữ pháp trong giáo trình J-LAS. ' +
      'Mình trả lời kèm nguồn để bạn kiểm tra lại.</p><div class="tutor-examples">' +
      EXAMPLES.map(function (q) {
        return '<button type="button" data-tutor-example="' + escapeHtml(q) + '" lang="ja">' + escapeHtml(q) + '</button>';
      }).join('') + '</div></div>';
  }

  function render() {
    els.log.innerHTML = (state.messages.length ? '' : introMarkup()) + state.messages.map(messageMarkup).join('') +
      (state.busy ? '<div class="tutor-msg tutor-msg--bot tutor-typing" aria-label="Gia sư đang trả lời"><span></span><span></span><span></span></div>' : '');
    els.log.scrollTop = els.log.scrollHeight;
    els.send.disabled = state.busy;
    els.counter.textContent = els.input.value.length + '/' + state.maxChars;
  }

  async function ask(question) {
    var q = String(question || '').trim();
    if (!q || state.busy) return;
    if (q.length > state.maxChars) {
      state.messages.push({ role: 'error', content: 'Câu hỏi dài quá, tối đa ' + state.maxChars + ' ký tự.' });
      render();
      return;
    }
    var history = historyForRequest(state.messages);
    state.messages.push({ role: 'user', content: q });
    state.busy = true;
    els.input.value = '';
    render();
    save();
    try {
      var data = await request('/api/tutor/chat', { method: 'POST', body: JSON.stringify({ question: q, history: history }) });
      state.messages.push({ role: 'assistant', content: data.answer, sources: data.sources || [] });
    } catch (e) {
      state.messages.push({ role: 'error', content: e.message });
    } finally {
      state.busy = false;
      save();
      render();
      els.input.focus();
    }
  }

  function setOpen(open) {
    state.open = open;
    els.panel.hidden = !open;
    els.toggle.setAttribute('aria-expanded', String(open));
    if (open) {
      render();
      els.input.focus();
    }
  }

  function build() {
    var wrap = document.createElement('div');
    wrap.className = 'tutor-widget no-inline-dictionary';
    wrap.innerHTML =
      '<section class="tutor-panel" id="jlasTutorPanel" role="dialog" aria-label="Gia sư J-LAS" hidden>' +
      '<header class="tutor-panel__head"><div><strong>Gia sư J-LAS</strong><small>Trả lời từ giáo trình J-LAS</small></div>' +
      '<button type="button" class="tutor-icon-btn" data-tutor-reset title="Cuộc trò chuyện mới" aria-label="Cuộc trò chuyện mới">' +
      icon(ICONS.reset) + '</button>' +
      '<button type="button" class="tutor-icon-btn" data-tutor-close title="Đóng" aria-label="Đóng">' +
      icon(ICONS.close) + '</button></header>' +
      '<div class="tutor-log" aria-live="polite"></div>' +
      '<form class="tutor-form"><textarea rows="2" placeholder="Hỏi về từ, kanji, ngữ pháp…" aria-label="Câu hỏi cho gia sư"></textarea>' +
      '<div class="tutor-form__row"><small class="tutor-note">AI có thể sai, hãy đối chiếu với bài học.</small>' +
      '<small class="tutor-counter"></small><button type="submit" class="tutor-send" aria-label="Gửi">' +
      icon(ICONS.send) + '</button></div></form></section>' +
      '<button type="button" class="tutor-toggle" aria-controls="jlasTutorPanel" aria-expanded="false" title="Hỏi gia sư">' +
      icon(ICONS.cap) + '<span>Hỏi gia sư</span></button>';
    document.body.appendChild(wrap);
    els = {
      panel: wrap.querySelector('.tutor-panel'), toggle: wrap.querySelector('.tutor-toggle'),
      log: wrap.querySelector('.tutor-log'), form: wrap.querySelector('.tutor-form'),
      input: wrap.querySelector('textarea'), send: wrap.querySelector('.tutor-send'), counter: wrap.querySelector('.tutor-counter')
    };
    els.toggle.addEventListener('click', function () { setOpen(!state.open); });
    wrap.querySelector('[data-tutor-close]').addEventListener('click', function () { setOpen(false); });
    wrap.querySelector('[data-tutor-reset]').addEventListener('click', function () {
      state.messages = [];
      save();
      render();
    });
    els.form.addEventListener('submit', function (event) {
      event.preventDefault();
      ask(els.input.value);
    });
    els.input.addEventListener('keydown', function (event) {
      if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
        event.preventDefault();
        ask(els.input.value);
      }
    });
    els.input.addEventListener('input', function () { els.counter.textContent = els.input.value.length + '/' + state.maxChars; });
    els.log.addEventListener('click', function (event) {
      var example = event.target.closest('[data-tutor-example]');
      if (example) ask(example.getAttribute('data-tutor-example'));
    });
    document.addEventListener('keydown', function (event) {
      if (event.key === 'Escape' && state.open) setOpen(false);
    });
  }

  async function mount() {
    if (els.panel || isHiddenPath(window.location.pathname)) return;
    if (!window.JlasAuth || !window.JlasAuth.isLoggedIn()) return;
    try {
      var status = await request('/api/tutor/status');
      if (!status || !status.enabled) return;
      state.maxChars = status.maxQuestionChars || state.maxChars;
    } catch (e) {
      return;
    }
    load();
    build();
    render();
  }

  return { mount: mount, formatAnswer: formatAnswer, isHiddenPath: isHiddenPath, historyForRequest: historyForRequest };
});
