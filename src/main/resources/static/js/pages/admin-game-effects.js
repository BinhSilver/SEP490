(function () {
  'use strict';
  window.Admin.section('game-effects', {
    subtitle: 'Chọn trang phục để xem hiệu ứng và nghe thử âm thanh của trò chơi.',
    render: function (root) {
      var frame = document.createElement('iframe');
      frame.src = '/admin/game-effects/preview';
      frame.title = 'Xem hiệu ứng Bắn máy bay, Bắt cá và Lật thẻ';
      frame.allow = 'autoplay';
      frame.style.cssText = 'display:block;width:100%;height:820px;border:0;border-radius:16px;';
      root.replaceChildren(frame);
    }
  });
})();
