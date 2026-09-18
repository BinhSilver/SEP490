(function () {
  'use strict';

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
  var state = { attemptId: null, currentQuestion: null };
  var questionView = document.getElementById('questionView');
  var resultView = document.getElementById('resultView');
  var emptyView = document.getElementById('emptyView');
  var notice = document.getElementById('smartTestNotice');
  var confirmView = document.getElementById('smartConfirm');
  var confirmResolver = null;

  if (!window.JlasAuth || !window.JlasAuth.requireLogin('/smart-test')) return;

  function show(view) {
    [questionView, resultView, emptyView].forEach(function (item) { item.hidden = item !== view; });
    notice.hidden = true;
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  function showError(message) {
    notice.textContent = message || 'Không thể thực hiện yêu cầu. Vui lòng thử lại.';
    notice.hidden = false;
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
    if (data.status === 'SUBMITTED' && data.result) {
      localStorage.removeItem(ACTIVE_ATTEMPT_KEY);
      renderResult(data.result);
      return;
    }
    state.currentQuestion = data.currentQuestion;
    renderQuestion(data);
  }

  function renderQuestion(data) {
    var q = data.currentQuestion;
    if (!q) return showError('Không tìm thấy câu hỏi tiếp theo.');
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
    questionText.textContent = q.questionText || '';
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
    passage.innerHTML = q.passageHtml || '';

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
      audio.preload = 'none';
      audio.src = q.audioUrl;
      media.appendChild(audio);
    } else if (q.imageUrl) {
      var image = document.createElement('img'); image.src = q.imageUrl; image.alt = 'Hình minh họa câu hỏi'; media.appendChild(image);
    }
    media.hidden = !q.audioUrl && !q.imageUrl;

    var list = document.getElementById('optionList');
    list.innerHTML = '';
    q.options.forEach(function (option, index) {
      var label = document.createElement('label'); label.className = 'option-item';
      var input = document.createElement('input'); input.type = 'radio'; input.name = 'answer'; input.value = String(index + 1);
      input.addEventListener('change', function () { document.getElementById('answerButton').disabled = false; });
      var marker = document.createElement('span'); marker.className = 'option-index'; marker.textContent = String.fromCharCode(65 + index);
      var text = document.createElement('span'); text.textContent = option;
      label.append(input, marker, text); list.appendChild(label);
    });
    document.getElementById('answerButton').disabled = true;
    show(questionView);
  }

  function renderResult(result) {
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
    show(resultView);
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

  document.getElementById('restartButton').addEventListener('click', function () {
    state = { attemptId: null, currentQuestion: null }; localStorage.removeItem(ACTIVE_ATTEMPT_KEY); window.location.assign('/survey');
  });

  var activeAttempt = localStorage.getItem(ACTIVE_ATTEMPT_KEY);
  if (activeAttempt) {
    api('/api/assessment/smart-tests/' + activeAttempt)
      .then(render)
      .catch(function () { localStorage.removeItem(ACTIVE_ATTEMPT_KEY); show(emptyView); });
  } else show(emptyView);
})();
