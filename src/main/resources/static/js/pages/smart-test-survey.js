(function () {
  'use strict';
  var ACTIVE_ATTEMPT_KEY = 'jlasSmartTestAttemptId';
  var SELECTED_SKILLS_KEY = 'jlasSmartTestSelectedSkills';
  var SKILL_ORDER = ['KANJI', 'VOCABULARY', 'GRAMMAR', 'READING', 'LISTENING'];
  var slides = Array.from(document.querySelectorAll('.survey-slide'));
  var nextButton = document.getElementById('surveyNextButton');
  var backButton = document.getElementById('surveyBackButton');
  var notice = document.getElementById('surveyNotice');
  var currentIndex = 0;
  var savedSurvey = null;
  var skillSelection = false;
  var wizard = document.getElementById('surveyWizard');
  var form = document.getElementById('smartTestSurveyForm');
  var progressHeader = document.querySelector('.survey-progress-header');
  var invitation = document.getElementById('surveyInvitation');
  var startTest = document.getElementById('surveyStartTest');
  var goHome = document.getElementById('surveyGoHome');
  if (!window.JlasAuth || !window.JlasAuth.requireLogin('/survey')) return;

  function visibleSlides() {
    var goal = document.querySelector('input[name="goal"]:checked');
    return slides.filter(function (slide) {
      if (skillSelection) return slide.dataset.surveyStep === 'skills';
      return slide.dataset.surveyStep !== 'skills'
        && (slide.dataset.surveyStep !== 'target' || !goal || goal.value === 'JLPT');
    });
  }

  function currentSlide() { return visibleSlides()[currentIndex]; }

  function hasAnswer(slide) {
    if (!slide) return false;
    if (slide.dataset.surveyStep === 'skills') return slide.querySelectorAll('input:checked').length > 0;
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
    nextButton.querySelector('span').textContent = skillSelection ? 'Bắt đầu Smart Test'
      : currentIndex === activeSlides.length - 1 ? 'Hoàn thành khảo sát' : 'Tiếp tục';
    notice.hidden = true;
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  function showError(message) { notice.textContent = message || 'Không thể tạo bài kiểm tra. Vui lòng thử lại.'; notice.hidden = false; }
  async function api(url, options) { var response = await fetch(url, Object.assign({ headers: window.JlasAuth.authHeaders() }, options || {})); var payload; try { payload = await response.json(); } catch (ignore) { payload = null; } if (!response.ok || !payload || !payload.success) throw new Error(payload && payload.message ? payload.message : 'Không thể kết nối tới máy chủ.'); return payload.data; }

  function showInvitation(survey) {
    savedSurvey = survey;
    wizard.classList.add('survey-wizard--invitation');
    form.hidden = true;
    progressHeader.hidden = true;
    invitation.hidden = false;
    notice.hidden = true;
    startTest.focus();
  }

  function showSkills(survey) {
    savedSurvey = survey;
    skillSelection = true;
    fillSurvey(survey);
    wizard.classList.remove('survey-wizard--invitation');
    wizard.classList.add('survey-wizard--skills');
    invitation.hidden = true;
    form.hidden = false;
    progressHeader.hidden = false;
    backButton.hidden = true;
    currentIndex = 0;
    render();
  }

  function fillSurvey(survey) {
    var values = { goal: survey.goal, targetLevel: survey.targetLevel, selfLevel: survey.selfReportedLevel,
      duration: survey.studyDurationMonths, minutes: survey.dailyStudyMinutes };
    Object.keys(values).forEach(function (name) {
      form.querySelectorAll('input[name="' + name + '"]').forEach(function (input) {
        input.checked = String(input.value) === String(values[name]);
      });
    });
    form.querySelectorAll('input[name="skill"]').forEach(function (input) {
      input.checked = survey.skills.indexOf(input.value) >= 0;
    });
  }

  startTest.addEventListener('click', function () { if (savedSurvey) showSkills(savedSurvey); });
  goHome.addEventListener('click', function () { window.location.assign('/'); });
  document.getElementById('surveyEdit').addEventListener('click', function () {
    invitation.hidden = true;
    form.hidden = false;
    progressHeader.hidden = false;
    skillSelection = false;
    backButton.hidden = false;
    wizard.classList.remove('survey-wizard--invitation', 'survey-wizard--skills');
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
    if (skillSelection) {
      var chosen = Array.from(form.querySelectorAll('input[name="skill"]')).filter(function (input) { return input.checked; })
        .map(function (input) { return input.value; });
      var testSurvey = Object.assign({}, savedSurvey, { skills: SKILL_ORDER.filter(function (skill) { return chosen.includes(skill); }) });
      nextButton.disabled = true;
      nextButton.querySelector('span').textContent = 'Đang tạo bài...';
      try {
        var updated = await api('/api/assessment/survey', { method: 'POST', body: JSON.stringify(testSurvey) });
        savedSurvey = updated.survey;
        var test = await api('/api/assessment/smart-tests', { method: 'POST', body: JSON.stringify(savedSurvey) });
        localStorage.setItem(ACTIVE_ATTEMPT_KEY, test.attemptId);
        localStorage.setItem(SELECTED_SKILLS_KEY, JSON.stringify(savedSurvey.skills));
        window.location.assign('/smart-test');
      } catch (error) { showError(error.message); }
      finally { nextButton.disabled = false; nextButton.querySelector('span').textContent = 'Bắt đầu Smart Test'; }
      return;
    }
    var goal = document.querySelector('input[name="goal"]:checked').value;
    var target = document.querySelector('input[name="targetLevel"]:checked');
    var body = {
      goal: goal,
      targetLevel: goal === 'JLPT' ? target.value : null,
      selfReportedLevel: document.querySelector('input[name="selfLevel"]:checked').value,
      studyDurationMonths: Number(document.querySelector('input[name="duration"]:checked').value),
      dailyStudyMinutes: Number(document.querySelector('input[name="minutes"]:checked').value),
      skills: savedSurvey ? savedSurvey.skills : SKILL_ORDER.slice()
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
    if (data.completed && data.survey) { showSkills(data.survey); }
  }).catch(function (error) { showError(error.message); });
})();
