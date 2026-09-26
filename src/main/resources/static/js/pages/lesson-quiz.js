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

  function icon(name) {
    return '<span class="material-symbols-outlined" aria-hidden="true">' + name + '</span>';
  }

  function skillBadge(skill, level) {
    var skillName = escapeHtml(skill || '');
    var levelName = escapeHtml(level || '');
    if (!skillName && !levelName) return '';
    return '<span class="lq-badge">' + (skillName ? skillName : '') + (levelName ? ' · ' + levelName : '') + '</span>';
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

  function renderReview(review, index) {
    var selected = review.selectedOption == null
      ? 'Bỏ qua'
      : 'Đã chọn ' + LABELS[review.selectedOption - 1];
    var correct = review.correctOption == null ? '—' : LABELS[review.correctOption - 1];
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
        root.innerHTML = renderQuiz(body.data);
        root.setAttribute('aria-busy', 'false');
      } catch (error) {
        root.innerHTML = renderError('Không thể kết nối tới máy chủ. Vui lòng thử lại.');
        root.setAttribute('aria-busy', 'false');
      }
    }

    function collectAnswers() {
      var answers = [];
      root.querySelectorAll('.lq-question').forEach(function (question) {
        var questionId = positiveInteger(Number(question.dataset.questionId));
        var selected = question.querySelector('input[type="radio"]:checked');
        answers.push({
          questionId: questionId,
          selectedOption: selected ? Number(selected.value) : null
        });
      });
      return answers;
    }

    async function submit() {
      var answers = collectAnswers();
      var unanswered = answers.filter(function (answer) { return answer.selectedOption == null; }).length;
      if (unanswered > 0 && !window.confirm('Còn ' + unanswered + ' câu chưa trả lời. Nộp bài luôn?')) return;
      root.setAttribute('aria-busy', 'true');
      try {
        var response = await fetch(submitUrl(), {
          method: 'POST',
          headers: Object.assign({ 'Content-Type': 'application/json' }, window.JlasAuth.authHeaders()),
          body: JSON.stringify({ answers: answers })
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

    var state = { quiz: null };

    root.addEventListener('click', function (event) {
      var action = event.target.closest('[data-action]');
      if (!action) return;
      if (action.dataset.action === 'retry') load();
      if (action.dataset.action === 'submit') submit();
    });

    load();
  }

  return { mount: mount };
});
