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

  function escapeHtml(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#039;');
  }

  function icon(name) {
    return '<span class="material-symbols-outlined" aria-hidden="true">' + name + '</span>';
  }

  function questionTypeLabel(type) {
    if (type === 'FILL_BLANK') return 'Điền vào chỗ trống';
    if (type === 'MATCHING') return 'Nối cặp';
    return 'Chọn đáp án đúng';
  }

  function renderLoading() {
    return '<div class="lq-state" role="status">' +
      '<span class="lq-spinner" aria-hidden="true"></span>' +
      '<p>Đang tải bài quiz…</p></div>';
  }

  function renderError(message) {
    return '<div class="lq-state lq-state--error" role="alert">' +
      '<span class="lq-state__icon">!</span>' +
      '<h2>Không tải được bài quiz</h2>' +
      '<p>' + escapeHtml(message || 'Vui lòng thử lại sau một chút.') + '</p>' +
      '<button class="lq-button lq-button--primary" type="button" data-action="retry">' +
      icon('refresh') + '<span>Thử lại</span></button></div>';
  }

  function renderQuestion(question, index) {
    question = question || {};
    var passage = question.passageHtml
      ? '<div class="lq-passage">' + escapeHtml(question.passageHtml) + '</div>' : '';

    var answer;
    if (question.questionType === 'FILL_BLANK') {
      answer = '<input class="lq-input" type="text" name="q-' + question.questionId + '"' +
        ' autocomplete="off" aria-label="Câu trả lời" />';
    } else {
      answer = (question.options || []).map(function (option, optionIndex) {
        return '<label class="lq-option">' +
          '<input type="radio" name="q-' + question.questionId + '" value="' + (optionIndex + 1) + '" />' +
          '<span class="lq-option__marker"></span>' +
          '<span class="lq-option__text">' + escapeHtml(option) + '</span></label>';
      }).join('');
    }

    return '<fieldset class="lq-question" data-question-id="' + question.questionId + '"' +
      ' data-question-type="' + escapeHtml(question.questionType) + '">' +
      '<legend class="lq-question__head">' +
      '<span class="lq-question__index">' + index + '</span>' +
      '<span class="lq-question__type">' + escapeHtml(questionTypeLabel(question.questionType)) + '</span>' +
      '</legend>' +
      passage +
      '<p class="lq-question__text">' + escapeHtml(question.questionText) + '</p>' +
      '<div class="lq-options">' + answer + '</div></fieldset>';
  }

  function renderQuiz(start) {
    var questions = start.questions || [];
    if (!questions.length) {
      return '<div class="lq-state" role="status"><span class="lq-state__icon">quiz</span>' +
        '<h2>Bài quiz chưa có câu hỏi</h2><p>Hãy quay lại sau khi câu hỏi được thêm.</p></div>';
    }
    return '<section class="lq-quiz" data-attempt-id="' + start.attemptId + '">' +
      '<header class="lq-quiz__head">' +
      '<h2 class="lq-quiz__title">' + escapeHtml(start.quizTitle) + '</h2>' +
      '<p class="lq-quiz__meta">' + questions.length + ' câu hỏi</p>' +
      '</header>' +
      '<div class="lq-alert" role="alert" hidden></div>' +
      questions.map(renderQuestion).join('') +
      '<div class="lq-quiz__actions">' +
      '<button class="lq-button lq-button--primary" type="button" data-action="submit">' +
      icon('send') + '<span>Nộp bài</span></button>' +
      '</div></section>';
  }

  function renderAnswer(answer) {
    answer = answer || {};
    var userAnswer = '';
    if (answer.questionType === 'FILL_BLANK') {
      userAnswer = answer.answerText;
    } else if (answer.selectedOption != null && (answer.options || []).length) {
      userAnswer = answer.options[answer.selectedOption - 1];
    }

    var badge = answer.isCorrect === true
      ? '<span class="lq-badge lq-badge--correct">' + icon('check_circle') + '<span>Đúng</span></span>'
      : '<span class="lq-badge lq-badge--wrong">' + icon('cancel') + '<span>Sai</span></span>';

    var userLine = userAnswer
      ? '<p class="lq-answer__row"><span>Bạn chọn</span><strong>' + escapeHtml(userAnswer) + '</strong></p>'
      : '<p class="lq-answer__row"><span>Bạn chọn</span><strong class="lq-answer__empty">(chưa trả lời)</strong></p>';
    var correctLine = answer.correctAnswerText
      ? '<p class="lq-answer__row"><span>Đáp án đúng</span><strong>' + escapeHtml(answer.correctAnswerText) + '</strong></p>'
      : '';
    var explanation = answer.explanation
      ? '<p class="lq-answer__explanation">' + escapeHtml(answer.explanation) + '</p>' : '';

    return '<article class="lq-answer ' + (answer.isCorrect === true ? 'is-correct' : 'is-wrong') + '">' +
      '<header class="lq-answer__head">' + badge +
      '<h3 class="lq-answer__text">' + escapeHtml(answer.questionText) + '</h3></header>' +
      '<div class="lq-answer__body">' + userLine + correctLine + explanation + '</div></article>';
  }

  function renderResult(result, lessonId) {
    var score = result.scorePercent == null ? 0 : Number(result.scorePercent);
    var answers = (result.answers || []).map(renderAnswer).join('');
    return '<section class="lq-result">' +
      '<header class="lq-result__head">' +
      '<span class="lq-result__score">' + score.toFixed(0) + '<small>/100</small></span>' +
      '<div class="lq-result__summary"><h2>' + escapeHtml(result.quizTitle) + '</h2>' +
      '<p>Bạn trả lời đúng <strong>' + result.correctCount + '/' + result.totalCount + '</strong> câu.</p></div>' +
      '</header>' +
      '<div class="lq-result__list">' + answers + '</div>' +
      '<div class="lq-result__actions">' +
      '<a class="lq-button" href="/learning/lesson/' + encodeURIComponent(lessonId) + '">' +
      icon('arrow_back') + '<span>Về bài học</span></a>' +
      '<a class="lq-button lq-button--primary" href="/learning/path">' +
      icon('flag') + '<span>Về lộ trình</span></a>' +
      '</div></section>';
  }

  function collectAnswers(questions) {
    return (questions || []).map(function (question) {
      var answer = { questionId: question.questionId };
      if (question.questionType === 'FILL_BLANK') {
        var input = document.querySelector('input[name="q-' + question.questionId + '"]');
        answer.answerText = input ? input.value : null;
      } else {
        var selected = document.querySelector('input[name="q-' + question.questionId + '"]:checked');
        answer.selectedOption = selected ? Number(selected.value) : null;
      }
      return answer;
    });
  }

  function submitErrorText(body) {
    if (body && body.code === 'VALIDATION_FAILED') {
      return 'Vui lòng trả lời đầy đủ các câu hỏi trước khi nộp bài.';
    }
    if (body && body.code === 'CONFLICT') {
      return 'Bài quiz này đã được nộp rồi.';
    }
    return (body && body.message) || 'Không thể nộp bài. Vui lòng thử lại.';
  }

  function mount() {
    var root = document.getElementById('lessonQuizRoot');
    if (!root || !window.JlasAuth || !window.JlasAuth.requireLogin()) return;

    var lessonId = Number(root.getAttribute('data-lesson-id'));
    if (!Number.isInteger(lessonId) || lessonId <= 0) return;

    var state = { questions: [], attemptId: null };

    function paint(html) { root.innerHTML = html; }

    async function readBody(response) {
      try { return await response.json(); } catch (error) { return {}; }
    }

    function showSubmitError(message) {
      var alert = root.querySelector('.lq-alert');
      if (alert) {
        alert.textContent = message;
        alert.hidden = false;
      }
    }

    function hideSubmitError() {
      var alert = root.querySelector('.lq-alert');
      if (alert) {
        alert.textContent = '';
        alert.hidden = true;
      }
    }

    async function startQuiz() {
      paint(renderLoading());
      try {
        var response = await fetch('/api/learning/quizzes/lessons/' + lessonId + '/start', {
          method: 'POST',
          headers: window.JlasAuth.authHeaders()
        });
        var body = await readBody(response);
        if (!response.ok || body.success === false || !body.data) {
          paint(renderError(body.message));
          return;
        }
        state.attemptId = body.data.attemptId;
        state.questions = body.data.questions || [];
        paint(renderQuiz(body.data));
      } catch (error) {
        paint(renderError('Không thể kết nối tới máy chủ. Vui lòng thử lại.'));
      }
    }

    async function loadResult() {
      try {
        var response = await fetch('/api/learning/quizzes/attempts/' + state.attemptId + '/result', {
          headers: window.JlasAuth.authHeaders()
        });
        var body = await readBody(response);
        if (!response.ok || body.success === false || !body.data) {
          paint(renderError(body.message));
          return;
        }
        paint(renderResult(body.data, lessonId));
      } catch (error) {
        paint(renderError('Không thể tải kết quả. Vui lòng thử lại.'));
      }
    }

    async function submitQuiz() {
      var submitButton = root.querySelector('[data-action="submit"]');
      if (submitButton) {
        submitButton.disabled = true;
        submitButton.classList.add('is-loading');
      }
      hideSubmitError();
      try {
        var response = await fetch('/api/learning/quizzes/attempts/' + state.attemptId + '/submit', {
          method: 'POST',
          headers: window.JlasAuth.authHeaders(),
          body: JSON.stringify({ answers: collectAnswers(state.questions) })
        });
        var body = await readBody(response);
        if (!response.ok || body.success === false) {
          showSubmitError(submitErrorText(body));
          return;
        }
        await loadResult();
      } catch (error) {
        showSubmitError('Không thể kết nối tới máy chủ. Vui lòng thử lại.');
      } finally {
        if (submitButton) {
          submitButton.disabled = false;
          submitButton.classList.remove('is-loading');
        }
      }
    }

    root.addEventListener('click', function (event) {
      var action = event.target.closest('[data-action]');
      if (!action) return;
      if (action.dataset.action === 'retry') startQuiz();
      if (action.dataset.action === 'submit') submitQuiz();
    });

    startQuiz();
  }

  return {
    renderQuiz: renderQuiz,
    renderResult: renderResult,
    collectAnswers: collectAnswers,
    mount: mount
  };
});
