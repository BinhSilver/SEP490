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
  if (!window.JlasAuth || !window.JlasAuth.requireLogin('/survey')) return;

  function visibleSlides() {
    var goal = document.querySelector('input[name="goal"]:checked');
    var studied = document.querySelector('input[name="studied"]:checked');
    return slides.filter(function (slide) {
      var step = slide.dataset.surveyStep;
      if (step === 'target') return !!goal && goal.value === 'JLPT';
      if (step === 'level') return !!studied && studied.value === 'YES';
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
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  function showError(message) { notice.textContent = message || 'Không thể tạo bài kiểm tra. Vui lòng thử lại.'; notice.hidden = false; }
  async function api(url, options) { var response = await fetch(url, Object.assign({ headers: window.JlasAuth.authHeaders() }, options || {})); var payload; try { payload = await response.json(); } catch (ignore) { payload = null; } if (!response.ok || !payload || !payload.success) throw new Error(payload && payload.message ? payload.message : 'Không thể kết nối tới máy chủ.'); return payload.data; }

  function showInvitation(survey) {
    savedSurvey = survey;
    var fromZero = survey.selfReportedLevel === 'FROM_ZERO';
    document.getElementById('inviteTitle').textContent = fromZero
      ? 'Bạn sẽ bắt đầu từ con số 0!'
      : 'Sẵn sàng khám phá trình độ của bạn?';
    document.getElementById('inviteText').textContent = fromZero
      ? 'Bạn chưa từng học tiếng Nhật nên không cần làm bài kiểm tra. J-LAS sẽ xây lộ trình học từ đầu cho bạn.'
      : 'Làm Smart Test cùng Kitsu để tìm điểm bắt đầu phù hợp. Nếu chưa sẵn sàng, bạn có thể làm bài sau.';
    startTest.hidden = fromZero;
    goHome.textContent = fromZero ? 'Về trang chủ' : 'Để sau, về trang chủ';
    wizard.classList.add('survey-wizard--invitation');
    form.hidden = true;
    progressHeader.hidden = true;
    invitation.hidden = false;
    notice.hidden = true;
    (fromZero ? goHome : startTest).focus();
  }

  function fillSurvey(survey) {
    var values = { studied: survey.selfReportedLevel === 'FROM_ZERO' ? 'NO' : 'YES',
      goal: survey.goal, targetLevel: survey.targetLevel, selfLevel: survey.selfReportedLevel,
      duration: survey.studyDurationMonths, minutes: survey.dailyStudyMinutes };
    Object.keys(values).forEach(function (name) {
      form.querySelectorAll('input[name="' + name + '"]').forEach(function (input) {
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
  goHome.addEventListener('click', function () { window.location.assign('/'); });
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
        nextButton.disabled = !hasAnswer(currentSlide());
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
      studyDurationMonths: Number(document.querySelector('input[name="duration"]:checked').value),
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
