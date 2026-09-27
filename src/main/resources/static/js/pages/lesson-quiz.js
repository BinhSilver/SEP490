(function (root, factory) {
  'use strict';

  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.LessonQuizPage = api;

  if (root && root.document) {
    if (root.document.readyState === 'loading') {
      root.document.addEventListener('DOMContentLoaded', api.mount, { once: true });
    } else {
      api.mount();
    }
  }
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';

  var LABELS = ['A', 'B', 'C', 'D'];
  // [JLAS-W0108] Số ký tự mỗi màn hình nối cặp — phải khớp KANA_BATCH_SIZE ở backend.
  var MATCHING_BATCH_SIZE = 5;

  var state = {
    quiz: null,
    isKana: false,
    screens: [],
    current: 0,
    answers: {},        // questionId (string) -> selectedOption (1-based)
    selectedKana: null  // kana questionId đang được chọn để ghép
  };

  function escapeHtml(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#039;');
  }

  function positiveInteger(value) {
    var result = Number(value);
    return Number.isInteger(result) && result > 0 ? result : null;
  }

  // [JLAS-W0108] Câu Gemini sinh trong gate quiz dùng id ÂM (để không đụng id ngân hàng).
  // positiveInteger loại bỏ id âm -> nộp bài hỏng. Hàm này giữ cả số âm, chỉ loại không-phải-số.
  function signedInteger(value) {
    var result = Number(value);
    return Number.isInteger(result) ? result : null;
  }

  function icon(name) {
    return '<span class="material-symbols-outlined" aria-hidden="true">' + name + '</span>';
  }

  function skillBadge(skill, level) {
    var skillName = escapeHtml(skill || '');
    var levelName = escapeHtml(level || '');
    if (!skillName && !levelName) return '';
    return '<span class="lq-badge">' + (skillName ? skillName : '') + (levelName ? ' · ' + levelName : '') + '</span>';
  }

  // [JLAS-W0108] Hiển thị đúng nội dung lựa chọn trong review (romaji/ký tự), thay vì nhãn A-D
  // không đủ nghĩa khi câu nối cặp có nhiều hơn 4 lựa chọn.
  function optionLabel(questionId, optionIndex) {
    var question = (state.quiz && state.quiz.questions || []).find(function (q) {
      return String(q.questionId) === String(questionId);
    });
    var text = question && question.options && question.options[optionIndex - 1];
    return text != null ? escapeHtml(text) : (LABELS[optionIndex - 1] || String(optionIndex));
  }

  function renderLoading() {
    return '<section class="lq-state lq-state--loading" role="status">' +
      '<span class="lq-spinner" aria-hidden="true"></span>' +
      '<h1>Đang tải bài kiểm tra</h1><p>JLAS đang chuẩn bị câu hỏi cho bạn.</p></section>';
  }

  function renderError(message) {
    return '<section class="lq-state lq-state--error" role="alert">' +
      '<span class="lq-state__icon">!</span><p class="lq-eyebrow">Chưa tải được bài kiểm tra</p>' +
      '<h1>Hãy thử lại sau một chút</h1><p>' + escapeHtml(message) + '</p>' +
      '<button class="lq-button lq-button--primary" type="button" data-action="retry">' +
        icon('refresh') + '<span>Thử lại</span></button></section>';
  }

  // ===== Quiz thường (lesson thường + gate quiz): hiện tất cả câu một lần (MCQ radio). =====

  function renderQuestion(question, index) {
    var audio = question.audioUrl
      ? '<audio class="lq-audio" controls preload="metadata" src="' + escapeHtml(question.audioUrl) + '"></audio>'
      : '';
    var passage = question.passageHtml
      ? '<div class="lq-passage">' + question.passageHtml + '</div>'
      : '';
    var options = (question.options || []).map(function (option, optionIndex) {
      return '<label class="lq-option">' +
        '<input type="radio" name="q' + question.questionId + '" value="' + (optionIndex + 1) + '">' +
        '<span class="lq-option__marker">' + LABELS[optionIndex] + '</span>' +
        '<span class="lq-option__text">' + escapeHtml(option) + '</span></label>';
    }).join('');

    return '<li class="lq-question" data-question-id="' + question.questionId + '">' +
      '<div class="lq-question__head">' +
        '<span class="lq-question__num">Câu ' + (index + 1) + '</span>' + skillBadge(question.skill, question.jlptLevel) +
      '</div>' +
      (question.title ? '<h3 class="lq-question__title">' + escapeHtml(question.title) + '</h3>' : '') +
      audio + passage +
      '<p class="lq-question__text">' + escapeHtml(question.questionText) + '</p>' +
      '<div class="lq-options" role="radiogroup" aria-label="Câu ' + (index + 1) + '">' + options + '</div></li>';
  }

  function renderQuiz(data) {
    var questions = data.questions || [];
    var title = escapeHtml(data.lessonTitle || 'Bài kiểm tra');
    var items = questions.map(renderQuestion).join('');
    return '<section class="lq-card">' +
      '<header class="lq-card__head">' +
        '<div><span class="lq-eyebrow">' + icon('quiz') + ' Kiểm tra kiến thức</span>' +
        '<h1>' + title + '</h1></div>' +
        '<span class="lq-count">' + questions.length + ' câu hỏi</span></header>' +
      '<p class="lq-hint">Trả lời tất cả câu hỏi. Đạt ' + 60 + '% để hoàn thành.</p>' +
      '<ol class="lq-questions">' + items + '</ol>' +
      '<button class="lq-button lq-button--primary lq-submit" type="button" data-action="submit">' +
        icon('send') + '<span>Nộp bài</span></button>' +
      (questions.length === 0 ? '<p class="lq-empty">Chưa có câu hỏi nào cho bài học này. Hãy quay lại sau khi học liệu được bổ sung.</p>' : '') +
      '</section>';
  }

  // ===== Quiz bảng chữ cái (Kana): từng màn một — nối cặp 2 cột (5 chữ/màn) rồi MCQ. =====

  function buildScreens(questions) {
    var screens = [];
    var batch = [];
    function flush() {
      if (batch.length) { screens.push({ type: 'MATCHING', items: batch }); batch = []; }
    }
    questions.forEach(function (q) {
      if (q.questionType === 'MATCHING') {
        batch.push(q);
        if (batch.length >= MATCHING_BATCH_SIZE) flush();
      } else {
        flush();
        screens.push({ type: 'MCQ', items: [q] });
      }
    });
    flush();
    return screens;
  }

  function renderMatchingScreen(items) {
    var romaji = (items[0] && items[0].options) || [];
    var kanaCol = items.map(function (q) {
      var key = String(q.questionId);
      var assigned = state.answers[key] != null ? romaji[state.answers[key] - 1] : null;
      var cls = 'lq-pair lq-pair--kana';
      if (String(state.selectedKana) === key) cls += ' is-selected';
      if (assigned != null) cls += ' is-assigned';
      return '<button type="button" class="' + cls + '" data-kana-id="' + key + '">' +
        '<span class="lq-pair__char">' + escapeHtml(q.questionText) + '</span>' +
        '<span class="lq-pair__tag">' + (assigned != null ? escapeHtml(assigned) : '') + '</span>' +
        '</button>';
    }).join('');
    var romajiCol = romaji.map(function (r, ri) {
      var idx = ri + 1;
      var used = items.some(function (q) { return state.answers[String(q.questionId)] === idx; });
      return '<button type="button" class="lq-pair lq-pair--romaji' + (used ? ' is-assigned' : '') +
        '" data-romaji-index="' + idx + '">' +
        '<span class="lq-pair__char">' + escapeHtml(r) + '</span></button>';
    }).join('');

    return '<div class="lq-step" data-step-type="matching">' +
      '<p class="lq-step__hint">Ghép mỗi mặt chữ với cách đọc đúng — bấm mặt chữ rồi bấm cách đọc tương ứng.</p>' +
      '<div class="lq-match">' +
        '<div class="lq-match__col"><h3 class="lq-match__title">Mặt chữ</h3>' + kanaCol + '</div>' +
        '<div class="lq-match__col"><h3 class="lq-match__title">Cách đọc (romaji)</h3>' + romajiCol + '</div>' +
      '</div></div>';
  }

  function renderMcqScreen(q) {
    var audio = q.audioUrl
      ? '<audio class="lq-audio" controls preload="metadata" src="' + escapeHtml(q.audioUrl) + '"></audio>'
      : '';
    var passage = q.passageHtml
      ? '<div class="lq-passage">' + q.passageHtml + '</div>'
      : '';
    var checked = state.answers[String(q.questionId)];
    var options = (q.options || []).map(function (opt, oi) {
      var val = oi + 1;
      return '<label class="lq-option">' +
        '<input type="radio" name="q' + q.questionId + '" value="' + val + '"' +
        (checked === val ? ' checked' : '') + '>' +
        '<span class="lq-option__marker">' + LABELS[oi] + '</span>' +
        '<span class="lq-option__text">' + escapeHtml(opt) + '</span></label>';
    }).join('');

    return '<div class="lq-step" data-step-type="mcq" data-question-id="' + q.questionId + '">' +
      '<div class="lq-question__head">' +
        '<span class="lq-question__num">Chọn 1 đáp án</span>' + skillBadge(q.skill, q.jlptLevel) +
      '</div>' +
      (q.title ? '<h3 class="lq-question__title">' + escapeHtml(q.title) + '</h3>' : '') +
      audio + passage +
      '<p class="lq-question__text">' + escapeHtml(q.questionText) + '</p>' +
      '<div class="lq-options" role="radiogroup">' + options + '</div></div>';
  }

  function renderStepper() {
    var index = state.current;
    var total = state.screens.length;
    var isLast = index === total - 1;
    var back = index === 0
      ? ''
      : '<button type="button" class="lq-button" data-action="prev">' + icon('arrow_back') + '<span>Quay lại</span></button>';
    var forward = isLast
      ? '<button type="button" class="lq-button lq-button--primary" data-action="submit">' + icon('send') + '<span>Nộp bài</span></button>'
      : '<button type="button" class="lq-button lq-button--primary" data-action="next">' + icon('arrow_forward') + '<span>Tiếp theo</span></button>';
    return '<div class="lq-stepper">' +
      '<div class="lq-stepper__progress">' + icon('quiz') + ' Bước ' + (index + 1) + ' / ' + total + '</div>' +
      '<div class="lq-stepper__actions">' + back + forward + '</div></div>';
  }

  function renderKanaQuiz() {
    var title = escapeHtml(state.quiz.lessonTitle || 'Bài kiểm tra');
    var screen = state.screens[state.current];
    var body = screen.type === 'MATCHING' ? renderMatchingScreen(screen.items) : renderMcqScreen(screen.items[0]);
    return '<section class="lq-card">' +
      '<header class="lq-card__head">' +
        '<div><span class="lq-eyebrow">' + icon('quiz') + ' Kiểm tra bảng chữ cái</span>' +
        '<h1>' + title + '</h1></div>' +
        '<span class="lq-count">' + state.screens.length + ' bước</span></header>' +
      body + renderStepper() + '</section>';
  }

  // ===== Review sau khi nộp bài. =====

  function renderReview(review, index) {
    var selected = review.selectedOption == null
      ? 'Bỏ qua'
      : 'Đã chọn ' + optionLabel(review.questionId, review.selectedOption);
    var correct = review.correctOption == null ? '—' : optionLabel(review.questionId, review.correctOption);
    return '<li class="lq-review' + (review.correct ? ' is-correct' : ' is-wrong') + '">' +
      '<div class="lq-review__head">' +
        '<span class="lq-review__num">Câu ' + (index + 1) + '</span>' +
        '<span class="lq-review__verdict">' + (review.correct ? icon('check_circle') + ' Đúng' : icon('cancel') + ' Chưa đúng') + '</span>' +
      '</div>' +
      '<div class="lq-review__meta"><span>' + selected + '</span><span>Đáp án đúng: <strong>' + correct + '</strong></span></div>' +
      (review.explanation ? '<p class="lq-review__explain">' + escapeHtml(review.explanation) + '</p>' : '') +
      '</li>';
  }

  function renderResult(result, cfg) {
    var passed = result.passed === true;
    var relearn = result.relearnRequired === true;
    var isGate = cfg.mode === 'gate';
    var score = result.scorePercent;
    var review = (result.review || []).map(renderReview).join('');

    var note;
    var action;
    if (passed) {
      note = isGate
        ? 'Giai đoạn theo mục tiêu đã được mở khóa. Hãy tiếp tục hành trình của bạn!'
        : 'Bài học đã được ghi nhận hoàn thành.';
      action = isGate
        ? '<a class="lq-button lq-button--primary" href="/learning/path">' + icon('arrow_forward') + '<span>Tiếp tục đến giai đoạn mục tiêu</span></a>'
        : '<a class="lq-button lq-button--primary" href="/learning/path">' + icon('arrow_forward') + '<span>Quay lại lộ trình</span></a>';
    } else if (isGate && relearn) {
      note = 'Bạn đã làm chưa đạt 2 lần. Hãy ôn lại phần khởi động trước khi thử lại nhé.';
      action = '<a class="lq-button lq-button--primary" href="/learning/path">' + icon('menu_book') + '<span>Ôn lại phần khởi động</span></a>';
    } else {
      note = 'Ôn lại nội dung bài học rồi thử lại để đạt điểm cao hơn.';
      action = '<button class="lq-button lq-button--primary" type="button" data-action="retry">' + icon('refresh') + '<span>Làm lại</span></button>';
    }

    // [JLAS-W0122] Điểm rất cao -> gợi ý tăng tốc lộ trình; điểm thấp -> gợi ý ôn tập điểm yếu.
    var suggestion = '';
    if (!isGate && passed && score > 90) {
      suggestion = '<a class="lq-button lq-button--ghost" href="/learning/path?replan=ACCELERATE">' + icon('auto_fix_high') + '<span>Tăng tốc lộ trình</span></a>';
    } else if (!isGate && !passed && score < 60) {
      suggestion = '<a class="lq-button lq-button--ghost" href="/learning/path?replan=REMEDIATE">' + icon('auto_fix_high') + '<span>Ôn tập điểm yếu</span></a>';
    }

    return '<section class="lq-card lq-result' + (passed ? ' is-passed' : ' is-failed') + '">' +
      '<div class="lq-result__score"><span class="lq-result__ring">' + score + '%</span>' +
      '<h1>' + (passed ? 'Chúc mừng, bạn đã vượt qua!' : (isGate && relearn ? 'Hãy ôn lại một chút nhé' : 'Chưa đạt, hãy thử lại nhé.')) + '</h1>' +
      '<p>' + result.correctAnswers + '/' + result.totalQuestions + ' câu đúng · ngưỡng đạt ' + result.passThresholdPercent + '%</p></div>' +
      '<p class="lq-result__note">' + note + '</p>' +
      '<div class="lq-result__actions">' + action + suggestion + '</div>' +
      (review ? '<div class="lq-review-block"><h2>Chi tiết từng câu</h2><ul class="lq-reviews">' + review + '</ul></div>' : '') +
      '</section>';
  }

  function mount() {
    var root = document.getElementById('lessonQuizRoot');
    if (!root) return;
    if (!window.JlasAuth || !window.JlasAuth.requireLogin(window.location.pathname)) return;

    var cfg = {
      mode: root.getAttribute('data-quiz-mode') === 'gate' ? 'gate' : 'lesson',
      lessonId: positiveInteger(Number(root.getAttribute('data-lesson-id')))
    };

    function quizUrl() {
      return cfg.mode === 'gate'
        ? '/api/learning/paths/gate-quiz'
        : '/api/learning/lessons/' + encodeURIComponent(cfg.lessonId) + '/quiz';
    }
    function submitUrl() {
      return cfg.mode === 'gate'
        ? '/api/learning/paths/gate-quiz/submit'
        : '/api/learning/lessons/' + encodeURIComponent(cfg.lessonId) + '/quiz/submit';
    }
    function errorMessage(code, fallback) {
      var messages = {
        FORBIDDEN: 'Bạn không có quyền truy cập bài kiểm tra này.',
        NOT_FOUND: 'Không tìm thấy bài kiểm tra cho nội dung này.',
        UNAUTHORIZED: 'Phiên đăng nhập đã hết hạn.'
      };
      return messages[code] || fallback || 'Không thể tải bài kiểm tra. Vui lòng thử lại.';
    }

    async function readBody(response) {
      try { return await response.json(); } catch (error) { return {}; }
    }

    async function load() {
      root.setAttribute('aria-busy', 'true');
      root.innerHTML = renderLoading();
      try {
        var response = await fetch(quizUrl(), { headers: window.JlasAuth.authHeaders() });
        var body = await readBody(response);
        if (!response.ok || body.success === false || !body.data) {
          root.innerHTML = renderError(errorMessage(body.code, body.message));
          root.setAttribute('aria-busy', 'false');
          return;
        }
        state.quiz = body.data;
        var questions = body.data.questions || [];
        state.isKana = questions.some(function (q) { return q.questionType === 'MATCHING'; });
        if (state.isKana) {
          state.screens = buildScreens(questions);
          state.current = 0;
          state.answers = {};
          state.selectedKana = null;
          renderCurrent();
        } else {
          root.innerHTML = renderQuiz(body.data);
          root.setAttribute('aria-busy', 'false');
        }
      } catch (error) {
        root.innerHTML = renderError('Không thể kết nối tới máy chủ. Vui lòng thử lại.');
        root.setAttribute('aria-busy', 'false');
      }
    }

    function collectAnswers() {
      var answers = [];
      root.querySelectorAll('.lq-question').forEach(function (question) {
        var questionId = signedInteger(Number(question.dataset.questionId));
        var radio = question.querySelector('input[type="radio"]:checked');
        answers.push({
          questionId: questionId,
          selectedOption: radio ? Number(radio.value) : null
        });
      });
      return answers;
    }

    function collectKanaAnswers() {
      var answers = [];
      state.screens.forEach(function (screen) {
        screen.items.forEach(function (q) {
          var key = String(q.questionId);
          answers.push({
            questionId: signedInteger(Number(q.questionId)),
            selectedOption: state.answers[key] != null ? state.answers[key] : null
          });
        });
      });
      return answers;
    }

    function selectKana(kanaId) {
      state.selectedKana = String(state.selectedKana) === String(kanaId) ? null : kanaId;
      renderCurrent();
    }

    function assignRomaji(romajiIndex) {
      if (state.selectedKana == null) return;
      var items = state.screens[state.current].items;
      items.forEach(function (q) {
        if (state.answers[String(q.questionId)] === romajiIndex) delete state.answers[String(q.questionId)];
      });
      state.answers[String(state.selectedKana)] = romajiIndex;
      state.selectedKana = null;
      renderCurrent();
    }

    function prevScreen() { if (state.current > 0) { state.current--; renderCurrent(); } }
    function nextScreen() { if (state.current < state.screens.length - 1) { state.current++; renderCurrent(); } }
    function renderCurrent() {
      root.innerHTML = renderKanaQuiz();
      root.setAttribute('aria-busy', 'false');
    }

    async function submit() {
      var answers = state.isKana ? collectKanaAnswers() : collectAnswers();
      var unanswered = answers.filter(function (answer) { return answer.selectedOption == null; }).length;
      if (unanswered > 0 && !window.confirm('Còn ' + unanswered + ' câu chưa trả lời. Nộp bài luôn?')) return;
      root.setAttribute('aria-busy', 'true');
      try {
        var response = await fetch(submitUrl(), {
          method: 'POST',
          headers: Object.assign({ 'Content-Type': 'application/json' }, window.JlasAuth.authHeaders()),
          body: JSON.stringify({ attemptId: state.quiz.attemptId, answers: answers })
        });
        var body = await readBody(response);
        if (!response.ok || body.success === false || !body.data) {
          root.setAttribute('aria-busy', 'false');
          if (window.JlasUI && typeof window.JlasUI.toast === 'function') {
            window.JlasUI.toast(body.message || 'Không thể chấm bài.', 'error');
          }
          return;
        }
        root.innerHTML = renderResult(body.data, cfg);
        root.setAttribute('aria-busy', 'false');
      } catch (error) {
        root.setAttribute('aria-busy', 'false');
        if (window.JlasUI && typeof window.JlasUI.toast === 'function') {
          window.JlasUI.toast('Không thể kết nối tới máy chủ. Vui lòng thử lại.', 'error');
        }
      }
    }

    root.addEventListener('click', function (event) {
      var kana = event.target.closest('[data-kana-id]');
      if (kana) { selectKana(kana.dataset.kanaId); return; }
      var romaji = event.target.closest('[data-romaji-index]');
      if (romaji) { assignRomaji(Number(romaji.dataset.romajiIndex)); return; }
      var action = event.target.closest('[data-action]');
      if (!action) return;
      if (action.dataset.action === 'retry') load();
      if (action.dataset.action === 'prev') prevScreen();
      if (action.dataset.action === 'next') nextScreen();
      if (action.dataset.action === 'submit') submit();
    });

    root.addEventListener('change', function (event) {
      if (event.target.matches('input[type="radio"]')) {
        var step = event.target.closest('[data-question-id]');
        if (step) state.answers[step.dataset.questionId] = Number(event.target.value);
      }
    });

    load();
  }

  return { mount: mount };
});
