(function (root, factory) {
  'use strict';

  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.LessonPage = api;

  if (root && root.document) {
    if (root.document.readyState === 'loading') {
      root.document.addEventListener('DOMContentLoaded', api.mount, { once: true });
    } else {
      api.mount();
    }
  }
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';

  function escapeHtml(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#039;');
  }

  function icon(name) {
    return '<span class="material-symbols-outlined" aria-hidden="true">' + name + '</span>';
  }

  function formatDate(value) {
    if (!value) return '';
    var date = new Date(value);
    if (Number.isNaN(date.getTime())) return '';
    try {
      return date.toLocaleString('vi-VN', {
        day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit'
      });
    } catch (error) {
      return '';
    }
  }

  function renderLoading() {
    return '<div class="ll-progress__loading" role="status">' +
      '<span class="ll-spinner" aria-hidden="true"></span>' +
      '<span>Đang tải trạng thái bài học…</span></div>';
  }

  function renderError() {
    return '<div class="ll-progress__card is-error" role="alert">' +
      '<span class="ll-progress__icon" aria-hidden="true">!</span>' +
      '<div class="ll-progress__body"><span class="ll-progress__kicker">Trạng thái bài học</span>' +
      '<strong>Không tải được trạng thái</strong><p>Vui lòng thử lại sau một chút.</p></div>' +
      '<button class="ll-button" type="button" data-action="retry">' + icon('refresh') + '<span>Thử lại</span></button>' +
      '</div>';
  }

  function renderState(progress) {
    progress = progress || {};
    var status = progress.status || 'NOT_STARTED';

    if (status === 'COMPLETED') {
      return '<div class="ll-progress__card is-complete">' +
        '<span class="ll-progress__icon" aria-hidden="true">' + icon('check_circle') + '</span>' +
        '<div class="ll-progress__body"><span class="ll-progress__kicker">Trạng thái bài học</span>' +
        '<strong>Bạn đã hoàn thành bài học này</strong>' +
        '<p>Hoàn thành lúc ' + escapeHtml(formatDate(progress.completedAt)) + ' · tiến độ đã được ghi vào lộ trình.</p></div>' +
        '</div>';
    }

    if (status === 'IN_PROGRESS') {
      return '<div class="ll-progress__card is-active">' +
        '<span class="ll-progress__icon" aria-hidden="true">' + icon('schedule') + '</span>' +
        '<div class="ll-progress__body"><span class="ll-progress__kicker">Trạng thái bài học</span>' +
        '<strong>Bạn đang học bài này</strong>' +
        '<p>Bắt đầu lúc ' + escapeHtml(formatDate(progress.startedAt)) + ' · nhấn hoàn thành khi đã học xong.</p></div>' +
        '<button class="ll-button ll-button--primary" type="button" data-action="complete">' +
        icon('check') + '<span>Hoàn thành bài</span></button>' +
        '</div>';
    }

    return '<div class="ll-progress__card">' +
      '<span class="ll-progress__icon" aria-hidden="true">' + icon('play_circle') + '</span>' +
      '<div class="ll-progress__body"><span class="ll-progress__kicker">Trạng thái bài học</span>' +
      '<strong>Bắt đầu học bài này</strong>' +
      '<p>Khi hoàn thành, tiến độ sẽ được ghi nhận vào lộ trình của bạn.</p></div>' +
      '<button class="ll-button ll-button--primary" type="button" data-action="start">' +
      icon('play_arrow') + '<span>Bắt đầu học</span></button>' +
      '</div>';
  }

  function sectionLabel(type) {
    switch (type) {
      case 'EXPLANATION': return 'Giải thích';
      case 'EXAMPLE': return 'Ví dụ';
      case 'EXERCISE': return 'Luyện tập';
      default: return 'Nội dung';
    }
  }

  function renderItem(item) {
    item = item || {};
    var html = '<article class="ll-item">';
    if (item.title) html += '<h3 class="ll-item__title">' + escapeHtml(item.title) + '</h3>';
    if (item.content) html += '<div class="ll-item__text">' + escapeHtml(item.content) + '</div>';
    if (item.translatedText) html += '<p class="ll-item__translation">' + escapeHtml(item.translatedText) + '</p>';
    return html + '</article>';
  }

  function renderSection(section) {
    section = section || {};
    var items = (section.items || []).map(renderItem).join('');
    return '<section class="ll-section">' +
      '<header class="ll-section__head">' +
      '<span class="ll-section__tag">' + escapeHtml(sectionLabel(section.sectionType)) + '</span>' +
      '<h2 class="ll-section__title">' + escapeHtml(section.title) + '</h2>' +
      '</header>' +
      '<div class="ll-section__items">' + items + '</div>' +
      '</section>';
  }

  function renderGrammarPoint(point) {
    point = point || {};
    var example = point.example
      ? '<p class="ll-grammar__row"><span class="ll-grammar__label">Ví dụ</span>' +
        '<span class="ll-grammar__example">' + escapeHtml(point.example) + '</span></p>'
      : '';
    var note = point.note ? '<p class="ll-grammar__note">' + escapeHtml(point.note) + '</p>' : '';
    return '<li class="ll-grammar__point">' +
      '<p class="ll-grammar__pattern">' + escapeHtml(point.pattern) + '</p>' +
      '<p class="ll-grammar__meaning">' + escapeHtml(point.meaning) + '</p>' +
      example + note + '</li>';
  }

  function renderGrammar(points) {
    points = points || [];
    if (!points.length) return '';
    return '<section class="ll-grammar">' +
      '<header class="ll-section__head">' +
      '<span class="ll-section__tag ll-section__tag--grammar">Ngữ pháp</span>' +
      '<h2 class="ll-section__title">Điểm ngữ pháp</h2>' +
      '</header>' +
      '<ul class="ll-grammar__list">' + points.map(renderGrammarPoint).join('') + '</ul>' +
      '</section>';
  }

  function renderContent(data) {
    data = data || {};
    var sections = (data.sections || []).map(renderSection).join('');
    var grammar = renderGrammar(data.grammarPoints);
    if (!sections && !grammar) {
      return '<div class="ll-content__empty">' +
        '<span class="material-symbols-outlined" aria-hidden="true">description_off</span>' +
        '<p>Bài học này chưa có nội dung chi tiết.</p></div>';
    }
    return sections + grammar;
  }

  function renderContentError() {
    return '<div class="ll-content__empty">' +
      '<span class="material-symbols-outlined" aria-hidden="true">cloud_off</span>' +
      '<p>Không tải được nội dung bài học. Vui lòng thử lại.</p></div>';
  }

  async function readBody(response) {
    try { return await response.json(); } catch (error) { return {}; }
  }

  function mount() {
    var root = document.getElementById('lessonProgressRoot');
    if (!root || !window.JlasAuth || !window.JlasAuth.requireLogin()) return;

    var lessonId = Number(root.getAttribute('data-lesson-id'));
    if (!Number.isInteger(lessonId) || lessonId <= 0) return;

    var contentRoot = document.getElementById('lessonContentRoot');

    function paint(html) {
      root.innerHTML = html;
    }

    async function load() {
      paint(renderLoading());
      try {
        var response = await fetch('/api/learning/progress/lessons/' + lessonId, {
          headers: window.JlasAuth.authHeaders()
        });
        var body = await readBody(response);
        if (!response.ok || body.success === false || !body.data) {
          paint(renderError());
          return;
        }
        paint(renderState(body.data));
      } catch (error) {
        paint(renderError());
      }
    }

    async function act(method) {
      paint(renderLoading());
      try {
        var response = await fetch('/api/learning/progress/lessons/' + lessonId + '/' + method, {
          method: 'POST',
          headers: window.JlasAuth.authHeaders()
        });
        var body = await readBody(response);
        if (!response.ok || body.success === false || !body.data) {
          paint(renderError());
          return;
        }
        paint(renderState(body.data));
      } catch (error) {
        paint(renderError());
      }
    }

    async function loadContent() {
      if (!contentRoot) return;
      try {
        var response = await fetch('/api/learning/lessons/' + lessonId, {
          headers: window.JlasAuth.authHeaders()
        });
        var body = await readBody(response);
        if (!response.ok || body.success === false || !body.data) {
          contentRoot.innerHTML = renderContentError();
          return;
        }
        contentRoot.innerHTML = renderContent(body.data);
      } catch (error) {
        contentRoot.innerHTML = renderContentError();
      }
    }

    root.addEventListener('click', function (event) {
      var target = event.target.closest('[data-action]');
      if (!target) return;
      var action = target.getAttribute('data-action');
      if (action === 'retry') load();
      if (action === 'start') act('start');
      if (action === 'complete') act('complete');
    });

    load();
    loadContent();
  }

  return {
    renderState: renderState,
    renderContent: renderContent,
    mount: mount
  };
});
