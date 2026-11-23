/* cpath-survey.js — Khảo sát đầu vào của Lộ trình Giao tiếp (survey.html).
 * [JLAS-W0108] Owner 07/11: mỗi thông tin hỏi 1 lần. Mục tiêu đã chọn ở khảo sát chung (?goal=) thì bỏ bước mục tiêu.
 * "Công việc" gộp 2 mục tiêu dữ liệu: IT / BrSE (goal BA) và Văn phòng, Nhà máy, Dịch vụ (goal WORK), chọn ở bước lĩnh vực.
 * Bước hiện ra tuỳ mục tiêu: [mục tiêu] -> [lĩnh vực, chỉ Công việc] -> chủ đề ưu tiên -> trình độ -> nhịp học.
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

  // First published situation of each topic in communication-compiled/*.json (owner 11/11, F55).
  var TOPIC_EXAMPLES = {
    AIR: 'Làm thủ tục ở sân bay', TRAIN: 'Đổi tàu', HOTEL: 'Nhận phòng khách sạn',
    FOOD: 'Gọi món ở nhà hàng', SHOP: 'Mua sắm và miễn thuế', TROUBLE: 'Khi bị lạc đường',
    HOME: 'Thuê phòng', MARKET: 'Đi siêu thị', PUBLIC: 'Làm thủ tục ngân hàng',
    HEALTH: 'Khám bệnh', SCHOOL: 'Báo nghỉ học', SOCIAL: 'Chuyện hàng xóm',
    TIME: 'Hỏi giờ mở cửa', PM: 'Báo cáo tiến độ trong họp định kỳ',
    COM: 'Chào hỏi lần đầu gặp khách hàng', REQ: 'Xác nhận điểm chưa rõ trong đặc tả',
    DEV: 'Tiếp nhận góp ý code review', TEST: 'Báo cáo kết quả kiểm thử',
    BUG: 'Tiếp nhận báo lỗi từ khách hàng', OPS: 'Báo cáo sơ bộ sự cố production',
    'OFFICE-GREETING': 'Chào buổi sáng', 'OFFICE-REPORT': 'Báo cáo tiến độ',
    'OFFICE-LEAVE': 'Xin nghỉ phép', 'OFFICE-PHONE': 'Nhận điện thoại',
    'OFFICE-MEETING': 'Chuẩn bị họp', 'FACTORY-SAFETY': 'Đeo đồ bảo hộ',
    'FACTORY-SHIFT': 'Xác nhận ca làm', 'FACTORY-INSTRUCTION': 'Xác nhận quy trình',
    'FACTORY-ERROR': 'Báo máy hỏng', 'FACTORY-5S': 'Chỉ thị sắp xếp gọn gàng',
    'SERVICE-RECEPTION': 'Đón khách đến cửa hàng', 'SERVICE-PRODUCT': 'Giới thiệu sản phẩm mới',
    'SERVICE-PAYMENT': 'Phương thức thanh toán', 'SERVICE-COMPLAINT': 'Phàn nàn hàng lỗi',
    'SERVICE-BOOKING': 'Đặt phòng'
  };

  var STEP_META = {
    goal: {
      kitsu: 'kitsu-cheer-30.webp',
      title: 'Bạn muốn học giao tiếp tiếng Nhật cho mục tiêu nào?',
      sub: 'Mục tiêu chọn ở đây sẽ là lộ trình chính. Mục tiêu khác có thể học tự do thêm trong Cài đặt.'
    },
    field: {
      kitsu: 'kitsu-thinking-30.webp',
      title: 'Bạn làm việc trong lĩnh vực nào?',
      sub: 'IT / BrSE là lộ trình riêng. Với Văn phòng, Nhà máy, Dịch vụ: Văn phòng chung luôn có, chọn thêm tối đa 2 lĩnh vực.'
    },
    topics: {
      kitsu: 'kitsu-thinking-30.webp',
      title: 'Bạn muốn ưu tiên những chủ đề nào trước?',
      sub: 'Chủ đề được chọn sẽ xếp lên đầu lộ trình. Các chủ đề còn lại vẫn học sau.'
    },
    level: {
      kitsu: 'kitsu-cheer-30.webp',
      title: 'Bạn giao tiếp tiếng Nhật ở mức nào?',
      sub: 'Không cần làm bài kiểm tra. Lộ trình chỉnh phiên âm và tốc độ nghe theo mức bạn chọn.'
    },
    pace: {
      kitsu: 'kitsu-celebrate-30.webp',
      title: 'Chọn nhịp học mỗi ngày và ngày nghỉ',
      sub: 'Mỗi tình huống có 4 bước: Từ & mẫu câu · Nghe · Nhập vai · Nói.'
    }
  };

  // "Công việc" in the UI: goal BA (IT / BrSE topics) or WORK (office + industries)
  var JOB = 'JOB';
  var IT_FIELD = 'IT_BRSE';
  var BA_TOPICS = ['PM', 'COM', 'REQ', 'DEV', 'TEST', 'BUG', 'OPS'];
  function isJob(goal) { return goal === 'BA' || goal === 'WORK' || goal === JOB; }
  /** True when {@code goal} (a card code, JOB covers BA/WORK) is the learner's current main comm goal. */
  function isMainGoal(state, goal) {
    var main = state && state.secondaryPath ? state.mainGoal : null;
    if (!main) return false;
    return goal === JOB || isJob(goal) ? isJob(main) : goal === main;
  }
  function isOffice(code) { return /OFFICE$/.test(String(code || '')); }

  /** The goal cards: Du lịch, Đời sống and one "Công việc" card standing for BA + WORK. */
  function displayGoals(goals) {
    var list = (goals || []).filter(function (g) { return !isJob(g.goal); });
    var jobs = (goals || []).filter(function (g) { return isJob(g.goal); });
    if (jobs.length) {
      list.push({ goal: JOB, nameVi: 'Công việc', topicCount: jobs.reduce(function (n, g) { return n + (g.topicCount || 0); }, 0) });
    }
    return list;
  }

  /** The field cards of "Công việc": the WORK industries (server codes) plus IT / BrSE. */
  function jobFields(goals) {
    var work = (goals || []).filter(function (g) { return g.goal === 'WORK'; })[0];
    var list = (work && Array.isArray(work.fields) ? work.fields : []).slice();
    if ((goals || []).some(function (g) { return g.goal === 'BA'; })) {
      list.push({ code: IT_FIELD, labelVi: 'IT / BrSE', topicCodes: BA_TOPICS });
    }
    return list;
  }

  /** The steps this learner sees, in order. */
  function steps(state) {
    var out = state.presetGoal ? [] : ['goal'];
    if (isJob(state.goal)) out.push('field');
    return out.concat(['topics', 'level'], state.secondaryPath ? [] : ['pace']);
  }

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

  /** F70: "Mới bắt đầu" also says whether they can read Hiragana/Katakana; not yet = Kana suggestion + full romaji. */
  function kanaQuestionHtml(state) {
    if ((state.selfLevel || 'BEGINNER') !== 'BEGINNER') return '';
    var yes = state.knowsKana === true, no = state.knowsKana === false;
    return '<fieldset class="cp-survey__kana"><legend>Bạn đã đọc được chữ cái tiếng Nhật (Hiragana, Katakana) chưa?</legend>'
      + '<div class="cp-survey__kana-opts">'
      + '<button type="button" class="cp-rest-chip" data-cp-kana="yes" aria-pressed="' + (yes ? 'true' : 'false') + '"><span>Đã đọc được</span></button>'
      + '<button type="button" class="cp-rest-chip" data-cp-kana="no" aria-pressed="' + (no ? 'true' : 'false') + '"><span>Chưa đọc được</span></button>'
      + '</div>'
      + (no ? '<p class="cp-survey__kana-tip">Nên học bảng chữ cái trước, khoảng 1–2 tuần. '
        + '<a href="/kana-connect" target="_blank" rel="noopener">Mở trò chơi chữ cái</a>. '
        + 'Nếu bỏ qua, cả lộ trình sẽ hiện <strong>romaji đầy đủ</strong> dưới mọi câu và mọi từ; tắt được trong Cài đặt khi bạn đã đọc được chữ.</p>' : '')
      + '</fieldset>';
  }

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
    return '/images/mascots/fox/' + file;
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

  function topicLabel(code, goals) {
    for (var i = 0; i < (goals || []).length; i++) {
      var topics = goals[i].topics || [];
      for (var j = 0; j < topics.length; j++) {
        if (topics[j].code === code && topics[j].nameVi) return topics[j].nameVi;
      }
    }
    return TOPIC_LABELS_FALLBACK[code] || code;
  }

  function getAvailableTopics(goals, state) {
    var codes = [];
    var add = function (list) { (list || []).forEach(function (c) { if (codes.indexOf(c) < 0) codes.push(c); }); };
    if (state.goal === 'BA') {
      add(BA_TOPICS);
    } else if (state.goal === 'WORK') {
      jobFields(goals).forEach(function (f) {
        if (f.code !== IT_FIELD && (isOffice(f.code) || state.fields.indexOf(f.code) >= 0)) add(f.topicCodes);
      });
      if (!codes.length) add(['OFFICE-GREETING', 'OFFICE-REPORT', 'OFFICE-LEAVE', 'OFFICE-PHONE', 'OFFICE-MEETING']);
    } else if (state.goal === 'TRAVEL') {
      add(['AIR', 'TRAIN', 'HOTEL', 'FOOD', 'SHOP', 'TROUBLE']);
    } else if (state.goal === 'DAILY_LIFE') {
      add(['HOME', 'MARKET', 'PUBLIC', 'HEALTH', 'SCHOOL', 'SOCIAL', 'TIME']);
    }
    return codes.map(function (c) {
      return { code: c, labelVi: topicLabel(c, goals), exampleVi: TOPIC_EXAMPLES[c] || '' };
    });
  }

  function buildPayload(state) {
    // WORK sends the server's field codes; the office field is always part of the path (server side)
    var fields = state.goal === 'WORK'
      ? (state.fields || []).filter(function (f) { return f !== IT_FIELD && !isOffice(f); }).slice(0, 2)
      : [];
    var payload = {
      goal: state.goal,
      role: state.secondaryPath ? 'SECONDARY' : 'MAIN',
      fields: fields,
      priorityTopics: (state.priorityTopics || []).slice(),
      selfLevel: state.selfLevel || 'BEGINNER',
      dailySituations: Math.min(4, Math.max(1, Number(state.dailySituations) || 2)),
      restDays: (state.restDays || []).slice()
    };
    if (state.finishDate) payload.finishDate = state.finishDate;
    // F70 (owner 12/11): a beginner who cannot read Kana yet gets full romaji on the whole path
    if ((state.selfLevel || 'BEGINNER') === 'BEGINNER' && state.knowsKana === false) payload.knowsKana = false;
    return payload;
  }

  function surveyHtml(goals, state) {
    var list = steps(state);
    var step = Math.max(1, Math.min(list.length, state.step || 1));
    var kind = list[step - 1];
    var total = list.length;
    var meta = STEP_META[kind];
    if (kind === 'goal' && state.secondaryPath) {
      meta = {
        kitsu: STEP_META.goal.kitsu,
        title: 'Thêm mục tiêu phụ để học tự do',
        sub: 'Lộ trình chính (' + (state.mainNameVi || state.mainGoal || 'Giao tiếp') + ') vẫn giữ nguyên lịch học hằng ngày. '
          + 'Mục tiêu phụ không giao bài theo ngày, bạn học khi muốn; chọn mục tiêu phụ mới sẽ thay mục tiêu phụ cũ.'
      };
    }
    var pct = Math.round((step / total) * 100);

    var dotsHtml = list.map(function (k, i) {
      var n = i + 1;
      var cls = 'cp-survey__dot' + (n === step ? ' is-active' : (n < step ? ' is-done' : ''));
      return '<button type="button" class="' + cls + '" data-cp-step-jump="' + n + '" aria-label="Bước ' + n + '"'
        + (n === step ? ' aria-current="step"' : '') + '>' + n + '</button>';
    }).join('');

    var bodyHtml = '';
    if (kind === 'goal') {
      var goalCards = displayGoals(goals).map(function (g) {
        var sel = g.goal === JOB ? isJob(state.goal) : state.goal === g.goal;
        // secondary goal: the main goal cannot be picked again (mentor 11/11)
        var isMain = isMainGoal(state, g.goal);
        if (isMain) sel = false;
        var desc = g.goal === 'TRAVEL'
          ? (g.topicCount + ' chủ đề · Sân bay, tàu điện, khách sạn, ăn uống, mua sắm')
          : (g.goal === 'DAILY_LIFE'
            ? (g.topicCount + ' chủ đề · Nhà cửa, siêu thị, hành chính, bệnh viện, trường học')
            : (g.topicCount + ' chủ đề · IT / BrSE, Văn phòng, Nhà máy, Dịch vụ'));
        if (isMain) desc = 'Đang là lộ trình chính · ' + desc;
        return '<button type="button" class="cp-pill' + (sel ? ' is-selected' : '') + '"'
          + ' data-cp-goal="' + esc(g.goal) + '" aria-pressed="' + (sel ? 'true' : 'false') + '"'
          + (isMain ? ' disabled title="Đây là lộ trình chính của bạn"' : '') + '>'
          + '<span class="cp-pill__icon">' + svgGoalIcon(g.goal) + '</span>'
          + '<span class="cp-pill__body">'
          + '<span class="cp-pill__label">' + esc(g.nameVi) + '</span>'
          + '<span class="cp-pill__desc">' + esc(desc) + '</span>'
          + '</span>'
          + '<span class="cp-pill__check">' + svgCheck() + '</span>'
          + '</button>';
      }).join('');
      bodyHtml = '<div class="cp-survey__grid" role="group" aria-label="Chọn mục tiêu giao tiếp">' + goalCards + '</div>';
    } else if (kind === 'field') {
      var fieldCards = jobFields(goals).map(function (f) {
        var it = f.code === IT_FIELD;
        var office = isOffice(f.code);
        var sel = it ? state.goal === 'BA' : (state.goal === 'WORK' && (office || state.fields.indexOf(f.code) >= 0));
        var count = Array.isArray(f.topicCodes) ? f.topicCodes.length : 5;
        var sub = it ? (count + ' chủ đề · Dự án, yêu cầu, lập trình, kiểm thử, vận hành')
          : (office ? ('Luôn có khi chọn Văn phòng, Nhà máy, Dịch vụ · ' + count + ' chủ đề') : (count + ' chủ đề chuyên ngành'));
        return '<button type="button" class="cp-pill' + (sel ? ' is-selected' : '') + '"'
          + ' data-cp-field="' + esc(f.code) + '" aria-pressed="' + (sel ? 'true' : 'false') + '">'
          + '<span class="cp-pill__icon">' + svgGoalIcon('WORK') + '</span>'
          + '<span class="cp-pill__body">'
          + '<span class="cp-pill__label">' + esc(f.labelVi) + '</span>'
          + '<span class="cp-pill__desc">' + esc(sub) + '</span>'
          + '</span>'
          + '<span class="cp-pill__check">' + svgCheck() + '</span>'
          + '</button>';
      }).join('');
      bodyHtml = '<div class="cp-survey__grid" role="group" aria-label="Chọn lĩnh vực công việc">' + fieldCards + '</div>';
    } else if (kind === 'topics') {
      var topics = getAvailableTopics(goals, state);
      var topicBtns = topics.map(function (t) {
        var sel = state.priorityTopics.indexOf(t.code) >= 0;
        return '<button type="button" class="cp-pill' + (sel ? ' is-selected' : '') + '"'
          + ' data-cp-topic="' + esc(t.code) + '" aria-pressed="' + (sel ? 'true' : 'false') + '">'
          + '<span class="cp-pill__icon">' + svgTopicIcon() + '</span>'
          + '<span class="cp-pill__body">'
          + '<span class="cp-pill__label">' + esc(t.labelVi) + '</span>'
          + '<span class="cp-pill__desc">' + esc(t.exampleVi ? 'Ví dụ: ' + t.exampleVi : 'Tình huống hội thoại') + '</span>'
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
    } else if (kind === 'level') {
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
      bodyHtml = '<div class="cp-survey__grid" role="group" aria-label="Tự đánh giá trình độ">' + lvCards + '</div>'
        + kanaQuestionHtml(state);
    } else {
      // owner 18/11: 15 phút = 1 tình huống, tối đa 60 phút = 4 (chỉ là ước lượng)
      var paceN = Math.min(4, Math.max(1, Number(state.dailySituations) || 2));
      var PACES = [{ n: 1, d: 'Nhẹ nhàng, đều đặn' }, { n: 2, d: 'Vừa sức' }, { n: 3, d: 'Tiến bộ nhanh' }, { n: 4, d: 'Học dồn' }];
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
        + PACES.map(function (pc) {
          var on = paceN === pc.n;
          return '<button type="button" class="cp-pill' + (on ? ' is-selected' : '') + '" data-cp-daily="' + pc.n + '" aria-pressed="' + (on ? 'true' : 'false') + '">'
            + '<span class="cp-pill__body"><span class="cp-pill__label">' + pc.n + ' tình huống / ngày</span><span class="cp-pill__desc">Khoảng ' + (pc.n * 15) + ' phút · ' + pc.d + '</span></span>'
            + '<span class="cp-pill__check">' + svgCheck() + '</span></button>';
        }).join('')
        + '</div>'
        + '</div>'
        + '<div class="cp-survey__row">'
        + '<span class="cp-survey__label">Chọn ngày nghỉ trong tuần (bấm để bật/tắt ngày nghỉ)</span>'
        + '<div class="cp-rest-days" role="group" aria-label="Ngày nghỉ trong tuần">' + restChips + '</div>'
        + (state.restNote ? '<p class="cp-survey__hint" role="status">' + esc(state.restNote) + '</p>' : '')
        + '</div>'
        + '<div class="cp-survey__row">'
        + '<label class="cp-survey__label" for="cpFinishDateInput">Hạn muốn hoàn thành (tùy chọn)</label>'
        + '<input id="cpFinishDateInput" type="date" class="cp-survey__date-input" data-cp-finish-date min="' + (function () { var d = new Date(); return d.getFullYear() + '-' + ('0' + (d.getMonth() + 1)).slice(-2) + '-' + ('0' + d.getDate()).slice(-2); })() + '" value="' + esc(state.finishDate || '') + '" />'
        + '</div>';
    }

    // owner 17/11: a learner coming from the goal step (no path yet) goes back to that step, not to a path they lack
    var topBack = typeof document !== 'undefined' && document.querySelector ? document.querySelector('.jp-back') : null;
    if (topBack && !state.secondaryPath) {
      topBack.setAttribute('href', '/survey?edit=goal');
      var lbl = topBack.querySelector('span');
      if (lbl) lbl.textContent = 'Quay lại chọn mục tiêu';
    }
    var prevBtn = step > 1
      ? '<button type="button" class="cp-btn-ghost" data-cp-prev>Quay lại</button>'
      // step 1 still goes back: a secondary goal returns to the path, otherwise to the general survey (owner 11/11)
      : '<a class="cp-btn-ghost" data-cp-back-out href="' + (state.secondaryPath ? '/learning/comm/path' : '/survey?edit=goal') + '">Quay lại</a>';
    var nextBtn = step < total
      ? '<button type="button" class="cp-btn-primary" data-cp-next>Tiếp tục</button>'
      : '<button type="button" class="cp-btn-primary" data-cp-submit' + (state.submitting ? ' disabled' : '') + '>'
        + (state.submitting ? (state.secondaryPath ? 'Đang thêm mục tiêu phụ…' : 'Đang tạo lộ trình…')
          : (state.secondaryPath ? 'Thêm mục tiêu phụ' : 'Bắt đầu lộ trình giao tiếp')) + '</button>';

    return '<section class="cp-survey__card" aria-labelledby="cpSurveyTitle">'
      + '<div class="cp-survey__top">'
      + '<div class="cp-survey__progress" aria-label="Tiến độ khảo sát">'
      + '<div class="cp-survey__bar"><div class="cp-survey__bar-fill" style="width:' + pct + '%"></div></div>'
      + '<span class="cp-survey__step-num">' + step + '/' + total + '</span>'
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
      presetGoal: false,
      fields: [],
      priorityTopics: [],
      selfLevel: 'BEGINNER',
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

    function syncSecondaryGoal() {
      // F170: changing the secondary goal starts on the one the learner has now (it used to preselect the first other goal)
      var cur = state.currentSecondary;
      if (cur && !state.secondaryPicked && !isMainGoal(state, cur)
          && goalsData.some(function (g) { return g.goal === cur || (isJob(cur) && g.goal === JOB); })) {
        state.secondaryPicked = true;
        if (state.goal !== cur) { state.goal = cur; state.fields = []; state.priorityTopics = []; }
      }
      if (isMainGoal(state, isJob(state.goal) ? JOB : state.goal)) {
        var other = displayGoals(goalsData).filter(function (g) { return !isMainGoal(state, g.goal); })[0];
        if (other) {
          state.goal = other.goal === JOB ? 'WORK' : other.goal;
          state.fields = [];
          state.priorityTopics = [];
        }
      }
      syncDefaultTopics();
    }

    // [JLAS-W0108] F155: wait for both /comm/goals and /comm/path so neither main-goal flash nor empty goal grid renders
    var goalsLoaded = false;
    var pathChecked = false;
    var loadFailed = false;
    var redirected = false;
    function render() {
      if (!goalsLoaded || !pathChecked || loadFailed || redirected) return;
      el.setAttribute('aria-busy', 'false');
      el.innerHTML = surveyHtml(goalsData, state);
    }

    el.addEventListener('click', function (ev) {
      var t = ev.target;
      if (!t || typeof t.closest !== 'function') return;

      var jumpBtn = t.closest('[data-cp-step-jump]');
      if (jumpBtn) {
        state.step = Math.max(1, Math.min(steps(state).length, Number(jumpBtn.getAttribute('data-cp-step-jump')) || 1));
        state.errorMsg = '';
        render();
        return;
      }

      var goalBtn = t.closest('[data-cp-goal]');
      if (goalBtn && !goalBtn.disabled && !(goalBtn.hasAttribute && goalBtn.hasAttribute('disabled'))) {
        var picked = goalBtn.getAttribute('data-cp-goal') || 'TRAVEL';
        // "Công việc" starts on WORK (office only); the field step picks IT / BrSE or the industries
        if (picked === JOB) { if (!isJob(state.goal)) { state.goal = 'WORK'; state.fields = []; } }
        else state.goal = picked;
        state.priorityTopics = [];
        syncDefaultTopics();
        render();
        return;
      }

      var fieldBtn = t.closest('[data-cp-field]');
      if (fieldBtn) {
        var fCode = fieldBtn.getAttribute('data-cp-field');
        if (fCode === IT_FIELD) {
          state.goal = 'BA';
          state.fields = [];
        } else if (fCode && !isOffice(fCode)) {
          var extra = state.goal === 'WORK' ? state.fields.slice() : [];
          state.goal = 'WORK';
          var idx = extra.indexOf(fCode);
          if (idx >= 0) extra.splice(idx, 1);
          else { if (extra.length >= 2) extra.shift(); extra.push(fCode); }
          state.fields = extra;
        } else {
          state.goal = 'WORK';
        }
        state.priorityTopics = [];
        syncDefaultTopics();
        render();
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

      var kanaBtn = t.closest('[data-cp-kana]');
      if (kanaBtn) {
        state.knowsKana = kanaBtn.getAttribute('data-cp-kana') === 'yes';
        render();
        return;
      }

      var lvBtn = t.closest('[data-cp-level]');
      if (lvBtn) {
        state.selfLevel = lvBtn.getAttribute('data-cp-level') || 'BEGINNER';
        render();
        return;
      }

      var dailyBtn = t.closest('[data-cp-daily]');
      if (dailyBtn) {
        state.dailySituations = Math.min(4, Math.max(1, Number(dailyBtn.getAttribute('data-cp-daily')) || 2));
        render();
        return;
      }

      var restBtn = t.closest('[data-cp-rest]');
      if (restBtn) {
        var dCode = restBtn.getAttribute('data-cp-rest');
        var rIdx = state.restDays.indexOf(dCode);
        if (rIdx >= 0) state.restDays.splice(rIdx, 1);
        else if (state.restDays.length < 6) state.restDays.push(dCode);
        // bug 17: the 7th day stays a study day; say why instead of ignoring the click
        state.restNote = rIdx < 0 && state.restDays.indexOf(dCode) < 0 ? 'Cần giữ ít nhất một ngày học trong tuần.' : '';
        render();
        return;
      }

      if (t.closest('[data-cp-prev]')) {
        state.step = Math.max(1, state.step - 1);
        state.errorMsg = '';
        render();
        return;
      }

      if (t.closest('[data-cp-next]')) {
        if (steps(state)[state.step - 1] === 'topics' && !state.priorityTopics.length) {
          state.errorMsg = 'Chọn ít nhất 1 chủ đề để xếp lên đầu lộ trình.';
          render();
          return;
        }
        state.step = Math.min(steps(state).length, state.step + 1);
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
          if (root.location) root.location.href = '/learning/comm/path';
        }, function (err) {
          state.submitting = false;
          state.errorMsg = (err && (err.code || err.status) && err.message) ? err.message : 'Chưa tạo được lộ trình. Vui lòng thử lại.';
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

    function failLoad() {
      loadFailed = true;
      el.setAttribute('aria-busy', 'false');
      el.innerHTML = '<section class="cp-survey__card"><p class="jp-alert" role="alert">Chưa tải được danh sách mục tiêu.</p>'
        + '<p><button type="button" class="cp-btn-primary" data-cp-reload>Thử lại</button></p></section>';
    }
    el.addEventListener('click', function (ev) {
      if (ev.target && ev.target.closest && ev.target.closest('[data-cp-reload]') && root.location) root.location.reload();
    });

    var urlGoal = null;
    var forceSecondary = false;
    try {
      var params = new (root.URLSearchParams || URLSearchParams)(String((root.location && root.location.search) || ''));
      urlGoal = params.get('goal');
      forceSecondary = params.get('secondary') === '1';
    } catch (e) {
      urlGoal = null;
      forceSecondary = false;
    }

    root.JpathApi.get('/api/learning/comm/goals').then(function (data) {
      goalsData = Array.isArray(data) ? data : [];
      if (!goalsData.length) { failLoad(); return; }
      goalsLoaded = true;
      // ?goal= from the general survey (BA = "Công việc", TRAVEL, DAILY_LIFE): that goal is not asked again
      var want = urlGoal;
      var has = function (c) { return goalsData.some(function (g) { return g.goal === c; }); };
      function applyPresetGoal() {
        // adding a secondary goal always starts on the goal step; the saved survey goal is the main one
        if (state.secondaryPath) { syncSecondaryGoal(); render(); return; }
        if (want && isJob(want) && (has('WORK') || has('BA'))) {
          state.goal = has('WORK') ? 'WORK' : 'BA';
          state.presetGoal = true;
        } else if (want && has(want)) {
          state.goal = want;
          state.presetGoal = true;
        }
        syncDefaultTopics();
        render();
      }
      applyPresetGoal();
      if (!want && !forceSecondary) {
        Promise.resolve(root.JpathApi.get('/api/assessment/survey')).then(function (survey) {
          var savedSurvey = survey && (survey.survey || (survey.data && survey.data.survey));
          var saved = savedSurvey && savedSurvey.goal;
          if (!saved) return;
          want = saved;
          applyPresetGoal();
        }).catch(function () {});
      }
    }, failLoad);

    // [JLAS-W0108] F152/F155: distinguish secondary-goal add (?secondary=1 or plain /survey with existing path)
    // from main-goal edit (/survey?edit=goal -> /learning/comm/survey?goal=...)
    Promise.resolve(root.JpathApi.get('/api/learning/comm/path')).then(function (p) {
      if (!p || !p.goal) {
        pathChecked = true;
        render();
        return;
      }
      if (!forceSecondary && urlGoal) {
        if (urlGoal === p.goal || (urlGoal === 'BA' && isJob(p.goal))) {
          redirected = true;
          if (root.location) {
            if (typeof root.location.replace === 'function') root.location.replace('/learning/comm/path');
            else root.location.href = '/learning/comm/path';
          }
          return;
        }
        pathChecked = true;
        render();
        return;
      }
      state.secondaryPath = true;
      state.presetGoal = false;
      state.mainGoal = p.goal;
      state.mainNameVi = p.nameVi || p.goal;
      state.currentSecondary = p.secondary && p.secondary.goal ? p.secondary.goal : null;
      if (p.selfLevel === 'BEGINNER' || p.selfLevel === 'ELEMENTARY' || p.selfLevel === 'INTERMEDIATE') {
        state.selfLevel = p.selfLevel;
      }
      if (p.display && typeof p.display.fullRomaji === 'boolean') {
        state.knowsKana = !p.display.fullRomaji;
      }
      state.step = 1;
      state.errorMsg = '';
      if (goalsData.length) syncSecondaryGoal();
      pathChecked = true;
      render();
    }).catch(function () { /* no main goal yet: this survey makes it */ }).then(function () {
      if (pathChecked || redirected) return;
      pathChecked = true;
      render();
    });
  }

  return {
    surveyHtml: surveyHtml,
    buildPayload: buildPayload,
    getAvailableTopics: getAvailableTopics,
    steps: steps,
    displayGoals: displayGoals,
    mount: mount
  };
});
