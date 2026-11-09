(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JlptResultPage = api;
  if (root && root.document) {
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', api.mount, { once: true });
    else api.mount();
  }
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';

  function esc(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function questionRows(exam, answers) {
    var parts = Array.isArray(exam && exam.parts) ? exam.parts : [];
    return parts.map(function (part) {
      var mondai = Array.isArray(part.mondai) ? part.mondai : [];
      var questions = mondai.reduce(function (all, group) { return all.concat(group.questions || []); }, []);
      var rows = questions.filter(function (q) { return q.correctOption != null; }).map(function (q) {
        var selected = answers && answers[String(q.id)] != null ? Number(answers[String(q.id)]) : null;
        var correct = Number(q.correctOption);
        var options = Array.isArray(q.options) ? q.options : [];
        var selectedOption = options.find(function (o) { return Number(o.number) === selected; });
        var correctOption = options.find(function (o) { return Number(o.number) === correct; });
        var right = selected === correct;
        return '<article class="jrr-question ' + (right ? 'is-correct' : selected == null ? 'is-unanswered' : 'is-wrong') + '">' +
          '<div class="jrr-question__head"><span>' + esc(q.number) + '</span><strong>' + esc(q.text || 'Câu hỏi') + '</strong>' +
          '<b>' + (right ? 'Đúng' : selected == null ? 'Bỏ trống' : 'Chưa đúng') + '</b></div>' +
          '<p>Bạn chọn: <strong>' + esc(selectedOption ? selectedOption.text : 'Chưa trả lời') + '</strong></p>' +
          (!right ? '<p>Đáp án đúng: <strong>' + esc(correctOption ? correctOption.text : '—') + '</strong></p>' : '') +
          (q.explanation ? '<details><summary>Xem giải thích</summary><p>' + esc(q.explanation) + '</p></details>' : '') +
          '</article>';
      }).join('');
      return '<section class="jrr-part"><header><span>' + esc(part.code) + '</span><h2>' + esc(part.title) + '</h2></header>' +
        (rows || '<p>Phần này chưa có câu hỏi chấm được.</p>') + '</section>';
    }).join('');
  }
  function render(data) {
    data = data || {};
    var result = data.result || {};
    var passed = result.passed === true;
    var sections = result.sections && typeof result.sections === 'object' ? Object.keys(result.sections).map(function (key) {
      var section = result.sections[key];
      return '<div class="jrr-section"><div><span>' + esc(key) + '</span><strong>' + esc(section.correctCount) + '/' + esc(section.questionCount) + '</strong></div>' +
        '<progress max="100" value="' + Math.max(0, Math.min(100, Number(section.scorePercent) || 0)) + '"></progress><small>' + esc(section.scorePercent) + '%</small></div>';
    }).join('') : '';
    var level = esc(result.level || 'JLPT');
    var passAction = passed ? '<a class="jrr-button jrr-button--primary" href="/jlpt/certificate?attemptId=' + encodeURIComponent(result.attemptId) + '">Mở chứng nhận kết quả</a>' : '';
    return '<main class="jrr-page"><header class="jrr-top"><a href="/jlpt?level=' + encodeURIComponent(result.level || 'N5') + '">← Thư viện đề</a>' +
      '<span>KotsuKotsu · Hồ sơ thi thử</span></header>' +
      '<section class="jrr-hero ' + (passed ? 'is-pass' : 'is-fail') + '"><div class="jrr-seal" aria-hidden="true">' + (passed ? '合' : '学') + '</div>' +
      '<p class="jrr-eyebrow">KẾT QUẢ ĐÃ LƯU · LƯỢT #' + esc(result.attemptId) + '</p>' +
      '<h1>' + (passed ? 'Đạt bài thi thử ' : 'Chưa đạt bài thi thử ') + level + '</h1>' +
      '<p class="jrr-lede">' + (passed ? 'Bạn đã đạt ngưỡng đánh giá nội bộ cho đề thi thử này.' : 'Hãy xem lại từng phần và thử lại khi bạn sẵn sàng.') + '</p>' +
      '<div class="jrr-score"><strong>' + esc(result.scorePercent) + '<small>%</small></strong><span>' + esc(result.correctCount) + ' / ' + esc(result.questionCount) + ' câu đúng</span></div>' +
      '<p class="jrr-disclaimer">Đây là kết quả tự đánh giá của bài thi thử KotsuKotsu, không phải kết quả kỳ thi JLPT chính thức.</p>' +
      '<div class="jrr-actions">' + passAction + '<a class="jrr-button" href="/jlpt?level=' + encodeURIComponent(result.level || 'N5') + '">Thi thử lại</a>' +
      '<a class="jrr-text-link" href="/learning/path/v2/summary">Về tổng kết lộ trình</a></div></section>' +
      '<section class="jrr-breakdown"><header><p>Kết quả theo phần</p><h2>Điểm mạnh và phần cần ôn</h2></header>' +
      (sections || '<p>Chưa có dữ liệu phân tích theo phần.</p>') + '</section>' +
      '<section class="jrr-review"><header><p>Đối chiếu bài làm đã lưu</p><h2>Xem lại câu trả lời</h2></header>' +
      questionRows(data.exam, data.answers) + '</section></main>';
  }
  function error(message) {
    return '<main class="jrr-page"><section class="jrr-empty"><h1>Chưa mở được kết quả thi</h1><p>' + esc(message || 'Vui lòng thử lại.') +
      '</p><a class="jrr-button" href="/jlpt">Về thư viện đề</a></section></main>';
  }
  function mount() {
    var root = document.getElementById('jlptResultRoot');
    if (!root || !window.JlasAuth || !window.JlasAuth.requireLogin('/jlpt/result' + window.location.search)) return;
    var requested = new URLSearchParams(window.location.search).get('level');
    var level = /^N[1-5]$/.test(requested || '') ? requested : 'N5';
    root.setAttribute('aria-busy', 'true');
    fetch('/api/jlpt/mock-attempts/latest?level=' + encodeURIComponent(level), { headers: window.JlasAuth.authHeaders() })
      .then(function (response) { return response.json().then(function (body) {
        if (!response.ok || body.success === false) throw new Error(body.message || 'Không tải được lượt thi.');
        if (!body.data) { root.innerHTML = error('Bạn chưa có lượt thi thử ' + level + ' đã lưu.'); return null; }
        return fetch('/api/jlpt/mock-attempts/' + encodeURIComponent(body.data.attemptId), { headers: window.JlasAuth.authHeaders() });
      }); })
      .then(function (response) {
        if (!response) return null;
        return response.json().then(function (body) {
          if (!response.ok || body.success === false || !body.data) throw new Error(body.message || 'Không tải được chi tiết lượt thi.');
          root.innerHTML = render(body.data);
        });
      }).catch(function (e) { root.innerHTML = error(e.message); })
      .finally(function () { root.setAttribute('aria-busy', 'false'); });
  }
  return { render: render, questionRows: questionRows, error: error, mount: mount };
});
