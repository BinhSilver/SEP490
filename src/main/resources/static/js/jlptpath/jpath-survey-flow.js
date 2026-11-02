/* [JLAS-W0108] jpath-survey-flow.js — survey step order and the hand-offs of the JLPT path (pure rules, no DOM).
 *
 * window.JpathSurveyFlow:
 *   visibleSteps(answers, jpathOn)        step keys in order; answers = {goal, studied}
 *   defaultKanaChoice(months)             'REVIEW' below 3 months of study, else null
 *   selfReportedLevelFor(studied)         'UNKNOWN' when studied ('YES'), else 'FROM_ZERO'
 *   buildPreferenceBody(answers)          PUT /api/learning/jlpt/entry-preferences body, or {error:'kana'|'days'}
 *   targetDisabled(level, config)         target level not released yet (only when the path is on)
 *   smartTestHandoffUrl(config, goal, id) confirmation page after Smart Test, or null (legacy flow)
 *   afterSurveyUrl(config, survey, pathStatus, context) where "Xem lộ trình" goes, or null (Smart Test invitation /
 *                                         legacy flow); context = {edit, smartTestStatus}
 *   keepsPath(pathStatus)                 the learner already has an ACTIVE or PAUSED path
 *   editStartStep(edit, answers)          first step for /survey?edit=1|goal|days, or null
 * A missing config or enabled=false always means the legacy behaviour.
 */
(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathSurveyFlow = api;
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function () {
  'use strict';

  var KANA_REVIEW_BELOW_MONTHS = 3;

  function isOn(config) {
    return Boolean(config && config.enabled);
  }

  function visibleSteps(answers, jpathOn) {
    var a = answers || {};
    var studied = a.studied === 'YES';
    var steps;
    if (!jpathOn) {
      steps = ['studied'];
      if (studied) steps.push('duration', 'level');
      steps.push('goal');
      if (a.goal === 'JLPT') steps.push('target');
      steps.push('minutes');
      return steps;
    }
    steps = ['goal', 'studied'];
    // Before the goal is chosen the JLPT order is counted, the goal most learners pick.
    if (!a.goal || a.goal === 'JLPT') {
      // While "studied" is unanswered the longer branch is counted, so the first screen reads 1/7.
      if (studied || !a.studied) steps.push('duration', 'kana');
      steps.push('target', 'minutes', 'days');
      return steps;
    }
    if (studied) steps.push('duration', 'level');
    steps.push('minutes');
    return steps;
  }

  function defaultKanaChoice(months) {
    if (months === null || months === undefined || months === '') return null;
    var value = Number(months);
    return isFinite(value) && value < KANA_REVIEW_BELOW_MONTHS ? 'REVIEW' : null;
  }

  function selfReportedLevelFor(studied) {
    return studied === 'YES' ? 'UNKNOWN' : 'FROM_ZERO';
  }

  function buildPreferenceBody(answers) {
    var a = answers || {};
    var studied = a.studied === 'YES';
    var skillDays = Array.isArray(a.skillDays) ? a.skillDays.slice() : [];
    var restDays = Array.isArray(a.restDays) ? a.restDays.slice() : [];
    if (studied && a.kanaChoice !== 'REMEMBER' && a.kanaChoice !== 'REVIEW') return { error: 'kana' };
    if (skillDays.length !== 2) return { error: 'days' };
    return {
      studiedBefore: studied,
      kanaChoice: studied ? a.kanaChoice : 'NONE',
      skillDays: skillDays,
      restDays: restDays
    };
  }

  function targetDisabled(level, config) {
    if (!isOn(config)) return false;
    var released = Array.isArray(config.releasedLevels) ? config.releasedLevels : [];
    return released.indexOf(level) < 0;
  }

  function smartTestHandoffUrl(config, goal, attemptId) {
    if (!isOn(config) || goal !== 'JLPT' || attemptId === null || attemptId === undefined || attemptId === '') return null;
    return '/learning/jlpt/confirm?source=SMART_TEST&assessmentAttemptId=' + encodeURIComponent(attemptId);
  }

  function keepsPath(pathStatus) {
    return pathStatus === 'ACTIVE' || pathStatus === 'PAUSED';
  }

  function afterSurveyUrl(config, survey, pathStatus, context) {
    if (!isOn(config) || !survey || survey.goal !== 'JLPT') return null;
    // Preflight C8: a learner whose path is ACTIVE/PAUSED does not retake Smart Test.
    if (keepsPath(pathStatus)) return '/learning/jlpt/start';
    if (survey.selfReportedLevel === 'FROM_ZERO') return '/learning/jlpt/start';
    // ?edit=days came from the start route (no entry answers yet): an existing Smart Test attempt goes back there
    // (→ confirm) instead of starting a new attempt. ?edit=1 after a restart still offers a fresh Smart Test.
    var c = context || {};
    if (c.edit === 'days' && (c.smartTestStatus === 'IN_PROGRESS' || c.smartTestStatus === 'SUBMITTED')) {
      return '/learning/jlpt/start';
    }
    return null;
  }

  function editStartStep(edit, answers) {
    if (edit === '1' || edit === 'goal') return 'goal';
    if (edit !== 'days') return null;
    var a = answers || {};
    // Preflight C7: a learner who studied but has no kana choice answers kana before the days.
    if (a.studied === 'YES' && a.kanaChoice !== 'REMEMBER' && a.kanaChoice !== 'REVIEW') return 'kana';
    return 'days';
  }

  return {
    visibleSteps: visibleSteps,
    defaultKanaChoice: defaultKanaChoice,
    selfReportedLevelFor: selfReportedLevelFor,
    buildPreferenceBody: buildPreferenceBody,
    targetDisabled: targetDisabled,
    smartTestHandoffUrl: smartTestHandoffUrl,
    afterSurveyUrl: afterSurveyUrl,
    keepsPath: keepsPath,
    editStartStep: editStartStep
  };
});
