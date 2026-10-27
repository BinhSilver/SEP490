/**
 * [JLAS2-17] /textbooks/manage/new và /textbooks/manage/{textbookId}/lessons/new — tạo giáo trình / bài học theo 3 bước,
 * thay hai modal "Thêm giáo trình" / "Thêm bài học" của textbook-manage.js (modal Sửa giữ nguyên ở đó).
 * <p>
 * Chỉ gọi lại đúng các API modal cũ đang dùng: POST /textbooks, POST /textbooks/{id}/thumbnail, POST /units/{id}/lessons,
 * GET/POST /manage/tags, GET /manage/lessons/import-template, POST /lessons/{id}/import, và POST /textbooks/{id}/units
 * (modal "Thêm phần" — dùng khi giáo trình chưa có phần nào). Quyền thật chặn ở server (@PreAuthorize CONTENT_CREATOR).
 * <p>
 * "Component" = hàm JS thuần: renderStepper, renderTextbookPreview, renderKnowledgePointPicker, renderLessonAside…
 * Không thư viện; style trong css/pages/admin-create.css (scope .admin-create-page).
 */
// [JLAS2-17] Chạy trong cổng quản trị: AdminShell xác nhận tài khoản + quyền (CONTENT_CREATOR) rồi mới mở trang.
window.AdminShell.ready.then(function (allowed) {
  'use strict';
  if (!allowed) return;

  var API = '/api/dictionary';
  var main = document.getElementById('jlabBaseContent');
  var root = document.getElementById('acRoot');
  if (!main || !root) return;
  var MODE = main.getAttribute('data-create');
  var MANAGE_URL = '/textbooks/manage';

  // Danh sách lựa chọn: đúng giá trị CHECK / @Pattern của backend và nhãn của modal cũ (textbook-manage.js).
  var LEVELS = [{ value: '', label: 'Không theo cấp độ' }, { value: 'N5', label: 'N5' }, { value: 'N4', label: 'N4' },
    { value: 'N3', label: 'N3' }, { value: 'N2', label: 'N2' }, { value: 'N1', label: 'N1' }];
  var SKILLS = [
    { value: 'VOCABULARY', label: 'Từ vựng', icon: 'fa-book' },
    { value: 'GRAMMAR', label: 'Ngữ pháp', icon: 'fa-sitemap' },
    { value: 'READING', label: 'Đọc', icon: 'fa-book-open' },
    { value: 'LISTENING', label: 'Nghe', icon: 'fa-headphones' },
    { value: 'SPEAKING', label: 'Nói', icon: 'fa-microphone' },
    { value: 'ONYOMI', label: 'Onyomi', icon: 'fa-language' },
    { value: 'KUNYOMI', label: 'Kunyomi', icon: 'fa-pen-nib' }
  ];
  var LESSON_TYPES = [{ value: 'CURRICULUM', label: 'Giáo trình' }, { value: 'AMON', label: 'Âm On' }, { value: 'REVIEW', label: 'Ôn tập' }];
  // Mã gửi lên đúng CHECK chk_lesson_situation_tag (NULL | TRAVEL | BA | FOUNDATION); "Công việc" là BA (không phải WORK).
  var SITUATIONS = [{ value: '', label: '— không —' }, { value: 'TRAVEL', label: 'Du lịch' }, { value: 'BA', label: 'Công việc' },
    { value: 'FOUNDATION', label: 'Nền tảng' }];
  var MINUTES = [15, 30, 45, 60, 90];
  var KNOWLEDGE_TAG_TYPE = 'KNOWLEDGE';
  var TITLE_MAX = 255;

  // Thumbnail tự sinh: cùng bảng màu + hash theo tên với trang /textbooks (textbooks.js) để xem trước đúng như học viên thấy.
  var THUMB_PALETTE = [
    { bg: '#ece6fb', ink: '#5b3fa8' }, { bg: '#d9f2ed', ink: '#1d7568' }, { bg: '#fde4dc', ink: '#b04f36' },
    { bg: '#dfeafb', ink: '#2e5c9a' }, { bg: '#fbf0cf', ink: '#86660f' }, { bg: '#fbe1ec', ink: '#a5426d' }
  ];
  var COLOR_CHOICES = ['#ece6fb', '#dfeafb', '#d9f2ed', '#fbf0cf', '#fde4dc', '#fbe1ec', '#5b3bb5', '#3f2785', '#2e5c9a', '#b04f36', '#a5426d', '#1e1b2e'];
  var KANJI_CHOICES = ['日', '本', '語', '学', '書', '文', '字', '道'];
  var DEFAULT_KANJI = '日';
  var HEX = /^#[0-9a-f]{6}$/i;
  // Giới hạn ảnh bìa = giới hạn THẬT của API upload thumbnail hiện có (server từ chối > 2MB; không đổi backend).
  var THUMB_MAX_BYTES = 2 * 1024 * 1024;
  var THUMB_TYPES = ['image/jpeg', 'image/png', 'image/webp'];

  // ── Tiện ích chung ─────────────────────────────────────────────────────────

  function escapeHtml(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function toast(message, kind) {
    if (window.JlasUI && typeof JlasUI.toast === 'function') JlasUI.toast(message, kind || 'info');
  }

  /** Cùng cách gọi API như textbook-manage.js: lỗi ném Error mang đúng message của server. */
  var TECHNICAL_ERROR = /could not execute|constraint|sql|insert into|update \w+ set|exception|jdbc|hibernate/i;
  var TECHNICAL_FALLBACK = 'Dữ liệu không hợp lệ. Vui lòng kiểm tra lại các lựa chọn.';

  /** Chuỗi rỗng / chỉ khoảng trắng → null (giống modal cũ và blankToNull ở server). */
  function blankToNull(value) {
    var text = value == null ? '' : String(value).trim();
    return text ? text : null;
  }

  function request(path, options) {
    var opts = options || {};
    var headers = window.JlasAuth && typeof JlasAuth.authHeaders === 'function' ? JlasAuth.authHeaders() : {};
    if (typeof FormData !== 'undefined' && opts.body instanceof FormData) delete headers['Content-Type'];
    else if (opts.body) headers['Content-Type'] = 'application/json';
    return fetch(API + path, { method: opts.method || 'GET', headers: headers, body: opts.body })
      .then(function (res) {
        return res.json().catch(function () { return null; }).then(function (json) {
          if (res.status === 403) throw new Error('Bạn không có quyền biên soạn nội dung.');
          if (res.status === 401) throw new Error('Phiên đăng nhập đã hết, vui lòng đăng nhập lại.');
          if (!res.ok || !json || json.success === false) {
            var detail = json && json.errors && typeof json.errors === 'object'
              ? Object.keys(json.errors).map(function (k) { return json.errors[k]; }).join(' ') : '';
            var message = detail || (json && json.message) || '';
            // Lỗi kỹ thuật (HTTP 5xx, body lỗi mặc định của Spring, câu SQL/exception): không đưa ra giao diện,
            // chỉ ghi console. Lỗi nghiệp vụ / kiểm tra dữ liệu (ApiResponse tiếng Việt) giữ nguyên.
            if (res.status >= 500 || !json || json.success !== false || TECHNICAL_ERROR.test(message)) {
              console.error('[admin-create] ' + (opts.method || 'GET') + ' ' + API + path + ' → HTTP ' + res.status, json);
              var err = new Error(TECHNICAL_FALLBACK);
              err.technical = true;
              throw err;
            }
            throw new Error(message || ('Lỗi HTTP ' + res.status));
          }
          return json.data;
        });
      });
  }

  function isCreator() {
    var u = window.JlasAuth && typeof JlasAuth.getUser === 'function' ? JlasAuth.getUser() : null;
    return !!(u && u.role === 'CONTENT_CREATOR');
  }

  function labelOf(list, value) {
    for (var i = 0; i < list.length; i++) if (list[i].value === value) return list[i].label;
    return value || '';
  }

  function hashString(value) {
    var hash = 0;
    var text = String(value == null ? '' : value);
    for (var i = 0; i < text.length; i++) hash = (hash * 31 + text.charCodeAt(i)) >>> 0;
    return hash;
  }

  /**
   * Chữ Hán trên nền tự chọn: trắng hoặc chữ đậm, lấy màu có tỉ lệ tương phản WCAG cao hơn. Ở vùng nền trung
   * tính cả hai đều < 4.5:1 thì dùng đen (khi đó luôn ≥ 4.6:1). GIỮ GIỐNG HỆT inkFor trong textbooks.js.
   */
  function relativeLuminance(hex) {
    var n = parseInt(hex.slice(1), 16);
    var ch = [(n >> 16) & 255, (n >> 8) & 255, n & 255].map(function (c) {
      c /= 255;
      return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    });
    return 0.2126 * ch[0] + 0.7152 * ch[1] + 0.0722 * ch[2];
  }

  function inkFor(hex) {
    var lum = relativeLuminance(hex);
    var onWhite = 1.05 / (lum + 0.05);
    var onDark = (lum + 0.05) / (relativeLuminance('#1E1B2E') + 0.05);
    if (Math.max(onWhite, onDark) >= 4.5) return onWhite >= onDark ? '#FFFFFF' : '#1E1B2E';
    return '#000000';
  }

  function thumbColors(title, color) {
    if (color && HEX.test(color)) return { bg: color, ink: inkFor(color) };
    return THUMB_PALETTE[hashString(title) % THUMB_PALETTE.length];
  }

  function reducedMotion() {
    return !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
  }

  // ── Khung wizard dùng chung ────────────────────────────────────────────────

  /**
   * wiz: { steps: [{label, render(), bind(), validate() → [{field, message}]}], aside: {render, update},
   *        submitLabel, submittingLabel, submit() → Promise, cancelUrl }
   */
  var wiz = null;

  function startWizard(config) {
    wiz = config;
    wiz.current = 0;
    wiz.reached = 0;
    wiz.submitting = false;
    wiz.done = false;
    root.innerHTML =
      '<div class="ac-stepper-wrap">' +
        '<ol class="ac-stepper" id="acStepper"></ol>' +
        '<div class="ac-progress" aria-hidden="true"><span class="ac-progress__bar" id="acProgress"></span></div>' +
      '</div>' +
      '<div class="ac-layout">' +
        '<section class="ac-card" aria-labelledby="acStepTitle">' +
          '<div class="ac-alert ac-alert--error" id="acError" role="alert" hidden></div>' +
          '<div class="ac-step" id="acStep"></div>' +
          '<div class="ac-foot">' +
            '<a class="ac-btn ac-btn--ghost" href="' + escapeHtml(wiz.cancelUrl) + '" id="acCancel">Huỷ</a>' +
            '<span class="ac-foot__right">' +
              '<button type="button" class="ac-btn" id="acBack">Quay lại</button>' +
              '<button type="button" class="ac-btn ac-btn--primary" id="acNext">Tiếp tục</button>' +
            '</span>' +
          '</div>' +
        '</section>' +
        '<aside class="ac-aside" id="acAside">' + wiz.aside.render() + '</aside>' +
      '</div>';
    document.getElementById('acBack').addEventListener('click', function () { goTo(wiz.current - 1); });
    document.getElementById('acNext').addEventListener('click', onNext);
    document.getElementById('acStepper').addEventListener('click', function (event) {
      var btn = event.target.closest('[data-step]');
      if (btn && !btn.disabled) goTo(Number(btn.getAttribute('data-step')));
    });
    if (wiz.aside.bind) wiz.aside.bind();
    renderStep(0);
  }

  /** Stepper: vòng số; bước hiện tại nền --primary; bước đã qua có ✓ và bấm được; bước chưa tới bị tắt. */
  function renderStepper() {
    var el = document.getElementById('acStepper');
    el.innerHTML = wiz.steps.map(function (step, i) {
      var current = i === wiz.current;
      var done = !current && i <= wiz.reached;
      return '<li class="ac-stepper__item' + (current ? ' is-current' : '') + (done ? ' is-done' : '') + '">' +
        '<button type="button" class="ac-stepper__btn" data-step="' + i + '"' + (current || !done ? ' disabled' : '') +
        (current ? ' aria-current="step"' : '') +
        ' aria-label="Bước ' + (i + 1) + ': ' + escapeHtml(step.label) + (done ? ' (đã xong, bấm để quay lại)' : '') + '">' +
          '<span class="ac-stepper__num" aria-hidden="true">' + (done ? '<i class="fa-solid fa-check"></i>' : (i + 1)) + '</span>' +
          '<span class="ac-stepper__label">' + escapeHtml(step.label) + '</span>' +
        '</button></li>';
    }).join('');
    document.getElementById('acProgress').style.width = ((wiz.current + 1) / wiz.steps.length * 100) + '%';
  }

  function renderStep(index, direction) {
    wiz.current = index;
    if (index > wiz.reached) wiz.reached = index;
    renderStepper();
    var stepEl = document.getElementById('acStep');
    var step = wiz.steps[index];
    // Nội dung bước nằm trong một phần tử MỚI mỗi lần vẽ: listener của step.bind() đi theo nó, không cộng dồn.
    stepEl.innerHTML = '<h2 class="ac-step__title" id="acStepTitle" tabindex="-1">Bước ' + (index + 1) + ': ' +
      escapeHtml(step.label) + '</h2><div class="ac-step__body">' + step.render() + '</div>';
    stepEl.classList.remove('is-enter-right', 'is-enter-left');
    if (direction) {
      void stepEl.offsetWidth;   // khởi động lại animation
      stepEl.classList.add(direction > 0 ? 'is-enter-right' : 'is-enter-left');
    }
    if (step.bind) step.bind(stepEl.querySelector('.ac-step__body'));
    var last = index === wiz.steps.length - 1;
    document.getElementById('acBack').hidden = index === 0;
    var next = document.getElementById('acNext');
    next.textContent = last ? wiz.submitLabel : 'Tiếp tục';
    next.disabled = wiz.submitting;
    if (direction) document.getElementById('acStepTitle').focus({ preventScroll: true });
    wiz.aside.update();
  }

  /** Lỗi tại chỗ: viền cam đỏ + rung + chữ lỗi ngay dưới trường; focus vào trường lỗi đầu tiên. */
  function showFieldErrors(errors) {
    var stepEl = document.getElementById('acStep');
    clearFieldErrors();
    errors.forEach(function (err, i) {
      var target = document.querySelector('[data-field="' + err.field + '"]');
      if (!target) return;
      var wrap = target.closest('.ac-field') || target.parentNode;
      wrap.classList.add('is-invalid');
      target.setAttribute('aria-invalid', 'true');
      var msg = document.createElement('p');
      msg.className = 'ac-field__error';
      msg.id = 'acErr-' + err.field;
      msg.textContent = err.message;
      wrap.appendChild(msg);
      target.setAttribute('aria-describedby', msg.id);
      target.classList.remove('is-shake');
      void target.offsetWidth;
      target.classList.add('is-shake');
      if (i === 0) target.focus();
    });
    return stepEl;
  }

  function clearFieldErrors(field) {
    Array.prototype.forEach.call(document.querySelectorAll('.ac-field.is-invalid'), function (wrap) {
      if (field && !wrap.querySelector('[data-field="' + field + '"]')) return;
      wrap.classList.remove('is-invalid');
      var msg = wrap.querySelector('.ac-field__error');
      if (msg) msg.remove();
      var input = wrap.querySelector('[aria-invalid]');
      if (input) { input.removeAttribute('aria-invalid'); input.removeAttribute('aria-describedby'); }
    });
  }

  function validateStep(i) {
    var errors = wiz.steps[i].validate ? wiz.steps[i].validate() : [];
    return errors;
  }

  /** Lùi tự do; tiến thì phải qua kiểm tra của mọi bước nằm giữa. */
  function goTo(index) {
    if (index < 0 || index >= wiz.steps.length || index === wiz.current || wiz.submitting) return;
    if (index > wiz.current) {
      for (var i = wiz.current; i < index; i++) {
        var errors = validateStep(i);
        if (errors.length) {
          if (i !== wiz.current) renderStep(i, -1);
          showFieldErrors(errors);
          return;
        }
      }
    }
    hideServerError();
    renderStep(index, index > wiz.current ? 1 : -1);
  }

  function onNext() {
    if (wiz.submitting) return;
    var errors = validateStep(wiz.current);
    if (errors.length) { showFieldErrors(errors); return; }
    if (wiz.current < wiz.steps.length - 1) { goTo(wiz.current + 1); return; }
    // Bước cuối: kiểm lại mọi bước (người dùng có thể đã sửa ở bước trước qua nút "Sửa").
    for (var i = 0; i < wiz.steps.length; i++) {
      var e = validateStep(i);
      if (e.length) { renderStep(i, -1); showFieldErrors(e); return; }
    }
    submit();
  }

  function submit() {
    var next = document.getElementById('acNext');
    wiz.submitting = true;
    next.disabled = true;
    next.innerHTML = '<i class="fa-solid fa-circle-notch fa-spin" aria-hidden="true"></i> ' + escapeHtml(wiz.submittingLabel);
    document.getElementById('acBack').disabled = true;
    hideServerError();
    wiz.submit().then(function (success) {
      wiz.done = true;
      dirty = false;
      showSuccess(success);
    }).catch(function (err) {
      // Lỗi server: hiện trong trang, giữ nguyên dữ liệu đã nhập.
      wiz.submitting = false;
      next.disabled = false;
      next.textContent = wiz.submitLabel;
      document.getElementById('acBack').disabled = false;
      showServerError(err.technical ? wiz.failPrefix + ' ' + err.message
        : (err.message || 'Không tạo được, vui lòng thử lại.'));
    });
  }

  function showServerError(message) {
    var el = document.getElementById('acError');
    el.innerHTML = '<i class="fa-solid fa-triangle-exclamation" aria-hidden="true"></i> ' + escapeHtml(message);
    el.hidden = false;
    el.scrollIntoView({ block: 'nearest', behavior: reducedMotion() ? 'auto' : 'smooth' });
  }

  function hideServerError() {
    var el = document.getElementById('acError');
    if (el) el.hidden = true;
  }

  /** Màn hình thành công: vòng tròn xanh, dấu ✓ vẽ nét + vòng lan toả; success = { title, html, actions, note } */
  function showSuccess(success) {
    root.innerHTML =
      '<section class="ac-success" aria-live="polite">' +
        '<div class="ac-success__mark" aria-hidden="true">' +
          '<span class="ac-success__ripple"></span>' +
          '<svg viewBox="0 0 52 52" class="ac-success__svg"><circle cx="26" cy="26" r="24" class="ac-success__circle"></circle>' +
          '<path d="M15 27 l7 7 l15 -16" class="ac-success__check"></path></svg>' +
        '</div>' +
        '<h2 class="ac-success__title" tabindex="-1">' + escapeHtml(success.title) + '</h2>' +
        (success.html || '') +
        '<div class="ac-success__actions">' + success.actions + '</div>' +
        (success.link || '') +
      '</section>';
    root.querySelector('.ac-success__title').focus({ preventScroll: true });
    if (success.bind) success.bind(root);
  }

  // ── Thay đổi dữ liệu + cảnh báo rời trang ─────────────────────────────────

  var dirty = false;
  window.addEventListener('beforeunload', function (event) {
    if (!dirty || (wiz && wiz.done)) return;
    event.preventDefault();
    event.returnValue = '';
  });

  /** Ô nhập có data-field ghi vào S[field]; nhóm role="radiogroup" data-radio ghi giá trị của radio được chọn. */
  function bindInputs(container, S, onChange) {
    container.addEventListener('input', function (event) {
      var el = event.target.closest('[data-field]');
      if (!el || el.getAttribute('data-bind') === 'manual') return;
      S[el.getAttribute('data-field')] = el.value;
      dirty = true;
      clearFieldErrors(el.getAttribute('data-field'));
      updateCounters(container, S);
      if (onChange) onChange(el.getAttribute('data-field'));
    });
    container.addEventListener('click', function (event) {
      var radio = event.target.closest('[role="radio"]');
      if (!radio || radio.disabled) return;
      selectRadio(radio, S, onChange);
    });
    container.addEventListener('keydown', function (event) {
      var radio = event.target.closest('[role="radio"]');
      if (!radio) return;
      var keys = { ArrowRight: 1, ArrowDown: 1, ArrowLeft: -1, ArrowUp: -1 };
      if (event.key === ' ' || event.key === 'Enter') { event.preventDefault(); selectRadio(radio, S, onChange); return; }
      if (!keys[event.key]) return;
      event.preventDefault();
      var group = radio.closest('[role="radiogroup"]');
      var radios = Array.prototype.slice.call(group.querySelectorAll('[role="radio"]:not([disabled])'));
      var next = radios[(radios.indexOf(radio) + keys[event.key] + radios.length) % radios.length];
      selectRadio(next, S, onChange);
      next.focus();
    });
  }

  function selectRadio(radio, S, onChange) {
    var group = radio.closest('[role="radiogroup"]');
    var name = group.getAttribute('data-radio');
    Array.prototype.forEach.call(group.querySelectorAll('[role="radio"]'), function (r) {
      var on = r === radio;
      r.setAttribute('aria-checked', on ? 'true' : 'false');
      r.tabIndex = on ? 0 : -1;
      r.classList.toggle('is-checked', on);
    });
    S[name] = radio.getAttribute('data-value');
    dirty = true;
    clearFieldErrors(name);
    if (onChange) onChange(name);
  }

  /** Nhóm lựa chọn (radio) dạng chip / thẻ: roving tabindex, aria-checked. */
  function radioGroup(name, labelId, options, value, cls, renderItem) {
    var hasValue = options.some(function (o) { return o.value === value; });
    return '<div class="' + (cls || 'ac-chips') + '" role="radiogroup" aria-labelledby="' + labelId + '" data-radio="' + name + '" data-field="' + name + '" tabindex="-1">' +
      options.map(function (o, i) {
        var on = o.value === value;
        var focusable = on || (!hasValue && i === 0);
        return '<button type="button" role="radio" class="' + (cls ? cls + '__item' : 'ac-chip') + (on ? ' is-checked' : '') + '"' +
          ' aria-checked="' + (on ? 'true' : 'false') + '" tabindex="' + (focusable ? '0' : '-1') + '" data-value="' + escapeHtml(o.value) + '"' +
          (o.aria ? ' aria-label="' + escapeHtml(o.aria) + '"' : '') + (o.style ? ' style="' + o.style + '"' : '') + '>' +
          (renderItem ? renderItem(o) : escapeHtml(o.label)) + '</button>';
      }).join('') + '</div>';
  }

  function counterText(field, value) {
    var n = String(value || '').length;
    return field === 'description' ? n + ' ký tự' : n + '/' + TITLE_MAX;
  }

  function updateCounters(container, S) {
    Array.prototype.forEach.call(container.querySelectorAll('[data-counter]'), function (el) {
      var f = el.getAttribute('data-counter');
      el.textContent = counterText(f, S[f]);
    });
  }

  function textField(id, field, label, value, opts) {
    opts = opts || {};
    return '<div class="ac-field">' +
      '<label class="ac-label" for="' + id + '">' + escapeHtml(label) + (opts.required ? ' <span class="ac-req" aria-hidden="true">*</span>' : '') + '</label>' +
      (opts.textarea
        ? '<textarea class="ac-input ac-input--area" id="' + id + '" data-field="' + field + '" rows="3"' +
          (opts.placeholder ? ' placeholder="' + escapeHtml(opts.placeholder) + '"' : '') + '>' + escapeHtml(value) + '</textarea>'
        : '<input class="ac-input" id="' + id + '" data-field="' + field + '" value="' + escapeHtml(value) + '"' +
          (opts.maxlength ? ' maxlength="' + opts.maxlength + '"' : '') + (opts.required ? ' aria-required="true"' : '') +
          (opts.lang ? ' lang="' + opts.lang + '"' : '') + (opts.type ? ' type="' + opts.type + '"' : '') +
          (opts.placeholder ? ' placeholder="' + escapeHtml(opts.placeholder) + '"' : '') + '>') +
      (opts.counter ? '<div class="ac-field__meta"><span class="ac-counter" data-counter="' + field + '">' + counterText(field, value) + '</span></div>' : '') +
      (opts.hint ? '<p class="ac-hint">' + escapeHtml(opts.hint) + '</p>' : '') +
      '</div>';
  }

  /** Dòng "Xem lại" + nút "Sửa" về bước tương ứng; open = khối cần mở sẵn ở bước đó (ví dụ "advanced"). */
  function reviewRow(label, value, stepIndex, open) {
    return '<div class="ac-review__row"><dt>' + escapeHtml(label) + '</dt><dd>' + value + '</dd>' +
      '<button type="button" class="ac-link-btn" data-goto="' + stepIndex + '"' + (open ? ' data-open="' + open + '"' : '') +
      ' aria-label="Sửa ' + escapeHtml(label) + '">Sửa</button></div>';
  }

  function bindGoto(container) {
    container.addEventListener('click', function (event) {
      var btn = event.target.closest('[data-goto]');
      if (!btn) return;
      goTo(Number(btn.getAttribute('data-goto')));
      if (btn.getAttribute('data-open') === 'advanced') {
        var adv = document.querySelector('.ac-adv');
        if (adv) adv.open = true;
      }
    });
  }

  // ════════════════════════════════════════════════════════════════════════
  // Trang 1 — Tạo giáo trình
  // ════════════════════════════════════════════════════════════════════════

  function textbookFileError(file) {
    if (!file) return 'Vui lòng chọn một ảnh.';
    if (THUMB_TYPES.indexOf(String(file.type).toLowerCase()) < 0) return 'Ảnh không đúng định dạng. Chỉ nhận PNG, JPG hoặc WEBP.';
    if (file.size > THUMB_MAX_BYTES) return 'Ảnh vượt quá 2 MB (ảnh này ' + (file.size / 1048576).toFixed(1) + ' MB).';
    return null;
  }

  function startTextbook() {
    var S = { title: '', description: '', jlptLevel: '', coverMode: 'auto', kanji: DEFAULT_KANJI, color: '', file: null, fileUrl: null };

    function coverSummary() {
      if (S.coverMode === 'upload') return S.file ? 'Ảnh tải lên: ' + escapeHtml(S.file.name) : 'Chưa chọn ảnh';
      return 'Tự sinh · chữ <span lang="ja">' + escapeHtml(S.kanji.trim() || DEFAULT_KANJI) + '</span> · màu ' +
        (S.color && HEX.test(S.color) ? '<span class="ac-swatch-inline" style="background:' + escapeHtml(S.color) + '"></span> ' + escapeHtml(S.color.toUpperCase()) : 'tự động');
    }

    var steps = [
      {
        label: 'Thông tin',
        render: function () {
          return textField('acTitle', 'title', 'Tên giáo trình', S.title,
              { required: true, maxlength: TITLE_MAX, counter: true, placeholder: 'Ví dụ: Tiếng Nhật sơ cấp N5' }) +
            textField('acDesc', 'description', 'Mô tả (không bắt buộc)', S.description,
              { textarea: true, counter: true, placeholder: 'Một câu cho học viên: giáo trình giúp học được gì' }) +
            '<div class="ac-field"><span class="ac-label" id="acLevelLabel">Cấp độ JLPT</span>' +
              radioGroup('jlptLevel', 'acLevelLabel', LEVELS, S.jlptLevel) + '</div>';
        },
        validate: function () {
          return S.title.trim() ? [] : [{ field: 'title', message: 'Vui lòng nhập tên giáo trình.' }];
        }
      },
      {
        label: 'Ảnh bìa',
        render: function () {
          return '<div class="ac-field"><span class="ac-label" id="acCoverLabel">Cách tạo ảnh bìa</span>' +
              radioGroup('coverMode', 'acCoverLabel', [{ value: 'auto', label: 'Tự sinh' }, { value: 'upload', label: 'Tải ảnh lên' }],
                S.coverMode, 'ac-switch') + '</div>' +
            '<div data-cover-panel="auto"' + (S.coverMode === 'auto' ? '' : ' hidden') + '>' +
              '<div class="ac-field ac-field--kanji">' +
                '<label class="ac-label" for="acKanji">Chữ Hán trên ảnh bìa</label>' +
                '<div class="ac-kanji-row">' +
                  '<input class="ac-input ac-input--kanji" id="acKanji" data-field="kanji" maxlength="4" lang="ja" value="' + escapeHtml(S.kanji) + '">' +
                  '<span class="ac-kanji-picks" role="group" aria-label="Chọn nhanh chữ Hán">' +
                    KANJI_CHOICES.map(function (k) {
                      return '<button type="button" class="ac-kanji-pick" data-kanji="' + k + '" lang="ja" aria-label="Dùng chữ ' + k + '">' + k + '</button>';
                    }).join('') +
                  '</span>' +
                '</div>' +
                '<p class="ac-hint">Tối đa 4 ký tự; để trống sẽ dùng 日.</p>' +
              '</div>' +
              '<div class="ac-field"><span class="ac-label" id="acColorLabel">Màu nền</span>' +
                radioGroup('colorChoice', 'acColorLabel',
                  [{ value: '', label: 'Tự động', aria: 'Màu tự động theo tên giáo trình' }].concat(COLOR_CHOICES.map(function (c) {
                    return { value: c, label: '', aria: 'Màu ' + c.toUpperCase(), style: '--sw:' + c };
                  })), S.color && COLOR_CHOICES.indexOf(S.color.toLowerCase()) >= 0 ? S.color.toLowerCase() : (S.color ? '#custom' : ''),
                  'ac-swatches', function (o) { return o.value ? '<span class="ac-swatch" aria-hidden="true"></span>' : 'Tự động'; }) +
              '</div>' +
              textField('acColor', 'color', 'Hoặc nhập mã màu', S.color, { maxlength: 7, placeholder: '#RRGGBB — để trống để tự chọn theo tên' }) +
            '</div>' +
            '<div data-cover-panel="upload"' + (S.coverMode === 'upload' ? '' : ' hidden') + '>' +
              '<div class="ac-field">' +
                '<span class="ac-label" id="acFileLabel">Ảnh bìa</span>' +
                '<div class="ac-drop" data-field="file" tabindex="0" role="button" aria-labelledby="acFileLabel acDropText">' +
                  (S.fileUrl ? '<img class="ac-drop__preview" src="' + escapeHtml(S.fileUrl) + '" alt="Xem trước ảnh bìa">' : '') +
                  '<span class="ac-drop__text" id="acDropText"' + (S.fileUrl ? ' hidden' : '') + '><i class="fa-regular fa-image" aria-hidden="true"></i> Kéo thả ảnh vào đây hoặc bấm để chọn</span>' +
                '</div>' +
                '<input type="file" id="acFile" accept="image/png,image/jpeg,image/webp" hidden>' +
                '<p class="ac-hint">PNG, JPG hoặc WEBP, tối đa 2 MB. Kích thước gợi ý 1200×375 (tỉ lệ 16:5).</p>' +
                (S.file ? '<div class="ac-row-actions"><button type="button" class="ac-btn ac-btn--sm" data-file-action="change">Đổi ảnh</button>' +
                  '<button type="button" class="ac-btn ac-btn--sm ac-btn--ghost" data-file-action="remove">Bỏ ảnh</button></div>' : '') +
              '</div>' +
            '</div>';
        },
        bind: function (el) {
          el.addEventListener('click', function (event) {
            var pick = event.target.closest('[data-kanji]');
            if (pick) {
              S.kanji = pick.getAttribute('data-kanji');
              el.querySelector('#acKanji').value = S.kanji;
              dirty = true;
              wiz.aside.update();
              return;
            }
            var act = event.target.closest('[data-file-action]');
            if (act) {
              if (act.getAttribute('data-file-action') === 'change') el.querySelector('#acFile').click();
              else { setFile(null); }
              return;
            }
            if (event.target.closest('.ac-drop')) el.querySelector('#acFile').click();
          });
          var drop = el.querySelector('.ac-drop');
          drop.addEventListener('keydown', function (event) {
            if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); el.querySelector('#acFile').click(); }
          });
          drop.addEventListener('dragover', function (event) { event.preventDefault(); drop.classList.add('is-drag'); });
          drop.addEventListener('dragleave', function () { drop.classList.remove('is-drag'); });
          drop.addEventListener('drop', function (event) {
            event.preventDefault();
            drop.classList.remove('is-drag');
            if (event.dataTransfer && event.dataTransfer.files && event.dataTransfer.files[0]) setFile(event.dataTransfer.files[0]);
          });
          el.querySelector('#acFile').addEventListener('change', function () {
            var input = el.querySelector('#acFile');
            if (input.files && input.files[0]) setFile(input.files[0]);
            input.value = '';
          });
        },
        validate: function () {
          if (S.coverMode === 'upload') return S.file ? [] : [{ field: 'file', message: 'Chọn một ảnh, hoặc chuyển sang "Tự sinh".' }];
          return !S.color || HEX.test(S.color.trim()) ? [] : [{ field: 'color', message: 'Mã màu phải có dạng #RRGGBB, ví dụ #5B3BB5.' }];
        }
      },
      {
        label: 'Xem lại',
        render: function () {
          return '<dl class="ac-review">' +
              reviewRow('Tên giáo trình', escapeHtml(S.title.trim()), 0) +
              reviewRow('Mô tả', S.description.trim() ? escapeHtml(S.description.trim()) : '<span class="ac-muted">Không có</span>', 0) +
              reviewRow('Cấp độ', escapeHtml(labelOf(LEVELS, S.jlptLevel)), 0) +
              reviewRow('Ảnh bìa', coverSummary(), 1) +
            '</dl>' +
            '<p class="ac-alert ac-alert--info"><i class="fa-solid fa-circle-info" aria-hidden="true"></i> Giáo trình mới được lưu ở trạng thái Nháp.' +
            ' Học viên chưa thấy cho đến khi bạn bấm Công bố ở danh sách.</p>';
        },
        bind: bindGoto
      }
    ];

    function setFile(file) {
      if (file) {
        var error = textbookFileError(file);
        if (error) { showFieldErrors([{ field: 'file', message: error }]); return; }
      }
      if (S.fileUrl) URL.revokeObjectURL(S.fileUrl);
      S.file = file;
      S.fileUrl = file ? URL.createObjectURL(file) : null;
      dirty = true;
      renderStep(wiz.current);   // vẽ lại bước (xem trước + nút Đổi / Bỏ ảnh)
    }

    // Cột phải: thẻ giáo trình như học viên thấy (renderTextbookPreview), cập nhật khi gõ.
    var aside = {
      render: function () {
        return '<div class="ac-aside__card"><p class="ac-aside__title">Xem trước</p>' +
          '<div class="ac-tbcard">' +
            '<div class="ac-thumb" id="acThumb">' +
              '<span class="ac-thumb__kanji" lang="ja" aria-hidden="true" id="acThumbKanji"></span>' +
              '<img class="ac-thumb__img" id="acThumbImg" alt="" hidden>' +
              '<span class="ac-thumb__badge ac-thumb__badge--level" id="acThumbLevel"></span>' +
              '<span class="ac-thumb__badge ac-thumb__badge--status">Nháp</span>' +
            '</div>' +
            '<h3 class="ac-tbcard__title" id="acPrevTitle"></h3>' +
            '<p class="ac-tbcard__desc" id="acPrevDesc"></p>' +
            '<div class="ac-tbcard__foot"><span><i class="fa-solid fa-layer-group" aria-hidden="true"></i> 0 bài học</span></div>' +
          '</div>' +
          '<p class="ac-aside__note">Học viên chỉ thấy giáo trình sau khi bạn công bố.</p></div>';
      },
      update: renderTextbookPreview
    };

    function renderTextbookPreview() {
      var colors = thumbColors(S.title, S.coverMode === 'auto' ? S.color.trim() : '');
      var thumb = document.getElementById('acThumb');
      if (!thumb) return;
      thumb.style.setProperty('--thumb-bg', colors.bg);
      thumb.style.setProperty('--thumb-ink', colors.ink);
      document.getElementById('acThumbKanji').textContent = S.kanji.trim() || DEFAULT_KANJI;
      var img = document.getElementById('acThumbImg');
      var showImg = S.coverMode === 'upload' && S.fileUrl;
      if (showImg) img.src = S.fileUrl; else img.removeAttribute('src');
      img.hidden = !showImg;
      var level = document.getElementById('acThumbLevel');
      level.textContent = S.jlptLevel || 'Nhập môn';
      level.hidden = !!showImg;
      var title = S.title.trim();
      var titleEl = document.getElementById('acPrevTitle');
      titleEl.textContent = title || 'Tên giáo trình';
      titleEl.classList.toggle('is-placeholder', !title);
      var desc = S.description.trim();
      var descEl = document.getElementById('acPrevDesc');
      descEl.textContent = desc.length > 110 ? desc.slice(0, 107) + '…' : desc;
      descEl.hidden = !desc;
    }

    startWizard({
      steps: steps, aside: aside, cancelUrl: MANAGE_URL,
      submitLabel: 'Tạo giáo trình', submittingLabel: 'Đang tạo…', failPrefix: 'Không tạo được giáo trình:',
      submit: function () {
        var body = {
          title: S.title.trim(), description: blankToNull(S.description), jlptLevel: blankToNull(S.jlptLevel),
          thumbnailKanji: blankToNull(S.kanji), themeColor: S.coverMode === 'auto' ? blankToNull(S.color) : null
        };
        return request('/textbooks', { method: 'POST', body: JSON.stringify(body) }).then(function (textbook) {
          if (S.coverMode !== 'upload' || !S.file) return { textbook: textbook, uploadError: null };
          var form = new FormData();
          form.append('file', S.file);
          // API upload cần ID → tạo xong mới tải ảnh; ảnh lỗi thì giáo trình vẫn đã tạo.
          return request('/textbooks/' + encodeURIComponent(textbook.id) + '/thumbnail', { method: 'POST', body: form })
            .then(function () { return { textbook: textbook, uploadError: null }; })
            .catch(function (err) { return { textbook: textbook, uploadError: err.message || 'Lỗi tải ảnh.' }; });
        }).then(function (r) {
          return {
            title: 'Đã tạo giáo trình',
            html: '<p class="ac-success__text">“' + escapeHtml(r.textbook.title) + '” đang ở trạng thái Nháp.</p>' +
              (r.uploadError ? '<p class="ac-alert ac-alert--warn" role="alert"><i class="fa-solid fa-triangle-exclamation" aria-hidden="true"></i> ' +
                'Đã tạo giáo trình nhưng chưa tải được ảnh, bạn có thể thêm lại ở Sửa. (' + escapeHtml(r.uploadError) + ')</p>' : ''),
            actions: '<a class="ac-btn ac-btn--primary" href="/textbooks/manage/' + encodeURIComponent(r.textbook.id) + '/lessons/new">' +
                '<i class="fa-solid fa-plus" aria-hidden="true"></i> Thêm bài học đầu tiên</a>' +
              '<a class="ac-btn" href="' + MANAGE_URL + '">Về danh sách giáo trình</a>',
            link: '<a class="ac-link" href="/textbooks/manage/new">Tạo giáo trình khác</a>'
          };
        });
      }
    });
    bindInputs(document.getElementById('acStep'), S, function (field) {
      if (field === 'coverMode') {
        Array.prototype.forEach.call(document.querySelectorAll('[data-cover-panel]'), function (p) {
          p.hidden = p.getAttribute('data-cover-panel') !== S.coverMode;
        });
        clearFieldErrors('file');
        clearFieldErrors('color');
      }
      if (field === 'colorChoice') {
        S.color = S.colorChoice;
        var input = document.getElementById('acColor');
        if (input) input.value = S.color;
        clearFieldErrors('color');
      }
      if (field === 'color') {
        // Mã màu gõ tay: chọn ô màu trùng (nếu có), không thì bỏ chọn bảng màu.
        var group = document.querySelector('[data-radio="colorChoice"]');
        var v = S.color.trim().toLowerCase();
        if (group) {
          Array.prototype.forEach.call(group.querySelectorAll('[role="radio"]'), function (r) {
            var on = r.getAttribute('data-value') === (HEX.test(v) || !v ? v : '#none');
            r.setAttribute('aria-checked', on ? 'true' : 'false');
            r.classList.toggle('is-checked', on);
          });
        }
      }
      wiz.aside.update();
    });
  }

  // ════════════════════════════════════════════════════════════════════════
  // Trang 2 — Thêm bài học
  // ════════════════════════════════════════════════════════════════════════

  function slugCode(text) {
    return String(text || '').normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/đ/g, 'd').replace(/Đ/g, 'D')
      .replace(/[^A-Za-z0-9]+/g, '-').replace(/^-+|-+$/g, '').toUpperCase().slice(0, 60);
  }

  function startLesson() {
    var textbookId = main.getAttribute('data-textbook-id');
    var presetUnit = main.getAttribute('data-unit-id');
    root.innerHTML = '<p class="ac-loading"><i class="fa-solid fa-circle-notch fa-spin" aria-hidden="true"></i> Đang tải giáo trình…</p>';
    Promise.all([
      request('/manage/textbooks/' + encodeURIComponent(textbookId)),
      request('/manage/tags?type=' + KNOWLEDGE_TAG_TYPE)
    ]).then(function (res) {
      runLesson(res[0], res[1] || [], presetUnit);
    }).catch(function (err) {
      root.innerHTML = '<p class="ac-alert ac-alert--error" role="alert"><i class="fa-solid fa-triangle-exclamation" aria-hidden="true"></i> ' +
        escapeHtml(err.message || 'Không tải được giáo trình.') + ' <a class="ac-link" href="' + MANAGE_URL + '">Về danh sách</a></p>';
    });
  }

  function runLesson(tree, tags, presetUnit) {
    var textbook = tree.textbook || {};
    var units = (tree.units || []).slice();
    var archived = textbook.status === 'ARCHIVED';
    document.getElementById('acCrumbTextbook').textContent = textbook.title || 'Giáo trình';
    document.title = 'Thêm bài học · ' + (textbook.title || '') + ' - JLAS';

    var initialUnit = presetUnit && units.some(function (u) { return String(u.id) === String(presetUnit); })
      ? String(presetUnit) : (units.length === 1 ? String(units[0].id) : '');
    var S;

    function fresh(unitId) {
      return {
        unitId: unitId, title: '', description: '', minutesChoice: '', minutesOther: '', skill: 'VOCABULARY',
        tagId: '', tagQuery: '', newTagOpen: false, newTagCode: '', newTagName: '', lessonType: 'CURRICULUM',
        situationTag: '', contentMode: 'manual', excelFile: null, advancedOpen: false
      };
    }
    S = fresh(initialUnit);

    function unitTitle() {
      var u = units.filter(function (x) { return String(x.id) === String(S.unitId); })[0];
      return u ? u.title : '';
    }

    function minutesValue() {
      if (S.minutesChoice === 'other') return S.minutesOther ? Number(S.minutesOther) : null;
      return S.minutesChoice ? Number(S.minutesChoice) : null;
    }

    function tagById(id) {
      return tags.filter(function (t) { return String(t.id) === String(id); })[0] || null;
    }

    function updateCrumbs() {
      var name = unitTitle();
      document.getElementById('acCrumbUnit').textContent = name;
      document.getElementById('acCrumbUnit').hidden = !name;
      document.getElementById('acCrumbUnitSep').hidden = !name;
    }

    /** Điểm kiến thức: ô tìm (lọc ngay trên trình duyệt) + danh sách radio cuộn + tạo mới tại chỗ. */
    function renderKnowledgePointPicker() {
      return '<div class="ac-field">' +
          '<span class="ac-label" id="acTagLabel">Điểm kiến thức <span class="ac-req" aria-hidden="true">*</span></span>' +
          '<label class="ac-sr-only" for="acTagSearch">Tìm điểm kiến thức theo mã hoặc tên</label>' +
          '<div class="ac-search"><i class="fa-solid fa-magnifying-glass" aria-hidden="true"></i>' +
            '<input class="ac-input" id="acTagSearch" data-field="tagQuery" data-bind="manual" placeholder="Tìm theo mã hoặc tên…" value="' + escapeHtml(S.tagQuery) + '" autocomplete="off"></div>' +
          '<div class="ac-taglist" role="radiogroup" aria-labelledby="acTagLabel" data-radio="tagId" data-field="tagId" tabindex="-1" id="acTagList">' +
            tagListHtml() + '</div>' +
          '<p class="ac-hint" id="acTagCount"></p>' +
        '</div>' +
        '<button type="button" class="ac-link-btn" id="acNewTagToggle" aria-expanded="' + (S.newTagOpen ? 'true' : 'false') + '" aria-controls="acNewTag">' +
          (S.newTagOpen ? '− Đóng khung tạo mới' : '+ Tạo điểm kiến thức mới') + '</button>' +
        '<div class="ac-newtag" id="acNewTag"' + (S.newTagOpen ? '' : ' hidden') + '>' +
          textField('acNewTagCode', 'newTagCode', 'Mã', S.newTagCode, { maxlength: 100, placeholder: 'Ví dụ: N5-DIA-DIEM' }) +
          textField('acNewTagName', 'newTagName', 'Tên', S.newTagName, { maxlength: 255, placeholder: 'Ví dụ: N5 bài 3: Địa điểm và hỏi giá' }) +
          '<button type="button" class="ac-btn ac-btn--sm ac-btn--primary" id="acNewTagAdd">Thêm và chọn</button>' +
        '</div>';
    }

    function tagListHtml() {
      var q = S.tagQuery.trim().toLowerCase();
      var list = tags.filter(function (t) {
        return !q || String(t.code || '').toLowerCase().indexOf(q) >= 0 || String(t.name || '').toLowerCase().indexOf(q) >= 0;
      });
      // Danh sách lớn: chỉ vẽ 200 dòng đầu khớp tìm kiếm, luôn giữ dòng đang chọn.
      var shown = list.slice(0, 200);
      var sel = tagById(S.tagId);
      if (sel && shown.indexOf(sel) < 0) shown.unshift(sel);
      if (!shown.length) return '<p class="ac-muted ac-taglist__empty">Không có điểm kiến thức nào khớp.</p>';
      var anyChecked = shown.some(function (t) { return String(t.id) === String(S.tagId); });
      return shown.map(function (t, i) {
        var on = String(t.id) === String(S.tagId);
        return '<button type="button" role="radio" class="ac-tag' + (on ? ' is-checked' : '') + '" aria-checked="' + (on ? 'true' : 'false') + '"' +
          ' tabindex="' + (on || (!anyChecked && i === 0) ? '0' : '-1') + '" data-value="' + escapeHtml(t.id) + '">' +
          '<span class="ac-tag__dot" aria-hidden="true"></span><span class="ac-tag__code">' + escapeHtml(t.code) + '</span>' +
          '<span class="ac-tag__name">' + escapeHtml(t.name) + '</span></button>';
      }).join('') + (list.length > shown.length ? '<p class="ac-muted ac-taglist__empty">Còn ' + (list.length - 200) + ' mục — gõ để lọc.</p>' : '');
    }

    function refreshTagList() {
      var listEl = document.getElementById('acTagList');
      if (!listEl) return;
      listEl.innerHTML = tagListHtml();
      var countEl = document.getElementById('acTagCount');
      if (countEl) countEl.textContent = tags.length + ' điểm kiến thức' + (S.tagId ? ' · đã chọn: ' + (tagById(S.tagId) || {}).code : '');
    }

    function advancedSummary() {
      return labelOf(LESSON_TYPES, S.lessonType) + ' · ' + labelOf(SITUATIONS, S.situationTag);
    }

    var steps = [
      {
        label: 'Thông tin',
        render: function () {
          return textField('acLTitle', 'title', 'Tên bài', S.title,
              { required: true, maxlength: TITLE_MAX, counter: true, placeholder: 'Ví dụ: Bài 3: Địa điểm và hỏi giá' }) +
            textField('acLDesc', 'description', 'Mô tả (không bắt buộc)', S.description,
              { textarea: true, counter: true, placeholder: 'Người học sẽ học được gì trong bài này' }) +
            '<div class="ac-field"><span class="ac-label" id="acMinLabel">Thời lượng</span>' +
              radioGroup('minutesChoice', 'acMinLabel', [{ value: '', label: 'Không ghi' }].concat(MINUTES.map(function (m) {
                return { value: String(m), label: m + ' phút' };
              })).concat([{ value: 'other', label: 'Khác' }]), S.minutesChoice) +
              '<div class="ac-other" data-other' + (S.minutesChoice === 'other' ? '' : ' hidden') + '>' +
                '<label class="ac-label ac-label--sm" for="acMinOther">Số phút</label>' +
                '<input class="ac-input ac-input--num" id="acMinOther" type="number" min="1" step="1" inputmode="numeric" data-field="minutesOther" value="' + escapeHtml(S.minutesOther) + '">' +
              '</div>' +
            '</div>';
        },
        validate: function () {
          var errors = [];
          if (!S.unitId) errors.push({ field: 'unitId', message: units.length ? 'Chọn phần mà bài học thuộc về.' : 'Giáo trình chưa có phần nào — tạo phần đầu tiên ở cột bên phải.' });
          if (!S.title.trim()) errors.push({ field: 'title', message: 'Vui lòng nhập tên bài.' });
          if (S.minutesChoice === 'other' && !(Number(S.minutesOther) > 0 && Number.isInteger(Number(S.minutesOther)))) {
            errors.push({ field: 'minutesOther', message: 'Thời lượng phải là số phút nguyên lớn hơn 0.' });
          }
          return errors;
        }
      },
      {
        label: 'Phân loại',
        render: function () {
          return '<div class="ac-field"><span class="ac-label" id="acSkillLabel">Kỹ năng chính <span class="ac-req" aria-hidden="true">*</span></span>' +
              radioGroup('skill', 'acSkillLabel', SKILLS, S.skill, 'ac-skills', function (o) {
                return '<i class="fa-solid ' + o.icon + '" aria-hidden="true"></i><span>' + escapeHtml(o.label) + '</span>';
              }) + '</div>' +
            renderKnowledgePointPicker() +
            '<details class="ac-adv"' + (S.advancedOpen ? ' open' : '') + '>' +
              '<summary class="ac-adv__summary"><span>Tùy chọn nâng cao</span><span class="ac-adv__value" id="acAdvValue">' + escapeHtml(advancedSummary()) + '</span></summary>' +
              '<div class="ac-adv__body">' +
                '<div class="ac-field"><span class="ac-label" id="acTypeLabel">Loại bài</span>' + radioGroup('lessonType', 'acTypeLabel', LESSON_TYPES, S.lessonType) + '</div>' +
                '<div class="ac-field"><span class="ac-label" id="acSitLabel">Tình huống</span>' + radioGroup('situationTag', 'acSitLabel', SITUATIONS, S.situationTag) + '</div>' +
              '</div>' +
            '</details>';
        },
        bind: function (el) {
          refreshTagList();
          el.querySelector('#acTagSearch').addEventListener('input', function (event) {
            S.tagQuery = event.target.value;
            refreshTagList();
          });
          el.querySelector('.ac-adv').addEventListener('toggle', function (event) { S.advancedOpen = event.target.open; });
          el.querySelector('#acNewTagToggle').addEventListener('click', function () {
            S.newTagOpen = !S.newTagOpen;
            if (S.newTagOpen && !S.newTagName && S.title.trim()) {
              // Gợi ý từ tên bài: tên = tên bài; mã = cấp độ + chữ không dấu của tên bài.
              S.newTagName = S.title.trim();
              S.newTagCode = ((textbook.jlptLevel ? textbook.jlptLevel + '-' : '') + slugCode(S.title)).slice(0, 100);
            }
            renderStep(wiz.current);
            if (S.newTagOpen) document.getElementById('acNewTagCode').focus();
          });
          el.querySelector('#acNewTagAdd').addEventListener('click', function () {
            var errors = [];
            if (!S.newTagCode.trim()) errors.push({ field: 'newTagCode', message: 'Nhập mã điểm kiến thức.' });
            if (!S.newTagName.trim()) errors.push({ field: 'newTagName', message: 'Nhập tên điểm kiến thức.' });
            if (errors.length) { showFieldErrors(errors); return; }
            var btn = el.querySelector('#acNewTagAdd');
            btn.disabled = true;
            btn.textContent = 'Đang thêm…';
            request('/manage/tags', {
              method: 'POST',
              body: JSON.stringify({ code: S.newTagCode.trim(), name: S.newTagName.trim(), tagType: KNOWLEDGE_TAG_TYPE })
            }).then(function (tag) {
              tags.unshift(tag);
              S.tagId = String(tag.id);
              S.tagQuery = '';
              S.newTagOpen = false;
              S.newTagCode = '';
              S.newTagName = '';
              toast('Đã thêm và chọn điểm kiến thức ' + tag.code + '.', 'success');
              renderStep(wiz.current);
            }).catch(function (err) {
              btn.disabled = false;
              btn.textContent = 'Thêm và chọn';
              showFieldErrors([{ field: 'newTagCode', message: err.message || 'Không tạo được điểm kiến thức.' }]);
            });
          });
        },
        validate: function () {
          var errors = [];
          if (!S.skill) errors.push({ field: 'skill', message: 'Chọn kỹ năng chính của bài.' });
          if (!S.tagId) errors.push({ field: 'tagId', message: 'Chọn một điểm kiến thức, hoặc tạo mới.' });
          return errors;
        }
      },
      {
        label: 'Nội dung',
        render: function () {
          var minutes = minutesValue();
          var tag = tagById(S.tagId);
          return '<div class="ac-field"><span class="ac-label" id="acContentLabel">Cách thêm nội dung</span>' +
              radioGroup('contentMode', 'acContentLabel', [
                { value: 'excel', label: 'Nhập từ Excel', icon: 'fa-file-excel', note: 'Từ vựng, ngữ pháp, hội thoại, kanji từ file mẫu' },
                { value: 'manual', label: 'Soạn thủ công', icon: 'fa-pen-to-square', note: 'Mở trang soạn nội dung sau khi tạo' },
                { value: 'later', label: 'Để sau', icon: 'fa-clock', note: 'Chỉ tạo bài, thêm nội dung lúc khác' }
              ], S.contentMode, 'ac-modes', function (o) {
                return '<i class="fa-solid ' + o.icon + '" aria-hidden="true"></i><strong>' + escapeHtml(o.label) + '</strong><span>' + escapeHtml(o.note) + '</span>';
              }) + '</div>' +
            '<div class="ac-excel" data-excel' + (S.contentMode === 'excel' ? '' : ' hidden') + '>' +
              '<button type="button" class="ac-btn ac-btn--sm" id="acTemplate"><i class="fa-solid fa-download" aria-hidden="true"></i> Tải file mẫu</button>' +
              '<div class="ac-field">' +
                '<label class="ac-label" for="acExcel">File Excel (.xlsx)</label>' +
                '<input class="ac-input ac-input--file" type="file" id="acExcel" data-field="excelFile" data-bind="manual" accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet">' +
                (S.excelFile ? '<p class="ac-hint">Đã chọn: ' + escapeHtml(S.excelFile.name) + '</p>' : '') +
                '<p class="ac-hint">File có dòng lỗi thì không nhập gì; bài học vẫn được tạo và bạn sửa file rồi nhập lại ở trang soạn nội dung.</p>' +
              '</div>' +
            '</div>' +
            '<h3 class="ac-subtitle">Xem lại</h3>' +
            '<dl class="ac-review">' +
              reviewRow('Tên bài', escapeHtml(S.title.trim()), 0) +
              reviewRow('Kỹ năng · Điểm kiến thức', escapeHtml(labelOf(SKILLS, S.skill)) + ' · ' +
                (tag ? '<span class="ac-tag__code">' + escapeHtml(tag.code) + '</span> ' + escapeHtml(tag.name) : '—'), 1) +
              reviewRow('Thời lượng · Loại · Tình huống', escapeHtml((minutes ? minutes + ' phút' : 'Không ghi') + ' · ' +
                labelOf(LESSON_TYPES, S.lessonType) + ' · ' + labelOf(SITUATIONS, S.situationTag)), 1, 'advanced') +
            '</dl>';
        },
        bind: function (el) {
          bindGoto(el);
          el.querySelector('#acTemplate').addEventListener('click', downloadTemplate);
          el.querySelector('#acExcel').addEventListener('change', function (event) {
            var file = event.target.files && event.target.files[0];
            if (file && !/\.xlsx$/i.test(file.name)) {
              event.target.value = '';
              showFieldErrors([{ field: 'excelFile', message: 'Chỉ nhận file Excel .xlsx.' }]);
              return;
            }
            S.excelFile = file || null;
            dirty = true;
            clearFieldErrors('excelFile');
          });
        },
        validate: function () {
          return S.contentMode === 'excel' && !S.excelFile ? [{ field: 'excelFile', message: 'Chọn file .xlsx, hoặc chọn "Soạn thủ công" / "Để sau".' }] : [];
        }
      }
    ];

    /** Tải file mẫu qua fetch (endpoint cần header Authorization) — cùng cách trang soạn nội dung đang làm. */
    function downloadTemplate() {
      var headers = window.JlasAuth && typeof JlasAuth.authHeaders === 'function' ? JlasAuth.authHeaders() : {};
      fetch(API + '/manage/lessons/import-template', { headers: headers })
        .then(function (res) {
          if (!res.ok) throw new Error(res.status === 403 ? 'Bạn không có quyền biên soạn nội dung.' : 'Không tải được file mẫu.');
          return res.blob();
        })
        .then(function (blob) {
          var url = URL.createObjectURL(blob);
          var link = document.createElement('a');
          link.href = url;
          link.download = 'mau-noi-dung-bai-hoc.xlsx';
          document.body.appendChild(link);
          link.click();
          link.remove();
          setTimeout(function () { URL.revokeObjectURL(url); }, 1000);
        })
        .catch(function (err) { showServerError(err.message); });
    }

    // Cột phải: bài thuộc giáo trình / phần nào + "Cần có để tạo" + giá trị tự động (renderLessonAside).
    var aside = {
      render: function () {
        return '<div class="ac-aside__card">' +
            '<p class="ac-aside__title">Bài học sẽ thuộc</p>' +
            '<p class="ac-aside__book"><i class="fa-solid fa-book" aria-hidden="true"></i> ' + escapeHtml(textbook.title || '') + '</p>' +
            (archived ? '<p class="ac-alert ac-alert--warn">Giáo trình đang lưu trữ — khôi phục giáo trình trước khi thêm bài.</p>' : '') +
            '<div id="acUnitBox"></div>' +
          '</div>' +
          '<div class="ac-aside__card">' +
            '<p class="ac-aside__title">Cần có để tạo</p>' +
            '<ul class="ac-checklist" id="acChecklist"></ul>' +
            '<p class="ac-aside__note" id="acAuto"></p>' +
          '</div>';
      },
      bind: function () {
        renderUnitBox();
        document.getElementById('acAside').addEventListener('change', function (event) {
          if (event.target.id === 'acUnit') {
            S.unitId = event.target.value;
            dirty = true;
            clearFieldErrors('unitId');
            aside.update();
          }
        });
        document.getElementById('acAside').addEventListener('click', function (event) {
          if (event.target.closest('#acUnitCreate')) createUnit();
        });
      },
      update: renderLessonAside
    };

    function renderUnitBox() {
      var box = document.getElementById('acUnitBox');
      if (units.length) {
        box.innerHTML = '<div class="ac-field"><label class="ac-label" for="acUnit">Phần <span class="ac-req" aria-hidden="true">*</span></label>' +
          '<select class="ac-input" id="acUnit" data-field="unitId">' +
          '<option value="">— chọn phần —</option>' +
          units.map(function (u) {
            return '<option value="' + escapeHtml(u.id) + '"' + (String(u.id) === String(S.unitId) ? ' selected' : '') + '>' +
              escapeHtml(u.orderIndex != null ? u.orderIndex + '. ' : '') + escapeHtml(u.title) + '</option>';
          }).join('') + '</select></div>';
      } else {
        // Giáo trình vừa tạo chưa có phần: bài học bắt buộc thuộc một phần → tạo phần ngay tại đây (API "Thêm phần" có sẵn).
        box.innerHTML = '<div class="ac-field"><label class="ac-label" for="acUnitNew">Giáo trình chưa có phần nào — tạo phần đầu tiên</label>' +
          '<input class="ac-input" id="acUnitNew" data-field="unitId" data-bind="manual" maxlength="255" placeholder="Ví dụ: Phần 1">' +
          '<button type="button" class="ac-btn ac-btn--sm ac-btn--primary ac-mt" id="acUnitCreate">Tạo phần</button></div>';
      }
    }

    function createUnit() {
      var input = document.getElementById('acUnitNew');
      var title = input.value.trim();
      if (!title) { showFieldErrors([{ field: 'unitId', message: 'Nhập tên phần.' }]); return; }
      var btn = document.getElementById('acUnitCreate');
      btn.disabled = true;
      request('/textbooks/' + encodeURIComponent(textbook.id) + '/units', { method: 'POST', body: JSON.stringify({ title: title, description: '' }) })
        .then(function (unit) {
          units.push(unit);
          S.unitId = String(unit.id);
          toast('Đã tạo phần “' + unit.title + '”.', 'success');
          renderUnitBox();
          renderLessonAside();
        })
        .catch(function (err) {
          btn.disabled = false;
          showFieldErrors([{ field: 'unitId', message: err.message || 'Không tạo được phần.' }]);
        });
    }

    function renderLessonAside() {
      updateCrumbs();
      var items = [
        { ok: !!S.unitId, label: 'Phần' },
        { ok: !!S.title.trim(), label: 'Tên bài' },
        { ok: !!S.skill, label: 'Kỹ năng' },
        { ok: !!S.tagId, label: 'Điểm kiến thức' }
      ];
      var list = document.getElementById('acChecklist');
      if (list) {
        list.innerHTML = items.map(function (it) {
          return '<li class="ac-check' + (it.ok ? ' is-ok' : '') + '"><span class="ac-check__icon" aria-hidden="true">' +
            (it.ok ? '<i class="fa-solid fa-check"></i>' : '') + '</span>' + escapeHtml(it.label) +
            '<span class="ac-sr-only">' + (it.ok ? ' — đã có' : ' — chưa có') + '</span></li>';
        }).join('');
      }
      var auto = document.getElementById('acAuto');
      if (auto) auto.textContent = 'Tự động: loại bài ' + labelOf(LESSON_TYPES, S.lessonType) + ', trạng thái Nháp.';
      var adv = document.getElementById('acAdvValue');
      if (adv) adv.textContent = advancedSummary();
    }

    function lessonSubmit() {
      var body = {
        knowledgeTagId: Number(S.tagId), title: S.title.trim(), description: blankToNull(S.description),
        lessonType: S.lessonType, skill: S.skill, situationTag: blankToNull(S.situationTag), estimatedMinutes: minutesValue()
      };
      return request('/units/' + encodeURIComponent(S.unitId) + '/lessons', { method: 'POST', body: JSON.stringify(body) })
        .then(function (lesson) {
          if (S.contentMode !== 'excel' || !S.excelFile) return { lesson: lesson, imported: null };
          var form = new FormData();
          form.append('file', S.excelFile);
          // Bài đã tạo; nhập Excel lỗi (dòng lỗi hoặc lỗi mạng) thì bài vẫn còn, báo lỗi ở màn hình thành công.
          return request('/lessons/' + encodeURIComponent(lesson.id) + '/import', { method: 'POST', body: form })
            .then(function (result) { return { lesson: lesson, imported: result }; })
            .catch(function (err) { return { lesson: lesson, imported: { imported: false, errors: [],
              failure: err.technical ? 'lỗi hệ thống khi đọc file, vui lòng thử lại' : err.message } }; });
        })
        .then(function (r) {
          var editorUrl = '/textbooks/lessons/' + encodeURIComponent(r.lesson.id) + '/edit';
          var importHtml = '';
          if (r.imported) {
            if (r.imported.imported) {
              var parts = [['từ mới', r.imported.vocabularyCreated], ['từ có sẵn được gắn', r.imported.vocabularyAttached],
                ['mẫu ngữ pháp', r.imported.grammarCreated], ['câu ví dụ', r.imported.examplesCreated],
                ['đoạn hội thoại', r.imported.dialogueScenesCreated], ['lời thoại', r.imported.dialogueLinesCreated],
                ['kanji', r.imported.kanjiAttached]].filter(function (p) { return p[1] > 0; })
                .map(function (p) { return p[1] + ' ' + p[0]; });
              importHtml = '<p class="ac-alert ac-alert--ok"><i class="fa-solid fa-file-circle-check" aria-hidden="true"></i> Đã nhập từ Excel: ' +
                escapeHtml(parts.length ? parts.join(', ') : 'không có mục mới') + '.</p>';
            } else {
              var errs = r.imported.errors || [];
              importHtml = '<div class="ac-alert ac-alert--warn" role="alert"><strong>Bài học đã tạo, nhưng chưa nhập được file Excel' +
                (r.imported.failure ? ': ' + escapeHtml(r.imported.failure) : (' — file có ' + errs.length + ' lỗi, chưa nhập dữ liệu nào.')) + '</strong>' +
                (errs.length ? '<ul class="ac-errlist">' + errs.map(function (e) {
                  return '<li><b>' + escapeHtml(e.sheet) + ', dòng ' + escapeHtml(e.row) + ':</b> ' + escapeHtml(e.message) + '</li>';
                }).join('') + '</ul>' : '') +
                '<p>Sửa file rồi nhập lại ở trang soạn nội dung.</p></div>';
            }
          }
          return {
            title: 'Đã tạo bài học',
            html: '<p class="ac-success__text">“' + escapeHtml(r.lesson.title) + '” · ' + escapeHtml(unitTitle()) + ' · trạng thái Nháp.</p>' + importHtml,
            actions: '<a class="ac-btn ac-btn--primary" href="' + editorUrl + '"><i class="fa-solid fa-list-check" aria-hidden="true"></i> Mở trang soạn nội dung</a>' +
              '<button type="button" class="ac-btn" id="acAnother"><i class="fa-solid fa-plus" aria-hidden="true"></i> Thêm bài tiếp theo</button>',
            link: '<a class="ac-link" href="' + MANAGE_URL + '">Về danh sách bài học</a>',
            bind: function (el) {
              el.querySelector('#acAnother').addEventListener('click', function () {
                S = fresh(S.unitId);   // giữ giáo trình / phần
                startWizard(lessonWizard());
                bindInputs(document.getElementById('acStep'), proxyState(), onLessonChange);
              });
            }
          };
        });
    }

    function lessonWizard() {
      return {
        steps: steps, aside: aside, cancelUrl: MANAGE_URL,
        submitLabel: 'Tạo bài học', submittingLabel: 'Đang tạo…', failPrefix: 'Không tạo được bài học:', submit: lessonSubmit
      };
    }

    // bindInputs ghi vào một object cố định; S có thể được thay (Thêm bài tiếp theo) nên dùng proxy đọc / ghi S hiện tại.
    function proxyState() {
      var p = {};
      ['title', 'description', 'minutesChoice', 'minutesOther', 'skill', 'tagId', 'lessonType', 'situationTag', 'contentMode',
        'newTagCode', 'newTagName', 'unitId'].forEach(function (k) {
        Object.defineProperty(p, k, { get: function () { return S[k]; }, set: function (v) { S[k] = v; }, enumerable: true });
      });
      return p;
    }

    function onLessonChange(field) {
      if (field === 'minutesChoice') {
        var other = document.querySelector('[data-other]');
        if (other) other.hidden = S.minutesChoice !== 'other';
        if (S.minutesChoice === 'other') document.getElementById('acMinOther').focus();
        clearFieldErrors('minutesOther');
      }
      if (field === 'contentMode') {
        var ex = document.querySelector('[data-excel]');
        if (ex) ex.hidden = S.contentMode !== 'excel';
        clearFieldErrors('excelFile');
      }
      renderLessonAside();
    }

    startWizard(lessonWizard());
    bindInputs(document.getElementById('acStep'), proxyState(), onLessonChange);
  }

  // ── Khởi động ──────────────────────────────────────────────────────────────

  if (!isCreator()) {
    var denied = document.getElementById('acDenied');
    if (denied) denied.hidden = false;
    return;
  }
  if (MODE === 'textbook') startTextbook();
  else if (MODE === 'lesson') startLesson();
});
