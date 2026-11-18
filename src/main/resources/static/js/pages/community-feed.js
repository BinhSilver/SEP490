/**
 * Community Hub: bảng tin /community. Ô soạn bài ở đầu feed, bộ lọc (giữ trên URL để chia sẻ/quay lại) gọn trong
 * nút "Bộ lọc" cạnh ô tìm kiếm (panel thả xuống; bottom sheet trên mobile), feed trộn bài viết + nội dung chia sẻ, tải thêm khi cuộn (IntersectionObserver; trình duyệt
 * không hỗ trợ thì dùng nút "Xem thêm"). Thẻ và tương tác: community-cards.js.
 */
(function () {
  'use strict';

  var UI = window.CommunityUI;
  var Cards = window.CommunityCards;
  var PAGE_SIZE = 10;
  var MOBILE = window.matchMedia('(max-width: 640px)');          // ô soạn bài mở dạng modal
  var el = function (id) { return document.getElementById(id); };

  var feedEl = el('cmFeed');
  var stateEl = el('cmFeedState');
  var summary = el('cmSummary');
  var loadMoreBtn = el('cmLoadMore');
  var keywordEl = el('cmKeyword');
  var sortEl = el('cmSort');
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

  // ── State bộ lọc (một nguồn duy nhất) + đồng bộ URL ─────────────────────────────────────────────
  // URL dùng khoá ngắn q / type / level / rating / sort (reload hay chia sẻ link vẫn giữ bộ lọc); link cũ dùng
  // keyword / contentType / jlptLevel / minRating vẫn đọc được. API giữ nguyên tên tham số cũ.
  var Filters = (function () {
    var DEFAULTS = { q: '', type: '', level: '', rating: '', sort: 'newest' };
    var LEGACY = { q: 'keyword', type: 'contentType', level: 'jlptLevel', rating: 'minRating' };
    var API = { q: 'keyword', type: 'contentType', level: 'jlptLevel', rating: 'minRating', sort: 'sort' };
    var SORTS = ['newest', 'topRated', 'mostCommented'];
    var current = Object.assign({}, DEFAULTS);
    var api = {
      LEVELS: ['N5', 'N4', 'N3', 'N2', 'N1'],
      RATINGS: { '4': '4★ trở lên', '5': '5★' },
      get: function () { return current; },
      /** Gộp patch (đã chuẩn hoá); trả về true nếu có thay đổi. */
      set: function (patch) {
        var next = Object.assign({}, current, patch);
        next.q = String(next.q || '').trim().slice(0, 100);
        if (SORTS.indexOf(next.sort) < 0) next.sort = 'newest';
        var changed = Object.keys(DEFAULTS).some(function (k) { return next[k] !== current[k]; });
        current = next;
        return changed;
      },
      /** Số bộ lọc trong popover đang bật (không tính từ khoá và sắp xếp). */
      activeCount: function () { return ['type', 'level', 'rating'].filter(function (k) { return current[k]; }).length; },
      hasFilter: function () { return !!(current.q || api.activeCount()); },
      readUrl: function (knownTypes) {
        var params = new URLSearchParams(window.location.search);
        var read = function (k) { return params.get(k) !== null ? params.get(k) : (LEGACY[k] ? params.get(LEGACY[k]) : null); };
        var next = Object.assign({}, DEFAULTS);
        next.q = read('q') || '';
        if (knownTypes.indexOf(read('type')) >= 0) next.type = read('type');
        if (api.LEVELS.indexOf(read('level')) >= 0) next.level = read('level');
        if (api.RATINGS[read('rating')]) next.rating = read('rating');
        next.sort = read('sort') || 'newest';
        api.set(next);
      },
      writeUrl: function () {
        var params = new URLSearchParams();
        Object.keys(DEFAULTS).forEach(function (k) { if (current[k] && current[k] !== DEFAULTS[k]) params.set(k, current[k]); });
        window.history.replaceState(null, '', window.location.pathname + (params.toString() ? '?' + params.toString() : ''));
      },
      apiParams: function () {
        var params = new URLSearchParams();
        Object.keys(API).forEach(function (k) { if (current[k] && current[k] !== DEFAULTS[k]) params.set(API[k], current[k]); });
        return params;
      }
    };
    return api;
  })();

  function fillSelect(select, options, keepFirst) {
    var first = keepFirst ? select.options[0].outerHTML : '';
    select.innerHTML = first + options.map(function (o) {
      return '<option value="' + UI.esc(o.value) + '">' + UI.esc(o.label) + '</option>';
    }).join('');
  }

  function hasFilter() {
    return Filters.hasFilter();
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
    var params = Filters.apiParams();
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
    Filters.writeUrl();
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

  // ── Ô soạn bài (chữ + tối đa 4 ảnh + 1 video tải lên hoặc link YouTube) ─────────────────────────
  var MAX_IMAGES = 4, MAX_IMAGE_MB = 5, MAX_VIDEO_MB = 50;
  var IMAGE_TYPES = ['image/jpeg', 'image/png', 'image/webp'];
  var VIDEO_TYPES = ['video/mp4', 'video/webm'];
  var YOUTUBE = /^(?:https?:\/\/)?(?:www\.|m\.)?(?:youtube\.com\/(?:watch\?(?:.*&)?v=|shorts\/|embed\/|live\/)|youtu\.be\/)([A-Za-z0-9_-]{11})(?:[?&#\/].*)?$/;
  var attach = { images: [], video: null };   // { file, url } — url là object URL để xem trước, thu hồi khi bỏ

  var composer = {
    pending: false,
    open: function (focusBody) {
      el('cmComposerForm').hidden = false;
      el('cmComposer').classList.add('is-open');   // ẩn nút nhanh ở hàng trên (đã có thanh "Thêm vào bài viết")
      el('cmComposerOpen').setAttribute('aria-expanded', 'true');
      if (focusBody !== false) el('cmComposerBody').focus();
    },
    close: function () {
      el('cmComposerForm').hidden = true;
      el('cmComposer').classList.remove('is-open');
      el('cmComposerOpen').setAttribute('aria-expanded', 'false');
      el('cmComposerBody').value = '';
      el('cmComposerLevel').value = '';
      el('cmYoutubeInput').value = '';
      el('cmYoutubeRow').hidden = true;
      attach.images.forEach(function (m) { URL.revokeObjectURL(m.url); });
      if (attach.video) URL.revokeObjectURL(attach.video.url);
      attach = { images: [], video: null };
      renderAttachments();
      this.update();
    },
    update: function () {
      var value = el('cmComposerBody').value;
      var counter = el('cmComposerCount');
      counter.textContent = value.length + '/' + Cards.MAX_POST;
      counter.classList.toggle('is-over', value.length > Cards.MAX_POST);
      el('cmComposerSubmit').disabled = this.pending || !value.trim() || value.length > Cards.MAX_POST;
      el('cmComposerSubmit').textContent = this.pending ? 'Đang đăng…' : 'Đăng';
    }
  };

  function youtubeValue() { return el('cmYoutubeInput').value.trim(); }

  /** Xem trước tệp đã chọn; mỗi tệp có nút ✕ để bỏ. */
  function renderAttachments() {
    var html = attach.images.map(function (m, i) {
      return '<div class="cm-attach"><img src="' + m.url + '" alt="Ảnh đính kèm ' + (i + 1) + '">' +
        '<button type="button" class="cm-attach__remove" data-remove="image" data-index="' + i + '" aria-label="Bỏ ảnh ' + (i + 1) + '">' +
        '<i class="fa-solid fa-xmark" aria-hidden="true"></i></button></div>';
    }).join('');
    if (attach.video) {
      html += '<div class="cm-attach cm-attach--video"><video src="' + attach.video.url + '" muted preload="metadata"></video>' +
        '<span class="cm-attach__badge"><i class="fa-solid fa-play" aria-hidden="true"></i></span>' +
        '<button type="button" class="cm-attach__remove" data-remove="video" aria-label="Bỏ video">' +
        '<i class="fa-solid fa-xmark" aria-hidden="true"></i></button></div>';
    }
    el('cmAttachList').innerHTML = html;
    el('cmAttachList').hidden = !html;
  }

  function addImages(files) {
    var list = Array.from(files || []);
    for (var i = 0; i < list.length; i++) {
      var file = list[i];
      if (attach.images.length >= MAX_IMAGES) { UI.toast('Mỗi bài tối đa ' + MAX_IMAGES + ' ảnh', 'warning'); break; }
      if (IMAGE_TYPES.indexOf(file.type) < 0) { UI.toast('Ảnh chỉ nhận JPG, PNG hoặc WebP', 'warning'); continue; }
      if (file.size > MAX_IMAGE_MB * 1024 * 1024) { UI.toast('Mỗi ảnh tối đa ' + MAX_IMAGE_MB + 'MB', 'warning'); continue; }
      attach.images.push({ file: file, url: URL.createObjectURL(file) });
    }
    renderAttachments();
  }

  function setVideo(file) {
    if (!file) return;
    if (VIDEO_TYPES.indexOf(file.type) < 0) { UI.toast('Video chỉ nhận MP4 hoặc WebM', 'warning'); return; }
    if (file.size > MAX_VIDEO_MB * 1024 * 1024) { UI.toast('Video tối đa ' + MAX_VIDEO_MB + 'MB', 'warning'); return; }
    if (youtubeValue()) { UI.toast('Mỗi bài chỉ một video: hãy bỏ link YouTube trước', 'warning'); return; }
    if (attach.video) URL.revokeObjectURL(attach.video.url);
    attach.video = { file: file, url: URL.createObjectURL(file) };
    renderAttachments();
  }

  /** Đăng bài; có ảnh/video/YouTube thì gửi multipart, không thì JSON như cũ. Bài mới chèn đầu feed. */
  async function publish(body, level) {
    var youtube = youtubeValue();
    var options;
    if (attach.images.length || attach.video || youtube) {
      var form = new FormData();
      form.append('body', body);
      if (level) form.append('jlptLevel', level);
      attach.images.forEach(function (m) { form.append('images', m.file, m.file.name); });
      if (attach.video) form.append('video', attach.video.file, attach.video.file.name);
      if (youtube) form.append('youtubeUrl', youtube);
      options = { method: 'POST', form: form };
    } else {
      options = { method: 'POST', body: { body: body, jlptLevel: level || null } };
    }
    var item = await UI.api('/api/community/posts', options);
    stateEl.innerHTML = '';
    cards.prepend(item);
    UI.toast('Đã đăng bài viết', 'success');
  }

  el('cmComposerOpen').addEventListener('click', function () { composer.open(); });
  el('cmComposerCancel').addEventListener('click', function () { composer.close(); });
  el('cmComposerBody').addEventListener('input', function () { composer.update(); });
  // Nút nhanh trên thanh soạn (giống mạng xã hội): mở ô soạn rồi mở hộp chọn tệp / ô link.
  el('cmQuickPhoto').addEventListener('click', function () { composer.open(false); el('cmPickImage').click(); });
  el('cmQuickVideo').addEventListener('click', function () { composer.open(false); el('cmPickVideo').click(); });
  el('cmQuickYoutube').addEventListener('click', function () {
    composer.open(false);
    if (attach.video) { UI.toast('Mỗi bài chỉ một video: hãy bỏ video đã chọn trước', 'warning'); return; }
    el('cmYoutubeRow').hidden = false;
    el('cmYoutubeInput').focus();
  });
  el('cmToolPhoto').addEventListener('click', function () { el('cmPickImage').click(); });
  el('cmToolVideo').addEventListener('click', function () { el('cmPickVideo').click(); });
  el('cmToolYoutube').addEventListener('click', function () { el('cmQuickYoutube').click(); });
  el('cmPickImage').addEventListener('change', function () { addImages(this.files); this.value = ''; });
  el('cmPickVideo').addEventListener('change', function () { setVideo(this.files[0]); this.value = ''; });
  el('cmYoutubeClear').addEventListener('click', function () { el('cmYoutubeInput').value = ''; el('cmYoutubeRow').hidden = true; });
  el('cmAttachList').addEventListener('click', function (event) {
    var btn = event.target.closest('[data-remove]');
    if (!btn) return;
    if (btn.dataset.remove === 'video') { URL.revokeObjectURL(attach.video.url); attach.video = null; }
    else { var m = attach.images.splice(Number(btn.dataset.index), 1)[0]; if (m) URL.revokeObjectURL(m.url); }
    renderAttachments();
  });
  el('cmComposerForm').addEventListener('submit', async function (event) {
    event.preventDefault();
    if (composer.pending) return;   // chống bấm Đăng 2 lần
    var body = el('cmComposerBody').value;
    if (!body.trim()) { UI.toast('Nội dung bài viết không được để trống', 'warning'); return; }
    if (body.length > Cards.MAX_POST) { UI.toast('Bài viết tối đa 5000 ký tự', 'warning'); return; }
    if (youtubeValue() && !YOUTUBE.test(youtubeValue())) { UI.toast('Link YouTube không hợp lệ', 'warning'); return; }
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

  // ── UI bộ lọc: thanh công cụ, popover (pill) và chip đang dùng ───────────────────────────────────
  var toggleBtn = el('cmFilterToggle');
  var panel = el('cmSide');
  var backdrop = el('cmDrawerBackdrop');
  var GROUPS = { type: el('cmTypePills'), level: el('cmLevelPills'), rating: el('cmRatingPills') };
  var draft = null;           // lựa chọn trong popover, chỉ ghi vào Filters khi bấm "Áp dụng"
  var typeLabels = {};        // value → nhãn loại nội dung (từ /api/community/meta)

  function optionsFor(key) {
    if (key === 'type') return [{ value: '', label: 'Tất cả' }].concat(Object.keys(typeLabels).map(function (v) { return { value: v, label: typeLabels[v] }; }));
    if (key === 'level') return [{ value: '', label: 'Tất cả' }].concat(Filters.LEVELS.map(function (l) { return { value: l, label: l }; }));
    return [{ value: '', label: 'Tất cả' }].concat(Object.keys(Filters.RATINGS).map(function (v) { return { value: v, label: Filters.RATINGS[v] }; }));
  }

  /** Nhóm pill chọn một (role=radiogroup, roving tabindex: Tab vào nhóm, mũi tên đổi lựa chọn). */
  function renderPills(key) {
    var value = draft[key];
    GROUPS[key].innerHTML = optionsFor(key).map(function (o) {
      var on = o.value === value;
      return '<button type="button" class="cm-pill" role="radio" aria-checked="' + on + '" tabindex="' + (on ? 0 : -1) +
        '" data-value="' + UI.esc(o.value) + '">' + UI.esc(o.label) + '</button>';
    }).join('');
  }

  /** Đổi lựa chọn tại chỗ (không dựng lại DOM, để focus không rơi khỏi popover). */
  function pick(key, value, focus) {
    draft[key] = value;
    GROUPS[key].querySelectorAll('.cm-pill').forEach(function (pill) {
      var on = pill.dataset.value === value;
      pill.setAttribute('aria-checked', String(on));
      pill.tabIndex = on ? 0 : -1;
      if (on && focus) pill.focus();
    });
  }

  Object.keys(GROUPS).forEach(function (key) {
    GROUPS[key].addEventListener('click', function (event) {
      var pill = event.target.closest('.cm-pill');
      if (pill) pick(key, pill.dataset.value, true);
    });
    GROUPS[key].addEventListener('keydown', function (event) {
      var step = { ArrowRight: 1, ArrowDown: 1, ArrowLeft: -1, ArrowUp: -1 }[event.key];
      if (!step) return;
      event.preventDefault();
      var pills = Array.from(GROUPS[key].querySelectorAll('.cm-pill'));
      var at = pills.indexOf(document.activeElement);
      pick(key, pills[(at + step + pills.length) % pills.length].dataset.value, true);
    });
  });

  function isOpen() { return panel.classList.contains('is-open'); }

  function openPanel() {
    draft = { type: Filters.get().type, level: Filters.get().level, rating: Filters.get().rating };
    Object.keys(GROUPS).forEach(renderPills);
    panel.classList.add('is-open');
    backdrop.hidden = false;
    toggleBtn.setAttribute('aria-expanded', 'true');
    var first = GROUPS.type.querySelector('[aria-checked="true"]') || GROUPS.type.querySelector('.cm-pill');
    if (first) first.focus();
  }

  function closePanel(returnFocus) {
    if (!isOpen()) return;
    panel.classList.remove('is-open');
    backdrop.hidden = true;
    toggleBtn.setAttribute('aria-expanded', 'false');
    if (returnFocus) toggleBtn.focus();
  }

  /** Huy hiệu số bộ lọc + hàng chip; gọi sau mỗi lần Filters đổi. */
  function renderFilterUi() {
    var f = Filters.get();
    var badge = el('cmFilterCount');
    badge.textContent = Filters.activeCount();
    badge.hidden = Filters.activeCount() === 0;
    var chips = [];
    if (f.q) chips.push({ key: 'q', label: '“' + f.q + '”' });
    if (f.type) chips.push({ key: 'type', label: typeLabels[f.type] || f.type });
    if (f.level) chips.push({ key: 'level', label: f.level });
    if (f.rating) chips.push({ key: 'rating', label: Filters.RATINGS[f.rating] });
    var box = el('cmChips');
    box.hidden = chips.length === 0;
    box.innerHTML = chips.map(function (c) {
      return '<span class="cm-chip-filter">' + UI.esc(c.label) +
        '<button type="button" data-key="' + c.key + '" aria-label="Bỏ bộ lọc ' + UI.esc(c.label) + '"><i class="fa-solid fa-xmark" aria-hidden="true"></i></button></span>';
    }).join('');
  }

  /** Ghi state → URL → giao diện → tải lại feed (API và cách tải giữ nguyên). */
  function applyFilters(patch) {
    if (!Filters.set(patch)) return;
    keywordEl.value = Filters.get().q;
    sortEl.value = Filters.get().sort;
    renderFilterUi();
    reload();
  }

  toggleBtn.addEventListener('click', function () { if (isOpen()) closePanel(true); else openPanel(); });
  el('cmFilterClose').addEventListener('click', function () { closePanel(true); });
  backdrop.addEventListener('click', function () { closePanel(false); });
  el('cmApply').addEventListener('click', function () {
    closePanel(true);
    applyFilters(draft);
  });
  el('cmReset').addEventListener('click', function () {
    closePanel(true);
    applyFilters({ type: '', level: '', rating: '' });
  });
  // Escape: hộp thoại (CommunityUI) tự đóng trước; không có hộp thoại thì đóng popover và trả focus về nút.
  document.addEventListener('keydown', function (event) {
    if (event.key === 'Escape' && isOpen() && !UI.hasOpenModal()) closePanel(true);
  });
  // Focus rời popover (Tab ra ngoài) trên màn rộng → đóng, không trả focus.
  el('cmFilterWrap').addEventListener('focusout', function (event) {
    if (isOpen() && !MOBILE.matches && !el('cmFilterWrap').contains(event.relatedTarget)) closePanel(false);
  });

  // Từ khoá: debounce 300ms, không còn nút "Tìm kiếm"; Enter áp dụng ngay.
  var keywordTimer = null;
  keywordEl.addEventListener('input', function () {
    clearTimeout(keywordTimer);
    keywordTimer = setTimeout(function () { applyFilters({ q: keywordEl.value }); }, 300);
  });
  keywordEl.addEventListener('keydown', function (event) {
    if (event.key !== 'Enter') return;
    event.preventDefault();
    clearTimeout(keywordTimer);
    applyFilters({ q: keywordEl.value });
  });
  // Sắp xếp: áp dụng ngay.
  sortEl.addEventListener('change', function () { applyFilters({ sort: sortEl.value }); });
  // Chip: bỏ một bộ lọc rồi tải lại.
  el('cmChips').addEventListener('click', function (event) {
    var btn = event.target.closest('button[data-key]');
    if (!btn) return;
    var patch = {};
    patch[btn.dataset.key] = '';
    applyFilters(patch);
    toggleBtn.focus();
  });

  async function init() {
    var me = UI.currentAuthor();
    el('cmComposerAvatar').innerHTML = UI.avatar(me);
    // "Hưng ơi, bạn đang nghĩ gì thế?" — tên gọi = từ cuối của họ tên (cách gọi tên tiếng Việt).
    var firstName = me.displayName && me.displayName !== '?' ? me.displayName.trim().split(/\s+/).pop() : '';
    el('cmComposerPrompt').textContent = (firstName ? firstName + ' ơi, ' : '') + 'bạn đang nghĩ gì thế?';
    try {
      var meta = await UI.api('/api/community/meta');
      meta.contentTypes.forEach(function (t) { typeLabels[t.value] = t.label; });
      Filters.LEVELS = meta.jlptLevels;
      fillSelect(sortEl, meta.sorts, false);
    } catch (error) {
      UI.toast(error.message, 'error');
    }
    Filters.readUrl(Object.keys(typeLabels));
    keywordEl.value = Filters.get().q;
    sortEl.value = Filters.get().sort;
    renderFilterUi();
    loadNext();
  }

  init();
})();
