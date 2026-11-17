(function () {
  'use strict';
  var ACTIVE_ATTEMPT_KEY = 'jlasSmartTestAttemptId';
  var slides = Array.from(document.querySelectorAll('.survey-slide'));
  var nextButton = document.getElementById('surveyNextButton');
  var backButton = document.getElementById('surveyBackButton');
  var notice = document.getElementById('surveyNotice');
  var loading = document.getElementById('surveyLoading');
  var currentIndex = 0;
  var savedSurvey = null;
  var wizard = document.getElementById('surveyWizard');
  var form = document.getElementById('smartTestSurveyForm');
  var progressHeader = document.querySelector('.survey-progress-header');
  var invitation = document.getElementById('surveyInvitation');
  if (form) form.hidden = true;
  if (progressHeader) progressHeader.hidden = true;
  var startTest = document.getElementById('surveyStartTest');
  var goHome = document.getElementById('surveyGoHome');
  // [JLAS-W0108] A8: "Để sau" returns to the page the learner came from (same site, not the survey itself).
  var later = document.getElementById('surveyLater');
  if (later) {
    try {
      var ref = document.referrer ? new URL(document.referrer) : null;
      if (ref && ref.origin === window.location.origin && ref.pathname.indexOf('/survey') !== 0
          && ref.pathname.indexOf('/learning') !== 0) later.href = ref.pathname + ref.search;
    } catch (e) { /* keep the home link */ }
  }
  var noTest = false;
  if (!window.JlasAuth || !window.JlasAuth.requireLogin('/survey')) return;

  // [JLAS-W0108] Task 23 - JLPT path (flag learning.jlpt-path.enabled, read from GET /api/learning/jlpt/config; any
  // failure counts as off). Off: the old 6-step survey, untouched. On: goal first, kana and skill-day steps, entry
  // answers saved to PUT /api/learning/jlpt/entry-preferences (JLPT goal only).
  var Flow = window.JpathSurveyFlow;
  var jpathConfig = { enabled: false };
  var jpathOn = false;
  // [JLAS-W0108] Communication path on (its goals API answers): BA/TRAVEL/DAILY_LIFE leave after the goal step.
  var commOn = false;
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
    var steps = Flow ? Flow.visibleSteps({ goal: goal, studied: studied }, jpathOn, commOn) : legacySteps(goal, studied);
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
    // [JLAS-W0108] F11: the total depends on the answers (branches), so only the step number is shown
    document.getElementById('surveyStepCounter').textContent = 'Bước ' + (currentIndex + 1);
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
  async function api(url, options) { var response = await httpFetch(url, Object.assign({ headers: window.JlasAuth.authHeaders() }, options || {})); var payload; try { payload = await response.json(); } catch (ignore) { payload = null; } if (!response.ok || !payload || !payload.success) throw new Error(payload && payload.message ? payload.message : 'Không thể kết nối tới máy chủ.'); return payload.data; }

  function showInvitation(survey, justSaved) {
    savedSurvey = survey;
    if (justSaved && commOn && survey && survey.goal === 'JLPT') {
      // owner 17/11: one main goal — JLPT as the main goal pauses the communication paths (progress kept)
      api('/api/learning/comm/goals/pause-for-jlpt', { method: 'POST' }).catch(function () { /* nothing to pause */ });
    }
    if (justSaved && commOn && survey && (survey.goal === 'BA' || survey.goal === 'TRAVEL' || survey.goal === 'DAILY_LIFE')) {
      // a new communication answer goes straight to the communication survey; a returning learner who opened
      // Smart Test from the menu stays here (owner 17/11: the menu entry must not bounce them to the path)
      window.location.assign('/learning/comm/survey?goal=' + encodeURIComponent(survey.goal));
      return;
    }
    var fromZero = survey.selfReportedLevel === 'FROM_ZERO';
    // [JLAS-W0108] BA/TRAVEL/DAILY_LIFE are survey-only (owner decision 2026-10-14); none may offer Smart Test.
    var surveyOnlyGoal = survey.goal === 'BA' || survey.goal === 'TRAVEL' || survey.goal === 'DAILY_LIFE';
    var pathStatus = entryStatus ? entryStatus.pathStatus : null;
    afterSurveyTarget = jpathOn && Flow ? Flow.afterSurveyUrl(jpathConfig, survey, pathStatus, {
      edit: editParam,
      smartTestStatus: entryStatus && entryStatus.smartTest ? entryStatus.smartTest.status : null,
      kanaChoice: checkedValue('kanaChoice') || (entryStatus ? entryStatus.kanaChoice : null)
    }) : null;
    // owner 07/11: kana not remembered -> no Smart Test (it cannot be read), the path starts at N5 with kana review
    if (checkedValue('kanaChoice') === 'REVIEW' || (entryStatus && entryStatus.kanaChoice === 'REVIEW')) fromZero = true;
    var keepsPath = Boolean(afterSurveyTarget) && Flow.keepsPath(pathStatus);
    var resumesSmartTest = Boolean(afterSurveyTarget) && !keepsPath && !fromZero;
    var needsPathOnly = fromZero || surveyOnlyGoal || Boolean(afterSurveyTarget);
    if (keepsPath) {
      document.getElementById('inviteTitle').textContent = 'Lộ trình JLPT của bạn vẫn được giữ!';
      document.getElementById('inviteText').textContent = 'Tiến độ học vẫn được giữ nguyên. Bạn có thể làm lại Smart Test để đo trình độ hiện tại; '
        + 'ở trang kết quả, KotsuKotsu so cấp mới với lộ trình và cho bạn đổi cấp nếu cần.';
    } else if (resumesSmartTest) {
      document.getElementById('inviteTitle').textContent = 'Kitsu đã lưu lịch học của bạn!';
      document.getElementById('inviteText').textContent = 'Bạn đã có bài Smart Test. Tiếp tục để xem kết quả và xác nhận lộ trình JLPT.';
    } else if (surveyOnlyGoal) {
      document.getElementById('inviteTitle').textContent = 'Chọn tình huống bạn muốn luyện!';
      document.getElementById('inviteText').textContent = 'Mục tiêu giao tiếp không dùng Smart Test. KotsuKotsu sẽ dùng khảo sát chủ đề để tạo lộ trình giao tiếp phù hợp.';
    } else if (fromZero) {
      document.getElementById('inviteTitle').textContent = 'Bạn sẽ bắt đầu từ con số 0!';
      document.getElementById('inviteText').textContent = 'Bạn chưa từng học tiếng Nhật nên không cần làm bài kiểm tra. KotsuKotsu sẽ xây lộ trình học từ đầu cho bạn.';
    } else {
      document.getElementById('inviteTitle').textContent = 'Sẵn sàng khám phá trình độ của bạn?';
      document.getElementById('inviteText').textContent = 'Làm Smart Test cùng Kitsu để tìm điểm bắt đầu phù hợp. Nếu chưa sẵn sàng, bạn có thể làm bài sau.';
    }
    // owner 17/11: a learner who keeps a JLPT path may still retake Smart Test (the result page offers the level move)
    startTest.hidden = needsPathOnly && !keepsPath;
    var startLabel = startTest.querySelector('span');
    if (startLabel) startLabel.textContent = keepsPath ? 'Làm lại Smart Test' : 'Bắt đầu Smart Test';
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
    if (jpathJlpt) body.selfReportedLevel = Flow.selfReportedLevelFor(studied, selfLevelEl ? selfLevelEl.value : null);

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
    finally { startTest.disabled = false; }
  }

  startTest.addEventListener('click', function () { if (savedSurvey) beginTest(savedSurvey); });
  goHome.addEventListener('click', async function () {
    if (!noTest) return window.location.assign('/');
    // [JLAS-W0108] JLPT path on: the path's own start route decides (confirm FROM_ZERO, resume, …).
    if (afterSurveyTarget) return window.location.assign(afterSurveyTarget);
    // [JLAS-W0108] Owner 07/11: only the new paths remain; /learning routes to the JLPT or communication path.
    window.location.assign('/learning');
  });
  /** Owner 17/11 (mentor review): changing the main goal says up front what happens to the current paths. */
  function showGoalChangeNote() {
    var slide = form.querySelector('[data-survey-step="goal"]');
    if (!slide || slide.querySelector('.survey-goal-change')) return;
    var note = document.createElement('div');
    note.className = 'survey-goal-change';
    note.setAttribute('role', 'note');
    note.setAttribute('data-notice-keep', '');
    note.innerHTML = '<strong>Bạn đang đổi mục tiêu chính.</strong> '
      + '<span data-goal-change-detail>Lộ trình đang học sẽ tạm dừng, tiến độ giữ nguyên; chọn lại mục tiêu cũ là học tiếp.</span>';
    slide.insertBefore(note, slide.firstChild);
    api('/api/learning/comm/goals/overview').then(function (v) {
      var parts = [];
      if (v && v.main) parts.push('Mục tiêu chính hiện tại: ' + (v.main.kind === 'JLPT' ? 'Lộ trình ' : 'Giao tiếp · ') + v.main.nameVi + ' — sẽ tạm dừng, tiến độ giữ nguyên.');
      if (v && v.secondary) parts.push('Mục tiêu phụ: Giao tiếp · ' + v.secondary.nameVi + ' — chọn mục tiêu này thì nó được nâng lên làm chính và giữ tiến độ.');
      parts.push('Chọn lại một mục tiêu đã học trước đây là học tiếp từ chỗ cũ.');
      var d = note.querySelector('[data-goal-change-detail]');
      if (d) d.textContent = ' ' + parts.join(' ');
    }, function () { /* the short note stays */ });
  }

  function openWizard(startStep) {
    if (savedSurvey) fillSurvey(savedSurvey);
    // An old answer only the legacy survey offers (duration 3 "Dưới 6 tháng") stays visible so the step reads answered.
    if (jpathOn) {
      form.querySelectorAll('.survey-option[data-legacy-only]').forEach(function (option) {
        if (option.querySelector('input:checked')) option.hidden = false;
      });
    }
    applyEntryStatus();
    if (editParam === 'goal') showGoalChangeNote();
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
      showInvitation(data.survey, true);
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
  function httpFetch(url, options) {
    var fn = (typeof window !== 'undefined' && typeof window.fetch === 'function')
      ? window.fetch.bind(window)
      : (typeof fetch === 'function' ? fetch : null);
    return fn ? fn(url, options) : Promise.reject(new Error('Không thể kết nối tới máy chủ.'));
  }

  function finishLoading() {
    if (loading) loading.hidden = true;
  }

  function showFormReady() {
    finishLoading();
    if (form) form.hidden = false;
    if (progressHeader) progressHeader.hidden = false;
    render();
  }

  function initSurvey() {
    var canFetch = (typeof window !== 'undefined' && typeof window.fetch === 'function') || typeof fetch === 'function';
    if (!canFetch) {
      applyFlagVisibility();
      setupDayPicker();
      showFormReady();
      return Promise.resolve();
    }
    var configPromise = Flow
      ? api('/api/learning/jlpt/config').then(function (config) {
          if (config) jpathConfig = config;
        }).catch(function () { jpathConfig = { enabled: false }; })
      : Promise.resolve();
    var commPromise = Flow
      ? httpFetch('/api/learning/comm/goals', { headers: window.JlasAuth.authHeaders() })
          .then(function (r) { commOn = !!(r && r.ok); }, function () { commOn = false; })
      : Promise.resolve();
    var surveyPromise = api('/api/assessment/survey').catch(function (error) { return { __error: error }; });
    var entryPromise = Flow
      ? api('/api/learning/jlpt/entry-status').catch(function () { return null; })
      : Promise.resolve(null);

    return Promise.all([configPromise, commPromise, surveyPromise, entryPromise]).then(function (results) {
      jpathOn = Boolean(jpathConfig && jpathConfig.enabled);
      var surveyRes = results[2];
      entryStatus = jpathOn ? (results[3] || null) : null;

      applyFlagVisibility();
      setupDayPicker();

      if (surveyRes && surveyRes.__error) {
        showFormReady();
        showError(surveyRes.__error.message);
        return;
      }

      var survey = surveyRes && surveyRes.completed && surveyRes.survey ? surveyRes.survey : null;
      // ?edit=1|goal (settings: restart / change goal) and ?edit=days (start route without entry answers, C7).
      var startStep = jpathOn && Flow ? Flow.editStartStep(editParam, {
        studied: survey ? (survey.selfReportedLevel === 'FROM_ZERO' ? 'NO' : 'YES') : null,
        kanaChoice: entryStatus && entryStatus.preferenceDone ? entryStatus.kanaChoice : null
      }) : null;
      if (survey) savedSurvey = survey;
      if (startStep) {
        finishLoading();
        openWizard(startStep);
      } else if (survey) {
        finishLoading();
        showInvitation(survey);
      } else {
        showFormReady();
      }
    });
  }

  var initReady = initSurvey();

  if (typeof module !== 'undefined' && module.exports) {
    module.exports = {
      buildSurveyBody: buildSurveyBody,
      getVietnamToday: getVietnamToday,
      formatDateISO: formatDateISO,
      getExamDateConstraints: getExamDateConstraints,
      initExamDateConstraints: initExamDateConstraints,
      fillSurvey: fillSurvey,
      ready: initReady
    };
  }
})();
