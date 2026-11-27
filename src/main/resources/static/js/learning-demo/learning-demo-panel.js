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
    + '@media (max-width:640px){.mission-demo{top:76px;bottom:auto;'
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

  var STEP_NAMES = { WORDS: 'Từ vựng', LISTEN: 'Nghe hội thoại', ROLEPLAY: 'Nhập vai', SPEAK: 'Luyện nói' };

  function stepText(d) {
    var text = 'Đã xong bước ' + (STEP_NAMES[d.stepDone] || d.stepDone) + '.';
    if (d.situationDone) text += ' Tình huống đã xong cả 4 bước.';
    if (d.cp) text += ' +' + d.cp + ' CP.';
    if (d.stepDone === 'SPEAK') text += ' Điểm nói không được điền thay: muốn đạt chủ đề phải ghi âm thật.';
    return text;
  }

  /** Study session id (study page) and situation code (/learning/comm/situation/{code}) of the open page. */
  function pageContext(doc, path) {
    var out = { sessionId: null, skillSessionId: null, situation: null };
    var el = doc && doc.querySelector ? doc.querySelector('[data-session-id]') : null;
    var id = el ? Number(el.getAttribute('data-session-id')) : NaN;
    if (id > 0 && el.hasAttribute('data-jpath-skill-session')) out.skillSessionId = id;
    else if (id > 0 && /^\/learning\/jlpt\/study/.test(path || '')) out.sessionId = id;
    var m = /^\/learning\/comm\/situation\/([^/?#]+)/.exec(path || '');
    if (m) out.situation = decodeURIComponent(m[1]);
    var q = /^\/learning\/comm\/situation\/?\?(?:.*&)?id=([^&#]+)/.exec(path || '');
    if (!m && q) out.situation = decodeURIComponent(q[1]);
    return out;
  }

  /** Demo survey answers: the panel ticks them step by step and presses "Tiếp tục" like a learner would. */
  var SURVEYS = {
    jlptZero: { label: 'JLPT, chưa học gì (đi thẳng N5)', answers: { goal: 'JLPT', studied: 'NO', targetLevel: 'N5', minutes: '30' } },
    jlptStudied: { label: 'JLPT, đã học (làm Smart Test)', answers: { goal: 'JLPT', studied: 'YES', duration: '4', kanaChoice: 'REMEMBER', selfLevel: 'N5', targetLevel: 'N4', minutes: '30' } },
    brse: { label: 'Giao tiếp BrSE', answers: { goal: 'BA', minutes: '30' } }
  };

  function visible(el) { return !!(el && el.getClientRects && el.getClientRects().length); }
  function wait(ms) { return new Promise(function (r) { root.setTimeout(r, ms); }); }

  function fillSurvey(key) {
    var answers = SURVEYS[key].answers;
    var form = document.getElementById('smartTestSurveyForm');
    var next = document.getElementById('surveyNextButton');
    if (!form || !next) return Promise.reject(new Error('Không thấy form khảo sát.'));
    var edit = document.getElementById('surveyEdit');
    var steps = 0;
    function round() {
      if (form.hidden || !visible(next)) {
        if (steps === 0 && visible(edit)) { edit.click(); return wait(500).then(round); }
        return 'Đã điền và lưu khảo sát "' + SURVEYS[key].label + '".';
      }
      if (steps++ > 15) throw new Error('Khảo sát dừng ở một bước chưa điền được.');
      Object.keys(answers).forEach(function (name) {
        var input = form.querySelector('input[name="' + name + '"][value="' + answers[name] + '"]');
        if (input && visible(input.closest('label') || input) && !input.checked) {
          input.checked = true;
          input.dispatchEvent(new Event('change', { bubbles: true }));
        }
      });
      var exam = document.getElementById('surveyExamDateInput');
      if (exam && visible(exam) && !exam.value) {
        var d = new Date(Date.now() + 180 * 864e5);
        exam.value = d.toISOString().slice(0, 10);
        exam.dispatchEvent(new Event('input', { bubbles: true }));
        exam.dispatchEvent(new Event('change', { bubbles: true }));
      }
      return wait(300).then(function waitReady(_, tries) {
        tries = tries || 0;
        if (form.hidden || !visible(next)) return round();
        if (next.disabled && tries < 30) return wait(500).then(function () { return waitReady(null, tries + 1); });
        if (next.disabled) throw new Error('Bước này cần chọn tay rồi bấm lại nút.');
        next.click();
        return wait(900).then(round);
      });
    }
    return Promise.resolve().then(round);
  }

  /**
   * Demo scripts per account (the server says which one the signed-in account plays). Each step opens its screen and,
   * when it has a "run", does that learner action there after the page loads.
   */
  var SCRIPTS = {
    A: { title: 'Người mới (JLPT từ đầu)', steps: [
      { label: '1. Khảo sát: chưa học gì', go: '/survey', run: 'survey:jlptZero' },
      { label: '2. Xem lộ trình', go: '/learning' },
      { label: '3. Mở buổi học hôm nay', go: '/learning', run: 'openToday' },
      { label: '4. Học xong hôm nay', go: '/learning', run: 'finishToday' },
      { label: '5. Sang ngày mai', go: '/learning', run: 'nextDay' }
    ] },
    B: { title: 'Học JLPT N5 lên N4', steps: [
      { label: '1. Khảo sát: đã học', go: '/survey', run: 'survey:jlptStudied' },
      { label: '2. Bắt đầu Smart Test', go: '/survey', run: 'startTest' },
      { label: '3. Smart Test: đúng hết', go: '/smart-test', run: 'smartRight' },
      { label: '4. Xem lộ trình', go: '/learning' },
      { label: '5. Học xong hôm nay', go: '/learning', run: 'finishToday' },
      { label: '6. Học hết lộ trình N5', go: '/learning', run: 'finishPath' },
      { label: '7. Mở buổi học hôm nay', go: '/learning', run: 'openToday' },
      { label: '8. Thi thử JLPT: trượt', go: '/learning', run: 'mockFail' },
      { label: '9. Thi thử JLPT: đạt (lên N4)', go: '/learning', run: 'mockPass' },
      { label: '10. Xem lộ trình cấp mới', go: '/learning' }
    ] },
    C: { title: 'Giao tiếp BrSE', steps: [
      { label: '1. Khảo sát: Giao tiếp BrSE', go: '/survey', run: 'survey:brse' },
      { label: '2. Khảo sát chủ đề', go: '/learning/comm/survey' },
      { label: '3. Xem lộ trình giao tiếp', go: '/learning/comm' }
    ] }
  };

  var RUN_KEY = 'jlasDemoRun';

  function runAction(name) {
    if (name.indexOf('survey:') === 0) return fillSurvey(name.slice(7));
    if (name === 'startTest') {
      var start = document.getElementById('surveyStartTest');
      if (!start || !visible(start)) return Promise.reject(new Error('Chưa có nút Làm Smart Test: hãy làm bước khảo sát trước.'));
      start.click();
      return Promise.resolve('Đã bắt đầu Smart Test.');
    }
    if (name === 'smartRight') {
      return call('POST', '/api/dev/learning/smart-test/answer-all', { correct: true }).then(function () {
        root.setTimeout(function () { root.location.reload(); }, 1500);
        return 'Đã trả lời đúng hết Smart Test.';
      });
    }
    if (name === 'openToday') {
      return call('POST', '/api/dev/learning/jlpt/open-today').then(function (json) {
        var id = json.data && json.data.sessionId;
        if (!id) throw new Error('Hôm nay không còn buổi học thẻ nào.');
        root.location.assign('/learning/jlpt/study?session=' + id);
        return 'Đang mở buổi học…';
      });
    }
    if (name === 'mockPass' || name === 'mockFail') {
      return call('POST', '/api/dev/learning/jlpt/mock', { pass: name === 'mockPass' }).then(function (json) {
        var d = json.data || {};
        root.setTimeout(function () { root.location.reload(); }, 3000);
        return 'Thi thử ' + d.level + ': ' + (d.lastScore != null ? d.lastScore + '%, ' : '')
          + (d.levelPassed ? 'đạt' + (d.nextLevel ? ', mở cấp ' + d.nextLevel : '') + '.' : 'chưa đạt.');
      });
    }
    var url = { finishToday: '/jlpt/finish-today', nextDay: '/jlpt/next-day', finishPath: '/jlpt/finish-path' }[name];
    if (!url) return Promise.reject(new Error('Không biết bước ' + name));
    return call('POST', '/api/dev/learning' + url).then(function (json) {
      root.setTimeout(function () { root.location.reload(); }, 2500);
      var d = json.data || {};
      return json.message + (d.day ? ' Ngày ' + d.day + '.' : '') + (d.today ? ' Hôm nay là ' + d.today + '.' : '')
        + (d.days ? ' ' + d.days + ' ngày.' : '') + (d.note ? ' ' + d.note : '');
    });
  }

  function scriptBlock(key, status) {
    var script = SCRIPTS[key];
    var wrap = document.createElement('div');
    wrap.className = 'mission-demo__group';
    script.steps.forEach(function (step) {
      var b = document.createElement('button');
      b.type = 'button';
      b.textContent = step.label;
      b.addEventListener('click', function () {
        var here = root.location.pathname.replace(/\/$/, '') === step.go;
        if (!here) {
          try { if (step.run) root.sessionStorage.setItem(RUN_KEY, step.run); } catch (e) { /* no storage */ }
          root.location.assign(step.go);
          return;
        }
        if (!step.run) { status.textContent = 'Đang ở màn này.'; return; }
        status.textContent = 'Đang xử lý…';
        runAction(step.run).then(function (t) { status.textContent = t; })
          .catch(function (e) { status.textContent = 'Lỗi: ' + e.message; });
      });
      wrap.append(b);
    });
    return wrap;
  }

  function build(state) {
    var style = document.createElement('style');
    style.textContent = CSS;
    document.head.appendChild(style);
    var panel = document.createElement('details');
    panel.className = 'mission-demo';
    panel.setAttribute('data-learning-demo', '');
    var summary = document.createElement('summary');
    summary.textContent = 'Demo Learning';
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
    var scriptKey = state.scenario && SCRIPTS[state.scenario] ? state.scenario : null;
    if (scriptKey) body.append(label('Kịch bản ' + scriptKey + ': ' + SCRIPTS[scriptKey].title), scriptBlock(scriptKey, status));
    if (document.getElementById('smartTestSurveyForm')) {
      var sv = document.createElement('div');
      sv.className = 'mission-demo__group';
      Object.keys(SURVEYS).forEach(function (key) {
        var b = document.createElement('button');
        b.type = 'button';
        b.textContent = SURVEYS[key].label;
        b.addEventListener('click', function () {
          b.disabled = true;
          status.textContent = 'Đang điền khảo sát…';
          fillSurvey(key).then(function (t) { status.textContent = t; })
            .catch(function (e) { status.textContent = 'Lỗi: ' + e.message; }).then(function () { b.disabled = false; });
        });
        sv.append(b);
      });
      body.append(label('Khảo sát: điền theo kịch bản'), sv);
    }
    var page = pageContext(root.document, root.location.pathname + root.location.search);
    if (state.protectedAccount) {
      status.textContent = 'Đây là tài khoản chính: nút demo bị khoá. Hãy mở profile Chrome của tài khoản demo.';
      body.append(status);
      panel.append(summary, body);
      document.body.appendChild(panel);
      return panel;
    }
    if (page.skillSessionId) {
      body.append(label('Buổi Đọc · Nghe'), button('Làm xong bước này', function () {
        return call('POST', '/api/dev/learning/skill-sessions/' + page.skillSessionId + '/next-step').then(function (json) {
          var v = json.data || {};
          return v.done ? 'Buổi Đọc · Nghe đã xong.' : 'Sang bước tiếp theo.';
        });
      }));
    }
    if (page.sessionId) {
      var one = function (remembered) {
        return function () {
          return call('POST', '/api/dev/learning/sessions/' + page.sessionId + '/one-card', { remembered: remembered })
            .then(function (json) {
              var o = json.data || {};
              return (o.cardsDone ? 'Đã học 1 thẻ (' + o.position + '/' + o.total + ').' : 'Không còn thẻ để học.')
                + (o.atCheck ? ' Tới bài kiểm tra.' : '') + (o.status === 'COMPLETED' ? ' Buổi học đã xong.' : '');
            });
        };
      };
      var cardGroup = document.createElement('div');
      cardGroup.className = 'mission-demo__group';
      cardGroup.append(button('Học 1 thẻ: Nhớ', one(true)), button('Học 1 thẻ: Quên', one(false)));
      body.append(label('Từng thẻ'), cardGroup);
      body.append(label('Buổi học đang mở'), button('Học hết thẻ tới bài kiểm tra', function () {
        return call('POST', '/api/dev/learning/sessions/' + page.sessionId + '/finish-cards').then(function (json) {
          var o = json.data;
          return 'Đã học ' + o.cardsDone + ' thẻ (' + o.position + '/' + o.total + ').'
            + (o.atCheck ? ' Tới bài kiểm tra: trang sẽ mở bài, rồi dùng nút "Trả lời đúng/sai hết".' : '')
            + (o.status === 'COMPLETED' ? ' Buổi học đã xong.' : '');
        });
      }));
    }
    if (page.situation) {
      var step = function (right) {
        return function () {
          return call('POST', '/api/dev/learning/comm/situations/' + encodeURIComponent(page.situation) + '/next-step',
            { roleplayRight: right }).then(function (json) { return stepText(json.data); });
        };
      };
      var commGroup = document.createElement('div');
      commGroup.className = 'mission-demo__group';
      commGroup.append(button('Hoàn tất bước tiếp theo', step(true)), button('Bước tiếp, nhập vai sai hết', step(false)));
      body.append(label('Tình huống giao tiếp'), commGroup);
    }
    var onPath = /^\/learning(\/jlpt|\/?$|\/?\?)/.test(root.location.pathname + root.location.search);
    if (onPath && !page.sessionId && !page.skillSessionId) {
      var jp = document.createElement('div');
      jp.className = 'mission-demo__group';
      var open = document.createElement('button');
      open.type = 'button';
      open.textContent = 'Mở buổi học hôm nay';
      open.addEventListener('click', function () {
        open.disabled = true;
        status.textContent = 'Đang mở buổi học…';
        call('POST', '/api/dev/learning/jlpt/open-today').then(function (json) {
          var id = json.data && json.data.sessionId;
          if (!id) throw new Error('Hôm nay không còn buổi học thẻ nào.');
          root.location.assign('/learning/jlpt/study?session=' + id);
        }).catch(function (e) { status.textContent = 'Lỗi: ' + e.message; open.disabled = false; });
      });
      jp.append(open, button('Học xong hôm nay', function () {
        return call('POST', '/api/dev/learning/jlpt/finish-today').then(function (json) {
          var d = json.data || {};
          return 'Ngày ' + d.day + ': ' + d.cards + ' thẻ, ' + d.checks + ' bài kiểm tra.' + (d.allDone ? ' Đã xong ngày.' : '') + (d.note ? ' ' + d.note : '');
        });
      }), button('Sang ngày mai', function () {
        return call('POST', '/api/dev/learning/jlpt/next-day').then(function (json) { return 'Hôm nay là ' + json.data.today + '.'; });
      }), button('Học hết lộ trình', function () {
        return call('POST', '/api/dev/learning/jlpt/finish-path').then(function (json) {
          var d = json.data || {};
          return 'Đã học ' + d.days + ' ngày (' + d.from + ' → ' + d.to + '), ' + d.cards + ' thẻ, ' + d.checks + ' bài kiểm tra.'
            + (d.finished ? ' Đã học hết bài của lộ trình: có thể thi JLPT.' : '') + (d.note ? ' ' + d.note : '');
        });
      }), button('Thi thử JLPT: trượt', function () {
        return runAction('mockFail');
      }), button('Thi thử JLPT: đạt (lên cấp)', function () {
        return runAction('mockPass');
      }));
      body.append(label('Lộ trình JLPT'), jp);
    }
    if (state.openSmartTest) {
      var answerAll = function (right) {
        return function () {
          return call('POST', '/api/dev/learning/smart-test/answer-all', { correct: right }).then(function (json) {
            var r = json.data || {};
            var res = r.result || {};
            return 'Đã trả lời ' + (r.answeredCount || 0) + ' câu.' + (res.estimatedOverallLevel ? ' Kết quả: ' + res.estimatedOverallLevel + '.' : '');
          });
        };
      };
      var stGroup = document.createElement('div');
      stGroup.className = 'mission-demo__group';
      stGroup.append(button('Trả lời đúng hết', answerAll(true)), button('Trả lời sai hết', answerAll(false)));
      body.append(label('Smart Test đang làm'), stGroup);
    }
    if (!check) {
      status.textContent = page.sessionId || page.skillSessionId || page.situation || document.getElementById('smartTestSurveyForm') || state.openSmartTest || onPath ? 'Chấm điểm, tiến độ và CP do hệ thống tự tính.'
        : 'Chưa có bài kiểm tra nào đang mở. Mở bài (kiểm tra nhanh, kiểm tra tuần, Đọc/Nghe…) rồi tải lại trang.';
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
    var pending = null;
    try { pending = root.sessionStorage.getItem(RUN_KEY); root.sessionStorage.removeItem(RUN_KEY); } catch (e) { /* no storage */ }
    if (pending) {
      panel.open = true;
      status.textContent = 'Đang xử lý…';
      root.setTimeout(function () {
        runAction(pending).then(function (t) { status.textContent = t; })
          .catch(function (e) { status.textContent = 'Lỗi: ' + e.message; });
      }, 1500);
    }
    return panel;
  }

  function start() {
    try {
      var q = new URLSearchParams(root.location && root.location.search || '');
      var qt = q.get('access_token');
      if (qt && root.JlasAuth && root.JlasAuth.setSession) {
        root.JlasAuth.setSession(qt);
      }
    } catch (e) { /* ignore */ }
    if (!root.JlasAuth || !root.JlasAuth.isLoggedIn || !root.JlasAuth.isLoggedIn()) return Promise.resolve(null);
    return call('GET', '/api/dev/learning/state')
      .then(function (json) { return build(json.data || {}); })
      .catch(function () { return null; /* demo tools disabled */ });
  }

  if (typeof module !== 'undefined' && module.exports) module.exports = { resultText: resultText, stepText: stepText, pageContext: pageContext, start: start };
  else start();
})(typeof window !== 'undefined' ? window : globalThis);
