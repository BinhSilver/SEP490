/** Community Hub: trang feed (/community). Bộ lọc được giữ trên URL để chia sẻ/quay lại. */
(function () {
  'use strict';

  var UI = window.CommunityUI;
  var PAGE_SIZE = 12;
  var form = document.getElementById('cmFilters');
  var grid = document.getElementById('cmGrid');
  var summary = document.getElementById('cmSummary');
  var pager = document.getElementById('cmPager');
  var pageInfo = document.getElementById('cmPageInfo');
  var fields = {
    keyword: document.getElementById('cmKeyword'),
    contentType: document.getElementById('cmType'),
    jlptLevel: document.getElementById('cmLevel'),
    minRating: document.getElementById('cmRating'),
    sort: document.getElementById('cmSort')
  };
  var state = { page: 0, totalPages: 0, loading: false };

  function fillSelect(select, options, keepFirst) {
    var first = keepFirst ? select.options[0].outerHTML : '';
    select.innerHTML = first + options.map(function (o) {
      return '<option value="' + UI.esc(o.value) + '">' + UI.esc(o.label) + '</option>';
    }).join('');
  }

  function readUrl() {
    var params = new URLSearchParams(window.location.search);
    Object.keys(fields).forEach(function (key) {
      var value = params.get(key);
      if (value !== null && Array.prototype.some.call(fields[key].options || [{ value: value }], function (o) { return o.value === value; })) {
        fields[key].value = value;
      } else if (key === 'keyword' && value !== null) {
        fields.keyword.value = value;
      }
    });
    state.page = Math.max(0, parseInt(params.get('page') || '0', 10) || 0);
  }

  function query() {
    var params = new URLSearchParams();
    Object.keys(fields).forEach(function (key) {
      var value = (fields[key].value || '').trim();
      if (value && !(key === 'sort' && value === 'newest')) params.set(key, value);
    });
    if (state.page > 0) params.set('page', String(state.page));
    return params;
  }

  function card(item) {
    var thumb = item.thumbnailUrl
      ? '<img src="' + UI.esc(UI.safeUrl(item.thumbnailUrl)) + '" alt="" loading="lazy">'
      : '<i class="fa-solid ' + (item.contentType === 'SHADOWING_VIDEO' ? 'fa-film' : 'fa-layer-group') + '" aria-hidden="true"></i>';
    var author = item.author ? UI.esc(item.author.displayName) : 'Ẩn danh';
    return '<article class="cm-card">' +
      '<a class="cm-card__link" href="' + UI.detailUrl(item.contentType, item.contentId) + '">' +
        '<div class="cm-card__thumb">' + thumb + '</div>' +
        '<div class="cm-card__body">' +
          '<div class="cm-badges">' +
            '<span class="cm-badge">' + UI.esc(item.contentTypeLabel) + '</span>' +
            (item.jlptLevel ? '<span class="cm-badge cm-badge--level">' + UI.esc(item.jlptLevel) + '</span>' : '') +
          '</div>' +
          '<h2 class="cm-card__title">' + UI.esc(item.title) + '</h2>' +
          (item.description ? '<p class="cm-card__desc">' + UI.esc(item.description) + '</p>' : '') +
          '<div class="cm-card__meta">' +
            '<span>' + UI.stars(item.ratingAvg) + ' <b>' + Number(item.ratingAvg || 0).toFixed(1) + '</b> (' + Number(item.ratingCount || 0) + ')</span>' +
            '<span><i class="fa-regular fa-comment" aria-hidden="true"></i> ' + Number(item.commentCount || 0) + '</span>' +
          '</div>' +
          '<p class="cm-card__author"><i class="fa-regular fa-user" aria-hidden="true"></i> ' + author +
            (item.myRating ? ' · <span class="cm-mine">Bạn đã chấm ' + Number(item.myRating) + '★</span>' : '') + '</p>' +
        '</div>' +
      '</a>' +
    '</article>';
  }

  function renderState(html) {
    grid.innerHTML = '<div class="cm-state">' + html + '</div>';
  }

  async function load(pushUrl) {
    if (state.loading) return;
    state.loading = true;
    grid.setAttribute('aria-busy', 'true');
    renderState('<span class="cm-spinner" aria-hidden="true"></span> Đang tải nội dung…');
    pager.hidden = true;
    var params = query();
    if (pushUrl) {
      var url = window.location.pathname + (params.toString() ? '?' + params.toString() : '');
      window.history.replaceState(null, '', url);
    }
    params.set('size', String(PAGE_SIZE));
    params.set('page', String(state.page));
    try {
      var data = await UI.api('/api/community/contents?' + params.toString());
      state.totalPages = data.totalPages;
      if (!data.items.length) {
        summary.textContent = '';
        renderState('<i class="fa-regular fa-folder-open" aria-hidden="true"></i>' +
          '<p><b>Chưa có nội dung phù hợp</b></p><p class="cm-muted">Thử đổi từ khoá hoặc bỏ bớt bộ lọc.</p>');
      } else {
        summary.textContent = 'Tìm thấy ' + data.totalElements + ' nội dung';
        grid.innerHTML = data.items.map(card).join('');
        if (data.totalPages > 1) {
          pager.hidden = false;
          pageInfo.textContent = 'Trang ' + (data.page + 1) + '/' + data.totalPages;
          pager.querySelector('[data-step="-1"]').disabled = data.page <= 0;
          pager.querySelector('[data-step="1"]').disabled = data.page + 1 >= data.totalPages;
        }
      }
    } catch (error) {
      summary.textContent = '';
      renderState('<i class="fa-solid fa-triangle-exclamation" aria-hidden="true"></i><p>' + UI.esc(error.message) + '</p>' +
        '<button type="button" class="btn btn--secondary btn--sm" id="cmRetry">Thử lại</button>');
      var retry = document.getElementById('cmRetry');
      if (retry) retry.addEventListener('click', function () { load(false); });
    } finally {
      state.loading = false;
      grid.setAttribute('aria-busy', 'false');
    }
  }

  async function init() {
    try {
      var meta = await UI.api('/api/community/meta');
      fillSelect(fields.contentType, meta.contentTypes, true);
      fillSelect(fields.jlptLevel, meta.jlptLevels.map(function (l) { return { value: l, label: l }; }), true);
      fillSelect(fields.sort, meta.sorts, false);
    } catch (error) {
      UI.toast(error.message, 'error');
    }
    readUrl();
    load(false);
  }

  form.addEventListener('submit', function (event) {
    event.preventDefault();
    state.page = 0;
    load(true);
  });
  ['contentType', 'jlptLevel', 'minRating', 'sort'].forEach(function (key) {
    fields[key].addEventListener('change', function () { state.page = 0; load(true); });
  });
  document.getElementById('cmReset').addEventListener('click', function () {
    form.reset();
    fields.sort.value = 'newest';
    state.page = 0;
    load(true);
  });
  pager.addEventListener('click', function (event) {
    var button = event.target.closest('[data-step]');
    if (!button || button.disabled) return;
    state.page = Math.min(Math.max(0, state.page + Number(button.dataset.step)), Math.max(0, state.totalPages - 1));
    load(true);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  });

  init();
})();
