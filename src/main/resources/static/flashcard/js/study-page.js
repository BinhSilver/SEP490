/* Study mode — flip card, remember / forgot, settings, Study Complete */
(function () {
  'use strict';

  var params = new URLSearchParams(location.search);
  var setId = Number(params.get('setId') || 0);
  var mode = (params.get('mode') || 'ALL').toUpperCase();
  var studyMode = normalizeStudyMode(params.get('studyMode') || localStorage.getItem('jlab.flashcard.studyMode') || 'RECALL');
  var cards = [];
  var index = 0;
  var flipped = false;
  var reviewed = {};
  var browseResults = {};
  var browseVisited = {};
  var visitedCards = {};
  var flipCount = 0;
  var studyClock = window.FlashStudyClock.create();
  var sessionDone = false;
  var elapsedTimer = null;
  var totalSetCards = null;
  var Faces = window.FlashStudyFaceFields;
  var FACE_FIELDS_KEY = 'jlab.flashcard.faceFields';
  var faceFields = readSavedFaceFields();
  // Fields at least one card of the session has; a face just needs one non-empty field per card.
  var faceAllowed = Faces.FIELDS.map(function (field) { return field.value; });
  var faceDraft = null;
  var setDetail = null;
  var pendingRating = 0;
  var settingsListboxes = [];
  // Ghi nhớ: hàng đợi lặp thẻ trong phiên; recallOnly = chỉ học lại các thẻ khó của phiên trước.
  var recall = null;
  var recallOnly = null;
  var sessionTitle = '';
  var focusActive = false;
  var swipeController = null;
  var faceFitter = null;
  // Lướt thẻ: tắt "Theo dõi tiến độ" thì chỉ xem thẻ, không chấm Đã nhớ / Chưa thuộc và không lưu gì.
  var trackProgress = readTrackProgress();

  function syncStudyClock() {
    if (document.hidden || sessionDone || !cards.length) studyClock.pause();
    else studyClock.start();
    updateElapsed();
    saveRecall();
  }

  document.addEventListener('visibilitychange', syncStudyClock);
  window.addEventListener('pageshow', syncStudyClock);
  window.addEventListener('pagehide', function () {
    studyClock.pause();
    saveRecall();
  });

  document.addEventListener('DOMContentLoaded', function () {
    // Học thẻ bắt buộc đăng nhập (để đếm study_count + lưu tiến độ)
    if (!FlashShell.requireLogin('Vui lòng đăng nhập để học thẻ từ')) {
      return;
    }

    var btnSettings = document.getElementById('btn-settings');
    if (btnSettings) btnSettings.addEventListener('click', openSettings);
    bindCardListPager();
    var trackInput = document.getElementById('track-progress');
    trackInput.checked = trackProgress;
    trackInput.addEventListener('change', function () {
      trackProgress = trackInput.checked;
      try { localStorage.setItem('jlab.flashcard.trackProgress', trackProgress ? 'true' : 'false'); } catch (e) { /* bỏ qua */ }
      updateStudyModeUi();
      paintFocus();
    });
    document.getElementById('btn-focus').addEventListener('click', function () {
      if (focusActive) exitFocus(); else enterFocus();
    });
    document.getElementById('btn-focus-exit').addEventListener('click', exitFocus);
    document.getElementById('btn-focus-settings').addEventListener('click', openSettings);
    document.addEventListener('fullscreenchange', function () {
      // Esc của trình duyệt thoát fullscreen thì thoát luôn giao diện tập trung.
      if (!document.fullscreenElement && focusActive) exitFocus();
    });
    document.getElementById('btn-close-settings').addEventListener('click', closeSettings);
    document.getElementById('btn-cancel-settings').addEventListener('click', closeSettings);
    document.getElementById('btn-apply-settings').addEventListener('click', applySettings);
    setupSettingsListboxes();
    updateStudyModeUi();
    document.querySelectorAll('[data-study-mode]').forEach(function (button) {
      button.addEventListener('click', function () { selectStudyMode(button.getAttribute('data-study-mode')); });
    });
    document.getElementById('btn-prev').addEventListener('click', function () { go(-1); });
    document.getElementById('btn-next').addEventListener('click', function () { go(1); });
    document.getElementById('btn-flip').addEventListener('click', flip);
    document.getElementById('btn-browse-prev').addEventListener('click', function () { go(-1); });
    document.getElementById('btn-browse-next').addEventListener('click', function () { go(1); });
    document.getElementById('btn-actions-settings').addEventListener('click', openSettings);
    document.getElementById('flip-card').addEventListener('click', flip);
    swipeController = window.FlashStudySwipe.attach(document.getElementById('flip-card'), {
      enabled: function () { return studyMode === 'BROWSE' && trackProgress && !sessionDone && !marking && cards.length > 0; },
      onSwipe: function (direction) { return markBrowse(direction === 'right'); }
    });
    faceFitter = window.FlashStudyFitText.attach(document.getElementById('flip-card'),
      [document.getElementById('face-front'), document.getElementById('face-back')], window);
    document.getElementById('btn-speak').addEventListener('click', function (event) {
      event.stopPropagation();
      speakJapanese();
    });
    var btnQuizNav = document.getElementById('btn-quiz-nav');
    if (btnQuizNav) btnQuizNav.addEventListener('click', startQuiz);
    var btnSpeedRecallNav = document.getElementById('btn-speed-recall-nav');
    if (btnSpeedRecallNav) btnSpeedRecallNav.addEventListener('click', function () {
      location.href = '/flashcard/speed-recall.html?setId=' + encodeURIComponent(setId);
    });
    document.querySelectorAll('[data-browse-result]').forEach(function (button) {
      button.addEventListener('click', function () {
        markBrowse(button.getAttribute('data-browse-result') === 'REMEMBERED');
      });
    });
    document.querySelectorAll('[data-quality]').forEach(function (button) {
      button.addEventListener('click', function () { mark(Number(button.getAttribute('data-quality'))); });
    });

    document.getElementById('btn-quiz-learning').addEventListener('click', startQuiz);
    document.getElementById('btn-study-learning').addEventListener('click', function () {
      if (this.hidden) {
        FlashShell.showToast('Không còn thẻ chưa thuộc / chưa học', 'err');
        return;
      }
      location.href = '/flashcard/study.html?setId=' + setId + '&mode=LEARNING';
    });
    document.getElementById('btn-recall-hard').addEventListener('click', function () {
      if (!recall) return;
      var hard = recallHardIds();
      if (!hard.length) return;
      recallOnly = hard;
      clearRecall();
      load();
    });
    document.getElementById('btn-study-all').addEventListener('click', function () {
      location.href = '/flashcard/study.html?setId=' + setId + '&mode=ALL';
    });
    document.getElementById('btn-reset-progress').addEventListener('click', openReset);
    document.getElementById('btn-close-reset').addEventListener('click', closeReset);
    document.getElementById('btn-cancel-reset').addEventListener('click', closeReset);
    document.getElementById('btn-confirm-reset').addEventListener('click', confirmReset);
    document.getElementById('btn-open-rating').addEventListener('click', openRating);
    document.getElementById('btn-close-rating').addEventListener('click', closeRating);
    document.getElementById('btn-cancel-rating').addEventListener('click', closeRating);
    document.getElementById('btn-save-rating').addEventListener('click', saveRating);

    document.addEventListener('keydown', function (e) {
      if (!document.getElementById('settings-modal').hidden) return;
      if (!document.getElementById('reset-modal').hidden) return;
      if (!document.getElementById('rating-modal').hidden) return;
      if (e.key === 'Escape' && focusActive) { exitFocus(); return; }
      if (sessionDone) return;
      if (studyMode === 'RECALL' && /^[0-5]$/.test(e.key) && !e.ctrlKey && !e.metaKey && !e.altKey) {
        e.preventDefault();
        mark(Number(e.key));
        return;
      }
      if (e.code === 'Space') { e.preventDefault(); flip(); }
      if (e.key === 'ArrowLeft') go(-1);
      if (e.key === 'ArrowRight') go(1);
    });

    if (!setId) {
      document.getElementById('study-empty').hidden = false;
      return;
    }
    load();
  });

  async function load() {
    try {
      var q = mode && mode !== 'ALL' ? ('?mode=' + encodeURIComponent(mode)) : '';
      var session = await FlashApi.get('/api/study/' + setId + q);
      applySession(session);
      loadSetDetail();
    } catch (e) {
      FlashShell.showToast(e.message || 'Không tải được phiên học', 'err');
      document.getElementById('study-empty').hidden = false;
    }
  }

  function applySession(session) {
    cards = session.cards || [];
    if (recallOnly) {
      cards = cards.filter(function (card) { return recallOnly.indexOf(card.id) >= 0; });
    }
    recall = null;
    totalSetCards = null;
    index = 0;
    flipped = false;
    reviewed = {};
    browseResults = {};
    browseVisited = {};
    visitedCards = {};
    flipCount = 0;
    studyClock.reset();
    stopElapsedTimer();
    sessionDone = false;
    updateStudyModeUi();
    var studyTitle = document.getElementById('study-title');
    if (studyTitle) studyTitle.textContent = session.title || 'Học thẻ';
    sessionTitle = session.title || 'Học thẻ';
    if (session.frontField || session.backField) {
      faceFields = { front: session.frontField || faceFields.front, back: session.backField || faceFields.back };
    }
    faceAllowed = Faces.available(cards);
    faceFields = Faces.sanitize(faceFields, faceAllowed, cards);
    syncSettingsListboxes();

    document.getElementById('study-complete').hidden = true;
    document.getElementById('study-card-list').hidden = true;
    document.getElementById('study-mode-nav').hidden = false;
    document.getElementById('study-session-progress').hidden = false;
    if (!cards.length) {
      document.getElementById('study-empty').hidden = false;
      document.getElementById('study-stage').hidden = true;
      document.getElementById('study-actions').hidden = true;
      return;
    }
    document.getElementById('study-empty').hidden = true;
    document.getElementById('study-stage').hidden = false;
    document.getElementById('study-actions').hidden = false;
    if (studyMode === 'RECALL') initRecall();
    renderCard();
    startElapsedTimer();
  }

  function renderCard() {
    var c = cards[index];
    if (!c) return;
    if (swipeController) swipeController.cancel();
    visitedCards[c.id] = true;
    if (studyMode === 'BROWSE') browseVisited[c.id] = true;
    flipped = false;
    document.getElementById('flip-inner').classList.remove('is-flipped');
    // The canonical study API returns the card fields (front/back/hanViet),
    // not legacy-only displayFront/displayBack properties. Resolve them here
    // so an existing card never renders as a blank face.
    document.getElementById('face-front').textContent = cardFace(c, faceFields.front);
    document.getElementById('face-back').textContent = cardFace(c, faceFields.back);
    if (faceFitter) faceFitter.refit();
    var img = document.getElementById('face-image');
    if (c.image) {
      img.src = c.image;
      img.hidden = false;
    } else {
      img.hidden = true;
      img.removeAttribute('src');
    }
    var studyProgress = document.getElementById('study-progress');
    if (studyProgress) studyProgress.textContent = (index + 1) + ' / ' + cards.length;
    var progressFill = document.getElementById('study-progress-fill');
    var progressPercent = Math.round(((index + 1) / cards.length) * 100);
    if (recall) progressPercent = paintRecallStatus(c.id);
    document.getElementById('study-browse-position').textContent = (index + 1) + ' / ' + cards.length;
    if (progressFill) progressFill.style.width = progressPercent + '%';
    paintFocus();
  }

  /* One line per chosen field (Kanji → Hiragana → Hán Việt → Nghĩa); `separator` joins them inline. */
  function cardFace(card, fields, separator) {
    if (!card) return '';
    return Faces.text(card, fields, separator) || card.front || card.kanji || '';
  }

  function flip() {
    if (sessionDone) return;
    flipped = !flipped;
    document.getElementById('flip-inner').classList.toggle('is-flipped', flipped);
    if (flipped) {
      flipCount++;
      var flipLabel = document.getElementById('study-flip-count');
      if (flipLabel) flipLabel.textContent = flipCount + ' lượt';
    }
  }

  function go(delta) {
    if (!cards.length || sessionDone || marking) return;
    // Ghi nhớ đi theo hàng đợi lặp lại, không lướt tự do.
    if (recall) return;
    if (delta > 0) {
      if (studyMode === 'BROWSE') recordBrowseLeave();
      if (Object.keys(visitedCards).length >= cards.length) {
        // Đi hết thẻ cũng kết thúc phiên, kể cả khi người dùng chưa chấm điểm.
        // Chỉ các lựa chọn rõ ràng mới được gửi lên API review.
        showComplete();
        return;
      }
    }
    index = (index + delta + cards.length) % cards.length;
    renderCard();
  }

  function recordBrowseLeave() {
    if (!trackProgress) return;
    var c = cards[index];
    if (c && !Object.prototype.hasOwnProperty.call(browseResults, c.id)) {
      // Không bấm lựa chọn = chưa nhớ trong phiên này. Không tạo progress row,
      // vì trạng thái NEW trong database chính là "chưa làm".
      browseResults[c.id] = 'NOT_REMEMBERED';
    }
  }

  function speakJapanese() {
    var c = cards[index];
    var text = c && (c.front || c.displayFront);
    if (!text) return;
    if (!('speechSynthesis' in window) || !('SpeechSynthesisUtterance' in window)) {
      FlashShell.showToast('Trình duyệt chưa hỗ trợ đọc tiếng Nhật', 'err');
      return;
    }
    window.speechSynthesis.cancel();
    var utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = 'ja-JP';
    utterance.rate = 0.85;
    window.speechSynthesis.speak(utterance);
  }

  var marking = false;

  async function mark(quality) {
    var c = cards[index];
    if (!c || sessionDone || marking) return;
    if (studyMode === 'BROWSE') return;
    if (!(window.JlasAuth && JlasAuth.isLoggedIn && JlasAuth.isLoggedIn())) {
      FlashShell.showToast('Đăng nhập để lưu tiến độ', 'err');
      reviewed[c.id] = true;
      if (recall) recallAnswer(quality); else afterMark();
      return;
    }
    marking = true;
    try {
      await FlashApi.post('/api/study/review', { setId: setId, cardId: c.id, quality: quality });
      reviewed[c.id] = true;
      if (recall) {
        recallAnswer(quality);
      } else {
        afterMark();
      }
    } catch (e) {
      FlashShell.showToast(e.message || 'Không lưu được', 'err');
    } finally {
      marking = false;
    }
  }

  /* ── Ghi nhớ: lặp thẻ trong phiên tới khi mọi thẻ đủ số lần chấm 5 ───
   * Thẻ mới cần 2 lần chấm 5. Chấm dưới 5 cộng thêm số lần cần (trần 3, riêng mức 0 trần 4)
   * và cho thẻ quay lại sau vài thẻ; điểm càng thấp thẻ càng quay lại sớm.
   * Mỗi lúc chỉ học một nhóm tối đa RECALL_GROUP thẻ; khoảng quay lại tính theo tỉ lệ
   * số thẻ còn lại trong nhóm (kẹp trong [min, max]) nên hợp với cả bộ mỏng lẫn bộ dày. */
  var RECALL_START_NEED = 2;
  var RECALL_GROUP = 12;
  var RECALL_GAP_RATIO = [0.15, 0.25, 0.35, 0.5, 0.75];
  var RECALL_GAP_MIN = [1, 2, 2, 3, 3];
  var RECALL_GAP_MAX = [4, 6, 9, Infinity, Infinity];
  var RECALL_ADD = [3, 2, 2, 1, 1];

  function recallKey() {
    return 'jlab.flashcard.recall.' + setId + '.' + mode + (recallOnly ? '.' + recallOnly.join('-') : '');
  }

  function sortedIds(ids) {
    return ids.slice().sort(function (a, b) { return a - b; }).join(',');
  }

  function initRecall() {
    var ids = cards.map(function (card) { return card.id; });
    var saved = readRecall(ids);
    if (saved) {
      recall = saved;
      recall.pending = recall.pending || [];
      // Bản lưu cũ chỉ có mốc bắt đầu, không biết thời gian thực học: giữ tiến độ,
      // nhưng không cộng khoảng nghỉ vào phiên đang tiếp tục.
      studyClock.reset(saved.elapsedMs);
      delete recall.startedAt;
      flipCount = saved.flipCount || 0;
    } else {
      recall = {
        ids: ids,
        queue: ids.slice(0, RECALL_GROUP),
        pending: ids.slice(RECALL_GROUP),
        need: {},
        attempts: {},
        gained: 0
      };
      ids.forEach(function (id) {
        recall.need[id] = RECALL_START_NEED;
        recall.attempts[id] = [];
      });
    }
    index = cardIndex(recall.queue[0]);
  }

  function readRecall(ids) {
    try {
      var saved = JSON.parse(localStorage.getItem(recallKey()) || 'null');
      if (saved && saved.queue && saved.queue.length && sortedIds(saved.ids || []) === sortedIds(ids)) return saved;
    } catch (e) {
      // Bản lưu hỏng hoặc trình duyệt chặn storage: bắt đầu phiên mới.
    }
    return null;
  }

  function saveRecall() {
    if (!recall || sessionDone) return;
    try {
      localStorage.setItem(recallKey(), JSON.stringify(Object.assign({}, recall, {
        elapsedMs: studyClock.elapsedMs(),
        flipCount: flipCount
      })));
    } catch (e) {
      // Không lưu được thì chỉ mất khả năng học tiếp sau khi tải lại trang.
    }
  }

  function clearRecall() {
    try { localStorage.removeItem(recallKey()); } catch (e) { /* bỏ qua */ }
  }

  function cardIndex(id) {
    for (var i = 0; i < cards.length; i++) {
      if (cards[i].id === id) return i;
    }
    return 0;
  }

  function recallAnswer(quality) {
    var id = recall.queue.shift();
    recall.attempts[id].push(quality);
    if (quality >= 5) {
      recall.need[id] -= 1;
      recall.gained += 1;
      if (recall.need[id] > 0) {
        recall.queue.push(id);
      } else if (recall.pending.length) {
        // Thẻ vừa thuộc rời nhóm: thẻ mới kế tiếp vào cuối hàng.
        recall.queue.push(recall.pending.shift());
      }
    } else {
      var need = recall.need[id];
      var cap = quality === 0 ? 4 : 3;
      recall.need[id] = Math.max(need, Math.min(need + RECALL_ADD[quality], cap));
      recall.queue.splice(recallGap(quality, recall.queue.length), 0, id);
    }
    if (!recall.queue.length) {
      clearRecall();
      showComplete();
      return;
    }
    saveRecall();
    index = cardIndex(recall.queue[0]);
    renderCard();
  }

  /** Số thẻ chen giữa trước khi thẻ vừa chấm quay lại; others = số thẻ khác đang trong nhóm. */
  function recallGap(quality, others) {
    var gap = Math.round(RECALL_GAP_RATIO[quality] * others);
    gap = Math.min(Math.max(gap, RECALL_GAP_MIN[quality]), RECALL_GAP_MAX[quality]);
    return Math.min(gap, others);
  }

  /** Vẽ dòng trạng thái của thẻ đang hiện và trả về % tiến độ phiên. */
  function paintRecallStatus(cardId) {
    var remaining = recall.queue.reduce(function (sum, id) { return sum + recall.need[id]; }, 0)
      + recall.pending.length * RECALL_START_NEED;
    var total = recall.gained + remaining;
    var need = recall.need[cardId] || 0;
    var needEl = document.getElementById('recall-need');
    needEl.replaceChildren();
    for (var i = 0; i < need; i++) {
      var dot = document.createElement('i');
      dot.className = 'recall-status__dot';
      dot.setAttribute('aria-hidden', 'true');
      needEl.appendChild(dot);
    }
    needEl.appendChild(document.createTextNode('Cần chấm 5 thêm ' + need + ' lần'));
    var left = recall.queue.length + recall.pending.length;
    var meta = 'Còn ' + left + '/' + recall.ids.length + ' thẻ chưa thuộc';
    if (recall.pending.length) {
      meta += ' · Nhóm đang học: ' + recall.queue.length + ' thẻ, ' + recall.pending.length + ' thẻ chưa vào nhóm';
    }
    document.getElementById('recall-meta').textContent = meta + ' · ' + recall.gained + '/' + total + ' lần nhớ';
    document.getElementById('recall-status').hidden = false;
    return total ? Math.round((recall.gained * 100) / total) : 0;
  }

  function recallHardIds() {
    return recall.ids.filter(function (id) { return (recall.attempts[id] || []).length >= 3; });
  }

  function paintRecallSummary(studyTime) {
    var ids = recall.ids;
    var total = ids.length;
    var answers = ids.reduce(function (sum, id) { return sum + recall.attempts[id].length; }, 0);
    var knownFirst = ids.filter(function (id) { return recall.attempts[id][0] >= 3; }).length;
    var hard = recallHardIds();
    var knownPct = total ? Math.round((knownFirst * 100) / total) : 0;

    document.getElementById('progress-pct').textContent = '100%';
    document.getElementById('progress-ring').style.setProperty('--pct', '100');
    document.getElementById('complete-progress-title').textContent = 'Kết quả ghi nhớ';
    document.getElementById('stat-mastered-label').textContent = 'Đã thuộc';
    document.getElementById('stat-mastered').textContent = total;
    document.getElementById('stat-learning-label').textContent = 'Biết ngay lần đầu';
    document.getElementById('stat-learning').textContent = knownFirst;
    document.getElementById('stat-new-label').textContent = 'Phải lặp từ 3 lần';
    document.getElementById('stat-new').textContent = hard.length;
    document.getElementById('ss-total').textContent = total;
    document.getElementById('ss-pct-label').textContent = 'Nhớ ngay lần đầu';
    document.getElementById('ss-pct').textContent = knownPct + '%';
    document.getElementById('ss-time').textContent = formatTime(studyTime);
    document.getElementById('ss-flips-label').textContent = 'Lượt chấm';
    document.getElementById('ss-flips').textContent = answers + ' lần';

    var list = document.getElementById('recall-hard-list');
    list.replaceChildren();
    ids.slice()
      .sort(function (a, b) { return recall.attempts[b].length - recall.attempts[a].length; })
      .filter(function (id) { return recall.attempts[id].length >= 3; })
      .slice(0, 5)
      .forEach(function (id) {
        var card = cards[cardIndex(id)];
        var item = document.createElement('li');
        var word = document.createElement('strong');
        word.lang = 'ja';
        word.textContent = cardFace(card, faceFields.front, ' · ');
        var history = document.createElement('span');
        history.textContent = recall.attempts[id].join(' → ');
        item.append(word, history);
        list.appendChild(item);
      });
    document.getElementById('recall-hard').hidden = !list.children.length;

    var btnHard = document.getElementById('btn-recall-hard');
    btnHard.hidden = !hard.length;
    document.getElementById('btn-recall-hard-note').textContent = hard.length + ' thẻ phải lặp từ 3 lần trở lên';
    // Phiên Ghi nhớ kết thúc khi mọi thẻ đã thuộc nên không còn "thẻ chưa nhớ" để lọc.
    document.getElementById('btn-study-learning').hidden = true;
    updateQuizAvailability();
    paintQuote(knownPct);
  }

  async function markBrowse(rememberedValue) {
    var c = cards[index];
    if (!c || sessionDone || marking || !trackProgress || studyMode !== 'BROWSE') return;
    if (!(window.JlasAuth && JlasAuth.isLoggedIn && JlasAuth.isLoggedIn())) {
      FlashShell.showToast('Đăng nhập để lưu kết quả học', 'err');
      browseResults[c.id] = rememberedValue ? 'REMEMBERED' : 'NOT_REMEMBERED';
      paintFocus();
      return;
    }
    marking = true;
    try {
      await FlashApi.post('/api/study/review', {
        setId: setId,
        cardId: c.id,
        quality: rememberedValue ? 5 : 0
      });
      browseResults[c.id] = rememberedValue ? 'REMEMBERED' : 'NOT_REMEMBERED';
      afterMark();
    } catch (e) {
      FlashShell.showToast(e.message || 'Không lưu được kết quả', 'err');
    } finally {
      marking = false;
    }
  }

  function afterMark() {
    var marked = studyMode === 'BROWSE' ? browseResults : reviewed;
    var doneCount = Object.keys(marked).length;

    if (doneCount >= cards.length) {
      showComplete();
      return;
    }
    // Next unreviewed card if possible
    for (var i = 1; i <= cards.length; i++) {
      var ni = (index + i) % cards.length;
      if (!marked[cards[ni].id]) {
        index = ni;
        renderCard();
        return;
      }
    }
    go(1);
  }

  async function showComplete() {
    sessionDone = true;
    studyClock.pause();
    exitFocus();
    stopElapsedTimer();
    document.getElementById('study-stage').hidden = true;
    document.getElementById('study-actions').hidden = true;
    document.getElementById('study-card-list').hidden = true;
    document.getElementById('study-mode-nav').hidden = true;
    document.getElementById('study-session-progress').hidden = true;
    document.getElementById('study-complete').hidden = false;
    requestAnimationFrame(function () {
      document.getElementById('study-complete').scrollIntoView({ block: 'start', behavior: 'auto' });
    });

    document.getElementById('recall-status').hidden = true;
    document.getElementById('recall-hard').hidden = true;
    document.getElementById('btn-recall-hard').hidden = true;

    var studyTime = Math.max(1, Math.round(studyClock.elapsedMs() / 1000));
    if (recall) {
      // Kết quả Ghi nhớ tính từ chính phiên vừa học; vẫn báo server để đếm lượt học bộ thẻ.
      paintRecallSummary(studyTime);
      FlashApi.post('/api/study/' + setId + '/complete', { studyTime: studyTime, flipCount: flipCount })
        .catch(function () { /* khách hoặc mất mạng: kết quả đã hiện đủ */ });
      return;
    }
    try {
      await FlashApi.post('/api/study/' + setId + '/complete', {
        studyTime: studyTime,
        flipCount: flipCount
      });
      var summary = await FlashApi.get(
        '/api/study/' + setId + '/summary?studyTime=' + studyTime + '&flipCount=' + flipCount
      );
      // API tóm tắt không trả thời gian học: dùng thời gian của phiên này.
      summary.studyTime = summary.studyTime || studyTime;
      paintSummary(summary, studyMode === 'BROWSE');
    } catch (e) {
      // Guest fallback — local numbers
      paintSummary({
        totalCards: cards.length,
        mastered: Object.keys(reviewed).length,
        learning: 0,
        newCards: 0,
        progress: 100,
        studyTime: studyTime,
        flipCount: flipCount
      }, studyMode === 'BROWSE');
    }
  }

  function startElapsedTimer() {
    stopElapsedTimer();
    syncStudyClock();
    var ticks = 0;
    elapsedTimer = setInterval(function () {
      updateElapsed();
      // Có checkpoint ngắn để vẫn giữ thời gian khi trình duyệt đóng đột ngột.
      if (++ticks % 5 === 0 && !document.hidden) saveRecall();
    }, 1000);
  }

  function stopElapsedTimer() {
    if (elapsedTimer) clearInterval(elapsedTimer);
    elapsedTimer = null;
  }

  function updateElapsed() {
    var elapsed = Math.floor(studyClock.elapsedMs() / 1000);
    var minutes = String(Math.floor(elapsed / 60)).padStart(2, '0');
    var seconds = String(elapsed % 60).padStart(2, '0');
    var label = document.getElementById('study-elapsed');
    if (label) label.textContent = minutes + ':' + seconds;
  }

  function paintSummary(s, isBrowse) {
    var pct = s.progress != null ? s.progress : 0;
    var mastered = s.mastered != null ? s.mastered : 0;
    var learning = s.learning != null ? s.learning : 0;
    var newCards = s.newCards != null ? s.newCards : 0;
    var total = s.totalCards != null ? s.totalCards : cards.length;
    if (isBrowse) {
      total = cards.length;
      mastered = Object.keys(browseResults).filter(function (id) { return browseResults[id] === 'REMEMBERED'; }).length;
      learning = Object.keys(browseResults).filter(function (id) { return browseResults[id] === 'NOT_REMEMBERED'; }).length;
      newCards = Math.max(0, total - mastered - learning);
      pct = total ? Math.round((mastered * 100) / total) : 0;
    }
    // Thẻ chưa thuộc = đang học + chưa học (NEW)
    var notMastered = learning + newCards;

    document.getElementById('progress-pct').textContent = pct + '%';
    document.getElementById('progress-ring').style.setProperty('--pct', String(pct));
    document.getElementById('stat-mastered').textContent = mastered;
    document.getElementById('stat-learning').textContent = learning;
    document.getElementById('stat-new').textContent = newCards;
    document.getElementById('complete-progress-title').textContent = isBrowse ? 'Kết quả phiên lướt' : 'Tiến độ';
    document.getElementById('stat-mastered-label').textContent = isBrowse ? 'Đã nhớ' : 'Đã biết';
    document.getElementById('stat-learning-label').textContent = isBrowse ? 'Chưa thuộc' : 'Đang học';
    document.getElementById('stat-new-label').textContent = isBrowse ? 'Chưa chọn' : 'Còn lại';
    document.getElementById('ss-total').textContent = total;
    document.getElementById('ss-pct').textContent = pct + '%';
    document.getElementById('ss-pct-label').textContent = isBrowse ? 'Tỷ lệ đã nhớ' : 'Tỷ lệ hoàn thành';
    document.getElementById('ss-time').textContent = formatTime(s.studyTime || 0);
    document.getElementById('ss-flips').textContent = (s.flipCount || flipCount) + ' lần';
    document.getElementById('ss-flips-label').textContent = isBrowse ? 'Lật thẻ' : 'Lật thẻ';
    if (isBrowse && !trackProgress) {
      // Lướt không theo dõi tiến độ: chỉ báo đã xem bao nhiêu thẻ.
      var seen = Object.keys(browseVisited).length;
      var seenPct = total ? Math.round((seen * 100) / total) : 0;
      document.getElementById('progress-pct').textContent = seenPct + '%';
      document.getElementById('progress-ring').style.setProperty('--pct', String(seenPct));
      document.getElementById('stat-mastered-label').textContent = 'Đã xem';
      document.getElementById('stat-mastered').textContent = seen;
      document.getElementById('stat-learning-label').textContent = 'Chưa xem';
      document.getElementById('stat-learning').textContent = Math.max(0, total - seen);
      document.getElementById('stat-new-label').textContent = 'Lượt lật thẻ';
      document.getElementById('stat-new').textContent = flipCount;
      document.getElementById('ss-pct-label').textContent = 'Tỷ lệ đã xem';
      document.getElementById('ss-pct').textContent = seenPct + '%';
      notMastered = 0;
      pct = seenPct;
    }

    // Chỉ hiện khi còn thẻ chưa thuộc / chưa học
    var btnStudyLearning = document.getElementById('btn-study-learning');
    if (btnStudyLearning) {
      btnStudyLearning.hidden = notMastered < 1;
    }
    updateQuizAvailability();

    paintQuote(pct);
  }

  async function loadSetDetail() {
    try {
      var set = await FlashApi.get('/api/flashcards/' + setId);
      setDetail = set;
      totalSetCards = Number(set.totalCards != null
        ? set.totalCards
        : (set.cards || []).length);
      renderSetDetail(set);
      updateQuizAvailability();
    } catch (e) {
      // The quiz bridge remains the source of truth if the metadata request fails.
    }
  }

  /** Heart toggle next to the rating; the set then shows in the library's "Yêu thích" tab. */
  function renderFavorite(set) {
    var button = document.getElementById('btn-favorite-set');
    if (!button || set.draft || !set.id) return;
    paintFavorite(button, Boolean(set.favorited));
    button.hidden = false;
    if (button.dataset.bound) return;
    button.dataset.bound = '1';
    button.addEventListener('click', async function () {
      button.disabled = true;
      try {
        var result = await FlashApi.post('/api/flashcards/' + set.id + '/favorite', {});
        var favorited = Boolean(result && result.favorited);
        paintFavorite(button, favorited);
        FlashShell.showToast(favorited ? 'Đã thêm vào Yêu thích' : 'Đã bỏ khỏi Yêu thích', 'ok');
      } catch (e) {
        FlashShell.showToast(e.message || 'Không cập nhật được Yêu thích', 'err');
      } finally {
        button.disabled = false;
      }
    });
  }

  function paintFavorite(button, favorited) {
    button.setAttribute('aria-pressed', String(favorited));
    button.classList.toggle('is-favorite', favorited);
    button.title = favorited ? 'Bỏ yêu thích' : 'Thêm vào Yêu thích';
    button.querySelector('i').className = (favorited ? 'fa-solid' : 'fa-regular') + ' fa-heart';
    button.querySelector('span').textContent = favorited ? 'Đã yêu thích' : 'Yêu thích';
  }

  function renderSetRating(set) {
    var ratingCount = Number(set.ratingCount || 0);
    document.getElementById('study-set-rating-value').textContent = ratingCount
      ? Number(set.avgRating || 0).toFixed(1)
      : 'Chưa có';
    document.getElementById('study-set-rating-count').textContent = '(' + ratingCount + ' đánh giá)';
  }

  function renderSetDetail(set) {
    var summary = document.getElementById('study-set-summary');
    var list = document.getElementById('study-card-list');
    if (!set) return;
    summary.hidden = false;
    document.getElementById('study-set-title').textContent = set.title || 'Bộ Flashcard';
    document.getElementById('study-set-study-count').textContent = Number(set.studyCount || 0).toLocaleString('vi-VN');
    renderSetRating(set);

    var ratingButton = document.getElementById('btn-open-rating');
    var canRate = !set.ownedByMe;
    ratingButton.disabled = !canRate;
    ratingButton.title = canRate ? 'Đánh giá bộ thẻ' : 'Bạn không thể đánh giá bộ thẻ của mình';
    renderFavorite(set);
    renderStudyCardList(set.cards || []);
    list.hidden = false;
  }

  function renderStudyCardList(setCards) {
    var container = document.getElementById('study-card-list-items');
    var count = document.getElementById('study-card-list-count');
    var columns = [
      { label: 'Kanji', className: 'study-card-list__kanji', value: function (card) { return String(card.front || '').trim() || String(card.kanji || '').trim(); } },
      { label: 'Hiragana', className: 'study-card-list__hiragana', value: function (card) { return String(card.hiragana || '').trim() || String(card.reading || '').trim(); } },
      { label: 'Hán Việt', className: 'study-card-list__han-viet', value: function (card) { return String(card.hanViet || '').trim(); } },
      { label: 'Nghĩa', className: 'study-card-list__meaning', value: function (card) { return String(card.meaning || '').trim() || String(card.back || '').trim(); } }
    ].filter(function (column) {
      return setCards.some(function (card) { return column.value(card); });
    });
    var table = container.closest('table');
    table.dataset.contentColumns = String(columns.length);
    var headingRow = table.tHead.rows[0];
    var indexHeading = document.createElement('th');
    indexHeading.scope = 'col';
    indexHeading.className = 'study-card-list__index-heading';
    indexHeading.textContent = '#';
    headingRow.replaceChildren(indexHeading);
    columns.forEach(function (column) {
      var heading = document.createElement('th');
      heading.scope = 'col';
      heading.textContent = column.label;
      headingRow.appendChild(heading);
    });
    container.replaceChildren();
    count.textContent = setCards.length + ' thẻ';
    setCards.forEach(function (card, cardIndex) {
      var item = document.createElement('tr');
      item.dataset.cardPage = String(Math.floor(cardIndex / CARD_LIST_PAGE) + 1);
      var number = document.createElement('th');
      number.scope = 'row';
      number.className = 'study-card-list__number';
      number.textContent = String(cardIndex + 1);
      item.appendChild(number);
      columns.forEach(function (column) {
        var cell = document.createElement('td');
        cell.className = column.className;
        cell.textContent = column.value(card) || '—';
        item.appendChild(cell);
      });
      container.appendChild(item);
    });
    cardListPages = Math.max(1, Math.ceil(setCards.length / CARD_LIST_PAGE));
    cardListTotal = setCards.length;
    document.getElementById('study-card-list-pager').hidden = cardListPages < 2;
    showCardListPage(1, 0);
  }

  // ---- Card list paging: 10 rows a page, the new page slides in from the side the learner moved to. ----
  var CARD_LIST_PAGE = 10;
  var cardListPage = 1, cardListPages = 1, cardListTotal = 0;

  function showCardListPage(page, direction) {
    cardListPage = Math.max(1, Math.min(cardListPages, page));
    var body = document.getElementById('study-card-list-items');
    Array.prototype.forEach.call(body.rows, function (row) { row.hidden = Number(row.dataset.cardPage) !== cardListPage; });
    var first = (cardListPage - 1) * CARD_LIST_PAGE + 1, last = Math.min(cardListTotal, cardListPage * CARD_LIST_PAGE);
    document.getElementById('study-cards-page').textContent = 'Trang ' + cardListPage + '/' + cardListPages + ' · thẻ ' + first + '–' + last;
    document.getElementById('study-cards-prev').disabled = cardListPage === 1;
    document.getElementById('study-cards-next').disabled = cardListPage === cardListPages;
    if (direction) {
      body.classList.remove('is-slide-next', 'is-slide-prev');
      void body.offsetWidth; // restart the animation on every turn
      body.classList.add(direction > 0 ? 'is-slide-next' : 'is-slide-prev');
    }
  }

  function turnCardListPage(step) {
    if (cardListPage + step < 1 || cardListPage + step > cardListPages) return;
    showCardListPage(cardListPage + step, step);
  }

  function bindCardListPager() {
    document.getElementById('study-cards-prev').addEventListener('click', function () { turnCardListPage(-1); });
    document.getElementById('study-cards-next').addEventListener('click', function () { turnCardListPage(1); });
    document.querySelector('.study-card-list__table-wrap').addEventListener('keydown', function (event) {
      if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
        event.preventDefault();
        turnCardListPage(event.key === 'ArrowRight' ? 1 : -1);
      }
    });
  }

  function openRating() {
    if (!setDetail || setDetail.ownedByMe) return;
    if (!FlashShell.requireLogin('Vui lòng đăng nhập để đánh giá bộ thẻ')) return;
    pendingRating = Number(setDetail.myRating || 0);
    renderRatingStars();
    document.getElementById('btn-save-rating').disabled = pendingRating < 1;
    document.getElementById('rating-modal').hidden = false;
  }

  function closeRating() {
    document.getElementById('rating-modal').hidden = true;
  }

  function renderRatingStars() {
    var container = document.getElementById('study-rating-stars');
    container.replaceChildren();
    for (var star = 1; star <= 5; star++) {
      (function (value) {
        var button = document.createElement('button');
        button.type = 'button';
        button.className = 'study-rating-star' + (value <= pendingRating ? ' is-selected' : '');
        button.setAttribute('role', 'radio');
        button.setAttribute('aria-checked', value === pendingRating ? 'true' : 'false');
        button.setAttribute('aria-label', value + ' sao');
        button.innerHTML = '<i class="fa-' + (value <= pendingRating ? 'solid' : 'regular') + ' fa-star" aria-hidden="true"></i>';
        button.addEventListener('click', function () {
          pendingRating = value;
          document.getElementById('btn-save-rating').disabled = false;
          renderRatingStars();
        });
        container.appendChild(button);
      })(star);
    }
  }

  async function saveRating() {
    if (pendingRating < 1) return;
    var button = document.getElementById('btn-save-rating');
    button.disabled = true;
    try {
      var ratingSummary = await FlashApi.post('/api/flashcards/' + setId + '/rating', { rating: pendingRating });
      setDetail = Object.assign({}, setDetail, ratingSummary);
      renderSetRating(setDetail);
      closeRating();
      FlashShell.showToast('Đã gửi đánh giá ' + pendingRating + '/5 sao', 'ok');
    } catch (e) {
      button.disabled = false;
      FlashShell.showToast(e.message || 'Không thể gửi đánh giá', 'err');
    }
  }

  function updateQuizAvailability() {
    var btnQuiz = document.getElementById('btn-quiz-learning');
    if (!btnQuiz) return;
    // Do not use the current study session length: LEARNING mode can contain
    // only a subset of a larger Flashcard set.
    var total = totalSetCards == null ? null : totalSetCards;
    btnQuiz.hidden = total != null && total < 4;
    var small = btnQuiz.querySelector('small');
    if (small) {
      small.textContent = total == null
        ? 'Kiểm tra toàn bộ thẻ trong bộ'
        : (total >= 4 ? ('Toàn bộ thẻ trong bộ · ' + total + ' thẻ') : 'Cần ít nhất 4 thẻ trong bộ');
    }
  }

  /* ── Danh ngôn / tục ngữ JP theo % tiến độ ───────────────── */
  var QUOTE_POOLS = [
    {
      min: 0, max: 24,
      title: 'Khởi đầu · Tích lũy',
      quotes: [
        { ja: '千里の道も一歩から', furi: 'せんりのみちもいっぽから', vi: 'Đường ngàn dặm cũng bắt đầu từ một bước.' },
        { ja: '塵も積もれば山となる', furi: 'ちりもつもればやまとなる', vi: 'Bụi chất lại cũng thành núi.' },
        { ja: '学問の始めは一歩から', furi: 'がくもんのはじめはいっぽから', vi: 'Học vấn bắt đầu từ một bước.' },
        { ja: '小さなことから始めよ', furi: 'ちいさなことからはじめよ', vi: 'Hãy bắt đầu từ những việc nhỏ.' },
        { ja: '継続は力なり', furi: 'けいぞくはちからなり', vi: 'Kiên trì là sức mạnh.' }
      ]
    },
    {
      min: 25, max: 49,
      title: 'Kiên trì · Không bỏ cuộc',
      quotes: [
        { ja: '雨垂れ石を穿つ', furi: 'あまだれいしをうがつ', vi: 'Giọt mưa cũng đục thủng đá.' },
        { ja: '石の上にも三年', furi: 'いしのうえにもさんねん', vi: 'Ngồi trên đá ba năm cũng ấm — kiên trì ắt thành.' },
        { ja: '継続は力なり', furi: 'けいぞくはちからなり', vi: 'Kiên trì là sức mạnh.' },
        { ja: '急がば回れ', furi: 'いそがばまわれ', vi: 'Muốn nhanh thì đi đường vòng.' },
        { ja: '為せば成る', furi: 'なせばなる', vi: 'Làm thì sẽ thành.' }
      ]
    },
    {
      min: 50, max: 74,
      title: 'Vượt khó · Nỗ lực',
      quotes: [
        { ja: '七転び八起き', furi: 'ななころびやおき', vi: 'Ngã bảy lần, đứng dậy tám lần.' },
        { ja: '失敗は成功のもと', furi: 'しっぱいはせいこうのもと', vi: 'Thất bại là mẹ thành công.' },
        { ja: '虎穴に入らずんば虎子を得ず', furi: 'こけつにいらずんばこじをえず', vi: 'Không vào hang hổ, sao bắt được hổ con.' },
        { ja: '為せば成る', furi: 'なせばなる', vi: 'Làm thì sẽ thành.' },
        { ja: '雨降って地固まる', furi: 'あめふってじかたまる', vi: 'Mưa qua đất mới vững.' }
      ]
    },
    {
      min: 75, max: 99,
      title: 'Bền bỉ · Khiêm tốn',
      quotes: [
        { ja: '習うより慣れろ', furi: 'ならうよりなれろ', vi: 'Thực hành hơn học suông.' },
        { ja: '継続は力なり', furi: 'けいぞくはちからなり', vi: 'Kiên trì là sức mạnh.' },
        { ja: '石の上にも三年', furi: 'いしのうえにもさんねん', vi: 'Ngồi trên đá ba năm cũng ấm — kiên trì ắt thành.' },
        { ja: '実るほど頭を垂れる稲穂かな', furi: 'みのるほどこうべをたれるいなほかな', vi: 'Lúa chín càng cúi đầu — càng giỏi càng khiêm tốn.' },
        { ja: '一念岩をも通す', furi: 'いちねんいわをもとおす', vi: 'Một niệm có thể xuyên đá.' }
      ]
    },
    {
      min: 100, max: 100,
      title: 'Tri thức · Học suốt đời',
      quotes: [
        { ja: '学問に王道なし', furi: 'がくもんにおうどうなし', vi: 'Học vấn không có đường tắt.' },
        { ja: '初心忘るべからず', furi: 'しょしんわするべからず', vi: 'Không được quên sơ tâm.' },
        { ja: '知れば知るほど知らぬことを知る', furi: 'しればしるほどしらぬことをしる', vi: 'Càng biết càng thấy mình chưa biết.' },
        { ja: '温故知新', furi: 'おんこちしん', vi: 'Ôn cũ biết mới.' },
        { ja: '学びて時にこれを習う、また説ばしからずや', furi: 'まなびてときにこれをならう、またよろこばしからずや', vi: 'Học rồi ôn đúng lúc — há chẳng vui sao?' }
      ]
    }
  ];

  function pickQuotePool(pct) {
    pct = Math.max(0, Math.min(100, Number(pct) || 0));
    for (var i = 0; i < QUOTE_POOLS.length; i++) {
      var p = QUOTE_POOLS[i];
      if (pct >= p.min && pct <= p.max) return p;
    }
    return QUOTE_POOLS[0];
  }

  function paintQuote(pct) {
    var pool = pickQuotePool(pct);
    var list = pool.quotes || [];
    if (!list.length) return;
    var q = list[Math.floor(Math.random() * list.length)];
    document.getElementById('quote-title').textContent = pool.title;
    document.getElementById('quote-ja').textContent = q.ja;
    document.getElementById('quote-furi').textContent = '（' + q.furi + '）';
    document.getElementById('quote-vi').textContent = '"' + q.vi + '"';
  }

  function formatTime(sec) {
    sec = Number(sec) || 0;
    if (sec < 60) return sec + ' giây';
    var m = Math.floor(sec / 60);
    var r = sec % 60;
    return r ? (m + ' phút ' + r + ' giây') : (m + ' phút');
  }

  function normalizeStudyMode(value) {
    return String(value).toUpperCase() === 'BROWSE' ? 'BROWSE' : 'RECALL';
  }

  function updateStudyModeUi() {
    if (swipeController) swipeController.cancel();
    var isBrowse = studyMode === 'BROWSE';
    document.querySelectorAll('[data-study-mode]').forEach(function (button) {
      button.classList.toggle('is-active', button.getAttribute('data-study-mode') === studyMode);
    });
    var quality = document.getElementById('study-quality-actions');
    var browse = document.getElementById('study-browse-actions');
    var help = document.getElementById('quality-help');
    if (quality) quality.hidden = isBrowse;
    if (browse) browse.hidden = !isBrowse;
    // Ghi nhớ đi theo hàng đợi nên ẩn mũi tên trước/sau (giữ chỗ để thẻ không bị lệch).
    ['btn-prev', 'btn-next'].forEach(function (id) {
      var button = document.getElementById(id);
      if (button) button.style.visibility = isBrowse ? '' : 'hidden';
    });
    var recallStatus = document.getElementById('recall-status');
    if (recallStatus && isBrowse) recallStatus.hidden = true;
    // Công tắc theo dõi tiến độ chỉ dành cho Lướt thẻ; Ghi nhớ luôn cần chấm điểm.
    document.getElementById('study-track-toggle').hidden = !isBrowse;
    var untracked = isBrowse && !trackProgress;
    if (browse) browse.classList.toggle('is-untracked', untracked);
    // Bật theo dõi: ✗ vị trí ✓. Tắt: ← vị trí → (chỉ lướt xem).
    document.querySelectorAll('[data-browse-result]').forEach(function (button) { button.hidden = untracked; });
    document.getElementById('btn-browse-prev').hidden = !untracked;
    document.getElementById('btn-browse-next').hidden = !untracked;
    syncSettingsListboxes();
  }

  function readTrackProgress() {
    try { return localStorage.getItem('jlab.flashcard.trackProgress') !== 'false'; } catch (e) { return true; }
  }

  /* ── Toàn màn hình: chỉ còn thẻ, bộ đếm hai bên và nút chấm, căn giữa kiểu Quizlet ── */
  function enterFocus() {
    if (sessionDone || !cards.length) return;
    focusActive = true;
    document.documentElement.classList.add('study-focus');
    paintFocusToggle();
    paintFocus();
    var root = document.documentElement;
    if (root.requestFullscreen && !document.fullscreenElement) {
      // Trình duyệt chặn fullscreen thì vẫn dùng lớp phủ toàn trang.
      root.requestFullscreen().catch(function () {});
    }
  }

  function exitFocus() {
    if (!focusActive) return;
    focusActive = false;
    document.documentElement.classList.remove('study-focus');
    paintFocusToggle();
    if (document.fullscreenElement && document.exitFullscreen) {
      document.exitFullscreen().catch(function () {});
    }
  }

  function paintFocusToggle() {
    var button = document.getElementById('btn-focus');
    var label = focusActive ? 'Thoát toàn màn hình' : 'Học toàn màn hình';
    button.title = label;
    button.setAttribute('aria-label', label);
    button.querySelector('i').className = 'fa-solid ' + (focusActive ? 'fa-compress' : 'fa-expand');
  }

  function paintFocus() {
    var left;
    var right;
    var position;
    if (recall) {
      left = recall.queue.length + recall.pending.length;
      right = recall.ids.length - left;
      position = right + ' / ' + recall.ids.length;
    } else {
      left = 0;
      right = 0;
      Object.keys(browseResults).forEach(function (id) {
        if (browseResults[id] === 'REMEMBERED') right++; else left++;
      });
      position = (index + 1) + ' / ' + cards.length;
    }
    document.getElementById('focus-position').textContent = position;
    document.getElementById('focus-title').textContent = sessionTitle;
    document.getElementById('focus-count-left').textContent = left;
    document.getElementById('focus-count-right').textContent = right;
    document.getElementById('focus-count-left-label').textContent = recall ? 'Đang học' : 'Chưa thuộc';
    document.getElementById('focus-count-right-label').textContent = recall ? 'Đã thuộc' : 'Đã nhớ';
    document.getElementById('study-focus-hint-mode').textContent = recall
      ? '· Phím 0–5 để chấm điểm'
      : '· Phím ← → để chuyển thẻ';
    // Không theo dõi tiến độ thì không có gì để đếm hai bên.
    document.getElementById('study-focus-counts').classList.toggle('is-off', !recall && !trackProgress);
  }

  function selectStudyMode(nextMode) {
    if (marking) return;
    nextMode = normalizeStudyMode(nextMode);
    if (nextMode === studyMode) return;
    recallOnly = null;
    studyMode = nextMode;
    localStorage.setItem('jlab.flashcard.studyMode', studyMode);
    updateStudyModeUi();
    if (setId) load();
  }

  function setupSettingsListboxes() {
    var modal = document.getElementById('settings-modal');
    if (!modal || settingsListboxes.length) return;

    modal.querySelectorAll('select.select').forEach(function (select) {
      var field = select.closest('.field');
      var label = field && field.querySelector('label');
      if (!field || !label) return;

      var labelId = label.id || (select.id + '-label');
      label.id = labelId;
      select.classList.add('study-select__native');
      select.setAttribute('aria-hidden', 'true');
      select.tabIndex = -1;

      var control = document.createElement('div');
      control.className = 'study-select';
      var trigger = document.createElement('button');
      trigger.type = 'button';
      trigger.className = 'study-select__trigger';
      trigger.setAttribute('aria-haspopup', 'listbox');
      trigger.setAttribute('aria-expanded', 'false');
      trigger.setAttribute('aria-labelledby', labelId);

      var listbox = document.createElement('div');
      listbox.className = 'study-select__listbox';
      listbox.id = select.id + '-listbox';
      listbox.setAttribute('role', 'listbox');
      listbox.setAttribute('aria-labelledby', labelId);
      listbox.hidden = true;
      trigger.setAttribute('aria-controls', listbox.id);

      function update() {
        var selected = select.options[select.selectedIndex];
        trigger.textContent = selected ? selected.text : '';
        Array.prototype.forEach.call(listbox.querySelectorAll('[role="option"]'), function (option) {
          var isSelected = option.dataset.value === select.value;
          option.classList.toggle('is-selected', isSelected);
          option.setAttribute('aria-selected', isSelected ? 'true' : 'false');
        });
      }

      function close() {
        listbox.hidden = true;
        trigger.setAttribute('aria-expanded', 'false');
      }

      function open(focusSelected) {
        settingsListboxes.forEach(function (controller) { controller.close(); });
        listbox.hidden = false;
        trigger.setAttribute('aria-expanded', 'true');
        if (focusSelected) {
          var selectedOption = listbox.querySelector('[aria-selected="true"]');
          if (selectedOption) selectedOption.focus();
        }
      }

      function choose(value, focusTrigger) {
        select.value = value;
        select.dispatchEvent(new Event('change', { bubbles: true }));
        update();
        close();
        if (focusTrigger) trigger.focus();
      }

      Array.prototype.forEach.call(select.options, function (nativeOption) {
        var option = document.createElement('button');
        option.type = 'button';
        option.className = 'study-select__option';
        option.setAttribute('role', 'option');
        option.dataset.value = nativeOption.value;
        option.textContent = nativeOption.text;
        option.addEventListener('click', function () { choose(nativeOption.value, true); });
        option.addEventListener('keydown', function (event) {
          var options = Array.prototype.slice.call(listbox.querySelectorAll('[role="option"]'));
          var at = options.indexOf(option);
          if (event.key === 'Escape') { event.preventDefault(); close(); trigger.focus(); }
          if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); choose(nativeOption.value, true); }
          if (event.key === 'ArrowDown' && options[at + 1]) { event.preventDefault(); options[at + 1].focus(); }
          if (event.key === 'ArrowUp' && options[at - 1]) { event.preventDefault(); options[at - 1].focus(); }
          if (event.key === 'Home' && options[0]) { event.preventDefault(); options[0].focus(); }
          if (event.key === 'End' && options.length) { event.preventDefault(); options[options.length - 1].focus(); }
        });
        listbox.appendChild(option);
      });

      trigger.addEventListener('click', function (event) {
        event.stopPropagation();
        if (listbox.hidden) open(false); else close();
      });
      trigger.addEventListener('keydown', function (event) {
        if (event.key === 'Enter' || event.key === ' ' || event.key === 'ArrowDown' || event.key === 'ArrowUp') {
          event.preventDefault();
          open(true);
        }
        if (event.key === 'Escape') close();
      });
      listbox.addEventListener('click', function (event) { event.stopPropagation(); });
      select.addEventListener('change', update);

      control.appendChild(trigger);
      control.appendChild(listbox);
      select.insertAdjacentElement('afterend', control);
      settingsListboxes.push({ close: close, sync: update, contains: function (target) { return control.contains(target); } });
      update();
    });

    document.addEventListener('click', function (event) {
      settingsListboxes.forEach(function (controller) {
        if (!controller.contains(event.target)) controller.close();
      });
    });
  }

  function syncSettingsListboxes() {
    settingsListboxes.forEach(function (controller) { controller.sync(); });
  }

  async function startQuiz(event) {
    var btn = event && event.currentTarget
      ? event.currentTarget
      : document.getElementById('btn-quiz-learning');
    // `cards` có thể chỉ là tập con của phiên học hiện tại (ví dụ chỉ các
    // thẻ chưa nhớ), nên không dùng nó để quyết định bộ có đủ thẻ quiz hay không.
    await FlashShell.openFlashcardQuiz(setId, { button: btn, onlyLearning: false });
  }

  function openSettings() {
    syncSettingsListboxes();
    faceDraft = { front: faceFields.front.slice(), back: faceFields.back.slice() };
    renderFacePicker();
    document.getElementById('settings-modal').hidden = false;
  }

  function readSavedFaceFields() {
    try {
      var saved = JSON.parse(localStorage.getItem(FACE_FIELDS_KEY) || 'null');
      if (saved && saved.front && saved.back) return { front: Faces.normalize(saved.front), back: Faces.normalize(saved.back) };
    } catch (e) { /* bỏ qua */ }
    return { front: Faces.DEFAULTS.front.slice(), back: Faces.DEFAULTS.back.slice() };
  }

  /* Checkbox chips for both faces: 1-3 fields each, never the same field on both, never a blank face. */
  function renderFacePicker() {
    if (!faceDraft) return;
    ['front', 'back'].forEach(function (face) {
      var other = face === 'front' ? 'back' : 'front';
      var host = document.querySelector('[data-face-picker="' + face + '"] .study-face-picker__options');
      host.replaceChildren();
      Faces.FIELDS.forEach(function (field) {
        var checked = faceDraft[face].indexOf(field.value) >= 0;
        var allowed = faceAllowed.indexOf(field.value) >= 0;
        var onOther = faceDraft[other].indexOf(field.value) >= 0;
        var full = !checked && faceDraft[face].length >= Faces.MAX_PER_FACE;
        var label = document.createElement('label');
        label.className = 'study-face-picker__option';
        var input = document.createElement('input');
        input.type = 'checkbox';
        input.value = field.value;
        input.checked = checked;
        input.disabled = !checked && (!allowed || onOther || full);
        input.addEventListener('change', function () {
          var list = faceDraft[face].filter(function (value) { return value !== field.value; });
          if (input.checked) list.push(field.value);
          faceDraft[face] = Faces.normalize(list);
          renderFacePicker();
        });
        var text = document.createElement('span');
        text.textContent = field.label;
        label.append(input, text);
        // Mark fields some cards lack: those cards simply show the face's other fields.
        var missing = allowed ? Faces.missingCount(cards, field.value) : 0;
        var hints = [];
        if (missing) {
          var badge = document.createElement('small');
          badge.className = 'study-face-picker__badge';
          badge.textContent = 'thiếu ' + missing;
          label.appendChild(badge);
          hints.push(missing + '/' + cards.length + ' thẻ chưa có ' + field.label
            + ' — các thẻ đó chỉ hiện những trường còn lại của mặt này');
        }
        if (!allowed) hints.push('Không thẻ nào trong bộ có ' + field.label);
        else if (onOther) hints.push('Đang hiển thị ở mặt ' + (other === 'front' ? 'trước' : 'sau'));
        else if (full) hints.push('Mỗi mặt chọn tối đa ' + Faces.MAX_PER_FACE + ' trường');
        if (hints.length) label.title = hints.join('. ');
        if (missing) input.setAttribute('aria-description', hints[0]);
        host.appendChild(label);
      });
    });
    var error = Faces.validate(faceDraft, faceAllowed, cards);
    var errorBox = document.getElementById('face-picker-error');
    errorBox.hidden = !error;
    errorBox.textContent = error;
    document.getElementById('btn-apply-settings').disabled = !!error;
  }
  function closeSettings() {
    document.getElementById('settings-modal').hidden = true;
  }
  function openReset() {
    document.getElementById('reset-modal').hidden = false;
  }
  function closeReset() {
    document.getElementById('reset-modal').hidden = true;
  }

  async function confirmReset() {
    if (!FlashShell.requireLogin('Đăng nhập để đặt lại tiến độ')) return;
    try {
      await FlashApi.post('/api/study/' + setId + '/reset', {});
      closeReset();
      FlashShell.showToast('Đã đặt lại tiến độ', 'ok');
      location.href = '/flashcard/study.html?setId=' + setId + '&mode=ALL';
    } catch (e) {
      FlashShell.showToast(e.message || 'Đặt lại thất bại', 'err');
    }
  }

  async function applySettings() {
    var draft = faceDraft || faceFields;
    var error = Faces.validate(draft, faceAllowed, cards);
    if (error) {
      FlashShell.showToast(error, 'err');
      return;
    }
    faceFields = { front: Faces.normalize(draft.front), back: Faces.normalize(draft.back) };
    try { localStorage.setItem(FACE_FIELDS_KEY, JSON.stringify(faceFields)); } catch (e) { /* bỏ qua */ }
    localStorage.setItem('jlab.flashcard.studyMode', studyMode);
    updateStudyModeUi();
    if (cards.length) renderCard();
    closeSettings();
    FlashShell.showToast('Đã áp dụng cài đặt', 'ok');
  }
})();
