(function () {
  'use strict';
  var ACTIVE_ATTEMPT_KEY = 'jlasSmartTestAttemptId';
  var slides = Array.from(document.querySelectorAll('.survey-slide'));
  var nextButton = document.getElementById('surveyNextButton');
  var backButton = document.getElementById('surveyBackButton');
  var notice = document.getElementById('surveyNotice');
  var currentIndex = 0;
  var savedSurvey = null;
  var wizard = document.getElementById('surveyWizard');
  var form = document.getElementById('smartTestSurveyForm');
  var progressHeader = document.querySelector('.survey-progress-header');
  var invitation = document.getElementById('surveyInvitation');
  var startTest = document.getElementById('surveyStartTest');
  var goHome = document.getElementById('surveyGoHome');
  var noTest = false;
  if (!window.JlasAuth || !window.JlasAuth.requireLogin('/survey')) return;

  function visibleSlides() {
    var goal = document.querySelector('input[name="goal"]:checked');
    var studied = document.querySelector('input[name="studied"]:checked');
    return slides.filter(function (slide) {
      var step = slide.dataset.surveyStep;
      if (step === 'target') return !!goal && goal.value === 'JLPT';
      if (step === 'level') return !!studied && studied.value === 'YES';
      if (step === 'duration') return !!studied && studied.value === 'YES';
      return true;
    });
  }

  function currentSlide() { return visibleSlides()[currentIndex]; }

  function hasAnswer(slide) {
    if (!slide) return false;
    return Boolean(slide.querySelector('input:checked'));
  }

  function render() {
    var activeSlides = visibleSlides();
    if (currentIndex >= activeSlides.length) currentIndex = activeSlides.length - 1;
    var active = activeSlides[currentIndex];
    slides.forEach(function (slide) { slide.classList.toggle('is-active', slide === active); });
    var progress = ((currentIndex + 1) / activeSlides.length) * 100;
    document.getElementById('surveyProgress').style.width = progress + '%';
    document.getElementById('surveyStepCounter').textContent = (currentIndex + 1) + '/' + activeSlides.length;
    backButton.disabled = currentIndex === 0;
    nextButton.disabled = !hasAnswer(active);
    nextButton.querySelector('span').textContent =
      currentIndex === activeSlides.length - 1 ? 'Hoàn thành khảo sát' : 'Tiếp tục';
    notice.hidden = true;
    if (active && active.dataset.surveyStep === 'target') refreshTargetOptions();
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  // [JLAS-W0108] Task 11 - which JLPT targets are reachable is decided by the server only
  // (GET /api/assessment/survey/target-options). If the request fails every target stays disabled.
  var targetOptionsRequest = 0;
  function targetInputs() { return Array.from(document.querySelectorAll('input[name="targetLevel"]')); }
  function renderTargetOptions(view) {
    targetInputs().forEach(function (input) {
      var match = view.filter(function (option) { return option.level === input.value; })[0];
      var label = input.closest('label');
      var text = label && label.querySelector('span:not(.level-mark):not(.survey-option__icon)');
      var detail = label && label.querySelector('.survey-option__server');
      if (!detail && text) {
        detail = document.createElement('small');
        detail.className = 'survey-option__server';
        text.appendChild(detail);
      }
      input.disabled = !match || match.disabled;
      if (label) label.classList.toggle('is-disabled', input.disabled);
      if (detail) {
        detail.textContent = !match ? 'Chưa tải được lựa chọn từ máy chủ.'
          : match.disabled ? match.reason
          : (match.recommended ? 'Gợi ý · ' : '') + (match.stageLabel ? 'Lộ trình: ' + match.stageLabel : '');
      }
      if (input.disabled && input.checked) input.checked = false;
    });
    nextButton.disabled = !hasAnswer(currentSlide());
  }
  async function refreshTargetOptions() {
    var request = ++targetOptionsRequest;
    var studied = document.querySelector('input[name="studied"]:checked');
    var selfLevel = document.querySelector('input[name="selfLevel"]:checked');
    var assessed = window.SmartTestSurveyTargets.assessedLevelFor({
      studied: studied ? studied.value : null, selfLevel: selfLevel ? selfLevel.value : null });
    try {
      var options = await api('/api/assessment/survey/target-options?assessedLevel=' + encodeURIComponent(assessed));
      if (request === targetOptionsRequest) renderTargetOptions(window.SmartTestSurveyTargets.describeOptions(options));
    } catch (error) {
      if (request === targetOptionsRequest) { renderTargetOptions([]); showError(error.message); }
    }
  }

  function showError(message) { notice.textContent = message || 'Không thể tạo bài kiểm tra. Vui lòng thử lại.'; notice.hidden = false; }
  async function api(url, options) { var response = await fetch(url, Object.assign({ headers: window.JlasAuth.authHeaders() }, options || {})); var payload; try { payload = await response.json(); } catch (ignore) { payload = null; } if (!response.ok || !payload || !payload.success) throw new Error(payload && payload.message ? payload.message : 'Không thể kết nối tới máy chủ.'); return payload.data; }

  function showInvitation(survey) {
    savedSurvey = survey;
    var fromZero = survey.selfReportedLevel === 'FROM_ZERO';
    var surveyOnlyGoal = survey.goal === 'TRAVEL' || survey.goal === 'DAILY_LIFE';
    var needsPathOnly = fromZero || surveyOnlyGoal;
    if (surveyOnlyGoal) {
      document.getElementById('inviteTitle').textContent = 'Chọn tình huống bạn muốn luyện!';
      document.getElementById('inviteText').textContent = 'Du lịch và đời sống không dùng Smart Test. J-LAS sẽ dùng khảo sát chủ đề để tạo lộ trình giao tiếp phù hợp.';
    } else if (fromZero) {
      document.getElementById('inviteTitle').textContent = 'Bạn sẽ bắt đầu từ con số 0!';
      document.getElementById('inviteText').textContent = 'Bạn chưa từng học tiếng Nhật nên không cần làm bài kiểm tra. J-LAS sẽ xây lộ trình học từ đầu cho bạn.';
    } else {
      document.getElementById('inviteTitle').textContent = 'Sẵn sàng khám phá trình độ của bạn?';
      document.getElementById('inviteText').textContent = 'Làm Smart Test cùng Kitsu để tìm điểm bắt đầu phù hợp. Nếu chưa sẵn sàng, bạn có thể làm bài sau.';
    }
    startTest.hidden = needsPathOnly;
    noTest = needsPathOnly;
    goHome.textContent = needsPathOnly ? 'Xem lộ trình của bạn' : 'Để sau, về trang chủ';
    goHome.classList.toggle('smart-test-primary', needsPathOnly);
    goHome.classList.toggle('smart-test-secondary', !needsPathOnly);
    wizard.classList.add('survey-wizard--invitation');
    form.hidden = true;
    progressHeader.hidden = true;
    invitation.hidden = false;
    notice.hidden = true;
    (needsPathOnly ? goHome : startTest).focus();
  }

  function fillSurvey(survey) {
    var values = { studied: survey.selfReportedLevel === 'FROM_ZERO' ? 'NO' : 'YES',
      goal: survey.goal, targetLevel: survey.targetLevel, selfLevel: survey.selfReportedLevel,
      duration: survey.studyDurationMonths, minutes: survey.dailyStudyMinutes };
    Object.keys(values).forEach(function (name) {
      form.querySelectorAll('input[name="' + name + '"]').forEach(function (input) {
        if (input.disabled) return; // N2/N1 target bị khóa (đang phát triển) — không pre-check để tránh kẹt
        input.checked = String(input.value) === String(values[name]);
      });
    });
  }

  async function beginTest(survey) {
    startTest.disabled = true;
    startTest.querySelector('span').textContent = 'Đang tạo bài...';
    try {
      var test = await api('/api/assessment/smart-tests', { method: 'POST', body: JSON.stringify(survey) });
      localStorage.setItem(ACTIVE_ATTEMPT_KEY, test.attemptId);
      window.location.assign('/smart-test');
    } catch (error) { showError(error.message); }
    finally { startTest.disabled = false; startTest.querySelector('span').textContent = 'Bắt đầu Smart Test'; }
  }

  startTest.addEventListener('click', function () { if (savedSurvey) beginTest(savedSurvey); });
  goHome.addEventListener('click', function () {
    if (!noTest) return window.location.assign('/');
    if (savedSurvey && savedSurvey.goal !== 'JLPT') {
      return window.location.assign('/learning/goals/' + encodeURIComponent(savedSurvey.goal) + '/survey');
    }
    window.location.assign('/learning/path');
  });
  document.getElementById('surveyEdit').addEventListener('click', function () {
    if (savedSurvey) fillSurvey(savedSurvey);
    invitation.hidden = true;
    form.hidden = false;
    progressHeader.hidden = false;
    backButton.hidden = false;
    wizard.classList.remove('survey-wizard--invitation');
    currentIndex = 0;
    render();
  });

  slides.forEach(function (slide) {
    slide.querySelectorAll('input').forEach(function (input) {
      input.addEventListener('change', function () {
        if (input.name === 'studied' || input.name === 'goal') {
          render();
        } else {
          nextButton.disabled = !hasAnswer(currentSlide());
        }
        if (input.type === 'radio') window.setTimeout(function () { if (!nextButton.disabled) nextButton.focus(); }, 120);
      });
    });
  });

  backButton.addEventListener('click', function () { if (currentIndex > 0) { currentIndex--; render(); } });
  nextButton.addEventListener('click', async function () {
    var activeSlides = visibleSlides();
    if (!hasAnswer(activeSlides[currentIndex])) return;
    if (currentIndex < activeSlides.length - 1) { currentIndex++; render(); return; }
    var goal = document.querySelector('input[name="goal"]:checked').value;
    var studied = document.querySelector('input[name="studied"]:checked').value;
    var target = document.querySelector('input[name="targetLevel"]:checked');
    var body = {
      goal: goal,
      targetLevel: goal === 'JLPT' ? target.value : null,
      selfReportedLevel: studied === 'YES' ? document.querySelector('input[name="selfLevel"]:checked').value : 'FROM_ZERO',
      studyDurationMonths: studied === 'YES' ? Number(document.querySelector('input[name="duration"]:checked').value) : 0,
      dailyStudyMinutes: Number(document.querySelector('input[name="minutes"]:checked').value)
    };
    nextButton.disabled = true; nextButton.querySelector('span').textContent = 'Đang lưu khảo sát...';
    try {
      var data = await api('/api/assessment/survey', { method: 'POST', body: JSON.stringify(body) });
      showInvitation(data.survey);
    }
    catch (error) { showError(error.message); }
    finally { nextButton.disabled = false; nextButton.querySelector('span').textContent = 'Hoàn thành khảo sát'; }
  });
  render();
  api('/api/assessment/survey').then(function (data) {
    if (data.completed && data.survey) { showInvitation(data.survey); }
  }).catch(function (error) { showError(error.message); });
})();
