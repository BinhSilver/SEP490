/** Community Hub: trang chi tiết (/community/{contentType}/{contentId}). */
(function () {
  'use strict';

  var UI = window.CommunityUI;
  var root = document.getElementById('cmDetail');
  var contentType = root.dataset.contentType;
  var contentId = root.dataset.contentId;
  var base = '/api/community/contents/' + encodeURIComponent(contentType) + '/' + encodeURIComponent(contentId);
  var COMMENT_PAGE_SIZE = 10;
  var MAX_COMMENT = 1000;

  var el = function (id) { return document.getElementById(id); };
  var detail = null;
  var reasons = [];
  var commentPage = 0;
  var commentTotalPages = 0;
  var reportTarget = null;

  // ── Nội dung ───────────────────────────────────────────────────────────
  function showNotFound(message) {
    el('cmDetailBody').hidden = true;
    el('cmDetailState').innerHTML = '<i class="fa-regular fa-face-frown" aria-hidden="true"></i>' +
      '<p><b>Không tìm thấy nội dung</b></p><p class="cm-muted">' + UI.esc(message) + '</p>' +
      '<a class="btn btn--primary btn--sm" href="/community">Về trang Cộng đồng</a>';
  }

  function renderDetail() {
    document.title = detail.title + ' | Cộng đồng JLAS';
    el('cmThumb').innerHTML = detail.thumbnailUrl
      ? '<img src="' + UI.esc(UI.safeUrl(detail.thumbnailUrl)) + '" alt="">'
      : '<i class="fa-solid ' + (detail.contentType === 'SHADOWING_VIDEO' ? 'fa-film' : 'fa-layer-group') + '"></i>';
    var badges = '<span class="cm-badge">' + UI.esc(detail.contentTypeLabel) + '</span>';
    if (detail.jlptLevel) badges += '<span class="cm-badge cm-badge--level">' + UI.esc(detail.jlptLevel) + '</span>';
    if (detail.extra && detail.extra.cardCount != null) badges += '<span class="cm-badge cm-badge--soft">' + detail.extra.cardCount + ' thẻ</span>';
    if (detail.status !== 'PUBLISHED') badges += '<span class="cm-badge cm-badge--warn">' + statusLabel(detail.status) + '</span>';
    el('cmBadges').innerHTML = badges;
    el('cmTitle').textContent = detail.title;
    el('cmAuthor').innerHTML = detail.author
      ? UI.avatar(detail.author) + '<span>' + UI.esc(detail.author.displayName) + (detail.owner ? ' (bạn)' : '') +
        (detail.createdAt ? ' · ' + UI.esc(UI.formatDate(detail.createdAt)) : '') + '</span>'
      : '';
    el('cmDesc').textContent = detail.description || 'Chưa có mô tả.';
    el('cmOpenSource').href = UI.safeUrl(detail.link);
    el('cmReportContent').hidden = !detail.canReport;

    var notice = el('cmNotice');
    notice.hidden = !detail.notice;
    notice.textContent = detail.notice || '';
    notice.classList.toggle('cm-banner--danger', detail.status === 'REMOVED');

    renderRating();
    var commentHint = el('cmCommentHint');
    el('cmCommentForm').hidden = !detail.canComment;
    commentHint.hidden = detail.canComment;
    commentHint.textContent = detail.canComment ? '' : 'Không thể bình luận vì nội dung đang không công khai.';
  }

  function statusLabel(status) {
    if (status === 'TEMPORARILY_HIDDEN') return 'Đang tạm ẩn';
    if (status === 'REMOVED') return 'Đã gỡ';
    return status;
  }

  // ── Đánh giá ───────────────────────────────────────────────────────────
  function renderRating() {
    el('cmRatingSummary').innerHTML = UI.stars(detail.ratingAvg) + ' <b>' + Number(detail.ratingAvg || 0).toFixed(1) +
      '</b>/5 · ' + detail.ratingCount + ' lượt đánh giá';
    var starsBox = el('cmStars');
    var hint = el('cmRateHint');
    var html = '';
    for (var i = 1; i <= 5; i++) {
      var on = detail.myRating && i <= detail.myRating;
      html += '<button type="button" class="cm-star' + (on ? ' is-on' : '') + '" role="radio" data-score="' + i + '"' +
        ' aria-checked="' + (detail.myRating === i) + '" aria-label="' + i + ' sao"' + (detail.canRate ? '' : ' disabled') + '>' +
        '<i class="' + (on ? 'fa-solid' : 'fa-regular') + ' fa-star" aria-hidden="true"></i></button>';
    }
    starsBox.innerHTML = html;
    el('cmMyRating').textContent = detail.myRating ? 'Bạn đã chấm ' + detail.myRating + ' sao' : (detail.canRate ? 'Chọn số sao để đánh giá' : '');
    el('cmUnrate').hidden = !detail.myRating;
    hint.hidden = detail.canRate;
    hint.textContent = detail.canRate ? '' : detail.owner
      ? 'Bạn không thể tự đánh giá nội dung của mình.'
      : 'Không thể đánh giá vì nội dung đang không công khai.';
  }

  async function rate(score) {
    try {
      var result = await UI.api(base + '/rating', { method: 'PUT', body: { score: score } });
      applyRating(result);
      UI.toast('Đã lưu đánh giá ' + score + ' sao', 'success');
    } catch (error) {
      UI.toast(error.message, 'error');
    }
  }

  async function unrate() {
    try {
      var result = await UI.api(base + '/rating', { method: 'DELETE' });
      applyRating(result);
      UI.toast('Đã gỡ đánh giá', 'success');
    } catch (error) {
      UI.toast(error.message, 'error');
    }
  }

  function applyRating(result) {
    detail.ratingAvg = result.ratingAvg;
    detail.ratingCount = result.ratingCount;
    detail.myRating = result.myRating;
    renderRating();
  }

  // ── Bình luận ──────────────────────────────────────────────────────────
  function commentHtml(c) {
    var actions = '';
    if (c.canEdit) actions += '<button type="button" class="cm-link" data-action="edit">Sửa</button>';
    if (c.canDelete) actions += '<button type="button" class="cm-link cm-link--danger" data-action="delete">Xoá</button>';
    if (c.canReport) actions += '<button type="button" class="cm-link" data-action="report"><i class="fa-regular fa-flag" aria-hidden="true"></i> Báo cáo</button>';
    var label = '';
    if (c.status === 'TEMPORARILY_HIDDEN') label = '<span class="cm-badge cm-badge--warn">Đang bị ẩn</span>';
    if (c.status === 'REMOVED') label = '<span class="cm-badge cm-badge--danger">Đã gỡ</span>';
    return '<li class="cm-comment' + (c.hidden ? ' is-hidden' : '') + '" data-id="' + c.id + '">' +
      UI.avatar(c.author) +
      '<div class="cm-comment__main">' +
        '<div class="cm-comment__head"><b>' + UI.esc(c.author ? c.author.displayName : '') + '</b>' +
          (c.mine ? '<span class="cm-muted">(bạn)</span>' : '') + label +
          '<span class="cm-muted cm-comment__time">' + UI.esc(UI.formatDate(c.createdAt)) + (c.edited ? ' · đã sửa' : '') + '</span>' +
        '</div>' +
        '<p class="cm-comment__body">' + UI.esc(c.body) + '</p>' +
        (actions ? '<div class="cm-comment__actions">' + actions + '</div>' : '') +
      '</div>' +
    '</li>';
  }

  var commentsById = {};

  function renderComments(items, append) {
    var list = el('cmComments');
    items.forEach(function (c) { commentsById[c.id] = c; });
    var html = items.map(commentHtml).join('');
    if (append) list.insertAdjacentHTML('beforeend', html);
    else list.innerHTML = html || '<li class="cm-state cm-state--inline">Chưa có bình luận nào. Hãy là người đầu tiên!</li>';
    el('cmMoreComments').hidden = commentPage + 1 >= commentTotalPages;
  }

  function setCommentCount() {
    el('cmCommentCount').textContent = '(' + (detail.commentCount || 0) + ')';
  }

  async function loadComments(append) {
    var list = el('cmComments');
    if (!append) list.innerHTML = '<li class="cm-state cm-state--inline"><span class="cm-spinner" aria-hidden="true"></span> Đang tải bình luận…</li>';
    try {
      var data = await UI.api(base + '/comments?page=' + commentPage + '&size=' + COMMENT_PAGE_SIZE);
      commentTotalPages = data.totalPages;
      renderComments(data.items, append);
    } catch (error) {
      if (!append) list.innerHTML = '<li class="cm-state cm-state--inline">' + UI.esc(error.message) + '</li>';
      else UI.toast(error.message, 'error');
    }
  }

  async function submitComment(event) {
    event.preventDefault();
    var input = el('cmCommentBody');
    var text = input.value.trim();
    if (!text) { UI.toast('Nội dung bình luận không được để trống', 'warning'); input.focus(); return; }
    if (text.length > MAX_COMMENT) { UI.toast('Bình luận tối đa 1000 ký tự', 'warning'); return; }
    var button = el('cmCommentSubmit');
    button.disabled = true;
    try {
      var created = await UI.api(base + '/comments', { method: 'POST', body: { body: text } });
      commentsById[created.id] = created;
      var list = el('cmComments');
      var empty = list.querySelector('.cm-state');
      if (empty) empty.remove();
      list.insertAdjacentHTML('afterbegin', commentHtml(created));
      input.value = '';
      updateCounter();
      detail.commentCount = (detail.commentCount || 0) + 1;
      setCommentCount();
    } catch (error) {
      UI.toast(error.message, 'error');
    } finally {
      button.disabled = false;
    }
  }

  function startEdit(item, comment) {
    var body = item.querySelector('.cm-comment__body');
    var actions = item.querySelector('.cm-comment__actions');
    if (item.querySelector('.cm-edit')) return;
    body.hidden = true;
    if (actions) actions.hidden = true;
    body.insertAdjacentHTML('afterend',
      '<div class="cm-edit"><textarea rows="3" maxlength="1000" aria-label="Sửa bình luận">' + UI.esc(comment.body) + '</textarea>' +
      '<div class="cm-edit__actions"><button type="button" class="btn btn--secondary btn--sm" data-edit="cancel">Huỷ</button>' +
      '<button type="button" class="btn btn--primary btn--sm" data-edit="save">Lưu</button></div></div>');
    item.querySelector('.cm-edit textarea').focus();
  }

  async function finishEdit(item, save) {
    var id = Number(item.dataset.id);
    var box = item.querySelector('.cm-edit');
    if (save) {
      var text = box.querySelector('textarea').value.trim();
      if (!text) { UI.toast('Nội dung bình luận không được để trống', 'warning'); return; }
      try {
        var updated = await UI.api('/api/community/comments/' + id, { method: 'PUT', body: { body: text } });
        commentsById[id] = updated;
        item.outerHTML = commentHtml(updated);
        UI.toast('Đã cập nhật bình luận', 'success');
        return;
      } catch (error) {
        UI.toast(error.message, 'error');
        return;
      }
    }
    box.remove();
    item.querySelector('.cm-comment__body').hidden = false;
    var actions = item.querySelector('.cm-comment__actions');
    if (actions) actions.hidden = false;
  }

  async function deleteComment(item) {
    if (!window.confirm('Xoá bình luận này?')) return;
    var id = Number(item.dataset.id);
    try {
      await UI.api('/api/community/comments/' + id, { method: 'DELETE' });
      var wasPublished = commentsById[id] && commentsById[id].status === 'PUBLISHED';
      item.remove();
      delete commentsById[id];
      if (wasPublished) { detail.commentCount = Math.max(0, (detail.commentCount || 0) - 1); setCommentCount(); }
      if (!el('cmComments').children.length) renderComments([], false);
      UI.toast('Đã xoá bình luận', 'success');
    } catch (error) {
      UI.toast(error.message, 'error');
    }
  }

  function updateCounter() {
    el('cmCommentCounter').textContent = el('cmCommentBody').value.length + '/' + MAX_COMMENT;
  }

  // ── Báo cáo ────────────────────────────────────────────────────────────
  function openReport(target) {
    reportTarget = target;
    el('cmReportTitle').textContent = target.type === 'COMMENT' ? 'Báo cáo bình luận' : 'Báo cáo nội dung';
    el('cmReportTarget').textContent = target.label;
    el('cmReportReason').innerHTML = reasons.map(function (r) {
      return '<option value="' + UI.esc(r.value) + '">' + UI.esc(r.label) + '</option>';
    }).join('');
    el('cmReportNote').value = '';
    el('cmReportModal').hidden = false;
    el('cmReportReason').focus();
  }

  function closeReport() {
    el('cmReportModal').hidden = true;
    reportTarget = null;
  }

  async function submitReport(event) {
    event.preventDefault();
    if (!reportTarget) return;
    var payload = { targetType: reportTarget.type, reason: el('cmReportReason').value, note: el('cmReportNote').value.trim() || null };
    if (reportTarget.type === 'COMMENT') payload.targetId = reportTarget.id;
    else { payload.contentType = contentType; payload.contentId = Number(contentId); }
    var button = el('cmReportSubmit');
    button.disabled = true;
    try {
      var result = await UI.api('/api/community/reports', { method: 'POST', body: payload });
      var wasComment = reportTarget.type === 'COMMENT';
      var commentId = reportTarget.id;
      closeReport();
      UI.toast('Đã gửi báo cáo. Cảm ơn bạn!', 'success');
      if (result && result.targetHidden) {
        if (wasComment) {
          var item = document.querySelector('.cm-comment[data-id="' + commentId + '"]');
          if (item) item.remove();
          detail.commentCount = Math.max(0, (detail.commentCount || 0) - 1);
          setCommentCount();
        } else {
          showNotFound('Nội dung đã được ẩn tạm thời để kiểm duyệt viên xem xét.');
        }
      } else if (wasComment) {
        var reported = document.querySelector('.cm-comment[data-id="' + commentId + '"] [data-action="report"]');
        if (reported) reported.remove();
      } else {
        el('cmReportContent').hidden = true;
      }
    } catch (error) {
      UI.toast(error.message, error.status === 409 ? 'warning' : 'error');
      if (error.status === 409) closeReport();
    } finally {
      button.disabled = false;
    }
  }

  // ── Khởi động ──────────────────────────────────────────────────────────
  async function init() {
    try {
      detail = await UI.api(base);
    } catch (error) {
      showNotFound(error.status === 404 ? 'Nội dung không tồn tại, đã bị ẩn hoặc bạn không có quyền xem.' : error.message);
      return;
    }
    el('cmDetailState').hidden = true;
    el('cmDetailBody').hidden = false;
    renderDetail();
    setCommentCount();
    loadComments(false);
    UI.api('/api/community/meta').then(function (meta) { reasons = meta.reasons; })
      .catch(function () { reasons = [{ value: 'OTHER', label: 'Khác' }]; });
  }

  el('cmStars').addEventListener('click', function (event) {
    var star = event.target.closest('[data-score]');
    if (star && !star.disabled) rate(Number(star.dataset.score));
  });
  el('cmUnrate').addEventListener('click', unrate);
  el('cmCommentForm').addEventListener('submit', submitComment);
  el('cmCommentBody').addEventListener('input', updateCounter);
  el('cmMoreComments').addEventListener('click', function () { commentPage += 1; loadComments(true); });
  el('cmReportContent').addEventListener('click', function () {
    openReport({ type: 'CONTENT', label: detail ? detail.title : '' });
  });
  el('cmComments').addEventListener('click', function (event) {
    var item = event.target.closest('.cm-comment');
    if (!item) return;
    var comment = commentsById[Number(item.dataset.id)];
    var edit = event.target.closest('[data-edit]');
    if (edit) { finishEdit(item, edit.dataset.edit === 'save'); return; }
    var button = event.target.closest('[data-action]');
    if (!button || !comment) return;
    if (button.dataset.action === 'edit') startEdit(item, comment);
    else if (button.dataset.action === 'delete') deleteComment(item);
    else if (button.dataset.action === 'report') {
      openReport({ type: 'COMMENT', id: comment.id, label: '“' + comment.body.slice(0, 120) + (comment.body.length > 120 ? '…' : '') + '”' });
    }
  });
  el('cmReportForm').addEventListener('submit', submitReport);
  el('cmReportModal').addEventListener('click', function (event) {
    if (event.target.closest('[data-cm-close]')) closeReport();
  });
  document.addEventListener('keydown', function (event) {
    if (event.key === 'Escape' && !el('cmReportModal').hidden) closeReport();
  });

  init();
})();
