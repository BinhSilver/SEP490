(function () {
  'use strict';

  const videoId = document.getElementById('practice-video-id')?.value;
  if (!videoId) return;

  const title = document.getElementById('practice-video-title');
  const countLabel = document.getElementById('practice-subtitle-count');
  const videoJlpt = document.getElementById('practice-video-jlpt');
  const cards = Array.from(document.querySelectorAll('.practice-mode-card'));
  const options = document.getElementById('practice-options');
  const startButton = document.getElementById('practice-start-button');
  const startRow = document.getElementById('practice-start-row');
  const startLabel = startButton.querySelector('span');
  const startIcon = document.getElementById('practice-start-icon');
  const startKitsu = document.getElementById('practice-start-kitsu');
  const status = document.getElementById('practice-status');
  const contributeLink = document.getElementById('practice-contribute-link');
  const availableLabel = document.getElementById('practice-available-count');
  const selectAllButton = document.getElementById('practice-select-all');
  const clearAllButton = document.getElementById('practice-clear-all');
  let selectedMode = 'LISTENING';
  let subtitleCount = 0;
  let availability = {};
  let creatingSession = false;
  let creationPaused = false;
  let createdSessionId = null;
  let creationVersion = 0;
  let creationController = null;
  let creationSelection = null;
  /** Dạng người học đã tự sửa số câu (không còn tự theo số tối đa). */
  /** Mức người học đã tự sửa số câu (không còn tự theo số tối đa), theo "MỨC:DẠNG". */
  const levelTouched = {};
  /** Ô số câu của từng mức: {"MỨC:DẠNG": {checkbox, wrap, input, minus, plus, small}}. */
  const levelRows = {};

  function headers() {
    return window.authHeaders ? window.authHeaders(true) : { 'Content-Type': 'application/json' };
  }

  function message(value, error) {
    status.textContent = value || '';
    status.style.color = error ? 'var(--color-error, #a71919)' : '';
  }

  function startText() {
    return selectedMode === 'SHADOWING' ? 'Bắt đầu Shadowing' : 'Bắt đầu luyện nghe';
  }

  function setAction(label, icon) {
    startLabel.textContent = label;
    if (startIcon) startIcon.className = 'fa-solid fa-' + icon;
  }

  function showPreparing(show) {
    startKitsu.hidden = !show;
    startRow?.classList.toggle('has-mascot', show);
  }

  function resetSessionCreation() {
    creationVersion++;
    creationController?.abort();
    creationController = null;
    creationSelection = null;
    creatingSession = false;
    creationPaused = false;
    createdSessionId = null;
    showPreparing(false);
    setAction(startText(), 'play');
    message('');
  }

  function selectionKey() {
    return JSON.stringify([selectedMode, selectedCombos().sort(), selectedCount().questionCount, selectedSelectionCounts()]);
  }

  function selectMode(mode) {
    if (selectedMode !== mode) resetSessionCreation();
    selectedMode = mode;
    cards.forEach(function (card) {
      const selected = card.dataset.mode === mode;
      card.classList.toggle('is-selected', selected);
      card.setAttribute('aria-pressed', String(selected));
    });
    options.hidden = mode === 'SHADOWING';
    setAction(startText(), 'play');
    showPreparing(false);
    message('');
    updateAvailability();
  }

  function renderLevelOptions() {
    const names = { EASY: 'Dễ', MEDIUM: 'Vừa', HARD: 'Khó' };
    document.querySelectorAll('.practice-kind-card').forEach(function (card) {
      const list = card.querySelector('.practice-level-list');
      Object.entries(names).forEach(function ([level, name]) {
        const label = document.createElement('label');
        const input = document.createElement('input');
        input.type = 'checkbox';
        input.value = level;
        input.dataset.kind = card.dataset.kind;
        input.disabled = true;
        const title = document.createElement('span');
        title.textContent = name;
        const count = document.createElement('small');
        count.textContent = '…';
        label.append(input, title, levelCountControl(level + ':' + card.dataset.kind, input, count), count);
        list.append(label);
        input.addEventListener('change', updateAvailability);
      });
    });
  }

  /**
   * Số câu muốn luyện của một mức trong một dạng (− / ô số / +), chỉ hiện khi mức đó được chọn.
   * Mặc định = tối đa đang có; người học sửa thì giữ số đó (chỉ thu lại khi vượt tối đa mới).
   * Nút và ô số nằm trong <label> nhưng là phần tử tương tác nên bấm vào không bật/tắt checkbox.
   */
  function levelCountControl(pair, checkbox, small) {
    const wrap = document.createElement('span');
    wrap.className = 'practice-level-count';
    wrap.hidden = true;
    const minus = document.createElement('button');
    const input = document.createElement('input');
    const plus = document.createElement('button');
    [[minus, '−', 'Bớt một câu', -1], [plus, '+', 'Thêm một câu', 1]].forEach(function ([button, text, aria, step]) {
      button.type = 'button';
      button.textContent = text;
      button.setAttribute('aria-label', aria);
      button.addEventListener('click', function (event) {
        event.preventDefault();
        set(Number(input.value) + step);
      });
    });
    input.type = 'number';
    input.min = '0';
    input.max = '0';
    input.value = '0';
    input.setAttribute('inputmode', 'numeric');
    input.setAttribute('aria-label', 'Số câu của mức này');
    input.addEventListener('change', function () { set(input.value); });
    wrap.append(minus, input, plus);
    levelRows[pair] = { checkbox, wrap, input, minus, plus, small };
    function set(value) {
      const max = Number(input.max) || 0;
      input.value = String(Math.max(0, Math.min(max, Math.round(Number(value) || 0))));
      levelTouched[pair] = true;
      updateAvailability();
    }
    return wrap;
  }

  /** Câu không chồng thời gian trong các mức đã chọn (cùng cách backend chọn), đếm theo "MỨC:DẠNG". */
  function maxPerSelection() {
    const selected = new Set(selectedCombos());
    const result = {};
    if (!Array.isArray(availability.questions)) return result;
    let lastEnd = -Infinity;
    availability.questions.filter(function (question) {
      return selected.has(question.level + ':' + question.kind);
    }).sort(function (left, right) {
      return Number(left.endTime) - Number(right.endTime);
    }).forEach(function (question) {
      if (Number(question.startTime) >= lastEnd) {
        const pair = question.level + ':' + question.kind;
        result[pair] = (result[pair] || 0) + 1;
        lastEnd = Number(question.endTime);
      }
    });
    return result;
  }

  /** Chạy sau updateLevelCounts: mức đang chọn hiện ô số câu và "/ tối đa" thay cho "N câu". */
  function syncLevelCounts() {
    const max = maxPerSelection();
    Object.entries(levelRows).forEach(function ([pair, control]) {
      const active = control.checkbox.checked && !control.checkbox.disabled;
      const limit = active ? max[pair] || 0 : 0;
      control.wrap.hidden = !active;
      if (!active) {
        levelTouched[pair] = false;
        return;
      }
      control.input.max = String(limit);
      if (!levelTouched[pair] || Number(control.input.value) > limit) control.input.value = String(limit);
      control.minus.disabled = Number(control.input.value) <= 0;
      control.plus.disabled = Number(control.input.value) >= limit;
      control.small.textContent = '/ ' + limit;
    });
  }

  /** {"MỨC:DẠNG": số câu} cho các mức đang chọn; null khi chưa có dữ liệu chuẩn bị (giữ cách tính cũ). */
  function selectedSelectionCounts() {
    if (!Array.isArray(availability.questions)) return null;
    const counts = {};
    Object.entries(levelRows).forEach(function ([pair, control]) {
      if (control.checkbox.checked && !control.checkbox.disabled) counts[pair] = Number(control.input.value) || 0;
    });
    return counts;
  }

  function selectedCombos() {
    return Array.from(document.querySelectorAll('.practice-level-list input[type="checkbox"]:checked'))
      .map(function (input) { return input.value + ':' + input.dataset.kind; });
  }

  function nonOverlappingCount(questions) {
    const candidates = [...questions].sort(function (left, right) {
      return Number(left.endTime) - Number(right.endTime);
    });
    let count = 0;
    let lastEnd = -Infinity;
    candidates.forEach(function (question) {
      if (Number(question.startTime) >= lastEnd) {
        count++;
        lastEnd = Number(question.endTime);
      }
    });
    return count;
  }

  function selectedAvailable() {
    const selected = new Set(selectedCombos());
    const selectionCounts = selectedSelectionCounts();
    if (selectionCounts) {
      return Object.values(selectionCounts).reduce(function (total, count) { return total + count; }, 0);
    }
    if (Array.isArray(availability.questions)) {
      return nonOverlappingCount(availability.questions.filter(function (question) {
        return selected.has(question.level + ':' + question.kind);
      }));
    }
    const counts = availability.counts || {};
    return Array.from(selected).reduce(function (total, pair) {
      return total + Number(counts[pair] || 0);
    }, 0);
  }

  function syncPreparedFilters() {
    updateLevelCounts();
    if (selectedCombos().length) return;
    const gapLevels = document.querySelectorAll('.practice-kind-card[data-kind="GAP"] .practice-level-list input[type="checkbox"]:not(:disabled)');
    if (gapLevels.length) {
      gapLevels.forEach(function (input) { input.checked = true; });
    } else {
      const first = document.querySelector('.practice-level-list input[type="checkbox"]:not(:disabled)');
      if (first) first.checked = true;
    }
  }

  function updateLevelCounts() {
    if (!Array.isArray(availability.questions)) return;
    const questions = availability.questions;
    document.querySelectorAll('.practice-level-list input[type="checkbox"]').forEach(function (input) {
      const count = nonOverlappingCount(questions.filter(function (question) {
        return question.level === input.value && question.kind === input.dataset.kind;
      }));
      const label = input.closest('label');
      input.disabled = count === 0;
      if (input.disabled) input.checked = false;
      label.querySelector('small').textContent = count + ' câu';
    });
  }

  function updateAvailability() {
    updateLevelCounts();
    syncLevelCounts();
    if (creatingSession && creationSelection !== selectionKey()) resetSessionCreation();
    const availableOptions = Array.from(document.querySelectorAll('.practice-level-list input[type="checkbox"]:not(:disabled)'));
    selectAllButton.disabled = !availableOptions.some(function (input) { return !input.checked; });
    clearAllButton.disabled = !availableOptions.some(function (input) { return input.checked; });
    const ready = selectedCount().questionCount;
    availableLabel.textContent = String(ready);
    if (creatingSession) return;
    if (selectedMode === 'SHADOWING') {
      contributeLink.hidden = true;
      startButton.disabled = false;
      return;
    }
    if (availability.questions?.length && !selectedCombos().length) {
      message('Hãy chọn ít nhất một mức nghe trong các dạng câu hỏi.', true);
      contributeLink.hidden = true;
      startButton.disabled = true;
      return;
    }
    if (ready === 0 && availability.questions?.length) {
      // Có chọn mức nhưng đặt hết về 0 câu: không phải thiếu câu hỏi, chỉ cần tăng số câu.
      message('Hãy chọn ít nhất 1 câu ở mức đang chọn.', true);
      contributeLink.hidden = true;
      startButton.disabled = true;
    } else if (ready === 0) {
      message('');
      contributeLink.hidden = false;
      startButton.disabled = true;
    } else {
      message('');
      contributeLink.hidden = true;
      startButton.disabled = false;
    }
  }

  renderLevelOptions();

  selectAllButton.addEventListener('click', function () {
    document.querySelectorAll('.practice-level-list input[type="checkbox"]:not(:disabled)').forEach(function (input) {
      input.checked = true;
    });
    updateAvailability();
  });

  clearAllButton.addEventListener('click', function () {
    document.querySelectorAll('.practice-level-list input[type="checkbox"]').forEach(function (input) {
      input.checked = false;
    });
    updateAvailability();
  });

  cards.forEach(function (card) {
    card.addEventListener('click', function () { selectMode(card.dataset.mode); });
  });

  async function loadLesson() {
    try {
      const responses = await Promise.all([
        fetch('/api/videos/' + encodeURIComponent(videoId), { headers: headers() }),
        fetch('/api/videos/' + encodeURIComponent(videoId) + '/subtitles', { headers: headers() })
      ]);
      if (!responses[0].ok || !responses[1].ok) throw new Error('Không tải được bài học');
      const video = await responses[0].json();
      const subtitles = await responses[1].json();
      title.textContent = video.title || 'Bài luyện nghe';
      subtitleCount = Array.isArray(subtitles) ? subtitles.filter(item => item.text?.trim()).length : 0;
      countLabel.textContent = subtitleCount + ' câu';
    } catch (error) {
      message(error.message || 'Không tải được bài học', true);
      startButton.disabled = true;
      return;
    }
    try {
      const availabilityResponse = await fetch('/api/videos/' + encodeURIComponent(videoId)
        + '/practice-preparation/availability', { headers: headers() });
      if (!availabilityResponse.ok) throw new Error('Không tải được bộ câu hỏi đã chuẩn bị');
      availability = await availabilityResponse.json();
      if (availability.videoJlptLevel) {
        videoJlpt.textContent = ' · Cấp độ video: ' + availability.videoJlptLevel;
        videoJlpt.hidden = false;
      }
      syncPreparedFilters();
      updateAvailability();
      const hasQuestions = window.ShadowingEmptyExercises.hasQuestions(availability);
      window.ShadowingSessionGuide?.init({ autoOpen: hasQuestions });
      if (!hasQuestions) window.ShadowingEmptyExercises.show();
    } catch (error) {
      availability = {};
      if (selectedMode === 'LISTENING') message(error.message, true);
      startButton.disabled = true;
    }
  }

  function selectedCount() {
    const value = Math.min(500, selectedAvailable());
    return { allQuestions: false, questionCount: value };
  }

  startButton.addEventListener('click', async function () {
    window.ShadowingSessionGuide?.finish();
    if (creatingSession && creationSelection !== selectionKey()) resetSessionCreation();
    // While a session is being prepared the button toggles Dừng / Tiếp tục instead of starting again.
    if (creatingSession) {
      creationPaused = !creationPaused;
      if (creationPaused) {
        showPreparing(false);
        setAction('Tiếp tục', 'play');
        message(createdSessionId ? 'Phiên học đã sẵn sàng.' : 'Đã dừng chuẩn bị phiên học.');
      } else if (createdSessionId) {
        location.href = '/shadowing/exercise/' + encodeURIComponent(createdSessionId);
      } else {
        showPreparing(true);
        setAction('Dừng', 'stop');
        message('Kitsu-chan đang chuẩn bị...');
      }
      return;
    }
    if (selectedMode === 'SHADOWING') {
      location.href = '/shadowing/player/' + encodeURIComponent(videoId) + '?mode=shadowing';
      return;
    }
    const count = selectedCount();
    if (selectedAvailable() === 0) return;
    creatingSession = true;
    creationPaused = false;
    createdSessionId = null;
    creationSelection = selectionKey();
    const version = ++creationVersion;
    const controller = new AbortController();
    creationController = controller;
    showPreparing(true);
    setAction('Dừng', 'stop');
    message('Kitsu-chan đang chuẩn bị...');
    try {
      const response = await fetch('/api/shadowing-practice/sessions', {
        method: 'POST',
        signal: controller.signal,
        headers: headers(),
        body: JSON.stringify({
          videoId: Number(videoId),
          mode: selectedMode,
          selections: selectedCombos(),
          questionCount: count.questionCount,
          allQuestions: count.allQuestions,
          selectionCounts: selectedSelectionCounts()
        })
      });
      const data = await response.json().catch(function () { return {}; });
      if (version !== creationVersion) return;
      if (!response.ok) throw new Error(data.message || data.error || 'Không tạo được phiên học');
      creationController = null;
      createdSessionId = data.sessionId;
      if (creationPaused) {
        message('Phiên học đã sẵn sàng.');
        return;
      }
      location.href = '/shadowing/exercise/' + encodeURIComponent(createdSessionId);
    } catch (error) {
      if (version !== creationVersion) return;
      resetSessionCreation();
      updateAvailability();
      message(error.message || 'Không tạo được phiên học', true);
    }
  });

  // History navigation can restore this entire script from the browser's back/forward cache.
  // Keep the learner's selections, but never reuse the session that they just left.
  window.addEventListener('pagehide', resetSessionCreation);
  window.addEventListener('pageshow', function (event) {
    if (!event.persisted) return;
    resetSessionCreation();
    updateAvailability();
  });

  loadLesson();
}());
