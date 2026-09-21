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
  var startedAt = Date.now();
  var sessionDone = false;
  var elapsedTimer = null;
  var totalSetCards = null;
  var faceFields = { front: 'FRONT', back: 'BACK' };
  var setDetail = null;
  var pendingRating = 0;
  var settingsListboxes = [];

  document.addEventListener('DOMContentLoaded', function () {
    // Học thẻ bắt buộc đăng nhập (để đếm study_count + lưu tiến độ)
    if (!FlashShell.requireLogin('Vui lòng đăng nhập để học thẻ từ')) {
      return;
    }

    document.getElementById('btn-settings').addEventListener('click', openSettings);
    document.getElementById('btn-close-settings').addEventListener('click', closeSettings);
    document.getElementById('btn-cancel-settings').addEventListener('click', closeSettings);
    document.getElementById('btn-apply-settings').addEventListener('click', applySettings);
    document.getElementById('study-mode-field').value = studyMode;
    setupSettingsListboxes();
    updateStudyModeUi();
    document.querySelectorAll('[data-study-mode]').forEach(function (button) {
      button.addEventListener('click', function () { selectStudyMode(button.getAttribute('data-study-mode')); });
    });
    var btnHvFront = document.getElementById('btn-hv-front');
    if (btnHvFront) {
      btnHvFront.addEventListener('click', function () {
        document.getElementById('face-front-field').value = 'HAN_VIET';
        document.getElementById('face-back-field').value = 'BACK';
        syncSettingsListboxes();
        FlashShell.showToast('Đã chọn: Hán Việt ở Mặt trước', 'info');
      });
    }
    var btnHvBack = document.getElementById('btn-hv-back');
    if (btnHvBack) {
      btnHvBack.addEventListener('click', function () {
        document.getElementById('face-front-field').value = 'FRONT';
        document.getElementById('face-back-field').value = 'HAN_VIET';
        syncSettingsListboxes();
        FlashShell.showToast('Đã chọn: Hán Việt ở Mặt sau', 'info');
      });
    }
    document.getElementById('btn-prev').addEventListener('click', function () { go(-1); });
    document.getElementById('btn-next').addEventListener('click', function () { go(1); });
    document.getElementById('btn-flip').addEventListener('click', flip);
    document.getElementById('btn-browse-flip').addEventListener('click', flip);
    document.getElementById('flip-card').addEventListener('click', flip);
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
      if (sessionDone) return;
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
    totalSetCards = null;
    index = 0;
    flipped = false;
    reviewed = {};
    browseResults = {};
    browseVisited = {};
    visitedCards = {};
    flipCount = 0;
    startedAt = Date.now();
    sessionDone = false;
    updateStudyModeUi();
    startElapsedTimer();
    document.getElementById('study-title').textContent = session.title || 'Học thẻ';
    if (session.frontField) faceFields.front = session.frontField;
    if (session.backField) faceFields.back = session.backField;
    document.getElementById('face-front-field').value = faceFields.front;
    document.getElementById('face-back-field').value = faceFields.back;
    syncSettingsListboxes();

    document.getElementById('study-complete').hidden = true;
    if (!cards.length) {
      document.getElementById('study-empty').hidden = false;
      document.getElementById('study-stage').hidden = true;
      document.getElementById('study-actions').hidden = true;
      return;
    }
    document.getElementById('study-empty').hidden = true;
    document.getElementById('study-stage').hidden = false;
    document.getElementById('study-actions').hidden = false;
    renderCard();
  }

  function renderCard() {
    var c = cards[index];
    if (!c) return;
    visitedCards[c.id] = true;
    if (studyMode === 'BROWSE') browseVisited[c.id] = true;
    flipped = false;
    document.getElementById('flip-inner').classList.remove('is-flipped');
    // The canonical study API returns the card fields (front/back/hanViet),
    // not legacy-only displayFront/displayBack properties. Resolve them here
    // so an existing card never renders as a blank face.
    document.getElementById('face-front').textContent = cardFace(c, faceFields.front);
    document.getElementById('face-back').textContent = cardFace(c, faceFields.back);
    var img = document.getElementById('face-image');
    if (c.image) {
      img.src = c.image;
      img.hidden = false;
    } else {
      img.hidden = true;
      img.removeAttribute('src');
    }
    document.getElementById('study-progress').textContent = (index + 1) + ' / ' + cards.length;
    var progressFill = document.getElementById('study-progress-fill');
    var progressPercent = Math.round(((index + 1) / cards.length) * 100);
    if (progressFill) progressFill.style.width = progressPercent + '%';
    var progressLabel = document.getElementById('study-progress-percent');
    if (progressLabel) progressLabel.textContent = progressPercent + '%';
  }

  function cardFace(card, field) {
    if (!card) return '';
    var values = {
      FRONT: card.front || card.kanji,
      BACK: card.back || card.meaning || card.reading,
      HAN_VIET: card.hanViet,
      HIRAGANA: card.hiragana || card.reading,
      READING: card.reading || card.hiragana,
      MEANING: card.meaning || card.back
    };
    return values[String(field || '').toUpperCase()] || card.front || card.kanji || '';
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
    if (!cards.length || sessionDone) return;
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

  async function mark(quality) {
    var c = cards[index];
    if (!c || sessionDone) return;
    if (studyMode === 'BROWSE') return;
    if (!(window.JlasAuth && JlasAuth.isLoggedIn && JlasAuth.isLoggedIn())) {
      FlashShell.showToast('Đăng nhập để lưu tiến độ', 'err');
      reviewed[c.id] = true;
      afterMark();
      return;
    }
    try {
      await FlashApi.post('/api/study/review', { setId: setId, cardId: c.id, quality: quality });
      reviewed[c.id] = true;
      FlashShell.showToast('Đã lưu mức ' + quality + '/5', 'ok');
      afterMark();
    } catch (e) {
      FlashShell.showToast(e.message || 'Không lưu được', 'err');
    }
  }

  async function markBrowse(rememberedValue) {
    var c = cards[index];
    if (!c || sessionDone) return;
    if (!(window.JlasAuth && JlasAuth.isLoggedIn && JlasAuth.isLoggedIn())) {
      FlashShell.showToast('Đăng nhập để lưu kết quả học', 'err');
      browseResults[c.id] = rememberedValue ? 'REMEMBERED' : 'NOT_REMEMBERED';
      return;
    }
    try {
      await FlashApi.post('/api/study/review', {
        setId: setId,
        cardId: c.id,
        quality: rememberedValue ? 5 : 0
      });
      browseResults[c.id] = rememberedValue ? 'REMEMBERED' : 'NOT_REMEMBERED';
      FlashShell.showToast(rememberedValue ? 'Đã đánh dấu: Đã nhớ' : 'Đã đánh dấu: Chưa thuộc', 'ok');
    } catch (e) {
      FlashShell.showToast(e.message || 'Không lưu được kết quả', 'err');
    }
  }

  function afterMark() {
    var doneCount = Object.keys(reviewed).length;

    if (doneCount >= cards.length) {
      showComplete();
      return;
    }
    // Next unreviewed card if possible
    for (var i = 1; i <= cards.length; i++) {
      var ni = (index + i) % cards.length;
      if (!reviewed[cards[ni].id]) {
        index = ni;
        renderCard();
        return;
      }
    }
    go(1);
  }

  async function showComplete() {
    sessionDone = true;
    if (elapsedTimer) clearInterval(elapsedTimer);
    document.getElementById('study-stage').hidden = true;
    document.getElementById('study-actions').hidden = true;
    document.getElementById('study-complete').hidden = false;

    var studyTime = Math.max(1, Math.round((Date.now() - startedAt) / 1000));
    try {
      await FlashApi.post('/api/study/' + setId + '/complete', {
        studyTime: studyTime,
        flipCount: flipCount
      });
      var summary = await FlashApi.get(
        '/api/study/' + setId + '/summary?studyTime=' + studyTime + '&flipCount=' + flipCount
      );
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
    if (elapsedTimer) clearInterval(elapsedTimer);
    updateElapsed();
    elapsedTimer = setInterval(updateElapsed, 1000);
  }

  function updateElapsed() {
    var elapsed = Math.max(0, Math.floor((Date.now() - startedAt) / 1000));
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
    document.getElementById('stat-new-label').textContent = isBrowse ? 'Chưa đánh dấu' : 'Còn lại';
    document.getElementById('ss-total').textContent = total;
    document.getElementById('ss-pct').textContent = pct + '%';
    document.getElementById('ss-pct-label').textContent = isBrowse ? 'Tỷ lệ đã nhớ' : 'Tỷ lệ hoàn thành';
    document.getElementById('ss-time').textContent = formatTime(s.studyTime || 0);
    document.getElementById('ss-flips').textContent = (s.flipCount || flipCount) + ' lần';
    document.getElementById('ss-flips-label').textContent = isBrowse ? 'Lật thẻ' : 'Lật thẻ';

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

  function renderSetDetail(set) {
    var summary = document.getElementById('study-set-summary');
    var list = document.getElementById('study-card-list');
    if (!set) return;
    summary.hidden = false;
    document.getElementById('study-set-title').textContent = set.title || 'Bộ Flashcard';
    document.getElementById('study-set-study-count').textContent = Number(set.studyCount || 0).toLocaleString('vi-VN');
    var ratingCount = Number(set.ratingCount || 0);
    document.getElementById('study-set-rating-value').textContent = ratingCount
      ? Number(set.avgRating || 0).toFixed(1)
      : 'Chưa có';
    document.getElementById('study-set-rating-count').textContent = '(' + ratingCount + ' đánh giá)';

    var ratingButton = document.getElementById('btn-open-rating');
    var canRate = !set.ownedByMe;
    ratingButton.disabled = !canRate;
    ratingButton.title = canRate ? 'Đánh giá bộ thẻ' : 'Bạn không thể đánh giá bộ thẻ của mình';
    renderStudyCardList(set.cards || []);
    list.hidden = false;
  }

  function renderStudyCardList(setCards) {
    var container = document.getElementById('study-card-list-items');
    var count = document.getElementById('study-card-list-count');
    container.replaceChildren();
    count.textContent = setCards.length + ' thẻ';
    setCards.forEach(function (card, cardIndex) {
      var item = document.createElement('tr');
      var number = document.createElement('th');
      number.scope = 'row';
      number.className = 'study-card-list__number';
      number.textContent = String(cardIndex + 1);
      var kanji = document.createElement('td');
      kanji.className = 'study-card-list__kanji';
      kanji.textContent = card.front || card.kanji || '—';
      var hiragana = document.createElement('td');
      hiragana.className = 'study-card-list__hiragana';
      hiragana.textContent = card.hiragana || card.reading || '—';
      var hanViet = document.createElement('td');
      hanViet.className = 'study-card-list__han-viet';
      hanViet.textContent = card.hanViet || '—';
      item.append(number, kanji, hiragana, hanViet);
      container.appendChild(item);
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
      setDetail = await FlashApi.post('/api/flashcards/' + setId + '/rating', { rating: pendingRating });
      renderSetDetail(setDetail);
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
    var isBrowse = studyMode === 'BROWSE';
    document.querySelectorAll('[data-study-mode]').forEach(function (button) {
      button.classList.toggle('is-active', button.getAttribute('data-study-mode') === studyMode);
    });
    var quality = document.getElementById('study-quality-actions');
    var browse = document.getElementById('study-browse-actions');
    var help = document.getElementById('quality-help');
    if (quality) quality.hidden = isBrowse;
    if (browse) browse.hidden = !isBrowse;
    var modeField = document.getElementById('study-mode-field');
    if (modeField) modeField.value = studyMode;
    syncSettingsListboxes();
  }

  function selectStudyMode(nextMode) {
    nextMode = normalizeStudyMode(nextMode);
    if (nextMode === studyMode) return;
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
    document.getElementById('study-mode-field').value = studyMode;
    syncSettingsListboxes();
    document.getElementById('settings-modal').hidden = false;
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
    var front = document.getElementById('face-front-field').value;
    var back = document.getElementById('face-back-field').value;
    var nextStudyMode = normalizeStudyMode(document.getElementById('study-mode-field').value);
    if (front === back) {
      FlashShell.showToast('Mặt trước và mặt sau phải khác nhau', 'err');
      return;
    }
    faceFields.front = front;
    faceFields.back = back;
    studyMode = nextStudyMode;
    localStorage.setItem('jlab.flashcard.studyMode', studyMode);
    updateStudyModeUi();
    if (cards.length) renderCard();
    closeSettings();
    FlashShell.showToast('Đã áp dụng cài đặt', 'ok');
  }
})();
