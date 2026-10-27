(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.SmartTestSurveyTargets = api;
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';
  // [JLAS-W0108] Task 11 - the survey never decides which JLPT targets are reachable. The server's
  // GET /api/assessment/survey/target-options is the only source; this module only turns that answer into a
  // view model. If the request fails the list is empty and every target stays disabled, never a hardcoded fallback.
  var STAGE_LABEL = { FOUNDATION: 'Bảng chữ cái' };
  var REASON_TEXT = {
    TARGET_BELOW_ASSESSED_LEVEL: 'Thấp hơn trình độ hiện tại của bạn — hãy chọn cấp cao hơn.',
    TARGET_TOO_FAR: 'Quá xa trình độ hiện tại — hãy chọn mục tiêu gần hơn trước.',
    PENDING_SMART_TEST: 'Sẽ được xác nhận sau khi bạn làm Smart Test.'
  };
  var NEUTRAL_REASON = 'Mục tiêu này chưa khả dụng với trình độ hiện tại.';
  var ASSESSED = ['UNKNOWN', 'FROM_ZERO', 'BELOW_N5', 'N5', 'N4', 'N3', 'N2', 'N1'];

  function stageLabel(levels) {
    return (Array.isArray(levels) ? levels : []).map(function (level) { return STAGE_LABEL[level] || level; }).join(' → ');
  }

  function describeOptions(options) {
    if (!Array.isArray(options)) return [];
    // The recommended target is whichever level the server names as the alternative for options it disallowed.
    var named = {};
    options.forEach(function (option) {
      if (option.allowed !== true && option.recommendedTarget) named[option.recommendedTarget] = true;
    });
    return options.map(function (option) {
      var allowed = option.allowed === true;
      return {
        level: option.targetLevel,
        disabled: !allowed,
        recommended: allowed && named[option.targetLevel] === true,
        reason: allowed ? '' : (REASON_TEXT[option.reasonCode] || NEUTRAL_REASON),
        stageLabel: stageLabel(option.stageLevels)
      };
    });
  }

  function assessedLevelFor(answers) {
    answers = answers || {};
    if (answers.studied === 'NO') return 'FROM_ZERO';
    return ASSESSED.indexOf(answers.selfLevel) >= 0 ? answers.selfLevel : 'UNKNOWN';
  }

  function mustClearSelection(selected, view) {
    if (!selected) return false;
    var match = (view || []).filter(function (option) { return option.level === selected; })[0];
    return !match || match.disabled;
  }

  return { describeOptions: describeOptions, assessedLevelFor: assessedLevelFor, mustClearSelection: mustClearSelection,
    stageLabel: stageLabel };
});
