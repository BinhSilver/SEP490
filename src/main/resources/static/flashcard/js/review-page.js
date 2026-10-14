/* Ôn hôm nay — hàng ôn SRS gộp mọi bộ thẻ (/api/study/due) và thống kê (/api/study/srs-stats). */
(function () {
  'use strict';

  var Srs = window.FlashcardSrs;
  var HEATMAP_WEEKS = 26;
  var PAGE_SIZE = 50;

  var queue = [];
  var current = null;
  var revealed = false;
  var busy = false;
  var reviewedCount = 0;
  var dueCount = 0;
  var el = {};

  document.addEventListener('DOMContentLoaded', function () {
    var redirectTarget = window.location.pathname + window.location.search;
    if (window.JlasAuth && typeof JlasAuth.requireLogin === 'function') {
      if (!JlasAuth.requireLogin(redirectTarget)) return;
    } else if (!localStorage.getItem('accessToken')) {
      window.location.href = '/login?redirect=' + encodeURIComponent(redirectTarget);
      return;
    }
    ['due', 'today', 'streak', 'retention', 'progress', 'progress-label', 'progress-fill', 'card', 'card-set',
      'card-relearn', 'speak', 'front', 'back', 'reading', 'meaning', 'hanviet', 'example', 'reveal', 'grades',
      'relearn-next', 'empty', 'empty-title', 'empty-text', 'loading', 'forecast', 'heatmap', 'counts'
    ].forEach(function (id) { el[id] = document.getElementById('srs-' + id); });

    el.reveal.addEventListener('click', reveal);
    el['relearn-next'].addEventListener('click', advance);
    el.speak.addEventListener('click', speak);
    el.grades.addEventListener('click', function (event) {
      var button = event.target.closest('[data-quality]');
      if (button) grade(Number(button.getAttribute('data-quality')));
    });
    document.addEventListener('keydown', onKey);

    loadQueue();
    loadStats();
  });

  function onKey(event) {
    if (!current || event.ctrlKey || event.metaKey || event.altKey) return;
    if (event.target && /^(INPUT|TEXTAREA|SELECT)$/.test(event.target.tagName)) return;
    if (event.key === ' ' || event.key === 'Enter') {
      event.preventDefault();
      if (!revealed) reveal(); else if (current.relearn) advance();
      return;
    }
    if (revealed && !current.relearn) {
      var g = Srs.gradeByHotkey(event.key);
      if (g) { event.preventDefault(); grade(g.quality); }
    }
  }

  async function loadQueue() {
    try {
      var data = await FlashApi.get('/api/study/due?limit=' + PAGE_SIZE);
      queue = (data && data.cards) || [];
      // Không có thẻ trả về thì coi như hết hạn, tránh tải lại liên tục.
      dueCount = queue.length ? (data.dueCount || queue.length) : 0;
      el.loading.hidden = true;
      paintDue();
      next();
    } catch (e) {
      el.loading.textContent = e.message || 'Không tải được hàng ôn';
    }
  }

  async function loadStats() {
    try {
      var stats = await FlashApi.get('/api/study/srs-stats');
      paintStats(stats || {});
    } catch (e) {
      el.counts.textContent = 'Chưa tải được thống kê.';
    }
  }

  function next() {
    current = queue.shift() || null;
    revealed = false;
    if (!current) {
      if (dueCount > 0 && reviewedCount > 0) { loadQueue(); return; }
      finish();
      return;
    }
    var card = current.card || {};
    el.card.hidden = false;
    el.empty.hidden = true;
    el.front.textContent = card.front || card.kanji || '';
    el['card-set'].textContent = current.setTitle || 'Bộ thẻ';
    el['card-set'].href = '/flashcard/study.html?setId=' + encodeURIComponent(current.setId);
    el['card-relearn'].hidden = !current.relearn;
    el.reading.textContent = card.reading && card.reading !== card.front ? card.reading : '';
    el.meaning.textContent = card.meaning || card.back || '';
    el.hanviet.textContent = card.hanViet ? 'Hán Việt: ' + card.hanViet : '';
    el.example.innerHTML = card.exampleJa
      ? '<span lang="ja">' + Srs.esc(card.exampleJa) + '</span>' + (card.exampleVi ? '<small>' + Srs.esc(card.exampleVi) + '</small>' : '')
      : '';
    el.back.hidden = true;
    el.reveal.hidden = false;
    el.grades.hidden = true;
    el['relearn-next'].hidden = true;
    paintProgress();
    el.reveal.focus({ preventScroll: true });
  }

  function reveal() {
    if (!current || revealed) return;
    revealed = true;
    el.back.hidden = false;
    el.reveal.hidden = true;
    if (current.relearn) {
      el['relearn-next'].hidden = false;
      el['relearn-next'].focus({ preventScroll: true });
      return;
    }
    var preview = current.intervalPreview || {};
    el.grades.innerHTML = Srs.GRADES.map(function (g) {
      return '<button type="button" class="srs-grade srs-grade--' + g.key + '" data-quality="' + g.quality + '">' +
        '<strong>' + Srs.esc(g.label) + '</strong><span>' + Srs.esc(Srs.formatInterval(preview[g.quality])) + '</span>' +
        '<kbd>' + g.hotkey + '</kbd></button>';
    }).join('');
    el.grades.hidden = false;
  }

  async function grade(quality) {
    if (!current || !revealed || busy || current.relearn) return;
    busy = true;
    el.grades.classList.add('is-busy');
    try {
      var result = await FlashApi.post('/api/study/due/review', { cardId: current.card.id, quality: quality });
      reviewedCount++;
      dueCount = result && typeof result.dueCount === 'number' ? result.dueCount : Math.max(0, dueCount - 1);
      if (Srs.isLapse(quality)) queue = Srs.requeue(queue, current);
      paintDue();
      bumpToday();
      next();
    } catch (e) {
      FlashShell.showToast(e.message || 'Không lưu được kết quả', 'err');
    } finally {
      busy = false;
      el.grades.classList.remove('is-busy');
    }
  }

  function advance() {
    if (current && current.relearn && revealed) next();
  }

  function finish() {
    el.card.hidden = true;
    el.progress.hidden = true;
    el.empty.hidden = false;
    if (reviewedCount > 0) {
      el['empty-title'].textContent = 'Hoàn thành! Bạn đã ôn ' + reviewedCount + ' thẻ';
      el['empty-text'].textContent = 'Lịch ôn đã được cập nhật. Quay lại theo dự báo bên dưới nhé.';
      loadStats();
    }
  }

  function speak() {
    var text = current && current.card && (current.card.front || current.card.kanji);
    if (!text || !('speechSynthesis' in window)) return;
    window.speechSynthesis.cancel();
    var utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = 'ja-JP';
    utterance.rate = 0.85;
    window.speechSynthesis.speak(utterance);
  }

  function paintDue() {
    el.due.textContent = dueCount;
  }

  function bumpToday() {
    var value = parseInt(el.today.textContent, 10);
    el.today.textContent = (isNaN(value) ? 0 : value) + 1;
  }

  function paintProgress() {
    var total = reviewedCount + dueCount;
    el.progress.hidden = total === 0;
    el['progress-label'].textContent = reviewedCount + ' / ' + total;
    el['progress-fill'].style.width = (total ? Math.round(reviewedCount / total * 100) : 0) + '%';
  }

  function paintStats(stats) {
    el.today.textContent = stats.reviewsToday || 0;
    el.streak.textContent = (stats.streakDays || 0) + ' ngày';
    el.retention.textContent = stats.retention30d == null ? '–' : String(stats.retention30d).replace('.', ',') + '%';
    if (typeof stats.dueNow === 'number' && !reviewedCount) { dueCount = stats.dueNow; paintDue(); }

    el.forecast.innerHTML = Srs.forecastBars(stats.forecast).map(function (bar) {
      return '<div class="srs-forecast__col" title="' + Srs.esc(bar.date + ': ' + bar.count + ' thẻ') + '">' +
        '<span class="srs-forecast__count">' + bar.count + '</span>' +
        '<span class="srs-forecast__track"><i style="height:' + bar.pct + '%"></i></span>' +
        '<span class="srs-forecast__label">' + Srs.esc(bar.label) + '</span></div>';
    }).join('');

    var today = (stats.forecast && stats.forecast[0] && stats.forecast[0].date) || new Date().toISOString().slice(0, 10);
    el.heatmap.innerHTML = Srs.heatmapCells(stats.heatmap, today, HEATMAP_WEEKS).map(function (cell) {
      return cell.future
        ? '<i class="is-future"></i>'
        : '<i data-level="' + cell.level + '" title="' + Srs.esc(cell.date + ': ' + cell.count + ' lượt ôn') + '"></i>';
    }).join('');
    el.counts.textContent = 'Đang học ' + (stats.learning || 0) + ' thẻ · Đã thuộc ' + (stats.mastered || 0) +
      ' thẻ · ' + (stats.reviews30d || 0) + ' lượt ôn đúng hạn trong 30 ngày';
  }
})();
