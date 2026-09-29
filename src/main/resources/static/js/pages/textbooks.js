/**
 * /textbooks — danh sách giáo trình số hoá, nhóm theo textbook.jlptLevel lấy từ dữ liệu.
 * Dữ liệu: GET /api/dictionary/textbooks (Bearer từ JlasAuth → CONTENT_CREATOR thấy cả DRAFT).
 */
(function () {
  'use strict';

  var API = '/api/dictionary/textbooks';
  // textbook.jlptLevel = null (bảng chữ cái…) = nhóm "Nhập môn": đặt theo vị trí trong lộ trình, xếp trước N5.
  var INTRO_LEVEL_LABEL = 'Nhập môn';
  var INTRO_LEVEL_NOTE = 'Bảng chữ cái, kiến thức nền trước N5';

  var loadingEl = document.getElementById('textbooksLoading');
  var errorEl = document.getElementById('textbooksError');
  var emptyEl = document.getElementById('textbooksEmpty');
  var levelsEl = document.getElementById('textbooksLevels');

  function escapeHtml(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function showError(message) {
    loadingEl.hidden = true;
    errorEl.textContent = message;
    errorEl.classList.add('is-visible');
    if (window.JlasUI && typeof JlasUI.toast === 'function') JlasUI.toast(message, 'error');
  }

  /**
   * Thứ tự cấp độ: N5 → N1 theo số giảm dần (N5, N4, N3, N2, N1), giá trị khác xếp sau theo chữ,
   * null/rỗng gom vào nhóm "Nhập môn" đặt đầu tiên (trước N5). Không hardcode danh sách cấp độ.
   */
  function levelSortKey(level) {
    if (!level) return [-1, 0, ''];
    var match = /^N(\d)$/i.exec(level.trim());
    if (match) return [0, -parseInt(match[1], 10), ''];
    return [1, 0, level.toLowerCase()];
  }

  function compareLevels(a, b) {
    var ka = levelSortKey(a), kb = levelSortKey(b);
    for (var i = 0; i < ka.length; i++) {
      if (ka[i] < kb[i]) return -1;
      if (ka[i] > kb[i]) return 1;
    }
    return 0;
  }

  function groupByLevel(textbooks) {
    var groups = {};
    textbooks.forEach(function (t) {
      var key = t.jlptLevel && t.jlptLevel.trim() ? t.jlptLevel.trim() : '';
      if (!groups[key]) groups[key] = [];
      groups[key].push(t);
    });
    return Object.keys(groups).sort(compareLevels).map(function (key) {
      return { level: key, label: key || INTRO_LEVEL_LABEL, note: key ? '' : INTRO_LEVEL_NOTE, textbooks: groups[key] };
    });
  }

  function statusBadge(status) {
    if (status === 'PUBLISHED') return '';
    return '<span class="tb-badge tb-badge--draft">' + escapeHtml(status) + '</span>';
  }

  /**
   * Bìa vẽ bằng CSS (bảng textbook không có cột ảnh): màu theo cấp độ qua [data-level] trong textbooks.css,
   * chữ cấp độ lớn ở giữa; jlptLevel null (bảng chữ cái / nhập môn) hiện あ ア.
   */
  function coverBlock(t) {
    var level = t.jlptLevel && t.jlptLevel.trim() ? t.jlptLevel.trim().toUpperCase() : '';
    return '<div class="textbook-card__cover" data-level="' + escapeHtml(level || 'intro') + '" aria-hidden="true">' +
      (level
        ? '<span class="textbook-card__cover-level">' + escapeHtml(level) + '</span>'
        : '<span class="textbook-card__cover-kana" lang="ja">あ<span class="textbook-card__cover-kana-sep">·</span>ア</span>') +
      '</div>';
  }

  function textbookCard(t) {
    var unitCount = t.unitCount == null ? 0 : t.unitCount;
    return '' +
      '<a class="textbook-card" href="/textbooks/' + encodeURIComponent(t.id) + '">' +
        coverBlock(t) +
        (t.status !== 'PUBLISHED' ? '<div class="textbook-card__top">' + statusBadge(t.status) + '</div>' : '') +
        '<h3 class="textbook-card__title">' + escapeHtml(t.title) + '</h3>' +
        // Mô tả chỉ hiện khi giáo trình chưa có nội dung (unitCount = 0): đó là lời giải thích vì sao trống.
        (unitCount === 0 && t.description ? '<p class="textbook-card__desc">' + escapeHtml(t.description) + '</p>' : '') +
        '<div class="textbook-card__footer">' +
          (unitCount > 0
            ? '<span><i class="fa-solid fa-layer-group" aria-hidden="true"></i> ' + unitCount + ' unit</span>'
            : '<span class="tb-badge">Chưa có nội dung</span>') +
          '<span class="textbook-card__cta">Xem giáo trình <i class="fa-solid fa-arrow-right" aria-hidden="true"></i></span>' +
        '</div>' +
      '</a>';
  }

  function levelSection(group) {
    return '' +
      '<section class="level-section" aria-label="' + escapeHtml(group.label) + '">' +
        '<div class="level-section__header">' +
          '<h2 class="level-section__title">' + escapeHtml(group.label) + '</h2>' +
          '<span class="level-section__count">' + group.textbooks.length + ' giáo trình</span>' +
          (group.note ? '<span class="level-section__note">' + escapeHtml(group.note) + '</span>' : '') +
        '</div>' +
        '<div class="textbook-grid">' + group.textbooks.map(textbookCard).join('') + '</div>' +
      '</section>';
  }

  function render(textbooks) {
    loadingEl.hidden = true;
    if (!textbooks || textbooks.length === 0) {
      emptyEl.hidden = false;
      return;
    }
    levelsEl.innerHTML = groupByLevel(textbooks).map(levelSection).join('');
    levelsEl.hidden = false;
  }

  function load() {
    var headers = window.JlasAuth && typeof JlasAuth.authHeaders === 'function' ? JlasAuth.authHeaders() : {};
    fetch(API, { headers: headers })
      .then(function (res) {
        return res.json().catch(function () { return null; }).then(function (json) {
          if (!res.ok || !json || json.success === false) {
            throw new Error((json && json.message) || ('Không tải được danh sách giáo trình (HTTP ' + res.status + ')'));
          }
          return json.data || [];
        });
      })
      .then(render)
      .catch(function (err) { showError(err.message || 'Không tải được danh sách giáo trình.'); });
  }

  /* ---------- Tab "Theo chủ đề": chủ đề từ bảng tag (SITUATION), tải lazy đúng 1 lần ---------- */

  var TOPICS_API = '/api/dictionary/topics';
  /** Câu giải thích chung cho MỌI chủ đề có lessonCount = 0 (không hardcode riêng chủ đề nào, không cần cột mô tả). */
  var TOPIC_EMPTY_REASON = 'Chưa có bài học nào thuộc chủ đề này';

  var topicsLoadingEl = document.getElementById('topicsLoading');
  var topicsErrorEl = document.getElementById('topicsError');
  var topicsEmptyEl = document.getElementById('topicsEmpty');
  var topicsListEl = document.getElementById('topicsList');
  var topicLessonsLoadingEl = document.getElementById('topicLessonsLoading');
  var topicLessonsEl = document.getElementById('topicLessons');

  var topicsState = 'idle';      // idle | loading | loaded | error — chặn gọi API lặp khi bấm qua lại tab
  var topicLessonsCache = {};    // code → data đã tải, không gọi lại khi chọn lại chủ đề
  var selectedTopic = null;

  function authHeaders() {
    return window.JlasAuth && typeof JlasAuth.authHeaders === 'function' ? JlasAuth.authHeaders() : {};
  }

  function fetchJson(url, notFoundMessage) {
    return fetch(url, { headers: authHeaders() }).then(function (res) {
      return res.json().catch(function () { return null; }).then(function (json) {
        if (res.status === 404 && notFoundMessage) throw new Error(notFoundMessage);
        if (!res.ok || !json || json.success === false) {
          throw new Error((json && json.message) || ('Lỗi HTTP ' + res.status));
        }
        return json.data;
      });
    });
  }

  function topicChip(t) {
    var count = t.lessonCount == null ? 0 : t.lessonCount;
    var empty = count === 0;
    return '' +
      '<button type="button" class="topic-chip' + (empty ? ' topic-chip--empty' : '') + '" data-topic="' + escapeHtml(t.code) + '"' +
        (empty ? ' aria-disabled="true" title="' + escapeHtml(TOPIC_EMPTY_REASON) + '"' : '') + '>' +
        '<span class="topic-chip__name">' + escapeHtml(t.name) + '</span>' +
        (empty
          ? '<span class="topic-chip__note">' + escapeHtml(TOPIC_EMPTY_REASON) + '</span>'
          : '<span class="topic-chip__count">' + count + ' bài</span>') +
      '</button>';
  }

  function renderTopics(topics) {
    topicsLoadingEl.hidden = true;
    if (!topics || topics.length === 0) { topicsEmptyEl.hidden = false; return; }
    topicsListEl.innerHTML = topics.map(topicChip).join('');
    topicsListEl.hidden = false;
  }

  function loadTopics() {
    if (topicsState === 'loading' || topicsState === 'loaded') return;
    topicsState = 'loading';
    topicsErrorEl.classList.remove('is-visible');
    topicsLoadingEl.hidden = false;
    fetchJson(TOPICS_API)
      .then(function (data) { topicsState = 'loaded'; renderTopics(data || []); })
      .catch(function (err) {
        topicsState = 'error'; // cho phép thử lại khi mở tab lần sau
        topicsLoadingEl.hidden = true;
        topicsErrorEl.textContent = err.message || 'Không tải được danh sách chủ đề.';
        topicsErrorEl.classList.add('is-visible');
        if (window.JlasUI && typeof JlasUI.toast === 'function') JlasUI.toast(topicsErrorEl.textContent, 'error');
      });
  }

  function topicLessonRow(l, index) {
    var meta = [];
    if (l.unitTitle) meta.push('<span class="tb-badge">' + escapeHtml(l.unitTitle) + '</span>');
    if (l.estimatedMinutes) meta.push('<span class="tb-badge"><i class="fa-regular fa-clock" aria-hidden="true"></i> ' + l.estimatedMinutes + ' phút</span>');
    meta.push(statusBadge(l.status));
    return '' +
      '<li>' +
        '<a class="lesson-row" href="/textbooks/' + encodeURIComponent(l.textbookId) + '/lessons/' + encodeURIComponent(l.id) + '">' +
          '<span class="lesson-row__order">' + (index + 1) + '</span>' +
          '<span>' +
            '<p class="lesson-row__title">' + escapeHtml(l.title) + '</p>' +
            '<span class="lesson-row__meta">' + meta.join('') + '</span>' +
          '</span>' +
          '<i class="fa-solid fa-chevron-right lesson-row__arrow" aria-hidden="true"></i>' +
        '</a>' +
      '</li>';
  }

  /** Bài của chủ đề trải nhiều giáo trình → nhóm theo giáo trình (giữ thứ tự API: textbook → unit → bài). */
  function renderTopicLessons(data) {
    topicLessonsLoadingEl.hidden = true;
    var lessons = (data && data.lessons) || [];
    var topic = (data && data.topic) || {};
    if (lessons.length === 0) {
      topicLessonsEl.innerHTML = '<div class="textbooks-empty textbooks-empty--inline">' +
        '<strong>' + escapeHtml(topic.name || '') + '</strong><p>' + escapeHtml(TOPIC_EMPTY_REASON) + '.</p></div>';
      topicLessonsEl.hidden = false;
      return;
    }
    var groups = [];
    var byTextbook = {};
    lessons.forEach(function (l) {
      var key = String(l.textbookId);
      if (!byTextbook[key]) { byTextbook[key] = { title: l.textbookTitle, level: l.jlptLevel, lessons: [] }; groups.push(byTextbook[key]); }
      byTextbook[key].lessons.push(l);
    });
    topicLessonsEl.innerHTML =
      '<div class="topic-lessons__header"><h2 class="level-section__title">' + escapeHtml(topic.name || '') + '</h2>' +
      '<span class="level-section__count">' + lessons.length + ' bài</span></div>' +
      groups.map(function (g) {
        return '<section class="unit-card">' +
          '<div class="unit-card__header">' +
          '<div><h3 class="unit-card__title">' + escapeHtml(g.title || '') + '</h3></div></div>' +
          '<ol class="lesson-list">' + g.lessons.map(topicLessonRow).join('') + '</ol>' +
        '</section>';
      }).join('');
    topicLessonsEl.hidden = false;
  }

  function selectTopic(code) {
    selectedTopic = code;
    Array.prototype.slice.call(topicsListEl.querySelectorAll('.topic-chip')).forEach(function (c) {
      c.classList.toggle('is-active', c.getAttribute('data-topic') === code);
    });
    topicLessonsEl.hidden = true;
    if (topicLessonsCache[code]) { renderTopicLessons(topicLessonsCache[code]); return; }
    topicLessonsLoadingEl.hidden = false;
    fetchJson('/api/dictionary/topics/' + encodeURIComponent(code) + '/lessons', 'Không tìm thấy chủ đề này.')
      .then(function (data) {
        topicLessonsCache[code] = data;
        if (selectedTopic === code) renderTopicLessons(data);
      })
      .catch(function (err) {
        topicLessonsLoadingEl.hidden = true;
        if (window.JlasUI && typeof JlasUI.toast === 'function') JlasUI.toast(err.message || 'Không tải được bài của chủ đề.', 'error');
      });
  }

  function setupTopics() {
    topicsListEl.addEventListener('click', function (event) {
      var chip = event.target.closest('.topic-chip');
      if (!chip || chip.getAttribute('aria-disabled') === 'true') return;
      selectTopic(chip.getAttribute('data-topic'));
    });
  }

  /** Chuyển tab "Theo cấp độ" / "Theo chủ đề"; tab chủ đề tải dữ liệu lazy đúng 1 lần. */
  function setupTabs() {
    var tabs = Array.prototype.slice.call(document.querySelectorAll('.lesson-tab'));
    var panels = Array.prototype.slice.call(document.querySelectorAll('.lesson-panel'));
    tabs.forEach(function (tab) {
      tab.addEventListener('click', function () {
        var target = tab.getAttribute('data-tab');
        tabs.forEach(function (t) {
          var active = t === tab;
          t.classList.toggle('is-active', active);
          t.setAttribute('aria-selected', active ? 'true' : 'false');
        });
        panels.forEach(function (p) { p.hidden = p.getAttribute('data-panel') !== target; });
        if (target === 'topic') loadTopics();
      });
    });
  }

  setupTabs();
  setupTopics();
  load();
})();
