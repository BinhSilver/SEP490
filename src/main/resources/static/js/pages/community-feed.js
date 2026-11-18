/**
 * Community Hub: bảng tin /community. Ô soạn bài ở đầu feed, bộ lọc (giữ trên URL để chia sẻ/quay lại) ở cột trái
 * (drawer trên mobile), feed trộn bài viết + nội dung chia sẻ, tải thêm khi cuộn (IntersectionObserver; trình duyệt
 * không hỗ trợ thì dùng nút "Xem thêm"). Thẻ và tương tác: community-cards.js.
 */
(function () {
  'use strict';

  var UI = window.CommunityUI;
  var Cards = window.CommunityCards;
  var PAGE_SIZE = 10;
  var MOBILE = window.matchMedia('(max-width: 640px)');          // ô soạn bài mở dạng modal
  var DRAWER = window.matchMedia('(max-width: 900px)');          // bộ lọc nằm trong drawer (cùng mốc với community.css)
  var el = function (id) { return document.getElementById(id); };

  var form = el('cmFilters');
  var feedEl = el('cmFeed');
  var stateEl = el('cmFeedState');
  var summary = el('cmSummary');
  var loadMoreBtn = el('cmLoadMore');
  var fields = {
    keyword: el('cmKeyword'),
    contentType: el('cmType'),
    jlptLevel: el('cmLevel'),
    minRating: el('cmRating'),
    sort: el('cmSort')
  };
  // removed: số thẻ đã bỏ khỏi feed (xoá / gỡ / bị ẩn do báo cáo) từ lần tải trang gần nhất — feed phân trang theo
  // offset nên trang sau phải lùi lại đúng số đó, không thì bỏ sót bài. failed: lần tải trang sau gần nhất bị lỗi →
  // không tự tải lại khi cuộn (tránh gọi API liên tục), chờ người dùng bấm "Xem thêm".
  var state = { page: -1, totalPages: 0, loading: false, done: false, failed: false, removed: 0, generation: 0 };
  var cards = Cards.create(feedEl, {
    onRemoved: function () {
      state.removed += 1;
      if (!cards.count() && state.done) showEmpty();
    }
  });

  // ── Bộ lọc ───────────────────────────────────────────────────────────────
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
      if (value === null) return;
      if (key === 'keyword') { fields.keyword.value = value; return; }
      if (Array.prototype.some.call(fields[key].options, function (o) { return o.value === value; })) fields[key].value = value;
    });
  }

  function filterParams() {
    var params = new URLSearchParams();
    Object.keys(fields).forEach(function (key) {
      var value = (fields[key].value || '').trim();
      if (value && !(key === 'sort' && value === 'newest')) params.set(key, value);
    });
    return params;
  }

  function hasFilter() {
    return Array.from(filterParams().keys()).length > 0;
  }

  // ── Trạng thái ───────────────────────────────────────────────────────────
  function skeleton() {
    var one = '<div class="cm-post cm-skeleton" aria-hidden="true"><div class="cm-post__head"><span class="cm-sk cm-sk--avatar"></span>' +
      '<div class="cm-post__who"><span class="cm-sk cm-sk--line" style="width:40%"></span><span class="cm-sk cm-sk--line" style="width:25%"></span></div></div>' +
      '<span class="cm-sk cm-sk--line"></span><span class="cm-sk cm-sk--line"></span><span class="cm-sk cm-sk--line" style="width:60%"></span></div>';
    stateEl.innerHTML = '<span class="cm-visually-hidden">Đang tải bảng tin…</span>' + one + one + one;
  }

  function showEmpty() {
    stateEl.innerHTML = '<div class="cm-state"><i class="fa-regular fa-comments" aria-hidden="true"></i>' +
      (hasFilter()
        ? '<p><b>Chưa có nội dung phù hợp</b></p><p class="cm-muted">Thử đổi từ khoá hoặc bỏ bớt bộ lọc.</p>'
        : '<p><b>Bảng tin còn trống</b></p><p class="cm-muted">Hãy là người đầu tiên chia sẻ kinh nghiệm học tiếng Nhật!</p>') +
      '</div>';
  }

  function showError(message) {
    stateEl.innerHTML = '<div class="cm-state"><i class="fa-solid fa-triangle-exclamation" aria-hidden="true"></i><p>' + UI.esc(message) + '</p>' +
      '<button type="button" class="btn btn--secondary btn--sm" id="cmRetry">Thử lại</button></div>';
    el('cmRetry').addEventListener('click', function () { state.failed = false; loadNext(); });
  }

  // ── Tải feed ─────────────────────────────────────────────────────────────
  async function loadNext() {
    if (state.loading || state.done) return;
    state.loading = true;
    var generation = state.generation;
    // Lùi offset theo số thẻ đã bỏ: tải lại phần trùng (bỏ qua nhờ cards.add) thay vì bỏ sót bài.
    var page = state.page < 0 ? 0 : Math.floor(Math.max(0, (state.page + 1) * PAGE_SIZE - state.removed) / PAGE_SIZE);
    if (page === 0 && state.page < 0) skeleton();
    feedEl.setAttribute('aria-busy', 'true');
    loadMoreBtn.hidden = true;
    var params = filterParams();
    params.set('page', String(page));
    params.set('size', String(PAGE_SIZE));
    try {
      var data = await UI.api('/api/community/contents?' + params.toString());
      if (generation !== state.generation) return;
      state.page = page;
      state.removed = 0;
      state.failed = false;
      state.totalPages = data.totalPages;
      state.done = page + 1 >= data.totalPages;
      stateEl.innerHTML = '';
      cards.add(data.items);
      summary.textContent = hasFilter() ? 'Tìm thấy ' + data.totalElements + ' mục' : '';
      if (!cards.count()) showEmpty();
      else if (!state.done && !('IntersectionObserver' in window)) loadMoreBtn.hidden = false;
    } catch (error) {
      if (generation !== state.generation) return;
      if (state.page < 0) showError(error.message);
      else { state.failed = true; UI.toast(error.message, 'error'); loadMoreBtn.hidden = false; }
    } finally {
      if (generation === state.generation) {
        state.loading = false;
        feedEl.setAttribute('aria-busy', 'false');
        // Sentinel vẫn trong khung nhìn (trang ngắn) thì quan sát lại để tải tiếp, không gọi API thừa. Vừa lỗi thì
        // KHÔNG quan sát lại (sentinel vẫn trong khung nhìn sẽ gọi lại ngay, thành vòng lặp) — chờ bấm "Xem thêm".
        if (observer && !state.done && !state.failed) { observer.unobserve(el('cmSentinel')); observer.observe(el('cmSentinel')); }
      }
    }
  }

  function reload() {
    state.generation += 1;
    state.page = -1;
    state.done = false;
    state.failed = false;
    state.removed = 0;
    state.loading = false;
    cards.clear();
    var params = filterParams();
    window.history.replaceState(null, '', window.location.pathname + (params.toString() ? '?' + params.toString() : ''));
    closeDrawer();
    loadNext();
  }

  var observer = null;
  if ('IntersectionObserver' in window) {
    observer = new IntersectionObserver(function (entries) {
      if (entries.some(function (e) { return e.isIntersecting; }) && state.page >= 0 && !state.failed) loadNext();
    }, { rootMargin: '600px 0px' });
    observer.observe(el('cmSentinel'));
  }
  loadMoreBtn.addEventListener('click', function () { state.failed = false; loadNext(); });

  // ── Ô soạn bài ───────────────────────────────────────────────────────────
  var composer = {
    pending: false,
    open: function () {
      if (MOBILE.matches) {
        Cards.editor.open({ title: 'Tạo bài viết', submitLabel: 'Đăng', body: '', level: '', onSubmit: publish });
        return;
      }
      el('cmComposerForm').hidden = false;
      el('cmComposerOpen').setAttribute('aria-expanded', 'true');
      el('cmComposerBody').focus();
    },
    close: function () {
      el('cmComposerForm').hidden = true;
      el('cmComposerOpen').setAttribute('aria-expanded', 'false');
      el('cmComposerBody').value = '';
      el('cmComposerLevel').value = '';
      this.update();
    },
    update: function () {
      var value = el('cmComposerBody').value;
      var counter = el('cmComposerCount');
      counter.textContent = value.length + '/' + Cards.MAX_POST;
      counter.classList.toggle('is-over', value.length > Cards.MAX_POST);
      el('cmComposerSubmit').disabled = this.pending || !value.trim() || value.length > Cards.MAX_POST;
    }
  };

  /** Đăng bài (dùng cho cả ô soạn trên desktop lẫn modal trên mobile); bài mới chèn đầu feed, không tải lại trang. */
  async function publish(body, level) {
    var item = await UI.api('/api/community/posts', { method: 'POST', body: { body: body, jlptLevel: level || null } });
    stateEl.innerHTML = '';
    cards.prepend(item);
    UI.toast('Đã đăng bài viết', 'success');
  }

  el('cmComposerOpen').addEventListener('click', function () { composer.open(); });
  el('cmComposerCancel').addEventListener('click', function () { composer.close(); });
  el('cmComposerBody').addEventListener('input', function () { composer.update(); });
  el('cmComposerForm').addEventListener('submit', async function (event) {
    event.preventDefault();
    if (composer.pending) return;   // chống bấm Đăng 2 lần
    var body = el('cmComposerBody').value;
    if (!body.trim()) { UI.toast('Nội dung bài viết không được để trống', 'warning'); return; }
    if (body.length > Cards.MAX_POST) { UI.toast('Bài viết tối đa 5000 ký tự', 'warning'); return; }
    composer.pending = true;
    composer.update();
    try {
      await publish(body, el('cmComposerLevel').value);
      composer.pending = false;
      composer.close();
    } catch (error) {
      UI.toast(error.message, 'error');
    } finally {
      composer.pending = false;
      composer.update();
    }
  });

  // ── Drawer bộ lọc (mobile) ───────────────────────────────────────────────
  function openDrawer() {
    el('cmSide').classList.add('is-open');
    el('cmDrawerBackdrop').hidden = false;
    el('cmFilterToggle').setAttribute('aria-expanded', 'true');
    fields.keyword.focus();
  }

  function closeDrawer() {
    if (!el('cmSide').classList.contains('is-open')) return;
    el('cmSide').classList.remove('is-open');
    el('cmDrawerBackdrop').hidden = true;
    el('cmFilterToggle').setAttribute('aria-expanded', 'false');
  }

  el('cmFilterToggle').addEventListener('click', openDrawer);
  el('cmFilterClose').addEventListener('click', closeDrawer);
  el('cmDrawerBackdrop').addEventListener('click', closeDrawer);
  // Escape khi đang có hộp thoại: hộp thoại tự đóng (CommunityUI), drawer giữ nguyên.
  document.addEventListener('keydown', function (event) { if (event.key === 'Escape' && !UI.hasOpenModal()) closeDrawer(); });

  form.addEventListener('submit', function (event) { event.preventDefault(); reload(); });
  // Bộ lọc nằm trong drawer (≤ 900px, cùng mốc với CSS): chờ bấm "Tìm kiếm" để kết hợp nhiều điều kiện, không tải lại
  // và đóng drawer sau mỗi lần đổi một ô.
  ['contentType', 'jlptLevel', 'minRating', 'sort'].forEach(function (key) {
    fields[key].addEventListener('change', function () { if (!DRAWER.matches) reload(); });
  });
  el('cmReset').addEventListener('click', function () {
    form.reset();
    fields.sort.value = 'newest';
    reload();
  });

  async function init() {
    el('cmComposerAvatar').innerHTML = UI.avatar(UI.currentAuthor());
    try {
      var meta = await UI.api('/api/community/meta');
      fillSelect(fields.contentType, meta.contentTypes, true);
      fillSelect(fields.jlptLevel, meta.jlptLevels.map(function (l) { return { value: l, label: l }; }), true);
      fillSelect(fields.sort, meta.sorts, false);
    } catch (error) {
      UI.toast(error.message, 'error');
    }
    readUrl();
    loadNext();
  }

  init();
})();
