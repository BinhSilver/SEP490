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

  // [JLAS-W0108] Task 23 - JLPT path (flag learning.jlpt-path.enabled, read from GET /api/learning/jlpt/config; any
  // failure counts as off). Off: the old 6-step survey, untouched. On: goal first, kana and skill-day steps, entry
  // answers saved to PUT /api/learning/jlpt/entry-preferences (JLPT goal only).
  var Flow = window.JpathSurveyFlow;
  var jpathConfig = { enabled: false };
  var jpathOn = false;
  var entryStatus = null;
  var afterSurveyTarget = null;
  var dayPicker = null;
  var dayState = { skillDays: ['WED', 'SAT'], restDays: [] };
  var kanaAutoFilled = false;
  var editParam = null;
  try { editParam = new URLSearchParams(window.location.search).get('edit'); } catch (ignore) { editParam = null; }

  function checkedValue(name) {
    var input = form.querySelector('input[name="' + name + '"]:checked');
    return input ? input.value : null;
  }

  function legacySteps(goal, studied) {
    return slides.map(function (slide) { return slide.dataset.surveyStep; }).filter(function (step) {
      if (step === 'target') return goal === 'JLPT';
      if (step === 'level' || step === 'duration') return studied === 'YES';
      return step !== 'kana' && step !== 'days';
    });
  }

  function visibleSlides() {
    var goal = checkedValue('goal');
    var studied = checkedValue('studied');
    var steps = Flow ? Flow.visibleSteps({ goal: goal, studied: studied }, jpathOn) : legacySteps(goal, studied);
    return steps.map(function (step) {
      return slides.filter(function (slide) { return slide.dataset.surveyStep === step; })[0];
    }).filter(Boolean);
  }

  function stepIndex(step) {
    return visibleSlides().map(function (slide) { return slide.dataset.surveyStep; }).indexOf(step);
  }

  function currentSlide() { return visibleSlides()[currentIndex]; }

  function hasAnswer(slide) {
    if (!slide) return false;
    if (slide.dataset.surveyStep === 'days') return dayState.skillDays.length === 2;
    // An option hidden by the flag (e.g. the old "Dưới 6 tháng") does not count as an answer.
    return Array.prototype.some.call(slide.querySelectorAll('input:checked'), function (input) {
      return !input.closest('[data-jpath-only][hidden], [data-legacy-only][hidden]');
    });
  }

  function applyFlagVisibility() {
    document.querySelectorAll('[data-jpath-only]').forEach(function (el) { el.hidden = !jpathOn; });
    document.querySelectorAll('[data-legacy-only]').forEach(function (el) { el.hidden = jpathOn; });
  }

  function setupDayPicker() {
    var mountPoint = form.querySelector('[data-jpath-day-picker]');
    if (!jpathOn || !mountPoint || !window.JpathDayPicker) return;
    dayPicker = window.JpathDayPicker.mount(mountPoint, dayState, function (next) {
      dayState = next;
      nextButton.disabled = !hasAnswer(currentSlide());
    });
  }

  function setKanaChoice(value) {
    form.querySelectorAll('input[name="kanaChoice"]').forEach(function (input) { input.checked = input.value === value; });
  }

  /** "Dưới 3 tháng" pre-selects "Muốn ôn lại"; a choice the learner made is never overwritten. */
  function prefillKana() {
    if (!jpathOn || !Flow) return;
    var current = checkedValue('kanaChoice');
    if (current && !kanaAutoFilled) return;
    var suggested = Flow.defaultKanaChoice(checkedValue('duration'));
    setKanaChoice(suggested);
    kanaAutoFilled = Boolean(suggested);
  }

  function applyEntryStatus() {
    if (!jpathOn || !entryStatus) return;
    var kana = entryStatus.preferenceDone ? entryStatus.kanaChoice : null;
    kanaAutoFilled = false;
    if (kana === 'REMEMBER' || kana === 'REVIEW') setKanaChoice(kana); else { setKanaChoice(null); prefillKana(); }
    var Days = window.JpathDayPicker;
    var skill = Days ? Days.validDays(entryStatus.skillDays) : [];
    var rest = Days ? Days.validDays(entryStatus.restDays).filter(function (d) { return skill.indexOf(d) < 0; }) : [];
    dayState = { skillDays: skill.length === 2 ? skill : ['WED', 'SAT'], restDays: rest.slice(0, 2) };
    if (skill.length !== 2) dayState.restDays = dayState.restDays.filter(function (d) { return d !== 'WED' && d !== 'SAT'; });
    if (dayPicker) dayPicker.setState(dayState);
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
    var goalInput = document.querySelector('input[name="goal"]:checked');
    var isJlpt = goalInput && goalInput.value === 'JLPT';
    var examDateField = document.getElementById('surveyExamDateField');
    if (examDateField) {
      examDateField.hidden = !isJlpt;
    }
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
      // JLPT path on: a level whose path is not released yet is shown as "Sắp có" and cannot be picked.
      var comingSoon = Boolean(jpathOn && Flow && Flow.targetDisabled(input.value, jpathConfig));
      input.disabled = comingSoon || !match || match.disabled;
      if (label) label.classList.toggle('is-disabled', input.disabled);
      if (detail) {
        detail.textContent = comingSoon ? 'Sắp có'
          : !match ? 'Chưa tải được lựa chọn từ máy chủ.'
          : match.disabled || match.reason ? match.reason
          : (match.recommended ? 'Gợi ý · ' : '') + (match.stageLabel ? 'Lộ trình: ' + match.stageLabel : '');
      }
      if (input.disabled && input.checked) input.checked = false;
    });
    nextButton.disabled = !hasAnswer(currentSlide());
  }
  async function refreshTargetOptions() {
    var request = ++targetOptionsRequest;
    var studied = document.querySelector('input[name="studied"]:checked');
    // The JLPT path does not ask the level step, so an old hidden answer must not steer the targets.
    var selfLevel = jpathOn && checkedValue('goal') === 'JLPT' ? null : document.querySelector('input[name="selfLevel"]:checked');
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
    // [JLAS-W0108] BA/TRAVEL/DAILY_LIFE are survey-only (owner decision 2026-10-14); none may offer Smart Test.
    var surveyOnlyGoal = survey.goal === 'BA' || survey.goal === 'TRAVEL' || survey.goal === 'DAILY_LIFE';
    var pathStatus = entryStatus ? entryStatus.pathStatus : null;
    afterSurveyTarget = jpathOn && Flow ? Flow.afterSurveyUrl(jpathConfig, survey, pathStatus, {
      edit: editParam,
      smartTestStatus: entryStatus && entryStatus.smartTest ? entryStatus.smartTest.status : null
    }) : null;
    var keepsPath = Boolean(afterSurveyTarget) && Flow.keepsPath(pathStatus);
    var resumesSmartTest = Boolean(afterSurveyTarget) && !keepsPath && !fromZero;
    var needsPathOnly = fromZero || surveyOnlyGoal || Boolean(afterSurveyTarget);
    if (keepsPath) {
      document.getElementById('inviteTitle').textContent = 'Lộ trình JLPT của bạn vẫn được giữ!';
      document.getElementById('inviteText').textContent = 'Bạn đã có lộ trình nên không cần làm lại Smart Test. Tiến độ học vẫn được giữ nguyên.';
    } else if (resumesSmartTest) {
      document.getElementById('inviteTitle').textContent = 'Kitsu đã lưu lịch học của bạn!';
      document.getElementById('inviteText').textContent = 'Bạn đã có bài Smart Test. Tiếp tục để xem kết quả và xác nhận lộ trình JLPT.';
    } else if (surveyOnlyGoal) {
      document.getElementById('inviteTitle').textContent = 'Chọn tình huống bạn muốn luyện!';
      document.getElementById('inviteText').textContent = 'Mục tiêu giao tiếp không dùng Smart Test. J-LAS sẽ dùng khảo sát chủ đề để tạo lộ trình giao tiếp phù hợp.';
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

  function getVietnamToday() {
    var now = new Date();
    var utc = now.getTime() + (now.getTimezoneOffset() * 60000);
    return new Date(utc + (7 * 3600000));
  }

  function formatDateISO(d) {
    var year = d.getFullYear();
    var month = String(d.getMonth() + 1).padStart(2, '0');
    var day = String(d.getDate()).padStart(2, '0');
    return year + '-' + month + '-' + day;
  }

  function getExamDateConstraints() {
    var vnToday = getVietnamToday();
    var tomorrow = new Date(vnToday.getFullYear(), vnToday.getMonth(), vnToday.getDate() + 1);
    var maxDate = new Date(vnToday.getFullYear() + 3, vnToday.getMonth(), vnToday.getDate());
    return {
      min: formatDateISO(tomorrow),
      max: formatDateISO(maxDate)
    };
  }

  function initExamDateConstraints(input) {
    if (!input) return;
    var constraints = getExamDateConstraints();
    input.min = constraints.min;
    input.max = constraints.max;
  }

  function buildSurveyBody(formEl) {
    var f = formEl || form || document;
    var goalEl = f.querySelector('input[name="goal"]:checked');
    var studiedEl = f.querySelector('input[name="studied"]:checked');
    var targetEl = f.querySelector('input[name="targetLevel"]:checked');
    var selfLevelEl = f.querySelector('input[name="selfLevel"]:checked');
    var durationEl = f.querySelector('input[name="duration"]:checked');
    var minutesEl = f.querySelector('input[name="minutes"]:checked');
    var examDateInput = f.querySelector('input[name="examDate"]') || document.getElementById('surveyExamDateInput');

    var goal = goalEl ? goalEl.value : null;
    var studied = studiedEl ? studiedEl.value : null;

    var body = {
      goal: goal,
      targetLevel: goal === 'JLPT' && targetEl ? targetEl.value : null,
      selfReportedLevel: studied === 'YES' && selfLevelEl ? selfLevelEl.value : 'FROM_ZERO',
      studyDurationMonths: studied === 'YES' && durationEl ? Number(durationEl.value) : 0,
      dailyStudyMinutes: minutesEl ? Number(minutesEl.value) : 30
    };
    var jpathJlpt = Boolean(jpathOn && Flow && goal === 'JLPT');
    // [JLAS-W0108] The JLPT path has no level step: a learner who studied is UNKNOWN (Smart Test finds the level).
    if (jpathJlpt) body.selfReportedLevel = Flow.selfReportedLevelFor(studied);

    if (goal === 'JLPT') {
      var dateStr = examDateInput && examDateInput.value ? examDateInput.value.trim() : '';
      body.examDate = dateStr ? dateStr : null;
      // F1-5 (owner 2026-10-28): up to two focus skills; the server checks them again. Not asked on the JLPT path.
      var focusBoxes = typeof f.querySelectorAll === 'function' ? f.querySelectorAll('input[name="focusSkills"]:checked') : [];
      body.focusSkills = jpathJlpt ? []
        : Array.prototype.map.call(focusBoxes, function (input) { return input.value; }).slice(0, 2);
    }
    return body;
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
    var examDateInput = document.getElementById('surveyExamDateInput');
    if (examDateInput) {
      examDateInput.value = survey.examDate ? String(survey.examDate).substring(0, 10) : '';
    }
    var focus = Array.isArray(survey.focusSkills) ? survey.focusSkills : [];
    form.querySelectorAll('input[name="focusSkills"]').forEach(function (input) { input.checked = focus.indexOf(input.value) >= 0; });
    limitFocusSkills();
  }

  /** F1-5: at most two focus skills — the third box is disabled until one is unticked. */
  function limitFocusSkills() {
    var boxes = form.querySelectorAll('input[name="focusSkills"]');
    var ticked = Array.prototype.filter.call(boxes, function (box) { return box.checked; }).length;
    boxes.forEach(function (box) { box.disabled = !box.checked && ticked >= 2; });
  }
  form.addEventListener('change', function (event) {
    if (event.target && event.target.name === 'focusSkills') limitFocusSkills();
  });

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
  goHome.addEventListener('click', async function () {
    if (!noTest) return window.location.assign('/');
    // [JLAS-W0108] JLPT path on: the path's own start route decides (confirm FROM_ZERO, resume, …).
    if (afterSurveyTarget) return window.location.assign(afterSurveyTarget);
    if (savedSurvey && savedSurvey.goal !== 'JLPT') {
      return window.location.assign('/learning/goals/' + encodeURIComponent(savedSurvey.goal) + '/survey');
    }
    try { await api('/api/learning/paths/from-zero', { method: 'POST', body: '{}' }); } catch (error) { showError(error.message); return; }
    window.location.assign('/learning/path');
  });
  function openWizard(startStep) {
    if (savedSurvey) fillSurvey(savedSurvey);
    // An old answer only the legacy survey offers (duration 3 "Dưới 6 tháng") stays visible so the step reads answered.
    if (jpathOn) {
      form.querySelectorAll('.survey-option[data-legacy-only]').forEach(function (option) {
        if (option.querySelector('input:checked')) option.hidden = false;
      });
    }
    applyEntryStatus();
    invitation.hidden = true;
    form.hidden = false;
    progressHeader.hidden = false;
    backButton.hidden = false;
    wizard.classList.remove('survey-wizard--invitation');
    currentIndex = startStep ? Math.max(0, stepIndex(startStep)) : 0;
    render();
  }
  document.getElementById('surveyEdit').addEventListener('click', function () { openWizard(null); });

  slides.forEach(function (slide) {
    slide.querySelectorAll('input').forEach(function (input) {
      input.addEventListener('change', function () {
        if (input.name === 'kanaChoice') kanaAutoFilled = false;
        if (input.name === 'duration') prefillKana();
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
    var body = buildSurveyBody(form);
    // [JLAS-W0108] Preflight C37: entry answers are saved only for a JLPT goal with the path on.
    var preference = null;
    if (jpathOn && Flow && body.goal === 'JLPT') {
      preference = Flow.buildPreferenceBody({ studied: checkedValue('studied'), kanaChoice: checkedValue('kanaChoice'),
        skillDays: dayState.skillDays, restDays: dayState.restDays });
      if (preference.error) {
        var missing = preference.error === 'kana' ? 'kana' : 'days';
        currentIndex = Math.max(0, stepIndex(missing));
        render();
        showError(missing === 'kana' ? 'Hãy chọn có ôn lại bảng chữ cái hay không.' : 'Hãy chọn đúng 2 ngày kỹ năng.');
        return;
      }
    }
    nextButton.disabled = true; nextButton.querySelector('span').textContent = 'Đang lưu khảo sát...';
    try {
      var data = await api('/api/assessment/survey', { method: 'POST', body: JSON.stringify(body) });
      if (preference) {
        entryStatus = await api('/api/learning/jlpt/entry-preferences', { method: 'PUT', body: JSON.stringify(preference) });
      }
      showInvitation(data.survey);
    }
    catch (error) {
      var msg = error && error.message ? error.message : '';
      if (msg.indexOf('Ngày thi') !== -1 || msg.indexOf('examDate') !== -1) {
        var errEl = document.getElementById('surveyExamDateError');
        if (errEl) {
          errEl.textContent = msg;
          errEl.hidden = false;
        }
        var active = visibleSlides();
        var targetIndex = -1;
        for (var i = 0; i < active.length; i++) {
          if (active[i].dataset.surveyStep === 'target') {
            targetIndex = i;
            break;
          }
        }
        if (targetIndex !== -1) {
          currentIndex = targetIndex;
          render();
        }
        var examDateInput = document.getElementById('surveyExamDateInput');
        if (examDateInput) examDateInput.focus();
      } else {
        showError(msg);
      }
    }
    finally { nextButton.disabled = false; nextButton.querySelector('span').textContent = 'Hoàn thành khảo sát'; }
  });
  var examDateInput = document.getElementById('surveyExamDateInput');
  if (examDateInput) {
    initExamDateConstraints(examDateInput);
    examDateInput.addEventListener('input', function () {
      var errEl = document.getElementById('surveyExamDateError');
      if (errEl) {
        errEl.textContent = '';
        errEl.hidden = true;
      }
    });
  }
  function loadJpathConfig() {
    if (!Flow) return Promise.resolve();
    return api('/api/learning/jlpt/config').then(function (config) {
      if (config) jpathConfig = config;
    }).catch(function () { jpathConfig = { enabled: false }; }).then(function () {
      jpathOn = Boolean(jpathConfig && jpathConfig.enabled);
    });
  }

  function loadSurvey() {
    return api('/api/assessment/survey').then(async function (data) {
      var survey = data && data.completed && data.survey ? data.survey : null;
      if (jpathOn) {
        try { entryStatus = await api('/api/learning/jlpt/entry-status'); } catch (ignore) { entryStatus = null; }
      }
      // ?edit=1|goal (settings: restart / change goal) and ?edit=days (start route without entry answers, C7).
      var startStep = jpathOn ? Flow.editStartStep(editParam, {
        studied: survey ? (survey.selfReportedLevel === 'FROM_ZERO' ? 'NO' : 'YES') : null,
        kanaChoice: entryStatus && entryStatus.preferenceDone ? entryStatus.kanaChoice : null
      }) : null;
      if (survey) savedSurvey = survey;
      if (startStep) openWizard(startStep);
      else if (survey) showInvitation(survey);
    }).catch(function (error) { showError(error.message); });
  }

  loadJpathConfig().then(function () {
    applyFlagVisibility();
    setupDayPicker();
    render();
    return loadSurvey();
  });

  if (typeof module !== 'undefined' && module.exports) {
    module.exports = {
      buildSurveyBody: buildSurveyBody,
      getVietnamToday: getVietnamToday,
      formatDateISO: formatDateISO,
      getExamDateConstraints: getExamDateConstraints,
      initExamDateConstraints: initExamDateConstraints,
      fillSurvey: fillSurvey
    };
  }
})();
