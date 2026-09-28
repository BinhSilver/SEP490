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

  function dayMin(items) {
    return items.reduce(function (min, item) {
      var day = positiveInteger(item.dayNumber);
      return day ? (min == null ? day : Math.min(min, day)) : min;
    }, null);
  }

  function dayMax(items) {
    return items.reduce(function (max, item) {
      var day = positiveInteger(item.dayNumber);
      return day ? (max == null ? day : Math.max(max, day)) : max;
    }, null);
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
    // [JLAS-W0113] Warm-up gate: once the warm-up phase is fully complete, the goal-learning
    // phase stays locked until the learner passes the summary quiz.
    var gateRequired = data.warmUpCompleted === true && data.gatePassed !== true;
    var activeIndex = -1;
    for (var i = 0; i < days.length; i++) {
      if (days[i].completed) continue;
      if (gateRequired && days[i].phase === 'GOAL_LEARNING') continue;
      activeIndex = i;
      break;
    }
    days.forEach(function (day, index) {
      if (day.completed) {
        day.status = 'COMPLETED';
      } else if (gateRequired && day.phase === 'GOAL_LEARNING') {
        day.status = 'LOCKED';
        day.gated = true;
      } else {
        day.status = index === activeIndex ? 'ACTIVE' : 'LOCKED';
      }
    });

    var totalLessons = number(data.totalLessons);
    var completedLessons = Math.min(number(data.completedLessons), totalLessons || number(data.completedLessons));
    var activeDay = activeIndex >= 0 ? days[activeIndex] : null;
    // [JLAS-W0108][MP06] "Tiếp tục học" trỏ vào activity đã mở dở (started=true) trước, thay vì
    // đoán theo thứ tự — chỉ fallback về đoán thứ tự khi chưa item nào được mở.
    var currentLesson = activeDay
      ? activeDay.items.find(function (item) { return item.started === true && item.completed !== true; })
        || activeDay.items.find(function (item) { return item.completed !== true; })
        || activeDay.items[0]
      : null;

    // [JLAS-W0108] Group goal-learning lessons by their primary knowledge point so the path reads
    // as extracted knowledge points rather than a raw curriculum dump. Day status is reused so each
    // lesson keeps its lock/active state inside the knowledge-point view.
    var dayStatusByNumber = new Map();
    days.forEach(function (day) { dayStatusByNumber.set(day.dayNumber, day.status); });
    var goalItems = rawPhases
      .filter(function (phase) { return phase.phase === 'GOAL_LEARNING'; })
      .reduce(function (acc, phase) { return acc.concat(phase.items || []); }, []);
    var pointsByCode = new Map();
    goalItems.forEach(function (item) {
      var code = item.knowledgePointCode ||
        (Array.isArray(item.matchedTagCodes) && item.matchedTagCodes.length ? item.matchedTagCodes[0] : null) ||
        'GENERAL';
      // [JLAS-W0108] Lát D — bỏ tiền tố "N5 bài N:" để tên điểm kiến thức đọc thành chủ đề.
      var name = (item.knowledgePointName || code).replace(/^(?:[A-Za-z0-9]+\s+)?bài\s*\d+\s*[:：]\s*/i, '');
      if (!pointsByCode.has(code)) pointsByCode.set(code, { code: code, name: name, items: [] });
      pointsByCode.get(code).items.push(item);
    });
    var knowledgePoints = Array.from(pointsByCode.values()).sort(function (a, b) {
      var diff = dayMin(a.items) - dayMin(b.items);
      return diff !== 0 ? diff : (a.code < b.code ? -1 : a.code > b.code ? 1 : 0);
    });
    knowledgePoints.forEach(function (point) {
      point.items.sort(function (a, b) { return number(a.orderIndex) - number(b.orderIndex); });
      point.totalMinutes = point.items.reduce(function (sum, item) { return sum + number(item.estimatedMinutes); }, 0);
      point.completedLessons = point.items.filter(function (item) { return item.completed === true; }).length;
      point.dayStart = dayMin(point.items);
      point.dayEnd = dayMax(point.items);
      point.progressPercent = percentage(point.completedLessons, point.items.length);
    });

    // [JLAS-W0108] Gemini narrative (tăng cường): overview + chiến lược từng giai đoạn. Null/absent
    // -> UI vẫn chạy bình thường không có narrative (Gemini lỗi/rate-limit không chặn trang).
    var aiPlan = data.aiPlan;
    var aiOverview = null;
    var aiPhases = [];
    if (aiPlan && typeof aiPlan === 'object') {
      aiOverview = aiPlan.overview ? String(aiPlan.overview).trim() : '';
      aiPhases = (Array.isArray(aiPlan.phases) ? aiPlan.phases : []).map(function (phase) {
        return {
          phase: phase && phase.phase ? phase.phase : null,
          strategy: phase && phase.strategy ? String(phase.strategy).trim() : '',
          focus: phase && phase.focus ? String(phase.focus).trim() : '',
          tips: phase && phase.tips ? String(phase.tips).trim() : ''
        };
      }).filter(function (phase) { return phase.strategy || phase.focus || phase.tips; });
    }
    var aiPlanModel = (aiOverview || aiPhases.length) ? { overview: aiOverview, phases: aiPhases } : null;

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
      knowledgePoints: knowledgePoints,
      dayStatusByNumber: dayStatusByNumber,
      gate: {
        required: gateRequired,
        passed: data.gatePassed === true,
        href: '/learning/path/gate-quiz'
      },
      coverageGaps: Array.isArray(data.coverageGaps) ? data.coverageGaps.slice() : [],
      aiPlan: aiPlanModel,
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

  // [JLAS-W0122] ?replan=ACCELERATE|REMEDIATE mở sẵn modal replan (gợi ý từ kết quả quiz).
  function resolveReplanMode(search) {
    var params = new URLSearchParams(search || '');
    var mode = params.get('replan');
    return (mode === 'REMEDIATE' || mode === 'ACCELERATE') ? mode : null;
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

  function renderGateBanner() {
    return '<section class="lp-gate" role="region" aria-label="Kiểm tra tổng kết khởi động">' +
      '<div class="lp-gate__copy">' + icon('workspace_premium') +
      '<div><h2>Kiểm tra tổng kết khởi động</h2>' +
      '<p>Bạn đã hoàn thành phần ôn tập. Vượt bài kiểm tra tổng kết để mở khóa giai đoạn theo mục tiêu.</p></div></div>' +
      '<a class="lp-button lp-button--primary" href="/learning/path/gate-quiz">' +
        'Làm bài kiểm tra ' + icon('arrow_forward') + '</a></section>';
  }

  function renderReplanModal() {
    return '<div class="lp-modal" data-replan-modal hidden role="dialog" aria-modal="true" aria-labelledby="lp-replan-title">' +
      '<div class="lp-modal__backdrop" data-action="replan-close"></div>' +
      '<div class="lp-modal__dialog">' +
        '<button type="button" class="lp-modal__close" data-action="replan-close" aria-label="Đóng">' + icon('close') + '</button>' +
        '<h2 id="lp-replan-title">' + icon('auto_fix_high') + '<span>Lên kế hoạch lại lộ trình</span></h2>' +
        '<p class="lp-modal__hint">Điều chỉnh phần lộ trình còn lại dựa trên tiến độ và Sổ tay điểm yếu. Bạn có thể xem trước thay đổi trước khi xác nhận.</p>' +
        '<div class="lp-replan__modes" role="radiogroup" aria-label="Chọn cách điều chỉnh">' +
          '<label class="lp-replan__mode">' +
            '<input type="radio" name="lp-replan-mode" value="REMEDIATE" data-replan-mode checked>' +
            '<span class="lp-replan__mode-copy"><strong>Ôn tập điểm yếu</strong><small>Bổ sung bài ôn tập từ Sổ tay điểm yếu còn mở.</small></span></label>' +
          '<label class="lp-replan__mode">' +
            '<input type="radio" name="lp-replan-mode" value="ACCELERATE" data-replan-mode>' +
            '<span class="lp-replan__mode-copy"><strong>Tăng tốc lộ trình</strong><small>Bỏ các mục ôn tập đã đạt mastery để rút ngắn lộ trình.</small></span></label>' +
        '</div>' +
        '<div class="lp-replan__preview" data-replan-preview aria-live="polite"><span class="lp-spinner"></span></div>' +
        '<div class="lp-modal__actions">' +
          '<button type="button" class="lp-button" data-action="replan-close">Hủy</button>' +
          '<button type="button" class="lp-button lp-button--primary" data-action="replan-confirm" data-replan-confirm disabled>Xác nhận và áp dụng</button>' +
        '</div>' +
      '</div></div>';
  }

  function renderReplanPreview(data) {
    data = data || {};
    var lines = [];
    if (data.explanation) lines.push('<p class="lp-replan__explain">' + escapeHtml(data.explanation) + '</p>');
    lines.push('<ul class="lp-replan__facts"><li>Bài học mới: <strong>' + number(data.newTotalLessons) + '</strong></li>' +
      '<li>Số ngày: <strong>' + number(data.newTotalDays) + '</strong></li>' +
      '<li>Tổng phút: <strong>' + number(data.newTotalMinutes) + '</strong></li></ul>');
    if (Array.isArray(data.addedSummaries) && data.addedSummaries.length) {
      lines.push('<div class="lp-replan__group lp-replan__group--add"><strong>Thêm vào</strong><ul>' +
        data.addedSummaries.map(function (summary) { return '<li>' + escapeHtml(summary) + '</li>'; }).join('') +
        '</ul></div>');
    }
    if (Array.isArray(data.removedSummaries) && data.removedSummaries.length) {
      lines.push('<div class="lp-replan__group lp-replan__group--remove"><strong>Bỏ đi</strong><ul>' +
        data.removedSummaries.map(function (summary) { return '<li>' + escapeHtml(summary) + '</li>'; }).join('') +
        '</ul></div>');
    }
    if (data.changesApply === false) {
      lines.push('<p class="lp-replan__nochange">Không có thay đổi nào để áp dụng lúc này.</p>');
    }
    return lines.join('');
  }

  function lessonHref(item) {
    if (item.vocabularyEntryId) return '/learning/warm-up/flashcard';
    if (item.grammarPointId) return '/learning/grammar/' + encodeURIComponent(item.grammarPointId);
    // [JLAS-W0108] P2 — kèm itemId để nút "Hoàn thành bài" ghi tiến độ theo activity, không theo source lesson.
    return '/learning/lesson/' + encodeURIComponent(item.lessonId) +
      (item.itemId ? '?item=' + encodeURIComponent(item.itemId) : '');
  }

  function actionForLesson(item, dayStatus) {
    if (dayStatus === 'LOCKED') return '<span class="lp-lesson__lock">' + icon('lock') + '<span>Khóa</span></span>';
    var href = lessonHref(item);
    var open = '<a class="lp-lesson__action" href="' + href + '">' +
      (item.completed ? 'Ôn lại' : 'Bắt đầu') + '</a>';
    if (item.completed) return open;
    // [JLAS-W0108] P4 — quiz riêng theo loại: ngữ pháp -> grammar quiz, từ/Kanji -> flashcard quiz,
    // đọc/nghe + target -> lesson quiz. Grammar item không có lessonId nên không dùng lesson quiz.
    var quizHref = item.grammarPointId
      ? '/learning/grammar/' + encodeURIComponent(item.grammarPointId) + '/quiz'
      : (item.vocabularyEntryId
        ? '/learning/path/flashcard-quiz'
        : '/learning/lesson/' + encodeURIComponent(item.lessonId) + '/quiz');
    return open + '<a class="lp-lesson__action lp-lesson__action--quiz" href="' + quizHref + '">' +
      icon('quiz') + '<span>Kiểm tra</span></a>';
  }

  function renderLesson(item, dayStatus) {
    var status = item.completed ? 'Đã hoàn thành' : dayStatus === 'ACTIVE' ? 'Chưa hoàn thành' : 'Chưa mở';
    // [JLAS-W0108] Nhãn theo loại item: ngữ pháp -> kanji/từ vựng -> đọc/nghe -> bài học.
    var isReading = item.reasonCode === 'WEAK_READING_ITEM';
    var isListening = item.reasonCode === 'WEAK_LISTENING_ITEM';
    var kindLabel = item.reasonCode === 'WARM_UP_FLASHCARDS' ? 'Bộ flashcard'
      : item.grammarPointId ? 'Ngữ pháp'
      : (item.vocabularyEntryId ? (item.reasonCode === 'WEAK_KANJI_ITEM' ? 'Kanji' : 'Từ vựng')
      : (isReading ? 'Đọc hiểu' : (isListening ? 'Nghe hiểu' : 'Bài học')));
    var subtitle = item.grammarPointId
      ? (item.grammarPointMeaning || item.reasonText || 'Mẫu ngữ pháp cần ôn lại trong Smart Test.')
      : (item.vocabularyEntryId
        ? (item.vocabularyMeaning || item.reasonText || 'Từ vựng cần ôn lại trong Smart Test.')
        : ((isReading || isListening)
          ? (item.reasonText || 'Luyện tập theo mức độ còn yếu trong Smart Test.')
          : (item.reasonText || 'Nội dung được chọn theo mục tiêu học tập của bạn.')));
    return '<li class="lp-lesson' + (item.completed ? ' is-complete' : '') + '">' +
      '<span class="lp-lesson__icon">' + icon(item.completed ? 'check_circle' : dayStatus === 'LOCKED' ? 'lock' : 'play_circle') + '</span>' +
      '<div class="lp-lesson__body"><div class="lp-lesson__meta"><span>' + kindLabel + '</span><span>' + number(item.estimatedMinutes) + ' phút</span></div>' +
      '<h4>' + escapeHtml(item.title || 'Bài học') + '</h4>' +
      '<p>' + escapeHtml(subtitle) + '</p>' +
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

  function renderKnowledgePoint(point, dayStatusByNumber) {
    var dayLabel = point.dayStart == null
      ? ''
      : point.dayStart === point.dayEnd
        ? 'Ngày ' + String(point.dayStart).padStart(2, '0')
        : 'Ngày ' + String(point.dayStart).padStart(2, '0') + ' – ' + String(point.dayEnd).padStart(2, '0');
    var itemsHtml = point.items.map(function (item) {
      var dayStatus = item.completed ? 'COMPLETED' : (dayStatusByNumber.get(positiveInteger(item.dayNumber)) || 'LOCKED');
      return renderLesson(item, dayStatus);
    }).join('');
    return '<article class="lp-point' + (point.progressPercent > 0 && point.progressPercent < 100 ? ' is-active' : '') + '">' +
      '<header class="lp-point__head"><span class="lp-point__marker">' + icon('lightbulb') + '</span>' +
      '<div class="lp-point__summary"><span class="lp-point__label">Điểm kiến thức</span>' +
      '<h3>' + escapeHtml(point.name) + '</h3><p>' + point.items.length + ' bài học · ' + point.totalMinutes + ' phút' +
      (dayLabel ? ' · ' + dayLabel : '') + '</p></div>' +
      '<div class="lp-point__progress"><strong>' + point.progressPercent + '%</strong></div></header>' +
      '<div class="lp-progress"><span style="width:' + point.progressPercent + '%"></span></div>' +
      '<ul class="lp-point__lessons">' + itemsHtml + '</ul></article>';
  }

  function renderKnowledgePoints(view) {
    var lessonTotal = view.knowledgePoints.reduce(function (sum, point) { return sum + point.items.length; }, 0);
    return '<section id="roadmap" class="lp-roadmap lp-roadmap--points"><div class="lp-section-heading"><div><span></span><h2>Điểm kiến thức cần chinh phục</h2></div>' +
      '<p>' + view.knowledgePoints.length + ' điểm kiến thức · ' + lessonTotal + ' bài học</p></div>' +
      (view.knowledgePoints.length
        ? view.knowledgePoints.map(function (point) { return renderKnowledgePoint(point, view.dayStatusByNumber); }).join('')
        : '<div class="lp-no-results">Chưa có điểm kiến thức nào trong giai đoạn này.</div>') + '</section>';
  }

  // [JLAS-W0108] Narrative Gemini (nếu có): overview + chiến lược/trọng tâm/mẹo từng giai đoạn.
  function renderAiPlan(aiPlan) {
    if (!aiPlan || !aiPlan.overview) return '';
    var phaseBlocks = (Array.isArray(aiPlan.phases) ? aiPlan.phases : []).map(function (phase) {
      var title = phase.phase === 'WARM_UP' ? 'Giai đoạn khởi động'
        : phase.phase === 'GOAL_LEARNING' ? 'Giai đoạn chinh phục mục tiêu'
        : (phase.phase || 'Giai đoạn');
      var lines = [];
      if (phase.strategy) lines.push('<p class="lp-ai-phase__line"><strong>Chiến lược</strong><span>' + escapeHtml(phase.strategy) + '</span></p>');
      if (phase.focus) lines.push('<p class="lp-ai-phase__line"><strong>Trọng tâm</strong><span>' + escapeHtml(phase.focus) + '</span></p>');
      if (phase.tips) lines.push('<p class="lp-ai-phase__line"><strong>Mẹo</strong><span>' + escapeHtml(phase.tips) + '</span></p>');
      return '<div class="lp-ai-phase"><h4>' + escapeHtml(title) + '</h4>' + lines.join('') + '</div>';
    }).join('');
    return '<section class="lp-ai-plan" aria-label="Kế hoạch học cho riêng bạn">' +
      '<div class="lp-ai-plan__head">' + icon('auto_awesome') + '<div><h2>Kế hoạch học cho riêng bạn</h2>' +
      '<p>' + escapeHtml(aiPlan.overview) + '</p></div></div>' +
      (phaseBlocks ? '<div class="lp-ai-plan__phases">' + phaseBlocks + '</div>' : '') +
      '</section>';
  }

  function renderPath(view, filters) {
    filters = filters || { phase: 'ALL', status: 'ALL' };
    var visibleDays = view.days.filter(function (day) {
      return (filters.phase === 'ALL' || day.phase === filters.phase) &&
        (filters.status === 'ALL' || day.status === filters.status);
    });
    var target = view.goal === 'JLPT'
      ? (view.currentLevel === 'BELOW_N5'
        ? 'JLPT ' + view.targetLevel + ' (từ bảng chữ cái)'
        : 'JLPT ' + view.currentLevel + ' → ' + view.targetLevel)
      : view.goal;
    var currentHref = view.currentLesson ? lessonHref(view.currentLesson) : '#roadmap';
    // [JLAS-W0108] The goal-learning phase is presented as extracted knowledge points (not a raw
    // curriculum list); the warm-up phase and the combined view keep the day-by-day schedule.
    var roadmapHtml = filters.phase === 'GOAL_LEARNING'
      ? renderKnowledgePoints(view)
      : '<section id="roadmap" class="lp-roadmap"><div class="lp-section-heading"><div><span></span><h2>Lịch học của bạn</h2></div><p>' + visibleDays.length + ' ngày hiển thị</p></div>' +
        (visibleDays.length ? visibleDays.map(renderDay).join('') : '<div class="lp-no-results">Không có ngày học phù hợp với bộ lọc này.</div>') + '</section>';
    return '<section class="lp-hero"><div class="lp-hero__copy"><span class="lp-eyebrow">' + icon('flag') + ' Lộ trình cá nhân hóa</span>' +
      '<h1>Ôn vững nền tảng, rồi tiến tới mục tiêu của bạn.</h1>' +
      '<div class="lp-hero__facts"><span>' + icon('auto_graph') + escapeHtml(target) + '</span><span>' + icon('calendar_month') + view.totalDays + ' ngày</span>' +
      '<span>' + icon('menu_book') + view.totalLessons + ' bài học</span><span>' + icon('schedule') + view.minutesPerDay + ' phút mỗi ngày</span></div></div>' +
      '<div class="lp-hero__progress"><div><span>Tiến độ lộ trình</span><strong>' + view.progressPercent + '%</strong></div>' +
      '<div class="lp-progress"><span style="width:' + view.progressPercent + '%"></span></div>' +
      '<p><span>' + view.completedLessons + '/' + view.totalLessons + ' bài đã hoàn thành</span><span>Còn ' + view.remainingLessons + ' bài</span></p>' +
      (view.currentLesson ? '<a class="lp-button lp-button--primary" href="' + currentHref + '">Tiếp tục học ' + icon('arrow_forward') + '</a>' : '') +
      '<button type="button" class="lp-button lp-button--ghost" data-action="open-replan">' + icon('auto_fix_high') + '<span>Lên kế hoạch lại</span></button>' + '</div></section>' +
      (view.gate && view.gate.required ? renderGateBanner() : '') +
      renderAiPlan(view.aiPlan) +
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
      roadmapHtml + '</div>' +
      '<aside class="lp-sidebar"><section class="lp-side-card"><div class="lp-side-card__head">' + icon('explore') + '<h2>Đích đến của bạn</h2></div><dl>' +
      '<div><dt>Mục tiêu cấp độ</dt><dd>' + escapeHtml(target) + '</dd></div><div><dt>Cường độ học</dt><dd>' + view.minutesPerDay + ' phút / ngày</dd></div>' +
      '<div><dt>Tổng thời lượng</dt><dd>' + view.totalMinutes + ' phút</dd></div></dl></section>' +
      '<section class="lp-side-card lp-side-card--tint"><div class="lp-side-card__head">' + icon('refresh') + '<h2>Vì sao bắt đầu bằng ôn tập?</h2></div>' +
      '<p>Các bài khởi động được chọn từ những điểm cần củng cố trong kết quả Smart Test.</p></section>' +
      '<section class="lp-side-card"><div class="lp-source">' + icon('menu_book') + '<div><span>Nguồn học theo mục tiêu</span><strong>' + view.textbookCount + ' giáo trình trong lộ trình</strong><p>Học liệu được hệ thống chọn theo mục tiêu và kết quả đánh giá.</p></div></div></section></aside></div>' + renderReplanModal();
  }

  function mount() {
    var root = document.getElementById('learningPathRoot');
    if (!root || !window.JlasAuth || !window.JlasAuth.requireLogin('/learning/path')) return;

    var state = { path: null, filters: { phase: 'ALL', status: 'ALL' }, attemptId: null, replan: null, autoReplanOpened: false };
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
        if (state.attemptId && body.data.assessmentAttemptId
            && Number(body.data.assessmentAttemptId) !== state.attemptId) {
          await createPath();
          return;
        }
        state.path = body.data;
        paint();
        maybeAutoOpenReplan();
      } catch (error) {
        root.innerHTML = renderError(null, 'Không thể kết nối tới máy chủ. Vui lòng kiểm tra mạng và thử lại.');
        root.setAttribute('aria-busy', 'false');
      }
    }

    async function createPath() {
      // [JLAS-W0108] Không có attempt -> lộ trình from-zero (người mới học bảng chữ cái).
      var fromZero = !state.attemptId;
      root.setAttribute('aria-busy', 'true');
      root.innerHTML = renderLoading();
      try {
        var response = await fetch(fromZero ? '/api/learning/paths/from-zero' : '/api/learning/paths', {
          method: 'POST',
          headers: window.JlasAuth.authHeaders(),
          body: fromZero ? '{}' : JSON.stringify({ assessmentAttemptId: state.attemptId })
        });
        var body = await readBody(response);
        if (!response.ok || body.success === false || !body.data) {
          root.innerHTML = renderError(body.code, body.message);
          root.setAttribute('aria-busy', 'false');
          return;
        }
        state.path = body.data;
        paint();
        maybeAutoOpenReplan();
      } catch (error) {
        root.innerHTML = renderError(null, 'Không thể kết nối tới máy chủ. Vui lòng thử lại.');
        root.setAttribute('aria-busy', 'false');
      }
    }

    // [JLAS-W0122] Replan proposal + confirm: xem trước (preview) không ghi gì; chỉ khi xác nhận
    // mới POST để tạo version path mới.
    function replanModalNode() {
      return root.querySelector('[data-replan-modal]');
    }

    function replanPreviewNode() {
      return root.querySelector('[data-replan-preview]');
    }

    function replanConfirmNode() {
      return root.querySelector('[data-replan-confirm]');
    }

    function currentReplanMode() {
      var checked = root.querySelector('[data-replan-mode]:checked');
      return checked ? checked.value : 'REMEDIATE';
    }

    async function openReplan(mode) {
      var modal = replanModalNode();
      if (modal) {
        modal.hidden = false;
        if (mode) {
          var radio = root.querySelector('[data-replan-mode][value="' + mode + '"]');
          if (radio) radio.checked = true;
        }
      }
      await loadReplanPreview(currentReplanMode());
    }

    function closeReplan() {
      var modal = replanModalNode();
      if (modal) modal.hidden = true;
    }

    function maybeAutoOpenReplan() {
      if (state.autoReplanOpened) return;
      var mode = resolveReplanMode(window.location.search);
      if (!mode) return;
      state.autoReplanOpened = true;
      openReplan(mode);
    }

    async function selectReplanMode(mode) {
      await loadReplanPreview(mode);
    }

    async function loadReplanPreview(mode) {
      var preview = replanPreviewNode();
      var confirm = replanConfirmNode();
      if (preview) preview.innerHTML = '<span class="lp-spinner"></span>';
      if (confirm) confirm.disabled = true;
      try {
        var response = await fetch('/api/learning/paths/replan/preview?mode=' + encodeURIComponent(mode), {
          headers: window.JlasAuth.authHeaders()
        });
        var body = await readBody(response);
        if (!response.ok || body.success === false) {
          if (preview) preview.innerHTML = '<p class="lp-replan__error">' +
            escapeHtml(body.message || 'Không thể xem trước thay đổi.') + '</p>';
          return;
        }
        state.replan = body.data;
        if (preview) preview.innerHTML = renderReplanPreview(body.data);
        if (confirm) confirm.disabled = !body.data.changesApply;
      } catch (error) {
        if (preview) preview.innerHTML = '<p class="lp-replan__error">Không thể kết nối máy chủ. Vui lòng thử lại.</p>';
      }
    }

    async function applyReplan() {
      var mode = currentReplanMode();
      var confirm = replanConfirmNode();
      if (confirm) confirm.disabled = true;
      try {
        var response = await fetch('/api/learning/paths/replan', {
          method: 'POST',
          headers: window.JlasAuth.authHeaders(),
          body: JSON.stringify({ mode: mode, confirmed: true })
        });
        var body = await readBody(response);
        if (!response.ok || body.success === false) {
          var preview = replanPreviewNode();
          if (preview) preview.innerHTML = '<p class="lp-replan__error">' +
            escapeHtml(body.message || 'Không thể cập nhật lộ trình.') + '</p>';
          if (confirm) confirm.disabled = false;
          return;
        }
        closeReplan();
        await load();
      } catch (error) {
        var previewNode = replanPreviewNode();
        if (previewNode) previewNode.innerHTML = '<p class="lp-replan__error">Không thể kết nối máy chủ. Vui lòng thử lại.</p>';
        if (confirm) confirm.disabled = false;
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
      if (action.dataset.action === 'open-replan') openReplan();
      if (action.dataset.action === 'replan-close') closeReplan();
      if (action.dataset.action === 'replan-confirm') applyReplan();
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
      if (event.target.matches('[data-replan-mode]')) {
        selectReplanMode(event.target.value);
        return;
      }
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
