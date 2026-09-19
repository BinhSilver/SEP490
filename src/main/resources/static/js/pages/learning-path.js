(function () {
  'use strict';
  if (!window.JlasAuth || !window.JlasAuth.requireLogin('/learning-path')) return;

  var notice = document.getElementById('lpNotice');
  var GOALS = { JLPT: 'Thi JLPT', BA: 'Kỹ sư cầu nối', TRAVEL: 'Du lịch', DAILY_LIFE: 'Đời sống hằng ngày' };
  var LEVELS = { FROM_ZERO: 'Bắt đầu từ số 0', UNKNOWN: 'Chưa rõ trình độ', N5: 'N5 · Nhập môn', N4: 'N4 · Cơ bản', N3: 'N3 · Trung cấp', N2: 'N2 · Nâng cao', N1: 'N1 · Thành thạo' };

  function showError(message) {
    notice.textContent = message || 'Không thể tải lộ trình. Vui lòng thử lại.';
    notice.hidden = false;
  }

  async function api(url) {
    var response = await fetch(url, { headers: window.JlasAuth.authHeaders() });
    var payload; try { payload = await response.json(); } catch (ignore) { payload = null; }
    if (!response.ok || !payload || !payload.success) throw new Error(payload && payload.message ? payload.message : 'Không thể kết nối tới máy chủ.');
    return payload.data;
  }

  function stepsFor(survey) {
    var fromZero = survey.selfReportedLevel === 'FROM_ZERO';
    var steps = [];
    if (fromZero) {
      steps.push('Làm quen bảng chữ cái Hiragana &amp; Katakana');
      steps.push('Nắm câu chào và mẫu câu giao tiếp đầu tiên');
    } else {
      steps.push('Ôn lại nền tảng ở mức ' + (LEVELS[survey.selfReportedLevel] || survey.selfReportedLevel));
    }
    steps.push('Xây nền tảng từ vựng &amp; ngữ pháp cốt lõi');
    steps.push('Luyện kỹ năng theo mục tiêu ' + (GOALS[survey.goal] || survey.goal));
    steps.push('Thực hành tình huống thực tế cùng Kitsu');
    return steps;
  }

  function render(survey) {
    document.getElementById('lpGoal').textContent = GOALS[survey.goal] || survey.goal || '—';
    document.getElementById('lpLevel').textContent = LEVELS[survey.selfReportedLevel] || survey.selfReportedLevel || '—';
    document.getElementById('lpMinutes').textContent = survey.dailyStudyMinutes ? survey.dailyStudyMinutes + ' phút' : '—';
    document.getElementById('lpSubtitle').textContent = survey.selfReportedLevel === 'FROM_ZERO'
      ? 'Bạn bắt đầu từ con số 0. J-LAS sẽ dẫn bạn từ bảng chữ cái đến giao tiếp tự tin.'
      : 'Bạn chưa cần đánh giá trình độ ngay. J-LAS gợi ý lộ trình để bạn bắt đầu học ngay hôm nay.';
    var list = document.getElementById('lpSteps');
    list.innerHTML = stepsFor(survey).map(function (label, index) {
      return '<li><span class="lp-step__index">' + (index + 1) + '</span><span class="lp-step__label">' + label + '</span></li>';
    }).join('');
  }

  api('/api/assessment/survey').then(function (data) {
    if (!data.completed || !data.survey) { window.location.assign('/survey'); return; }
    render(data.survey);
  }).catch(showError);
})();
