/**
 * Onyomi quiz runner: one question at a time with immediate feedback, graded on the server.
 * A panel on the right of the question (below it on narrow screens) lists every question by number
 * and holds the back / forward buttons, so the learner can move freely (the arrow keys work too) and
 * leave a question for later; its "Xem kết quả" button appears once every question is answered, and
 * "Làm lại" drops the attempt (ungraded, through /attempts/{id}/abandon) to start a new one.
 *
 * Mounts on every [data-onyomi-quiz] element:
 *   data-start-url   POST endpoint that opens (or resumes) an attempt
 *   data-kind        practice | final; sent with the onyomi:quiz-passed event
 *   data-title, data-intro, data-start-label   texts of the intro card
 *   data-next-url, data-next-label             optional link offered once the quiz is passed
 *
 * Each answer is checked (and recorded) through /api/learning/quizzes/attempts/{id}/answers, so it
 * cannot be changed afterwards: going back to an answered question shows its answer and explanation.
 */
(function () {
  'use strict';

  var PASS_PERCENT = 60;

  function escapeHtml(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#039;');
  }

  async function readBody(response) {
    try { return await response.json(); } catch (error) { return {}; }
  }

  function mount(root) {
    var config = {
      startUrl: root.dataset.startUrl,
      kind: root.dataset.kind || 'practice',
      title: root.dataset.title || 'Luyện tập',
      intro: root.dataset.intro || '',
      startLabel: root.dataset.startLabel || 'Bắt đầu',
      nextUrl: root.dataset.nextUrl || '',
      nextLabel: root.dataset.nextLabel || 'Tiếp tục'
    };
    var state = { attemptId: null, questions: [], index: 0, feedback: {}, busy: false, cardHeight: 0, confirmRestart: false };

    // `running` widens the quiz to make room for the question list next to the question
    function paint(html, running) {
      root.classList.toggle('is-running', !!running);
      root.innerHTML = html;
    }

    function panel(className, inner) {
      return '<div class="oy-panel ' + className + '">' + inner + '</div>';
    }

    function paintIntro() {
      paint(panel('oy-quiz__intro',
        '<h3>' + escapeHtml(config.title) + '</h3>' +
        (config.intro ? '<p>' + escapeHtml(config.intro) + '</p>' : '') +
        '<button class="oy-button oy-button--primary" type="button" data-action="start">' +
        '<i class="fa-solid fa-play" aria-hidden="true"></i><span>' + escapeHtml(config.startLabel) + '</span></button>'));
    }

    function paintState(message, withRetry) {
      paint(panel('oy-quiz__state',
        (withRetry ? '' : '<span class="oy-spinner" aria-hidden="true"></span> ') +
        '<span role="status">' + escapeHtml(message) + '</span>' +
        (withRetry ? '<div class="oy-quiz__actions" style="margin-top:16px">' +
          '<button class="oy-button" type="button" data-action="' + withRetry + '">Thử lại</button></div>' : '')));
    }

    function current() {
      return state.questions[state.index];
    }

    function firstUnanswered() {
      for (var i = 0; i < state.questions.length; i++) {
        if (!state.feedback[state.questions[i].questionId]) return i;
      }
      return state.questions.length;
    }

    function answeredCount() {
      return Object.keys(state.feedback).length;
    }

    function optionsHtml(question, feedback) {
      return '<div class="oy-quiz__options">' + (question.options || []).map(function (option, i) {
        var number = i + 1;
        var className = 'oy-quiz__option';
        if (feedback) {
          if (feedback.correctOption === number) className += ' is-correct';
          else if (feedback.selectedOption === number) className += ' is-wrong';
        }
        return '<button class="' + className + '" type="button" data-action="choose" data-option="' + number + '"' +
          (feedback ? ' disabled' : '') + '>' + escapeHtml(option) + '</button>';
      }).join('') + '</div>';
    }

    function typingHtml(question, feedback) {
      var mark = feedback ? (feedback.correct ? ' is-correct' : ' is-wrong') : '';
      return '<div class="oy-quiz__typing">' +
        '<input class="oy-quiz__input' + mark + '" type="text" autocomplete="off" autocapitalize="off" spellcheck="false"' +
        ' aria-label="Câu trả lời" placeholder="コウ / こう / kou"' +
        (feedback ? ' disabled value="' + escapeHtml(feedback.answerText) + '"' : '') + ' />' +
        (feedback ? '' : '<button class="oy-button oy-button--primary" type="button" data-action="check-text">Kiểm tra</button>') +
        '</div><p class="oy-quiz__typing-hint">Gõ Katakana, Hiragana hoặc romaji đều được.</p>';
    }

    function feedbackHtml(feedback) {
      return '<div class="oy-quiz__feedback ' + (feedback.correct ? 'is-correct' : 'is-wrong') + '" role="status">' +
        '<h4>' + (feedback.correct ? 'Chính xác!' : 'Chưa đúng') + '</h4>' +
        (!feedback.correct && feedback.correctAnswerText
          ? '<p><strong>Đáp án:</strong> ' + escapeHtml(feedback.correctAnswerText) + '</p>' : '') +
        (feedback.explanation ? '<p>' + escapeHtml(feedback.explanation) + '</p>' : '') + '</div>';
    }

    // holds the place of the explanation until the question is answered
    function hintHtml() {
      return '<div class="oy-quiz__hint"><i class="fa-regular fa-lightbulb" aria-hidden="true"></i>' +
        '<span>Trả lời để xem đáp án và giải thích.</span></div>';
    }

    // the question list: one number per question, back / forward, then what is left or the results button
    function sideHtml() {
      var total = state.questions.length;
      var left = total - answeredCount();
      var steps = state.questions.map(function (question, i) {
        var feedback = state.feedback[question.questionId];
        var className = 'oy-quiz__step' + (feedback ? (feedback.correct ? ' is-correct' : ' is-wrong') : '');
        var status = feedback ? (feedback.correct ? 'đã trả lời đúng' : 'đã trả lời sai') : 'chưa làm';
        return '<button class="' + className + '" type="button" data-action="go" data-index="' + i + '"' +
          (i === state.index ? ' aria-current="step"' : '') +
          ' aria-label="Câu ' + (i + 1) + ', ' + status + '">' + (i + 1) + '</button>';
      }).join('');
      return '<aside class="oy-panel oy-quiz__side" aria-label="Danh sách câu hỏi">' +
        '<p class="oy-quiz__side-title">Câu hỏi</p>' +
        '<nav class="oy-quiz__steps" aria-label="Chọn câu hỏi">' + steps + '</nav>' +
        '<div class="oy-quiz__nav">' +
        '<button class="oy-button" type="button" data-action="prev"' + (state.index === 0 ? ' disabled' : '') + '>' +
        '<i class="fa-solid fa-chevron-left" aria-hidden="true"></i><span>Câu trước</span></button>' +
        '<button class="oy-button" type="button" data-action="forward"' + (state.index === total - 1 ? ' disabled' : '') + '>' +
        '<span>Câu sau</span><i class="fa-solid fa-chevron-right" aria-hidden="true"></i></button>' +
        restartHtml(total - left) + '</div>' +
        (left
          ? '<p class="oy-quiz__left">Còn ' + left + ' câu chưa làm</p>'
          : '<button class="oy-button oy-button--primary oy-quiz__finish" type="button" data-action="submit">' +
            '<span>Xem kết quả</span></button>') +
        '</aside>';
    }

    // "Làm lại" drops this attempt for a new one; once something is answered it asks first, in place
    function restartHtml(done) {
      if (!state.confirmRestart) {
        return '<button class="oy-button oy-quiz__restart" type="button" data-action="restart">' +
          '<i class="fa-solid fa-rotate-right" aria-hidden="true"></i><span>Làm lại</span></button>';
      }
      return '<div class="oy-quiz__confirm" role="group" aria-labelledby="oy-restart-' + config.kind + '">' +
        '<p id="oy-restart-' + config.kind + '">Bỏ ' + done + ' câu đã làm và bắt đầu lượt mới với bộ câu khác?</p>' +
        '<button class="oy-button oy-button--primary" type="button" data-action="restart-confirm">Làm lại</button>' +
        '<button class="oy-button" type="button" data-action="restart-cancel">Huỷ</button></div>';
    }

    /**
     * Paints the current question. `focusSelector` keeps the focus on the control that moved here (the
     * back/forward button or the question number), so the keyboard can keep stepping through the quiz.
     * Right after an answer the focus goes to the results button once nothing is left, else to "Câu sau".
     */
    function paintQuestion(focusSelector) {
      var question = current();
      var feedback = state.feedback[question.questionId];
      var total = state.questions.length;
      var done = answeredCount();
      paint('<div class="oy-quiz__layout">' + panel('oy-quiz__card',
        '<div class="oy-quiz__top"><span>Câu ' + (state.index + 1) + ' / ' + total + '</span>' +
        '<div class="oy-progress" aria-hidden="true"><div class="oy-progress__fill" style="width:' +
        Math.round(done * 100 / total) + '%"></div></div></div>' +
        (question.focus ? '<div class="oy-quiz__focus" lang="ja">' + escapeHtml(question.focus) + '</div>' : '') +
        '<p class="oy-quiz__question">' + escapeHtml(question.questionText) + '</p>' +
        (question.questionType === 'FILL_BLANK' ? typingHtml(question, feedback) : optionsHtml(question, feedback)) +
        (feedback ? feedbackHtml(feedback) : hintHtml()) +
        '<p class="oy-quiz__alert" role="alert" hidden></p>') +
        sideHtml() + '</div>', true);
      holdCardHeight();
      var focusTarget = (focusSelector && root.querySelector(focusSelector)) ||
        (focusSelector && root.querySelector('[data-action="go"][aria-current]')) ||
        (feedback
          ? root.querySelector('[data-action="submit"]') ||
            root.querySelector('[data-action="forward"]:not(:disabled)') ||
            root.querySelector('[data-action="go"][aria-current]')
          : root.querySelector('.oy-quiz__input'));
      if (focusTarget) focusTarget.focus({ preventScroll: true });
    }

    function goTo(index, focusSelector) {
      if (state.busy || index < 0 || index >= state.questions.length) return;
      state.index = index;
      state.confirmRestart = false;
      paintQuestion(focusSelector);
    }

    // The card keeps the tallest size it has had in this attempt: an answer's explanation makes it taller,
    // and shrinking back on the next question would make the page below jump.
    function holdCardHeight() {
      var card = root.querySelector('.oy-quiz__card');
      if (!card) return;
      if (state.cardHeight) card.style.minHeight = state.cardHeight + 'px';
      state.cardHeight = Math.max(state.cardHeight, card.offsetHeight);
    }

    function showAlert(message) {
      var alert = root.querySelector('.oy-quiz__alert');
      if (!alert) return;
      alert.textContent = message;
      alert.hidden = false;
    }

    function setBusy(busy) {
      state.busy = busy;
      root.querySelectorAll('[data-action="choose"], [data-action="check-text"], .oy-quiz__input').forEach(function (el) {
        el.disabled = busy;
      });
    }

    async function start() {
      if (!window.JlasAuth || !window.JlasAuth.requireLogin()) return;
      paintState('Đang mở bài…');
      try {
        var response = await fetch(config.startUrl, { method: 'POST', headers: window.JlasAuth.authHeaders() });
        var body = await readBody(response);
        if (!response.ok || body.success === false || !body.data) {
          paintState(body.message || 'Không mở được bài. Vui lòng thử lại.', 'start');
          return;
        }
        state.attemptId = body.data.attemptId;
        state.questions = body.data.questions || [];
        state.feedback = {};
        state.cardHeight = 0;
        state.confirmRestart = false;
        (body.data.answered || []).forEach(function (feedback) {
          state.feedback[feedback.questionId] = feedback;
        });
        if (!state.questions.length) {
          paintState('Bài này chưa có câu hỏi.', 'start');
          return;
        }
        state.index = firstUnanswered();
        if (state.index >= state.questions.length) {
          submit();
          return;
        }
        paintQuestion();
      } catch (error) {
        paintState('Không thể kết nối tới máy chủ. Vui lòng thử lại.', 'start');
      }
    }

    async function sendAnswer(payload) {
      if (state.busy) return;
      setBusy(true);
      try {
        var response = await fetch('/api/learning/quizzes/attempts/' + state.attemptId + '/answers', {
          method: 'POST',
          headers: window.JlasAuth.authHeaders(),
          body: JSON.stringify(payload)
        });
        var body = await readBody(response);
        if (!response.ok || body.success === false || !body.data) {
          setBusy(false);
          showAlert(body.message || 'Không kiểm tra được câu trả lời. Vui lòng thử lại.');
          return;
        }
        state.busy = false;
        state.feedback[payload.questionId] = body.data;
        paintQuestion();
      } catch (error) {
        setBusy(false);
        showAlert('Không thể kết nối tới máy chủ. Vui lòng thử lại.');
      }
    }

    function choose(option) {
      var question = current();
      if (!question || state.feedback[question.questionId]) return;
      sendAnswer({ questionId: question.questionId, selectedOption: option });
    }

    function checkText() {
      var question = current();
      var input = root.querySelector('.oy-quiz__input');
      if (!question || !input || state.feedback[question.questionId]) return;
      var text = input.value.trim();
      if (!text) {
        showAlert('Hãy gõ câu trả lời trước khi kiểm tra.');
        input.focus();
        return;
      }
      sendAnswer({ questionId: question.questionId, answerText: text });
    }

    // drops the open attempt on the server, then starts again: a new attempt with a new set of questions
    async function restart() {
      state.confirmRestart = false;
      paintState('Đang mở lượt mới…');
      try {
        var response = await fetch('/api/learning/quizzes/attempts/' + state.attemptId + '/abandon', {
          method: 'POST',
          headers: window.JlasAuth.authHeaders()
        });
        var body = await readBody(response);
        if (!response.ok || body.success === false) {
          paintState(body.message || 'Không làm lại được. Vui lòng thử lại.', 'restart-confirm');
          return;
        }
      } catch (error) {
        paintState('Không thể kết nối tới máy chủ. Vui lòng thử lại.', 'restart-confirm');
        return;
      }
      start();
    }

    function askRestart(ask) {
      if (state.busy) return;
      if (ask && !answeredCount()) {
        restart();
        return;
      }
      state.confirmRestart = ask;
      paintQuestion(ask ? '[data-action="restart-cancel"]' : '[data-action="restart"]');
    }

    async function submit() {
      paintState('Đang chấm bài…');
      var answers = state.questions
        .map(function (question) {
          var feedback = state.feedback[question.questionId];
          return feedback ? {
            questionId: question.questionId,
            selectedOption: feedback.selectedOption,
            answerText: feedback.answerText
          } : null;
        })
        .filter(Boolean);
      try {
        var response = await fetch('/api/learning/quizzes/attempts/' + state.attemptId + '/submit', {
          method: 'POST',
          headers: window.JlasAuth.authHeaders(),
          body: JSON.stringify({ answers: answers })
        });
        var body = await readBody(response);
        if (!response.ok || body.success === false || !body.data) {
          paintState(body.message || 'Không nộp được bài. Vui lòng thử lại.', 'submit');
          return;
        }
        paintResult(body.data);
        if (body.data.passed) {
          root.dispatchEvent(new CustomEvent('onyomi:quiz-passed', {
            bubbles: true,
            detail: { kind: config.kind, score: Number(body.data.scorePercent) }
          }));
        }
        if (window.RewardFX) window.RewardFX.checkPending();
      } catch (error) {
        paintState('Không thể kết nối tới máy chủ. Vui lòng thử lại.', 'submit');
      }
    }

    function verdictText(passed) {
      if (!passed) {
        return 'Cần đạt ít nhất ' + PASS_PERCENT + '% để qua. Xem lại các câu sai bên dưới rồi làm lại nhé.';
      }
      if (config.kind === 'practice') return 'Bạn đã vượt qua bài luyện tập, bài học được ghi nhận hoàn thành.';
      if (config.kind === 'final') return 'Bạn đã vượt qua bài kiểm tra cuối giai đoạn!';
      return 'Làm tốt lắm!';
    }

    function paintResult(result) {
      var score = Number(result.scorePercent || 0);
      var wrong = state.questions.filter(function (question) {
        var feedback = state.feedback[question.questionId];
        return feedback && !feedback.correct;
      });
      var review = wrong.length
        ? '<ul class="oy-quiz__review">' + wrong.map(function (question) {
          var feedback = state.feedback[question.questionId];
          return '<li><strong>' + escapeHtml(question.questionText) + '</strong>' +
            'Đáp án: ' + escapeHtml(feedback.correctAnswerText) +
            (feedback.explanation ? '<br>' + escapeHtml(feedback.explanation) : '') + '</li>';
        }).join('') + '</ul>'
        : '';
      var nextLink = result.passed && config.nextUrl
        ? '<a class="oy-button oy-button--primary" href="' + escapeHtml(config.nextUrl) + '">' +
          escapeHtml(config.nextLabel) + '</a>'
        : '';
      paint(panel('oy-quiz__result',
        '<div class="oy-quiz__score">' + score.toFixed(0) + '<small>/100</small></div>' +
        '<p>Đúng ' + result.correctCount + '/' + result.totalCount + ' câu</p>' +
        '<p class="oy-quiz__verdict ' + (result.passed ? 'is-passed' : 'is-failed') + '">' +
        escapeHtml(verdictText(result.passed)) + '</p>' +
        '<div class="oy-quiz__actions">' + nextLink +
        '<button class="oy-button" type="button" data-action="start">' +
        '<i class="fa-solid fa-rotate-right" aria-hidden="true"></i><span>Làm lượt mới</span></button></div>' +
        review));
    }

    root.addEventListener('click', function (event) {
      var action = event.target.closest('[data-action]');
      if (!action || !root.contains(action) || action.disabled) return;
      switch (action.dataset.action) {
        case 'start': start(); break;
        case 'choose': choose(Number(action.dataset.option)); break;
        case 'check-text': checkText(); break;
        case 'prev': goTo(state.index - 1, '[data-action="prev"]:not(:disabled)'); break;
        case 'forward': goTo(state.index + 1, '[data-action="forward"]:not(:disabled)'); break;
        case 'go':
          goTo(Number(action.dataset.index), '[data-action="go"][data-index="' + action.dataset.index + '"]');
          break;
        case 'submit': submit(); break;
        case 'restart': askRestart(true); break;
        case 'restart-cancel': askRestart(false); break;
        case 'restart-confirm': if (!state.busy) restart(); break;
        default: break;
      }
    });

    root.addEventListener('keydown', function (event) {
      if (event.key === 'Enter' && event.target.classList.contains('oy-quiz__input')) {
        event.preventDefault();
        checkText();
        return;
      }
      if (event.key === 'Escape' && state.confirmRestart && root.querySelector('.oy-quiz__confirm')) {
        event.preventDefault();
        askRestart(false);
        return;
      }
      // ← / → step between questions, except while typing an answer
      if ((event.key === 'ArrowLeft' || event.key === 'ArrowRight') && !event.altKey && !event.ctrlKey &&
          !event.metaKey && !event.shiftKey && !event.target.matches('input, textarea') &&
          root.querySelector('.oy-quiz__card')) {
        event.preventDefault();
        if (event.key === 'ArrowLeft') goTo(state.index - 1, '[data-action="prev"]:not(:disabled)');
        else goTo(state.index + 1, '[data-action="forward"]:not(:disabled)');
      }
    });

    paintIntro();
  }

  function mountAll() {
    document.querySelectorAll('[data-onyomi-quiz]').forEach(mount);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', mountAll, { once: true });
  } else {
    mountAll();
  }
})();
