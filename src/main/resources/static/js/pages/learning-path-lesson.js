/* [JLAS-W0108] Trang học riêng một bài trong lộ trình (learning/path-lesson.html).
 * Nội dung bài do textbook-lesson.js render; file này ghi nhận bắt đầu học (khi mở trang từ một
 * activity cụ thể) và nút "Hoàn thành bài" (server cộng streak + credit) rồi quay về lộ trình.
 */
(function (root, factory) {
  'use strict';

  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.LearningPathLessonPage = api;

  if (root && root.document) {
    if (root.document.readyState === 'loading') {
      root.document.addEventListener('DOMContentLoaded', api.mount, { once: true });
    } else {
      api.mount();
    }
  }
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';

  // [JLAS-W0108][MP06] Chỉ start được khi trang mở từ một activity cụ thể trong lộ trình (itemId) —
  // start "cả bài" không có ý nghĩa vì hai item có thể dùng chung một lesson.
  function itemStartUrl(itemId) {
    return itemId ? '/api/learning/path-items/' + encodeURIComponent(itemId) + '/start' : null;
  }

  // [JLAS-W0108] P2 — hoàn thành theo item khi có itemId (độc lập source lesson); fallback theo
  // lessonId cho các lối vào cũ chưa gắn itemId.
  function itemCompleteUrl(itemId, lessonId) {
    if (itemId) return '/api/learning/path-items/' + encodeURIComponent(itemId) + '/complete';
    return lessonId ? '/api/learning/lessons/' + encodeURIComponent(lessonId) + '/complete' : null;
  }

  function mount() {
    var main = document.getElementById('jlabBaseContent');
    var lessonId = main ? main.getAttribute('data-lesson-id') : null;
    var itemId = main ? main.getAttribute('data-item-id') : null;
    var completeBar = document.getElementById('lessonComplete');
    var completeBtn = document.getElementById('lp-lesson-complete');
    var quizLink = document.getElementById('lp-lesson-quiz');
    var statusEl = document.getElementById('lp-lesson-status');

    if (quizLink && lessonId) {
      quizLink.setAttribute('href', '/learning/lesson/' + encodeURIComponent(lessonId) + '/quiz');
    }

    // Hiện thanh hoàn thành khi nội dung bài đã render xong (textbook-lesson.js bỏ `hidden` của #lessonContent).
    function revealWhenReady() {
      var content = document.getElementById('lessonContent');
      if (!content || !completeBar) return;
      var check = function () { if (content.hidden === false) completeBar.hidden = false; };
      check();
      if (completeBar.hidden) {
        new MutationObserver(check).observe(content, { attributes: true, attributeFilter: ['hidden'] });
      }
    }

    // [JLAS-W0108][MP06] Best-effort: ghi nhận đã mở bài để "Tiếp tục học" trỏ đúng chỗ đang dở;
    // lỗi mạng không được chặn hiển thị bài học.
    function start() {
      var url = itemStartUrl(itemId);
      if (!url || !window.JlasAuth || !window.JlasAuth.authHeaders) return;
      fetch(url, { method: 'POST', headers: window.JlasAuth.authHeaders() }).catch(function () {});
    }

    function complete() {
      var url = itemCompleteUrl(itemId, lessonId);
      if (!url) return;
      if (!window.JlasAuth || !window.JlasAuth.requireLogin(window.location.pathname)) return;
      completeBtn.disabled = true;
      if (statusEl) statusEl.textContent = 'Đang lưu…';
      fetch(url, {
        method: 'POST',
        headers: window.JlasAuth.authHeaders()
      })
        .then(function (res) {
          return res.json().catch(function () { return null; }).then(function (json) {
            if (!res.ok || !json || json.success === false) {
              throw new Error((json && json.message) || ('Lỗi HTTP ' + res.status));
            }
            return json;
          });
        })
        .then(function () {
          if (window.JlasUI && typeof JlasUI.toast === 'function') {
            window.JlasUI.toast('Đã ghi nhận hoàn thành bài học!', 'success');
          }
          window.location.replace('/learning/path');
        })
        .catch(function (err) {
          completeBtn.disabled = false;
          if (statusEl) statusEl.textContent = '';
          if (window.JlasUI && typeof JlasUI.toast === 'function') {
            window.JlasUI.toast(err.message || 'Không ghi nhận được tiến độ.', 'error');
          }
        });
    }

    if (completeBtn) completeBtn.addEventListener('click', complete);
    revealWhenReady();
    start();
  }

  return { itemStartUrl: itemStartUrl, itemCompleteUrl: itemCompleteUrl, mount: mount };
});
