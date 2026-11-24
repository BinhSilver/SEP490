/**
 * Community Hub: thẻ trên feed (bài viết POST + nội dung chia sẻ) dùng chung cho /community và /community/posts/{id}.
 * Vẽ thẻ, menu "…", bình luận inline (Enter gửi, Shift+Enter xuống dòng), báo cáo, sửa/xoá bài, chia sẻ, đánh giá sao.
 * Mọi nội dung người dùng đều đi qua UI.esc; thao tác nào cũng được BE kiểm tra quyền lại.
 * Cần markup: #cmReportModal (báo cáo), #cmEditor (soạn/sửa bài), #cmConfirm (xác nhận) — xem templates/community.
 */
(function (global) {
  'use strict';

  var UI = global.CommunityUI;
  var MAX_POST = 5000;
  var MAX_COMMENT = 1000;
  var COMMENT_PAGE = 10;
  var LEVELS = ['N5', 'N4', 'N3', 'N2', 'N1'];
  var reasonsPromise = null;

  function el(id) { return document.getElementById(id); }
  function keyOf(item) { return item.contentType + ':' + item.contentId; }
  function isPost(item) { return item.contentType === 'POST'; }

  function reasons() {
    if (!reasonsPromise) {
      reasonsPromise = UI.api('/api/community/meta').then(function (meta) { return meta.reasons; })
        .catch(function () { reasonsPromise = null; return [{ value: 'OTHER', label: 'Khác' }]; });
    }
    return reasonsPromise;
  }

  function shareUrl(item) {
    return global.location.origin + UI.detailUrl(item.contentType, item.contentId);
  }

  function levelChip(level) {
    return '<span class="cm-chip cm-chip--level">' + UI.esc(level || 'Chung') + '</span>';
  }

  // ── Trình soạn bài (modal): đăng bài trên mobile + sửa bài ────────────────
  var editor = {
    pending: false,
    open: function (options) {
      var modal = el('cmEditor');
      if (!modal) return;
      this.options = options;
      el('cmEditorTitle').textContent = options.title;
      el('cmEditorSubmit').textContent = options.submitLabel;
      el('cmEditorBody').value = options.body || '';
      el('cmEditorLevel').value = options.level || '';
      this.update();
      // options.fallback(): phần tử nhận focus khi đóng nếu nơi mở không còn (vd thẻ đã vẽ lại sau khi lưu).
      UI.openModal(modal, { focus: el('cmEditorBody'), onEscape: function () { editor.close(); },
        fallback: options.fallback });
    },
    close: function () {
      this.options = null;
      UI.closeModal(el('cmEditor'));
    },
    update: function () {
      var value = el('cmEditorBody').value;
      var length = value.length;
      var counter = el('cmEditorCount');
      counter.textContent = length + '/' + MAX_POST;
      counter.classList.toggle('is-over', length > MAX_POST);
      el('cmEditorSubmit').disabled = this.pending || !value.trim() || length > MAX_POST;
    },
    submit: async function (event) {
      event.preventDefault();
      if (!this.options || this.pending) return;
      var body = el('cmEditorBody').value;
      if (!body.trim()) { UI.toast('Nội dung bài viết không được để trống', 'warning'); return; }
      if (body.length > MAX_POST) { UI.toast('Bài viết tối đa 5000 ký tự', 'warning'); return; }
      this.pending = true;
      this.update();
      try {
        await this.options.onSubmit(body, el('cmEditorLevel').value);
        this.close();
      } catch (error) {
        UI.toast(error.message, 'error');
      } finally {
        this.pending = false;
        if (el('cmEditor') && !el('cmEditor').hidden) this.update();
      }
    }
  };

  function bindEditor() {
    var modal = el('cmEditor');
    if (!modal || modal.dataset.bound) return;
    modal.dataset.bound = '1';
    el('cmEditorForm').addEventListener('submit', function (e) { editor.submit(e); });
    el('cmEditorBody').addEventListener('input', function () { editor.update(); });
    modal.addEventListener('click', function (e) { if (e.target.closest('[data-cm-close]')) editor.close(); });
    // Escape / giữ focus trong hộp: CommunityUI.openModal (chỉ đóng hộp trên cùng).
  }

  // ── Báo cáo ───────────────────────────────────────────────────────────────
  var report = { target: null };

  async function openReport(target) {
    var list = await reasons();
    report.target = target;
    el('cmReportTitle').textContent = target.type === 'COMMENT' ? 'Báo cáo bình luận'
      : target.isPost ? 'Báo cáo bài viết' : 'Báo cáo nội dung';
    el('cmReportTarget').textContent = target.label;
    el('cmReportReason').innerHTML = list.map(function (r) {
      return '<option value="' + UI.esc(r.value) + '">' + UI.esc(r.label) + '</option>';
    }).join('');
    el('cmReportNote').value = '';
    UI.openModal(el('cmReportModal'), { focus: el('cmReportReason'), onEscape: closeReport, fallback: target.fallback });
  }

  function closeReport() {
    report.target = null;
    UI.closeModal(el('cmReportModal'));
  }

  function bindReport() {
    var modal = el('cmReportModal');
    if (!modal || modal.dataset.bound) return;
    modal.dataset.bound = '1';
    el('cmReportForm').addEventListener('submit', async function (event) {
      event.preventDefault();
      var target = report.target;
      if (!target) return;
      var payload = { targetType: target.type, reason: el('cmReportReason').value, note: el('cmReportNote').value.trim() || null };
      if (target.type === 'COMMENT') payload.targetId = target.id;
      else { payload.contentType = target.contentType; payload.contentId = Number(target.contentId); }
      var button = el('cmReportSubmit');
      button.disabled = true;
      try {
        var result = await UI.api('/api/community/reports', { method: 'POST', body: payload });
        closeReport();
        UI.toast(result && result.message ? result.message : 'Đã gửi báo cáo. Cảm ơn bạn!', 'success');
        target.done(result);
      } catch (error) {
        UI.toast(error.message, error.status === 409 ? 'warning' : 'error');
        if (error.status === 409) closeReport();
      } finally {
        button.disabled = false;
      }
    });
    modal.addEventListener('click', function (e) { if (e.target.closest('[data-cm-close]')) closeReport(); });
  }

  // ── Gỡ trực tiếp (MODERATOR/ADMIN): lý do bắt buộc 1-500 ký tự ─────────────
  var MAX_REASON = 500;
  var removal = { target: null, pending: false };

  function openRemove(target) {
    removal.target = target;
    el('cmRemoveTitle').textContent = target.type === 'COMMENT' ? 'Gỡ bình luận' : 'Gỡ bài viết';
    el('cmRemoveTarget').textContent = target.label;
    el('cmRemoveReason').value = '';
    updateRemove();
    UI.openModal(el('cmRemoveModal'), { focus: el('cmRemoveReason'), onEscape: closeRemove, fallback: target.fallback });
  }

  function closeRemove() {
    removal.target = null;
    UI.closeModal(el('cmRemoveModal'));
  }

  function updateRemove() {
    var value = el('cmRemoveReason').value;
    el('cmRemoveCount').textContent = value.length + '/' + MAX_REASON;
    el('cmRemoveCount').classList.toggle('is-over', value.length > MAX_REASON);
    el('cmRemoveSubmit').disabled = removal.pending || !value.trim() || value.length > MAX_REASON;
  }

  function bindRemove() {
    var modal = el('cmRemoveModal');
    if (!modal || modal.dataset.bound) return;
    modal.dataset.bound = '1';
    el('cmRemoveReason').addEventListener('input', updateRemove);
    el('cmRemoveForm').addEventListener('submit', async function (event) {
      event.preventDefault();
      var target = removal.target;
      var reason = el('cmRemoveReason').value.trim();
      if (!target || removal.pending) return;
      if (!reason) { UI.toast('Vui lòng nhập lý do gỡ', 'warning'); return; }
      if (reason.length > MAX_REASON) { UI.toast('Lý do tối đa 500 ký tự', 'warning'); return; }
      removal.pending = true;
      updateRemove();
      try {
        var url = target.type === 'COMMENT'
          ? '/api/community/comments/' + encodeURIComponent(target.id) + '/remove'
          : '/api/community/posts/' + encodeURIComponent(target.id) + '/remove';
        var result = await UI.api(url, { method: 'POST', body: { reason: reason } });
        closeRemove();
        UI.toast(target.type === 'COMMENT' ? 'Đã gỡ bình luận' : 'Đã gỡ bài viết', 'success');
        target.done(result);
      } catch (error) {
        UI.toast(error.message, 'error');
      } finally {
        removal.pending = false;
        if (!el('cmRemoveModal').hidden) updateRemove();
      }
    });
    modal.addEventListener('click', function (e) { if (e.target.closest('[data-cm-close]')) closeRemove(); });
  }

  // ── HTML ─────────────────────────────────────────────────────────────────
  /** Nút "Lưu" / "Đã lưu" ở hàng hành động dưới bài (cùng thao tác với mục "Lưu bài" trong menu "…"). */
  function saveActionHtml(item) {
    return '<button type="button" class="cm-action' + (item.saved ? ' is-saved' : '') + '" data-act="save" data-role="save-action" aria-pressed="' + !!item.saved + '">' +
      '<i class="' + (item.saved ? 'fa-solid' : 'fa-regular') + ' fa-bookmark" aria-hidden="true"></i> ' + (item.saved ? 'Đã lưu' : 'Lưu') + '</button>';
  }

  function menuHtml(item) {
    // "Lưu bài" (xem lại sau, mục "Đã lưu") có cho mọi mục, nên menu "…" luôn có ít nhất một lựa chọn.
    var entries = item.saved
      ? '<button type="button" role="menuitem" data-act="save"><i class="fa-solid fa-bookmark" aria-hidden="true"></i> Bỏ lưu</button>'
      : '<button type="button" role="menuitem" data-act="save"><i class="fa-regular fa-bookmark" aria-hidden="true"></i> Lưu bài</button>';
    if (item.canEdit) entries += '<button type="button" role="menuitem" data-act="edit"><i class="fa-regular fa-pen-to-square" aria-hidden="true"></i> Sửa bài viết</button>';
    // Bài đang tạm ẩn chờ kiểm duyệt: BE không cho xoá (kiểm duyệt viên cần nội dung) nên không hiện nút.
    if (item.mine && isPost(item) && item.status !== 'TEMPORARILY_HIDDEN') entries += '<button type="button" role="menuitem" data-act="delete" class="is-danger"><i class="fa-regular fa-trash-can" aria-hidden="true"></i> Xoá bài viết</button>';
    if (item.canReport) entries += '<button type="button" role="menuitem" data-act="report"><i class="fa-regular fa-flag" aria-hidden="true"></i> Báo cáo</button>';
    if (item.canModerate) entries += '<button type="button" role="menuitem" data-act="remove-post" class="is-danger"><i class="fa-solid fa-ban" aria-hidden="true"></i> Gỡ bài (kiểm duyệt)</button>';
    if (!entries) return '';
    return '<div class="cm-menu-wrap">' +
      '<button type="button" class="cm-icon-btn" data-act="menu" aria-haspopup="menu" aria-expanded="false" aria-label="Tuỳ chọn">' +
        '<i class="fa-solid fa-ellipsis" aria-hidden="true"></i></button>' +
      '<div class="cm-menu" role="menu" hidden>' + entries + '</div></div>';
  }

  function statusNotice(item) {
    if (!item.status || item.status === 'PUBLISHED') return '';
    var removed = item.status === 'REMOVED';
    var text = removed ? 'Bài viết đã bị gỡ khỏi Cộng đồng, chỉ bạn và kiểm duyệt viên còn xem được.'
      : 'Bài đang tạm ẩn do bị báo cáo, chờ kiểm duyệt viên xem xét.';
    var reason = removed && item.moderationNote
      ? '<span class="cm-banner__reason">Lý do: ' + UI.esc(item.moderationNote) + '</span>' : '';
    return '<p class="cm-banner' + (removed ? ' cm-banner--danger' : '') + '" role="status">' + text + reason + '</p>';
  }

  /**
   * Ảnh / video của bài viết: ảnh xếp lưới (1–4, bấm mở ảnh gốc ở tab mới), video tải lên dùng trình phát của trình
   * duyệt (preload=metadata, không tải cả tệp), YouTube nhúng bản youtube-nocookie, lazy.
   */
  function mediaHtml(media) {
    if (!media || !media.length) return '';
    var images = media.filter(function (m) { return m.kind === 'IMAGE'; });
    var html = '';
    if (images.length) {
      html += '<div class="cm-media-grid cm-media-grid--' + Math.min(images.length, 4) + '">' + images.map(function (m, i) {
        return '<a class="cm-media-grid__item" href="' + UI.esc(m.url) + '" target="_blank" rel="noopener">' +
          '<img src="' + UI.esc(m.url) + '" alt="Ảnh ' + (i + 1) + ' trong bài viết" loading="lazy" decoding="async"></a>';
      }).join('') + '</div>';
    }
    media.forEach(function (m) {
      if (m.kind === 'VIDEO') {
        html += '<video class="cm-media-video" src="' + UI.esc(m.url) + '" controls preload="metadata" playsinline></video>';
      } else if (m.kind === 'YOUTUBE') {
        html += '<div class="cm-media-youtube"><iframe src="' + UI.esc(m.url) + '" title="Video YouTube" loading="lazy"' +
          ' allow="accelerometer; encrypted-media; gyroscope; picture-in-picture; web-share" allowfullscreen' +
          ' referrerpolicy="strict-origin-when-cross-origin"></iframe></div>';
      }
    });
    return '<div class="cm-media">' + html + '</div>';
  }

  function previewHtml(item) {
    var thumb = item.thumbnailUrl
      ? '<img src="' + UI.esc(UI.safeUrl(item.thumbnailUrl)) + '" alt="" loading="lazy">'
      : '<i class="fa-solid ' + (item.contentType === 'SHADOWING_VIDEO' ? 'fa-film' : 'fa-layer-group') + '" aria-hidden="true"></i>';
    return '<a class="cm-preview" href="' + UI.esc(UI.safeUrl(item.link)) + '">' +
      '<span class="cm-preview__thumb">' + thumb + '</span>' +
      '<span class="cm-preview__main">' +
        '<span class="cm-preview__title">' + UI.esc(item.title) + '</span>' +
        '<span class="cm-preview__chips"><span class="cm-chip">' + UI.esc(item.contentTypeLabel) + '</span>' +
          (item.jlptLevel ? levelChip(item.jlptLevel) : '') + '</span>' +
        '<span class="cm-preview__rating" data-role="rating">' + ratingSummary(item) + '</span>' +
      '</span>' +
      '<i class="fa-solid fa-chevron-right cm-preview__go" aria-hidden="true"></i>' +
    '</a>';
  }

  function ratingSummary(item) {
    return UI.stars(item.ratingAvg) + ' <b>' + Number(item.ratingAvg || 0).toFixed(1) + '</b> (' + Number(item.ratingCount || 0) + ')' +
      (item.myRating ? ' · <span class="cm-mine">Bạn: ' + Number(item.myRating) + '★</span>' : '');
  }

  function rateRowHtml(item) {
    var html = '';
    for (var i = 1; i <= 5; i++) {
      var on = item.myRating && i <= item.myRating;
      html += '<button type="button" class="cm-star' + (on ? ' is-on' : '') + '" data-act="rate" data-score="' + i + '" aria-label="' + i + ' sao">' +
        '<i class="' + (on ? 'fa-solid' : 'fa-regular') + ' fa-star" aria-hidden="true"></i></button>';
    }
    return '<div class="cm-rate-row" data-role="rate" hidden><span>Chấm điểm:</span><div class="cm-stars-input">' + html + '</div></div>';
  }

  function commentHtml(c) {
    var actions = '';
    if (c.mine && c.canDelete) actions += '<button type="button" class="cm-link cm-link--danger" data-act="del-comment">Xoá</button>';
    if (c.canReport) actions += '<button type="button" class="cm-link" data-act="report-comment">Báo cáo</button>';
    if (c.canRemove) actions += '<button type="button" class="cm-link cm-link--danger" data-act="remove-comment">Gỡ</button>';
    // Kiểm duyệt viên thấy cả bình luận đã gỡ (danh sách đầy đủ): ghi rõ trạng thái như trang chi tiết cũ.
    var label = c.status === 'TEMPORARILY_HIDDEN' ? ' <span class="cm-badge cm-badge--warn">Đang bị ẩn</span>'
      : c.status === 'REMOVED' ? ' <span class="cm-badge cm-badge--danger">Đã gỡ</span>' : '';
    return '<li class="cm-reply' + (c.hidden ? ' is-hidden' : '') + '" data-comment="' + Number(c.id) + '">' +
      UI.avatar(c.author) +
      '<div class="cm-reply__main">' +
        '<div class="cm-reply__bubble"><b>' + UI.esc(c.author ? c.author.displayName : '') + '</b>' + label +
          '<p class="cm-reply__text">' + UI.esc(c.body) + '</p></div>' +
        '<div class="cm-reply__meta">' + UI.timeTag(c.createdAt) + (c.edited ? ' · đã sửa' : '') + actions + '</div>' +
      '</div></li>';
  }

  function cardHtml(item) {
    var post = isPost(item);
    var author = item.author || { displayName: 'Ẩn danh' };
    var meta = UI.timeTag(item.createdAt) +
      (post ? ' · ' + levelChip(item.jlptLevel) : ' · <span class="cm-muted">đã chia sẻ ' + UI.esc((item.contentTypeLabel || '').toLowerCase()) + '</span>') +
      (item.edited ? ' · <span class="cm-muted">(đã chỉnh sửa)</span>' : '');
    var body = post
      ? '<div class="cm-post__text" data-role="text">' + UI.esc(item.description) + '</div>' +
        '<button type="button" class="cm-more" data-act="expand" hidden>Xem thêm</button>' + mediaHtml(item.media)
      :(item.description ? '<div class="cm-post__text cm-post__text--short" data-role="text">' + UI.esc(item.description) + '</div>' : '') +
        previewHtml(item);
    var count = Number(item.commentCount || 0);
    var actions =
      (item.canRate ? '<button type="button" class="cm-action" data-act="toggle-rate"><i class="fa-regular fa-star" aria-hidden="true"></i> Đánh giá</button>' : '') +
      '<button type="button" class="cm-action" data-act="focus-comment"' + (item.canComment ? '' : ' disabled') + '><i class="fa-regular fa-comment" aria-hidden="true"></i> Bình luận</button>' +
      saveActionHtml(item) +
      '<button type="button" class="cm-action" data-act="share"><i class="fa-solid fa-share-nodes" aria-hidden="true"></i> Chia sẻ</button>';
    var me = UI.currentAuthor();
    var composer = item.canComment
      ? '<form class="cm-reply-form" data-role="reply-form">' + UI.avatar(me) +
          '<div class="cm-reply-form__box"><label class="cm-visually-hidden">Viết bình luận</label>' +
            '<textarea rows="1" maxlength="' + MAX_COMMENT + '" placeholder="Viết bình luận… (Enter để gửi, Shift+Enter xuống dòng)" data-role="reply-input"></textarea>' +
            '<span class="cm-reply-form__count" data-role="reply-count" hidden></span>' +
            '<button type="submit" class="cm-icon-btn cm-icon-btn--send" aria-label="Gửi bình luận"><i class="fa-solid fa-paper-plane" aria-hidden="true"></i></button>' +
          '</div></form>'
      : '';
    return '<article class="cm-post" data-key="' + UI.esc(keyOf(item)) + '">' +
      statusNotice(item) +
      '<header class="cm-post__head">' + UI.avatar(author) +
        '<div class="cm-post__who"><b>' + UI.esc(author.displayName) + (item.mine ? ' <span class="cm-muted">(bạn)</span>' : '') + '</b>' +
          '<div class="cm-post__meta">' + meta + '</div></div>' +
        menuHtml(item) +
      '</header>' +
      '<div class="cm-post__body">' + body + '</div>' +
      '<div class="cm-post__stats"><button type="button" class="cm-link" data-act="more-comments" data-role="count">' + count + ' bình luận</button></div>' +
      '<div class="cm-post__actions">' + actions + '</div>' +
      (item.canRate ? rateRowHtml(item) : '') +
      '<div class="cm-thread">' +
        '<button type="button" class="cm-link cm-thread__more" data-act="more-comments" data-role="more" hidden>Xem thêm bình luận</button>' +
        '<ul class="cm-replies" data-role="replies"></ul>' + composer +
      '</div>' +
    '</article>';
  }

  // ── Bộ điều khiển một danh sách thẻ ──────────────────────────────────────
  function create(container, options) {
    options = options || {};
    var items = {};
    var threads = {};

    bindReport();
    bindEditor();
    bindRemove();

    function cardOf(node) { return node.closest('.cm-post'); }
    function itemOf(card) { return items[card.dataset.key]; }

    function thread(item) {
      var key = keyOf(item);
      if (!threads[key]) {
        // removed: số bình luận đã bỏ khỏi danh sách phía server từ lần tải trang gần nhất (xoá / bị ẩn do báo cáo) —
        // phân trang theo offset nên trang sau phải lùi lại đúng số đó, không thì bỏ sót bình luận.
        threads[key] = { comments: {}, page: -1, totalPages: null, loading: false, removed: 0 };
        (item.recentComments || []).forEach(function (c) { threads[key].comments[c.id] = c; });
      }
      return threads[key];
    }

    function renderThread(card, item) {
      var state = thread(item);
      var list = Object.keys(state.comments).map(function (id) { return state.comments[id]; })
        .sort(function (a, b) { return new Date(a.createdAt) - new Date(b.createdAt) || a.id - b.id; });
      card.querySelector('[data-role="replies"]').innerHTML = list.map(commentHtml).join('');
      // commentCount chỉ đếm bình luận PUBLISHED, còn danh sách inline có thể có bình luận đang ẩn của chính mình.
      var shownPublished = list.filter(function (c) { return c.status === 'PUBLISHED'; }).length;
      var hasMore = state.totalPages === null ? Number(item.commentCount || 0) > shownPublished : state.page + 1 < state.totalPages;
      card.querySelector('[data-role="more"]').hidden = !hasMore;
      card.querySelector('[data-role="count"]').textContent = Number(item.commentCount || 0) + ' bình luận';
    }

    /** Bài dài hơn 5 dòng: hiện "Xem thêm" (đo sau khi vẽ). */
    function clamp(card) {
      var text = card.querySelector('.cm-post__text:not(.cm-post__text--short)');
      var more = card.querySelector('[data-act="expand"]');
      if (!text || !more) return;
      more.hidden = !(text.scrollHeight > text.clientHeight + 2);
    }

    function mount(card, item) {
      renderThread(card, item);
      clamp(card);
    }

    // "Xem thêm" đo lại khi bề rộng đổi (xoay máy, thu nhỏ cửa sổ) hoặc font tải xong; thẻ đang mở rộng thì giữ nguyên.
    var reclampTimer = null;
    function reclampAll() {
      container.querySelectorAll('.cm-post').forEach(function (card) {
        var text = card.querySelector('[data-role="text"]');
        if (text && !text.classList.contains('is-expanded')) clamp(card);
      });
    }
    global.addEventListener('resize', function () {
      clearTimeout(reclampTimer);
      reclampTimer = setTimeout(reclampAll, 150);
    });
    if (document.fonts && document.fonts.ready) document.fonts.ready.then(reclampAll);

    /** Vẽ lại menu "…" theo item (vd vừa báo cáo thì bỏ mục "Báo cáo"); menu không còn mục nào thì bỏ luôn nút "…". */
    function refreshMenu(card, item) {
      var wrap = card.querySelector('.cm-post__head .cm-menu-wrap');
      if (!wrap) return;
      var html = menuHtml(item);
      if (html) wrap.outerHTML = html; else wrap.remove();
    }

    /** Nơi nhận focus khi hộp thoại đóng mà nút mở nó không còn (thẻ vẽ lại / bình luận vẽ lại). */
    function focusIn(item, prefer) {
      return function () {
        var card = container.querySelector('.cm-post[data-key="' + CSS.escape(keyOf(item)) + '"]');
        if (!card) return null;
        return card.querySelector(prefer) || card.querySelector('[data-role="reply-input"]') ||
          card.querySelector('[data-act="share"]');
      };
    }

    function nodeFor(item) {
      var holder = document.createElement('div');
      holder.innerHTML = cardHtml(item);
      return holder.firstChild;
    }

    function add(list, prepend) {
      var fragment = document.createDocumentFragment();
      // Feed phân trang theo offset: bài vừa đăng (chèn đầu) đẩy lệch trang sau → bỏ thẻ đã có, không vẽ trùng.
      var nodes = list.filter(function (item) { return !items[keyOf(item)]; }).map(function (item) {
        items[keyOf(item)] = item;
        var node = nodeFor(item);
        fragment.appendChild(node);
        return { node: node, item: item };
      });
      if (prepend) container.insertBefore(fragment, container.firstChild);
      else container.appendChild(fragment);
      nodes.forEach(function (n) { mount(n.node, n.item); });
    }

    function replace(item) {
      var key = keyOf(item);
      var old = container.querySelector('.cm-post[data-key="' + CSS.escape(key) + '"]');
      var keep = threads[key];
      items[key] = item;
      if (keep) (item.recentComments || []).forEach(function (c) { keep.comments[c.id] = c; });
      var node = nodeFor(item);
      if (old) old.replaceWith(node); else container.insertBefore(node, container.firstChild);
      mount(node, item);
    }

    function remove(card) {
      delete items[card.dataset.key];
      delete threads[card.dataset.key];
      card.remove();
      if (options.onRemoved) options.onRemoved();
    }

    async function loadMoreComments(card, item) {
      var state = thread(item);
      if (state.loading) return;
      state.loading = true;
      try {
        // Lùi offset theo số bình luận đã bỏ: tải lại phần trùng (bỏ qua nhờ map theo id) thay vì bỏ sót.
        var offset = Math.max(0, (state.page + 1) * COMMENT_PAGE - state.removed);
        var next = Math.floor(offset / COMMENT_PAGE);
        var data = await UI.api('/api/community/contents/' + encodeURIComponent(item.contentType) + '/' +
          encodeURIComponent(item.contentId) + '/comments?page=' + next + '&size=' + COMMENT_PAGE);
        state.page = next;
        state.removed = 0;
        state.totalPages = data.totalPages;
        data.items.forEach(function (c) { state.comments[c.id] = c; });
        renderThread(card, item);
      } catch (error) {
        UI.toast(error.message, 'error');
      } finally {
        state.loading = false;
      }
    }

    async function sendComment(card, item) {
      var input = card.querySelector('[data-role="reply-input"]');
      var form = card.querySelector('[data-role="reply-form"]');
      if (!input || form.dataset.pending) return;
      var text = input.value.trim();
      if (!text) { UI.toast('Nội dung bình luận không được để trống', 'warning'); return; }
      if (text.length > MAX_COMMENT) { UI.toast('Bình luận tối đa 1000 ký tự', 'warning'); return; }
      form.dataset.pending = '1';
      input.disabled = true;
      try {
        var created = await UI.api('/api/community/contents/' + encodeURIComponent(item.contentType) + '/' +
          encodeURIComponent(item.contentId) + '/comments', { method: 'POST', body: { body: text } });
        thread(item).comments[created.id] = created;
        item.commentCount = Number(item.commentCount || 0) + 1;
        input.value = '';
        autoGrow(input);
        updateReplyCount(card);
        renderThread(card, item);
      } catch (error) {
        UI.toast(error.message, 'error');
      } finally {
        delete form.dataset.pending;
        input.disabled = false;
        input.focus();
      }
    }

    async function deleteComment(card, item, id) {
      var ok = await UI.confirmDialog({ title: 'Xoá bình luận', message: 'Bình luận sẽ bị xoá và không khôi phục được.', confirmLabel: 'Xoá' });
      if (!ok) return;
      try {
        await UI.api('/api/community/comments/' + id, { method: 'DELETE' });
        var state = thread(item);
        var removed = state.comments[id];
        delete state.comments[id];
        state.removed += 1;
        if (removed && removed.status === 'PUBLISHED') item.commentCount = Math.max(0, Number(item.commentCount || 0) - 1);
        renderThread(card, item);
        UI.toast('Đã xoá bình luận', 'success');
      } catch (error) {
        UI.toast(error.message, 'error');
      }
    }

    async function deletePost(card, item) {
      var ok = await UI.confirmDialog({ title: 'Xoá bài viết', message: 'Bài viết và các bình luận sẽ không còn hiển thị. Bạn chắc chắn muốn xoá?', confirmLabel: 'Xoá bài' });
      if (!ok) return;
      try {
        await UI.api('/api/community/posts/' + encodeURIComponent(item.contentId), { method: 'DELETE' });
        UI.toast('Đã xoá bài viết', 'success');
        if (options.onPostDeleted) options.onPostDeleted(item);
        else remove(card);
      } catch (error) {
        UI.toast(error.message, 'error');
      }
    }

    /** Lưu / Bỏ lưu. Đang xem "Đã lưu" (options.removeOnUnsave) mà bỏ lưu thì bỏ thẻ khỏi danh sách. */
    async function toggleSave(card, item) {
      if (item.saving) return;   // đang gửi: bỏ qua lần bấm thứ hai (tránh lưu rồi bỏ lưu ngay)
      item.saving = true;
      var path = '/api/community/contents/' + encodeURIComponent(item.contentType) + '/' +
        encodeURIComponent(item.contentId) + '/save';
      try {
        var result = await UI.api(path, { method: item.saved ? 'DELETE' : 'PUT' });
        item.saved = !!(result && result.saved);
        UI.toast(item.saved ? 'Đã lưu. Xem lại trong mục “Đã lưu” ở thanh tìm kiếm.' : 'Đã bỏ lưu', 'success');
        if (!item.saved && options.removeOnUnsave && options.removeOnUnsave()) remove(card);
        else {
          refreshMenu(card, item);
          var action = card.querySelector('[data-role="save-action"]');
          if (action) {
            var focused = document.activeElement === action;
            action.outerHTML = saveActionHtml(item);
            if (focused) card.querySelector('[data-role="save-action"]').focus();
          }
        }
      } catch (error) {
        UI.toast(error.message, 'error');
      } finally {
        item.saving = false;
      }
    }

    function editPost(item) {
      editor.open({
        title: 'Sửa bài viết', submitLabel: 'Lưu', body: item.description, level: item.jlptLevel || '',
        fallback: focusIn(item, '[data-act="menu"]'),
        onSubmit: async function (body, level) {
          var updated = await UI.api('/api/community/posts/' + encodeURIComponent(item.contentId),
            { method: 'PUT', body: { body: body, jlptLevel: level || null } });
          replace(updated);
          UI.toast('Đã cập nhật bài viết', 'success');
        }
      });
    }

    async function rate(card, item, score) {
      try {
        var result = await UI.api('/api/community/contents/' + encodeURIComponent(item.contentType) + '/' +
          encodeURIComponent(item.contentId) + '/rating', { method: 'PUT', body: { score: score } });
        item.ratingAvg = result.ratingAvg;
        item.ratingCount = result.ratingCount;
        item.myRating = result.myRating;
        card.querySelector('[data-role="rating"]').innerHTML = ratingSummary(item);
        card.querySelectorAll('[data-act="rate"]').forEach(function (b) {
          var on = Number(b.dataset.score) <= score;
          b.classList.toggle('is-on', on);
          b.querySelector('i').className = (on ? 'fa-solid' : 'fa-regular') + ' fa-star';
        });
        UI.toast('Đã lưu đánh giá ' + score + ' sao', 'success');
      } catch (error) {
        UI.toast(error.message, 'error');
      }
    }

    async function share(item) {
      try {
        await UI.copyText(shareUrl(item));
        UI.toast('Đã sao chép liên kết', 'success');
      } catch (error) {
        UI.toast('Không sao chép được, liên kết: ' + shareUrl(item), 'warning');
      }
    }

    function closeMenus(except) {
      container.querySelectorAll('.cm-menu:not([hidden])').forEach(function (menu) {
        if (menu === except) return;
        menu.hidden = true;
        var toggle = menu.parentNode.querySelector('[data-act="menu"]');
        if (toggle) toggle.setAttribute('aria-expanded', 'false');
      });
    }

    function autoGrow(input) {
      input.style.height = 'auto';
      input.style.height = Math.min(input.scrollHeight, 160) + 'px';
    }

    function updateReplyCount(card) {
      var input = card.querySelector('[data-role="reply-input"]');
      var count = card.querySelector('[data-role="reply-count"]');
      if (!input || !count) return;
      count.hidden = input.value.length < MAX_COMMENT - 200;
      count.textContent = input.value.length + '/' + MAX_COMMENT;
    }

    container.addEventListener('click', function (event) {
      var button = event.target.closest('[data-act]');
      if (!button || !container.contains(button)) return;
      var card = cardOf(button);
      var item = card && itemOf(card);
      if (!item) return;
      var act = button.dataset.act;
      if (act === 'menu') {
        var menu = button.parentNode.querySelector('.cm-menu');
        var open = menu.hidden;
        closeMenus(menu);
        menu.hidden = !open;
        button.setAttribute('aria-expanded', String(open));
        if (open) { var first = menu.querySelector('button'); if (first) first.focus(); }
        return;
      }
      // Mục trong menu "…": đưa focus về nút "…" trước khi menu ẩn, để hộp thoại mở sau đó trả focus về đúng chỗ.
      var fromMenu = button.closest('.cm-menu');
      closeMenus();
      if (fromMenu) {
        var menuToggle = fromMenu.parentNode.querySelector('[data-act="menu"]');
        if (menuToggle) menuToggle.focus();
      }
      if (act === 'expand') {
        var text = card.querySelector('[data-role="text"]');
        var expanded = text.classList.toggle('is-expanded');
        button.textContent = expanded ? 'Thu gọn' : 'Xem thêm';
      } else if (act === 'save') toggleSave(card, item);
      else if (act === 'edit') editPost(item);
      else if (act === 'delete') deletePost(card, item);
      else if (act === 'report') {
        openReport({ type: 'CONTENT', isPost: isPost(item), contentType: item.contentType, contentId: item.contentId,
          label: '“' + (item.title || '').slice(0, 120) + '”', fallback: focusIn(item, '[data-act="share"]'),
          done: function (result) {
            if (result && result.targetHidden) remove(card);
            else { item.canReport = false; refreshMenu(card, item); }   // cập nhật model, lần vẽ lại sau không hiện lại
          } });
      } else if (act === 'remove-post') {
        openRemove({ type: 'POST', id: item.contentId, label: '“' + (item.title || '').slice(0, 120) + '”',
          fallback: focusIn(item, '[data-act="menu"]'), done: function (updated) {
            // Feed: bài bị gỡ không còn hiện với người học → bỏ thẻ. Trang bài viết: vẽ lại để thấy trạng thái + lý do.
            if (options.keepRemoved && updated) replace(updated); else remove(card);
          } });
      } else if (act === 'remove-comment') {
        var cid = Number(button.closest('[data-comment]').dataset.comment);
        var target = thread(item).comments[cid];
        openRemove({ type: 'COMMENT', id: cid, label: '“' + (target ? target.body.slice(0, 120) : '') + '”',
          fallback: focusIn(item, '[data-role="reply-input"]'), done: function () {
            // Chỉ kiểm duyệt viên gỡ được và họ vẫn thấy bình luận đã gỡ (danh sách đầy đủ): giữ lại, ghi "Đã gỡ".
            var removedComment = thread(item).comments[cid];
            if (removedComment) {
              if (removedComment.status === 'PUBLISHED') item.commentCount = Math.max(0, Number(item.commentCount || 0) - 1);
              removedComment.status = 'REMOVED';
              removedComment.hidden = true;
              removedComment.canRemove = false;
              removedComment.canReport = false;
              removedComment.canEdit = false;
            }
            renderThread(card, item);
          } });
      } else if (act === 'more-comments') {
        if (button.dataset.role === 'count' && !card.querySelector('[data-role="more"]').hidden) loadMoreComments(card, item);
        else if (button.dataset.role === 'more') loadMoreComments(card, item);
        else { var input = card.querySelector('[data-role="reply-input"]'); if (input) input.focus(); }
      } else if (act === 'focus-comment') {
        var box = card.querySelector('[data-role="reply-input"]');
        if (box) box.focus();
      } else if (act === 'share') share(item);
      else if (act === 'toggle-rate') {
        var row = card.querySelector('[data-role="rate"]');
        row.hidden = !row.hidden;
      } else if (act === 'rate') rate(card, item, Number(button.dataset.score));
      else if (act === 'del-comment') deleteComment(card, item, Number(button.closest('[data-comment]').dataset.comment));
      else if (act === 'report-comment') {
        var id = Number(button.closest('[data-comment]').dataset.comment);
        var comment = thread(item).comments[id];
        openReport({ type: 'COMMENT', id: id, label: '“' + (comment ? comment.body.slice(0, 120) : '') + '”',
          fallback: focusIn(item, '[data-role="reply-input"]'), done: function (result) {
            var state = thread(item);
            if (result && result.targetHidden) {
              delete state.comments[id];
              state.removed += 1;   // bình luận bị ẩn rời khỏi danh sách server → trang sau lùi offset
              item.commentCount = Math.max(0, Number(item.commentCount || 0) - 1);
            } else if (state.comments[id]) {
              state.comments[id].canReport = false;   // cập nhật model để lần vẽ lại không hiện lại "Báo cáo"
            }
            renderThread(card, item);
          } });
      }
    });

    container.addEventListener('submit', function (event) {
      var form = event.target.closest('[data-role="reply-form"]');
      if (!form) return;
      event.preventDefault();
      var card = cardOf(form);
      sendComment(card, itemOf(card));
    });

    container.addEventListener('keydown', function (event) {
      var input = event.target.closest('[data-role="reply-input"]');
      // Gõ bằng IME (tiếng Nhật): Enter chốt chữ không phải Enter gửi. Safari (macOS) báo isComposing = false cho phím
      // Enter đó nhưng keyCode = 229 nên phải kiểm cả hai.
      if (input && event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229) {
        event.preventDefault();
        var card = cardOf(input);
        sendComment(card, itemOf(card));
      }
      if (event.key === 'Escape') {
        var openMenu = event.target.closest('.cm-menu');
        closeMenus();
        if (openMenu) {
          var toggle = openMenu.parentNode.querySelector('[data-act="menu"]');
          if (toggle) toggle.focus();   // đóng menu bằng Escape: focus về nút "…"
        }
      }
    });

    container.addEventListener('input', function (event) {
      var input = event.target.closest('[data-role="reply-input"]');
      if (!input) return;
      autoGrow(input);
      updateReplyCount(cardOf(input));
    });

    document.addEventListener('click', function (event) {
      if (!event.target.closest('.cm-menu-wrap')) closeMenus();
    });

    return {
      add: function (list) { add(list, false); },
      prepend: function (item) { add([item], true); },
      replace: replace,
      clear: function () { items = {}; threads = {}; container.innerHTML = ''; },
      count: function () { return Object.keys(items).length; },
      /** Trang chi tiết: nạp ngay trang bình luận đầu tiên. */
      expandComments: function (item) {
        var card = container.querySelector('.cm-post[data-key="' + CSS.escape(keyOf(item)) + '"]');
        if (card) loadMoreComments(card, items[keyOf(item)]);
      }
    };
  }

  global.CommunityCards = { create: create, editor: editor, LEVELS: LEVELS, MAX_POST: MAX_POST };
})(window);
