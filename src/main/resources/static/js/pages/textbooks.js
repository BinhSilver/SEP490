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
  // topicBased (API tính: không có cấp độ + có bài gắn tag SITUATION) = nhóm "Theo chủ đề", xếp sau cùng.
  var TOPIC_GROUP_KEY = '\u0000topic';
  var TOPIC_GROUP_LABEL = 'Theo chủ đề';
  var TOPIC_GROUP_NOTE = 'Từ vựng và tình huống theo lĩnh vực, học độc lập với cấp độ';

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
    if (level === TOPIC_GROUP_KEY) return [2, 0, ''];
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

  function groupKeyOf(textbook) {
    if (textbook.topicBased) return TOPIC_GROUP_KEY;
    return textbook.jlptLevel && textbook.jlptLevel.trim() ? textbook.jlptLevel.trim() : '';
  }

  function groupLabel(key) {
    if (key === TOPIC_GROUP_KEY) return TOPIC_GROUP_LABEL;
    return key || INTRO_LEVEL_LABEL;
  }

  function groupNote(key) {
    if (key === TOPIC_GROUP_KEY) return TOPIC_GROUP_NOTE;
    return key ? '' : INTRO_LEVEL_NOTE;
  }

  function groupByLevel(textbooks) {
    var groups = {};
    textbooks.forEach(function (t) {
      var key = groupKeyOf(t);
      if (!groups[key]) groups[key] = [];
      groups[key].push(t);
    });
    return Object.keys(groups).sort(compareLevels).map(function (key) {
      // level rỗng cho nhóm chủ đề: bìa/CSS [data-level] dùng chung kiểu với nhóm không có cấp độ.
      return {
        level: key === TOPIC_GROUP_KEY ? '' : key,
        label: groupLabel(key),
        note: groupNote(key),
        textbooks: groups[key]
      };
    });
  }

  function statusBadge(status) {
    if (status === 'PUBLISHED') return '';
    return '<span class="tb-badge tb-badge--draft">' + escapeHtml(status) + '</span>';
  }

  /* ---------- TextbookThumbnail: ảnh tải lên, hoặc thumbnail tự sinh khi chưa có / ảnh lỗi ---------- */

  /** 6 nền pastel (tím, xanh ngọc, cam san hô, xanh dương, vàng, hồng) kèm màu chữ Hán cùng tông. */
  var THUMB_PALETTE = [
    { bg: '#ece6fb', ink: '#5b3fa8' },
    { bg: '#d9f2ed', ink: '#1d7568' },
    { bg: '#fde4dc', ink: '#b04f36' },
    { bg: '#dfeafb', ink: '#2e5c9a' },
    { bg: '#fbf0cf', ink: '#86660f' },
    { bg: '#fbe1ec', ink: '#a5426d' }
  ];
  var THUMB_DEFAULT_KANJI = '日';
  /** theme_color do người biên soạn chọn có thể là màu bất kỳ → chữ Hán dùng màu tối trung tính. */
  var THUMB_CUSTOM_INK = '#161d1f';

  /** Hash ổn định theo tên: cùng tên luôn ra cùng màu, giữa các lần tải trang và giữa các máy. */
  function hashString(value) {
    var hash = 0;
    var text = String(value == null ? '' : value);
    for (var i = 0; i < text.length; i++) hash = (hash * 31 + text.charCodeAt(i)) >>> 0;
    return hash;
  }

  function thumbnailColors(t) {
    if (t.themeColor && /^#[0-9a-f]{6}$/i.test(t.themeColor)) return { bg: t.themeColor, ink: THUMB_CUSTOM_INK };
    return THUMB_PALETTE[hashString(t.title) % THUMB_PALETTE.length];
  }

  function levelBadgeText(t) {
    if (t.jlptLevel && t.jlptLevel.trim()) return t.jlptLevel.trim().toUpperCase();
    return t.topicBased ? 'Chủ đề' : INTRO_LEVEL_LABEL;
  }

  /**
   * Thumbnail thay cho khối bìa cũ. Lớp fallback luôn được vẽ bên dưới; ảnh (nếu có) phủ lên trên và bị gỡ
   * khi tải lỗi (xem setupThumbnailFallback) nên người dùng không bao giờ thấy ảnh vỡ. Badge cấp độ góc trái,
   * badge trạng thái góc phải nằm TRONG thumbnail để tiêu đề các card thẳng hàng với nhau.
   */
  function textbookThumbnail(t) {
    var colors = thumbnailColors(t);
    var kanji = t.thumbnailKanji && t.thumbnailKanji.trim() ? t.thumbnailKanji.trim() : THUMB_DEFAULT_KANJI;
    return '' +
      '<div class="textbook-thumb" style="--thumb-bg:' + escapeHtml(colors.bg) + ';--thumb-ink:' + escapeHtml(colors.ink) + '">' +
        '<span class="textbook-thumb__kanji" lang="ja" aria-hidden="true">' + escapeHtml(kanji) + '</span>' +
        (t.thumbnailUrl
          ? '<img class="textbook-thumb__img" src="' + escapeHtml(t.thumbnailUrl) + '" alt="" loading="lazy" decoding="async">'
          : '') +
        // Có ảnh thì ẩn badge cấp độ (ảnh tự thiết kế đã thể hiện); ảnh lỗi → setupThumbnailFallback hiện lại.
        '<span class="textbook-thumb__badge textbook-thumb__badge--level"' + (t.thumbnailUrl ? ' hidden' : '') + '>' +
          escapeHtml(levelBadgeText(t)) + '</span>' +
        (t.status !== 'PUBLISHED'
          ? '<span class="textbook-thumb__badge textbook-thumb__badge--status">' + statusBadge(t.status) + '</span>'
          : '') +
      '</div>';
  }

  /**
   * Sự kiện error của <img> không nổi bọt → bắt ở pha capture một lần cho cả danh sách.
   * Ảnh lỗi: gỡ ảnh để lộ fallback chữ Hán và hiện lại badge cấp độ đã ẩn lúc render.
   */
  function setupThumbnailFallback() {
    levelsEl.addEventListener('error', function (event) {
      var target = event.target;
      if (!target || !target.classList || !target.classList.contains('textbook-thumb__img')) return;
      var thumb = target.parentNode;
      var levelBadge = thumb && thumb.querySelector('.textbook-thumb__badge--level');
      if (levelBadge) levelBadge.hidden = false;
      target.remove();
    }, true);
  }

  function textbookCard(t) {
    var unitCount = t.unitCount == null ? 0 : t.unitCount;
    return '' +
      '<a class="textbook-card" href="/textbooks/' + encodeURIComponent(t.id) + '">' +
        textbookThumbnail(t) +
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

  /**
   * Lối vào màn hình quản lý, chỉ hiện với CONTENT_CREATOR.
   * Đọc role từ đúng nguồn mà shell nav dùng (JlasAuth.getUser().role, cache localStorage làm mới qua
   * /api/auth/me) nên không phát sinh cách kiểm vai thứ hai. NAV_ROLES không dùng được ở đây vì nó chỉ
   * ẩn/hiện phần tử BÊN TRONG shell nav, còn nút này nằm trong nội dung trang; cách này trùng với
   * textbook-units.js đang làm cho nút đổi trạng thái. Đây chỉ là ẩn/hiện giao diện — route thật đã
   * chặn ở backend bằng @PreAuthorize, gõ thẳng /textbooks/manage cũng chỉ nhận 403 từ API.
   */
  function setupManageEntry() {
    var link = document.getElementById('manageEntryLink');
    if (!link) return;
    var user = window.JlasAuth && typeof JlasAuth.getUser === 'function' ? JlasAuth.getUser() : null;
    link.hidden = !(user && user.role === 'CONTENT_CREATOR');
  }

  setupTabs();
  setupThumbnailFallback();
  setupTopics();
  setupManageEntry();
  load();
})();
