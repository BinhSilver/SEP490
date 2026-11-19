/** Community Hub: trang một bài viết /community/posts/{id} (link chia sẻ, link từ thông báo). */
(function () {
  'use strict';

  var UI = window.CommunityUI;
  var root = document.getElementById('cmPostPage');
  var postId = root.dataset.postId;
  var stateEl = document.getElementById('cmPostState');

  function notFound(message) {
    stateEl.hidden = false;
    stateEl.innerHTML = '<i class="fa-regular fa-face-frown" aria-hidden="true"></i>' +
      '<p><b>Không tìm thấy bài viết</b></p><p class="cm-muted">' + UI.esc(message) + '</p>' +
      '<a class="btn btn--primary btn--sm" href="/community">Về trang Cộng đồng</a>';
  }

  var cards = window.CommunityCards.create(document.getElementById('cmPost'), {
    keepRemoved: true,
    onPostDeleted: function () { window.location.href = '/community'; },
    onRemoved: function () { notFound('Bài viết đã được ẩn tạm thời để kiểm duyệt viên xem xét.'); }
  });

  async function init() {
    var item;
    try {
      item = await UI.api('/api/community/posts/' + encodeURIComponent(postId));
    } catch (error) {
      notFound(error.status === 404 ? 'Bài viết không tồn tại, đã bị xoá/ẩn hoặc bạn không có quyền xem.' : error.message);
      return;
    }
    stateEl.hidden = true;
    var excerpt = (item.title || '').trim();
    document.title = (excerpt ? excerpt.slice(0, 60) + ' | ' : '') + 'Cộng đồng KotsuKotsu';
    cards.add([item]);
    cards.expandComments(item);
  }

  init();
})();
