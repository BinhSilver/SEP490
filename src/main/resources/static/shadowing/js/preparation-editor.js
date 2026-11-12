(function () {
  'use strict';
  const videoId = document.getElementById('video-id')?.value;
  if (!videoId || !document.body.classList.contains('page-shadowing-preparation')) return;
  const base = '/api/videos/' + encodeURIComponent(videoId) + '/practice-preparation';
  const progress = document.getElementById('prepare-exercises-progress');
  const progressBar = document.getElementById('prepare-progress-bar');
  const suggestions = document.getElementById('prepare-exercises-suggestions');
  const preview = document.getElementById('prepare-exercises-preview');
  const generationReport = document.getElementById('prepare-generation-report');
  const generationMissing = document.getElementById('prepare-generation-missing');
  const generationIssues = document.getElementById('prepare-generation-issues');
  const generationIssueList = document.getElementById('prepare-generation-issue-list');
  const reviewLevel = document.getElementById('prepare-review-level');
  const reviewKind = document.getElementById('prepare-review-kind');
  const showMore = document.getElementById('prepare-show-more');
  const start = document.getElementById('prepare-exercises-start');
  const selectionBar = document.getElementById('prepare-selection-bar');
  const selectPage = document.getElementById('prepare-select-page');
  const selectScope = document.getElementById('prepare-select-scope');
  const approveSelected = document.getElementById('prepare-approve-selected');
  const rejectSelected = document.getElementById('prepare-reject-selected');
  const media = document.getElementById('preparation-video');
  const videoLevel = document.getElementById('prepare-video-jlpt-level');
  const videoLevelSave = document.getElementById('prepare-video-level-save');
  const videoLevelStatus = document.getElementById('prepare-video-level-status');
  const contributionSection = document.querySelector('.preparation-contribute');
  const contribution = contributionSection.querySelector('p');
  const preflight = window.ShadowingPreparationPreflight.create(document.getElementById('prepare-preflight'), {
    reopen: document.getElementById('prepare-preflight-open'), next: start,
    retry: function () { preparePage(true); }
  });
  const analyze = document.getElementById('prepare-analyze');
  const manual = window.ShadowingManualQuestions.create(document.getElementById('prepare-manual'), {
    save: function (payload) { return request(base + '/questions/manual', { method: 'POST', body: JSON.stringify(payload) }); },
    saved: async function () {
      currentFilter = 'DRAFT';
      filterTouched = true;
      reviewLevel.value = '';
      reviewKind.value = '';
      document.querySelectorAll('.preparation-filter button[data-filter]').forEach(function (button) {
        button.setAttribute('aria-pressed', String(button.dataset.filter === 'DRAFT'));
      });
      visibleCount = 8;
      await loadStatus();
    },
    listen: playSegment
  });
  let preparationPromise = null;
  let latestStatus = null;
  let pollTimer = null;
  let initialized = false;
  let hasStoredKinds = false;
  let kindTouched = false;
  let levelTouched = false;
  let sourceById = new Map();
  let stopPreview = null;
  let activePlayButton = null;
  let questions = [];
  let renderedQuestionSignature = null;
  let currentFilter = 'ALL';
  let filterTouched = false;
  let visibleCount = 8;
  let remainingByKind = {};
  let evidenceSignature = '';
  let canEdit = false;
  let isPublicVideo = false;
  const selectedQuestionIds = new Set();
  let filteredQuestionIds = [];
  const levelNames = { EASY: 'Dễ', MEDIUM: 'Vừa', HARD: 'Khó' };
  const kindNames = {
    GAP: 'Điền nội dung', ACTION_SEQUENCE: 'Hành động và thứ tự',
    DETAIL: 'Thông tin cụ thể', REASON_PURPOSE: 'Lý do và mục đích',
    MAIN_IDEA: 'Ý chính và kết luận', PROBLEM_SOLUTION: 'Vấn đề và giải pháp',
    ATTITUDE_DECISION: 'Thái độ và quyết định'
  };

  function showSuggestionStatus(message) {
    suggestions.textContent = message || '';
    suggestions.hidden = !message;
  }

  async function loadSources() {
    const [subtitles, video] = await Promise.all([
      request('/api/videos/' + encodeURIComponent(videoId) + '/subtitles'),
      request('/api/videos/' + encodeURIComponent(videoId))
    ]);
    sourceById = new Map((Array.isArray(subtitles) ? subtitles : [])
      .map(function (subtitle) { return [Number(subtitle.id), subtitle]; }));
    document.getElementById('prepare-video-title').textContent = video.title || 'Video đang chỉnh sửa';
    preflight.video(video.title || 'Video đang chỉnh sửa', sourceById.size);
    isPublicVideo = video.visibility === 'PUBLIC';
    await window.ShadowingYoutube.load(media, videoId, {
      meta: video,
      streamUrl: window.mediaStreamUrl
        ? window.mediaStreamUrl(videoId)
        : '/api/videos/' + encodeURIComponent(videoId) + '/stream'
    });
    document.querySelector('.preparation-listen').classList.toggle('is-audio', Boolean(video.audioOnly));
  }

  function playSegment(question, button) {
    document.querySelectorAll('.preparation-question-play').forEach(function (play) {
      play.classList.remove('is-playing');
      play.textContent = play.dataset.label || 'Xem dẫn chứng';
    });
    if (stopPreview) media.removeEventListener('timeupdate', stopPreview);
    activePlayButton = button;
    stopPreview = function () {
      if (media.currentTime >= Number(question.endTime) - 0.03) {
        media.pause();
        media.removeEventListener('timeupdate', stopPreview);
        stopPreview = null;
        button.classList.remove('is-playing');
        button.textContent = button.dataset.label || 'Xem dẫn chứng';
        activePlayButton = null;
      }
    };
    media.addEventListener('timeupdate', stopPreview);
    const begin = function () {
      media.currentTime = Math.max(0, Number(question.startTime) || 0);
      if (window.matchMedia('(max-width: 900px)').matches) {
        media.scrollIntoView({ behavior: 'smooth', block: 'center' });
      }
      button.classList.add('is-playing');
      button.textContent = 'Đang phát';
      const playback = media.play();
      if (playback?.catch) playback.catch(function () {
        button.classList.remove('is-playing');
        button.textContent = button.dataset.label || 'Xem dẫn chứng';
        progress.textContent = 'Không phát được đoạn này.';
      });
    };
    if (media.readyState >= 1) begin();
    else media.addEventListener('loadedmetadata', begin, { once: true });
  }

  media.addEventListener('pause', function () {
    if (!activePlayButton) return;
    activePlayButton.classList.remove('is-playing');
    activePlayButton.textContent = activePlayButton.dataset.label || 'Xem dẫn chứng';
    activePlayButton = null;
  });

  function headers() {
    return window.authHeaders ? window.authHeaders(true) : { 'Content-Type': 'application/json' };
  }

  async function request(url, options) {
    const response = await fetch(url, { headers: headers(), ...options });
    const data = await response.json().catch(function () { return {}; });
    if (!response.ok) throw new Error(data.message || data.error || 'Không tải được dữ liệu bài tập');
    return data;
  }

  function selected(containerId) {
    return Array.from(document.querySelectorAll('#' + containerId + ' input[type="checkbox"]:checked'))
      .map(function (input) { return input.value; });
  }

  function updateSummaries() {
    const levels = selected('prepare-levels');
    const kinds = selected('prepare-kinds');
    document.getElementById('prepare-level-summary').textContent = levels.length
      ? levels.map(function (level) { return levelNames[level]; }).join(', ') : 'Chưa chọn';
    document.getElementById('prepare-kind-summary').textContent = kinds.length === 1
      ? kindNames[kinds[0]] : kinds.length ? kinds.length + ' dạng đã chọn' : 'Chưa chọn';
  }

  function updateCapacity(remaining, preparedCounts) {
    remainingByKind = remaining || {};
    document.querySelectorAll('.preparation-kind-row').forEach(function (row) {
      const kind = row.dataset.kind;
      const available = Math.max(0, Number(remainingByKind[kind]) || 0);
      const checkbox = row.querySelector('input[type="checkbox"]');
      const amount = row.querySelector('input[type="number"]');
      const prepared = Math.max(0, Number(preparedCounts?.[kind]) || 0);
      row.querySelector('.preparation-capacity').textContent =
        'Đang có ' + prepared + ' · Ước tính còn ' + available + ' câu';
      checkbox.disabled = available === 0;
      if (checkbox.disabled) checkbox.checked = false;
      amount.disabled = !checkbox.checked || available === 0;
      amount.min = available ? '1' : '0';
      amount.max = String(available);
      if (available === 0) {
        amount.value = '0';
      } else if (Number(amount.value) < 1) {
        amount.value = String(Math.min(available, Number(amount.defaultValue) || 1));
      } else if (Number(amount.value) > available) {
        amount.value = String(available);
      }
    });
    updateSummaries();
  }

  function applySelection(containerId, values) {
    if (!Array.isArray(values) || !values.length) return;
    document.querySelectorAll('#' + containerId + ' input[type="checkbox"]').forEach(function (input) {
      input.checked = values.includes(input.value);
    });
    updateSummaries();
  }

  function renderSuggestionIcons(values) {
    document.querySelectorAll('.preparation-kind-row').forEach(function (row) {
      const icon = row.querySelector('.prepare-kind-suggestion');
      icon.innerHTML = '<i class="fa-solid fa-thumbs-up" aria-hidden="true"></i>';
      icon.setAttribute('role', 'img');
      icon.setAttribute('aria-label', 'AI gợi ý');
      icon.title = 'AI gợi ý';
      icon.hidden = !values.includes(row.dataset.kind);
    });
  }

  function evidenceDetails(lines, ids, label) {
    const details = document.createElement('details');
    details.className = 'preparation-evidence';
    const summary = document.createElement('summary');
    summary.textContent = label;
    details.appendChild(summary);
    ids.forEach(function (id) {
      const line = lines.find(function (item) { return Number(item.id) === Number(id); });
      const text = document.createElement('p');
      text.lang = 'ja';
      text.textContent = line ? formatTime(line.startTime) + ' · ' + (line.originalText || line.text)
        : 'Dòng #' + id + ' không nằm trong phụ đề AI đã nhận.';
      details.appendChild(text);
    });
    const available = lines.filter(function (item) { return ids.includes(Number(item.id)); });
    if (available.length) {
      const play = document.createElement('button');
      play.type = 'button';
      play.className = 'preparation-question-play';
      play.dataset.label = 'Nghe dẫn chứng · ' + formatTime(available[0].startTime);
      play.textContent = play.dataset.label;
      play.addEventListener('click', function () {
        playSegment({ startTime: Math.min(...available.map(item => Number(item.startTime))),
          endTime: Math.max(...available.map(item => Number(item.endTime))) }, play);
      });
      details.appendChild(play);
    }
    return details;
  }

  function renderEvidencePlans(plans) {
    const values = Array.isArray(plans) ? plans : [];
    const signature = JSON.stringify(values);
    if (signature === evidenceSignature) return;
    evidenceSignature = signature;
    document.querySelectorAll('.preparation-kind-row').forEach(function (row) {
      row.querySelector('.preparation-plan-list')?.remove();
      const matches = values.filter(function (plan) { return plan.kind === row.dataset.kind; });
      if (!matches.length) return;
      const list = document.createElement('details');
      list.className = 'preparation-plan-list';
      const summary = document.createElement('summary');
      summary.textContent = 'Xem dẫn chứng cho ước tính';
      list.appendChild(summary);
      matches.slice(0, row.dataset.kind === 'MAIN_IDEA' ? 2 : 20).forEach(function (plan) {
        const item = document.createElement('article');
        const focus = document.createElement('p');
        focus.textContent = plan.focus;
        item.appendChild(focus);
        item.appendChild(evidenceDetails(Array.from(sourceById.values()), plan.sourceIds || [], 'Phụ đề gốc'));
        if (plan.contextSummary) {
          const context = document.createElement('p');
          context.className = 'preparation-evidence-context';
          context.textContent = 'Ngữ cảnh: ' + plan.contextSummary;
          item.appendChild(context);
        }
        list.appendChild(item);
      });
      const note = document.createElement('p');
      note.className = 'preparation-evidence-context';
      note.textContent = 'Ước tính dựa trên dẫn chứng. Câu tạo ra vẫn cần kiểm tra và duyệt.';
      list.appendChild(note);
      row.appendChild(list);
    });
  }

  function formatTime(seconds) {
    const value = Math.max(0, Math.floor(Number(seconds) || 0));
    return Math.floor(value / 60) + ':' + String(value % 60).padStart(2, '0');
  }

  function reviewChoiceOrder(choices, questionId) {
    const ordered = choices.slice();
    let seed = (Number(questionId) || 1) >>> 0;
    for (let index = ordered.length - 1; index > 0; index--) {
      seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
      const swap = seed % (index + 1);
      [ordered[index], ordered[swap]] = [ordered[swap], ordered[index]];
    }
    return ordered;
  }

  function updateSelectionBar() {
    selectionBar.hidden = !canEdit || filteredQuestionIds.length === 0;
    selectScope.textContent = 'Chọn tất cả ' + filteredQuestionIds.length + ' câu đang lọc';
    selectPage.checked = filteredQuestionIds.length > 0
      && filteredQuestionIds.every(function (id) { return selectedQuestionIds.has(id); });
    selectPage.indeterminate = filteredQuestionIds.some(function (id) { return selectedQuestionIds.has(id); })
      && !selectPage.checked;
    selectPage.disabled = filteredQuestionIds.length === 0;
    const selectedDraftCount = questions.filter(function (question) {
      return question.status === 'DRAFT' && selectedQuestionIds.has(question.id);
    }).length;
    approveSelected.disabled = selectedDraftCount === 0;
    rejectSelected.disabled = selectedQuestionIds.size === 0;
    approveSelected.textContent = selectedDraftCount
      ? 'Duyệt ' + selectedDraftCount + ' câu nháp' : 'Duyệt câu nháp';
    rejectSelected.textContent = selectedQuestionIds.size
      ? 'Xóa ' + selectedQuestionIds.size + ' câu đã chọn' : 'Xóa đã chọn';
  }

  function jobStatusText(data) {
    if (data.status === 'RUNNING') return 'Đang tạo câu hỏi · ' + data.progress + '%';
    if (data.status === 'QUEUED') return 'Đang chờ xử lý';
    if (data.status === 'REVIEW') return 'Có câu đang chờ duyệt';
    if (data.status === 'FAILED') {
      const excluded = Number(data.excludedQuestionCount) || 0;
      return excluded ? 'Không tạo được câu hỏi mới · ' + excluded + ' câu cũ cần tạo lại'
        : 'Không tạo được câu hỏi mới';
    }
    if (data.status === 'PARTIAL') {
      const targets = data.targets || {};
      const wanted = Object.values(targets).reduce(function (sum, value) { return sum + Number(value || 0); }, 0);
      const missing = Object.values(data.missing || {}).reduce(function (sum, value) {
        return sum + Number(value || 0);
      }, 0);
      return wanted ? 'Đã có ' + Math.max(0, wanted - missing) + '/' + wanted
        + ' câu hợp lệ · còn thiếu ' + missing
        : 'Chưa tạo đủ câu đạt yêu cầu';
    }
    return data.status === 'READY' ? 'Sẵn sàng' : 'Chưa tạo';
  }

  function renderGenerationReport(data) {
    const missing = Object.entries(data.missing || {}).filter(function (entry) {
      return Number(entry[1]) > 0;
    });
    const issues = Array.isArray(data.generationIssues) ? data.generationIssues : [];
    generationReport.hidden = !data.owner || !missing.length
      || (data.status !== 'PARTIAL' && data.status !== 'FAILED');
    if (generationReport.hidden) return;
    generationMissing.textContent = missing.map(function (entry) {
      return (kindNames[entry[0]] || entry[0]) + ': thiếu ' + entry[1] + ' câu';
    }).join(' · ') + '.';
    generationIssues.hidden = issues.length === 0;
    if (issues.length) generationIssues.open = true;
    generationIssueList.replaceChildren();
    if (!issues.length) {
      generationMissing.textContent += data.missingAfterCompletion
        ? ' Số câu đang có đã giảm; bạn có thể tạo câu thay thế.'
        : ' Lượt tạo cũ chưa lưu lý do loại từng câu.';
      return;
    }
    generationIssues.querySelector('summary').textContent =
      issues.length + ' chi tiết về câu chưa đạt hoặc còn thiếu';
    issues.forEach(function (issue) {
      const row = document.createElement('article');
      row.className = 'preparation-generation-issue';
      const meta = document.createElement('strong');
      meta.textContent = [kindNames[issue.kind] || 'Đoạn nghe', levelNames[issue.level],
        Number.isFinite(Number(issue.startTime)) && issue.startTime !== null
          ? formatTime(issue.startTime) : null].filter(Boolean).join(' · ');
      const reason = document.createElement('p');
      reason.textContent = issue.reason;
      row.append(meta, reason);
      if (issue.sourceIds?.length) {
        row.appendChild(evidenceDetails(issue.evidence || [], issue.sourceIds,
          'Dẫn chứng AI dùng · ' + issue.sourceIds.map(id => '#' + id).join(', ')));
      }
      if (issue.suppliedIds?.length) {
        const marked = (issue.evidence || []).filter(line => line.marked).map(line => Number(line.id));
        if (marked.length && JSON.stringify(marked) !== JSON.stringify(issue.sourceIds)) {
          row.appendChild(evidenceDetails(issue.evidence || [], marked, 'Dẫn chứng được đánh dấu khi ước tính'));
        }
        row.appendChild(evidenceDetails(issue.evidence || [], issue.suppliedIds,
          'Xem toàn bộ đoạn phụ đề đã gửi cho AI'));
      }
      if (issue.prompt) {
        const prompt = document.createElement('p');
        prompt.lang = 'ja';
        prompt.textContent = issue.prompt;
        row.appendChild(prompt);
      }
      if (issue.answer) {
        const answer = document.createElement('p');
        answer.lang = 'ja';
        answer.textContent = 'Đáp án AI đưa ra: ' + issue.answer;
        row.appendChild(answer);
      }
      if (Array.isArray(issue.choices) && issue.choices.length) {
        const choices = document.createElement('ol');
        choices.className = 'preparation-generation-choices';
        issue.choices.forEach(function (choice) {
          const item = document.createElement('li');
          item.lang = 'ja';
          item.textContent = choice;
          choices.appendChild(item);
        });
        row.appendChild(choices);
      }
      if (!issue.sourceIds?.length && issue.startTime !== null && issue.startTime !== undefined) {
        const play = document.createElement('button');
        play.type = 'button';
        play.className = 'preparation-question-play';
        play.dataset.label = 'Nghe đoạn · ' + formatTime(issue.startTime);
        play.textContent = play.dataset.label;
        play.addEventListener('click', function () {
          playSegment({ startTime: issue.startTime, endTime: Number(issue.startTime) + 8 }, play);
        });
        row.appendChild(play);
      }
      generationIssueList.appendChild(row);
    });
  }

  function makeQuestionCard(question) {
    const row = document.createElement('article');
    row.className = 'preparation-question';
    const head = document.createElement('div');
    head.className = 'preparation-question-head';
    if (canEdit) {
      const pick = document.createElement('label');
      pick.className = 'preparation-question-select';
      const checkbox = document.createElement('input');
      checkbox.type = 'checkbox';
      checkbox.checked = selectedQuestionIds.has(question.id);
      checkbox.setAttribute('aria-label', 'Chọn câu hỏi: ' + question.prompt);
      checkbox.addEventListener('change', function () {
        if (checkbox.checked) selectedQuestionIds.add(question.id);
        else selectedQuestionIds.delete(question.id);
        updateSelectionBar();
      });
      pick.appendChild(checkbox);
      head.appendChild(pick);
    }
    const kind = document.createElement('span');
    kind.className = 'preparation-question-kind';
    kind.textContent = kindNames[question.kind] || 'Câu hỏi';
    const level = document.createElement('span');
    level.textContent = '· ' + (levelNames[question.level] || 'Bài nghe');
    const time = document.createElement('span');
    time.textContent = '· ' + formatTime(question.startTime);
    const status = document.createElement('span');
    status.className = 'preparation-question-status';
    status.classList.toggle('is-draft', question.status === 'DRAFT');
    status.textContent = question.status === 'DRAFT' ? 'Bản nháp' : 'Đã duyệt';
    head.append(kind, level, time, status);

    const main = document.createElement('div');
    main.className = 'preparation-question-main';
    const content = document.createElement('div');
    const prompt = document.createElement('p');
    prompt.className = 'preparation-question-prompt';
    prompt.lang = 'ja';
    prompt.textContent = question.prompt;
    const answer = document.createElement('p');
    answer.className = 'preparation-question-answer';
    answer.append(document.createTextNode('Đáp án: '));
    const answerText = document.createElement('strong');
    answerText.lang = 'ja';
    answerText.textContent = question.answer;
    answer.appendChild(answerText);
    content.appendChild(prompt);
    if (question.choices?.length === 4) {
      const choices = document.createElement('ol');
      choices.className = 'preparation-question-choices';
      choices.setAttribute('aria-label', 'Bốn lựa chọn trả lời');
      reviewChoiceOrder(question.choices, question.id).forEach(function (choice) {
        const item = document.createElement('li');
        item.lang = 'ja';
        item.classList.toggle('is-answer', choice === question.answer);
        const label = document.createElement('span');
        label.textContent = choice;
        item.appendChild(label);
        if (choice === question.answer) {
          const marker = document.createElement('span');
          marker.className = 'preparation-choice-correct';
          marker.lang = 'vi';
          marker.textContent = 'Đáp án đúng';
          item.appendChild(marker);
        }
        choices.appendChild(item);
      });
      content.appendChild(choices);
    } else {
      content.appendChild(answer);
    }

    const actions = document.createElement('div');
    actions.className = 'preparation-question-actions';
    if (canEdit && question.status === 'DRAFT') {
      const accept = document.createElement('button');
      accept.type = 'button';
      accept.className = 'preparation-question-approve';
      accept.textContent = 'Duyệt câu';
      accept.addEventListener('click', async function () {
        accept.disabled = true;
        try {
          await request(base + '/questions/' + encodeURIComponent(question.id) + '/approve',
            { method: 'POST', body: '{}' });
          selectedQuestionIds.delete(question.id);
          await loadStatus();
        } catch (error) {
          progress.textContent = error.message;
          accept.disabled = false;
        }
      });
      actions.appendChild(accept);
    }
    const reject = document.createElement('button');
    reject.type = 'button';
    reject.className = 'preparation-question-reject';
    reject.textContent = 'Loại câu';
    reject.hidden = !canEdit;
    reject.setAttribute('aria-label', 'Loại câu hỏi: ' + question.prompt);
    reject.addEventListener('click', async function () {
      const confirmed = typeof window.showConfirm === 'function'
        ? await window.showConfirm({ title: 'Loại câu hỏi này?',
          message: 'Câu này sẽ không xuất hiện trong bài học. Bạn có thể tạo câu khác sau.',
          confirmText: 'Loại câu', cancelText: 'Giữ lại', variant: 'danger' })
        : window.confirm('Loại câu hỏi này?');
      if (!confirmed) return;
      reject.disabled = true;
      try {
        await request(base + '/questions/' + encodeURIComponent(question.id), { method: 'DELETE' });
        await loadStatus();
      } catch (error) {
        progress.textContent = error.message;
        reject.disabled = false;
      }
    });
    actions.append(reject);
    main.append(content, actions);

    const details = document.createElement('details');
    const summary = document.createElement('summary');
    summary.className = 'preparation-question-play';
    summary.dataset.label = 'Xem dẫn chứng · ' + formatTime(question.startTime);
    summary.textContent = summary.dataset.label;
    summary.addEventListener('click', function () { playSegment(question, summary); });
    details.appendChild(summary);
    const evidence = document.createElement('p');
    evidence.lang = 'ja';
    evidence.textContent = (question.sourceIds || []).map(function (id) {
      return sourceById.get(Number(id))?.text || '';
    }).filter(Boolean).join(' ');
    details.appendChild(evidence);
    if (question.explanation) {
      const explanation = document.createElement('p');
      explanation.textContent = 'Giải thích: ' + question.explanation;
      details.appendChild(explanation);
    }
    row.append(head, main, details);
    return row;
  }

  function renderPreview() {
    preview.replaceChildren();
    const filtered = questions.filter(function (question) {
      return (currentFilter === 'ALL' || question.status === currentFilter)
        && (!reviewLevel.value || question.level === reviewLevel.value)
        && (!reviewKind.value || question.kind === reviewKind.value);
    });
    filteredQuestionIds = filtered.map(function (question) { return question.id; });
    if (!filtered.length) {
      const empty = document.createElement('p');
      empty.className = 'preparation-empty';
      empty.textContent = questions.length
        ? 'Không có câu hỏi trong mục này.'
        : 'Chưa có câu hỏi mẫu';
      preview.appendChild(empty);
    } else {
      filtered.slice(0, visibleCount).forEach(function (question) {
        preview.appendChild(makeQuestionCard(question));
      });
    }
    showMore.hidden = filtered.length <= visibleCount;
    updateSelectionBar();
  }

  function updateFilterCounts() {
    const scoped = questions.filter(function (question) {
      return (!reviewLevel.value || question.level === reviewLevel.value)
        && (!reviewKind.value || question.kind === reviewKind.value);
    });
    document.querySelectorAll('.preparation-filter button[data-filter]').forEach(function (button) {
      const number = button.dataset.filter === 'ALL' ? scoped.length
        : scoped.filter(function (question) { return question.status === button.dataset.filter; }).length;
      button.querySelector('span').textContent = String(number);
    });
  }

  async function loadStatus() {
    const data = await request(base);
    latestStatus = data;
    const owner = Boolean(data.owner);
    canEdit = owner;
    manual.update(data.manualSources || [], owner);
    analyze.disabled = !data.aiAvailable || data.status === 'RUNNING' || data.status === 'QUEUED';
    document.getElementById('prepare-back-link').hidden = !owner;
    document.querySelector('.preparation-video-level').hidden = !owner;
    contributionSection.hidden = owner && !isPublicVideo;
    contribution.textContent = owner
      ? 'Bạn duyệt câu hỏi được đóng góp'
      : data.contributedToday
        ? 'Đã đóng góp hôm nay · chờ chủ video duyệt'
        : 'Bạn có thể tạo thêm · chủ video duyệt';
    progress.textContent = jobStatusText(data);
    progress.title = data.status === 'PARTIAL' || data.status === 'FAILED' ? data.message || '' : '';
    renderGenerationReport(data);
    progressBar.value = Number(data.progress) || 0;
    progressBar.hidden = data.status !== 'RUNNING' && data.status !== 'QUEUED';
    if (!initialized) {
      applySelection('prepare-levels', data.levels);
      applySelection('prepare-kinds', data.kinds);
      hasStoredKinds = Array.isArray(data.kinds) && data.kinds.length > 0;
      initialized = true;
    }
    if (!levelTouched) videoLevel.value = data.selectedJlptLevel || '';
    videoLevelStatus.textContent = data.levelOutdated ? 'Phụ đề đã thay đổi' : '';
    videoLevelSave.disabled = !videoLevel.value;
    questions = Array.isArray(data.questions) ? data.questions : [];
    if (!filterTouched) {
      currentFilter = Number(data.draftCount) > 0 ? 'DRAFT' : 'ALL';
      document.querySelectorAll('.preparation-filter button[data-filter]').forEach(function (item) {
        item.setAttribute('aria-pressed', String(item.dataset.filter === currentFilter));
      });
    }
    const validQuestionIds = new Set(questions.map(function (question) { return question.id; }));
    selectedQuestionIds.forEach(function (id) {
      if (!validQuestionIds.has(id)) selectedQuestionIds.delete(id);
    });
    updateCapacity(data.remaining, data.preparedCounts);
    renderEvidencePlans(data.evidencePlans);
    const signature = questions.map(function (question) {
      return question.id + ':' + question.status;
    }).join('|');
    if (signature !== renderedQuestionSignature) {
      renderedQuestionSignature = signature;
      updateFilterCounts();
      renderPreview();
    }
    document.querySelectorAll('.preparation-question-reject').forEach(function (button) {
      button.hidden = !owner;
    });
    updateSelectionBar();
    updateCreateAvailability(data);
    start.textContent = owner
      ? questions.length ? 'Tạo thêm câu hỏi' : 'Tạo câu hỏi' : 'Đóng góp câu hỏi';
    if (!data.aiAvailable) showSuggestionStatus(owner
      ? 'Bạn có thể nhập câu hỏi thủ công. Tạo tự động cần cấu hình OpenRouter.'
      : 'Tạo tự động cần cấu hình OpenRouter.');
    if (data.status === 'RUNNING' || data.status === 'QUEUED') {
      if (!pollTimer) pollTimer = setInterval(function () { loadStatus().catch(function () {}); }, 4000);
    } else if (pollTimer) {
      clearInterval(pollTimer);
      pollTimer = null;
    }
    return data;
  }

  function updateCreateAvailability(data) {
    start.disabled = !preflight.ready || data.status === 'RUNNING' || data.status === 'QUEUED'
      || !data.aiAvailable || (!data.owner && data.contributedToday)
      || !Object.values(data.remaining || {}).some(count => Number(count) > 0);
  }

  async function loadSuggestions() {
    preflight.phase('analysis');
    renderSuggestionIcons([]);
    const data = await request(base + '/suggestions', { method: 'POST', body: '{}' });
    const values = Array.isArray(data.suggestions) ? data.suggestions : [];
    renderSuggestionIcons(values);
    showSuggestionStatus(values.length ? ''
      : 'AI chưa tìm thấy dạng hỏi hội thoại đủ căn cứ trong phụ đề hiện tại.');
    preflight.phase('finalizing');
    await loadStatus();
    if (values.length && !hasStoredKinds && !kindTouched) {
      document.querySelectorAll('#prepare-kinds input[type="checkbox"]:not([value="GAP"])').forEach(function (input) {
        input.checked = values.includes(input.value) && !input.disabled;
        input.closest('.preparation-kind-row').querySelector('input[type="number"]').disabled = !input.checked;
      });
      updateSummaries();
    }
  }

  function preparePage(analyzeSources) {
    if (preparationPromise) return preparationPromise;
    start.disabled = true;
    showSuggestionStatus('');
    if (analyzeSources) preflight.begin();
    preparationPromise = (async function () {
      await loadSources();
      await loadStatus();
      if (analyzeSources) await loadSuggestions();
      preflight.complete(latestStatus.remaining);
      updateCreateAvailability(latestStatus);
    }()).catch(function (error) {
      if (analyzeSources) preflight.fail(error.message || 'Không tải được dữ liệu chuẩn bị. Hãy thử lại.');
      else showSuggestionStatus(error.message || 'Không tải được dữ liệu. Hãy tải lại trang.');
      start.disabled = true;
    }).finally(function () { preparationPromise = null; });
    return preparationPromise;
  }

  analyze.addEventListener('click', function () { preparePage(true); });

  document.querySelectorAll('.preparation-filter button[data-filter]').forEach(function (button) {
    button.addEventListener('click', function () {
      selectedQuestionIds.clear();
      filterTouched = true;
      currentFilter = button.dataset.filter;
      visibleCount = 8;
      document.querySelectorAll('.preparation-filter button[data-filter]').forEach(function (item) {
        item.setAttribute('aria-pressed', String(item === button));
      });
      renderPreview();
    });
  });
  [reviewLevel, reviewKind].forEach(function (select) {
    select.addEventListener('change', function () {
      selectedQuestionIds.clear();
      visibleCount = 8;
      updateFilterCounts();
      renderPreview();
    });
  });

  showMore.addEventListener('click', function () {
    visibleCount += 8;
    renderPreview();
  });

  document.querySelectorAll('.preparation-dropdown').forEach(function (dropdown) {
    dropdown.addEventListener('toggle', function () {
      if (dropdown.open) document.querySelectorAll('.preparation-dropdown').forEach(function (other) {
        if (other !== dropdown) other.open = false;
      });
    });
  });

  document.querySelectorAll('#prepare-levels input[type="checkbox"]').forEach(function (input) {
    input.addEventListener('change', updateSummaries);
  });
  document.querySelectorAll('#prepare-kinds input[type="checkbox"]').forEach(function (input) {
    input.addEventListener('change', function () {
      kindTouched = true;
      input.closest('.preparation-kind-row').querySelector('input[type="number"]').disabled = !input.checked;
      updateSummaries();
    });
  });

  videoLevel.addEventListener('change', function () {
    levelTouched = true;
    videoLevelSave.disabled = !videoLevel.value;
    videoLevelStatus.textContent = videoLevel.value ? 'Chưa lưu thay đổi' : '';
  });

  videoLevelSave.addEventListener('click', async function () {
    if (!videoLevel.value) return;
    videoLevelSave.disabled = true;
    videoLevelStatus.textContent = 'Đang lưu…';
    try {
      await request(base + '/video-level', {
        method: 'PUT', body: JSON.stringify({ level: videoLevel.value })
      });
      levelTouched = false;
      videoLevelStatus.textContent = 'Đã lưu cấp độ video';
      await loadStatus();
    } catch (error) {
      videoLevelStatus.textContent = error.message;
      videoLevelSave.disabled = false;
    }
  });

  start.addEventListener('click', async function () {
    if (!preflight.ready) return;
    const levels = selected('prepare-levels');
    const kinds = selected('prepare-kinds');
    if (!levels.length || !kinds.length) {
      progress.textContent = 'Hãy chọn ít nhất một mức và một dạng câu hỏi.';
      return;
    }
    const amounts = {};
    for (const kind of kinds) {
      const input = document.querySelector('.preparation-kind-row[data-kind="' + kind + '"] input[type="number"]');
      const amount = Number(input.value);
      if (!Number.isInteger(amount) || amount < 1 || amount > Number(remainingByKind[kind] || 0)) {
        progress.textContent = kindNames[kind] + ': số câu phải từ 1 đến ' + (remainingByKind[kind] || 0) + '.';
        document.getElementById('prepare-kind-dropdown').open = true;
        input.focus();
        return;
      }
      amounts[kind] = amount;
    }
    const total = Object.values(amounts).reduce(function (sum, amount) { return sum + amount; }, 0);
    if (total > 21) {
      progress.textContent = 'Mỗi lượt tạo tối đa 21 câu. Hãy giảm số câu rồi tạo tiếp ở lượt sau.';
      document.getElementById('prepare-kind-dropdown').open = true;
      return;
    }
    start.disabled = true;
    progress.textContent = 'Đang đưa vào hàng chờ…';
    try {
      await request(base, { method: 'POST', body: JSON.stringify({ levels, amounts }) });
      await loadStatus();
    } catch (error) {
      progress.textContent = error.message;
      start.disabled = false;
    }
  });

  selectPage.addEventListener('change', function () {
    filteredQuestionIds.forEach(function (id) {
      if (selectPage.checked) selectedQuestionIds.add(id);
      else selectedQuestionIds.delete(id);
    });
    renderPreview();
  });

  approveSelected.addEventListener('click', async function () {
    const draftIds = questions.filter(function (question) {
      return question.status === 'DRAFT' && selectedQuestionIds.has(question.id);
    }).map(function (question) { return question.id; });
    if (!draftIds.length) return;
    approveSelected.disabled = true;
    try {
      await request(base + '/approve-selected', {
        method: 'POST', body: JSON.stringify({ ids: draftIds })
      });
      selectedQuestionIds.clear();
      await loadStatus();
    } catch (error) {
      progress.textContent = error.message;
    } finally {
      updateSelectionBar();
    }
  });

  rejectSelected.addEventListener('click', async function () {
    if (!selectedQuestionIds.size) return;
    const confirmed = typeof window.showConfirm === 'function'
      ? await window.showConfirm({ title: 'Xóa ' + selectedQuestionIds.size + ' câu đã chọn?',
        message: 'Các câu này sẽ không xuất hiện trong phiên học mới. Lịch sử học trước đó vẫn được giữ.',
        confirmText: 'Xóa câu', cancelText: 'Giữ lại', variant: 'danger' })
      : window.confirm('Xóa ' + selectedQuestionIds.size + ' câu đã chọn?');
    if (!confirmed) return;
    rejectSelected.disabled = true;
    try {
      await request(base + '/reject-selected', {
        method: 'POST', body: JSON.stringify({ ids: Array.from(selectedQuestionIds) })
      });
      selectedQuestionIds.clear();
      await loadStatus();
    } catch (error) {
      progress.textContent = error.message;
    } finally {
      updateSelectionBar();
    }
  });

  preparePage();
}());
