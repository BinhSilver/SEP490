(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.SmartTestResultRoute = api;
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';
  // [JLAS-W0108] Owner 2026-10-21: what the Smart Test result says about the path before it is created.
  // OD-34 unreleased target, OD-35 UNKNOWN far below the target, OD-37 why a Nhập môn result starts one stage lower.
  // The server still decides every route; this only words the result it already sent.
  var LEVELS = ['N5', 'N4', 'N3', 'N2', 'N1'];

  function rank(level) { return LEVELS.indexOf(String(level || '').toUpperCase()); }

  function notice(result) {
    var none = { kind: null, text: '', askConsent: false, blockCreate: false, stages: [] };
    if (!result || !result.targetLevel) return none;
    var target = String(result.targetLevel).toUpperCase();
    var assessed = String(result.estimatedOverallLevel || '').toUpperCase();
    var self = String(result.selfReportedLevel || '').toUpperCase();
    var band = String(result.abilityBand || '');
    var recommended = String(result.recommendedTarget || '').toUpperCase();
    if (rank(target) < 0 || rank(assessed) < 0) return none;

    if (result.targetReasonCode === 'PATH_NOT_SUPPORTED' && rank(recommended) >= 0 && rank(target) > rank(recommended)) {
      return { kind: 'PENDING_RELEASE', askConsent: false, blockCreate: true, stages: [],
        text: 'Lộ trình ' + target + ' chưa được phát hành. Kết quả Smart Test (' + assessed + ') đã được lưu; khi ' +
          target + ' mở, bạn tạo lộ trình từ kết quả này. Trong lúc chờ, bạn có thể làm khảo sát mới để học tới ' +
          recommended + '.' };
    }
    if (self === 'UNKNOWN' && rank(assessed) + 1 < rank(target)) {
      var stages = LEVELS.slice(rank(assessed), rank(target) + 1);
      return { kind: 'UNKNOWN_FAR', askConsent: true, blockCreate: false, stages: stages,
        text: 'Kết quả Smart Test của bạn là ' + assessed + ', còn mục tiêu ' + target + ' cách ' +
          (rank(target) - rank(assessed)) + ' cấp. Khi tạo lộ trình, bạn sẽ được đề nghị học lần lượt ' +
          stages.join(' → ') + '; nếu muốn mục tiêu gần hơn, hãy làm khảo sát mới.' };
    }
    if (/ Nhập môn$/.test(band)) {
      var start = rank(self) >= 0 ? self : assessed;
      if (rank(start) >= 0 && rank(start) < rank(target)) {
        return { kind: 'INTRO_STAGE', askConsent: false, blockCreate: false,
          stages: LEVELS.slice(rank(start), rank(target) + 1),
          // Owner 2026-10-25: the learner now chooses to study that stage again first (recommended) or go straight on.
          text: 'Kết quả ở mức Nhập môn: khi tạo lộ trình, bạn chọn học lại chặng ' + start + ' trước (khuyến nghị) rồi lên ' +
            target + ', hoặc vào thẳng.' };
      }
    }
    return none;
  }

  return { notice: notice };
});
