(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.GoalSurveyPage = api;
  if (root && root.document) {
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', api.mount, { once: true });
    else api.mount();
  }
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';

  function escapeHtml(value) {
    return String(value == null ? '' : value).replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#039;');
  }

  function renderDefinition(definition) {
    var questions = (definition.questions || []).map(function (question) {
      var type = question.selectionType === 'SINGLE' ? 'radio' : 'checkbox';
      var options = (question.options || []).map(function (option) {
        return '<label class="survey-option goal-survey-option"><input type="' + type + '" name="' +
          escapeHtml(question.questionCode) + '" value="' + escapeHtml(option.optionCode) + '"' +
          (option.learningMode ? ' data-learning-mode="' + escapeHtml(option.learningMode) + '"' : '') + '>' +
          '<span><strong>' + escapeHtml(option.labelVi) + '</strong></span><i class="survey-radio"></i></label>';
      }).join('');
      return '<fieldset class="goal-survey-question" data-question-code="' + escapeHtml(question.questionCode) +
        '" data-required="' + question.required + '"><legend>' + escapeHtml(question.textVi) + '</legend>' +
        '<div class="survey-options">' + options + '</div></fieldset>';
    }).join('');
    return '<header class="survey-question"><div><span class="survey-eyebrow">Lộ trình cá nhân hóa</span><h1>' +
      escapeHtml(definition.displayNameVi) + '</h1><p>Chọn nội dung bạn muốn học. Lựa chọn này là nhu cầu, không phải kết luận năng lực.</p></div></header>' + questions;
  }

  function buildSubmission(learningMode, controls) {
    var grouped = {};
    (controls || []).forEach(function (control) {
      if (!control.checked) return;
      if (!grouped[control.questionCode]) grouped[control.questionCode] = [];
      if (grouped[control.questionCode].indexOf(control.optionCode) < 0) grouped[control.questionCode].push(control.optionCode);
    });
    return { learningMode: learningMode || null, answers: Object.keys(grouped).map(function (questionCode) {
      return { questionCode: questionCode, optionCodes: grouped[questionCode] };
    }) };
  }

  function nextAction(definition, response) {
    return response && response.assessmentRequirement === 'BRSE_FOUNDATION' ? '/smart-test' : '/learning/path/v2';
  }

  async function api(url, options) {
    var response = window.JlasAuth && window.JlasAuth.authFetch
      ? await window.JlasAuth.authFetch(url, options || {}) : await fetch(url, options || {});
    var payload = await response.json();
    if (!response.ok || !payload.success) throw new Error(payload.message || 'Không thể xử lý khảo sát.');
    return payload.data;
  }

  async function mount() {
    var root = document.getElementById('goalSurveyPage');
    if (!root) return;
    if (window.JlasAuth && !window.JlasAuth.requireLogin(window.location.pathname)) return;
    var goalCode = root.dataset.goalCode;
    var form = document.getElementById('goalSurveyForm');
    var content = document.getElementById('goalSurveyContent');
    var notice = document.getElementById('goalSurveyNotice');
    try {
      var definition = await api('/api/learning/v2/goals/' + encodeURIComponent(goalCode) + '/survey');
      content.innerHTML = renderDefinition(definition);
      form.addEventListener('submit', async function (event) {
        event.preventDefault();
        notice.hidden = true;
        var inputs = Array.from(form.querySelectorAll('input[type="radio"],input[type="checkbox"]'));
        var modeInput = inputs.find(function (input) { return input.checked && input.dataset.learningMode; });
        var request = buildSubmission(modeInput ? modeInput.dataset.learningMode : null, inputs.map(function (input) {
          return { questionCode: input.name, optionCode: input.value, checked: input.checked };
        }));
        try {
          var result = await api('/api/learning/v2/goals/' + encodeURIComponent(goalCode) + '/survey', {
            method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(request)
          });
          window.location.assign(nextAction(definition, result));
        } catch (error) { notice.textContent = error.message; notice.hidden = false; }
      });
    } catch (error) { notice.textContent = error.message; notice.hidden = false; }
  }

  return { escapeHtml: escapeHtml, renderDefinition: renderDefinition, buildSubmission: buildSubmission,
    nextAction: nextAction, mount: mount };
});
