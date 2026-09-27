/* [JLAS-W0108] Trang học riêng một bài trong lộ trình (learning/path-lesson.html).
 * Nội dung bài do textbook-lesson.js render; file này chỉ thêm nút "Hoàn thành bài"
 * ghi nhận hoàn thành (server cộng streak + credit) rồi quay về lộ trình.
 */
(function () {
  'use strict';

  var main = document.getElementById('jlabBaseContent');
  var lessonId = main ? main.getAttribute('data-lesson-id') : null;
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

  function complete() {
    if (!lessonId) return;
    if (!window.JlasAuth || !window.JlasAuth.requireLogin(window.location.pathname)) return;
    completeBtn.disabled = true;
    if (statusEl) statusEl.textContent = 'Đang lưu…';
    fetch('/api/learning/lessons/' + encodeURIComponent(lessonId) + '/complete', {
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
})();
