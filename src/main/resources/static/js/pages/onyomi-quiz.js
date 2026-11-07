/**
 * Onyomi quiz runner: one question at a time with immediate feedback, graded on the server.
 * A panel on the right of the question (below it on narrow screens) lists every question by number
 * and holds the back / forward buttons, so the learner can move freely (the arrow keys work too) and
 * leave a question for later; its "Xem kết quả" button appears once every question is answered, and
 * "Làm lại" drops the attempt (ungraded, through /attempts/{id}/abandon) to start a new one.
 * On phones (see onyomi.css) that panel is a popup opened from a button beside the progress bar, and
 * "Xem kết quả" moves out of it, under the question.
 *
 * Mounts on every [data-onyomi-quiz] element:
 *   data-start-url   POST endpoint that opens (or resumes) an attempt
 *   data-kind        practice | final; sent with the onyomi:quiz-passed event
 *   data-title, data-intro, data-start-label   texts of the intro card
 *   data-next-url, data-next-label             optional link offered once the quiz is passed
 *   data-back-url, data-back-label             optional link on the result card (e.g. back to the stage)
 *   data-autostart                             open the attempt right away instead of showing the intro
 *   data-variant="exam"                        timed test page: rules screen, fullscreen, server clock,
 *                                              integrity monitor (leaving the page is logged and, past the
 *                                              limit, closes the attempt), lettered options, no "Làm lại".
 *                                              Uses [data-exam-time], [data-exam-leaves], [data-exam-warning]
 *                                              and [data-exam-exit] on the page around the quiz.
 *
 * Each answer is checked (and recorded) through /api/learning/quizzes/attempts/{id}/answers, so it
 * cannot be changed afterwards: going back to an answered question shows its answer and explanation.
 *
 * window.JlasOnyomiQuiz lets the demo panel (onyomi-demo-panel.js, mission.dev-tools only) drive the
 * open attempt: finish it right or wrong, start a new one, and move the clock or the leave count of a test.
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
      nextLabel: root.dataset.nextLabel || 'Tiếp tục',
      backUrl: root.dataset.backUrl || '',
      backLabel: root.dataset.backLabel || 'Quay lại',
      exam: root.dataset.variant === 'exam'
    };
    // Timed test only: the server clock, how often the learner left, and why it was submitted.
    var exam = { on: false, deadline: 0, timer: null, startedAt: 0, leaves: 0, limit: 3, lastLeave: 0,
                 fullscreen: false, reason: '', confirmSubmit: false, channel: null,
                 tab: Math.random().toString(36).slice(2), lastReport: {} };
    var state = { attemptId: null, questions: [], index: 0, feedback: {}, busy: false, cardHeight: 0, confirmRestart: false, popupOpen: false };

    // `running` widens the quiz to make room for the question list next to the question
    function paint(html, running) {
      root.classList.toggle('is-running', !!running);
      root.innerHTML = html;
    }

    function panel(className, inner) {
      return '<div class="oy-panel ' + className + '">' + inner + '</div>';
    }

    function paintIntro() {
      if (config.exam) {
        paint(panel('oy-quiz__intro oy-exam__rules',
          '<h2>Trước khi bắt đầu</h2>' +
          '<ul>' +
          '<li><i class="fa-regular fa-clock" aria-hidden="true"></i><span><strong>20 câu trong 10 phút.</strong> ' +
            'Đồng hồ tính trên máy chủ: tải lại trang hay đóng trang, giờ vẫn chạy.</span></li>' +
          '<li><i class="fa-solid fa-expand" aria-hidden="true"></i><span>Bài làm ở <strong>chế độ toàn màn hình</strong>. ' +
            'Rời tab, chuyển cửa sổ, thoát toàn màn hình hay tải lại trang đều tính là <strong>rời bài</strong>.</span></li>' +
          '<li><i class="fa-solid fa-triangle-exclamation" aria-hidden="true"></i><span><strong>Rời bài 3 lần hoặc hết giờ</strong>, ' +
            'bài tự nộp với các câu đã trả lời.</span></li>' +
          '<li><i class="fa-solid fa-ban" aria-hidden="true"></i><span>Sao chép, dán, chuột phải và phím mở công cụ nhà phát triển ' +
            'bị chặn và được ghi lại.</span></li>' +
          '</ul>' +
          '<button class="oy-button oy-button--primary" type="button" data-action="start">' +
          '<i class="fa-solid fa-play" aria-hidden="true"></i><span>Bắt đầu làm bài</span></button>'));
        return;
      }
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
        if (feedback && config.exam) {
          if (feedback.selectedOption === number) className += ' is-selected';
        } else if (feedback) {
          if (feedback.correctOption === number) className += ' is-correct';
          else if (feedback.selectedOption === number) className += ' is-wrong';
        }
        var locked = feedback && !config.exam;
        var letter = config.exam ? '<span class="oy-quiz__letter" aria-hidden="true">' + 'ABCD'.charAt(i) + '</span>' : '';
        return '<button class="' + className + '" type="button" data-action="choose" data-option="' + number + '"' +
          (locked ? ' disabled' : '') + (config.exam ? ' aria-pressed="' + Boolean(feedback && feedback.selectedOption === number) + '"' : '') +
          '>' + letter + '<span lang="ja">' + escapeHtml(option) + '</span></button>';
      }).join('') + '</div>';
    }

    function typingHtml(question, feedback) {
      if (config.exam) {
        return '<div class="oy-quiz__typing">' +
          '<input class="oy-quiz__input" type="text" autocomplete="off" autocapitalize="off" spellcheck="false"' +
          ' aria-label="Câu trả lời" placeholder="コウ / こう / kou" value="' + escapeHtml(feedback ? feedback.answerText : '') + '" />' +
          '<button class="oy-button oy-button--primary" type="button" data-action="check-text">' + (feedback ? 'Lưu lại' : 'Lưu') + '</button>' +
          '</div><p class="oy-quiz__typing-hint">Gõ Katakana, Hiragana hoặc romaji đều được.</p>';
      }
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
      if (config.exam) return examSideHtml();
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
      var finish = '<button class="oy-button oy-button--primary oy-quiz__finish" type="button" data-action="submit">' +
        '<span>Xem kết quả</span></button>';
      return '<div class="oy-quiz__backdrop" data-action="popup-close"></div>' +
        '<aside class="oy-panel oy-quiz__side oy-quiz__side--popup" aria-label="Danh sách câu hỏi">' +
        '<p class="oy-quiz__side-title">Câu hỏi</p>' +
        '<button class="oy-quiz__popup-close" type="button" data-action="popup-close" aria-label="Đóng">' +
        '<i class="fa-solid fa-xmark" aria-hidden="true"></i></button>' +
        '<nav class="oy-quiz__steps" aria-label="Chọn câu hỏi">' + steps + '</nav>' +
        '<div class="oy-quiz__nav">' +
        '<button class="oy-button" type="button" data-action="prev" aria-label="Câu trước"' + (state.index === 0 ? ' disabled' : '') + '>' +
        '<i class="fa-solid fa-chevron-left" aria-hidden="true"></i><span>Câu trước</span></button>' +
        '<button class="oy-button" type="button" data-action="forward" aria-label="Câu sau"' + (state.index === total - 1 ? ' disabled' : '') + '>' +
        '<span>Câu sau</span><i class="fa-solid fa-chevron-right" aria-hidden="true"></i></button>' +
        restartHtml(total - left) + '</div>' +
        (left ? '<p class="oy-quiz__left">Còn ' + left + ' câu chưa làm</p>' : finish) +
        '</aside>' +
        (left ? '' : finish.replace('oy-quiz__finish"', 'oy-quiz__finish oy-quiz__finish--page"'));
    }

    // Phones only: the button that opens it and the CSS that makes it a popup exist only below 640px.
    function syncPopup(animate) {
      var side = root.querySelector('.oy-quiz__side--popup');
      var backdrop = root.querySelector('.oy-quiz__backdrop');
      document.body.classList.toggle('oy-quiz-popup-open', state.popupOpen);
      if (!side || !backdrop) return;
      side.classList.toggle('is-open', state.popupOpen);
      side.classList.toggle('is-opening', state.popupOpen && !!animate);
      backdrop.classList.toggle('is-open', state.popupOpen);
      if (state.popupOpen) {
        side.setAttribute('role', 'dialog');
        side.setAttribute('aria-modal', 'true');
      } else {
        side.removeAttribute('role');
        side.removeAttribute('aria-modal');
      }
      var opener = root.querySelector('.oy-quiz__popup-open');
      if (opener) opener.setAttribute('aria-expanded', String(state.popupOpen));
    }

    function setPopup(open, focusSelector) {
      state.popupOpen = open;
      syncPopup(open);
      var target = focusSelector && root.querySelector(focusSelector);
      if (target) target.focus({ preventScroll: true });
    }

    // Exam: no "Làm lại" (it would reset the clock); "Nộp bài" any time, asking first while questions are left.
    function examSideHtml() {
      var total = state.questions.length;
      var left = total - answeredCount();
      var steps = state.questions.map(function (question, i) {
        var answered = Boolean(state.feedback[question.questionId]);
        return '<button class="oy-quiz__step' + (answered ? ' is-answered' : '') + '" type="button" data-action="go" data-index="' + i + '"' +
          (i === state.index ? ' aria-current="step"' : '') +
          ' aria-label="Câu ' + (i + 1) + ', ' + (answered ? 'đã làm' : 'chưa làm') + '">' + (i + 1) + '</button>';
      }).join('');
      var submitArea = exam.confirmSubmit
        ? '<div class="oy-quiz__confirm" role="group" aria-labelledby="oy-submit-early">' +
          '<p id="oy-submit-early">Còn ' + left + ' câu chưa làm sẽ tính là sai. Nộp bài và chấm điểm ngay?</p>' +
          '<button class="oy-button oy-button--primary" type="button" data-action="submit">Nộp bài</button>' +
          '<button class="oy-button" type="button" data-action="submit-cancel">Làm tiếp</button></div>'
        : '<button class="oy-button' + (left ? '' : ' oy-button--primary') + ' oy-quiz__finish" type="button" data-action="' +
          (left ? 'submit-ask' : 'submit') + '"><i class="fa-solid fa-paper-plane" aria-hidden="true"></i><span>Nộp bài</span></button>';
      return '<aside class="oy-panel oy-quiz__side" aria-label="Danh sách câu hỏi">' +
        '<div class="oy-exam__side-head"><p class="oy-quiz__side-title"><i class="fa-solid fa-table-cells-large" aria-hidden="true"></i> Câu hỏi</p>' +
        '<span class="oy-exam__chip">' + (left ? 'Còn ' + left + ' câu chưa làm' : 'Đã làm đủ ' + total + ' câu') + '</span></div>' +
        '<nav class="oy-quiz__steps" aria-label="Chọn câu hỏi">' + steps + '</nav>' +
        '<ul class="oy-exam__legend" aria-hidden="true">' +
        '<li><i class="oy-exam__swatch"></i>Chưa làm</li><li><i class="oy-exam__swatch is-answered"></i>Đã làm</li>' +
        '<li><i class="oy-exam__swatch is-current"></i>Đang chọn</li></ul>' +
        '<div class="oy-quiz__nav">' +
        '<button class="oy-button" type="button" data-action="prev"' + (state.index === 0 ? ' disabled' : '') + '>' +
        '<i class="fa-solid fa-chevron-left" aria-hidden="true"></i><span>Câu trước</span></button>' +
        '<button class="oy-button oy-button--primary" type="button" data-action="forward"' + (state.index === total - 1 ? ' disabled' : '') + '>' +
        '<span>Câu sau</span><i class="fa-solid fa-chevron-right" aria-hidden="true"></i></button></div>' +
        submitArea + '</aside>';
    }

    // "Làm lại" drops this attempt for a new one; once something is answered it asks first, in place
    function restartHtml(done) {
      if (!state.confirmRestart) {
        return '<button class="oy-button oy-quiz__restart" type="button" data-action="restart" aria-label="Làm lại">' +
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
      if (config.exam) { paintExamQuestion(focusSelector); return; }
      var question = current();
      var feedback = state.feedback[question.questionId];
      var total = state.questions.length;
      var done = answeredCount();
      paint('<div class="oy-quiz__layout">' + panel('oy-quiz__card',
        '<div class="oy-quiz__top"><span>Câu ' + (state.index + 1) + ' / ' + total + '</span>' +
        '<div class="oy-progress" aria-hidden="true"><div class="oy-progress__fill" style="width:' +
        Math.round(done * 100 / total) + '%"></div></div>' +
        '<button class="oy-button oy-quiz__popup-open" type="button" data-action="popup-open" aria-haspopup="dialog"' +
        ' aria-expanded="false" aria-label="Danh sách câu hỏi"><i class="fa-solid fa-table-cells-large" aria-hidden="true"></i></button></div>' +
        (question.focus ? '<div class="oy-quiz__focus" lang="ja">' + escapeHtml(question.focus) + '</div>' : '') +
        '<p class="oy-quiz__question">' + escapeHtml(question.questionText) + '</p>' +
        (question.questionType === 'FILL_BLANK' ? typingHtml(question, feedback) : optionsHtml(question, feedback)) +
        (feedback ? feedbackHtml(feedback) : hintHtml()) +
        '<p class="oy-quiz__alert" role="alert" hidden></p>') +
        sideHtml() + '</div>', true);
      syncPopup(false);
      holdCardHeight();
      // the results button is in the panel on a wide screen, under the question on a phone
      var submitButton = Array.prototype.filter.call(root.querySelectorAll('[data-action="submit"]'), function (button) {
        return button.offsetParent !== null;
      })[0];
      var focusTarget = (focusSelector && root.querySelector(focusSelector)) ||
        (focusSelector && root.querySelector('[data-action="go"][aria-current]')) ||
        (feedback
          ? submitButton ||
            root.querySelector('[data-action="forward"]:not(:disabled)') ||
            root.querySelector('[data-action="go"][aria-current]')
          : root.querySelector('.oy-quiz__input'));
      if (focusTarget) focusTarget.focus({ preventScroll: true });
    }

    function paintExamQuestion(focusSelector) {
      var question = current();
      var feedback = state.feedback[question.questionId];
      var total = state.questions.length;
      var done = answeredCount();
      paint('<div class="oy-quiz__layout oy-exam__layout"><div class="oy-exam__main">' +
        panel('oy-exam__progress',
          '<div class="oy-exam__progress-row"><span class="oy-exam__count"><i class="fa-regular fa-circle-question" aria-hidden="true"></i>' +
          'Câu ' + (state.index + 1) + ' / ' + total + '</span>' +
          '<span class="oy-exam__done">' + done + ' / ' + total + ' câu đã trả lời</span></div>' +
          '<div class="oy-progress" aria-hidden="true"><div class="oy-progress__fill" style="width:' +
          Math.round(done * 100 / total) + '%"></div></div>') +
        panel('oy-quiz__card oy-exam__card',
          '<span class="oy-exam__chip"><i class="fa-solid fa-language" aria-hidden="true"></i> Hán Việt → âm On</span>' +
          (question.focus ? '<div class="oy-quiz__focus" lang="ja">' + escapeHtml(question.focus) + '</div>' : '') +
          '<p class="oy-quiz__question">' + escapeHtml(question.questionText) + '</p>' +
          '<p class="oy-exam__sub">Chọn cách đọc On đúng theo quy tắc chuyển âm đã học.</p>') +
        '<p class="oy-exam__pick"><i class="fa-regular fa-hand-pointer" aria-hidden="true"></i> Chọn 1 trong 4 đáp án bên dưới:</p>' +
        (question.questionType === 'FILL_BLANK' ? typingHtml(question, feedback) : optionsHtml(question, feedback)) +
        '<p class="oy-exam__saved" role="status">' + (feedback
          ? '<i class="fa-solid fa-check" aria-hidden="true"></i> Đã lưu lựa chọn, bạn vẫn đổi được trước khi nộp bài. Bài chỉ được chấm khi nộp.'
          : '<i class="fa-regular fa-circle" aria-hidden="true"></i> Chưa chọn đáp án.') + '</p>' +
        '<p class="oy-quiz__alert" role="alert" hidden></p></div>' +
        sideHtml() + '</div>', true);
      var focusTarget = (focusSelector && root.querySelector(focusSelector)) ||
        (focusSelector && root.querySelector('[data-action="go"][aria-current]')) ||
        (feedback ? root.querySelector('[data-action="forward"]:not(:disabled)') || root.querySelector('.oy-quiz__option.is-selected')
          : root.querySelector('.oy-quiz__option') || root.querySelector('.oy-quiz__input'));
      if (focusTarget) focusTarget.focus({ preventScroll: true });
    }

    function goTo(index, focusSelector) {
      if (state.busy || index < 0 || index >= state.questions.length) return;
      state.index = index;
      state.confirmRestart = false;
      exam.confirmSubmit = false;
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
      setPopup(false);
      if (config.exam) enterFullscreen();
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
        if (config.exam) {
          examBegin(body.data);
          if (!exam.on) return;
        }
        if (state.index >= state.questions.length) {
          // In the exam every question having a choice is not the end: choices can change until "Nộp bài".
          if (!config.exam) {
            submit();
            return;
          }
          state.index = 0;
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
        if (config.exam && response.status === 409) {
          state.busy = false;
          exam.reason = exam.reason || (body.message || '');
          submit();
          return;
        }
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
      var saved = question && state.feedback[question.questionId];
      if (!question || (saved && (!config.exam || saved.selectedOption === option))) return;
      sendAnswer({ questionId: question.questionId, selectedOption: option });
    }

    function checkText() {
      var question = current();
      var input = root.querySelector('.oy-quiz__input');
      if (!question || !input || (state.feedback[question.questionId] && !config.exam)) return;
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
      setPopup(false);
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
      if (config.exam) examStop();
      exam.confirmSubmit = false;
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
        if (config.exam) await loadDetails();
        showSubmitted(body.data);
      } catch (error) {
        paintState('Không thể kết nối tới máy chủ. Vui lòng thử lại.', 'submit');
      }
    }

    function showSubmitted(result) {
      paintResult(result);
      if (result.passed) {
        root.dispatchEvent(new CustomEvent('onyomi:quiz-passed', {
          bubbles: true,
          detail: { kind: config.kind, score: Number(result.scorePercent) }
        }));
      }
      if (window.RewardFX) window.RewardFX.checkPending();
    }

    // ── Demo panel (mission.dev-tools) ──────────────────────────────────────────────────────────
    async function demoPost(path, payload) {
      var response = await fetch('/api/dev/onyomi/attempts/' + state.attemptId + path, {
        method: 'POST',
        headers: window.JlasAuth.authHeaders(),
        body: JSON.stringify(payload || {})
      });
      var body = await readBody(response);
      if (!response.ok || body.success === false) throw new Error(body.message || ('HTTP ' + response.status));
      return body.data;
    }

    function openAttempt() {
      if (!state.attemptId || state.busy || !root.querySelector('.oy-quiz__layout')) {
        throw new Error('Chưa có lượt làm bài đang mở.');
      }
    }

    // the server answers every question (right or wrong) and submits; the review shows what it recorded
    async function demoFinish(correct) {
      openAttempt();
      setPopup(false);
      state.busy = true;
      var result;
      try {
        result = await demoPost('/finish', { correct: correct });
      } finally {
        state.busy = false;
      }
      if (config.exam) examStop();
      exam.confirmSubmit = false;
      await loadDetails();
      if (state.details) {
        state.feedback = {};
        Object.keys(state.details).forEach(function (id) {
          var d = state.details[id];
          state.feedback[id] = { correct: d.isCorrect === true, correctAnswerText: d.correctAnswerText, explanation: d.explanation };
        });
      }
      showSubmitted(result);
      return result;
    }

    async function demoNewAttempt() {
      openAttempt();
      if (config.exam) examStop();
      await restart();
    }

    async function demoSecondsLeft(seconds) {
      openAttempt();
      if (!config.exam || !exam.on) throw new Error('Chỉ dùng trong bài kiểm tra đang chạy.');
      var data = await demoPost('/seconds-left', { seconds: seconds });
      exam.deadline = Date.now() + data.secondsLeft * 1000;
      tick();
      return data.secondsLeft;
    }

    async function demoLeave() {
      openAttempt();
      if (!config.exam || !exam.on) throw new Error('Chỉ dùng trong bài kiểm tra đang chạy.');
      exam.lastLeave = 0;
      await leave('TAB_HIDDEN');
      return exam.leaves;
    }

    window.JlasOnyomiQuiz = {
      exam: config.exam,
      finish: demoFinish,
      newAttempt: demoNewAttempt,
      secondsLeft: demoSecondsLeft,
      leave: demoLeave
    };

    // Graded answers of the submitted attempt (correct answer + explanation); the exam hides them until now.
    async function loadDetails() {
      state.details = null;
      try {
        var response = await fetch('/api/learning/quizzes/attempts/' + state.attemptId + '/result', {
          headers: window.JlasAuth.authHeaders()
        });
        var body = await readBody(response);
        if (response.ok && body.data) {
          state.details = {};
          (body.data.answers || []).forEach(function (d) { state.details[d.questionId] = d; });
        }
      } catch (error) { /* the score is still shown; only the review is missing */ }
    }

    function examReviewHtml() {
      if (!state.details) return '<p class="oy-quiz__review-missing">Không tải được phần giải thích. Tải lại trang để xem.</p>';
      return '<ol class="oy-quiz__review oy-exam__review">' + state.questions.map(function (question, i) {
        var d = state.details[question.questionId] || {};
        var picked = d.selectedOption && d.options ? d.options[d.selectedOption - 1] : d.answerText;
        var right = d.isCorrect === true;
        return '<li class="' + (right ? 'is-correct' : 'is-wrong') + '">' +
          '<strong><span class="oy-exam__mark" aria-label="' + (right ? 'Đúng' : 'Sai') + '">' + (right ? '✓' : '✗') + '</span> Câu ' + (i + 1) +
          ': ' + escapeHtml(question.questionText) + '</strong>' +
          '<span>Bạn chọn: ' + (picked ? '<b lang="ja">' + escapeHtml(picked) + '</b>' : '<i>bỏ trống</i>') + '</span>' +
          (right ? '' : '<span>Đáp án: <b lang="ja">' + escapeHtml(d.correctAnswerText || '') + '</b></span>') +
          (d.explanation ? '<span class="oy-exam__why">' + escapeHtml(d.explanation) + '</span>' : '') + '</li>';
      }).join('') + '</ol>';
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
      var review = config.exam ? examReviewHtml() : wrong.length
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
      var examInfo = '';
      if (config.exam) {
        var used = Math.min(LIMIT_SECONDS(), Math.round((Date.now() - exam.startedAt) / 1000));
        examInfo = (exam.reason ? '<p class="oy-exam__reason" role="status"><i class="fa-solid fa-circle-info" aria-hidden="true"></i> ' +
            escapeHtml(exam.reason) + '</p>' : '') +
          '<ul class="oy-exam__stats"><li><i class="fa-regular fa-clock" aria-hidden="true"></i> Thời gian làm: ' + clock(used) + '</li>' +
          '<li><i class="fa-regular fa-eye" aria-hidden="true"></i> Số lần rời bài: ' + exam.leaves + ' / ' + exam.limit + '</li></ul>';
      }
      paint(panel('oy-quiz__result',
        examInfo +
        '<div class="oy-quiz__score">' + score.toFixed(0) + '<small>/100</small></div>' +
        '<p>Đúng ' + result.correctCount + '/' + result.totalCount + ' câu</p>' +
        '<p class="oy-quiz__verdict ' + (result.passed ? 'is-passed' : 'is-failed') + '">' +
        escapeHtml(verdictText(result.passed)) + '</p>' +
        '<div class="oy-quiz__actions">' + nextLink +
        '<button class="oy-button" type="button" data-action="start">' +
        '<i class="fa-solid fa-rotate-right" aria-hidden="true"></i><span>Làm lượt mới</span></button>' +
        (config.backUrl ? '<a class="oy-button" href="' + escapeHtml(config.backUrl) + '">' + escapeHtml(config.backLabel) + '</a>' : '') +
        '</div>' +
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
          state.popupOpen = false;   // picking a question shows it; the arrows leave the popup open to keep stepping
          goTo(Number(action.dataset.index), '[data-action="go"][data-index="' + action.dataset.index + '"]');
          syncPopup(false);
          break;
        case 'popup-open': setPopup(true, '.oy-quiz__popup-close'); break;
        case 'popup-close': setPopup(false, '.oy-quiz__popup-open'); break;
        case 'submit': submit(); break;
        case 'submit-ask': exam.confirmSubmit = true; paintQuestion('[data-action="submit-cancel"]'); break;
        case 'submit-cancel': exam.confirmSubmit = false; paintQuestion('[data-action="submit-ask"]'); break;
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
      if (event.key === 'Escape' && state.popupOpen) {
        event.preventDefault();
        setPopup(false, '.oy-quiz__popup-open');
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

    // ── Exam: server clock + integrity monitor ─────────────────────────────────────────────────
    var timeEl = document.querySelector('[data-exam-time]');
    var leavesEl = document.querySelector('[data-exam-leaves]');
    var warningEl = document.querySelector('[data-exam-warning]');
    var exitEl = document.querySelector('[data-exam-exit]');
    var limitSeconds = 600;
    function LIMIT_SECONDS() { return limitSeconds; }

    function clock(seconds) {
      seconds = Math.max(0, Math.round(seconds));
      return String(Math.floor(seconds / 60)).padStart(2, '0') + ':' + String(seconds % 60).padStart(2, '0');
    }

    function enterFullscreen() {
      var el = document.documentElement;
      if (document.fullscreenElement || !el.requestFullscreen) return;
      el.requestFullscreen().then(function () { exam.fullscreen = true; }).catch(function () { /* refused: still watched */ });
    }

    function examBegin(data) {
      if (data.timeLimitSeconds == null) return;   // not a timed attempt: behave like a normal quiz
      limitSeconds = data.timeLimitSeconds;
      exam.limit = data.leaveLimit || 3;
      exam.leaves = data.leaveCount || 0;
      exam.deadline = Date.now() + (data.secondsLeft || 0) * 1000;
      exam.startedAt = exam.deadline - limitSeconds * 1000;
      exam.on = true;
      exam.reason = '';
      if (document.fullscreenElement) exam.fullscreen = true;
      paintLeaves();
      if (timeEl) timeEl.closest('[data-exam-timer]').hidden = false;
      tick();
      exam.timer = setInterval(tick, 250);
      if ('BroadcastChannel' in window) {
        exam.channel = new BroadcastChannel('jlas-exam-' + config.startUrl);
        exam.channel.onmessage = function (event) {
          // The test was opened again in another tab: that tab takes over (the server counted it as leaving).
          if (event.data && event.data.type === 'open' && event.data.tab !== exam.tab && exam.on) {
            examStop();
            paintState('Bài kiểm tra đang được làm ở một tab khác. Hãy đóng tab này.');
          }
        };
        exam.channel.postMessage({ type: 'open', tab: exam.tab });
      }
      if (exam.leaves >= exam.limit) {
        exam.reason = 'Bạn đã rời khỏi bài ' + exam.leaves + ' lần, bài được nộp tự động.';
        submit();
        return;
      }
      if (exam.leaves > 0 && (data.answered || []).length) showWarning('Bạn vừa mở lại bài giữa chừng, lần này được tính là rời bài.');
    }

    function tick() {
      var left = (exam.deadline - Date.now()) / 1000;
      if (timeEl) {
        timeEl.textContent = clock(left);
        timeEl.closest('[data-exam-timer]').classList.toggle('is-low', left <= 60);
      }
      if (left <= 0 && exam.on) {
        exam.reason = 'Hết giờ, bài được nộp tự động với các câu đã trả lời.';
        report('TIME_UP');
        submit();
      }
    }

    function paintLeaves() {
      if (!leavesEl) return;
      leavesEl.hidden = false;
      leavesEl.querySelector('b').textContent = exam.leaves + '/' + exam.limit;
      leavesEl.classList.toggle('is-warn', exam.leaves > 0);
    }

    function examStop() {
      exam.on = false;
      clearInterval(exam.timer);
      if (timeEl) timeEl.closest('[data-exam-timer]').hidden = true;   // submitted: the clock no longer matters
      if (exam.channel) { exam.channel.close(); exam.channel = null; }
      if (warningEl) warningEl.hidden = true;
      if (document.fullscreenElement && document.exitFullscreen) document.exitFullscreen().catch(function () {});
      exam.fullscreen = false;
    }

    /** Sends one event; leaving events come back with the server's count, which decides the auto-submit. */
    async function report(type, detail) {
      if (!state.attemptId) return null;
      try {
        var response = await fetch('/api/learning/quizzes/attempts/' + state.attemptId + '/integrity', {
          method: 'POST',
          headers: window.JlasAuth.authHeaders(),
          body: JSON.stringify({ type: type, detail: detail || null })
        });
        var body = await readBody(response);
        return body && body.data ? body.data : null;
      } catch (error) {
        return null;
      }
    }

    async function leave(type) {
      if (!exam.on) return;
      var now = Date.now();
      if (now - exam.lastLeave < 1500) return;   // a tab switch fires both blur and hidden: count it once
      exam.lastLeave = now;
      var data = await report(type);
      if (!exam.on) return;
      exam.leaves = data ? data.leaveCount : exam.leaves + 1;
      paintLeaves();
      if ((data && data.locked) || exam.leaves >= exam.limit) {
        exam.reason = 'Bạn đã rời khỏi bài ' + exam.leaves + ' lần, bài được nộp tự động.';
        submit();
        return;
      }
      showWarning(type === 'FULLSCREEN_EXIT' ? 'Bạn vừa thoát chế độ toàn màn hình.' : 'Bạn vừa rời khỏi trang làm bài.');
    }

    function blocked(type) {
      if (!exam.on) return;
      var now = Date.now();
      if (now - (exam.lastReport[type] || 0) < 3000) return;
      exam.lastReport[type] = now;
      report(type);
    }

    function showWarning(message) {
      if (!warningEl) return;
      warningEl.querySelector('[data-exam-warning-text]').textContent = message + ' Đây là lần ' + exam.leaves + '/' + exam.limit +
        '. Rời bài đủ ' + exam.limit + ' lần, bài sẽ tự nộp.';
      warningEl.hidden = false;
      var back = warningEl.querySelector('[data-exam-resume]');
      if (back) back.focus();
    }

    if (config.exam) {
      document.addEventListener('visibilitychange', function () {
        if (document.hidden) leave('TAB_HIDDEN');
      });
      window.addEventListener('blur', function () {
        setTimeout(function () { if (!document.hidden && !document.hasFocus()) leave('WINDOW_BLUR'); }, 400);
      });
      document.addEventListener('fullscreenchange', function () {
        if (document.fullscreenElement) { exam.fullscreen = true; return; }
        if (exam.fullscreen) { exam.fullscreen = false; leave('FULLSCREEN_EXIT'); }
      });
      ['copy', 'cut', 'paste', 'contextmenu'].forEach(function (name) {
        document.addEventListener(name, function (event) {
          if (!exam.on) return;
          event.preventDefault();
          blocked(name === 'contextmenu' ? 'CONTEXT_MENU' : name === 'paste' ? 'PASTE' : 'COPY');
        });
      });
      document.addEventListener('keydown', function (event) {
        if (!exam.on) return;
        var key = (event.key || '').toUpperCase();
        var devtools = key === 'F12' || ((event.ctrlKey || event.metaKey) && event.shiftKey && 'IJC'.indexOf(key) !== -1 && key) ||
          ((event.ctrlKey || event.metaKey) && key === 'U');
        if (devtools) { event.preventDefault(); blocked('DEVTOOLS'); }
      });
      window.addEventListener('beforeunload', function (event) {
        if (!exam.on) return;
        event.preventDefault();
        event.returnValue = '';
      });
      if (warningEl) {
        warningEl.addEventListener('click', function (event) {
          if (!event.target.closest('[data-exam-resume]')) return;
          warningEl.hidden = true;
          enterFullscreen();
        });
      }
      if (exitEl) {
        exitEl.addEventListener('click', function (event) {
          if (!exam.on) return;
          if (!window.confirm('Thoát bây giờ? Đồng hồ vẫn chạy và câu đã làm được lưu; quay lại làm tiếp sẽ tính là 1 lần rời bài.')) {
            event.preventDefault();
            return;
          }
          examStop();
        });
      }
    }

    if (root.hasAttribute('data-autostart')) start();
    else paintIntro();
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
