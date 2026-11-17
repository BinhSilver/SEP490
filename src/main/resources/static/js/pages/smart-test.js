(function () {
  'use strict';

  /** Media câu hỏi: URL tuyệt đối hoặc '/...' (vd. /audio/smart-test/...) giữ nguyên; tương đối → kho audio giáo trình. */
  function mediaUrl(path) {
    var p = String(path == null ? '' : path).trim();
    if (!p) return '';
    if (/^(https?:|data:|blob:|\/)/i.test(p)) return p;
    return '/uploads/content-audio/' + p.replace(/^\.\//, '');
  }

  var ACTIVE_ATTEMPT_KEY = 'jlasSmartTestAttemptId';
  var SELECTED_SKILLS_KEY = 'jlasSmartTestSelectedSkills';
  var labels = {
    VOCABULARY: 'Từ vựng', GRAMMAR: 'Ngữ pháp', READING: 'Đọc hiểu',
    LISTENING: 'Nghe hiểu', KANJI: 'Kanji',
    LOW: 'Tham khảo', MEDIUM: 'Khá tin cậy', HIGH: 'Tin cậy cao'
  };
  var skillMeta = {
    VOCABULARY: { mark: '語', color: '#26b96b' },
    GRAMMAR: { mark: '文', color: '#e9ab26' },
    READING: { mark: '読', color: '#e47b27' },
    LISTENING: { mark: '聴', color: '#37aee2' },
    KANJI: { mark: '漢', color: '#d94a56' }
  };
  var skillOrder = ['KANJI', 'VOCABULARY', 'GRAMMAR', 'READING', 'LISTENING'];
  var levelValue = { N5: 1, N4: 2, N3: 3, N2: 4, N1: 5 };
  var readinessCopy = {
    READY: function (target) { return 'Bạn đã sẵn sàng cho mục tiêu ' + target + ' — hãy chuyển sang lộ trình học để chinh phục cấp độ này.'; },
    NEAR: function (target) { return 'Bạn đang tiến rất gần mục tiêu ' + target + ' — cần củng cố thêm một số điểm yếu là đạt.'; },
    BUILDING: function (target) { return 'Bạn đang xây dựng nền tảng để đạt mục tiêu ' + target + ' — lộ trình sẽ bắt đầu từ phần ôn tập.'; },
    STABLE: function () { return 'Trình độ hiện tại của bạn đã ổn định — tiếp tục duy trì và mở rộng vùng kiến thức.'; },
    PROVISIONAL: function () { return 'Đây là kết quả tạm tính — hãy hoàn thành đủ kỹ năng để nhận đánh giá chính xác hơn.'; }
  };
  var state = { attemptId: null, currentQuestion: null, goal: null, result: null };
  var existingPathUrl = null;
  var pathLookupState = 'unknown';
  var pathLookupVersion = 0;
  var timer = { interval: null, fallback: null, remaining: 0, started: false };
  var questionView = document.getElementById('questionView');
  var resultView = document.getElementById('resultView');
  var emptyView = document.getElementById('emptyView');
  var notice = document.getElementById('smartTestNotice');
  var confirmView = document.getElementById('smartConfirm');
  var confirmResolver = null;

  if (!window.JlasAuth || !window.JlasAuth.requireLogin('/smart-test')) return;

  /** {@code anchor}: keep the learner's place on the next question — its card lands just under the header (with the
   * timer bar) instead of the page jumping back above the hero; without it the page starts at the top. */
  function show(view, anchor) {
    [questionView, resultView, emptyView].forEach(function (item) { item.hidden = item !== view; });
    notice.hidden = true;
    if (anchor) anchor.scrollIntoView({ behavior: 'smooth', block: 'start' });
    else window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  /** Question-bank markup (<u> marking the hidden kanji, line breaks) is rendered, never shown as raw text. */
  function setSafeHtml(element, value) {
    if (window.SmartTestSafeHtml) element.innerHTML = window.SmartTestSafeHtml.html(value);
    else element.textContent = value == null ? '' : String(value);
  }

  function showError(message) {
    notice.textContent = message || 'Không thể thực hiện yêu cầu. Vui lòng thử lại.';
    notice.hidden = false;
  }

  function stripHtml(html) {
    var tmp = document.createElement('div');
    tmp.innerHTML = html || '';
    return (tmp.textContent || '').trim();
  }

  // Data câu điền kanji dùng □ làm chỗ trống; hiển thị thành ô trống gạch chân thay vì ô vuông.
  function markBlanks(html) {
    return (html || '').replace(/□/g, '<span class="kanji-blank" aria-label="ô trống">　</span>');
  }

  function clamp(value, min, max) {
    return Math.max(min, Math.min(max, value));
  }

  // Thời lượng làm bài theo kỹ năng: đọc tính theo độ dài đoạn văn, còn lại cố định.
  function timeLimitFor(q) {
    if (q.skill === 'READING') {
      var textLen = stripHtml(q.passageHtml).length;
      return clamp(60, 240, 30 + Math.ceil(textLen / 2));
    }
    return 45;
  }

  function clearTimer() {
    if (timer.interval) { clearInterval(timer.interval); timer.interval = null; }
    if (timer.fallback) { clearTimeout(timer.fallback); timer.fallback = null; }
    timer.started = false;
  }

  function updateTimerBadge() {
    var badge = document.getElementById('questionTimer');
    if (!badge) return;
    var value = badge.querySelector('b');
    var minutes = Math.floor(timer.remaining / 60);
    var seconds = timer.remaining % 60;
    value.textContent = (minutes > 0 ? minutes + ':' : '') + (seconds < 10 ? '0' + seconds : seconds);
    badge.classList.toggle('is-warning', timer.remaining <= 15);
  }

  function startCountdown(seconds) {
    clearTimer();
    timer.remaining = Math.max(1, Math.round(seconds));
    timer.started = true;
    updateTimerBadge();
    timer.interval = setInterval(function () {
      timer.remaining -= 1;
      updateTimerBadge();
      if (timer.remaining <= 0) {
        clearTimer();
        skipCurrentQuestion();
      }
    }, 1000);
  }

  function scheduleTimer(q) {
    clearTimer();
    if (q.skill === 'LISTENING') {
      var audio = document.querySelector('#questionMedia audio');
      if (audio) {
        var started = false;
        var begin = function () {
          if (started) return;
          var duration = audio.duration;
          if (!duration || !isFinite(duration)) return;
          started = true;
          startCountdown(Math.max(45, Math.round(duration) + 10));
        };
        audio.addEventListener('loadedmetadata', begin);
        audio.addEventListener('durationchange', begin);
        timer.fallback = setTimeout(function () { if (!timer.started) startCountdown(60); }, 3000);
        return;
      }
      startCountdown(60);
      return;
    }
    startCountdown(timeLimitFor(q));
  }

  async function skipCurrentQuestion() {
    if (!state.attemptId || !state.currentQuestion) return;
    var button = document.getElementById('answerButton');
    button.disabled = true;
    try {
      var data = await api('/api/assessment/smart-tests/' + state.attemptId + '/answers', {
        method: 'POST',
        body: JSON.stringify({ questionId: state.currentQuestion.id, selectedOption: null })
      });
      render(data);
    } catch (error) {
      showError(error.message);
      button.disabled = !document.querySelector('input[name="answer"]:checked');
    }
  }

  async function api(url, options) {
    var response = await fetch(url, Object.assign({ headers: window.JlasAuth.authHeaders() }, options || {}));
    var payload;
    try { payload = await response.json(); } catch (ignore) { payload = null; }
    if (!response.ok || !payload || !payload.success) {
      throw new Error(payload && payload.message ? payload.message : 'Không thể kết nối tới máy chủ.');
    }
    return payload.data;
  }

  function render(data) {
    state.attemptId = data.attemptId;
    state.goal = data.goal || state.goal;
    if (data.status === 'SUBMITTED' && data.result) {
      localStorage.removeItem(ACTIVE_ATTEMPT_KEY);
      state.result = data.result;
      renderResult(data.result);
      return;
    }
    state.currentQuestion = data.currentQuestion;
    renderQuestion(data);
  }

  function renderQuestion(data) {
    var q = data.currentQuestion;
    if (!q) return showError('Không tìm thấy câu hỏi tiếp theo.');
    // Test-support: expose the canonical question id so the E2E answer oracle
    // can look up the correct option without a privileged route.
    questionView.setAttribute('data-question-id', q.id);
    var storedSkills = [];
    try {
      var parsedSkills = JSON.parse(localStorage.getItem(SELECTED_SKILLS_KEY) || '[]');
      if (Array.isArray(parsedSkills)) storedSkills = parsedSkills;
    } catch (ignore) { storedSkills = []; }
    var responseSkills = Array.isArray(data.selectedSkills) ? data.selectedSkills : [];
    var selectedSkills = skillOrder.filter(function (skill) {
      return (responseSkills.length ? responseSkills : storedSkills).includes(skill);
    });
    if (!selectedSkills.length) selectedSkills = [q.skill];
    var roadmapPosition = 0;
    document.querySelectorAll('#skillRoadmap li').forEach(function (item) {
      var selected = selectedSkills.includes(item.dataset.skill);
      item.hidden = !selected;
      if (selected) {
        roadmapPosition += 1;
        item.querySelector('i').textContent = roadmapPosition;
      }
    });
    document.getElementById('questionSkill').textContent = labels[q.skill] || q.skill;
    document.getElementById('questionLevel').textContent = q.jlptLevel;
    var questionTag = document.getElementById('questionTag');
    questionTag.textContent = q.tagCode || 'GENERAL';
    questionTag.hidden = !q.tagCode;
    document.getElementById('progressBar').style.width = (data.totalQuestions ? data.answeredCount * 100 / data.totalQuestions : 0) + '%';
    document.getElementById('questionTitle').textContent = q.title || '';

    var questionText = document.getElementById('questionText');
    setSafeHtml(questionText, q.questionText || '');
    questionText.innerHTML = markBlanks(questionText.innerHTML);
    // Hide auxiliary prompts for vocabulary/grammar. Kanji tasks retain their
    // reading/spelling/component instructions (including the component reading hint).
    var passageUsesAuxiliaryPrompt = q.passageHtml && ['VOCABULARY', 'GRAMMAR'].includes(q.skill);
    questionText.hidden = passageUsesAuxiliaryPrompt || !q.questionText;
    document.querySelectorAll('#skillRoadmap li').forEach(function (item) {
      item.classList.toggle('is-active', item.dataset.skill === q.skill);
    });
    var tips = {
      VOCABULARY: 'Nhìn kỹ sắc thái của từng từ trước khi chọn nhé!',
      GRAMMAR: 'Đọc trọn câu để nhận ra cấu trúc tự nhiên nhất.',
      READING: 'Tìm ý chính trước, sau đó quay lại chi tiết cần hỏi.',
      LISTENING: 'Tập trung vào từ khóa và mục đích của người nói.',
      KANJI: 'Quan sát cả âm đọc lẫn ngữ cảnh sử dụng của chữ.'
    };
    document.getElementById('examTip').textContent = tips[q.skill] || 'Bình tĩnh đọc kỹ trước khi trả lời nhé!';

    var passage = document.getElementById('questionPassage');
    // Keep listening transcripts available to content/audio pipelines without
    // exposing the answer source while the learner is doing the test.
    passage.hidden = !q.passageHtml || q.skill === 'LISTENING';
    passage.classList.toggle('question-passage--reading', q.skill === 'READING');
    passage.innerHTML = markBlanks(q.passageHtml);

    var media = document.getElementById('questionMedia');
    media.innerHTML = '';
    if (q.audioUrl && q.audioUrl.indexOf('tts:') === 0) {
      var listenButton = document.createElement('button');
      listenButton.type = 'button'; listenButton.className = 'question-tts';
      listenButton.innerHTML = '<span class="material-symbols-outlined">volume_up</span><span>Phát đoạn hội thoại</span>';
      listenButton.addEventListener('click', function () {
        if (!window.speechSynthesis) return showError('Trình duyệt này chưa hỗ trợ phát giọng đọc.');
        window.speechSynthesis.cancel();
        var utterance = new SpeechSynthesisUtterance(q.audioUrl.slice(4));
        utterance.lang = 'ja-JP'; utterance.rate = 0.9;
        window.speechSynthesis.speak(utterance);
      });
      media.appendChild(listenButton);
    } else if (q.audioUrl) {
      var audio = document.createElement('audio');
      audio.controls = true;
      audio.preload = 'metadata';
      audio.src = mediaUrl(q.audioUrl);
      media.appendChild(audio);
    } else if (q.imageUrl) {
      var image = document.createElement('img'); image.src = mediaUrl(q.imageUrl); image.alt = 'Hình minh họa câu hỏi'; media.appendChild(image);
    }
    media.hidden = !q.audioUrl && !q.imageUrl;

    var list = document.getElementById('optionList');
    list.innerHTML = '';
    q.options.forEach(function (option, index) {
      var label = document.createElement('label'); label.className = 'option-item';
      var input = document.createElement('input'); input.type = 'radio'; input.name = 'answer'; input.value = String(index + 1);
      input.addEventListener('change', function () { document.getElementById('answerButton').disabled = false; });
      var marker = document.createElement('span'); marker.className = 'option-index'; marker.textContent = String.fromCharCode(65 + index);
      var text = document.createElement('span'); setSafeHtml(text, option);
      label.append(input, marker, text); list.appendChild(label);
    });
    document.getElementById('answerButton').disabled = true;
    scheduleTimer(q);
    var firstQuestion = questionView.hidden;
    show(questionView, firstQuestion ? null : questionView.querySelector('.exam-card'));
    var focusTarget = questionText.hidden ? passage : questionText;
    if (focusTarget && !firstQuestion) focusTarget.focus({ preventScroll: true });
  }

  function renderResult(result) {
    clearTimer();
    var skillsByName = {};
    (result.skills || []).forEach(function (skill) { skillsByName[skill.skill] = skill; });
    var testedSkills = skillOrder.map(function (name) { return skillsByName[name]; }).filter(Boolean);
    document.getElementById('resultSummary').textContent = result.partial
      ? 'Kết quả tạm tính của ' + testedSkills.length + ' kỹ năng đã làm.'
      : 'Bạn đã hoàn thành ' + testedSkills.length + ' kỹ năng. Đây là trình độ hiện tại của từng kỹ năng.';
    var positions = testedSkills.map(skillPosition);
    var overallPosition = positions.length
      ? positions.reduce(function (sum, value) { return sum + value; }, 0) / positions.length
      : 0;
    document.getElementById('overallLevel').textContent = result.estimatedOverallLevel || '—';
    var overallBand = document.getElementById('overallBand');
    overallBand.textContent = masteryStage(result.estimatedOverallLevel, overallPosition);
    overallBand.hidden = false;
    document.getElementById('overallScope').textContent = testedSkills.length === 5
      ? 'Tổng hợp từ đầy đủ 5 kỹ năng.'
      : 'Tổng hợp từ ' + testedSkills.length + '/5 kỹ năng bạn đã chọn.';
    renderSpiderChart(testedSkills, positions, overallPosition);

    var target = document.getElementById('skillResults'); target.innerHTML = '';
    testedSkills.forEach(function (skill, index) {
      var skillName = skill.skill;
      var meta = skillMeta[skillName];
      var stage = masteryStage(skill.estimatedLevel, positions[index]);
      var row = document.createElement('article'); row.className = 'spider-skill-item';
      row.style.setProperty('--skill-color', meta.color);

      var identity = document.createElement('div'); identity.className = 'spider-skill-item__identity';
      var mark = document.createElement('span'); mark.className = 'spider-skill-item__mark'; mark.textContent = meta.mark;
      var name = document.createElement('strong'); name.textContent = labels[skillName];
      identity.append(mark, name);
      var resultWrap = document.createElement('div'); resultWrap.className = 'spider-skill-item__result';
      var level = document.createElement('span'); level.className = 'spider-skill-item__value';
      level.textContent = (skill.estimatedLevel || '—') + ' · ' + stage;
      resultWrap.appendChild(level);
      row.append(identity, resultWrap); target.appendChild(row);
    });
    renderFeedback(result);
    renderReview(result.review);
    show(resultView);
    refreshPathActions();
  }

  function smartTestConfirmUrl() {
    var handoff = window.JpathSurveyFlow && window.JpathSurveyFlow.smartTestHandoffUrl(jpathConfig, state.goal, state.attemptId);
    return handoff || (state.attemptId
      ? '/learning/path?assessmentAttemptId=' + encodeURIComponent(state.attemptId)
      : '/learning');
  }

  function updatePathActions() {
    var createButton = document.getElementById('createPathButton');
    var updateButton = document.getElementById('updatePathButton');
    if (!createButton) return;
    createButton.querySelector('span').textContent = existingPathUrl ? 'Vào lộ trình học'
      : pathLookupState === 'none' ? 'Tạo lộ trình học' : 'Vào trang học';
    createButton.href = existingPathUrl || (pathLookupState === 'none' ? smartTestConfirmUrl() : '/learning');
    createButton.hidden = pathLookupState === 'none' && Boolean(window.SmartTestResultRoute && window.SmartTestResultRoute.notice(state.result).blockCreate);
    if (updateButton) {
      updateButton.hidden = !existingPathUrl || !state.attemptId;
      // The confirm page only serves learners without an ACTIVE path, so a learner who already has a JLPT path
      // updates it right here: the result level is compared with the path and the matching level move is offered.
      if (!updateButton.hidden) {
        updateButton.href = '#';
        updateButton.onclick = function (e) { e.preventDefault(); planPathUpdate(); };
      }
    }
  }

  var LEVEL_ORDER = ['N5', 'N4', 'N3', 'N2', 'N1'];

  /** Pure: what the result means for an existing path, from GET /api/learning/jlpt/settings. */
  function pathUpdatePlan(resultLevel, view) {
    var v = view || {};
    var open = v.openLevel || null;
    var lvl = resultLevel ? String(resultLevel).toUpperCase() : null;
    if (!lvl || LEVEL_ORDER.indexOf(lvl) < 0 || !open) return { kind: 'unknown', level: lvl, open: open };
    if (lvl === open) return { kind: 'same', level: lvl, open: open };
    if ((v.lowerLevels || []).indexOf(lvl) >= 0) return { kind: 'lower', level: lvl, open: open, url: '/api/learning/jlpt/path/lower-level' };
    if ((v.returnLevels || []).indexOf(lvl) >= 0) return { kind: 'return', level: lvl, open: open, url: '/api/learning/jlpt/path/return-level' };
    return { kind: LEVEL_ORDER.indexOf(lvl) > LEVEL_ORDER.indexOf(open) ? 'higher' : 'unavailable', level: lvl, open: open };
  }

  function pathUpdateText(plan) {
    switch (plan.kind) {
      case 'same': return 'Kết quả ' + plan.level + ' khớp với cấp bạn đang học. Lộ trình hiện tại đã đúng, không cần đổi.';
      case 'lower': return 'Kết quả ' + plan.level + ' thấp hơn cấp đang học (' + plan.open + '). Chuyển sang học ' + plan.level
        + ' trước; tiến độ ' + plan.open + ' vẫn được giữ để quay lại sau.';
      case 'return': return 'Kết quả ' + plan.level + ' khớp với cấp bạn từng đạt. Có thể quay lại học ' + plan.level + ' ngay.';
      case 'higher': return 'Kết quả ' + plan.level + ' cao hơn cấp đang học (' + plan.open + '). Lên cấp mới cần qua bài kiểm tra tổng kết cấp '
        + plan.open + ' trên lộ trình, để tiến độ không bị bỏ sót.';
      case 'unavailable': return 'Cấp ' + plan.level + ' hiện chưa mở cho lộ trình của bạn. Bạn vẫn học tiếp cấp ' + plan.open + '.';
      default: return 'Chưa xác định được cấp từ kết quả này. Bạn có thể đổi cấp trong Cài đặt lộ trình.';
    }
  }

  function planPathUpdate() {
    var box = document.getElementById('pathUpdateBox');
    if (!box) {
      box = document.createElement('div');
      box.id = 'pathUpdateBox';
      box.className = 'smart-test-path-update';
      box.setAttribute('role', 'status');
      var actions = document.querySelector('.result-actions');
      if (actions) actions.insertAdjacentElement('afterend', box); else return;
    }
    box.textContent = 'Đang so kết quả với lộ trình của bạn…';
    fetch('/api/learning/jlpt/settings', { headers: window.JlasAuth.authHeaders() })
      .then(function (r) { return r.ok ? r.json() : Promise.reject(new Error('Chưa đọc được lộ trình hiện tại.')); })
      .then(function (payload) {
        var plan = pathUpdatePlan(state.result && state.result.estimatedOverallLevel, payload && payload.data);
        box.innerHTML = '';
        var p = document.createElement('p'); p.textContent = pathUpdateText(plan); box.appendChild(p);
        var row = document.createElement('div'); row.className = 'result-actions';
        if (plan.url) {
          var go = document.createElement('button');
          go.type = 'button'; go.className = 'smart-test-primary';
          go.textContent = plan.kind === 'lower' ? 'Chuyển sang học ' + plan.level : 'Quay lại học ' + plan.level;
          go.addEventListener('click', function () {
            go.disabled = true;
            fetch(plan.url, { method: 'POST', headers: Object.assign({ 'Content-Type': 'application/json' }, window.JlasAuth.authHeaders()),
              body: JSON.stringify({ level: plan.level }) })
              .then(function (r) { return r.json().catch(function () { return {}; }).then(function (b) { if (!r.ok) throw new Error(b.message || 'Chưa đổi được cấp.'); }); })
              .then(function () { window.location.assign('/learning'); })
              .catch(function (err) { go.disabled = false; p.textContent = err.message; });
          });
          row.appendChild(go);
        }
        var open = document.createElement('a'); open.className = 'smart-test-secondary'; open.href = '/learning'; open.textContent = 'Vào lộ trình';
        row.appendChild(open);
        if (plan.kind === 'unknown' || plan.kind === 'unavailable') {
          var st = document.createElement('a'); st.className = 'smart-test-secondary'; st.href = '/learning/jlpt/settings'; st.textContent = 'Cài đặt lộ trình';
          row.appendChild(st);
        }
        box.appendChild(row);
      })
      .catch(function (err) { box.textContent = err.message || 'Chưa đọc được lộ trình hiện tại.'; });
  }
  if (typeof window !== 'undefined') window.SmartTestPathUpdate = { plan: pathUpdatePlan, text: pathUpdateText };

  function refreshPathActions() {
    var version = ++pathLookupVersion;
    existingPathUrl = null;
    pathLookupState = 'unknown';
    updatePathActions();
    Promise.all([
      fetch('/api/learning/jlpt/path', { headers: window.JlasAuth.authHeaders() }),
      fetch('/api/learning/comm/path', { headers: window.JlasAuth.authHeaders() })
    ]).then(function (responses) {
      if (version !== pathLookupVersion) return;
      if (responses[0].ok) existingPathUrl = '/learning';
      else if (responses[1].ok) existingPathUrl = '/learning/comm/path';
      else if (responses[0].status === 404 && responses[1].status === 404) {
        return fetch('/api/learning/jlpt/entry-status', { headers: window.JlasAuth.authHeaders() })
          .then(function (statusResponse) { return statusResponse.ok ? statusResponse.json() : null; })
          .then(function (payload) {
            if (version !== pathLookupVersion) return;
            var status = payload && payload.data && payload.data.pathStatus;
            if (status === 'ACTIVE' || status === 'PAUSED') existingPathUrl = '/learning';
            else if (payload) pathLookupState = 'none';
            updatePathActions();
          });
      }
      updatePathActions();
    }).catch(function () { /* Keep the current action until path state can be checked. */ });
  }

  function renderFeedback(result) {
    var section = document.getElementById('feedbackSection');
    var banner = document.getElementById('readinessBanner');
    var list = document.getElementById('recommendationList');
    var weakness = document.getElementById('grammarWeakness');
    var weaknessList = document.getElementById('grammarWeaknessList');

    var recommendations = result.recommendations || [];
    var grammarPoints = [];
    (result.skills || []).forEach(function (skill) {
      (skill.grammarPoints || []).forEach(function (gp) {
        if (gp.totalQuestions > 0 && gp.correctAnswers < gp.totalQuestions) grammarPoints.push(gp);
      });
    });

    section.hidden = !result.readinessLevel && !recommendations.length && !grammarPoints.length;

    banner.hidden = true; banner.innerHTML = '';
    var targetLevel = String(result.targetLevel || '').toUpperCase();
    var assessedLevel = String(result.estimatedOverallLevel || '').toUpperCase();
    var isHigherThanTarget = Boolean(result.requiresTargetConfirmation && levelValue[assessedLevel] &&
      levelValue[targetLevel] && levelValue[assessedLevel] > levelValue[targetLevel]);
    var selfLevel = String(result.selfReportedLevel || '').toUpperCase();
    var route = window.SmartTestResultRoute ? window.SmartTestResultRoute.notice(result) : { kind: null };
    var createButton = document.getElementById('createPathButton');
    if (createButton) createButton.hidden = Boolean(route.blockCreate);
    var copy = route.kind === 'PENDING_RELEASE' || route.kind === 'UNKNOWN_FAR'
      ? function () { return route.text; }
      : isLowerThanSelfReport(result)
      ? function () { return 'Kết quả Smart Test của bạn là ' + assessedLevel + ', thấp hơn mức bạn tự khai ' + selfLevel +
        '. Khi tạo lộ trình, bạn có thể chọn củng cố nền từ ' + assessedLevel + ' hoặc giữ theo khảo sát; mục tiêu ' +
        targetLevel + ' không thay đổi.'; }
      : isHigherThanTarget
      ? function () { return 'Kết quả Smart Test của bạn là ' + assessedLevel + ', cao hơn mục tiêu đã chọn ' + targetLevel +
        '. Kết quả đánh giá được giữ nguyên; hãy chọn cấp muốn ôn trước khi tạo lộ trình.'; }
      : route.kind === 'INTRO_STAGE' && readinessCopy[result.readinessLevel]
      ? function (target) { return readinessCopy[result.readinessLevel](target) + ' ' + route.text; }
      : readinessCopy[result.readinessLevel];
    if (copy) {
      var icon = document.createElement('span');
      icon.className = 'material-symbols-outlined';
      icon.textContent = 'flag';
      var text = document.createElement('p');
      text.textContent = copy(result.targetLevel || '');
      banner.append(icon, text);
      banner.hidden = false;
    }

    list.innerHTML = '';
    recommendations.forEach(function (rec) {
      var li = document.createElement('li');
      li.textContent = rec;
      list.appendChild(li);
    });

    weakness.hidden = !grammarPoints.length;
    weaknessList.innerHTML = '';
    grammarPoints.forEach(function (gp) {
      var chip = document.createElement('span');
      chip.className = 'grammar-weakness__chip';
      var pattern = document.createElement('strong');
      pattern.textContent = gp.pattern || '';
      var detail = document.createElement('small');
      detail.textContent = (gp.lessonCode ? gp.lessonCode + ' · ' : '') + gp.correctAnswers + '/' + gp.totalQuestions + ' đúng';
      chip.append(pattern, detail);
      weaknessList.appendChild(chip);
    });
  }

  function renderReview(review) {
    var list = document.getElementById('reviewList');
    var wrapper = document.getElementById('questionReview');
    list.innerHTML = '';
    if (!Array.isArray(review) || !review.length) { wrapper.hidden = true; return; }
    wrapper.hidden = false;
    review.forEach(function (item) { list.appendChild(buildReviewItem(item)); });
  }

  function buildReviewItem(item) {
    var article = document.createElement('article');
    article.className = 'review-item' + (item.correct ? ' review-item--correct' : ' review-item--wrong');

    var header = document.createElement('header');
    header.className = 'review-item__head';
    var number = document.createElement('span');
    number.className = 'review-item__number';
    number.textContent = 'Câu ' + item.number;
    var meta = document.createElement('span');
    meta.className = 'review-item__meta';
    meta.textContent = (labels[item.skill] || item.skill) + ' · ' + item.jlptLevel;
    var verdict = document.createElement('span');
    verdict.className = 'review-item__verdict';
    verdict.innerHTML = item.selectedOption == null
      ? '<span class="material-symbols-outlined">schedule</span>Bỏ qua'
      : (item.correct
        ? '<span class="material-symbols-outlined">check_circle</span>Đúng'
        : '<span class="material-symbols-outlined">cancel</span>Sai');
    header.append(number, meta, verdict);

    var question = document.createElement('p');
    question.className = 'review-item__question';
    setSafeHtml(question, item.questionText || '');
    question.innerHTML = markBlanks(question.innerHTML);
    article.append(header, question);
    if (item.passageHtml) {
      // The sentence the instruction refers to (the underlined part, the blank), as it was shown in the test.
      var passage = document.createElement(item.skill === 'READING' ? 'details' : 'div');
      passage.className = 'review-item__passage';
      var body = document.createElement('div');
      setSafeHtml(body, item.passageHtml);
      if (item.skill === 'READING') {
        var summary = document.createElement('summary');
        summary.textContent = 'Xem đoạn văn';
        passage.appendChild(summary);
      }
      passage.appendChild(body);
      article.appendChild(passage);
    }

    var options = document.createElement('ul');
    options.className = 'review-options';
    (item.options || []).forEach(function (option, index) {
      var optionNumber = index + 1;
      var li = document.createElement('li');
      li.className = 'review-option';
      if (optionNumber === item.correctOption) li.classList.add('is-correct');
      if (optionNumber === item.selectedOption && optionNumber !== item.correctOption) li.classList.add('is-selected-wrong');
      var mark = document.createElement('span');
      mark.className = 'review-option__mark';
      mark.textContent = String.fromCharCode(65 + index);
      var optionText = document.createElement('span');
      setSafeHtml(optionText, option);
      li.append(mark, optionText);
      options.appendChild(li);
    });
    article.appendChild(options);

    if (item.grammarPattern || item.explanation) {
      var explanation = document.createElement('div');
      explanation.className = 'review-item__explanation';
      if (item.grammarPattern) {
        var pattern = document.createElement('strong');
        pattern.className = 'review-item__pattern';
        pattern.textContent = item.grammarPattern + (item.grammarMeaning ? ' — ' + item.grammarMeaning : '');
        explanation.appendChild(pattern);
      }
      if (item.explanation) {
        var note = document.createElement('p');
        note.className = 'review-item__note';
        note.textContent = item.explanation;
        explanation.appendChild(note);
      }
      article.appendChild(explanation);
    }

    return article;
  }

  function renderSpiderChart(skills, positions, overallPosition) {
    var radar = document.getElementById('skillRadar');
    if (skills.length < 3) {
      radar.innerHTML = '<p class="spider-fallback">Cần ít nhất 3 kỹ năng để tạo biểu đồ mạng nhện.</p>';
      return;
    }
    var width = 390, height = 335, cx = 195, cy = 158, radius = 112;
    var axes = skills.map(function (skill, index) {
      var angle = -Math.PI / 2 + index * Math.PI * 2 / skills.length;
      return { skill: skill, angle: angle, x: cx + Math.cos(angle) * radius, y: cy + Math.sin(angle) * radius };
    });
    function ringPoints(scale) {
      return axes.map(function (axis) {
        return (cx + Math.cos(axis.angle) * radius * scale).toFixed(1) + ','
          + (cy + Math.sin(axis.angle) * radius * scale).toFixed(1);
      }).join(' ');
    }
    var grid = [1, 2, 3, 4, 5].map(function (ring) {
      return '<polygon class="radar-grid__ring" points="' + ringPoints(ring / 5) + '"></polygon>';
    }).join('');
    var lines = axes.map(function (axis) {
      return '<line x1="' + cx + '" y1="' + cy + '" x2="' + axis.x.toFixed(1)
        + '" y2="' + axis.y.toFixed(1) + '"></line>';
    }).join('');
    var overallPoints = ringPoints(overallPosition);
    var skillPoints = axes.map(function (axis, index) {
      return (cx + Math.cos(axis.angle) * radius * positions[index]).toFixed(1) + ','
        + (cy + Math.sin(axis.angle) * radius * positions[index]).toFixed(1);
    }).join(' ');
    var labelsSvg = axes.map(function (axis) {
      var meta = skillMeta[axis.skill.skill];
      var labelRadius = radius + 40;
      var x = cx + Math.cos(axis.angle) * labelRadius;
      var y = cy + Math.sin(axis.angle) * labelRadius;
      return '<text class="radar-label" x="' + x.toFixed(1) + '" y="' + y.toFixed(1)
        + '" text-anchor="middle"><tspan class="radar-label__mark" fill="' + meta.color + '">'
        + meta.mark + '</tspan><tspan x="' + x.toFixed(1) + '" dy="18">'
        + labels[axis.skill.skill] + ' · ' + axis.skill.estimatedLevel + '</tspan></text>';
    }).join('');
    radar.innerHTML = '<svg viewBox="0 0 ' + width + ' ' + height + '" aria-hidden="true"><g class="radar-grid">'
      + grid + lines + '</g><polygon class="radar-overall" points="' + overallPoints + '"></polygon>'
      + '<polygon class="radar-data" points="' + skillPoints + '"></polygon>' + labelsSvg + '</svg>';
  }

  function skillPosition(skill) {
    var estimatedValue = levelValue[skill.estimatedLevel] || 1;
    var band = skill.abilityBand || '';
    var stageProgress = band.endsWith('Nhập môn') ? 0.2
      : band.endsWith('Căn bản') ? 0.5 : band.endsWith('Thành thạo') ? 0.85 : null;
    if (stageProgress !== null) return ((estimatedValue - 1) + stageProgress) / 5;
    var batches = Array.isArray(skill.batches) ? skill.batches : [];
    var higherProbes = batches.filter(function (batch) {
      return (levelValue[batch.level] || 0) > estimatedValue;
    });
    var progress;

    if (higherProbes.length) {
      var probe = higherProbes[higherProbes.length - 1];
      var probeRatio = probe.totalQuestions ? probe.correctAnswers / probe.totalQuestions : 0;
      // The harder probe refines mastery of the proven lower level:
      // 0-1/5 => Căn Bản, 2-3/5 => Thành Thạo. A score of 4-5/5
      // should already have been awarded the harder level by the backend.
      progress = probeRatio <= 0.2 ? 0.5 : 0.85;
    } else {
      var sameLevel = batches.filter(function (batch) { return batch.level === skill.estimatedLevel; });
      var correct = sameLevel.reduce(function (sum, batch) { return sum + batch.correctAnswers; }, 0);
      var total = sameLevel.reduce(function (sum, batch) { return sum + batch.totalQuestions; }, 0);
      progress = total ? correct / total : 0.5;
      if ((skill.abilityBand || '').endsWith('Nhập môn')) {
        progress = Math.max(0.3, Math.min(0.65, progress * 0.65));
      }
    }

    var position = ((estimatedValue - 1) + Math.max(0.08, Math.min(1, progress))) / 5;
    return Math.max(0.025, Math.min(1, position));
  }

  function masteryStage(level, position) {
    var value = levelValue[level] || 1;
    var progress = position * 5 - (value - 1);
    progress = Math.max(0, Math.min(1, progress));
    if (progress < 1 / 3) return 'Nhập Môn';
    if (progress < 2 / 3) return 'Căn Bản';
    return 'Thành Thạo';
  }

  function askConfirmation(title, message, yesLabel) {
    document.getElementById('smartConfirmTitle').textContent = title;
    document.getElementById('smartConfirmMessage').textContent = message;
    document.querySelector('#smartConfirmYes span:last-child').textContent = yesLabel || 'Đồng ý';
    confirmView.hidden = false;
    document.body.classList.add('has-smart-confirm');
    document.getElementById('smartConfirmNo').focus();
    return new Promise(function (resolve) { confirmResolver = resolve; });
  }

  function closeConfirmation(accepted) {
    if (confirmView.hidden) return;
    confirmView.hidden = true;
    document.body.classList.remove('has-smart-confirm');
    var resolve = confirmResolver;
    confirmResolver = null;
    if (resolve) resolve(accepted);
  }

  document.getElementById('smartConfirmNo').addEventListener('click', function () { closeConfirmation(false); });
  document.getElementById('smartConfirmYes').addEventListener('click', function () { closeConfirmation(true); });
  confirmView.addEventListener('click', function (event) { if (event.target === confirmView) closeConfirmation(false); });
  document.addEventListener('keydown', function (event) { if (event.key === 'Escape' && !confirmView.hidden) closeConfirmation(false); });

  document.getElementById('answerForm').addEventListener('submit', async function (event) {
    event.preventDefault();
    var selected = document.querySelector('input[name="answer"]:checked');
    if (!selected || !state.currentQuestion) return;
    var button = document.getElementById('answerButton'); button.disabled = true;
    try {
      var data = await api('/api/assessment/smart-tests/' + state.attemptId + '/answers', {
        method: 'POST', body: JSON.stringify({ questionId: state.currentQuestion.id, selectedOption: Number(selected.value) })
      });
      render(data);
    } catch (error) { showError(error.message); button.disabled = false; }
  });

  document.getElementById('finishButton').addEventListener('click', async function () {
    if (!state.attemptId) return;
    var accepted = await askConfirmation('Kết thúc bài thi?', 'Kết quả sẽ được tính từ những câu bạn đã trả lời. Bạn vẫn có thể ở lại để hoàn thành kỹ năng hiện tại.', 'Kết thúc bài');
    if (!accepted) return;
    var finishButton = document.getElementById('finishButton');
    var answerButton = document.getElementById('answerButton');
    finishButton.disabled = true; answerButton.disabled = true;
    try {
      var data = await api('/api/assessment/smart-tests/' + state.attemptId + '/finish', { method: 'POST' });
      render(data);
    } catch (error) {
      showError(error.message); finishButton.disabled = false;
      answerButton.disabled = !document.querySelector('input[name="answer"]:checked');
    }
  });

  // Owner 2026-10-19: a JLPT result below a known self-reported level needs the learner's explicit answer.
  function isLowerThanSelfReport(result) {
    if (!result || state.goal === 'BA') return false;
    var self = String(result.selfReportedLevel || '').toUpperCase();
    var assessed = String(result.estimatedOverallLevel || '').toUpperCase();
    return Boolean(levelValue[self] && levelValue[assessed] && result.targetLevel && levelValue[assessed] < levelValue[self]);
  }

  // Owner 2026-10-25: a question answered by one of several buttons; "Quay lại" (or Esc) answers null.
  // The dialog's grid/flex styles override the [hidden] attribute, so display is switched inline.
  function askChoice(title, message, options) {
    var box = document.getElementById('smartChoiceOptions');
    var yes = document.getElementById('smartConfirmYes');
    var no = document.getElementById('smartConfirmNo');
    var actions = no.parentNode;
    var noText = no.textContent;
    box.innerHTML = '';
    options.forEach(function (option) {
      var itemWrap = document.createElement('div');
      itemWrap.style.display = 'grid';
      itemWrap.style.gap = '4px';

      var button = document.createElement('button');
      button.type = 'button';
      button.className = option.primary ? 'smart-confirm__yes' : 'smart-confirm__no';
      button.textContent = option.label;
      button.addEventListener('click', function () { closeConfirmation(option.value); });
      itemWrap.appendChild(button);

      if (option.warning) {
        var warnP = document.createElement('p');
        warnP.className = 'smart-confirm__warning';
        warnP.textContent = option.warning;
        itemWrap.appendChild(warnP);
      }
      box.appendChild(itemWrap);
    });
    box.style.display = 'grid'; yes.style.display = 'none'; actions.style.gridTemplateColumns = '1fr';
    var answer = askConfirmation(title, message);
    no.textContent = 'Quay lại';
    return answer.then(function (value) {
      box.style.display = 'none'; box.innerHTML = ''; yes.style.display = ''; actions.style.gridTemplateColumns = '';
      no.textContent = noText;
      return typeof value === 'string' ? value : null;
    });
  }

  function nextLevel(level) {
    var order = ['N5', 'N4', 'N3', 'N2', 'N1'];
    var index = order.indexOf(level);
    return index >= 0 && index < order.length - 1 ? order[index + 1] : level;
  }

  function mistakeSummary(level) {
    var wrong = (state.result && state.result.skills || []).filter(function (skill) {
      return skill.totalQuestions > 0 && skill.correctAnswers < skill.totalQuestions;
    }).map(function (skill) {
      return (labels[skill.skill] || skill.skill) + ' sai ' + (skill.totalQuestions - skill.correctAnswers) + '/' + skill.totalQuestions;
    });
    return wrong.length ? 'Smart Test còn câu sai ở ' + level + ': ' + wrong.join(', ') + '.' : 'Smart Test còn một số câu sai ở ' + level + '.';
  }

  async function retakeSmartTest() {
    var survey = await api('/api/assessment/survey');
    var payload = survey && survey.survey ? survey.survey : survey;
    var test = await api('/api/assessment/smart-tests', { method: 'POST', body: JSON.stringify(payload) });
    localStorage.setItem(ACTIVE_ATTEMPT_KEY, test.attemptId);
    render(test);
    window.location.assign('/smart-test');
  }

  // The notice is shown through textContent (askChoice / askConfirmation), which never parses HTML, so its text is
  // passed as is — escaping it here would show "&lt;" to the learner.
  function plain(val) { return String(val == null ? '' : val); }

  function formatWeakLevelNotice(notice) {
    var weakLevel = plain(notice.weakLevel);
    var title = 'Bạn nên học lại ' + weakLevel + ' trước';

    var para1 = '';
    var skills = Array.isArray(notice.weakSkills) ? notice.weakSkills : [];
    if (!skills.length) {
      para1 = 'Kết quả Smart Test cho thấy bạn chưa vững ' + weakLevel + '.';
    } else {
      var skillParts = skills.map(function (s) {
        return plain(s.label || s.skill) + ' ' + Number(s.correct || 0) + '/' + Number(s.total || 0);
      });
      para1 = 'Kết quả Smart Test cho thấy bạn chưa vững ' + weakLevel + ': ' + skillParts.join(', ') + '.';
    }

    var stages = Array.isArray(notice.stages) ? notice.stages.map(plain) : [];
    var weakIndex = stages.indexOf(weakLevel);
    var afterStages = weakIndex >= 0 ? stages.slice(weakIndex + 1) : stages.filter(function (st) { return st !== weakLevel; });
    var afterText = afterStages.join(' → ');

    var timeNote = '';
    if (notice.estimating) {
      timeNote = ' (đang tính số ngày…)';
    } else if (notice.estimatedDays != null && notice.minutesPerDay != null) {
      timeNote = ' (khoảng ' + Number(notice.estimatedDays) + ' ngày với ' + Number(notice.minutesPerDay) + ' phút/ngày)';
    }

    var para2 = 'KotsuKotsu khuyến khích học lại ' + weakLevel + ' cho vững' + timeNote + (afterText ? ', sau đó mới tới ' + afterText + '.' : '.');
    return { title: title, message: para1 + ' ' + para2 };
  }

  function fetchEstimate(attemptId, kanaKnown, notice, messageEl) {
    if (!notice || notice._estimatePromise) return notice ? notice._estimatePromise : Promise.resolve(null);
    var controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
    // A long chain (N5 → N3, ~380 packages) builds the whole path to count its days: ~12 s warm, more on a cold app.
    // The learner can choose while it runs, so wait long enough for the number to arrive.
    var timeoutId = setTimeout(function () {
      if (controller) controller.abort();
    }, 45000);

    // [JLAS-W0108] the estimate comes from the JLPT path (its own data source), not the retired v1 path
    var minutes = Number(notice.minutesPerDay) || 30;
    var fetchOpts = Object.assign({
      method: 'POST',
      headers: Object.assign({ 'Content-Type': 'application/json' }, window.JlasAuth.authHeaders()),
      body: JSON.stringify({ openLevel: notice.weakLevel, targetLevel: notice.weakLevel, dailyMinutes: minutes,
        kanaTab: kanaKnown === false, skillDays: ['WED', 'SAT'], restDays: ['SUN'] })
    }, controller ? { signal: controller.signal } : {});
    notice._estimatePromise = api('/api/learning/jlpt/estimate', fetchOpts)
      .then(function (data) {
        clearTimeout(timeoutId);
        notice.estimating = false;
        notice._estimateDone = true;
        var finish = data && (data.levelFinish || data.targetFinish);
        if (finish) {
          var days = Math.round((Date.parse(finish + 'T00:00:00Z') - Date.parse(new Date().toISOString().slice(0, 10) + 'T00:00:00Z')) / 86400000);
          if (days > 0) {
            notice.estimatedDays = days;
            notice.minutesPerDay = data.dailyMinutes || minutes;
          }
        }
        var confirmCard = document.getElementById('smartConfirm');
        if (messageEl && confirmCard && !confirmCard.hidden) {
          var updated = formatWeakLevelNotice(notice);
          messageEl.textContent = updated.message;
        }
        return data;
      })
      .catch(function () {
        clearTimeout(timeoutId);
        notice.estimating = false;
        notice._estimateDone = true;
        var confirmCard = document.getElementById('smartConfirm');
        if (messageEl && confirmCard && !confirmCard.hidden) {
          var updated = formatWeakLevelNotice(notice);
          messageEl.textContent = updated.message;
        }
        return null;
      });
    return notice._estimatePromise;
  }

  /** Asks the server's entry questions in order; null when the learner went back or started a new Smart Test. */
  async function askEntryQuestions(questions) {
    var answers = { kanaKnown: null, entryChoice: null };
    if (!questions) return answers;
    if (questions.askKana) {
      var kana = await askChoice('Bạn đã đọc được bảng chữ cái chưa?', questions.partial
        ? 'Bạn chưa hoàn thành Smart Test, nên hệ thống cần biết bạn đã đọc được Hiragana và Katakana chưa.'
        : 'Kết quả Smart Test ở dưới N5. Nếu bạn chưa đọc được Hiragana và Katakana, lộ trình bắt đầu từ bảng chữ cái.',
        [{ value: 'NO', label: 'Chưa — học bảng chữ cái trước', primary: true }, { value: 'YES', label: 'Đã đọc được' }]);
      if (kana === null) return null;
      answers.kanaKnown = kana === 'YES';
      if (!answers.kanaKnown) return answers;
    }

    // Owner 2026-10-27 (R1 / Việc 2 & 4): WEAK_LEVEL notice modal with estimate
    if (questions.notice && questions.choiceKind === 'WEAK_LEVEL') {
      var attemptId = (questions && questions.assessmentAttemptId) || (typeof state !== 'undefined' && state ? state.attemptId : null);
      var weakLvl = plain(questions.notice.weakLevel);
      var skipLvl = plain(questions.notice.skipLevel);
      var noticeOptions = [
        { value: 'STUDY_WEAK_LEVEL', label: 'Bắt đầu học ' + weakLvl, primary: true }
      ];
      if (questions.notice.allowSkip && skipLvl) {
        noticeOptions.push({
          value: 'SKIP_WEAK_LEVEL',
          label: 'Vẫn học thẳng ' + skipLvl,
          warning: 'Bạn có thể gặp khó vì chưa vững ' + weakLvl
        });
      }

      var hasEstimate = questions.notice.estimatedDays != null && questions.notice.minutesPerDay != null;
      var estimateDone = Boolean(questions.notice._estimateDone);

      if (!hasEstimate && !estimateDone && attemptId) {
        questions.notice.estimating = true;
      }

      var formatted = formatWeakLevelNotice(questions.notice);

      var messageEl = document.getElementById('smartConfirmMessage');
      if (messageEl && questions.notice.estimating) {
        messageEl.setAttribute('aria-live', 'polite');
      }

      if (questions.notice.estimating && attemptId && !questions.notice._estimatePromise) {
        fetchEstimate(attemptId, answers.kanaKnown, questions.notice, messageEl);
      }

      var weakChoice = await askChoice(formatted.title, formatted.message, noticeOptions);
      if (weakChoice === null) return null;
      answers.entryChoice = weakChoice;
      return answers;
    }

    var level = questions.level;
    var target = questions.targetLevel;
    var next = nextLevel(level);
    var spec = {
      INCOMPLETE_FOUNDATION: { title: 'Bạn đã vững ' + level + ' chưa?',
        text: 'Bạn chưa hoàn thành bài test, vì vậy hệ thống không thể xác định được bạn đã vững cấp độ ' + level + ' hay chưa.',
        options: [{ value: 'SOLID', label: 'Vững rồi — không cần học lại ' + level },
          { value: 'NOT_SOLID', label: 'Chưa vững — học thêm ' + level + ' trong lộ trình', primary: true },
          { value: 'RETAKE', label: 'Làm lại Smart Test' }] },
      MISTAKE_REVIEW: { title: 'Ôn lỗi ' + level + ' trước?',
        text: mistakeSummary(level) + ' Bạn muốn ôn các lỗi này trước khi học tiếp?',
        options: [{ value: 'REVIEW_MISTAKES', label: 'Ôn các lỗi này trước (khởi động)', primary: true },
          { value: 'SKIP_REVIEW', label: next === target ? 'Bỏ qua, vào thẳng ' + target : 'Bỏ qua, học tiếp từ ' + next }] }
    }[questions.choiceKind];
    if (!spec) return answers;
    var choice = await askChoice(spec.title, spec.text, spec.options);
    if (choice === null) return null;
    if (choice === 'RETAKE') {
      await retakeSmartTest();
      return null;
    }
    answers.entryChoice = choice;
    return answers;
  }

  var createPathButton = document.getElementById('createPathButton');
  if (createPathButton) {
    createPathButton.addEventListener('click', async function (event) {
      if (pathLookupState !== 'none') return;
      event.preventDefault();
      // [JLAS-W0108] Decision 34: a JLPT learner with the path on goes straight to the confirmation page, never
      // through the old entry questions below.
      var jpathUrl = window.JpathSurveyFlow && window.JpathSurveyFlow.smartTestHandoffUrl(jpathConfig, state.goal, state.attemptId);
      if (jpathUrl) return window.location.assign(jpathUrl);
      // [JLAS-W0108] Owner 07/11: only the new paths remain; /learning/path hands a Smart Test to the JLPT confirmation.
      if (state.attemptId) return window.location.assign('/learning/path?assessmentAttemptId=' + encodeURIComponent(state.attemptId));
      return window.location.assign('/learning');
    });
  }

  document.getElementById('restartButton').addEventListener('click', function () {
    state = { attemptId: null, currentQuestion: null, goal: null, result: null }; localStorage.removeItem(ACTIVE_ATTEMPT_KEY); window.location.assign('/survey');
  });

  // [JLAS-W0108] JLPT path flag for the "Tạo lộ trình" hand-off; any failure keeps the old flow.
  var jpathConfig = { enabled: false };
  if (window.JpathSurveyFlow) {
    api('/api/learning/jlpt/config').then(function (c) {
      if (c) jpathConfig = c;
      if (state.result) updatePathActions();
    }).catch(function () {});
  }

  var activeAttempt = localStorage.getItem(ACTIVE_ATTEMPT_KEY);
  if (activeAttempt) {
    api('/api/assessment/smart-tests/' + activeAttempt)
      .then(render)
      .catch(function () { localStorage.removeItem(ACTIVE_ATTEMPT_KEY); show(emptyView); });
  } else show(emptyView);

  if (typeof module !== "undefined" && module.exports) {
    module.exports = {
      formatWeakLevelNotice: formatWeakLevelNotice,
      askEntryQuestions: askEntryQuestions,
      askChoice: askChoice,
      fetchEstimate: fetchEstimate
    };
  }
})();
