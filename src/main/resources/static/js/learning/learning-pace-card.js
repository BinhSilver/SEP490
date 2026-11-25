/* [JLAS-W0108] One weekly pace card for JLPT and communication paths. */
(function (root) {
  'use strict';
  if (!root || !root.document) return;
  var box = root.document.querySelector('[data-learning-pace]');
  if (!box || !root.JpathApi) return;
  var api = root.JpathApi;

  function node(tag, text) {
    var el = root.document.createElement(tag);
    el.textContent = text;
    return el;
  }

  function show(advice) {
    box.textContent = '';
    box.hidden = !advice || !advice.available;
    if (box.hidden) return;
    var unit = advice.key === 'JLPT' ? 'từ mới' : 'tình huống';
    var action = advice.direction === 'INCREASE' ? 'tăng' : 'giảm';
    box.appendChild(node('h2', 'Gợi ý nhịp học tuần sau'));
    box.appendChild(node('p', 'Kiểm tra tuần lần đầu: ' + advice.firstScore + '%. Gợi ý ' + action
      + ' từ ' + advice.current + (advice.direction === 'INCREASE' ? ' lên ' : ' xuống ') + advice.proposed + ' ' + unit + ' mỗi ngày.'));
    if (advice.focus) box.appendChild(node('p', advice.focus + ' trong tuần sau.'));
    box.appendChild(node('p', 'Khoảng ' + advice.minutesPerDay + ' phút/ngày cho lộ trình này; '
      + advice.totalMinutes + ' phút/ngày cho tất cả lộ trình đang học.'));
    if (advice.overFreeTime) box.appendChild(node('p', 'Tổng thời gian vượt mức rảnh '
      + advice.freeMinutes + ' phút/ngày bạn đã khai.'));
    var apply = node('button', 'Áp dụng nhịp mới');
    var keep = node('button', 'Giữ nhịp hiện tại');
    apply.type = keep.type = 'button';
    apply.className = 'jp-btn jp-btn--primary';
    keep.className = 'jp-btn';
    [apply, keep].forEach(function (button) {
      button.addEventListener('click', function () {
        apply.disabled = keep.disabled = true;
        api.send('POST', '/api/learning/pace/' + encodeURIComponent(advice.key),
          { apply: button === apply }).then(function () { root.location.reload(); })
          .catch(function (error) {
            apply.disabled = keep.disabled = false;
            box.appendChild(node('p', error && error.message ? error.message : 'Chưa lưu được lựa chọn.'));
          });
      });
    });
    box.appendChild(apply);
    box.appendChild(keep);
  }

  var kind = box.getAttribute('data-learning-pace');
  var key = kind === 'JLPT' ? Promise.resolve('JLPT') :
    api.get('/api/learning/my-paths').then(function (overview) {
      var goal = new URLSearchParams(root.location.search).get('goal');
      var paths = overview && overview.paths || [];
      var match = paths.find(function (path) { return path.kind === 'COMM' && path.status === 'ACTIVE'
        && (!goal || path.goal === goal); });
      return match && match.key;
    });
  key.then(function (value) {
    if (value) return api.get('/api/learning/pace/' + encodeURIComponent(value)).then(show);
  }).catch(function () { box.hidden = true; });
})(typeof window !== 'undefined' ? window : null);
