(function () {
  'use strict';

  // BR-05/UC5 — Modal helper
  function showQuotaModal(data) {
    var modal = document.getElementById('quotaModal');
    var msg = document.getElementById('quotaMessage');
    var usage = document.getElementById('quotaUsage');
    if (!modal) return;
    if (msg && data && data.message) msg.textContent = data.message;
    if (usage && data && data.data) {
      var d = data.data;
      var html = '';
      if (typeof d.dailyMinutes === 'number') {
        html += '<li>Hôm nay: <strong>' + d.dailyMinutes.toFixed(1) + '</strong>/' +
                (d.dailyLimitMin != null && d.dailyLimitMin < 1e9 ? d.dailyLimitMin.toFixed(0) : '∞') + ' phút</li>';
      }
      if (typeof d.monthlyMinutes === 'number') {
        html += '<li>Tháng này: <strong>' + d.monthlyMinutes.toFixed(1) + '</strong>/' +
                (d.monthlyLimitMin != null && d.monthlyLimitMin < 1e9 ? d.monthlyLimitMin.toFixed(0) : '∞') + ' phút</li>';
      }
      if (typeof d.creditBalance === 'number') {
        html += '<li>CP hiện có: <strong>' + d.creditBalance + '</strong>'
          + (typeof d.creditPerTurn === 'number' ? ' (1 lượt = ' + d.creditPerTurn + ' CP)' : '') + '</li>';
        // Báo trước: số dư sau lượt này, hoặc số CP còn thiếu.
        if (typeof d.creditPerTurn === 'number' && d.creditPerTurn > 0) {
          html += d.creditBalance >= d.creditPerTurn
            ? '<li>Sau lượt này còn: <strong>' + (d.creditBalance - d.creditPerTurn) + ' CP</strong></li>'
            : '<li>Bạn còn thiếu <strong>' + (d.creditPerTurn - d.creditBalance) + ' CP</strong> cho một lượt</li>';
        }
        if (window.JlasCpAutoPay && window.JlasCpAutoPay.capReached('aiCall')) {
          html += '<li>Đã tự dùng đủ ' + window.JlasCpAutoPay.DAILY_CAP + ' CP hôm nay nên cần xác nhận lại.</li>';
        }
      }
      usage.innerHTML = html;
    }
    modal.hidden = false;
    var close = document.getElementById('btnCloseQuota');
    if (close) close.onclick = function () { modal.hidden = true; };
    var useCp = document.getElementById('btnQuotaUseCredit');
    if (useCp) {
      var d2 = (data && data.data) || {};
      var cost = d2.creditPerTurn;
      var canPay = data && data.code === 'QUOTA_EXCEEDED' && typeof cost === 'number' && cost > 0
        && typeof d2.creditBalance === 'number' && d2.creditBalance >= cost;
      useCp.hidden = !canPay;
      if (canPay) useCp.innerHTML = '<i class="fa-solid fa-coins"></i> Dùng ' + cost + ' CP cho lượt này';
      var autoWrap = document.getElementById('quotaAutoWrap');
      var autoBox = document.getElementById('quotaAutoCredit');
      var autoCap = document.getElementById('quotaAutoCap');
      if (autoWrap) autoWrap.hidden = !canPay || !window.JlasCpAutoPay;
      if (autoCap && window.JlasCpAutoPay) autoCap.textContent = window.JlasCpAutoPay.DAILY_CAP;
      if (autoBox) autoBox.checked = false;
      useCp.onclick = function () {
        modal.hidden = true;
        var auto = !!(autoBox && autoBox.checked);
        if (window.AICallUI && typeof window.AICallUI.onUseCredit === 'function') window.AICallUI.onUseCredit(auto, cost);
      };
    }
    var buy = document.getElementById('btnQuotaBuy');
    if (buy) buy.onclick = function () { window.location.href = '/credits'; };
    var up = document.getElementById('btnQuotaUpgrade');
    if (up) up.onclick = function () { window.location.href = '/premium'; };
  }

  function showMuteModal(data) {
    var modal = document.getElementById('muteModal');
    var msg = document.getElementById('muteMessage');
    if (!modal) return;
    if (msg && data && data.message) msg.textContent = data.message;
    modal.hidden = false;
    var close = document.getElementById('btnCloseMute');
    if (close) close.onclick = function () { modal.hidden = true; };
  }

  window.AICallUI = { showQuotaModal: showQuotaModal, showMuteModal: showMuteModal };

  const body = document.body;
  const sessionId = body.dataset.sessionId;
  const scenarioId = body.dataset.scenarioId;
  const freeChat = body.dataset.freeChat === 'true' || window.__FREE_CHAT__ === true;
  const scenarioBackgroundUrl = body.dataset.backgroundUrl;
  const setSceneBackground = window.AiCallSceneImage.createSceneImageLoader(function(url){
    if(freeChat)return;
    const chatFrame=document.querySelector('.cv-main');
    if(chatFrame)chatFrame.style.setProperty('--scenario-chat-background-image','url('+JSON.stringify(url)+')');
  },function(){return new Image();});
  if(!freeChat)setSceneBackground(scenarioBackgroundUrl);
  let ended = body.dataset.ended === 'true';

  const messagesEl = document.getElementById('chatMessages');
  const input = document.getElementById('messageInput');
  const btnSend = document.getElementById('btnSend');
  const composeError = document.getElementById('composeError');
  const btnMic = document.getElementById('btnMic');
  const suggestRow = document.getElementById('suggestRow');
  const typing = document.getElementById('typingIndicator');
  const progressFill = document.getElementById('progressFill');
  const progressText = document.getElementById('progressText');
  const missionList = document.getElementById('missionList');
  const stateBadge = document.getElementById('stateBadge');
  const completeOverlay = document.getElementById('completeOverlay');
  const btnResult = document.getElementById('btnResult');

  // The sentence a learner sent into yesterday's free chat, waiting in today's input.
  const PENDING_FREE_CHAT_KEY = 'jlas.aicall.freechat.pending';
  if (freeChat && input && !input.disabled) {
    try {
      const pending = sessionStorage.getItem(PENDING_FREE_CHAT_KEY);
      if (pending) {
        sessionStorage.removeItem(PENDING_FREE_CHAT_KEY);
        input.value = pending;
      }
    } catch (e) {}
  }

  let busy = false;
  let settings = loadSettings();
  const hasCast = body.dataset.hasCast === 'true';
  const teacherVoice = body.dataset.teacherVoice === 'true';
  if (teacherVoice) settings.ttsGender = settings.teacherGender || 'male';
  if (freeChat) {
    // Free chat keeps reading/translation preferences; suggestions remain scenario-only.
    settings.suggestions = false;
    // feedback defaults true via defaultSettings; user can toggle in sidebar
  }
  let freeChatPrefs = freeChat ? loadFreeChatPrefs() : null;
  let vocabIndex = freeChat ? {} : buildVocabIndex();
  let ttsAudio = null;
  let ttsObjectUrl = null;
  let ttsMediaSource = null;
  let ttsAbortController = null;
  let ttsRunId = 0;
  let ttsBusy = false;

  function jsonHeaders() {
    if (window.JlasAuth && typeof window.JlasAuth.authHeaders === 'function') {
      return window.JlasAuth.authHeaders();
    }
    return { 'Content-Type': 'application/json' };
  }

  const ttsRateRange = document.getElementById('ttsRateRange');
  const ttsGenderSelect = document.getElementById('ttsGenderSelect');
  const ttsRateLabel = document.getElementById('ttsRateLabel');
  const btnTtsTest = document.getElementById('btnTtsTest');
  const btnTtsFemale = document.getElementById('btnTtsFemale');
  const btnTtsMale = document.getElementById('btnTtsMale');
  const ttsHint = document.getElementById('ttsHint');

  applySettings();
  bindToggles();
  bindTtsControls();
  bindExistingMessages();
  bindCompose();
  if (freeChat) {
    bindFreeChatPrefs();
  } else {
    bindVocabList();
    bindWordModal();
  }
  if (btnResult) btnResult.href = body.dataset.resultUrl || '#';
  scrollBottom(true);
  // Load AI suggestions only if DB chips empty (async — does not block page)
  if (!freeChat && !ended && suggestRow && suggestRow.children.length === 0) {
    fetch('/api/conversation/' + sessionId + '/suggestions')
      .then(function (r) { return r.json(); })
      .then(function (data) { renderSuggestions(data.items || []); })
      .catch(function () {});
  }

  function defaultSettings() {
    return {
      furigana: true,
      translation: false,
      feedback: true,
      suggestions: true,
      autoplay: false,
      autoscroll: true,
      ttsGender: 'female', // female = Google 日本語, male = Microsoft Ichiro
      ttsRate: 1
    };
  }

  function defaultFreeChatPrefs() {
    return {
      conversationActive: true,
      personality: 'friendly',
      level: 'n3',
      style: 'casual',
      emotion: 'friendly',
      initiative: 'medium',
      responseLength: 'medium',
      conversationMode: 'free-talk',
      favoriteTopics: []
    };
  }

  function loadFreeChatPrefs() {
    try {
      var prefs = Object.assign(defaultFreeChatPrefs(), JSON.parse(localStorage.getItem('jlas_freechat_prefs') || '{}'));
      // migrate old chip "Programming"  "Technology"
      if (Array.isArray(prefs.favoriteTopics)) {
        prefs.favoriteTopics = prefs.favoriteTopics.map(function (t) {
          return t === 'Programming' || t === 'Lập trình' ? 'Technology' : t;
        });
      } else {
        prefs.favoriteTopics = [];
      }
      return prefs;
    } catch (e) {
      return defaultFreeChatPrefs();
    }
  }

  function saveFreeChatPrefs() {
    if (!freeChatPrefs) return;
    localStorage.setItem('jlas_freechat_prefs', JSON.stringify(freeChatPrefs));
  }

  function bindFreeChatPrefs() {
    if (!freeChatPrefs) return;
    const btnDrive = document.getElementById('btnConversationActive');
    const topicsCard = document.getElementById('favoriteTopicsCard');
    const personality = document.getElementById('fcPersonality');
    const level = document.getElementById('fcLevel');
    const style = document.getElementById('fcStyle');
    const initiative = document.getElementById('fcInitiative');
    const topicInputs = [...document.querySelectorAll('#favoriteTopicsCard input[data-topic]')];
    const topicSummary = document.getElementById('fcTopicSummary');

    function applyDriveUi() {
      if (btnDrive) {
        btnDrive.classList.toggle('on', !!freeChatPrefs.conversationActive);
        btnDrive.setAttribute('aria-pressed', freeChatPrefs.conversationActive ? 'true' : 'false');
      }
      if (topicsCard) {
        topicsCard.style.opacity = freeChatPrefs.conversationActive ? '1' : '0.45';
        topicInputs.forEach(function (input) { input.disabled = !freeChatPrefs.conversationActive; });
      }
    }

    if (btnDrive) {
      btnDrive.addEventListener('click', function () {
        freeChatPrefs.conversationActive = !freeChatPrefs.conversationActive;
        saveFreeChatPrefs();
        applyDriveUi();
      });
    }

    function applyTopicSummary() {
      if (topicSummary) topicSummary.textContent = freeChatPrefs.favoriteTopics.length
        ? freeChatPrefs.favoriteTopics.length + ' đã chọn' : 'Chưa chọn';
    }
    topicInputs.forEach(function (input) {
      input.checked = (freeChatPrefs.favoriteTopics || []).includes(input.dataset.topic);
      input.addEventListener('change', function () {
        freeChatPrefs.favoriteTopics = topicInputs.filter(function (item) { return item.checked; })
          .map(function (item) { return item.dataset.topic; });
        saveFreeChatPrefs();
        applyTopicSummary();
      });
    });
    applyTopicSummary();
    if (topicsCard) {
      document.addEventListener('click', function (event) {
        if (!topicsCard.contains(event.target)) topicsCard.open = false;
      });
      topicsCard.addEventListener('keydown', function (event) {
        if (event.key === 'Escape') {
          topicsCard.open = false;
          topicsCard.querySelector('summary').focus();
        }
      });
    }

    function bindSelect(el, key) {
      if (!el) return;
      if (freeChatPrefs[key]) el.value = freeChatPrefs[key];
      el.addEventListener('change', function () {
        freeChatPrefs[key] = el.value;
        saveFreeChatPrefs();
      });
    }
    bindSelect(personality, 'personality');
    bindSelect(level, 'level');
    bindSelect(style, 'style');
    bindSelect(initiative, 'initiative');
    applyDriveUi();
  }

  function buildFreeChatSettingsPayload() {
    if (!freeChat || !freeChatPrefs) return undefined;
    return {
      conversationActive: !!freeChatPrefs.conversationActive,
      personality: freeChatPrefs.personality || 'friendly',
      level: freeChatPrefs.level || 'n3',
      style: freeChatPrefs.style || 'casual',
      emotion: freeChatPrefs.emotion || 'friendly',
      initiative: freeChatPrefs.initiative || 'medium',
      responseLength: freeChatPrefs.responseLength || 'medium',
      conversationMode: freeChatPrefs.conversationMode || 'free-talk',
      favoriteTopics: freeChatPrefs.favoriteTopics || []
    };
  }

  function normalizeGender(g) {
    return g === 'male' ? 'male' : 'female';
  }

  function loadSettings() {
    try {
      const s = Object.assign(defaultSettings(), JSON.parse(localStorage.getItem('jlas_cv_settings') || '{}'));
      s.ttsRate = clampTtsRate(s.ttsRate);
      // migrate old free-form voice key  gender when possible
      if (s.ttsGender !== 'male' && s.ttsGender !== 'female') {
        const old = String(s.ttsVoice || '').toLowerCase();
        if (old.indexOf('ichiro') >= 0) s.ttsGender = 'male';
        else s.ttsGender = 'female';
      }
      s.ttsGender = normalizeGender(s.ttsGender);
      delete s.ttsVoice;
      return s;
    } catch (e) {
      return defaultSettings();
    }
  }

  function clampTtsRate(v) {
    const n = Number(v);
    if (!isFinite(n)) return 1;
    return Math.min(1.5, Math.max(0.5, Math.round(n * 10) / 10));
  }

  function formatRate(r) {
    return (Math.round(clampTtsRate(r) * 10) / 10).toFixed(1) + '×';
  }

  function saveSettings() {
      localStorage.setItem('jlas_cv_settings', JSON.stringify(settings));
  }

  function furiganaDebugStatus(source) {
    const readings = Array.from(document.querySelectorAll('.bubble-text rt'));
    const sample = readings[0] ? window.getComputedStyle(readings[0]) : null;
    const sampleRect = readings[0] ? readings[0].getBoundingClientRect() : null;
    const status = {
      source: source || 'manual',
      setting: !!settings.furigana,
      bodyClassEnabled: body.classList.contains('show-furigana'),
      switches: document.querySelectorAll('.switch[data-toggle="furigana"]').length,
      rubyElements: document.querySelectorAll('.bubble-text ruby').length,
      readingElements: readings.length,
      sampleReading: readings[0] ? readings[0].textContent.trim() : null,
      sampleCss: sample ? {
        display: sample.display,
        visibility: sample.visibility,
        opacity: sample.opacity,
        fontSize: sample.fontSize
      } : null,
      sampleRect: sampleRect ? {
        width: Math.round(sampleRect.width),
        height: Math.round(sampleRect.height)
      } : null
    };
    window.__JLAS_FURIGANA_DEBUG = status;
    // JSON thay vì Object để người dùng có thể copy nguyên dòng từ DevTools.
    console.info('[JLAS Furigana] ' + JSON.stringify(status));
    return status;
  }

  // Có thể chạy JlasFuriganaDebug.status() trong Console để kiểm tra tại chỗ.
  window.JlasFuriganaDebug = {
    status: function () { return furiganaDebugStatus('console'); },
    enable: function () {
      settings.furigana = true;
      saveSettings();
      applySettings();
      return furiganaDebugStatus('console-enable');
    },
    disable: function () {
      settings.furigana = false;
      saveSettings();
      applySettings();
      return furiganaDebugStatus('console-disable');
    }
  };

  function applySettings() {
    body.classList.toggle('show-furigana', !!settings.furigana);
    body.classList.toggle('show-translation', !!settings.translation);
    body.classList.toggle('show-feedback', !!settings.feedback);
    body.classList.toggle('show-suggestions', !!settings.suggestions);
    document.querySelectorAll('.switch[data-toggle]').forEach(function (el) {
      const key = el.dataset.toggle;
      const on = !!settings[key];
      el.classList.toggle('on', on);
      el.setAttribute('aria-pressed', on ? 'true' : 'false');
    });
    if (ttsRateRange) ttsRateRange.value = String(clampTtsRate(settings.ttsRate));
    if (ttsRateLabel) ttsRateLabel.textContent = formatRate(settings.ttsRate);
    applyGenderButtons();
    furiganaDebugStatus('apply-settings');
  }

  function applyGenderButtons() {
    const g = normalizeGender(settings.ttsGender);
    if (ttsGenderSelect) ttsGenderSelect.value = g;
    if (btnTtsFemale) {
      btnTtsFemale.setAttribute('aria-pressed', g === 'female' ? 'true' : 'false');
    }
    if (btnTtsMale) {
      btnTtsMale.setAttribute('aria-pressed', g === 'male' ? 'true' : 'false');
    }
  }

  function bindToggles() {
    document.querySelectorAll('.switch[data-toggle]').forEach(function (el) {
      el.addEventListener('click', function (event) {
        event.stopPropagation();
        const key = el.dataset.toggle;
        const previous = !!settings[key];
        settings[key] = !settings[key];
        console.info('[JLAS Toggle]', { key: key, previous: previous, next: !!settings[key] });
        saveSettings();
        applySettings();
        if (key === 'furigana' || key === 'translation') {
          document.querySelectorAll('.msg-row.assistant').forEach(function (row) {
            // Khi người học chủ động bật Furigana, cho phép backend bổ sung
            // cách đọc cho cả những từ chưa nằm trong bộ từ vựng kịch bản.
            ensureAnnotations(row, key === 'furigana' && settings.furigana);
          });
        }
      });

      const row = el.closest('.toggle-row');
      if (row && !row.dataset.toggleRowBound) {
        row.dataset.toggleRowBound = '1';
        row.addEventListener('click', function () {
          el.click();
        });
      }
    });
  }

  function bindTtsControls() {
    function setGender(g) {
      settings.ttsGender = normalizeGender(g);
      if (teacherVoice) settings.teacherGender = settings.ttsGender;
      saveSettings();
      applyGenderButtons();
      updateTtsHint();
    }

    if (ttsGenderSelect) ttsGenderSelect.addEventListener('change', function () { setGender(ttsGenderSelect.value); });
    if (btnTtsFemale) {
      btnTtsFemale.addEventListener('click', function () { setGender('female'); });
    }
    if (btnTtsMale) {
      btnTtsMale.addEventListener('click', function () { setGender('male'); });
    }
    if (ttsRateRange) {
      ttsRateRange.addEventListener('input', function () {
        settings.ttsRate = clampTtsRate(ttsRateRange.value);
        if (ttsRateLabel) ttsRateLabel.textContent = formatRate(settings.ttsRate);
      });
      ttsRateRange.addEventListener('change', function () {
        settings.ttsRate = clampTtsRate(ttsRateRange.value);
        saveSettings();
        if (ttsRateLabel) ttsRateLabel.textContent = formatRate(settings.ttsRate);
      });
    }
    if (btnTtsTest) {
      btnTtsTest.addEventListener('click', function () {
        if (hasCast) {
          const latest = [...messagesEl.querySelectorAll('.msg-row.assistant[data-message-id]')].pop();
          if (latest) return speak(latest.dataset.text, latest.querySelector('.btn-tts'));
        }
        speak('こんにちは。よろしくお願いします。', btnTtsTest);
      });
    }
    updateTtsHint();
    fetch('/api/tts/status')
      .then(function (r) { return r.json(); })
      .then(function (data) {
        if (ttsHint && data && data.configured === false) {
          ttsHint.textContent = 'Chưa cấu hình Fish Audio — điền fish.audio.api.key và voice model id trong application.properties';
        } else if (ttsHint && hasCast) {
          updateTtsHint();
        } else if (ttsHint && data && data.model) {
        ttsHint.textContent = 'Fish Audio · mô hình ' + data.model + ' · '
            + (normalizeGender(settings.ttsGender) === 'male' ? 'Nam' : 'Nữ');
        }
      })
      .catch(function () {});
  }

  function updateTtsHint() {
    if (!ttsHint) return;
    if (hasCast) { ttsHint.textContent = 'Giọng đọc theo nhân vật trong câu chuyện'; return; }
    const g = normalizeGender(settings.ttsGender) === 'male' ? 'Nam' : 'Nữ';
    ttsHint.textContent = 'Fish Audio · giọng ' + g + ' (model id trong application.properties)';
  }

  function disposeTtsTransport() {
    if (ttsAbortController) {
      try { ttsAbortController.abort(); } catch (e) {}
      ttsAbortController = null;
    }
    if (ttsAudio) {
      try {
        ttsAudio.onended = null;
        ttsAudio.onerror = null;
        ttsAudio.pause();
        ttsAudio.removeAttribute('src');
        ttsAudio.load();
      } catch (e) {}
      ttsAudio = null;
    }
    if (ttsMediaSource && ttsMediaSource.readyState === 'open') {
      try { ttsMediaSource.endOfStream(); } catch (e) {}
    }
    ttsMediaSource = null;
    if (ttsObjectUrl) {
      try { URL.revokeObjectURL(ttsObjectUrl); } catch (e) {}
      ttsObjectUrl = null;
    }
  }

  function stopTts() {
    ttsRunId += 1;
    disposeTtsTransport();
  }

  function buildVocabIndex() {
    const map = {};
    try {
      const raw = typeof window.__VOCAB__ === 'string' ? JSON.parse(window.__VOCAB__) : window.__VOCAB__;
      (raw.items || []).forEach(function (v) {
        if (v.wordJa) map[v.wordJa] = v;
      });
    } catch (e) {}
    document.querySelectorAll('#vocabList .vocab-item').forEach(function (el) {
      map[el.dataset.word] = {
        wordJa: el.dataset.word,
        reading: el.dataset.reading,
        meaning: el.dataset.meaning,
        partOfSpeech: el.dataset.pos,
        jlptLevel: el.dataset.jlpt
      };
    });
    return map;
  }

  function bindExistingMessages() {
    messagesEl.querySelectorAll('.msg-row').forEach(function (row) {
      wireRow(row);
      if (row.dataset.role === 'assistant') {
        ensureAnnotations(row);
      }
    });
  }

  function wireRow(row) {
    const text = row.dataset.text || '';
    row.querySelectorAll('.btn-tts').forEach(function (btn) {
      btn.addEventListener('click', function () { speak(text, btn); });
    });
    row.querySelectorAll('.btn-copy').forEach(function (btn) {
      btn.addEventListener('click', function () {
        navigator.clipboard && navigator.clipboard.writeText(text);
        btn.innerHTML = '<i class="fa-solid fa-check"></i> Đã chép';
        setTimeout(function () { btn.innerHTML = '<i class="fa-solid fa-copy"></i> Chép'; }, 1200);
      });
    });

  }

  async function ensureAnnotations(row, force) {
    if (row.dataset.role !== 'assistant') return;
    const text = row.dataset.text || '';
    const textEl = row.querySelector('.msg-text');
    const trEl = row.querySelector('.translation-line');

    if ((settings.furigana || force) && (!row.dataset.rubyDone || force)) {
      try {
        console.info('[JLAS Furigana] request', {
          sessionId: sessionId,
          force: !!force,
          text: text,
          rubyDone: row.dataset.rubyDone || null
        });
        // force=true (nút あ): cho phép AI bù từ chưa có trong vocab
        const res = await fetch('/api/conversation/' + sessionId + '/furigana', {
          method: 'POST',
          headers: jsonHeaders(),
          body: JSON.stringify({ text: text, allowAi: !!force })
        });
        const data = await res.json();
        if (!res.ok) throw new Error('Furigana API HTTP ' + res.status);
        console.info('[JLAS Furigana] response', {
          status: res.status,
          tokenCount: data.tokens ? data.tokens.length : 0,
          rubyCount: (data.tokens || []).filter(function (token) {
            return !!(token.reading || '').trim() && /[\u4E00-\u9FFF]/.test(token.surface || '');
          }).length
        });
        if (data.tokens && data.tokens.length && textEl) {
          textEl.innerHTML = tokensToRuby(data.tokens);
          bindTokenClicks(textEl);
          if (hasCompleteKanjiReadings(data.tokens)) {
            row.dataset.rubyDone = '1';
          } else {
            delete row.dataset.rubyDone;
          }
          furiganaDebugStatus('annotations-rendered');
        }
      } catch (e) {
        console.error('[JLAS Furigana] Không thể tải Furigana', e);
      }
    }

    if (settings.translation && trEl && !row.dataset.trDone) {
      try {
        const res = await fetch('/api/conversation/' + sessionId + '/translate', {
          method: 'POST',
          headers: jsonHeaders(),
          body: JSON.stringify({ text: text })
        });
        const data = await res.json();
        if (!res.ok || !data.translation) throw new Error('Không thể tải bản dịch.');
        trEl.textContent = data.translation;
        trEl.hidden = false;
        trEl.classList.add('show');
        row.dataset.trDone = '1';
      } catch (e) {
        trEl.textContent = 'Không thể tải bản dịch. Tắt rồi bật lại công tắc dịch trong Công cụ để thử lại.';
        trEl.hidden = false;
        trEl.classList.add('show');
      }
    } else if (trEl && row.dataset.trDone) {
      trEl.classList.add('show');
      trEl.hidden = false;
    }
  }

  function tokensToRuby(tokens) {
    return (tokens || []).map(function (t) {
      const surface = escapeHtml(t.surface || '');
      const reading = t.reading || '';
      const tokenClass = t.fromVocabDb ? 'token token--dictionary' : 'token-fragment';
      const attrs = ' class="' + tokenClass + '" data-surface="' + escapeAttr(t.surface || '') + '"'
        + ' data-reading="' + escapeAttr(reading) + '"'
        + ' data-meaning="' + escapeAttr(t.meaning || '') + '"'
        + ' data-pos="' + escapeAttr(t.partOfSpeech || '') + '"'
        + ' data-jlpt="' + escapeAttr(t.jlptLevel || '') + '"';
      if (reading && /[\u4E00-\u9FFF]/.test(t.surface || '')) {
        return '<ruby' + attrs + '>' + surface + '<rt>' + escapeHtml(reading) + '</rt></ruby>';
      }
      return '<span' + attrs + '>' + surface + '</span>';
    }).join('');
  }

  function hasCompleteKanjiReadings(tokens) {
    return (tokens || []).every(function (token) {
      return !/[\u4E00-\u9FFF]/.test(token.surface || '') || !!(token.reading || '').trim();
    });
  }

  function bindTokenClicks(root) {
    root.querySelectorAll('.token').forEach(function (el) {
      el.addEventListener('click', function () {
        openWordModal({
          wordJa: el.dataset.surface,
          reading: el.dataset.reading,
          meaning: el.dataset.meaning || (vocabIndex[el.dataset.surface] && vocabIndex[el.dataset.surface].meaning) || '',
          partOfSpeech: el.dataset.pos || '',
          jlptLevel: el.dataset.jlpt || ''
        });
      });
    });
  }

  function openWordModal(v) {
    const modal = document.getElementById('wordModal');
    document.getElementById('wordModalTitle').textContent = v.wordJa || '';
    document.getElementById('wordModalReading').textContent = v.reading || '—';
    document.getElementById('wordModalMeaning').textContent = v.meaning || 'Chưa có nghĩa trong cơ sở dữ liệu.';
    document.getElementById('wordModalPos').textContent = v.partOfSpeech || '—';
    document.getElementById('wordModalJlpt').textContent = v.jlptLevel || '—';
    modal.classList.add('show');
  }

  function bindWordModal() {
    const modal = document.getElementById('wordModal');
    if (!modal) return;
    const btnClose = document.getElementById('btnCloseWordModal');
    if (btnClose) {
      btnClose.addEventListener('click', function () {
        modal.classList.remove('show');
      });
    }
    modal.addEventListener('click', function (e) {
      if (e.target === modal) modal.classList.remove('show');
    });
    const btnSave = document.getElementById('btnSaveFlashcard');
    if (btnSave) {
      btnSave.addEventListener('click', function () {
        const word = document.getElementById('wordModalTitle').textContent;
        const reading = document.getElementById('wordModalReading').textContent;
        const meaning = document.getElementById('wordModalMeaning').textContent;
        let list = [];
        try { list = JSON.parse(localStorage.getItem('jlas_flashcards') || '[]'); } catch (e) {}
        list.push({ word: word, reading: reading, meaning: meaning, at: Date.now() });
        localStorage.setItem('jlas_flashcards', JSON.stringify(list));
        this.innerHTML = '<i class="fa-solid fa-check"></i> Đã lưu';
        const btn = this;
        setTimeout(function () { btn.innerHTML = '<i class="fa-solid fa-bookmark"></i> Lưu thẻ'; }, 1200);
      });
    }
  }

  function bindCompose() {
    btnSend.addEventListener('click', send);
    input.addEventListener('keydown', function (e) {
      // Enter commits a Japanese IME candidate before it submits a chat turn.
      if (e.isComposing || e.keyCode === 229) return;
      if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        send();
      }
    });
    if (suggestRow) {
      suggestRow.addEventListener('click', function (e) {
        const chip = e.target.closest('.suggest-chip');
        if (!chip) return;
        input.value = chip.textContent;
        // On a phone, focus opens the keyboard and scrolls the page for it, although nothing needs typing.
        if (!coarsePointer()) input.focus();
      });
    }
    if (btnMic && (window.SpeechRecognition || window.webkitSpeechRecognition)) {
      const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
      const rec = new SR();
      rec.lang = 'ja-JP';
      rec.interimResults = false;
      // Một lần nói tối đa voiceMaxSeconds giây và chữ nhận được không vượt maxlength của ô nhập (kế hoạch Pro/CP).
      const voiceMaxMs = (Number(input.dataset.voiceMaxSeconds) || 30) * 1000;
      const maxChars = Number(input.getAttribute('maxlength')) || 0;
      let voiceTimer = null;
      const stopVoiceTimer = function () {
        if (voiceTimer) clearTimeout(voiceTimer);
        voiceTimer = null;
        btnMic.classList.remove('recording');
      };
      btnMic.addEventListener('click', function () {
        try {
          rec.start();
          btnMic.classList.add('recording');
          voiceTimer = setTimeout(function () { try { rec.stop(); } catch (e) {} }, voiceMaxMs);
        } catch (e) {}
      });
      rec.onresult = function (ev) {
        const heard = (ev.results[0] && ev.results[0][0] && ev.results[0][0].transcript) || input.value;
        input.value = maxChars > 0 ? Array.from(heard).slice(0, maxChars).join('') : heard;
      };
      rec.onend = stopVoiceTimer;
      rec.onerror = stopVoiceTimer;
    } else if (btnMic) {
      btnMic.title = 'Trình duyệt không hỗ trợ nhận giọng nói';
      btnMic.disabled = true;
    }
  }

  function bindVocabList() {
    document.querySelectorAll('#vocabList .vocab-item').forEach(function (el) {
      el.addEventListener('click', function () {
        openWordModal({
          wordJa: el.dataset.word,
          reading: el.dataset.reading,
          meaning: el.dataset.meaning,
          partOfSpeech: el.dataset.pos,
          jlptLevel: el.dataset.jlpt
        });
      });
    });
  }

  // Mỗi lần gửi có một mã riêng: server dùng nó để không tính phí hai lần khi request bị gửi lặp (JLAS-W0199).
  function newRequestId() {
    if (window.crypto && typeof window.crypto.randomUUID === 'function') return window.crypto.randomUUID();
    return 'r' + Date.now().toString(36) + Math.random().toString(36).slice(2, 10);
  }

  // Lượt vừa bị chặn vì hết hạn mức: nếu người học bấm "Dùng CP" thì gửi lại đúng lượt đó (cùng mã).
  let creditRetry = null;
  // "Không hỏi lại hôm nay" (js/components/cp-auto-pay.js): các lượt sau tự trả CP, không hỏi lại trong ngày.
  const AUTO_PAY = 'aiCall';
  function autoCreditCovers() {
    return !!window.JlasCpAutoPay && window.JlasCpAutoPay.covers(AUTO_PAY);
  }
  window.AICallUI.onUseCredit = function (auto, cost) {
    if (!creditRetry || busy || ended) return;
    if (window.JlasCpAutoPay) {
      if (auto) window.JlasCpAutoPay.enable(AUTO_PAY, cost);
      else window.JlasCpAutoPay.disable(AUTO_PAY);
    }
    const retry = creditRetry;
    creditRetry = null;
    busy = true;
    btnSend.disabled = true;
    showTyping(true);
    postTurn(retry.text, retry.learnerRow, retry.requestId, true);
  };

  function addChargeNote(row, text, isError) {
    if (!row) return;
    const col = row.querySelector('.msg-col') || row;
    const note = document.createElement('div');
    note.className = 'turn-charge-note';
    note.style.cssText = 'font-size:.75rem;margin-top:4px;color:' + (isError ? 'var(--wrong, #b42318)' : 'var(--muted, #667085)') + ';';
    note.textContent = text;
    col.appendChild(note);
  }

  function showComposeError(message) {
    if (composeError) { composeError.textContent = message || ''; composeError.hidden = !message; }
    input.setAttribute('aria-invalid', message ? 'true' : 'false');
  }

  // Fast script check for typing/STT; the server also validates ambiguous Kanji-only text.
  function hasJapaneseInput(text) {
    const content = text.normalize('NFKC').replace(/(?<![A-Za-z0-9])(?:JLPT|AI|N[1-5])(?![A-Za-z0-9])/g, '');
    if (!/[\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Han}]/u.test(content)) return false;
    return ![...content].some(function (char) {
      return (/\p{L}/u.test(char) && !/[\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Han}ー]/u.test(char))
        || (/[\p{Cc}\p{Cf}]/u.test(char) && !/\s/u.test(char) && char !== '\u200d');
    });
  }

  if (input) input.addEventListener('input', function () { showComposeError(''); });

  async function send() {
    if (busy || ended) return;
    const text = (input.value || '').trim();
    if (!text) return;
    if (!hasJapaneseInput(text)) {
      showComposeError('AI Call chỉ nhận tiếng Nhật. Vui lòng nhập bằng Hiragana/Katakana hoặc viết câu Kanji kèm kana.');
      input.focus();
      return;
    }
    showComposeError('');
    busy = true;
    btnSend.disabled = true;
    const learnerRow = addMessageRow('user', text);
    input.value = '';
    showTyping(true);
    creditRetry = null;
    await postTurn(text, learnerRow, newRequestId(), autoCreditCovers());
  }

  async function postTurn(text, learnerRow, requestId, useCredit) {
    try {
      const payload = { sessionId: Number(sessionId), message: text, clientRequestId: requestId, useCredit: !!useCredit };
      const fc = buildFreeChatSettingsPayload();
      if (fc) payload.freeChatSettings = fc;
      const res = await fetch('/api/conversation/chat', {
        method: 'POST',
        headers: jsonHeaders(),
        body: JSON.stringify(payload)
      });
      const data = await res.json().catch(function () { return {}; });
      showTyping(false);

      // A tab left open overnight: yesterday's chat is closed, so carry the sentence to today's.
      if (!res.ok && data.code === 'AICALL_FREE_CHAT_NEW_DAY') {
        try { sessionStorage.setItem(PENDING_FREE_CHAT_KEY, text); } catch (e) {}
        window.location.href = '/ai-call/free-chat/start';
        return;
      }

      if (!res.ok && (data.code === 'AICALL_JAPANESE_REQUIRED' || data.code === 'AICALL_MESSAGE_TOO_LONG')) {
        if (learnerRow) learnerRow.remove();
        if (!input.value) input.value = text;
        showComposeError(data.message || 'AI Call chỉ nhận tiếng Nhật.');
        input.focus();
        return;
      }

      // Hết hạn mức (hoặc không đủ CP): hiện modal chờ hạn mức mới / dùng CP / nâng cấp.
      if (res.status === 402) {
        if (data && data.code === 'QUOTA_EXCEEDED') creditRetry = { text: text, learnerRow: learnerRow, requestId: requestId };
        // Không đủ CP thì tắt tự trả; hết trần ngày thì giữ để popup nói rõ lý do hỏi lại.
        if (window.JlasCpAutoPay && !(data && data.code === 'QUOTA_EXCEEDED')) window.JlasCpAutoPay.disable(AUTO_PAY);
        showQuotaModal(data);
        return;
      }
      // Tài khoản đang bị chế tài: server trả 403 kèm lý do.
      if (res.status === 403) {
        showMuteModal(data);
        return;
      }
      // 409: lượt trước chưa xong / đang xử lý; 503: quá tải (chưa bị tính phí).
      if (!res.ok) {
        addChargeNote(learnerRow, '⚠️ ' + ((data && (data.message || data.error)) || 'Không gửi được, thử lại nhé.'), true);
        return;
      }

      if (data.error && !data.assistantReply) {
        addMessageRow('assistant', data.error);
      } else {
        const row = addMessageRow('assistant', data.assistantReply || '…', null, data.assistantTokens, null, data.assistantSpeaker, data.assistantMessageId);
        if (data.feedback) appendFeedbackCard(learnerRow, data.feedback);
        if (data.error) {
          // hiện lỗi kỹ thuật nhỏ dưới bubble (không giả làm thoại AI)
          const note = document.createElement('div');
          note.style.cssText = 'font-size:.78rem;color:var(--wrong, #b42318);margin-top:4px;';
          note.textContent = '⚠️ ' + data.error;
          row.querySelector('.msg-col').appendChild(note);
        }
        if (data.sceneTransition) addSceneTransition(data.sceneTransition);
        if (data.sceneImageUrl) setSceneBackground(data.sceneImageUrl);
        let audibleRow = row;
        if (data.speakerOpening) {
          const opening = data.speakerOpening;
          audibleRow = addMessageRow('assistant', opening.text, null, null, null, opening.speaker, opening.messageId);
          if (settings.furigana || settings.translation) ensureAnnotations(audibleRow, settings.furigana);
        }
        if (data.currentSpeaker) {
          window.__AI_ROLE__ = data.currentSpeaker.name;
          document.querySelectorAll('[data-current-speaker]').forEach(function (label) { label.textContent = data.currentSpeaker.name; });
        }
        if (settings.autoplay) speak(audibleRow.dataset.text || '', audibleRow.querySelector('.btn-tts'));
        if (settings.furigana || settings.translation) ensureAnnotations(row, settings.furigana);
      }
      if (data.chargeRefunded) {
        addChargeNote(learnerRow, 'AI gặp lỗi nên lượt này không bị tính.', false);
      } else if (data.creditCharged > 0) {
        let autoNote = '';
        if (window.JlasCpAutoPay && window.JlasCpAutoPay.state(AUTO_PAY).on) {
          window.JlasCpAutoPay.add(AUTO_PAY, data.creditCharged);
          const paid = window.JlasCpAutoPay.state(AUTO_PAY);
          autoNote = ' (tự dùng hôm nay: ' + paid.spent + '/' + paid.cap + ' CP)';
        }
        addChargeNote(learnerRow, '−' + data.creditCharged + ' CP cho lượt này' + autoNote, false);
      } else if (typeof data.quotaTurnsLeft === 'number' && data.chargeType === 'QUOTA' && data.quotaTurnsLeft <= 3) {
        // Báo trước khi sắp hết hạn mức, để popup CP không xuất hiện bất ngờ.
        addChargeNote(learnerRow, data.quotaTurnsLeft > 0
          ? 'Còn ' + data.quotaTurnsLeft + ' lượt miễn phí. Sau đó mỗi lượt tốn ' + (data.creditPerTurn || '') + ' CP.'
          : 'Đã hết lượt miễn phí. Lượt sau tốn ' + (data.creditPerTurn || '') + ' CP.', false);
      }

      if (!freeChat) {
        updateProgress(data);
        renderSuggestions(data.suggestedReplies || []);
      }

      if (data.conversationEnded && !freeChat) {
        ended = true;
        input.disabled = true;
        btnSend.disabled = true;
        if (btnEndSession) btnEndSession.hidden = true;
        if (completeOverlay) completeOverlay.classList.add('show');
      }
    } catch (e) {
      showTyping(false);
      addMessageRow('assistant', 'Lỗi mạng. Vui lòng thử lại.');
    } finally {
      busy = false;
      if (!input.disabled) btnSend.disabled = false;
      if (!coarsePointer()) input.focus();
    }
  }

  /** Touch screens: focusing the input would pop the keyboard up and push the page up for it. */
  function coarsePointer() {
    return typeof window.matchMedia === 'function' && window.matchMedia('(pointer: coarse)').matches;
  }

  // Người học chủ động kết thúc phiên (JLAS-W0205): kịch bản thì chấm điểm rồi sang trang kết quả.
  const btnEndSession = document.getElementById('btnEndSession');
  if (btnEndSession) {
    btnEndSession.addEventListener('click', async function () {
      if (busy) return;
      if (!window.confirm('Kết thúc cuộc hội thoại này?')) return;
      btnEndSession.disabled = true;
      try {
        const res = await fetch('/api/conversation/' + sessionId + '/end', { method: 'POST', headers: jsonHeaders() });
        const data = await res.json().catch(function () { return {}; });
        if (!res.ok) throw new Error((data && data.message) || 'HTTP ' + res.status);
        window.location.href = data.redirectUrl || body.dataset.homeUrl || '/ai-call';
      } catch (e) {
        btnEndSession.disabled = false;
        window.alert('Không kết thúc được phiên: ' + e.message);
      }
    });
  }

  function addMessageRow(role, text, feedback, tokens, translation, speaker, messageId) {
    const row = document.createElement('div');
    row.className = 'msg-row ' + role;
    row.dataset.role = role;
    row.dataset.text = text;
    if (messageId) row.dataset.messageId = messageId;
    if (speaker) row.dataset.speakerCode = speaker.code;
    const now = new Date();
    const hh = String(now.getHours()).padStart(2, '0');
    const mm = String(now.getMinutes()).padStart(2, '0');
    const learnerRole = window.__USER_ROLE__ || 'Bạn';
    const roleLabel = role === 'assistant'
      ? (speaker ? speaker.name : window.__AI_ROLE__ || 'AI')
      : (freeChat || learnerRole === 'Bạn' ? 'Bạn' : 'Bạn · ' + learnerRole);

    let bubbleInner = '<div class="bubble-text jp msg-text"></div>';
    if (role === 'assistant') {
      bubbleInner +=
        '<div class="translation-line" hidden></div>' +
        '<div class="bubble-tools">' +
        '<button type="button" class="tool-chip btn-tts"><i class="fa-solid fa-volume-high"></i> Phát</button>' +
        '<button type="button" class="tool-chip btn-copy"><i class="fa-solid fa-copy"></i> Chép</button>' +
        '</div>';
    }

    row.innerHTML =
      '<div class="avatar">' + (role === 'assistant' ? '<span>AI</span>' : '<i class="fa-solid fa-user" aria-hidden="true"></i>') + '</div>' +
      '<div class="msg-col">' +
      '<div class="msg-meta"><strong></strong><span></span></div>' +
      '<div class="bubble">' + bubbleInner + '</div>' +
      '</div>';

    row.querySelector('.msg-meta strong').textContent = roleLabel;
    row.querySelector('.msg-meta span').textContent = hh + ':' + mm;
    const textEl = row.querySelector('.msg-text');
    if (tokens && tokens.length) {
      textEl.innerHTML = tokensToRuby(tokens);
      bindTokenClicks(textEl);
      if (hasCompleteKanjiReadings(tokens)) row.dataset.rubyDone = '1';
    } else {
      textEl.textContent = text;
    }
    if (translation) {
      const tr = row.querySelector('.translation-line');
      if (tr) {
        tr.textContent = translation;
        tr.hidden = false;
        tr.classList.add('show');
        row.dataset.trDone = '1';
      }
    }
    messagesEl.appendChild(row);
    if (feedback) appendFeedbackCard(row, feedback);
    wireRow(row);
    scrollBottom();
    return row;
  }

  function addSceneTransition(text) {
    if (!messagesEl || !text) return;
    const banner = document.createElement('div');
    banner.className = 'scene-transition';
    banner.setAttribute('role', 'separator');

    const label = document.createElement('span');
    const icon = document.createElement('i');
    icon.className = 'fa-regular fa-clock';
    icon.setAttribute('aria-hidden', 'true');
    label.appendChild(icon);
    label.appendChild(document.createTextNode(text));
    banner.appendChild(label);
    messagesEl.appendChild(banner);
    scrollBottom();
  }

  function appendFeedbackCard(row, feedback) {
    if (!row || !feedback || !feedback.correctedText) return;
    const column = row.querySelector('.msg-col');
    if (!column) return;

    const oldCard = column.querySelector('.feedback-card');
    if (oldCard) oldCard.remove();

    const card = document.createElement('details');
    card.className = 'feedback-card has-content';
    card.open = true;

    const title = feedback.mistakeType === 'vocab_help' ? 'Nghĩa từ' : 'Sửa lỗi';
    card.innerHTML =
      '<summary><i class="fa-solid fa-spell-check"></i> ' + title + '</summary>' +
      '<div class="bad"></div><div class="ok"></div><div class="reason"></div>';
    card.querySelector('.bad').textContent = feedback.originalText || '';
    card.querySelector('.ok').textContent = feedback.correctedText || '';
    card.querySelector('.reason').textContent = feedback.reasonVi || '';
    if (feedback.mistakeType === 'vocab_help') card.classList.add('is-vocab-help');
    column.appendChild(card);
    scrollBottom();
    if (feedback.mistakeType !== 'vocab_help') showCorrectionReadings(card.querySelector('.ok'), feedback.correctedText);
  }

  // Learners speak their answer, so the correction always shows how its kanji are read (一人 → ひとり),
  // whatever the furigana toggle says.
  async function showCorrectionReadings(el, text) {
    if (!el || !/[一-鿿]/.test(text || '')) return;
    for (const allowAi of [false, true]) {
      try {
        const res = await fetch('/api/conversation/' + sessionId + '/furigana', {
          method: 'POST',
          headers: jsonHeaders(),
          body: JSON.stringify({ text: text, allowAi: allowAi })
        });
        if (!res.ok) return;
        const data = await res.json();
        if (!data.tokens || !data.tokens.length) continue;
        el.innerHTML = tokensToRuby(data.tokens);
        bindTokenClicks(el);
        if (hasCompleteKanjiReadings(data.tokens)) return;
      } catch (e) {
        console.error('[JLAS Furigana] Không thể tải cách đọc của câu sửa', e);
        return;
      }
    }
  }

  function updateProgress(data) {
    if (freeChat) return;
    if (typeof data.progressPercent === 'number' && progressFill && progressText) {
      progressFill.style.width = data.progressPercent + '%';
      progressText.textContent = data.progressPercent;
    }
    if (data.currentState && stateBadge) stateBadge.textContent = data.currentState;
    if (missionList && (data.completedObjectives || data.remainingObjectives)) {
      const doneSet = new Set(data.completedObjectives || []);
      missionList.querySelectorAll('li').forEach(function (li) {
        const title = (li.querySelector('span') || li).textContent.trim();
        const done = doneSet.has(title);
        li.classList.toggle('done', done);
        li.classList.toggle('todo', !done);
        const icon = li.querySelector('i');
        if (icon) icon.className = done ? 'fa-solid fa-circle-check' : 'fa-regular fa-square';
      });
    }
  }

  function renderSuggestions(items) {
    if (!suggestRow || freeChat) return;
    suggestRow.innerHTML = '';
    (items || []).forEach(function (s) {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'suggest-chip';
      b.textContent = s;
      suggestRow.appendChild(b);
    });
  }

  function showTyping(on) {
    typing.classList.toggle('show', !!on);
    if (on) scrollBottom();
  }

  function scrollBottom(force) {
    if (!settings.autoscroll && !force) return;
    messagesEl.scrollTop = messagesEl.scrollHeight;
  }

  function ttsPayload(text, btn) {
    const payload = {
      text: text,
      gender: normalizeGender(settings.ttsGender),
      speed: clampTtsRate(settings.ttsRate)
    };
    const row = btn && btn.closest('.msg-row');
    if (row && row.dataset.messageId) payload.messageId = Number(row.dataset.messageId);
    else if (btn && btn.dataset.speakerCode) {
      payload.sessionId = sessionId;
      payload.speakerCode = btn.dataset.speakerCode;
    }
    return JSON.stringify(payload);
  }

  async function ttsResponseError(res) {
    const contentType = (res.headers.get('content-type') || '').toLowerCase();
    let msg = 'TTS lỗi HTTP ' + res.status;
    if (contentType.indexOf('json') >= 0) {
      try {
        const err = await res.json();
        if (err && err.error) msg = err.error;
      } catch (e) {}
    }
    return new Error(msg);
  }

  function canStreamMp3() {
    return typeof window.MediaSource !== 'undefined'
      && typeof window.ReadableStream !== 'undefined'
      && window.MediaSource.isTypeSupported('audio/mpeg');
  }

  function waitForMediaSource(mediaSource, signal) {
    return new Promise(function (resolve, reject) {
      function cleanup() {
        mediaSource.removeEventListener('sourceopen', opened);
        mediaSource.removeEventListener('error', failed);
        if (signal) signal.removeEventListener('abort', aborted);
      }
      function opened() {
        cleanup();
        try {
          resolve(mediaSource.addSourceBuffer('audio/mpeg'));
        } catch (e) {
          reject(e);
        }
      }
      function failed() {
        cleanup();
        reject(new Error('Trình duyệt không mở được luồng MP3.'));
      }
      function aborted() {
        cleanup();
        reject(new DOMException('TTS cancelled', 'AbortError'));
      }
      mediaSource.addEventListener('sourceopen', opened, { once: true });
      mediaSource.addEventListener('error', failed, { once: true });
      if (signal) signal.addEventListener('abort', aborted, { once: true });
    });
  }

  function appendAudioChunk(sourceBuffer, chunk, signal) {
    return new Promise(function (resolve, reject) {
      function cleanup() {
        sourceBuffer.removeEventListener('updateend', updated);
        sourceBuffer.removeEventListener('error', failed);
        sourceBuffer.removeEventListener('abort', failed);
        if (signal) signal.removeEventListener('abort', aborted);
      }
      function updated() { cleanup(); resolve(); }
      function failed() { cleanup(); reject(new Error('Luồng âm thanh không hợp lệ.')); }
      function aborted() { cleanup(); reject(new DOMException('TTS cancelled', 'AbortError')); }
      sourceBuffer.addEventListener('updateend', updated, { once: true });
      sourceBuffer.addEventListener('error', failed, { once: true });
      sourceBuffer.addEventListener('abort', failed, { once: true });
      if (signal) signal.addEventListener('abort', aborted, { once: true });
      try {
        const exactChunk = chunk.buffer.slice(chunk.byteOffset, chunk.byteOffset + chunk.byteLength);
        sourceBuffer.appendBuffer(exactChunk);
      } catch (e) {
        cleanup();
        reject(e);
      }
    });
  }

  function bindStreamingAudioLifecycle(audio, btn, runId) {
    audio.onended = function () {
      if (runId !== ttsRunId) return;
      if (btn) btn.classList.remove('active');
      ttsBusy = false;
      stopTts();
    };
    audio.onerror = function () {
      if (runId !== ttsRunId) return;
      if (btn) btn.classList.remove('active');
      ttsBusy = false;
      stopTts();
      console.warn('Phát âm thanh TTS thất bại');
    };
  }

  async function playStreamingTts(text, btn, runId) {
    const controller = new AbortController();
    ttsAbortController = controller;

    const mediaSource = new MediaSource();
    ttsMediaSource = mediaSource;
    ttsObjectUrl = URL.createObjectURL(mediaSource);
    const audio = new Audio(ttsObjectUrl);
    ttsAudio = audio;
    bindStreamingAudioLifecycle(audio, btn, runId);

    const sourceBufferPromise = waitForMediaSource(mediaSource, controller.signal);
    const responsePromise = fetch('/api/tts/speak-stream', {
      method: 'POST',
      headers: jsonHeaders(),
      body: ttsPayload(text, btn),
      signal: controller.signal
    });

    // Keep a single media element waiting for the first progressive MP3 frames.
    let playError = null;
    const playPromise = audio.play().catch(function (e) { playError = e; });
    const sourceBuffer = await sourceBufferPromise;
    const res = await responsePromise;
    if (!res.ok) throw await ttsResponseError(res);
    if (!res.body || typeof res.body.getReader !== 'function') {
      throw new Error('Trình duyệt không hỗ trợ nhận âm thanh theo luồng.');
    }

    const reader = res.body.getReader();
    try {
      while (true) {
        if (runId !== ttsRunId) throw new DOMException('TTS cancelled', 'AbortError');
        const part = await reader.read();
        if (part.done) break;
        if (part.value && part.value.byteLength) {
          await appendAudioChunk(sourceBuffer, part.value, controller.signal);
        }
      }
    } catch (e) {
      e.ttsPlaybackStarted = audio.currentTime > 0;
      throw e;
    } finally {
      try { reader.releaseLock(); } catch (e) {}
    }

    if (mediaSource.readyState === 'open' && !sourceBuffer.updating) {
      try { mediaSource.endOfStream(); } catch (e) {}
    }
    await playPromise;
    if (playError) {
      playError.ttsPlaybackStarted = audio.currentTime > 0;
      throw playError;
    }
  }

  async function speak(text, btn) {
    if (!canStreamMp3()) {
      return speakLegacyFullMp3(text, btn);
    }
    if (!text) return;
    stopTts();
    const runId = ttsRunId;
    ttsBusy = true;
    document.querySelectorAll('.btn-tts.active, #btnTtsTest.active').forEach(function (el) {
      el.classList.remove('active');
    });
    if (btn) btn.classList.add('active');

    try {
      await playStreamingTts(text, btn, runId);
    } catch (e) {
      if (runId !== ttsRunId || (e && e.name === 'AbortError')) return;
      if (!e.ttsPlaybackStarted) {
        console.warn('Streaming TTS unavailable, using full MP3 fallback', e);
        disposeTtsTransport();
        return speakLegacyFullMp3(text, btn);
      }
      if (btn) btn.classList.remove('active');
      ttsBusy = false;
      stopTts();
      console.warn('Streaming TTS failed after playback started', e);
    }
  }

  async function speakLegacyFullMp3(text, btn) {
    if (!text) return;
    stopTts();
    const runId = ttsRunId;
    const controller = new AbortController();
    ttsAbortController = controller;
    ttsBusy = true;
    document.querySelectorAll('.btn-tts.active, #btnTtsTest.active').forEach(function (el) {
      el.classList.remove('active');
    });
    if (btn) btn.classList.add('active');

    try {
      const res = await fetch('/api/tts/speak', {
        method: 'POST',
        headers: jsonHeaders(),
        body: ttsPayload(text, btn),
        signal: controller.signal
      });

      if (!res.ok) throw await ttsResponseError(res);

      const blob = await res.blob();
      if (runId !== ttsRunId) return;
      ttsObjectUrl = URL.createObjectURL(blob);
      const audio = new Audio(ttsObjectUrl);
      ttsAudio = audio;
      audio.onended = function () {
        if (runId !== ttsRunId) return;
        if (btn) btn.classList.remove('active');
        stopTts();
        ttsBusy = false;
      };
      audio.onerror = function () {
        if (runId !== ttsRunId) return;
        if (btn) btn.classList.remove('active');
        stopTts();
        ttsBusy = false;
        if (ttsHint) ttsHint.textContent = 'Không phát được audio TTS.';
      };
      await audio.play();
    } catch (e) {
      if (runId !== ttsRunId || (e && e.name === 'AbortError')) return;
      if (btn) btn.classList.remove('active');
      ttsBusy = false;
      stopTts();
      if (ttsHint) {
        ttsHint.textContent = (e && e.message) ? e.message : 'TTS thất bại. Kiểm tra fish.audio config.';
      }
      console.warn('TTS failed', e);
    }
  }

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, function (c) {
      return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c];
    });
  }
  function escapeAttr(s) {
    return escapeHtml(s).replace(/\n/g, ' ');
  }
})();
