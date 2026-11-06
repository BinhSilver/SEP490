/* cpath-survey.js — Khảo sát đầu vào 5 bước cho Lộ trình Giao tiếp (survey.html).
 * Dạng UMD: window.CpathSurvey, dùng chung window.JpathApi.get / .send.
 * Mỗi bước vừa trọn 1 màn hình, Kitsu đổi dáng theo từng bước.
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.CpathSurvey = api;
  if (root && root.document && typeof module === 'undefined') {
    if (root.document.readyState === 'loading') {
      root.document.addEventListener('DOMContentLoaded', function () { api.mount(); });
    } else {
      api.mount();
    }
  }
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var TOPIC_LABELS_FALLBACK = {
    'AIR': 'Sân bay & xuất nhập cảnh',
    'TRAIN': 'Tàu xe & di chuyển',
    'HOTEL': 'Lưu trú & khách sạn',
    'FOOD': 'Ăn uống & nhà hàng',
    'SHOP': 'Mua sắm & hoàn thuế',
    'TROUBLE': 'Hỏi đường & sự cố',
    'HOME': 'Nhà cửa & tiện ích',
    'MARKET': 'Đi chợ & bữa ăn',
    'PUBLIC': 'Hành chính & ngân hàng',
    'HEALTH': 'Sức khoẻ & bệnh viện',
    'SCHOOL': 'Trường học & học tập',
    'SOCIAL': 'Quan hệ xã hội',
    'TIME': 'Thời gian & thời tiết',
    'OFFICE-GREETING': 'Chào hỏi đồng nghiệp',
    'OFFICE-REPORT': 'Báo cáo công việc',
    'OFFICE-LEAVE': 'Xin phép & nghỉ phép',
    'OFFICE-PHONE': 'Điện thoại công sở',
    'OFFICE-MEETING': 'Họp & thảo luận',
    'REQ': 'Yêu cầu & thiết kế',
    'DEV': 'Phát triển & lập trình',
    'TEST': 'Kiểm thử phần mềm',
    'BUG': 'Lỗi & chất lượng',
    'OPS': 'Môi trường & vận hành',
    'PM': 'Quản lý dự án',
    'COM': 'Giao tiếp dự án',
    'FACTORY-SAFETY': 'An toàn lao động',
    'FACTORY-SHIFT': 'Bàn giao ca',
    'FACTORY-INSTRUCTION': 'Nhận chỉ thị sản xuất',
    'FACTORY-ERROR': 'Báo cáo sự cố máy',
    'FACTORY-5S': 'Quy tắc 5S',
    'SERVICE-RECEPTION': 'Đón tiếp khách hàng',
    'SERVICE-PRODUCT': 'Tư vấn sản phẩm',
    'SERVICE-PAYMENT': 'Thanh toán tại quầy',
    'SERVICE-COMPLAINT': 'Xử lý phản hồi khách',
    'SERVICE-BOOKING': 'Đặt bàn & đặt phòng'
  };

  var STEP_META = [
    {
      step: 1,
      kitsu: 'kitsu-cheer-30.webp',
      title: 'Bạn muốn học giao tiếp tiếng Nhật cho mục tiêu nào?',
      sub: 'Mục tiêu chọn ở đây sẽ là lộ trình chính. Các mục tiêu khác có thể học tự do thêm trong Cài đặt.'
    },
    {
      step: 2,
      kitsu: 'kitsu-thinking-30.webp',
      title: 'Chọn ngành công việc của bạn (1–2 ngành)',
      sub: 'Ngành Văn phòng chung luôn có sẵn để đảm bảo nền tảng giao tiếp nơi làm việc.'
    },
    {
      step: 3,
      kitsu: 'kitsu-thinking-30.webp',
      title: 'Bạn muốn ưu tiên những chủ đề nào trước?',
      sub: 'Chọn một hoặc nhiều chủ đề bạn cần dùng sớm nhất — hệ thống sẽ xếp lên đầu lộ trình.'
    },
    {
      step: 4,
      kitsu: 'kitsu-cheer-30.webp',
      title: 'Tự đánh giá mức độ giao tiếp hiện tại của bạn',
      sub: 'Không cần làm bài kiểm tra đầu vào — lộ trình sẽ điều chỉnh hỗ trợ phiên âm và tốc độ nghe phù hợp.'
    },
    {
      step: 5,
      kitsu: 'kitsu-celebrate-30.webp',
      title: 'Chọn nhịp học mỗi ngày và lịch nghỉ trong tuần',
      sub: 'Mỗi chủ đề gồm 3 tình huống thực tế với 4 bước: Từ & mẫu câu · Nghe · Nhập vai · Nói.'
    }
  ];

  var SELF_LEVELS = [
    {
      code: 'BEGINNER',
      labelVi: 'Mới bắt đầu',
      descVi: 'Chưa từng giao tiếp tiếng Nhật hoặc mới biết vài câu chào hỏi cơ bản.'
    },
    {
      code: 'ELEMENTARY',
      labelVi: 'Sơ cấp',
      descVi: 'Nghe hiểu và nói được các câu ngắn, đơn giản trong sinh hoạt quen thuộc.'
    },
    {
      code: 'INTERMEDIATE',
      labelVi: 'Trung cấp',
      descVi: 'Đã giao tiếp được câu cơ bản, muốn luyện phản xạ tự nhiên và lịch sự hơn.'
    }
  ];

  var DAYS_OF_WEEK = [
    { code: 'MON', label: 'T2', full: 'Thứ hai' },
    { code: 'TUE', label: 'T3', full: 'Thứ ba' },
    { code: 'WED', label: 'T4', full: 'Thứ tư' },
    { code: 'THU', label: 'T5', full: 'Thứ năm' },
    { code: 'FRI', label: 'T6', full: 'Thứ sáu' },
    { code: 'SAT', label: 'T7', full: 'Thứ bảy' },
    { code: 'SUN', label: 'CN', full: 'Chủ nhật' }
  ];

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function kitsuImgSrc(file) {
    if (root && root.CpathMock && typeof root.CpathMock.kitsuSrc === 'function') {
      return root.CpathMock.kitsuSrc(file);
    }
    return '/images/mascots/kitsu/' + file;
  }

  function svgCheck() {
    return '<svg viewBox="0 0 16 16" width="14" height="14" fill="none" aria-hidden="true">'
      + '<path d="M3.5 8.2l3 3 6-6.2" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>'
      + '</svg>';
  }

  function svgGoalIcon(goal) {
    if (goal === 'TRAVEL') {
      return '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" aria-hidden="true">'
        + '<path d="M21 16v-2l-8-5V3.5a1.5 1.5 0 0 0-3 0V9l-8 5v2l8-2.5V19l-2 1.5V22l3.5-1 3.5 1v-1.5L13 19v-5.5l8 2.5z" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"/>'
        + '</svg>';
    }
    if (goal === 'DAILY_LIFE') {
      return '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" aria-hidden="true">'
        + '<path d="M3 10.5L12 3l9 7.5M5.5 9v10.5a1 1 0 0 0 1 1h11a1 1 0 0 0 1-1V9M9.5 20.5v-6h5v6" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"/>'
        + '</svg>';
    }
    return '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" aria-hidden="true">'
      + '<rect x="3" y="7" width="18" height="13" rx="2.5" stroke="currentColor" stroke-width="1.75"/>'
      + '<path d="M8 7V5a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2M3 12h18" stroke="currentColor" stroke-width="1.75" stroke-linecap="round"/>'
      + '</svg>';
  }

  function svgTopicIcon() {
    return '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" aria-hidden="true">'
      + '<path d="M4 6.5A2.5 2.5 0 0 1 6.5 4h11A2.5 2.5 0 0 1 20 6.5v7a2.5 2.5 0 0 1-2.5 2.5H10l-4.5 3.5V16H6.5A2.5 2.5 0 0 1 4 13.5v-7z" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"/>'
      + '</svg>';
  }

  function topicLabel(code) {
    if (root && root.CpathMock && root.CpathMock.TOPIC_LABELS && root.CpathMock.TOPIC_LABELS[code]) {
      return root.CpathMock.TOPIC_LABELS[code];
    }
    return TOPIC_LABELS_FALLBACK[code] || code;
  }

  function getAvailableTopics(goals, state) {
    var goalObj = (goals || []).filter(function (g) { return g.goal === state.goal; })[0];
    if (!goalObj) return [];
    var codes = [];
    var fields = Array.isArray(goalObj.fields) ? goalObj.fields : [];
    if (state.goal === 'WORK') {
      var activeFields = ['OFFICE'].concat(state.fields.filter(function (f) { return f !== 'OFFICE'; }));
      fields.forEach(function (f) {
        if (activeFields.indexOf(f.code) >= 0 && Array.isArray(f.topicCodes)) {
          f.topicCodes.forEach(function (tc) {
            if (codes.indexOf(tc) < 0) codes.push(tc);
          });
        }
      });
    } else {
      fields.forEach(function (f) {
        if (Array.isArray(f.topicCodes)) {
          f.topicCodes.forEach(function (tc) {
            if (codes.indexOf(tc) < 0) codes.push(tc);
          });
        }
      });
    }
    if (!codes.length) {
      if (state.goal === 'TRAVEL') codes = ['AIR', 'TRAIN', 'HOTEL', 'FOOD', 'SHOP', 'TROUBLE'];
      else if (state.goal === 'DAILY_LIFE') codes = ['HOME', 'MARKET', 'PUBLIC', 'HEALTH', 'SCHOOL', 'SOCIAL', 'TIME'];
      else codes = ['OFFICE-GREETING', 'OFFICE-REPORT', 'OFFICE-LEAVE', 'OFFICE-PHONE', 'OFFICE-MEETING'];
    }
    return codes.map(function (c) {
      return { code: c, labelVi: topicLabel(c) };
    });
  }

  function buildPayload(state) {
    var fields = state.goal === 'WORK'
      ? ['OFFICE'].concat((state.fields || []).filter(function (f) { return f !== 'OFFICE'; }).slice(0, 2))
      : [];
    var payload = {
      goal: state.goal,
      fields: fields,
      priorityTopics: (state.priorityTopics || []).slice(),
      selfLevel: state.selfLevel || 'ELEMENTARY',
      dailySituations: Number(state.dailySituations) === 1 ? 1 : 2,
      restDays: (state.restDays || []).slice()
    };
    if (state.finishDate) payload.finishDate = state.finishDate;
    return payload;
  }

  function surveyHtml(goals, state) {
    var step = state.step || 1;
    var meta = STEP_META[step - 1] || STEP_META[0];
    var pct = Math.round((step / 5) * 100);

    var dotsHtml = STEP_META.map(function (m) {
      var cls = 'cp-survey__dot' + (m.step === step ? ' is-active' : (m.step < step ? ' is-done' : ''));
      return '<button type="button" class="' + cls + '" data-cp-step-jump="' + m.step + '" aria-label="Bước ' + m.step + '">'
        + m.step + '</button>';
    }).join('');

    var bodyHtml = '';
    if (step === 1) {
      var goalCards = (goals || []).map(function (g) {
        var sel = state.goal === g.goal;
        var desc = g.goal === 'TRAVEL'
          ? (g.topicCount + ' chủ đề · Sân bay, tàu điện, khách sạn, ăn uống, mua sắm')
          : (g.goal === 'DAILY_LIFE'
            ? (g.topicCount + ' chủ đề · Nhà cửa, siêu thị, hành chính, bệnh viện, trường học')
            : (g.goal === 'BA'
              ? (g.topicCount + ' chủ đề · Quản lý dự án, yêu cầu & thiết kế, lập trình, kiểm thử, vận hành')
              : (g.topicCount + ' chủ đề · Văn phòng chung, Nhà máy, Dịch vụ')));
        return '<button type="button" class="cp-pill' + (sel ? ' is-selected' : '') + '"'
          + ' data-cp-goal="' + esc(g.goal) + '" aria-pressed="' + (sel ? 'true' : 'false') + '">'
          + '<span class="cp-pill__icon">' + svgGoalIcon(g.goal) + '</span>'
          + '<span class="cp-pill__body">'
          + '<span class="cp-pill__label">' + esc(g.nameVi) + '</span>'
          + '<span class="cp-pill__desc">' + esc(desc) + '</span>'
          + '</span>'
          + '<span class="cp-pill__check">' + svgCheck() + '</span>'
          + '</button>';
      }).join('');
      bodyHtml = '<div class="cp-survey__grid" role="group" aria-label="Chọn mục tiêu giao tiếp">' + goalCards + '</div>';
    } else if (step === 2) {
      var workGoal = (goals || []).filter(function (g) { return g.goal === 'WORK'; })[0];
      var workFields = (workGoal && Array.isArray(workGoal.fields)) ? workGoal.fields : [
        { code: 'OFFICE', labelVi: 'Văn phòng chung', topicCodes: ['OFFICE-GREETING', 'OFFICE-REPORT', 'OFFICE-LEAVE', 'OFFICE-PHONE', 'OFFICE-MEETING'] },
        { code: 'IT_BRSE', labelVi: 'IT / BrSE', topicCodes: ['REQ', 'DEV', 'TEST', 'BUG', 'OPS', 'PM', 'COM'] },
        { code: 'FACTORY', labelVi: 'Nhà máy / Sản xuất', topicCodes: ['FACTORY-SAFETY', 'FACTORY-SHIFT', 'FACTORY-INSTRUCTION', 'FACTORY-ERROR', 'FACTORY-5S'] },
        { code: 'SERVICE', labelVi: 'Dịch vụ / Khách sạn', topicCodes: ['SERVICE-RECEPTION', 'SERVICE-PRODUCT', 'SERVICE-PAYMENT', 'SERVICE-COMPLAINT', 'SERVICE-BOOKING'] }
      ];
      if (state.goal !== 'WORK') {
        var curGoalName = state.goal === 'TRAVEL' ? 'Du lịch' : 'Đời sống';
        bodyHtml = '<div class="cp-survey__row">'
          + '<div class="cp-pill is-selected is-locked" aria-disabled="true">'
          + '<span class="cp-pill__icon">' + svgGoalIcon(state.goal) + '</span>'
          + '<span class="cp-pill__body">'
          + '<span class="cp-pill__label">Mục tiêu ' + esc(curGoalName) + ' (Không chia theo ngành công việc)</span>'
          + '<span class="cp-pill__desc">Bước chọn ngành áp dụng khi chọn mục tiêu Công việc. Bạn có thể bấm Tiếp tục để chọn chủ đề ưu tiên, hoặc đổi sang Công việc bên dưới.</span>'
          + '</span>'
          + '<span class="cp-pill__check">' + svgCheck() + '</span>'
          + '</div>'
          + '</div>';
      } else {
        var fieldCards = workFields.map(function (f) {
          var isOffice = f.code === 'OFFICE';
          var sel = isOffice || state.fields.indexOf(f.code) >= 0;
          var count = Array.isArray(f.topicCodes) ? f.topicCodes.length : 5;
          var sub = isOffice ? ('Luôn có sẵn · ' + count + ' chủ đề nền tảng') : (count + ' chủ đề chuyên ngành (chọn tối đa 2 ngành)');
          return '<button type="button" class="cp-pill' + (sel ? ' is-selected' : '') + (isOffice ? ' is-locked' : '') + '"'
            + ' data-cp-field="' + esc(f.code) + '"' + (isOffice ? ' aria-disabled="true"' : '')
            + ' aria-pressed="' + (sel ? 'true' : 'false') + '">'
            + '<span class="cp-pill__icon">' + svgGoalIcon('WORK') + '</span>'
            + '<span class="cp-pill__body">'
            + '<span class="cp-pill__label">' + esc(f.labelVi) + '</span>'
            + '<span class="cp-pill__desc">' + esc(sub) + '</span>'
            + '</span>'
            + '<span class="cp-pill__check">' + svgCheck() + '</span>'
            + '</button>';
        }).join('');
        bodyHtml = '<div class="cp-survey__grid" role="group" aria-label="Chọn ngành công việc">' + fieldCards + '</div>';
      }
    } else if (step === 3) {
      var topics = getAvailableTopics(goals, state);
      var topicBtns = topics.map(function (t) {
        var sel = state.priorityTopics.indexOf(t.code) >= 0;
        return '<button type="button" class="cp-pill' + (sel ? ' is-selected' : '') + '"'
          + ' data-cp-topic="' + esc(t.code) + '" aria-pressed="' + (sel ? 'true' : 'false') + '">'
          + '<span class="cp-pill__icon">' + svgTopicIcon() + '</span>'
          + '<span class="cp-pill__body">'
          + '<span class="cp-pill__label">' + esc(t.labelVi) + '</span>'
          + '<span class="cp-pill__desc">3 tình huống hội thoại</span>'
          + '</span>'
          + '<span class="cp-pill__check">' + svgCheck() + '</span>'
          + '</button>';
      }).join('');
      bodyHtml = '<div class="cp-survey__top">'
        + '<span class="cp-survey__sub">Đã chọn ưu tiên: <strong>' + state.priorityTopics.length + '/' + topics.length + '</strong> chủ đề</span>'
        + '<button type="button" class="cp-btn-ghost" data-cp-topics-toggle>'
        + (state.priorityTopics.length === topics.length ? 'Bỏ chọn tất cả' : 'Chọn tất cả')
        + '</button>'
        + '</div>'
        + '<div class="cp-survey__grid cp-survey__grid--topics" role="group" aria-label="Chủ đề ưu tiên">' + topicBtns + '</div>';
    } else if (step === 4) {
      var lvCards = SELF_LEVELS.map(function (lv) {
        var sel = state.selfLevel === lv.code;
        return '<button type="button" class="cp-pill' + (sel ? ' is-selected' : '') + '"'
          + ' data-cp-level="' + esc(lv.code) + '" aria-pressed="' + (sel ? 'true' : 'false') + '">'
          + '<span class="cp-pill__icon">' + svgTopicIcon() + '</span>'
          + '<span class="cp-pill__body">'
          + '<span class="cp-pill__label">' + esc(lv.labelVi) + '</span>'
          + '<span class="cp-pill__desc">' + esc(lv.descVi) + '</span>'
          + '</span>'
          + '<span class="cp-pill__check">' + svgCheck() + '</span>'
          + '</button>';
      }).join('');
      bodyHtml = '<div class="cp-survey__grid" role="group" aria-label="Tự đánh giá trình độ">' + lvCards + '</div>';
    } else {
      var pace1 = Number(state.dailySituations) === 1;
      var pace2 = Number(state.dailySituations) !== 1;
      var restChips = DAYS_OF_WEEK.map(function (d) {
        var isRest = state.restDays.indexOf(d.code) >= 0;
        return '<button type="button" class="cp-rest-chip" data-cp-rest="' + d.code + '" aria-pressed="' + (isRest ? 'true' : 'false') + '" title="' + esc(d.full) + '">'
          + '<span>' + esc(d.label) + '</span>'
          + (isRest ? svgCheck() : '')
          + '</button>';
      }).join('');
      bodyHtml = '<div class="cp-survey__row">'
        + '<span class="cp-survey__label">Số tình huống học mỗi ngày</span>'
        + '<div class="cp-pace-grid">'
        + '<button type="button" class="cp-pill' + (pace1 ? ' is-selected' : '') + '" data-cp-daily="1" aria-pressed="' + (pace1 ? 'true' : 'false') + '">'
        + '<span class="cp-pill__body"><span class="cp-pill__label">1 tình huống / ngày</span><span class="cp-pill__desc">Khoảng 15 phút · Nhẹ nhàng, đều đặn</span></span>'
        + '<span class="cp-pill__check">' + svgCheck() + '</span>'
        + '</button>'
        + '<button type="button" class="cp-pill' + (pace2 ? ' is-selected' : '') + '" data-cp-daily="2" aria-pressed="' + (pace2 ? 'true' : 'false') + '">'
        + '<span class="cp-pill__body"><span class="cp-pill__label">2 tình huống / ngày</span><span class="cp-pill__desc">Khoảng 30 phút · Tiến bộ nhanh</span></span>'
        + '<span class="cp-pill__check">' + svgCheck() + '</span>'
        + '</button>'
        + '</div>'
        + '</div>'
        + '<div class="cp-survey__row">'
        + '<span class="cp-survey__label">Chọn ngày nghỉ trong tuần (bấm để bật/tắt ngày nghỉ)</span>'
        + '<div class="cp-rest-days" role="group" aria-label="Ngày nghỉ trong tuần">' + restChips + '</div>'
        + '</div>'
        + '<div class="cp-survey__row">'
        + '<label class="cp-survey__label" for="cpFinishDateInput">Hạn muốn hoàn thành (tùy chọn)</label>'
        + '<input id="cpFinishDateInput" type="date" class="cp-survey__date-input" data-cp-finish-date value="' + esc(state.finishDate || '') + '" />'
        + '</div>';
    }

    var prevBtn = step > 1
      ? '<button type="button" class="cp-btn-ghost" data-cp-prev>‹ Quay lại</button>'
      : '<span></span>';
    var nextBtn = step < 5
      ? '<button type="button" class="cp-btn-primary" data-cp-next>Tiếp tục ›</button>'
      : '<button type="button" class="cp-btn-primary" data-cp-submit' + (state.submitting ? ' disabled' : '') + '>'
        + (state.submitting ? 'Đang tạo lộ trình…' : 'Bắt đầu lộ trình giao tiếp') + '</button>';

    return '<section class="cp-survey__card" aria-labelledby="cpSurveyTitle">'
      + '<div class="cp-survey__top">'
      + '<div class="cp-survey__progress" aria-label="Tiến độ khảo sát">'
      + '<div class="cp-survey__bar"><div class="cp-survey__bar-fill" style="width:' + pct + '%"></div></div>'
      + '<span class="cp-survey__step-num">' + step + '/5</span>'
      + '</div>'
      + '<div class="cp-survey__dots">' + dotsHtml + '</div>'
      + '</div>'
      + '<header class="cp-survey__head">'
      + '<img class="cp-survey__kitsu" src="' + esc(kitsuImgSrc(meta.kitsu)) + '" width="52" height="52" alt="Kitsu" />'
      + '<div class="cp-survey__titles">'
      + '<h1 id="cpSurveyTitle" class="cp-survey__title">' + esc(meta.title) + '</h1>'
      + '<p class="cp-survey__sub">' + esc(meta.sub) + '</p>'
      + '</div>'
      + '</header>'
      + bodyHtml
      + (state.errorMsg ? '<p class="jp-alert" role="alert">' + esc(state.errorMsg) + '</p>' : '')
      + '<footer class="cp-survey__foot">' + prevBtn + nextBtn + '</footer>'
      + '</section>';
  }

  function mount(targetEl) {
    var doc = root && root.document;
    var el = targetEl || (doc && doc.getElementById && doc.getElementById('cpathSurveyRoot'));
    if (!el || !root.JpathApi) return;

    var state = {
      step: 1,
      goal: 'TRAVEL',
      fields: ['OFFICE', 'IT_BRSE'],
      priorityTopics: ['AIR', 'TRAIN'],
      selfLevel: 'ELEMENTARY',
      dailySituations: 2,
      restDays: ['SUN'],
      finishDate: '',
      submitting: false,
      errorMsg: ''
    };
    var goalsData = [];

    function syncDefaultTopics() {
      var avail = getAvailableTopics(goalsData, state);
      var validCodes = avail.map(function (t) { return t.code; });
      state.priorityTopics = state.priorityTopics.filter(function (c) { return validCodes.indexOf(c) >= 0; });
      if (!state.priorityTopics.length && validCodes.length) {
        state.priorityTopics = validCodes.slice(0, Math.min(2, validCodes.length));
      }
    }

    function render() {
      el.setAttribute('aria-busy', 'false');
      el.innerHTML = surveyHtml(goalsData, state);
    }

    el.addEventListener('click', function (ev) {
      var t = ev.target;
      if (!t || typeof t.closest !== 'function') return;

      var jumpBtn = t.closest('[data-cp-step-jump]');
      if (jumpBtn) {
        state.step = Math.max(1, Math.min(5, Number(jumpBtn.getAttribute('data-cp-step-jump')) || 1));
        state.errorMsg = '';
        render();
        return;
      }

      var goalBtn = t.closest('[data-cp-goal]');
      if (goalBtn) {
        state.goal = goalBtn.getAttribute('data-cp-goal') || 'TRAVEL';
        syncDefaultTopics();
        render();
        return;
      }

      var fieldBtn = t.closest('[data-cp-field]');
      if (fieldBtn) {
        var fCode = fieldBtn.getAttribute('data-cp-field');
        if (fCode && fCode !== 'OFFICE') {
          var extra = state.fields.filter(function (x) { return x !== 'OFFICE'; });
          var idx = extra.indexOf(fCode);
          if (idx >= 0) {
            extra.splice(idx, 1);
          } else {
            if (extra.length >= 2) extra.shift();
            extra.push(fCode);
          }
          state.fields = ['OFFICE'].concat(extra);
          syncDefaultTopics();
          render();
        }
        return;
      }

      var topicBtn = t.closest('[data-cp-topic]');
      if (topicBtn) {
        var tCode = topicBtn.getAttribute('data-cp-topic');
        var tIdx = state.priorityTopics.indexOf(tCode);
        if (tIdx >= 0) state.priorityTopics.splice(tIdx, 1);
        else state.priorityTopics.push(tCode);
        render();
        return;
      }

      var toggleAllBtn = t.closest('[data-cp-topics-toggle]');
      if (toggleAllBtn) {
        var allTopics = getAvailableTopics(goalsData, state).map(function (x) { return x.code; });
        if (state.priorityTopics.length === allTopics.length) state.priorityTopics = [];
        else state.priorityTopics = allTopics;
        render();
        return;
      }

      var lvBtn = t.closest('[data-cp-level]');
      if (lvBtn) {
        state.selfLevel = lvBtn.getAttribute('data-cp-level') || 'ELEMENTARY';
        render();
        return;
      }

      var dailyBtn = t.closest('[data-cp-daily]');
      if (dailyBtn) {
        state.dailySituations = Number(dailyBtn.getAttribute('data-cp-daily')) === 1 ? 1 : 2;
        render();
        return;
      }

      var restBtn = t.closest('[data-cp-rest]');
      if (restBtn) {
        var dCode = restBtn.getAttribute('data-cp-rest');
        var rIdx = state.restDays.indexOf(dCode);
        if (rIdx >= 0) state.restDays.splice(rIdx, 1);
        else if (state.restDays.length < 6) state.restDays.push(dCode);
        render();
        return;
      }

      // step 2 (industries) only applies to a goal that has industries (Công việc); the others skip it
      var noFields = function () {
        var g = (goalsData || []).filter(function (x) { return x.goal === state.goal; })[0];
        return !(g && g.fields && g.fields.length);
      };

      if (t.closest('[data-cp-prev]')) {
        state.step = Math.max(1, state.step - 1);
        if (state.step === 2 && noFields()) state.step = 1;
        state.errorMsg = '';
        render();
        return;
      }

      if (t.closest('[data-cp-next]')) {
        state.step = Math.min(5, state.step + 1);
        if (state.step === 2 && noFields()) state.step = 3;
        state.errorMsg = '';
        render();
        return;
      }

      if (t.closest('[data-cp-submit]')) {
        if (state.submitting) return;
        state.submitting = true;
        state.errorMsg = '';
        render();
        var payload = buildPayload(state);
        root.JpathApi.send('POST', '/api/learning/comm/path', payload).then(function () {
          var nextHref = (root.CpathMock && typeof root.CpathMock.pageUrl === 'function')
            ? root.CpathMock.pageUrl('path')
            : '/learning/comm/path';
          if (root.location) root.location.href = nextHref;
        }, function (err) {
          state.submitting = false;
          state.errorMsg = (err && err.message) ? err.message : 'Chưa tạo được lộ trình. Vui lòng thử lại.';
          render();
        });
      }
    });

    el.addEventListener('change', function (ev) {
      var t = ev.target;
      if (t && t.matches && t.matches('[data-cp-finish-date]')) {
        state.finishDate = t.value || '';
      }
    });

    root.JpathApi.get('/api/learning/comm/goals').then(function (data) {
      goalsData = Array.isArray(data) && data.length ? data : (root.CpathMock ? root.CpathMock.GOALS_DATA : []);
      syncDefaultTopics();
      render();
    }, function () {
      goalsData = root.CpathMock ? root.CpathMock.GOALS_DATA : [];
      syncDefaultTopics();
      render();
    });

    // a learner who already has a main goal adds a secondary one here (free study, no daily plan)
    Promise.resolve(root.JpathApi.get('/api/learning/comm/path')).then(function (p) {
      if (!p || !p.goal) return;
      STEP_META[0].title = 'Thêm mục tiêu phụ để học tự do';
      STEP_META[0].sub = 'Lộ trình chính (' + (p.nameVi || p.goal) + ') vẫn giữ nguyên lịch học hằng ngày. '
        + 'Mục tiêu phụ không giao bài theo ngày, bạn học khi muốn; chọn mục tiêu phụ mới sẽ thay mục tiêu phụ cũ.';
      render();
    }, function () { /* no main goal yet: this survey makes it */ });
  }

  return {
    surveyHtml: surveyHtml,
    buildPayload: buildPayload,
    getAvailableTopics: getAvailableTopics,
    mount: mount
  };
});
