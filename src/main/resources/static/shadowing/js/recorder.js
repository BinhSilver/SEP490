/* Per-subtitle recording and pronunciation assessment for Shadowing. */
(function () {
  'use strict';

  const MAX_RECORDING_SECONDS = 30;
  const GOOD_SCORE = 85;
  const FAIR_SCORE = 70;
  const SMALL_KANA = new Set(['ゃ', 'ゅ', 'ょ', 'ぁ', 'ぃ', 'ぅ', 'ぇ', 'ぉ', 'ゎ', 'ゕ', 'ゖ']);
  const states = new Map();
  let active = null;
  let referencePlaybackListener = null;
  window.isShadowingPronunciationRecording = function () { return Boolean(active); };

  function cardFor(subtitleId) {
    return Array.from(document.querySelectorAll('.subtitle-item')).find(function (card) {
      return String(card.dataset.subtitleId) === String(subtitleId);
    }) || null;
  }

  function stateFor(card) {
    const id = String(card.dataset.subtitleId);
    let state = states.get(id);
    if (!state) {
      state = { attempt: null, blob: null, objectUrl: null, audioUrl: null, history: [] };
      states.set(id, state);
    }
    return state;
  }

  function audioFor(card) { return card.querySelector('[data-pronunciation-audio]'); }
  function panelFor(card) { return card.querySelector('[data-pronunciation-panel]'); }

  function authHeaders() {
    if (window.JlasAuth && typeof JlasAuth.authHeaders === 'function') {
      const headers = JlasAuth.authHeaders();
      // FormData must provide its own boundary; never force application/json here.
      delete headers['Content-Type'];
      delete headers['content-type'];
      return headers;
    }
    const token = localStorage.getItem('accessToken');
    return token ? { Authorization: 'Bearer ' + token } : {};
  }

  function formatTime(seconds) {
    if (!Number.isFinite(seconds) || seconds < 0) seconds = 0;
    return Math.floor(seconds / 60) + ':' + String(Math.floor(seconds % 60)).padStart(2, '0');
  }

  function setStatus(card, text) {
    const status = card.querySelector('[data-record-status]');
    if (status) status.textContent = text;
  }

  function sourceVideo() {
    return document.getElementById('main-video');
  }

  function pauseSourceVideo() {
    const video = sourceVideo();
    clearReferencePlayback();
    if (video) video.pause();
  }

  function clearReferencePlayback() {
    const video = sourceVideo();
    if (video && referencePlaybackListener) video.removeEventListener('timeupdate', referencePlaybackListener);
    referencePlaybackListener = null;
  }

  function setPanelVisible(card, visible) {
    const panel = panelFor(card);
    if (panel) panel.hidden = !visible;
  }

  function resetCurrentAttempt(card) {
    const state = stateFor(card);
    if (state.objectUrl) URL.revokeObjectURL(state.objectUrl);
    state.objectUrl = null;
    state.blob = null;
    state.audioUrl = null;
    state.attempt = null;
    const audio = audioFor(card);
    if (audio) {
      audio.pause();
      audio.removeAttribute('src');
      audio.load();
    }
    const panel = panelFor(card);
    if (panel) panel.hidden = state.history.length === 0;
    const history = card.querySelector('[data-pronunciation-history]');
    if (history) history.hidden = state.history.length === 0;
    const score = card.querySelector('[data-action="score"]');
    if (score) {
      score.disabled = true;
      score.textContent = '--';
      delete score.dataset.grade;
    }
    const playback = card.querySelector('[data-pronunciation-playback]');
    if (playback) playback.hidden = true;
    const result = card.querySelector('[data-pronunciation-result]');
    if (result) result.hidden = true;
    const details = card.querySelector('[data-pronunciation-details]');
    if (details) details.hidden = true;
    const detailsButton = card.querySelector('[data-action="score-details"]');
    if (detailsButton) detailsButton.textContent = 'Xem lỗi';
    const focus = card.querySelector('[data-pronunciation-focus]');
    if (focus) focus.hidden = true;
  }

  function getMimeType() {
    if (!window.MediaRecorder || typeof MediaRecorder.isTypeSupported !== 'function') return '';
    return ['audio/webm;codecs=opus', 'audio/ogg;codecs=opus', 'audio/mp4', 'audio/webm']
      .find(type => MediaRecorder.isTypeSupported(type)) || '';
  }

  function setRecordingUi(card, recording) {
    const button = card.querySelector('[data-action="record"]');
    if (!button) return;
    button.classList.toggle('recording', recording);
    button.title = recording ? 'Dừng thu câu này' : 'Thu câu này';
    button.setAttribute('aria-label', recording ? 'Dừng thu câu này' : 'Thu câu này');
    const icon = button.querySelector('i');
    if (icon) icon.className = recording ? 'fa-solid fa-stop' : 'fa-solid fa-microphone';
  }

  async function startRecording(card, index) {
    if (active) {
      if (active.card === card) {
        stopRecording(false);
        return;
      }
      setStatus(card, 'Đang xử lý bản thu trước');
      return;
    }
    if (!navigator.mediaDevices?.getUserMedia || !window.MediaRecorder) {
      setStatus(card, 'Trình duyệt không hỗ trợ thu âm');
      return;
    }
    if (typeof window.selectShadowingPronunciationSubtitle === 'function') {
      window.selectShadowingPronunciationSubtitle(index, 'recording');
    }
    const context = typeof window.getShadowingPronunciationContext === 'function'
      ? window.getShadowingPronunciationContext(index) : null;
    if (!context || !context.subtitleId) {
      setStatus(card, 'Chưa có câu phụ đề để thu');
      return;
    }
    resetCurrentAttempt(card);
    let stream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({
        audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true }
      });
    } catch (error) {
      setStatus(card, 'Không có quyền dùng micro');
      return;
    }
    const latest = typeof window.getShadowingPronunciationContext === 'function'
      ? window.getShadowingPronunciationContext(index) : null;
    if (!latest || String(latest.subtitleId) !== String(context.subtitleId)) {
      stream.getTracks().forEach(track => track.stop());
      setStatus(card, 'Bạn vừa đổi câu · hãy bấm thu lại');
      return;
    }
    const mimeType = getMimeType();
    let recorder;
    try {
      recorder = mimeType ? new MediaRecorder(stream, { mimeType }) : new MediaRecorder(stream);
    } catch (error) {
      stream.getTracks().forEach(track => track.stop());
      setStatus(card, 'Không thể khởi tạo bộ thu âm');
      return;
    }
    const startedAt = Date.now();
    active = { card, context, recorder, stream, chunks: [], discard: false, timer: null };
    recorder.ondataavailable = event => {
      if (event.data?.size) active?.chunks.push(event.data);
    };
    recorder.onerror = () => setStatus(card, 'Có lỗi khi thu âm · hãy thử lại');
    recorder.onstop = finishRecording;
    const video = sourceVideo();
    const sentenceStart = Number(context.startTime);
    const sentenceEnd = Number(context.endTime);
    if (video && Number.isFinite(sentenceStart)) {
      try {
        video.currentTime = Math.max(0, sentenceStart);
      } catch (_) {
        // The player may still be loading; playback below will report a useful status.
      }
    }
    recorder.start(250);
    if (video) {
      const playRequest = video.play();
      if (playRequest && typeof playRequest.catch === 'function') {
        playRequest.catch(() => setStatus(card, 'Không thể tự phát câu mẫu · hãy bấm Phát'));
      }
    }
    setRecordingUi(card, true);
    setStatus(card, 'Đang thu câu #' + context.sentenceNumber + ' · 00:00');
    active.timer = setInterval(function () {
      const videoTime = video && Number.isFinite(video.currentTime) ? video.currentTime : NaN;
      if (Number.isFinite(sentenceEnd) && Number.isFinite(videoTime) && videoTime >= sentenceEnd) {
        stopRecording(false);
        return;
      }
      const seconds = Number.isFinite(sentenceStart) && Number.isFinite(videoTime)
        ? Math.max(0, videoTime - sentenceStart)
        : Math.min(MAX_RECORDING_SECONDS, (Date.now() - startedAt) / 1000);
      setStatus(card, 'Đang thu câu #' + context.sentenceNumber + ' · ' + formatTime(seconds));
    }, 100);
    setTimeout(function () {
      if (active && active.recorder === recorder && recorder.state !== 'inactive') stopRecording(false);
    }, MAX_RECORDING_SECONDS * 1000);
  }

  function stopRecording(discard) {
    if (!active) return;
    active.discard = !!discard;
    if (active.timer) clearInterval(active.timer);
    pauseSourceVideo();
    active.stream.getTracks().forEach(track => track.stop());
    setRecordingUi(active.card, false);
    if (active.recorder.state !== 'inactive') active.recorder.stop();
  }

  function finishRecording() {
    const finished = active;
    if (!finished) return;
    active = null;
    if (finished.discard) return;
    const mimeType = finished.recorder.mimeType || (finished.chunks[0] && finished.chunks[0].type) || 'audio/webm';
    const blob = new Blob(finished.chunks, { type: mimeType });
    if (!blob.size) {
      setStatus(finished.card, 'Bản thu trống · hãy thử lại');
      return;
    }
    const state = stateFor(finished.card);
    state.blob = blob;
    state.objectUrl = URL.createObjectURL(blob);
    state.context = finished.context;
    state.attempt = null;
    const audio = audioFor(finished.card);
    if (audio) {
      audio.src = state.objectUrl;
      audio.load();
    }
    setPanelVisible(finished.card, true);
    const playback = finished.card.querySelector('[data-pronunciation-playback]');
    if (playback) playback.hidden = false;
    const scoreButton = finished.card.querySelector('[data-action="score"]');
    if (scoreButton) {
      scoreButton.disabled = false;
      scoreButton.textContent = 'Chấm';
    }
    setStatus(finished.card, 'Đã ghi xong câu #' + finished.context.sentenceNumber + ' · bấm Chấm');
  }

  function updateAudioUi(card) {
    const audio = audioFor(card);
    const seek = card.querySelector('[data-action="recording-seek"]');
    const time = card.querySelector('[data-recording-time]');
    if (!audio || !time) return;
    const duration = Number.isFinite(audio.duration) ? audio.duration : 0;
    if (seek && duration) seek.value = String((audio.currentTime / duration) * 100);
    time.textContent = formatTime(audio.currentTime) + ' / ' + formatTime(duration);
    const playButton = card.querySelector('[data-action="play-recording"] i');
    if (playButton) playButton.className = audio.paused ? 'fa-solid fa-play' : 'fa-solid fa-pause';
  }

  function scoreGrade(score) {
    if (!Number.isFinite(score)) return 'unknown';
    if (score >= GOOD_SCORE) return 'good';
    if (score >= FAIR_SCORE) return 'fair';
    return 'weak';
  }

  function errorLabel(errorType) {
    return ({
      Mispronunciation: 'phát âm chưa đúng',
      Omission: 'bị thiếu hoặc nuốt âm',
      Insertion: 'bị thừa âm',
      None: 'độ chính xác còn thấp'
    })[errorType] || 'cần luyện lại âm này';
  }

  function scoreValue(value) {
    if (value === null || value === undefined || value === '') return null;
    const score = Number(value);
    return Number.isFinite(score) ? score : null;
  }

  function scoreText(value) {
    const score = scoreValue(value);
    return score === null ? '--' : Math.round(score) + '/100';
  }

  function contextForCard(card) {
    const state = stateFor(card);
    if (state.context) return state.context;
    return typeof window.getShadowingPronunciationContext === 'function'
      ? window.getShadowingPronunciationContext(Number(card.dataset.subtitleIndex))
      : null;
  }

  function escapeRegExp(value) {
    return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  }

  function cleanJapaneseToken(value) {
    return String(value || '')
      .normalize('NFKC')
      .replace(/[\s、。！？!?「」『』（）()・…,.]/gu, '');
  }

  function toHiragana(value) {
    return Array.from(String(value || '').normalize('NFKC')).map(function (character) {
      const code = character.charCodeAt(0);
      return code >= 0x30A1 && code <= 0x30F6 ? String.fromCharCode(code - 0x60) : character;
    }).join('');
  }

  function readingForWord(wordText, annotatedText) {
    const word = cleanJapaneseToken(wordText);
    if (!word) return '';
    if (/^[\p{Script=Hiragana}\p{Script=Katakana}ー]+$/u.test(word)) return toHiragana(word);
    const source = String(annotatedText || '');
    const match = new RegExp(escapeRegExp(word) + '[（(]([^）)]+)[）)]', 'u').exec(source);
    if (match) return toHiragana(cleanJapaneseToken(match[1]));
    let expanded = word;
    const annotationPattern = /([\p{Script=Han}々〆ヵヶ]+)[（(]([^）)]+)[）)]/gu;
    for (const annotation of source.matchAll(annotationPattern)) {
      if (expanded.includes(annotation[1])) expanded = expanded.replace(annotation[1], annotation[2]);
    }
    return /^[\p{Script=Hiragana}\p{Script=Katakana}ー]+$/u.test(expanded) ? toHiragana(expanded) : '';
  }

  function splitMoras(reading) {
    const moras = [];
    Array.from(toHiragana(reading)).forEach(function (character) {
      if (!/[\p{Script=Hiragana}ー]/u.test(character)) return;
      if (SMALL_KANA.has(character) && moras.length) moras[moras.length - 1] += character;
      else moras.push(character);
    });
    return moras;
  }

  function moraWeight(mora) {
    return /^(?:[あいうえおんっー]|ゔ)$/u.test(mora) ? 1 : 2;
  }

  function estimateMoraScores(moras, phonemes, fallbackScore) {
    if (!moras.length) return [];
    const available = (Array.isArray(phonemes) ? phonemes : [])
      .map(function (phoneme) { return scoreValue(phoneme.accuracyScore); })
      .filter(function (score) { return score !== null; });
    if (!available.length) {
      return moras.map(function (mora) { return { mora: mora, score: fallbackScore, estimated: true }; });
    }
    const totalWeight = moras.reduce(function (sum, mora) { return sum + moraWeight(mora); }, 0);
    let consumedWeight = 0;
    return moras.map(function (mora, index) {
      const start = Math.min(available.length - 1, Math.floor(consumedWeight * available.length / totalWeight));
      consumedWeight += moraWeight(mora);
      let end = Math.ceil(consumedWeight * available.length / totalWeight);
      if (index === moras.length - 1) end = available.length;
      end = Math.max(start + 1, Math.min(available.length, end));
      const slice = available.slice(start, end);
      const score = slice.length
        ? slice.reduce(function (sum, value) { return sum + value; }, 0) / slice.length
        : fallbackScore;
      return { mora: mora, score: score, estimated: true };
    });
  }

  function practiceTip(mora, errorType) {
    if (errorType === 'Omission') return 'Đọc đủ mora này và giữ đúng một nhịp; đừng lướt qua khi nối sang âm kế tiếp.';
    if (errorType === 'Insertion') return 'Đọc gọn đúng số mora của mẫu; tránh thêm nguyên âm ở cuối hoặc chen thêm một nhịp.';
    const normalized = toHiragana(mora);
    if (normalized === 'っ') return 'Giữ một nhịp ngắn trước phụ âm kế tiếp. Không đọc っ thành một âm “tsu” riêng.';
    if (normalized === 'ん') return 'Giữ ん đủ một mora và để luồng hơi qua mũi; đừng nuốt mất âm khi nối từ.';
    if (normalized === 'ー') return 'Kéo dài nguyên âm trước đó thêm đúng một mora, không đổi sang nguyên âm khác.';
    if (/し/u.test(normalized)) return 'Với し, tạo luồng hơi hẹp và đọc gần “shi”; nghe mẫu chậm rồi bắt chước phần chuyển sang nguyên âm.';
    if (/つ/u.test(normalized)) return 'Với つ, bắt đầu bằng chặn nhẹ /t/ rồi xả sang /sɯ/; tránh đọc thành “su” hoặc “chu”.';
    if (/ふ/u.test(normalized)) return 'Với ふ, thổi hơi nhẹ giữa hai môi; tránh cắn môi dưới như âm “f” tiếng Việt.';
    if (/ら|り|る|れ|ろ/u.test(normalized)) return 'Chạm đầu lưỡi thật nhanh một lần phía sau lợi trên; không rung kéo dài như “r” tiếng Việt.';
    if (/さ|す|せ|そ/u.test(normalized)) return 'Giữ luồng hơi rõ ở phụ âm /s/, sau đó mới chuyển sang nguyên âm; tránh làm âm bị mềm thành “sh”.';
    if (/あ|か|が|さ|ざ|た|だ|な|は|ば|ぱ|ま|や|ら|わ/u.test(normalized)) return 'Mở rõ nguyên âm /a/ và giữ âm lượng đều; nghe mẫu rồi lặp riêng mora này ba lần.';
    if (/い|き|ぎ|し|じ|ち|に|ひ|び|ぴ|み|り/u.test(normalized)) return 'Giữ nguyên âm /i/ ngắn và sáng, không kéo dài; lặp chậm rồi ghép lại vào cả từ.';
    if (/う|く|ぐ|す|ず|つ|ぬ|ふ|ぶ|ぷ|む|ゆ|る/u.test(normalized)) return 'Với /u/ tiếng Nhật, môi chỉ tròn nhẹ và âm ngắn; tránh đẩy môi quá mạnh như “u” tiếng Việt.';
    if (/え|け|げ|せ|ぜ|て|で|ね|へ|べ|ぺ|め|れ/u.test(normalized)) return 'Giữ /e/ rõ và ngắn, không trượt thành “ê”; so sánh trực tiếp với câu mẫu.';
    if (/お|こ|ご|そ|ぞ|と|ど|の|ほ|ぼ|ぽ|も|よ|ろ|を/u.test(normalized)) return 'Giữ /o/ ngắn, môi tròn vừa phải; tránh kéo thành “ô” dài hơn mẫu.';
    return 'Nghe câu mẫu ở tốc độ chậm, lặp riêng mora này ba lần rồi thu lại cả câu.';
  }

  function setDetailsVisible(card, visible) {
    const details = card.querySelector('[data-pronunciation-details]');
    if (details) details.hidden = !visible;
    const button = card.querySelector('[data-action="score-details"]');
    if (button) button.textContent = visible ? 'Ẩn phân tích' : 'Xem lỗi';
  }

  function upsertHistory(state, attempt) {
    if (!attempt || !attempt.attemptId) return;
    const existing = state.history.findIndex(item => String(item.attemptId) === String(attempt.attemptId));
    if (existing >= 0) state.history[existing] = attempt;
    else state.history.unshift(attempt);
  }

  function renderHistory(card) {
    const state = stateFor(card);
    const history = card.querySelector('[data-pronunciation-history]');
    if (!history) return;
    history.replaceChildren();
    if (!state.history.length) {
      history.hidden = true;
      return;
    }
    history.hidden = false;
    const heading = document.createElement('div');
    heading.className = 'sub-pronunciation-history-heading';
    heading.textContent = 'Lịch sử bản thu (' + state.history.length + ')';
    history.appendChild(heading);
    state.history.forEach(function (attempt, position) {
      const item = document.createElement('div');
      item.className = 'sub-pronunciation-history-item';

      const meta = document.createElement('div');
      meta.className = 'sub-pronunciation-history-meta';
      const label = document.createElement('strong');
      label.textContent = 'Lần ' + (state.history.length - position);
      const score = document.createElement('span');
      const value = Number(attempt.pronunciationScore);
      score.textContent = Number.isFinite(value) ? Math.round(value) + '/100' : 'Chưa có điểm';
      score.dataset.grade = scoreGrade(value);
      meta.append(label, score);
      if (attempt.createdAt) {
        const date = document.createElement('time');
        const parsed = new Date(attempt.createdAt);
        date.textContent = Number.isNaN(parsed.getTime()) ? String(attempt.createdAt) : parsed.toLocaleString();
        meta.appendChild(date);
      }

      const audio = document.createElement('audio');
      audio.controls = true;
      audio.preload = 'none';
      audio.dataset.action = 'history-audio';
      audio.src = attempt.audioUrl || '';
      audio.addEventListener('click', function (event) {
        event.stopPropagation();
      });
      audio.addEventListener('play', function () {
        pauseSourceVideo();
        history.querySelectorAll('audio[data-action="history-audio"]').forEach(function (other) {
          if (other !== audio) other.pause();
        });
      });
      item.append(meta, audio);
      history.appendChild(item);
    });
  }

  function renderMetric(card, name, value) {
    const element = card.querySelector('[data-pronunciation-metric="' + name + '"]');
    if (!element) return;
    const score = scoreValue(value);
    element.textContent = scoreText(score);
    element.dataset.grade = score === null ? 'unknown' : scoreGrade(score);
  }

  function renderWordFocus(card, attempt, wordIndex) {
    const words = Array.isArray(attempt.words) ? attempt.words : [];
    const word = words[wordIndex];
    const focus = card.querySelector('[data-pronunciation-focus]');
    if (!focus || !word) {
      if (focus) focus.hidden = true;
      return;
    }
    const state = stateFor(card);
    state.selectedWordIndex = wordIndex;
    focus.hidden = false;
    card.querySelectorAll('[data-action="select-pronunciation-word"]').forEach(function (button) {
      button.setAttribute('aria-pressed', String(Number(button.dataset.wordIndex) === wordIndex));
    });

    const wordScore = scoreValue(word.accuracyScore);
    const focusWord = focus.querySelector('[data-pronunciation-focus-word]');
    const focusScore = focus.querySelector('[data-pronunciation-focus-score]');
    if (focusWord) focusWord.textContent = word.word || '—';
    if (focusScore) {
      focusScore.textContent = scoreText(wordScore);
      focusScore.dataset.grade = wordScore === null ? 'unknown' : scoreGrade(wordScore);
    }

    const context = contextForCard(card);
    const reading = readingForWord(word.word, context && context.readingText);
    const moras = splitMoras(reading);
    const moraScores = estimateMoraScores(moras, word.phonemes, wordScore);
    const track = focus.querySelector('[data-pronunciation-mora-track]');
    if (track) {
      track.replaceChildren();
      moraScores.forEach(function (entry) {
        const item = document.createElement('span');
        item.className = 'sub-pronunciation-mora';
        item.dataset.grade = entry.score === null ? 'unknown' : scoreGrade(entry.score);
        const kana = document.createElement('strong');
        kana.textContent = entry.mora;
        const score = document.createElement('small');
        score.textContent = entry.score === null ? '--' : String(Math.round(entry.score));
        item.append(kana, score);
        track.appendChild(item);
      });
      if (!moraScores.length) {
        const unavailable = document.createElement('span');
        unavailable.className = 'sub-pronunciation-mora-unavailable';
        unavailable.textContent = 'Chưa ánh xạ được từ này sang kana.';
        track.appendChild(unavailable);
      }
    }

    const weakMora = moraScores
      .filter(function (entry) { return entry.score !== null; })
      .sort(function (left, right) { return left.score - right.score; })[0];
    const confidence = focus.querySelector('[data-pronunciation-confidence]');
    if (confidence) {
      confidence.textContent = moraScores.length
        ? 'Các mức theo mora là ước tính từ thứ tự điểm âm; điểm từ phía trên là điểm Azure trả trực tiếp.'
        : 'Azure xác định độ lệch ở cấp từ nhưng chưa trả tên âm tiếng Nhật, nên Kitsu-chan chưa chỉ đích danh kana.';
    }
    const tip = focus.querySelector('[data-pronunciation-tip]');
    if (tip) {
      tip.textContent = weakMora
        ? 'Ưu tiên luyện “' + weakMora.mora + '” (' + scoreText(weakMora.score) + '). ' + practiceTip(weakMora.mora, word.errorType)
        : 'Từ này ' + errorLabel(word.errorType) + '. Hãy nghe câu mẫu, nói chậm từng mora rồi thu lại.';
    }
    const learnerButton = focus.querySelector('[data-action="play-focus-recording"]');
    if (learnerButton) learnerButton.disabled = !audioFor(card)?.getAttribute('src');
  }

  function playReference(card) {
    if (active) {
      setStatus(card, 'Hãy dừng bản thu trước khi nghe câu mẫu');
      return;
    }
    const video = sourceVideo();
    const context = contextForCard(card);
    const start = Number(context && context.startTime);
    const end = Number(context && context.endTime);
    if (!video || !Number.isFinite(start) || !Number.isFinite(end) || end <= start) {
      setStatus(card, 'Chưa có thời gian câu mẫu để phát lại');
      return;
    }
    const learnerAudio = audioFor(card);
    if (learnerAudio) learnerAudio.pause();
    clearReferencePlayback();
    try {
      video.currentTime = Math.max(0, start);
    } catch (_) {
      setStatus(card, 'Video chưa sẵn sàng để phát câu mẫu');
      return;
    }
    referencePlaybackListener = function () {
      if (video.currentTime < start - 0.4 || video.currentTime > end + 0.6) {
        clearReferencePlayback();
        return;
      }
      if (video.currentTime >= end) {
        video.pause();
        clearReferencePlayback();
      }
    };
    video.addEventListener('timeupdate', referencePlaybackListener);
    const playRequest = video.play();
    if (playRequest && typeof playRequest.catch === 'function') {
      playRequest.catch(function () {
        clearReferencePlayback();
        setStatus(card, 'Không thể phát câu mẫu lúc này');
      });
    }
  }

  function toggleLearnerAudio(card) {
    const audio = audioFor(card);
    if (!audio || !audio.getAttribute('src')) {
      setStatus(card, 'Chưa có bản thu để nghe lại');
      return;
    }
    pauseSourceVideo();
    if (audio.paused) {
      const playRequest = audio.play();
      if (playRequest && typeof playRequest.catch === 'function') {
        playRequest.catch(function () { setStatus(card, 'Không thể phát lại bản thu'); });
      }
    } else {
      audio.pause();
    }
  }

  function renderAttempt(card, attempt, addToHistory) {
    const state = stateFor(card);
    if (addToHistory !== false) {
      upsertHistory(state, attempt);
      renderHistory(card);
    }
    state.attempt = attempt;
    state.context = state.context || contextForCard(card);
    if (attempt.audioUrl && !state.blob) {
      state.audioUrl = attempt.audioUrl;
      const audio = audioFor(card);
      if (audio) { audio.src = attempt.audioUrl; audio.load(); }
    }
    setPanelVisible(card, true);
    const playback = card.querySelector('[data-pronunciation-playback]');
    if (playback) playback.hidden = false;
    const score = scoreValue(attempt.pronunciationScore);
    const scoreButton = card.querySelector('[data-action="score"]');
    if (scoreButton) {
      scoreButton.disabled = false;
      scoreButton.textContent = score !== null ? Math.round(score) + ' điểm' : '--';
      scoreButton.dataset.grade = scoreGrade(score);
      scoreButton.title = 'Mở chi tiết điểm câu này';
    }
    const result = card.querySelector('[data-pronunciation-result]');
    if (result) result.hidden = false;
    const resultScore = card.querySelector('[data-pronunciation-score]');
    if (resultScore) {
      resultScore.textContent = scoreText(score);
      resultScore.dataset.grade = scoreGrade(score);
    }
    renderMetric(card, 'accuracy', attempt.accuracyScore);
    renderMetric(card, 'fluency', attempt.fluencyScore);
    renderMetric(card, 'completeness', attempt.completenessScore);
    const reason = card.querySelector('[data-pronunciation-reason]');
    const words = Array.isArray(attempt.words) ? attempt.words : [];
    const weakWords = words.filter(function (word) {
      const value = scoreValue(word.accuracyScore);
      return (value !== null && value < FAIR_SCORE) || (word.errorType && word.errorType !== 'None');
    });
    if (reason) {
      const recognized = attempt.recognizedText ? ' Kitsu-chan nghe được: “' + attempt.recognizedText + '”.' : '';
      reason.textContent = weakWords.length
        ? 'Tập trung vào các từ tô đỏ hoặc vàng bên dưới. Bấm từng từ để xem mora cần luyện và cách sửa.' + recognized
        : (attempt.feedback || 'Các từ trong câu chưa có lỗi nổi bật.') + recognized;
    }
    const wordBox = card.querySelector('[data-pronunciation-word-scores]');
    if (wordBox) {
      wordBox.replaceChildren();
      words.forEach(function (word, index) {
        const item = document.createElement('button');
        const wordScore = scoreValue(word.accuracyScore);
        item.type = 'button';
        item.className = 'pronunciation-word';
        item.dataset.action = 'select-pronunciation-word';
        item.dataset.wordIndex = String(index);
        item.dataset.grade = scoreGrade(wordScore);
        item.setAttribute('aria-pressed', 'false');
        item.title = 'Xem cách luyện từ ' + (word.word || 'này');
        const label = document.createElement('span');
        label.textContent = word.word || '—';
        const value = document.createElement('small');
        value.textContent = wordScore === null ? '--' : String(Math.round(wordScore));
        item.append(label, value);
        wordBox.appendChild(item);
      });
    }
    const preferredIndex = weakWords.length ? words.indexOf(weakWords[0]) : 0;
    renderWordFocus(card, attempt, Math.max(0, preferredIndex));
    setDetailsVisible(card, addToHistory !== false);
  }

  async function assess(card) {
    const state = stateFor(card);
    if (state.attempt) {
      const details = card.querySelector('[data-pronunciation-details]');
      if (details) setDetailsVisible(card, details.hidden);
      return;
    }
    if (!state.blob || !state.context) {
      setStatus(card, 'Hãy thu câu này trước khi chấm');
      return;
    }
    const live = typeof window.getShadowingPronunciationContext === 'function'
      ? window.getShadowingPronunciationContext(Number(card.dataset.subtitleIndex)) : null;
    if (!live || String(live.subtitleId) !== String(state.context.subtitleId)) {
      resetCurrentAttempt(card);
      setStatus(card, 'Bạn đã đổi câu · hãy thu lại câu đang chọn');
      return;
    }
    const scoreButton = card.querySelector('[data-action="score"]');
    if (scoreButton) {
      scoreButton.disabled = true;
      scoreButton.textContent = 'Đang chấm…';
      scoreButton.setAttribute('aria-busy', 'true');
    }
    setStatus(card, 'Kitsu-chan đang chấm câu #' + state.context.sentenceNumber + '…');
    const controller = new AbortController();
    const requestTimeout = setTimeout(() => controller.abort(), 60000);
    try {
      const form = new FormData();
      const extension = state.blob.type.includes('ogg') ? 'ogg' : state.blob.type.includes('mp4') ? 'm4a' : 'webm';
      form.append('subtitleId', String(state.context.subtitleId));
      form.append('audio', state.blob, 'shadowing-recording.' + extension);
      const response = await fetch('/api/pronunciation/assess', {
        method: 'POST',
        headers: authHeaders(),
        body: form,
        signal: controller.signal
      });
      const result = await response.json().catch(() => ({}));
      if (!response.ok) {
        console.error('[Pronunciation] assessment request failed', {
          subtitleId: state.context.subtitleId,
          status: response.status,
          message: result.message || result.error || 'unknown-error'
        });
        throw new Error(result.message || result.error || ('Chấm phát âm thất bại (HTTP ' + response.status + ')'));
      }
      renderAttempt(card, result);
      setStatus(card, 'Đã chấm xong câu #' + state.context.sentenceNumber);
    } catch (error) {
      console.error('[Pronunciation] assessment failed', {
        subtitleId: state.context && state.context.subtitleId,
        type: error.name,
        message: error.message
      });
      if (scoreButton) {
        scoreButton.disabled = false;
        scoreButton.textContent = 'Chấm';
      }
      setStatus(card, error.name === 'AbortError'
        ? 'Chấm quá thời gian 60 giây · hãy thử lại'
        : (error.message || 'Không thể chấm phát âm lúc này'));
    } finally {
      clearTimeout(requestTimeout);
      if (scoreButton) scoreButton.removeAttribute('aria-busy');
    }
  }

  function bindAudio(card) {
    const audio = audioFor(card);
    if (!audio || audio.dataset.bound === '1') return;
    audio.dataset.bound = '1';
    ['loadedmetadata', 'timeupdate', 'ended', 'pause', 'play'].forEach(event => {
      audio.addEventListener(event, () => updateAudioUi(card));
    });
  }

  window.loadPronunciationAttempts = async function (videoId) {
    try {
      const query = encodeURIComponent(videoId);
      let response = await fetch('/api/pronunciation/attempts/history?videoId=' + query, { headers: authHeaders() });
      // Keep already-running servers compatible until they are restarted with the history endpoint.
      if (!response.ok) {
        response = await fetch('/api/pronunciation/attempts/latest?videoId=' + query, { headers: authHeaders() });
      }
      if (!response.ok) return;
      const attempts = await response.json();
      const bySubtitle = new Map();
      (Array.isArray(attempts) ? attempts : []).forEach(function (attempt) {
        const key = String(attempt.subtitleId);
        if (!bySubtitle.has(key)) bySubtitle.set(key, []);
        bySubtitle.get(key).push(attempt);
      });
      bySubtitle.forEach(function (subtitleHistory, subtitleId) {
        const card = cardFor(subtitleId);
        if (!card) return;
        const state = stateFor(card);
        state.history = subtitleHistory;
        bindAudio(card);
        renderHistory(card);
        const latest = state.history[0];
        if (latest) renderAttempt(card, latest, false);
      });
    } catch (_) { /* The lesson remains usable when history is unavailable. */ }
  };

  document.addEventListener('DOMContentLoaded', function () {
    document.addEventListener('click', function (event) {
      const action = event.target.closest('[data-action]');
      const card = event.target.closest('.subtitle-item');
      if (!action || !card) return;
      event.stopPropagation();
      const index = Number(card.dataset.subtitleIndex);
      if (action.dataset.action === 'record') startRecording(card, index);
      else if (action.dataset.action === 'score') assess(card);
      else if (action.dataset.action === 'score-details') {
        const details = card.querySelector('[data-pronunciation-details]');
        if (details) setDetailsVisible(card, details.hidden);
      } else if (action.dataset.action === 'select-pronunciation-word') {
        const state = stateFor(card);
        if (state.attempt) renderWordFocus(card, state.attempt, Number(action.dataset.wordIndex));
      } else if (action.dataset.action === 'play-reference') {
        playReference(card);
      } else if (action.dataset.action === 'play-recording' || action.dataset.action === 'play-focus-recording') {
        toggleLearnerAudio(card);
      } else if (action.dataset.action === 'recording-seek') {
        const audio = audioFor(card);
        if (audio && Number.isFinite(audio.duration)) audio.currentTime = audio.duration * Number(action.value) / 100;
      }
    });
    window.addEventListener('shadowing:pronunciation-context-change', function (event) {
      if (!active || !event.detail || String(active.context.subtitleId) === String(event.detail.subtitleId)) return;
      if (event.detail.source === 'playback') return;
      const oldCard = active.card;
      stopRecording(true);
      resetCurrentAttempt(oldCard);
      setStatus(oldCard, 'Đã chuyển câu · bản thu cũ đã được bỏ');
    });
  });

  window.addEventListener('shadowing:subtitles-rendered', function () {
    document.querySelectorAll('.subtitle-item').forEach(bindAudio);
  });
}());
