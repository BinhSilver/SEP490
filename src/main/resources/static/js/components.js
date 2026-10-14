/**
 * Shared UI helpers: toast, modal, dropdown, sidebar
 */
(function () {
  'use strict';

  /* ---------- Toast ---------- */
  window.JlasUI = window.JlasUI || {};

  JlasUI.toast = function (message, type) {
    type = type || 'info';
    var host = document.getElementById('toast-host');
    if (!host) {
      host = document.createElement('div');
      host.id = 'toast-host';
      // Bắt buộc: toast.css chỉ định nghĩa .toast-host (class), không có #toast-host. Thiếu class này
      // thì host mất position:fixed và toast rơi xuống cuối <body>, ngoài tầm nhìn rồi tự xoá sau 4.5s —
      // trang nào không nhúng fragments/components/toast.html sẽ nuốt lỗi âm thầm.
      host.className = 'toast-host';
      host.setAttribute('aria-live', 'polite');
      host.setAttribute('aria-atomic', 'true');
      document.body.appendChild(host);
    }

    var normalizedMessage = String(message || '').trim();
    var duplicate = Array.from(host.querySelectorAll('.toast')).find(function (toast) {
      return toast.dataset.toastMessage === normalizedMessage;
    });
    if (duplicate) return duplicate;

    while (host.querySelectorAll('.toast').length >= 2) {
      host.querySelector('.toast').remove();
    }

    var el = document.createElement('div');
    el.className = 'toast toast--' + type;
    el.dataset.toastMessage = normalizedMessage;
    el.setAttribute('role', type === 'error' ? 'alert' : 'status');
    el.innerHTML =
      '<span class="material-symbols-outlined" style="font-size:18px">' +
      (type === 'error' ? 'error' : type === 'success' ? 'check_circle' : type === 'warning' ? 'warning' : 'info') +
      '</span><span>' + escapeHtml(message) + '</span>' +
      '<button type="button" class="toast__close" aria-label="Đóng">' +
      '<span class="material-symbols-outlined" style="font-size:16px">close</span></button>';

    host.appendChild(el);
    el.querySelector('.toast__close').addEventListener('click', function () {
      el.remove();
    });
    setTimeout(function () {
      if (el.parentNode) el.remove();
    }, 4500);
    return el;
  };

  /* ---------- Modal ---------- */
  JlasUI.openModal = function (id) {
    var el = document.getElementById(id);
    if (el) {
      el.classList.add('is-open');
      el.setAttribute('aria-hidden', 'false');
    }
  };

  JlasUI.closeModal = function (id) {
    var el = document.getElementById(id);
    if (el) {
      el.classList.remove('is-open');
      el.setAttribute('aria-hidden', 'true');
    }
  };

  document.addEventListener('click', function (e) {
    var closeBtn = e.target.closest('[data-modal-close]');
    if (closeBtn) {
      var backdrop = closeBtn.closest('.modal-backdrop');
      if (backdrop) {
        backdrop.classList.remove('is-open');
        backdrop.setAttribute('aria-hidden', 'true');
      }
    }
    if (e.target.classList.contains('modal-backdrop')) {
      e.target.classList.remove('is-open');
      e.target.setAttribute('aria-hidden', 'true');
    }
  });

  /* ---------- Dropdown ---------- */
  document.addEventListener('click', function (e) {
    var trigger = e.target.closest('[data-dropdown-toggle]');
    if (trigger) {
      e.preventDefault();
      var dd = trigger.closest('.dropdown');
      document.querySelectorAll('.dropdown.is-open').forEach(function (d) {
        if (d !== dd) {
          d.classList.remove('is-open');
          var otherTrigger = d.querySelector('[data-dropdown-toggle]');
          if (otherTrigger) otherTrigger.setAttribute('aria-expanded', 'false');
        }
      });
      if (dd) {
        dd.classList.toggle('is-open');
        trigger.setAttribute('aria-expanded', String(dd.classList.contains('is-open')));
      }
      return;
    }
    if (!e.target.closest('.dropdown')) {
      document.querySelectorAll('.dropdown.is-open').forEach(function (d) {
        d.classList.remove('is-open');
        var openTrigger = d.querySelector('[data-dropdown-toggle]');
        if (openTrigger) openTrigger.setAttribute('aria-expanded', 'false');
      });
    }
  });

  /* ---------- Sidebar ---------- */
  document.addEventListener('click', function (e) {
    var openBtn = e.target.closest('[data-sidebar-open]');
    var closeBtn = e.target.closest('[data-sidebar-close]');
    var sidebar = document.getElementById('app-sidebar');
    var backdrop = document.getElementById('sidebar-backdrop');
    if (openBtn && sidebar) {
      sidebar.classList.add('is-open');
      if (backdrop) backdrop.classList.add('is-open');
    }
    if (closeBtn || e.target.id === 'sidebar-backdrop') {
      if (sidebar) sidebar.classList.remove('is-open');
      if (backdrop) backdrop.classList.remove('is-open');
    }
  });

  /* ---------- Alert helper (toast-only form feedback) ---------- */
  JlasUI.showAlert = function (alertEl, message, type) {
    var toastType = type === 'ok' ? 'success' : (type || 'error');

    // Older forms reserve an alert area above the form. Keep it empty so all
    // request feedback uses the same popup treatment without moving the page.
    if (alertEl) {
      alertEl.hidden = true;
      alertEl.className = 'alert';
      alertEl.innerHTML = '';
    }

    return JlasUI.toast(message, toastType);
  };

  JlasUI.hideAlert = function (alertEl) {
    if (!alertEl) return;
    alertEl.hidden = true;
    alertEl.className = 'alert';
    alertEl.innerHTML = '';
  };

  /* ---------- Password toggle ---------- */
  document.addEventListener('click', function (e) {
    var btn = e.target.closest('[data-toggle-password]');
    if (!btn) return;
    var id = btn.getAttribute('data-toggle-password');
    var input = document.getElementById(id);
    if (!input) return;
    var icon = btn.querySelector('.material-symbols-outlined');
    var show = input.type === 'password';
    input.type = show ? 'text' : 'password';
    if (icon) icon.textContent = show ? 'visibility_off' : 'visibility';
  });

  /* ---------- Sakura AI Progress Component ---------- */
  var aiProgressState = {
    progress: 0,
    target: 0,
    total: 100,
    current: null,
    unit: 'mục',
    animationFrame: null,
    creepTimer: null,
    closeTimer: null
  };

  // Luôn chạy hoạt ảnh, không theo prefers-reduced-motion của hệ điều hành (quyết định sản phẩm).
  var prefersReducedMotion = false;

  function ensureAiProgressModal() {
    var modal = document.getElementById('sakura-ai-progress-modal');
    if (!modal) {
      if (!document.getElementById('sakura-ai-progress-css')) {
        var link = document.createElement('link');
        link.id = 'sakura-ai-progress-css';
        link.rel = 'stylesheet';
        link.href = '/css/components/ai-progress.css';
        document.head.appendChild(link);
      }

      modal = document.createElement('div');
      modal.className = 'sakura-progress-backdrop';
      modal.id = 'sakura-ai-progress-modal';
      modal.setAttribute('aria-hidden', 'true');
      modal.innerHTML =
        '<div class="sakura-scenery" aria-hidden="true">' +
          '<div class="sakura-sun"></div>' +
          '<div class="sakura-branch sakura-branch--left"></div>' +
          '<div class="sakura-branch sakura-branch--right"></div>' +
          '<div id="sakuraPetalBg"></div>' +
        '</div>' +
        '<main class="sakura-progress-card" role="dialog" aria-modal="true" aria-labelledby="sakuraAiTitle" aria-describedby="sakuraAiSubtitle sakuraAiStatus">' +
          '<button type="button" class="sakura-close-btn" id="sakuraAiCloseBtn" aria-label="Ẩn cửa sổ tiến độ">&times;</button>' +
          '<div class="header">' +
            '<div>' +
              '<div class="lesson-label" id="sakuraAiCategory">JLAS AI ASSISTANT</div>' +
              '<div class="title" id="sakuraAiTitle">Hành trình hôm nay 🌸</div>' +
            '</div>' +
            '<div class="progress-number">' +
              '<strong id="sakuraAiPercent">0</strong><span>%</span>' +
            '</div>' +
          '</div>' +
          '<div class="subtitle" id="sakuraAiSubtitle">Mỗi từ mới là một cánh hoa trên hành trình của bạn.</div>' +
          '<div class="progress-area">' +
            '<div class="track" id="sakuraAiTrack" role="progressbar" aria-label="Tiến độ xử lý AI" aria-valuemin="0" aria-valuemax="100" aria-valuenow="0">' +
              '<div class="fill" id="sakuraAiFill"></div>' +
              '<div class="progress-bloom" id="sakuraAiMovingPetal" aria-hidden="true">🌸</div>' +
            '</div>' +
            '<div class="stage-list" id="sakuraAiStages" aria-hidden="true">' +
              '<span class="stage is-active"><i></i>Chuẩn bị</span>' +
              '<span class="stage"><i></i>AI xử lý</span>' +
              '<span class="stage"><i></i>Hoàn tất</span>' +
            '</div>' +
          '</div>' +
          '<div class="bottom">' +
            '<div class="workload"><span class="workload-dot"></span><strong id="sakuraAiTotal">100</strong> <span id="sakuraAiUnit">mục</span></div>' +
            '<div class="status-pill"><span class="status-spinner" aria-hidden="true"></span><strong id="sakuraAiStatus" aria-live="polite">Đang khởi tạo AI...</strong></div>' +
          '</div>' +
          '<div class="complete-message" id="sakuraAiCompleteMessage" role="status"><span>✓</span> Hoàn thành! 🌸</div>' +
        '</main>';
      document.body.appendChild(modal);

      modal.querySelector('#sakuraAiCloseBtn').addEventListener('click', function () {
        JlasUI.hideAiProgress();
      });

      createSakuraBackgroundPetals(modal.querySelector('#sakuraPetalBg'));
    }
    return modal;
  }

  function createSakuraBackgroundPetals(container) {
    if (!container || container.children.length) return;
    for (var i = 0; i < 7; i++) {
      var petal = document.createElement('div');
      petal.className = 'sakura-falling-petal';
      petal.style.left = (Math.random() * 100) + 'vw';
      petal.style.setProperty('--petal-duration', (11 + Math.random() * 8) + 's');
      petal.style.setProperty('--petal-delay', (-Math.random() * 16) + 's');
      petal.style.setProperty('--petal-opacity', (0.18 + Math.random() * 0.2).toFixed(2));
      petal.style.setProperty('--petal-scale', (0.7 + Math.random() * 0.55).toFixed(2));
      petal.style.setProperty('--petal-drift', (35 + Math.random() * 80) + 'px');
      container.appendChild(petal);
    }
  }

  function clampProgress(value) {
    value = Number(value);
    return Number.isFinite(value) ? Math.max(0, Math.min(100, value)) : 0;
  }

  function paintSakuraProgress(newPct, statusText) {
    var fill = document.getElementById('sakuraAiFill');
    var movingPetal = document.getElementById('sakuraAiMovingPetal');
    var track = document.getElementById('sakuraAiTrack');
    var percent = document.getElementById('sakuraAiPercent');
    var totalEl = document.getElementById('sakuraAiTotal');
    var statusEl = document.getElementById('sakuraAiStatus');
    var stages = document.querySelectorAll('#sakuraAiStages .stage');

    aiProgressState.progress = clampProgress(newPct);
    var ratio = aiProgressState.progress / 100;

    if (fill) fill.style.transform = 'scaleX(' + ratio + ')';
    if (movingPetal) movingPetal.style.left = aiProgressState.progress + '%';
    if (track) track.setAttribute('aria-valuenow', Math.round(aiProgressState.progress));

    if (percent) percent.textContent = Math.round(aiProgressState.progress);
    if (totalEl) totalEl.textContent = aiProgressState.total;
    if (statusEl && statusText) statusEl.textContent = statusText;

    if (stages.length) {
      var activeStage = aiProgressState.progress >= 96 ? 2 : (aiProgressState.progress >= 28 ? 1 : 0);
      stages.forEach(function (stage, index) {
        stage.classList.toggle('is-active', index === activeStage);
        stage.classList.toggle('is-done', index < activeStage);
      });
    }
  }

  function animateSakuraProgress(newPct, statusText, onFinish) {
    var target = clampProgress(newPct);
    var start = aiProgressState.progress;
    var distance = Math.abs(target - start);

    aiProgressState.target = target;
    if (statusText) paintSakuraProgress(start, statusText);
    if (aiProgressState.animationFrame) cancelAnimationFrame(aiProgressState.animationFrame);

    if (prefersReducedMotion || distance < 0.1) {
      paintSakuraProgress(target, statusText);
      if (onFinish) onFinish();
      return;
    }

    var startedAt = performance.now();
    var duration = Math.min(1050, Math.max(320, distance * 13));

    function frame(now) {
      var elapsed = Math.min(1, (now - startedAt) / duration);
      var eased = 1 - Math.pow(1 - elapsed, 3);
      paintSakuraProgress(start + ((target - start) * eased));
      if (elapsed < 1) {
        aiProgressState.animationFrame = requestAnimationFrame(frame);
      } else {
        aiProgressState.animationFrame = null;
        if (onFinish) onFinish();
      }
    }

    aiProgressState.animationFrame = requestAnimationFrame(frame);
  }

  function stopAutomaticProgress() {
    if (aiProgressState.creepTimer) {
      clearInterval(aiProgressState.creepTimer);
      aiProgressState.creepTimer = null;
    }
  }

  function startAutomaticProgress() {
    stopAutomaticProgress();
    aiProgressState.creepTimer = setInterval(function () {
      var modal = document.getElementById('sakura-ai-progress-modal');
      if (!modal || !modal.classList.contains('is-visible') || aiProgressState.target >= 92) return;

      var remaining = 92 - aiProgressState.target;
      var step = Math.max(0.35, Math.min(3.2, remaining * 0.075));
      animateSakuraProgress(aiProgressState.target + step);
    }, 720);
  }

  JlasUI.showAiProgress = function (options) {
    options = options || {};
    var modal = ensureAiProgressModal();

    var titleEl = modal.querySelector('#sakuraAiTitle');
    var categoryEl = modal.querySelector('#sakuraAiCategory');
    var subtitleEl = modal.querySelector('#sakuraAiSubtitle');
    var unitEl = modal.querySelector('#sakuraAiUnit');
    var completeMsg = modal.querySelector('#sakuraAiCompleteMessage');

    if (titleEl) titleEl.textContent = options.title || 'Đang xử lý AI... 🌸';
    if (categoryEl) categoryEl.textContent = options.category || 'JLAS AI ASSISTANT';
    if (subtitleEl) subtitleEl.textContent = options.subtitle || 'Mỗi từ mới là một cánh hoa trên hành trình của bạn.';
    if (unitEl) unitEl.textContent = options.unit || 'mục';
    if (completeMsg) completeMsg.classList.remove('show');

    if (aiProgressState.closeTimer) clearTimeout(aiProgressState.closeTimer);
    stopAutomaticProgress();
    if (aiProgressState.animationFrame) cancelAnimationFrame(aiProgressState.animationFrame);

    aiProgressState.progress = 0;
    aiProgressState.target = 0;
    aiProgressState.total = options.total || 100;
    aiProgressState.current = options.current !== undefined ? options.current : null;
    aiProgressState.unit = options.unit || 'mục';

    var initialPct = options.percent !== undefined ? options.percent : Math.round((aiProgressState.current / aiProgressState.total) * 100);
    paintSakuraProgress(0, options.status || 'Đang khởi tạo AI...');
    modal.setAttribute('aria-hidden', 'false');
    modal.classList.add('is-visible');
    animateSakuraProgress(initialPct, options.status || 'Đang khởi tạo AI...');
    startAutomaticProgress();
  };

  JlasUI.updateAiProgress = function (arg, statusText) {
    var modal = document.getElementById('sakura-ai-progress-modal');
    if (!modal || !modal.classList.contains('is-visible')) return;

    var pct = 0;
    if (typeof arg === 'number') {
      pct = arg;
    } else if (typeof arg === 'object') {
      if (arg.total !== undefined) aiProgressState.total = arg.total;
      if (arg.current !== undefined) aiProgressState.current = arg.current;
      pct = arg.percent !== undefined
        ? arg.percent
        : (arg.current !== undefined
          ? Math.round((aiProgressState.current / (aiProgressState.total || 1)) * 100)
          : aiProgressState.target);
      if (arg.status) statusText = arg.status;
    }

    animateSakuraProgress(Math.max(aiProgressState.target, pct), statusText);
  };

  JlasUI.completeAiProgress = function (messageText, onComplete) {
    var modal = document.getElementById('sakura-ai-progress-modal');
    if (!modal) {
      if (onComplete) onComplete();
      return;
    }
    if (!modal.classList.contains('is-visible')) {
      if (onComplete) onComplete();
      return;
    }

    stopAutomaticProgress();
    animateSakuraProgress(100, 'Hoàn thành!', function () {
      var completeMsg = modal.querySelector('#sakuraAiCompleteMessage');
      if (completeMsg) {
        if (messageText) completeMsg.innerHTML = '<span>✓</span> ' + escapeHtml(messageText);
        completeMsg.classList.add('show');
      }

      aiProgressState.closeTimer = setTimeout(function () {
        if (completeMsg) completeMsg.classList.remove('show');
        JlasUI.hideAiProgress();
        if (onComplete) onComplete();
      }, 950);
    });
  };

  JlasUI.hideAiProgress = function () {
    var modal = document.getElementById('sakura-ai-progress-modal');
    stopAutomaticProgress();
    if (aiProgressState.animationFrame) {
      cancelAnimationFrame(aiProgressState.animationFrame);
      aiProgressState.animationFrame = null;
    }
    if (modal) {
      modal.classList.remove('is-visible');
      modal.setAttribute('aria-hidden', 'true');
    }
  };

  function escapeHtml(str) {
    return String(str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }
})();
