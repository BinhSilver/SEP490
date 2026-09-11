/* Set detail — rating + comments (Flow B2) */
(function () {
  'use strict';

  var params = new URLSearchParams(location.search);
  var setId = Number(params.get('id') || 0);
  var setData = null;
  var commentStars = 0;

  document.addEventListener('DOMContentLoaded', function () {
    if (!setId) {
      showError('Thiếu id bộ thẻ.');
      return;
    }
    document.getElementById('btn-close-login').addEventListener('click', closeLoginModal);
    document.getElementById('btn-cancel-login').addEventListener('click', closeLoginModal);
    document.getElementById('btn-send-comment').addEventListener('click', sendComment);
    document.getElementById('btn-clone').addEventListener('click', doClone);
    document.getElementById('btn-fav').addEventListener('click', toggleFav);
    document.getElementById('btn-quiz').addEventListener('click', function () {
      FlashShell.openFlashcardQuiz(setId, { button: this });
    });
    load();
  });

  function isLoggedIn() {
    if (window.JlasAuth && JlasAuth.isLoggedIn()) return true;
    return !!localStorage.getItem('accessToken');
  }

  function openLoginModal() {
    document.getElementById('login-modal').hidden = false;
  }
  function closeLoginModal() {
    document.getElementById('login-modal').hidden = true;
  }

  async function load() {
    try {
      setData = await FlashApi.get('/api/flashcards/' + setId);
      FlashShell.setSidebarActive(setData.ownedByMe ? 'my' : 'public');
      paintSet(setData);
      await loadComments();
      await checkSyncPrompt();
    } catch (e) {
      showError(e.message || 'Không tải được bộ thẻ.');
    }
  }

  /** BR-06 — Sync Prompt: hiện banner nếu tác giả gốc có thẻ mới. */
  async function checkSyncPrompt() {
    if (!isLoggedIn() || !setData || !setData.ownedByMe) return;
    try {
      var preview = await FlashApi.get('/api/flashcards/' + setId + '/sync-preview');
      if (!preview || !preview.newCardCount) return;
      var existing = document.getElementById('sync-banner');
      if (existing) existing.remove();
      var banner = document.createElement('div');
      banner.id = 'sync-banner';
      banner.className = 'sync-banner';
      banner.innerHTML =
        '<i class="fa-solid fa-arrows-rotate"></i>' +
        '<span>Có <strong>' + preview.newCardCount +
        '</strong> từ mới từ tác giả gốc <em>' + FlashShell.esc(preview.originalTitle || '—') +
        '</em></span>' +
        '<div class="sync-actions">' +
          '<button class="btn btn-ghost btn-sm" id="btn-sync-dismiss">Bỏ qua</button>' +
          '<button class="btn btn-primary btn-sm" id="btn-sync-now">Đồng bộ</button>' +
        '</div>';
      var main = document.getElementById('set-main');
      main.insertBefore(banner, main.firstChild);
      document.getElementById('btn-sync-dismiss').onclick = function () {
        banner.remove();
      };
      document.getElementById('btn-sync-now').onclick = async function () {
        try {
          var res = await FlashApi.post('/api/flashcards/' + setId + '/sync', {});
          FlashShell.showToast('Đã thêm ' + (res.addedCount || 0) + ' thẻ mới', 'ok');
          banner.remove();
          // Reload set detail
          setData = await FlashApi.get('/api/flashcards/' + setId);
          paintSet(setData);
        } catch (e) {
          FlashShell.showToast(e.message || 'Sync thất bại', 'err');
        }
      };
    } catch (e) {
      // 400 = not a clone  không hiện banner, im lặng
    }
  }

  function showError(msg) {
    document.getElementById('set-loading').hidden = true;
    document.getElementById('set-main').hidden = true;
    document.getElementById('set-error').hidden = false;
    document.getElementById('set-error-msg').textContent = msg;
  }

  function paintSet(s) {
    document.getElementById('set-loading').hidden = true;
    document.getElementById('set-error').hidden = true;
    document.getElementById('set-main').hidden = false;

    document.title = (s.title || 'Bộ thẻ') + ' — JLAS';
    document.getElementById('set-title').textContent = s.title || '—';
    document.getElementById('set-desc').textContent = s.description || '';
    document.getElementById('set-desc').hidden = !s.description;

    var vis = s.visibility === 'PUBLIC' ? 'Công khai' : 'Riêng tư';
    var mod = '';
    if (s.moderationStatus === 'REVIEW') mod = ' · Chờ duyệt';
    document.getElementById('set-meta').innerHTML =
      FlashShell.esc(s.ownerName || '—') + ' · ' +
      (s.totalCards || 0) + ' thẻ · ' +
      (s.studyCount || 0) + ' lượt học · ' +
      vis + FlashShell.esc(mod);

    var avg = s.avgRating != null ? Number(s.avgRating).toFixed(1) : '—';
    var rc = s.ratingCount || 0;
    document.getElementById('set-rating-summary').innerHTML =
      starsHtml(s.avgRating || 0, false) +
      ' <strong>' + avg + '</strong> <span class="muted">(' + rc + ' đánh giá)</span>' +
      ' · <i class="fa-solid fa-comment"></i> ' + (s.commentCount || 0);

    document.getElementById('btn-study').href = '/flashcard/study.html?setId=' + s.id;
    var quizButton = document.getElementById('btn-quiz');
    var canQuiz = (s.totalCards || (s.cards || []).length) >= 4;
    quizButton.disabled = !canQuiz;
    quizButton.title = canQuiz
      ? 'Kiểm tra kiến thức từ bộ Flashcard này'
      : 'Cần ít nhất 4 thẻ để kiểm tra';
    document.getElementById('btn-edit').hidden = !s.ownedByMe;
    if (s.ownedByMe) {
      document.getElementById('btn-edit').href = '/flashcard/editor.html?id=' + s.id;
    }

    commentStars = 0;
    function onCommentStar(n) {
      commentStars = n;
      renderStarPicker('comment-stars', commentStars, onCommentStar);
    }
    renderStarPicker('comment-stars', 0, onCommentStar);

    paintFav(!!s.favorited);

    var cards = s.cards || [];
    var preview = cards.slice(0, 10);
    var tbody = document.getElementById('preview-body');
    tbody.innerHTML = preview.map(function (c) {
      return '<tr><td lang="ja">' + FlashShell.esc(c.front) + '</td><td>' +
        FlashShell.esc(c.back) + '</td><td>' + FlashShell.esc(c.hanViet || '—') + '</td></tr>';
    }).join('') || '<tr><td colspan="3" class="muted">Chưa có thẻ</td></tr>';

    var more = document.getElementById('preview-more');
    if (cards.length > 10) {
      more.hidden = false;
      more.textContent = 'Hiển thị 10 / ' + cards.length + ' thẻ · Học để xem hết.';
    } else {
      more.hidden = true;
    }
  }

  function starsHtml(rating, interactive) {
    var r = Math.round(Number(rating) || 0);
    var html = '<span class="star-rating' + (interactive ? ' interactive' : '') + '">';
    for (var i = 1; i <= 5; i++) {
      html += '<i class="fa-' + (i <= r ? 'solid' : 'regular') + ' fa-star star-btn' +
        (i <= r ? ' filled' : '') + '"></i>';
    }
    return html + '</span>';
  }

  function renderStarPicker(elId, value, onPick) {
    var el = document.getElementById(elId);
    if (!el) return;
    el.innerHTML = '';
    for (var i = 1; i <= 5; i++) {
      (function (n) {
        var btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'star-btn' + (n <= value ? ' filled' : '');
        btn.title = n + ' sao';
        btn.innerHTML = '<i class="fa-' + (n <= value ? 'solid' : 'regular') + ' fa-star"></i>';
        btn.addEventListener('click', function () { onPick(n); });
        el.appendChild(btn);
      })(i);
    }
  }

  async function loadComments() {
    try {
      var page = await FlashApi.get('/api/flashcards/' + setId + '/comments?page=0&size=50');
      var items = (page && page.content) || [];
      document.getElementById('comment-count-label').textContent = '(' + (page.totalElements || items.length) + ')';
      var list = document.getElementById('comment-list');
      if (!items.length) {
        list.innerHTML = '<p class="muted">Chưa có bình luận. Hãy là người đầu tiên!</p>';
        return;
      }
      list.innerHTML = items.map(function (c) {
        var stars = c.rating ? starsHtml(c.rating, false) : '';
        var del = c.ownedByMe
          ? '<button type="button" class="btn btn-ghost btn-sm" data-del-c="' + c.id + '"><i class="fa-solid fa-trash"></i></button>'
          : '';
        var when = c.createdAt ? String(c.createdAt).replace('T', ' ').slice(0, 16) : '';
        return (
          '<div class="comment-item">' +
            '<div class="comment-item-header">' +
              '<strong>' + FlashShell.esc(c.displayName || 'Học viên') + '</strong> ' +
              stars +
              '<span class="caption">' + FlashShell.esc(when) + '</span>' +
              del +
            '</div>' +
            '<p class="comment-content">' + FlashShell.esc(c.content) + '</p>' +
          '</div>'
        );
      }).join('');
      list.querySelectorAll('[data-del-c]').forEach(function (btn) {
        btn.addEventListener('click', function () {
          delComment(Number(btn.getAttribute('data-del-c')));
        });
      });
    } catch (e) {
      document.getElementById('comment-list').innerHTML =
        '<p class="muted">Không tải được bình luận.</p>';
    }
  }

  async function sendComment() {
    if (!isLoggedIn()) { openLoginModal(); return; }
    var text = document.getElementById('comment-text').value.trim();
    var err = document.getElementById('comment-error');
    err.hidden = true;
    if (!text) {
      err.textContent = 'Nội dung bình luận không được để trống';
      err.hidden = false;
      return;
    }
    try {
      var body = { content: text };
      if (commentStars >= 1 && commentStars <= 5 && !(setData && setData.ownedByMe)) {
        body.rating = commentStars;
      }
      await FlashApi.post('/api/flashcards/' + setId + '/comments', body);
      document.getElementById('comment-text').value = '';
      commentStars = 0;
      function onCommentStar2(n) {
        commentStars = n;
        renderStarPicker('comment-stars', commentStars, onCommentStar2);
      }
      renderStarPicker('comment-stars', 0, onCommentStar2);
      FlashShell.showToast('Đã gửi bình luận', 'ok');
      setData = await FlashApi.get('/api/flashcards/' + setId);
      paintSet(setData);
      await loadComments();
    } catch (e) {
      err.textContent = e.message || 'Nội dung không phù hợp';
      err.hidden = false;
      FlashShell.showToast(e.message || 'Gửi thất bại', 'err');
    }
  }

  async function delComment(id) {
    if (!confirm('Xóa bình luận này?')) return;
    try {
      await FlashApi.del('/api/flashcards/comments/' + id);
      FlashShell.showToast('Đã xóa', 'ok');
      setData = await FlashApi.get('/api/flashcards/' + setId);
      paintSet(setData);
      await loadComments();
    } catch (e) {
      FlashShell.showToast(e.message || 'Xóa thất bại', 'err');
    }
  }

  async function doClone() {
    if (!isLoggedIn()) { openLoginModal(); return; }
    try {
      var set = await FlashApi.post('/api/flashcards/' + setId + '/clone', {});
      FlashShell.showToast('Đã nhân bản: ' + set.title, 'ok');
      location.href = '/flashcard/editor.html?id=' + set.id;
    } catch (e) {
      FlashShell.showToast(e.message || 'Nhân bản thất bại', 'err');
    }
  }

  function paintFav(on) {
    var btn = document.getElementById('btn-fav');
    btn.innerHTML = on
      ? '<i class="fa-solid fa-heart" style="color:var(--color-warning,#e11)"></i>'
      : '<i class="fa-regular fa-heart"></i>';
    btn.classList.toggle('is-fav', on);
  }

  async function toggleFav() {
    if (!isLoggedIn()) { openLoginModal(); return; }
    try {
      var res = await FlashApi.post('/api/flashcards/' + setId + '/favorite', {});
      paintFav(!!res.favorited);
      FlashShell.showToast(res.favorited ? 'Đã thêm yêu thích' : 'Đã bỏ yêu thích', 'ok');
    } catch (e) {
      FlashShell.showToast(e.message || 'Không cập nhật được', 'err');
    }
  }
})();
