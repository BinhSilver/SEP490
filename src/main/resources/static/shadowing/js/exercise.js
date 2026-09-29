(function () {
  'use strict';

  const sessionId = document.getElementById('exercise-session-id')?.value;
  if (!sessionId) return;

  const mediaPanel = document.getElementById('exercise-media-panel');
  const media = document.getElementById('exercise-media');
  const listenButton = document.getElementById('exercise-listen-button');
  const listenIcon = listenButton.querySelector('i');
  const furiganaToggle = document.getElementById('exercise-furigana-toggle');
  const subtitleToggle = document.getElementById('exercise-subtitle-toggle');
  const speed = document.getElementById('exercise-speed');
  const speedControl = document.getElementById('exercise-speed-control');
  const speedToggle = document.getElementById('exercise-speed-toggle');
  const speedLabel = document.getElementById('exercise-speed-label');
  const speedMenu = document.getElementById('exercise-speed-menu');
  const speedButtons = Array.from(speedMenu.querySelectorAll('[data-speed]'));
  const questionPanel = document.getElementById('exercise-question-panel');
  const answerDock = document.getElementById('exercise-answer-dock');
  const waitingState = document.getElementById('exercise-waiting-state');
  const waitingTitle = document.getElementById('exercise-waiting-title');
  const waitingText = document.getElementById('exercise-waiting-text');
  const kitsuTip = document.getElementById('exercise-kitsu-tip');
  const kitsuVideo = document.getElementById('exercise-kitsu-video');
  const prompt = document.getElementById('exercise-prompt');
  const answerInput = document.getElementById('exercise-answer');
  const actions = document.getElementById('exercise-actions');
  const submitButton = document.getElementById('exercise-submit');
  const nextButton = document.getElementById('exercise-next');
  const status = document.getElementById('exercise-status');
  const completePanel = document.getElementById('exercise-complete');
  const workspace = document.getElementById('exercise-workspace');
  const sessionActions = document.getElementById('exercise-session-actions');
  const pauseButton = document.getElementById('exercise-pause-button');
  const resumeButton = document.getElementById('exercise-resume-button');
  const videoProgress = document.getElementById('exercise-video-progress');
  const videoTimeline = document.getElementById('exercise-video-timeline');
  const stopMarkers = document.getElementById('exercise-stop-markers');
  videoTimeline.tabIndex = 0;
  videoTimeline.setAttribute('role', 'slider');
  videoTimeline.setAttribute('aria-valuemin', '0');
  videoTimeline.setAttribute('aria-valuemax', '100');
  const videoClock = document.getElementById('exercise-video-clock');
  const liveJapanese = document.getElementById('exercise-live-japanese');
  const liveTranslation = document.getElementById('exercise-live-translation');
  const transcript = document.getElementById('exercise-transcript');

  let session = null;
  let question = null;
  let subtitles = [];
  let stops = [];
  let stopBySubtitleId = new Map();
  let transcriptRows = new Map();
  let mediaVideoId = null;
  let stoppedAtQuestion = false;
  let replayMode = false;
  let finishingRemainder = false;
  let pendingStartTime = null;
  let autoplayPending = false;
  let advancingAfterSeek = false;
  let activeLiveSubtitle = null;
  let furiganaVisible = false;
  let subtitleVisible = true;
  let sessionPaused = false;
  let resumePlaybackAfterPause = false;
  let pausedAnswerControls = null;

  function headers() {
    return window.authHeaders ? window.authHeaders(true) : { 'Content-Type': 'application/json' };
  }

  function setStatus(value, error) {
    status.textContent = value || '';
    status.style.color = error ? '#a71919' : '';
  }

  function pauseSession() {
    if (sessionPaused || !session) return;
    sessionPaused = true;
    resumePlaybackAfterPause = !media.paused;
    pausedAnswerControls = [answerInput, submitButton, nextButton].map(function (control) {
      return control.disabled;
    });
    answerInput.disabled = true;
    submitButton.disabled = true;
    nextButton.disabled = true;
    answerInput.blur();
    workspace.classList.add('is-session-paused');
    workspace.setAttribute('aria-disabled', 'true');
    media.pause();
    kitsuVideo.pause();
    pauseButton.hidden = true;
    resumeButton.hidden = false;
    setStatus('Phiên học đang tạm dừng.');
  }

  function resumeSession() {
    if (!sessionPaused) return;
    sessionPaused = false;
    workspace.classList.remove('is-session-paused');
    workspace.removeAttribute('aria-disabled');
    if (pausedAnswerControls) {
      answerInput.disabled = pausedAnswerControls[0];
      submitButton.disabled = pausedAnswerControls[1];
      nextButton.disabled = pausedAnswerControls[2];
    }
    pausedAnswerControls = null;
    pauseButton.hidden = false;
    resumeButton.hidden = true;
    setStatus('');
    if (resumePlaybackAfterPause) {
      const playback = media.play();
      if (playback?.catch) playback.catch(function () {
        setStatus('Nhấn Phát bài để tiếp tục video.', false);
      });
    }
    resumePlaybackAfterPause = false;
  }

  function setWaitingState(title, text) {
    waitingTitle.textContent = title;
    waitingText.textContent = text;
  }

  function formatTime(value) {
    const seconds = Math.max(0, Math.floor(Number(value) || 0));
    return Math.floor(seconds / 60) + ':' + String(seconds % 60).padStart(2, '0');
  }

  function timelineDuration() {
    if (Number.isFinite(media.duration) && media.duration > 0) return media.duration;
    return subtitles.reduce(function (maximum, item) {
      return Math.max(maximum, Number(item.endTime) || 0);
    }, 0);
  }

  function setMedia(videoId, audioOnly) {
    if (String(mediaVideoId) === String(videoId)) return;
    mediaVideoId = videoId;
    media.src = window.mediaStreamUrl
      ? window.mediaStreamUrl(videoId)
      : '/api/videos/' + encodeURIComponent(videoId) + '/stream';
    mediaPanel.classList.toggle('is-audio', Boolean(audioOnly));
  }

  function isStopDone(stop) {
    if (!session) return false;
    if (stop.number <= session.currentPosition) return true;
    return Boolean(question && question.answered && stop.subtitleId === question.subtitleId);
  }

  function isCurrentStop(stop) {
    return Boolean(question && stop.subtitleId === question.subtitleId);
  }

  function concealedText(subtitle, stop) {
    if (!stop || isStopDone(stop)) return subtitle.text || '';
    if (isCurrentStop(stop) && session?.mode === 'CLOZE') return question?.promptText || '＿＿＿';
    return '••••••  Câu kiểm tra #' + stop.number;
  }

  function renderFurigana(value) {
    const source = String(value || '');
    const pattern = /([^\s()（）]+)[(（]([^()（）]+)[)）]/g;
    const fragment = document.createDocumentFragment();
    let cursor = 0;
    let match;
    while ((match = pattern.exec(source))) {
      fragment.append(document.createTextNode(source.slice(cursor, match.index)));
      const ruby = document.createElement('ruby');
      ruby.append(document.createTextNode(match[1]));
      const reading = document.createElement('rt');
      reading.textContent = match[2];
      ruby.append(reading);
      fragment.append(ruby);
      cursor = match.index + match[0].length;
    }
    fragment.append(document.createTextNode(source.slice(cursor)));
    liveJapanese.replaceChildren(fragment);
  }

  function renderLiveJapanese(subtitle, stop) {
    const hiddenForQuestion = Boolean(stop && !isStopDone(stop));
    const showFurigana = furiganaVisible && subtitle?.furigana && !hiddenForQuestion;
    const sentenceLength = Array.from(String(subtitle?.text || concealedText(subtitle, stop))).length;
    const isMedium = sentenceLength >= 28;
    const isLong = sentenceLength >= 46;
    const isExtraLong = sentenceLength >= 76;
    liveJapanese.classList.toggle('has-furigana', Boolean(showFurigana));
    liveJapanese.classList.toggle('is-medium', isMedium && !isLong);
    liveJapanese.classList.toggle('is-long', isLong);
    mediaPanel.classList.toggle('has-long-live-subtitle', isLong && Boolean(showFurigana));
    mediaPanel.classList.toggle('has-extra-long-live-subtitle', isExtraLong && Boolean(showFurigana));
    if (showFurigana) {
      renderFurigana(subtitle.furigana);
      return;
    }
    liveJapanese.textContent = concealedText(subtitle, stop);
  }

  function setListenButton(label, icon) {
    listenIcon.className = 'fa-solid fa-' + icon;
    listenButton.title = label;
    listenButton.setAttribute('aria-label', label);
  }

  function renderPrompt(expectedAnswer) {
    const promptText = question?.promptText || '';
    prompt.replaceChildren();
    const blank = /[＿_]{3,}/.exec(promptText);
    if (!blank) {
      prompt.textContent = promptText;
      return;
    }

    prompt.append(document.createTextNode(promptText.slice(0, blank.index)));
    const answerSlot = document.createElement('span');
    answerSlot.className = 'exercise-answer-slot';
    answerSlot.setAttribute('aria-label', expectedAnswer ? 'Đáp án: ' + expectedAnswer : 'Chỗ trống cần điền');
    if (expectedAnswer) {
      answerSlot.classList.add('is-revealed');
      answerSlot.textContent = expectedAnswer;
    }
    prompt.append(answerSlot, document.createTextNode(promptText.slice(blank.index + blank[0].length)));
  }

  function renderMarkers() {
    stopMarkers.replaceChildren();
    const duration = timelineDuration();
    if (!duration) return;
    stops.forEach(function (stop) {
      const marker = document.createElement('span');
      marker.className = 'exercise-stop-marker';
      marker.classList.toggle('is-current', isCurrentStop(stop));
      marker.classList.toggle('is-done', isStopDone(stop));
      marker.classList.toggle('is-question', isCurrentStop(stop) && stoppedAtQuestion);
      marker.style.left = Math.min(100, Math.max(0, stop.endTime * 100 / duration)) + '%';
      marker.title = formatTime(stop.endTime) + ' · Câu hỏi #' + stop.number
        + ' · ' + stop.difficultyLabel;
      marker.addEventListener('click', function (event) {
        event.stopPropagation();
        seekTo(Number(stop.startTime), true);
      });
      stopMarkers.appendChild(marker);
    });
  }

  function renderTranscript() {
    transcript.replaceChildren();
    transcriptRows = new Map();
    subtitles.forEach(function (subtitle) {
      const subtitleId = Number(subtitle.id);
      const stop = stopBySubtitleId.get(subtitleId);
      const row = document.createElement('article');
      row.className = 'exercise-transcript-row';
      row.dataset.subtitleId = String(subtitleId);
      row.tabIndex = 0;
      row.title = 'Phát từ câu này';
      if (stop) {
        row.classList.add('is-stop');
        row.classList.toggle('is-done', isStopDone(stop));
        row.classList.toggle('is-question', isCurrentStop(stop) && stoppedAtQuestion);
      }

      row.addEventListener('click', function () {
        seekTo(Number(subtitle.startTime), true);
      });
      row.addEventListener('keydown', function (event) {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          seekTo(Number(subtitle.startTime), true);
        }
      });

      const time = document.createElement('span');
      time.className = 'exercise-transcript-time';
      time.textContent = formatTime(subtitle.startTime);
      const text = document.createElement('p');
      text.className = 'exercise-transcript-text';
      text.lang = 'ja';
      text.textContent = concealedText(subtitle, stop);
      row.append(time, text);
      if (stop) {
        const badge = document.createElement('span');
        badge.className = 'exercise-transcript-badge';
        badge.innerHTML = '<i class="fa-solid fa-flag"></i> #' + stop.number;
        row.appendChild(badge);
      }
      transcript.appendChild(row);
      transcriptRows.set(subtitleId, row);
    });
  }

  function subtitleAt(time) {
    return subtitles.find(function (subtitle) {
      return time >= Number(subtitle.startTime) && time < Number(subtitle.endTime);
    }) || null;
  }

  function showLiveSubtitle(subtitle) {
    activeLiveSubtitle = subtitle;
    transcriptRows.forEach(function (row) { row.classList.remove('is-active'); });
    if (!subtitle) {
      liveJapanese.classList.remove('has-furigana', 'is-medium', 'is-long');
      mediaPanel.classList.remove('has-long-live-subtitle', 'has-extra-long-live-subtitle');
      liveJapanese.textContent = media.paused ? 'Nhấn Phát bài để tiếp tục.' : '';
      liveTranslation.textContent = '';
      updatePreparationTip(null);
      return;
    }
    const subtitleId = Number(subtitle.id);
    const stop = stopBySubtitleId.get(subtitleId);
    renderLiveJapanese(subtitle, stop);
    liveTranslation.textContent = stop && !isStopDone(stop) ? '' : (subtitle.translation || '');
    updatePreparationTip(subtitle);
    const row = transcriptRows.get(subtitleId);
    if (row) {
      row.classList.add('is-active');
      const top = row.offsetTop - transcript.offsetTop;
      const bottom = top + row.offsetHeight;
      if (top < transcript.scrollTop || bottom > transcript.scrollTop + transcript.clientHeight) {
        const target = Math.max(0, top - Math.max(8, (transcript.clientHeight - row.offsetHeight) / 2));
        transcript.scrollTo({ top: target, behavior: 'smooth' });
      }
    }
  }

  function updatePreparationTip(subtitle) {
    const pendingStop = stops.find(function (stop) { return !isStopDone(stop); });
    const pendingIndex = pendingStop
      ? subtitles.findIndex(function (item) { return Number(item.id) === Number(pendingStop.subtitleId); })
      : -1;
    const previousSubtitle = pendingIndex > 0 ? subtitles[pendingIndex - 1] : null;
    const show = Boolean(subtitle && previousSubtitle
      && Number(subtitle.id) === Number(previousSubtitle.id)
      && !stoppedAtQuestion && !question?.answered && !finishingRemainder);

    if (show) {
      answerDock.hidden = false;
      questionPanel.classList.remove('is-listening');
      waitingState.hidden = true;
      kitsuTip.hidden = false;
      prompt.hidden = true;
      answerInput.hidden = true;
      actions.hidden = true;
      setStatus('Sắp đến câu có cờ #' + pendingStop.number + '…', false);
      const playback = kitsuVideo.play();
      if (playback?.catch) playback.catch(function () { /* poster remains visible */ });
    } else if (!stoppedAtQuestion && !question?.answered && !finishingRemainder) {
      answerDock.hidden = true;
      questionPanel.classList.add('is-listening');
      waitingState.hidden = false;
      setWaitingState('Đang chờ câu hỏi xuất hiện', 'Video đang phát. Khi đến câu có cờ, video sẽ tự dừng để bạn làm bài.');
      kitsuTip.hidden = true;
      kitsuVideo.pause();
    }
  }

  function updateVideoProgress() {
    const duration = timelineDuration();
    const current = Number(media.currentTime) || 0;
    const percentage = duration ? Math.min(100, current * 100 / duration) : 0;
    videoProgress.style.width = percentage + '%';
    videoTimeline.setAttribute('aria-valuenow', String(Math.round(percentage)));
    videoClock.textContent = formatTime(current) + ' / ' + formatTime(duration);
    showLiveSubtitle(subtitleAt(current));
  }

  function applyPendingPlayback() {
    if (pendingStartTime != null) {
      media.currentTime = Math.max(0, Math.min(pendingStartTime, media.duration || pendingStartTime));
      pendingStartTime = null;
    }
    renderMarkers();
    updateVideoProgress();
    if (autoplayPending) {
      autoplayPending = false;
      const request = media.play();
      if (request?.catch) request.catch(function () {
        setStatus('Bấm Phát bài để tiếp tục video.', false);
      });
    }
  }

  function playFrom(time, asReplay) {
    replayMode = Boolean(asReplay);
    media.playbackRate = Number(speed.value) || 1;
    if (media.readyState < 1) {
      pendingStartTime = time;
      autoplayPending = true;
      return;
    }
    media.currentTime = Math.max(0, Math.min(Number(time) || 0, media.duration || Number(time) || 0));
    const request = media.play();
    if (request?.catch) request.catch(function () {
      setStatus('Không thể phát video lúc này.', true);
    });
  }

  function seekTo(time, resumePlayback) {
    const duration = timelineDuration();
    const target = Math.max(0, Math.min(Number(time) || 0, duration || Number(time) || 0));
    if (question?.answered && !finishingRemainder
      && target >= Number(question.endTime) - 0.04) {
      advanceAfterAnsweredSeek(target);
      return;
    }
    const pendingQuestionEnd = question && !question.answered ? Number(question.endTime) : null;
    replayMode = Boolean(question && !question.answered && stoppedAtQuestion
      && target >= Number(question.startTime) - 0.04
      && target <= Number(question.endTime) + 0.04);
    pendingStartTime = null;
    autoplayPending = false;
    if (media.readyState < 1) {
      pendingStartTime = target;
      autoplayPending = Boolean(resumePlayback);
      return;
    }
    if (pendingQuestionEnd != null && target >= pendingQuestionEnd - 0.04) {
      media.pause();
      media.currentTime = Math.max(Number(question.startTime), pendingQuestionEnd - 0.02);
      updateVideoProgress();
      if (!stoppedAtQuestion) unlockAnswer();
      return;
    }
    media.currentTime = target;
    updateVideoProgress();
    if (resumePlayback) {
      const request = media.play();
      if (request?.catch) request.catch(function () {
        setStatus('Bấm Phát bài để tiếp tục video.', false);
      });
    }
  }

  async function advanceAfterAnsweredSeek(target) {
    if (advancingAfterSeek) return;
    advancingAfterSeek = true;
    media.pause();
    nextButton.disabled = true;
    pauseButton.disabled = true;
    setStatus('Đang mở câu hỏi tiếp theo…', false);
    try {
      const response = await fetch('/api/shadowing-practice/sessions/' + encodeURIComponent(sessionId) + '/next', {
        method: 'POST', headers: headers(), body: '{}'
      });
      const data = await response.json().catch(function () { return {}; });
      if (!response.ok) throw new Error(data.message || data.error || 'Không chuyển được câu');
      // Seeking beyond an answered stop has the same intent as "Tiếp tục video".
      // Start from the chosen timestamp so the next unanswered stop is still enforced.
      renderSession(data, true, target);
    } catch (error) {
      nextButton.disabled = false;
      setStatus(error.message || 'Không chuyển được câu', true);
    } finally {
      advancingAfterSeek = false;
      pauseButton.disabled = false;
    }
  }

  function resumeTimeForCurrentQuestion() {
    if (!question || question.number <= 1) return 0;
    const previous = stops[question.number - 2];
    return previous ? Number(previous.endTime) + 0.02 : 0;
  }

  function resetQuestionUi() {
    stoppedAtQuestion = false;
    replayMode = false;
    finishingRemainder = false;
    questionPanel.classList.remove('is-waiting', 'is-finishing');
    questionPanel.classList.add('is-listening');
    answerDock.hidden = true;
    waitingState.hidden = false;
    setWaitingState('Đang chờ câu hỏi xuất hiện', 'Video đang phát. Khi đến câu có cờ, video sẽ tự dừng để bạn làm bài.');
    kitsuTip.hidden = true;
    kitsuVideo.pause();
    prompt.hidden = true;
    answerInput.hidden = true;
    actions.hidden = true;
    answerInput.value = '';
    answerInput.disabled = true;
    submitButton.disabled = true;
    submitButton.hidden = false;
    nextButton.hidden = true;
    nextButton.disabled = false;
    answerInput.classList.remove('is-correct', 'is-incorrect');
    answerDock.scrollTop = 0;
    setStatus('', false);
  }

  function renderSession(view, autoResume, explicitResumeTime) {
    session = view;
    sessionActions.hidden = Boolean(view.completed || (!view.question && !autoResume));
    stops = Array.isArray(view.stops) ? view.stops : [];
    stopBySubtitleId = new Map(stops.map(function (stop) {
      return [Number(stop.subtitleId), stop];
    }));
    document.getElementById('exercise-video-title').textContent = view.videoTitle || 'Bài luyện nghe';
    document.getElementById('exercise-mode-label').textContent = view.mode === 'CLOZE'
      ? 'Nghe cả bài · Điền từ hoặc cụm' : 'Nghe cả bài · Điền cả câu';
    document.getElementById('exercise-back-link').href = '/shadowing/practice/' + view.videoId;
    setMedia(view.videoId, view.audioOnly);

    if (view.completed || !view.question) {
      if (autoResume && explicitResumeTime != null) renderRemainder(view, explicitResumeTime);
      else renderComplete(view);
      return;
    }

    workspace.hidden = false;
    completePanel.hidden = true;
    question = view.question;
    resetQuestionUi();
    renderPrompt();
    answerInput.rows = view.mode === 'CLOZE' ? 2 : 4;
    answerInput.placeholder = view.mode === 'CLOZE'
      ? 'Video dừng xong, nhập từ hoặc cụm từ còn thiếu…'
      : 'Video dừng xong, nhập toàn bộ câu bạn vừa nghe…';
    renderMarkers();
    renderTranscript();

    const resumeTime = explicitResumeTime == null ? resumeTimeForCurrentQuestion() : explicitResumeTime;
    pendingStartTime = resumeTime;
    if (media.readyState >= 1) applyPendingPlayback();
    if (question.answered && question.previousAnswer) {
      stoppedAtQuestion = true;
      renderResult(question.previousAnswer);
    } else if (autoResume) {
      playFrom(resumeTime, false);
    }
  }

  function unlockAnswer() {
    if (!question || question.answered || finishingRemainder) return;
    stoppedAtQuestion = true;
    replayMode = false;
    media.pause();
    try { media.currentTime = Math.max(Number(question.startTime), Number(question.endTime) - 0.02); }
    catch (_) { /* metadata is loaded */ }
    questionPanel.classList.add('is-waiting');
    questionPanel.classList.remove('is-listening');
    answerDock.hidden = false;
    waitingState.hidden = true;
    kitsuTip.hidden = true;
    kitsuVideo.pause();
    prompt.hidden = false;
    answerInput.hidden = false;
    actions.hidden = false;
    answerInput.disabled = false;
    submitButton.disabled = false;
    renderMarkers();
    renderTranscript();
    showLiveSubtitle(subtitles.find(function (item) {
      return Number(item.id) === Number(question.subtitleId);
    }));
    setStatus('', false);
    if (session.mode === 'CLOZE') liveJapanese.textContent = question.promptText || '＿＿＿';
    else liveJapanese.textContent = 'Câu kiểm tra đang được ẩn.';
    liveTranslation.textContent = '';
    answerInput.focus();
  }

  listenButton.addEventListener('click', function () {
    if (sessionPaused) return;
    if (!media.paused) {
      media.pause();
      return;
    }
    if (finishingRemainder) {
      const request = media.play();
      if (request?.catch) request.catch(function () { setStatus('Không thể phát video lúc này.', true); });
      return;
    }
    if (question && (stoppedAtQuestion || question.answered)) {
      playFrom(Number(question.startTime), true);
      return;
    }
    const start = media.currentTime > 0 ? media.currentTime : resumeTimeForCurrentQuestion();
    playFrom(start, false);
  });

  furiganaToggle.addEventListener('click', function () {
    if (sessionPaused) return;
    furiganaVisible = !furiganaVisible;
    furiganaToggle.classList.toggle('is-active', furiganaVisible);
    furiganaToggle.setAttribute('aria-pressed', String(furiganaVisible));
    furiganaToggle.setAttribute('aria-label', furiganaVisible ? 'Ẩn furigana' : 'Hiện furigana');
    furiganaToggle.title = furiganaVisible ? 'Ẩn furigana' : 'Hiện furigana';
    if (activeLiveSubtitle) showLiveSubtitle(activeLiveSubtitle);
  });

  subtitleToggle.addEventListener('click', function () {
    if (sessionPaused) return;
    subtitleVisible = !subtitleVisible;
    mediaPanel.classList.toggle('is-subtitle-hidden', !subtitleVisible);
    subtitleToggle.classList.toggle('is-active', subtitleVisible);
    subtitleToggle.setAttribute('aria-pressed', String(subtitleVisible));
    subtitleToggle.setAttribute('aria-label', subtitleVisible ? 'Ẩn phụ đề' : 'Hiện phụ đề');
    subtitleToggle.title = subtitleVisible ? 'Ẩn phụ đề' : 'Hiện phụ đề';
  });

  function closeSpeedMenu() {
    speedMenu.hidden = true;
    speedToggle.setAttribute('aria-expanded', 'false');
  }

  speedToggle.addEventListener('click', function (event) {
    if (sessionPaused) return;
    event.stopPropagation();
    const willOpen = speedMenu.hidden;
    speedMenu.hidden = !willOpen;
    speedToggle.setAttribute('aria-expanded', String(willOpen));
  });

  speedButtons.forEach(function (button) {
    button.addEventListener('click', function () {
      if (sessionPaused) return;
      const value = Number(button.dataset.speed) || 1;
      speed.value = String(value);
      speedLabel.textContent = button.textContent;
      media.playbackRate = value;
      speedButtons.forEach(function (item) {
        const active = item === button;
        item.classList.toggle('is-active', active);
        item.setAttribute('aria-selected', String(active));
      });
      closeSpeedMenu();
    });
  });

  document.addEventListener('click', function (event) {
    if (!speedControl.contains(event.target)) closeSpeedMenu();
  });

  speedControl.addEventListener('keydown', function (event) {
    if (event.key === 'Escape') {
      closeSpeedMenu();
      speedToggle.focus();
    }
  });

  let timelineWasPlaying = false;

  function seekFromTimelinePointer(event, resumePlayback) {
    const bounds = videoTimeline.getBoundingClientRect();
    const ratio = bounds.width ? (event.clientX - bounds.left) / bounds.width : 0;
    seekTo(timelineDuration() * Math.max(0, Math.min(1, ratio)), resumePlayback);
  }

  videoTimeline.addEventListener('click', function (event) {
    if (sessionPaused) return;
    if (event.target.closest('.exercise-stop-marker')) return;
    seekFromTimelinePointer(event, !media.paused);
  });

  videoTimeline.addEventListener('pointerdown', function (event) {
    if (sessionPaused) return;
    if (event.target.closest('.exercise-stop-marker')) return;
    timelineWasPlaying = !media.paused;
    media.pause();
    videoTimeline.setPointerCapture?.(event.pointerId);
    seekFromTimelinePointer(event, false);
  });

  videoTimeline.addEventListener('pointermove', function (event) {
    if (sessionPaused) return;
    if (videoTimeline.hasPointerCapture?.(event.pointerId)) {
      seekFromTimelinePointer(event, false);
    }
  });

  videoTimeline.addEventListener('pointerup', function (event) {
    if (sessionPaused) return;
    if (videoTimeline.hasPointerCapture?.(event.pointerId)) {
      videoTimeline.releasePointerCapture?.(event.pointerId);
      seekFromTimelinePointer(event, timelineWasPlaying);
      timelineWasPlaying = false;
    }
  });

  videoTimeline.addEventListener('keydown', function (event) {
    if (sessionPaused) return;
    const duration = timelineDuration();
    if (!duration) return;
    const current = Number(media.currentTime) || 0;
    const step = event.shiftKey ? 10 : 5;
    if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
      event.preventDefault();
      seekTo(current + (event.key === 'ArrowLeft' ? -step : step), !media.paused);
    } else if (event.key === 'Home' || event.key === 'End') {
      event.preventDefault();
      seekTo(event.key === 'Home' ? 0 : duration, !media.paused);
    }
  });

  media.addEventListener('loadedmetadata', applyPendingPlayback);
  media.addEventListener('durationchange', renderMarkers);
  media.addEventListener('play', function () {
    setListenButton('Tạm dừng', 'pause');
  });
  media.addEventListener('pause', function () {
    if (finishingRemainder) setListenButton('Tiếp tục phần còn lại', 'play');
    else if (stoppedAtQuestion || question?.answered) setListenButton('Nghe lại câu', 'play');
    else if (media.currentTime > 0.05) setListenButton('Tiếp tục bài', 'play');
    else setListenButton('Phát bài', 'play');
  });
  media.addEventListener('timeupdate', function () {
    updateVideoProgress();
    if (sessionPaused || !question || finishingRemainder) return;
    const currentTime = Number(media.currentTime) || 0;
    const questionEnd = Number(question.endTime);
    const reachedEnd = currentTime >= questionEnd - 0.04;
    if (replayMode && reachedEnd) {
      replayMode = false;
      media.pause();
      try { media.currentTime = Math.max(Number(question.startTime), Number(question.endTime) - 0.02); }
      catch (_) { /* no-op */ }
      if (!question.answered) unlockAnswer();
      return;
    }
    if (!question.answered && !stoppedAtQuestion && reachedEnd) unlockAnswer();
  });
  media.addEventListener('ended', function () {
    updateVideoProgress();
    if (sessionPaused) return;
    if (finishingRemainder && session) renderComplete(session);
    else if (question && !question.answered) unlockAnswer();
  });

  function renderResult(result) {
    if (question) question.answered = true;
    questionPanel.classList.remove('is-waiting');
    questionPanel.classList.remove('is-listening');
    answerDock.hidden = false;
    waitingState.hidden = true;
    kitsuTip.hidden = true;
    kitsuVideo.pause();
    prompt.hidden = false;
    answerInput.hidden = false;
    actions.hidden = false;
    answerInput.disabled = true;
    submitButton.hidden = true;
    nextButton.hidden = false;
    const isCorrect = result.result === 'CORRECT';
    renderPrompt(result.expectedAnswer || '');
    answerInput.classList.toggle('is-correct', isCorrect);
    answerInput.classList.toggle('is-incorrect', !isCorrect);
    renderMarkers();
    renderTranscript();
    showLiveSubtitle(subtitles.find(function (item) { return Number(item.id) === Number(question?.subtitleId); }));
  }

  submitButton.addEventListener('click', async function () {
    if (sessionPaused) return;
    if (!question || !answerInput.value.trim()) {
      setStatus('Hãy nhập đáp án trước khi kiểm tra.', true);
      answerInput.focus();
      return;
    }
    if (!stoppedAtQuestion) {
      setStatus('Hãy nghe đến khi video tự dừng ở cuối câu.', true);
      return;
    }
    submitButton.disabled = true;
    pauseButton.disabled = true;
    setStatus('Kitsu-chan đang kiểm tra…');
    try {
      const response = await fetch('/api/shadowing-practice/sessions/' + encodeURIComponent(sessionId) + '/answers', {
        method: 'POST', headers: headers(),
        body: JSON.stringify({ itemId: question.itemId, answer: answerInput.value })
      });
      const data = await response.json().catch(function () { return {}; });
      if (!response.ok) throw new Error(data.message || data.error || 'Không chấm được đáp án');
      renderResult(data);
      setStatus('', false);
    } catch (error) {
      submitButton.disabled = false;
      setStatus(error.message || 'Không chấm được đáp án', true);
    } finally {
      pauseButton.disabled = false;
    }
  });

  answerInput.addEventListener('keydown', function (event) {
    if (session?.mode === 'CLOZE' && event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      submitButton.click();
    }
  });

  nextButton.addEventListener('click', async function () {
    if (sessionPaused) return;
    if (advancingAfterSeek) return;
    const resumeFrom = Number(question?.endTime) + 0.02;
    nextButton.disabled = true;
    pauseButton.disabled = true;
    try {
      const response = await fetch('/api/shadowing-practice/sessions/' + encodeURIComponent(sessionId) + '/next', {
        method: 'POST', headers: headers(), body: '{}'
      });
      const data = await response.json().catch(function () { return {}; });
      if (!response.ok) throw new Error(data.message || data.error || 'Không chuyển được câu');
      renderSession(data, true, resumeFrom);
    } catch (error) {
      nextButton.disabled = false;
      setStatus(error.message || 'Không chuyển được câu', true);
    } finally {
      pauseButton.disabled = false;
    }
  });

  function renderRemainder(view, resumeFrom) {
    session = view;
    sessionActions.hidden = false;
    question = null;
    finishingRemainder = true;
    stoppedAtQuestion = false;
    replayMode = false;
    workspace.hidden = false;
    completePanel.hidden = true;
    questionPanel.classList.remove('is-waiting');
    questionPanel.classList.add('is-finishing');
    questionPanel.classList.add('is-listening');
    answerDock.hidden = true;
    waitingState.hidden = false;
    setWaitingState('Đang phát phần còn lại', 'Bạn đã hoàn thành các điểm dừng. Video sẽ tiếp tục đến hết bài.');
    answerInput.hidden = true;
    actions.hidden = true;
    prompt.textContent = 'Bạn đã làm xong các điểm dừng. Hãy nghe nốt phần còn lại của video.';
    document.getElementById('exercise-instruction').textContent =
      'Kitsu-chan sẽ kết thúc phiên sau khi video phát hết.';
    setStatus('Video đang tiếp tục từ vị trí vừa dừng.', false);
    renderMarkers();
    renderTranscript();
    const duration = timelineDuration();
    if (duration && resumeFrom >= duration - 0.08) {
      renderComplete(view);
      return;
    }
    playFrom(resumeFrom, false);
  }

  function renderComplete(view) {
    media.pause();
    sessionActions.hidden = true;
    finishingRemainder = false;
    workspace.hidden = true;
    completePanel.hidden = false;
    document.getElementById('exercise-complete-summary').textContent =
      'Bạn trả lời đúng ' + view.correctCount + '/' + view.totalQuestions
      + ' câu · điểm trung bình ' + view.averageScore + '/100.';
    document.getElementById('exercise-restart').onclick = function () {
      location.href = '/shadowing/practice/' + view.videoId;
    };
  }

  async function loadSession() {
    try {
      const sessionResponse = await fetch(
        '/api/shadowing-practice/sessions/' + encodeURIComponent(sessionId), { headers: headers() });
      const view = await sessionResponse.json().catch(function () { return {}; });
      if (!sessionResponse.ok) throw new Error(view.message || view.error || 'Không tải được phiên học');
      const subtitleResponse = await fetch(
        '/api/videos/' + encodeURIComponent(view.videoId) + '/subtitles', { headers: headers() });
      if (!subtitleResponse.ok) throw new Error('Không tải được phụ đề cả bài');
      subtitles = await subtitleResponse.json();
      subtitles = (Array.isArray(subtitles) ? subtitles : []).sort(function (left, right) {
        return Number(left.startTime) - Number(right.startTime);
      });
      renderSession(view, false, null);
    } catch (error) {
      setStatus(error.message || 'Không tải được phiên học', true);
      workspace.hidden = true;
    }
  }

  pauseButton.addEventListener('click', pauseSession);
  resumeButton.addEventListener('click', resumeSession);

  loadSession();
}());
