/*
 * [JLAS-W0108] "Demo Learning" panel for the JLPT path pages (Review 3 demo). It appears only when the server
 * exposes /api/dev/learning (mission.dev-tools=true). It never gets answers: it asks the server to answer the
 * caller's open check through the real answer/submit flow, so scoring, progress and CP run as by hand.
 */
(function (root) {
  'use strict';

  var CSS = '.mission-demo{position:fixed;right:16px;bottom:16px;z-index:1450;width:min(300px,calc(100vw - 32px));'
    + 'border-radius:16px;background:#17202b;color:#fff;box-shadow:0 18px 50px rgba(0,0,0,.3);font-size:.84rem}'
    + '.mission-demo summary{padding:12px 14px;cursor:pointer;font-weight:700;list-style:none}'
    + '.mission-demo summary::-webkit-details-marker{display:none}'
    + '.mission-demo__body{display:grid;gap:8px;max-height:min(70vh,560px);overflow-y:auto;padding:0 14px 14px}'
    + '.mission-demo__group{display:grid;grid-template-columns:1fr 1fr;gap:6px}'
    + '.mission-demo__label{margin-top:4px;color:rgba(255,255,255,.6);font-size:.74rem;text-transform:uppercase;letter-spacing:.08em}'
    + '.mission-demo button{padding:8px 10px;border:1px solid rgba(255,255,255,.16);border-radius:10px;'
    + 'background:rgba(255,255,255,.06);color:#fff;cursor:pointer;font:inherit;text-align:left}'
    + '.mission-demo button:hover{background:rgba(255,255,255,.14)}.mission-demo button:disabled{opacity:.5;cursor:wait}'
    + '.mission-demo__status{color:rgba(255,255,255,.75);font-size:.76rem}.mission-demo:not([open]){width:auto}'
    + '@media (max-width:640px){.mission-demo{bottom:calc(84px + env(safe-area-inset-bottom));'
    + 'max-height:calc(100dvh - 180px);overflow-y:auto}}';

  var KIND_NAMES = {
    MICRO_CHECK: 'Kiểm tra nhanh', GROUP_EXERCISE: 'Bài tập nhóm', KANA_ROW: 'Kiểm tra hàng kana',
    WEEK_KNOWN: 'Kiểm tra tuần', SKILL_PRACTICE: 'Luyện Đọc/Nghe', SKILL_DICTATION: 'Chép chính tả',
    SKILL_SECTION: 'Phần thi kỹ năng', SKILL_LESSON: 'Bài kỹ năng', GRAMMAR_PRACTICE: 'Luyện ngữ pháp',
    GRAMMAR_REVIEW: 'Ôn ngữ pháp', FREE_QUIZ: 'Ôn tập tự chọn'
  };

  function headers() {
    var auth = root.JlasAuth;
    return auth && typeof auth.authHeaders === 'function' ? auth.authHeaders() : { 'Content-Type': 'application/json' };
  }

  function call(method, url, body) {
    return root.fetch(url, { method: method, headers: headers(), body: body ? JSON.stringify(body) : undefined })
      .then(function (response) {
        return response.json().catch(function () { return {}; }).then(function (json) {
          if (!response.ok || json.success === false) throw new Error(json.message || ('Mã lỗi ' + response.status));
          return json;
        });
      });
  }

  function resultText(r) {
    var text = 'Kết quả: ' + r.correct + '/' + r.total + ' câu đúng (' + r.scorePercent + '%)';
    if (r.passed === true) text += ', đạt';
    if (r.passed === false) text += ', chưa đạt';
    text += r.cpAwarded ? ', +' + r.cpAwarded + ' CP.' : '.';
    if (r.tooFast) text += ' Hệ thống đánh dấu "làm quá nhanh" nên không thưởng CP; lần sau chờ vài giây rồi bấm.';
    return text;
  }

  function build(state) {
    var style = document.createElement('style');
    style.textContent = CSS;
    document.head.appendChild(style);
    var panel = document.createElement('details');
    panel.className = 'mission-demo';
    panel.setAttribute('data-learning-demo', '');
    var summary = document.createElement('summary');
    summary.textContent = '🧪 Demo Learning';
    var body = document.createElement('div');
    body.className = 'mission-demo__body';
    var status = document.createElement('div');
    status.className = 'mission-demo__status';
    status.setAttribute('role', 'status');
    var buttons = [];

    function label(text) {
      var node = document.createElement('div');
      node.className = 'mission-demo__label';
      node.textContent = text;
      return node;
    }

    function button(text, handler) {
      var node = document.createElement('button');
      node.type = 'button';
      node.textContent = text;
      buttons.push(node);
      node.addEventListener('click', function () {
        buttons.forEach(function (b) { b.disabled = true; });
        status.textContent = 'Đang xử lý…';
        Promise.resolve().then(handler).then(function (text) {
          status.textContent = text + ' Đang tải lại trang…';
          root.setTimeout(function () { root.location.reload(); }, 2500);
        }).catch(function (error) {
          status.textContent = 'Lỗi: ' + error.message;
          buttons.forEach(function (b) { b.disabled = false; });
        });
      });
      return node;
    }

    var check = state.openCheck;
    if (state.protectedAccount) {
      status.textContent = 'Đây là tài khoản chính: nút demo bị khoá. Hãy mở profile Chrome của tài khoản demo.';
      body.append(status);
    } else if (!check) {
      status.textContent = 'Chưa có bài kiểm tra nào đang mở. Mở bài (kiểm tra nhanh, kiểm tra tuần, Đọc/Nghe…) rồi tải lại trang.';
      body.append(status);
    } else {
      var finish = function (mode) {
        return function () {
          return call('POST', '/api/dev/learning/checks/' + encodeURIComponent(check.token) + '/finish', { mode: mode })
            .then(function (json) { return resultText(json.data); });
        };
      };
      var group = document.createElement('div');
      group.className = 'mission-demo__group';
      group.append(button('Trả lời đúng hết và nộp', finish('PASS')), button('Trả lời sai hết và nộp', finish('FAIL')));
      body.append(label((KIND_NAMES[check.kind] || check.kind) + ' · ' + check.questions + ' câu'), group);
      if (check.timerOn) body.append(label('Đồng hồ'), button('Để hết giờ mọi câu và nộp', finish('TIMEOUT')));
      status.textContent = 'Chấm điểm, tiến độ và CP do hệ thống tự tính.';
      body.append(status);
    }
    panel.append(summary, body);
    document.body.appendChild(panel);
    return panel;
  }

  function start() {
    if (!root.JlasAuth || !root.JlasAuth.isLoggedIn || !root.JlasAuth.isLoggedIn()) return Promise.resolve(null);
    return call('GET', '/api/dev/learning/state')
      .then(function (json) { return build(json.data || {}); })
      .catch(function () { return null; /* demo tools disabled */ });
  }

  if (typeof module !== 'undefined' && module.exports) module.exports = { resultText: resultText, start: start };
  else start();
})(typeof window !== 'undefined' ? window : globalThis);
