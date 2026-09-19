(function (root, factory) {
  'use strict';

  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.LearningPathPage = api;

  if (root && root.document) {
    if (root.document.readyState === 'loading') {
      root.document.addEventListener('DOMContentLoaded', api.mount, { once: true });
    } else {
      api.mount();
    }
  }
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';

  var PHASE_ORDER = { WARM_UP: 0, GOAL_LEARNING: 1 };
  var PHASE_COPY = {
    WARM_UP: {
      number: 1,
      title: 'Khởi động',
      description: 'Củng cố những kiến thức bạn còn chưa chắc.'
    },
    GOAL_LEARNING: {
      number: 2,
      title: 'Chinh phục mục tiêu',
      description: 'Tiếp nối nền tảng, học từng bước theo mục tiêu.'
    }
  };

  function escapeHtml(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#039;');
  }

  function number(value) {
    var result = Number(value);
    return Number.isFinite(result) && result >= 0 ? result : 0;
  }

  function positiveInteger(value) {
    var result = Number(value);
    return Number.isInteger(result) && result > 0 ? result : null;
  }

  function percentage(done, total) {
    if (!total) return 0;
    return Math.max(0, Math.min(100, Math.round((done / total) * 100)));
  }

  function createViewModel(data) {
    data = data || {};
    var rawPhases = Array.isArray(data.phases) ? data.phases.slice() : [];
    rawPhases.sort(function (a, b) {
      return (PHASE_ORDER[a.phase] == null ? 99 : PHASE_ORDER[a.phase]) -
        (PHASE_ORDER[b.phase] == null ? 99 : PHASE_ORDER[b.phase]);
    });

    var daysByNumber = new Map();
    var phases = rawPhases.map(function (phase) {
      var copy = PHASE_COPY[phase.phase] || {
        number: PHASE_ORDER[phase.phase] == null ? 0 : PHASE_ORDER[phase.phase] + 1,
        title: phase.phase || 'Giai đoạn',
        description: 'Nội dung trong lộ trình cá nhân của bạn.'
      };
      var items = (Array.isArray(phase.items) ? phase.items.slice() : []).sort(function (a, b) {
        return number(a.orderIndex) - number(b.orderIndex);
      });
      items.forEach(function (item) {
        var dayNumber = positiveInteger(item.dayNumber);
        if (!dayNumber) return;
        if (!daysByNumber.has(dayNumber)) {
          daysByNumber.set(dayNumber, {
            dayNumber: dayNumber,
            phase: phase.phase,
            phaseTitle: copy.title,
            items: []
          });
        }
        daysByNumber.get(dayNumber).items.push(item);
      });
      var completed = items.filter(function (item) { return item.completed === true; }).length;
      return {
        code: phase.phase,
        number: copy.number,
        title: copy.title,
        description: copy.description,
        lessonCount: number(phase.lessonCount),
        totalMinutes: number(phase.totalMinutes),
        dayCount: number(phase.dayCount),
        completedLessons: completed,
        progressPercent: percentage(completed, number(phase.lessonCount))
      };
    });

    var days = Array.from(daysByNumber.values()).sort(function (a, b) {
      return a.dayNumber - b.dayNumber;
    });
    days.forEach(function (day) {
      day.items.sort(function (a, b) { return number(a.orderIndex) - number(b.orderIndex); });
      day.totalMinutes = day.items.reduce(function (sum, item) { return sum + number(item.estimatedMinutes); }, 0);
      day.completedLessons = day.items.filter(function (item) { return item.completed === true; }).length;
      day.completed = day.items.length > 0 && day.completedLessons === day.items.length;
      day.progressPercent = percentage(day.completedLessons, day.items.length);
    });
    var activeIndex = days.findIndex(function (day) { return !day.completed; });
    days.forEach(function (day, index) {
      day.status = day.completed ? 'COMPLETED' : index === activeIndex ? 'ACTIVE' : 'LOCKED';
    });

    var totalLessons = number(data.totalLessons);
    var completedLessons = Math.min(number(data.completedLessons), totalLessons || number(data.completedLessons));
    var activeDay = activeIndex >= 0 ? days[activeIndex] : null;
    var currentLesson = activeDay
      ? activeDay.items.find(function (item) { return item.completed !== true; }) || activeDay.items[0]
      : null;

    return {
      id: data.id,
      goal: data.goal || 'Mục tiêu học tập',
      currentLevel: data.currentLevel || '—',
      targetLevel: data.targetLevel || '—',
      minutesPerDay: number(data.minutesPerDay),
      totalLessons: totalLessons,
      totalMinutes: number(data.totalMinutes),
      totalDays: number(data.totalDays),
      completedLessons: completedLessons,
      remainingLessons: Math.max(0, totalLessons - completedLessons),
      progressPercent: percentage(completedLessons, totalLessons),
      phases: phases,
      days: days,
      activeDay: activeDay,
      currentLesson: currentLesson,
      coverageGaps: Array.isArray(data.coverageGaps) ? data.coverageGaps.slice() : [],
      textbookCount: new Set(rawPhases.flatMap(function (phase) {
        return (phase.items || []).map(function (item) { return item.textbookId; }).filter(Boolean);
      })).size
    };
  }

  function resolveAssessmentAttemptId(search, storage) {
    var params = new URLSearchParams(search || '');
    var fromUrl = positiveInteger(Number(params.get('assessmentAttemptId')));
    if (fromUrl) return fromUrl;
    if (!storage || typeof storage.getItem !== 'function') return null;
    return positiveInteger(Number(storage.getItem('assessmentAttemptId')));
  }

  function errorMessage(code, fallback) {
    var messages = {
      FORBIDDEN: 'Bạn không có quyền truy cập lộ trình này.',
      NOT_FOUND: 'Bạn chưa có lộ trình học cá nhân.',
      CONFLICT: 'Hãy hoàn thành khảo sát và Smart Test trước khi tạo lộ trình.',
      SERVICE_UNAVAILABLE: 'Nguồn dữ liệu học tập đang tạm thời chưa sẵn sàng.',
      UNAUTHORIZED: 'Phiên đăng nhập đã hết hạn.'
    };
    return messages[code] || fallback || 'Không thể tải lộ trình. Vui lòng thử lại.';
  }

  function icon(name) {
    return '<span class="material-symbols-outlined" aria-hidden="true">' + name + '</span>';
  }

  function renderLoading() {
    return '<section class="lp-state lp-state--loading" role="status">' +
      '<span class="lp-spinner" aria-hidden="true"></span>' +
      '<h1>Đang tải lộ trình</h1><p>JLAS đang chuẩn bị hành trình học của bạn.</p></section>';
  }

  function renderError(code, message) {
    return '<section class="lp-state lp-state--error" role="alert">' +
      '<span class="lp-state__icon">!</span><p class="lp-eyebrow">Chưa tải được lộ trình</p>' +
      '<h1>Hãy thử lại sau một chút</h1><p>' + escapeHtml(errorMessage(code, message)) + '</p>' +
      '<button class="lp-button lp-button--primary" type="button" data-action="retry">' +
        icon('refresh') + '<span>Thử lại</span></button></section>';
  }

  function renderPhase(phase) {
    var state = phase.progressPercent >= 100 ? 'Hoàn thành' : phase.progressPercent > 0 ? 'Đang học' : 'Tiếp theo';
    return '<article class="lp-phase' + (phase.progressPercent > 0 && phase.progressPercent < 100 ? ' is-active' : '') + '">' +
      '<div class="lp-phase__head"><span class="lp-phase__number">' + phase.number + '</span>' +
      '<span class="lp-badge">' + state + '</span></div>' +
      '<h2>' + escapeHtml(phase.title) + '</h2><p>' + escapeHtml(phase.description) + '</p>' +
      '<small>' + phase.lessonCount + ' bài học · ' + phase.dayCount + ' ngày · ' + phase.totalMinutes + ' phút</small>' +
      '<div class="lp-progress" aria-label="Tiến độ ' + escapeHtml(phase.title) + '"><span style="width:' + phase.progressPercent + '%"></span></div>' +
      '</article>';
  }

  function actionForLesson(item, dayStatus) {
    if (dayStatus === 'LOCKED') return '<span class="lp-lesson__lock">' + icon('lock') + '<span>Khóa</span></span>';
    var label = item.completed ? 'Ôn lại' : 'Bắt đầu';
    return '<a class="lp-lesson__action" href="/learning/lesson/' + encodeURIComponent(item.lessonId) + '">' + label + '</a>';
  }

  function renderLesson(item, dayStatus) {
    var status = item.completed ? 'Đã hoàn thành' : dayStatus === 'ACTIVE' ? 'Chưa hoàn thành' : 'Chưa mở';
    return '<li class="lp-lesson' + (item.completed ? ' is-complete' : '') + '">' +
      '<span class="lp-lesson__icon">' + icon(item.completed ? 'check_circle' : dayStatus === 'LOCKED' ? 'lock' : 'play_circle') + '</span>' +
      '<div class="lp-lesson__body"><div class="lp-lesson__meta"><span>Bài học</span><span>' + number(item.estimatedMinutes) + ' phút</span></div>' +
      '<h4>' + escapeHtml(item.title || 'Bài học') + '</h4>' +
      '<p>' + escapeHtml(item.reasonText || 'Nội dung được chọn theo mục tiêu học tập của bạn.') + '</p>' +
      '<span class="lp-lesson__status">' + status + '</span></div>' + actionForLesson(item, dayStatus) + '</li>';
  }

  function renderDay(day) {
    var statusCopy = day.status === 'COMPLETED' ? 'Hoàn thành' : day.status === 'ACTIVE' ? 'Hôm nay' : 'Chưa bắt đầu';
    var title = day.items.length === 1 ? day.items[0].title : day.phaseTitle;
    var marker = day.status === 'COMPLETED' ? icon('check') : String(day.dayNumber).padStart(2, '0');
    var expanded = day.status === 'ACTIVE';
    return '<article class="lp-day lp-day--' + day.status.toLowerCase() + '" data-day="' + day.dayNumber + '">' +
      '<header class="lp-day__head"><span class="lp-day__marker">' + marker + '</span>' +
      '<div class="lp-day__summary"><div><span class="lp-day__label">Ngày ' + String(day.dayNumber).padStart(2, '0') + '</span>' +
      '<span class="lp-badge lp-badge--' + day.status.toLowerCase() + '">' + statusCopy + '</span></div>' +
      '<h3>' + escapeHtml(title) + '</h3><p>' + day.items.length + ' bài học · ' + day.totalMinutes + ' phút</p></div>' +
      (day.status === 'ACTIVE' ? '<div class="lp-day__progress"><strong>' + day.progressPercent + '%</strong><span>' + day.completedLessons + '/' + day.items.length + ' bài</span></div>' : '') +
      (day.status !== 'LOCKED' ? '<button type="button" class="lp-day__toggle" data-action="toggle-day" aria-expanded="' + expanded + '" aria-label="Mở nội dung ngày ' + day.dayNumber + '">' + icon(expanded ? 'expand_less' : 'expand_more') + '</button>' : icon('lock')) +
      '</header>' +
      '<div class="lp-day__details"' + (expanded ? '' : ' hidden') + '><p class="lp-day__details-title">Nội dung học chi tiết (' + day.items.length + ' bài học)</p>' +
      '<ul>' + day.items.map(function (item) { return renderLesson(item, day.status); }).join('') + '</ul></div></article>';
  }

  function renderCoverageGap(gap) {
    return '<li><span>' + escapeHtml(gap.tagCode || 'Học liệu') + '</span><strong>' +
      escapeHtml(gap.reasonCode === 'MISSING_WARM_UP_RESOURCE' ? 'Chưa đủ học liệu ôn tập' : gap.reasonCode || 'Cần củng cố') +
      '</strong></li>';
  }

  function renderPath(view, filters) {
    filters = filters || { phase: 'ALL', status: 'ALL' };
    var visibleDays = view.days.filter(function (day) {
      return (filters.phase === 'ALL' || day.phase === filters.phase) &&
        (filters.status === 'ALL' || day.status === filters.status);
    });
    var target = view.goal === 'JLPT' ? 'JLPT ' + view.currentLevel + ' → ' + view.targetLevel : view.goal;
    var currentHref = view.currentLesson ? '/learning/lesson/' + encodeURIComponent(view.currentLesson.lessonId) : '#roadmap';
    return '<section class="lp-hero"><div class="lp-hero__copy"><span class="lp-eyebrow">' + icon('flag') + ' Lộ trình cá nhân hóa</span>' +
      '<h1>Ôn vững nền tảng, rồi tiến tới mục tiêu của bạn.</h1>' +
      '<div class="lp-hero__facts"><span>' + icon('auto_graph') + escapeHtml(target) + '</span><span>' + icon('calendar_month') + view.totalDays + ' ngày</span>' +
      '<span>' + icon('menu_book') + view.totalLessons + ' bài học</span><span>' + icon('schedule') + view.minutesPerDay + ' phút mỗi ngày</span></div></div>' +
      '<div class="lp-hero__progress"><div><span>Tiến độ lộ trình</span><strong>' + view.progressPercent + '%</strong></div>' +
      '<div class="lp-progress"><span style="width:' + view.progressPercent + '%"></span></div>' +
      '<p><span>' + view.completedLessons + '/' + view.totalLessons + ' bài đã hoàn thành</span><span>Còn ' + view.remainingLessons + ' bài</span></p>' +
      (view.currentLesson ? '<a class="lp-button lp-button--primary" href="' + currentHref + '">Tiếp tục học ' + icon('arrow_forward') + '</a>' : '') + '</div></section>' +
      '<div class="lp-layout"><div class="lp-main"><section class="lp-phases" aria-label="Các giai đoạn">' + view.phases.map(renderPhase).join('') + '</section>' +
      '<div class="lp-toolbar"><div class="lp-tabs" aria-label="Lọc giai đoạn">' +
      '<button type="button" data-filter-phase="ALL" class="' + (filters.phase === 'ALL' ? 'is-active' : '') + '">Tất cả</button>' +
      '<button type="button" data-filter-phase="WARM_UP" class="' + (filters.phase === 'WARM_UP' ? 'is-active' : '') + '">Khởi động</button>' +
      '<button type="button" data-filter-phase="GOAL_LEARNING" class="' + (filters.phase === 'GOAL_LEARNING' ? 'is-active' : '') + '">Theo mục tiêu</button></div>' +
      '<label class="lp-status-filter"><span class="lp-sr-only">Trạng thái bài học</span><select data-filter-status>' +
      ['ALL', 'ACTIVE', 'COMPLETED', 'LOCKED'].map(function (value) {
        var copy = { ALL: 'Tất cả trạng thái', ACTIVE: 'Đang học', COMPLETED: 'Đã hoàn thành', LOCKED: 'Chưa bắt đầu' }[value];
        return '<option value="' + value + '"' + (filters.status === value ? ' selected' : '') + '>' + copy + '</option>';
      }).join('') + '</select></label></div>' +
      '<section id="roadmap" class="lp-roadmap"><div class="lp-section-heading"><div><span></span><h2>Lịch học của bạn</h2></div><p>' + visibleDays.length + ' ngày hiển thị</p></div>' +
      (visibleDays.length ? visibleDays.map(renderDay).join('') : '<div class="lp-no-results">Không có ngày học phù hợp với bộ lọc này.</div>') + '</section></div>' +
      '<aside class="lp-sidebar"><section class="lp-side-card"><div class="lp-side-card__head">' + icon('explore') + '<h2>Đích đến của bạn</h2></div><dl>' +
      '<div><dt>Mục tiêu cấp độ</dt><dd>' + escapeHtml(target) + '</dd></div><div><dt>Cường độ học</dt><dd>' + view.minutesPerDay + ' phút / ngày</dd></div>' +
      '<div><dt>Tổng thời lượng</dt><dd>' + view.totalMinutes + ' phút</dd></div></dl></section>' +
      '<section class="lp-side-card lp-side-card--tint"><div class="lp-side-card__head">' + icon('refresh') + '<h2>Vì sao bắt đầu bằng ôn tập?</h2></div>' +
      (view.coverageGaps.length ? '<p>Hệ thống ghi nhận các khoảng trống học liệu cần được xử lý:</p><ul class="lp-gap-list">' + view.coverageGaps.map(renderCoverageGap).join('') + '</ul>' : '<p>Các bài khởi động được chọn từ những điểm cần củng cố trong kết quả Smart Test.</p>') + '</section>' +
      '<section class="lp-side-card"><div class="lp-source">' + icon('menu_book') + '<div><span>Nguồn học theo mục tiêu</span><strong>' + view.textbookCount + ' giáo trình trong lộ trình</strong><p>Học liệu được hệ thống chọn theo mục tiêu và kết quả đánh giá.</p></div></div></section></aside></div>';
  }

  function mount() {
    var root = document.getElementById('learningPathRoot');
    if (!root || !window.JlasAuth || !window.JlasAuth.requireLogin('/learning/path')) return;

    var state = { path: null, filters: { phase: 'ALL', status: 'ALL' }, attemptId: null };
    state.attemptId = resolveAssessmentAttemptId(window.location.search, window.sessionStorage) ||
      resolveAssessmentAttemptId('', window.localStorage);

    function paint() {
      root.innerHTML = renderPath(createViewModel(state.path), state.filters);
      root.setAttribute('aria-busy', 'false');
    }

    async function readBody(response) {
      try { return await response.json(); } catch (error) { return {}; }
    }

    async function load() {
      root.setAttribute('aria-busy', 'true');
      root.innerHTML = renderLoading();
      try {
        var response = await fetch('/api/learning/paths/current', { headers: window.JlasAuth.authHeaders() });
        var body = await readBody(response);
        if (response.status === 404 || body.code === 'NOT_FOUND') {
          await createPath();
          return;
        }
        if (!response.ok || body.success === false || !body.data) {
          root.innerHTML = renderError(body.code, body.message);
          root.setAttribute('aria-busy', 'false');
          return;
        }
        state.path = body.data;
        paint();
      } catch (error) {
        root.innerHTML = renderError(null, 'Không thể kết nối tới máy chủ. Vui lòng kiểm tra mạng và thử lại.');
        root.setAttribute('aria-busy', 'false');
      }
    }

    async function createPath() {
      if (!state.attemptId) {
        root.innerHTML = renderError(null, 'Chưa tìm thấy kết quả Smart Test đã hoàn tất. Hãy quay lại sau khi hoàn thành bài đánh giá.');
        root.setAttribute('aria-busy', 'false');
        return;
      }
      root.setAttribute('aria-busy', 'true');
      root.innerHTML = renderLoading();
      try {
        var response = await fetch('/api/learning/paths', {
          method: 'POST',
          headers: window.JlasAuth.authHeaders(),
          body: JSON.stringify({ assessmentAttemptId: state.attemptId })
        });
        var body = await readBody(response);
        if (!response.ok || body.success === false || !body.data) {
          root.innerHTML = renderError(body.code, body.message);
          root.setAttribute('aria-busy', 'false');
          return;
        }
        state.path = body.data;
        paint();
      } catch (error) {
        root.innerHTML = renderError(null, 'Không thể kết nối tới máy chủ. Vui lòng thử lại.');
        root.setAttribute('aria-busy', 'false');
      }
    }

    root.addEventListener('click', function (event) {
      var action = event.target.closest('[data-action]');
      var phaseFilter = event.target.closest('[data-filter-phase]');
      if (phaseFilter) {
        state.filters.phase = phaseFilter.dataset.filterPhase;
        paint();
        return;
      }
      if (!action) return;
      if (action.dataset.action === 'retry') load();
      if (action.dataset.action === 'toggle-day') {
        var card = action.closest('.lp-day');
        var details = card && card.querySelector('.lp-day__details');
        if (!details) return;
        details.hidden = !details.hidden;
        action.setAttribute('aria-expanded', String(!details.hidden));
        var iconNode = action.querySelector('.material-symbols-outlined');
        if (iconNode) iconNode.textContent = details.hidden ? 'expand_more' : 'expand_less';
      }
    });

    root.addEventListener('change', function (event) {
      if (!event.target.matches('[data-filter-status]')) return;
      state.filters.status = event.target.value;
      paint();
    });

    load();
  }

  return {
    createViewModel: createViewModel,
    resolveAssessmentAttemptId: resolveAssessmentAttemptId,
    errorMessage: errorMessage,
    renderPath: renderPath,
    renderError: renderError,
    mount: mount
  };
});
