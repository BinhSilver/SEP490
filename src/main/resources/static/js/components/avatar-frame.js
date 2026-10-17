/**
 * Khung avatar động + màu tên (món mua ở Cửa hàng, chọn ở Hồ sơ).
 *
 * - Ảnh khung tĩnh đã chuẩn hoá (static/images/avatar-frames): lỗ tròn ở giữa, khung = 1,5 × avatar.
 * - Mọi chuyển động là CSS (avatar-frame.css); file này chỉ dựng các lớp hiệu ứng theo toạ độ đá đo từ ảnh.
 * - Luôn chạy đầy đủ hiệu ứng, không theo prefers-reduced-motion (quyết định sản phẩm).
 *
 * Dùng: phần tử bọc avatar có [data-avatar-frame] → JlasAvatarFrame.applyUser(user) gắn khung đang dùng;
 * mọi [data-user-name] nhận màu tên qua [data-name-style].
 */
(function () {
  'use strict';

  var BASE = '/images/avatar-frames/';
  /** Toạ độ (% khung) đo từ ảnh: big = đá lớn trên/dưới, gems = đá nhỏ, orbs = quả cầu, drips = đầu giọt treo. */
  var FRAMES = {
    'frame-cosmic': {
      img: BASE + 'cosmic.webp', theme: 'cosmic',
      big: [[49.8, 13.8], [50.5, 85.6]],
      gems: [[77.1, 24.0], [77.2, 75.3], [22.5, 75.5], [22.4, 24.6]],
      orbs: [[12.9, 48.7], [88.6, 50.0]]
    },
    'frame-seraph': {
      img: BASE + 'seraph.webp', theme: 'seraph',
      big: [[49.5, 7.4], [49.8, 85.1]],
      gems: [[49.8, 15.4], [21.9, 27.3], [76.9, 27.2], [12.9, 51.0], [87.0, 50.6]]
    },
    'frame-inferno': {
      img: BASE + 'inferno.webp', theme: 'inferno',
      big: [[49.8, 13.4], [50.3, 83.3]],
      gems: [[26.8, 23.2], [73.2, 23.0], [17.8, 31.6], [82.3, 31.7], [12.9, 50.2], [86.8, 50.2], [19.9, 70.3], [80.0, 69.9]]
    },
    'frame-vampire': {
      img: BASE + 'vampire.webp', theme: 'vampire',
      big: [[49.8, 16.0], [49.8, 81.1]],
      gems: [[25.7, 19.5], [74.6, 19.3], [37.9, 15.1], [61.7, 15.0], [12.0, 48.9], [88.0, 49.1], [14.1, 67.2], [85.8, 67.3], [50.1, 91.7]],
      drips: [[15.8, 76.3], [84.1, 76.4]]
    }
  };
  /** Giây cho một vòng dải sáng; khớp --af-period trong CSS. */
  var PERIOD = 4;
  var GLITTER = 18;
  var MOTES = 10;

  /** Góc theo chiều kim đồng hồ tính từ đỉnh, để đá chớp đúng lúc dải sáng chạy qua. */
  function angleOf(point) {
    return (Math.atan2(point[0] - 50, -(point[1] - 50)) * 180 / Math.PI + 360) % 360;
  }

  function at(point) {
    return 'left:' + point[0] + '%;top:' + point[1] + '%';
  }

  function flare(point, big) {
    var delay = (angleOf(point) / 360) * PERIOD - PERIOD;
    return '<span class="af-flare' + (big ? ' af-flare--big' : '') + '" style="' + at(point)
      + ';animation-delay:' + delay.toFixed(2) + 's"></span>';
  }

  function markup(code) {
    var frame = FRAMES[code];
    if (!frame) return '';
    var fx = '';
    (frame.big || []).forEach(function (p) { fx += flare(p, true); });
    (frame.gems || []).forEach(function (p) { fx += flare(p, false); });
    (frame.orbs || []).forEach(function (p, i) {
      fx += flare(p, false);
      fx += '<span class="af-orb" style="' + at(p) + (i ? ';animation-direction:reverse' : '') + '"></span>';
    });
    (frame.drips || []).forEach(function (p, i) {
      fx += '<span class="af-drip" style="' + at(p) + ';animation-delay:-' + (i * 1.7).toFixed(1) + 's"></span>';
    });
    // Kim tuyến rải đều trên vành, lệch pha cố định (không random để mọi lần vẽ giống nhau).
    for (var g = 0; g < GLITTER; g++) {
      fx += '<span class="af-arm" style="rotate:' + (g * 20 + 7) + 'deg"><i class="af-glit" style="animation-delay:-'
        + (((g * 7) % 18) / 9).toFixed(2) + 's;animation-duration:' + (1.6 + (g % 5) * 0.25).toFixed(2) + 's"></i></span>';
    }
    for (var m = 0; m < MOTES; m++) {
      // --r: góc cánh tay, để tàn lửa (Hỏa Ngục) xoay ngược lại và luôn bay thẳng lên.
      var turn = m * 36 + 18;
      fx += '<span class="af-arm" style="rotate:' + turn + 'deg;--r:' + turn + 'deg"><i class="af-mote" style="animation-delay:-'
        + ((m * 1.3) % 3.2).toFixed(1) + 's"></i></span>';
    }
    return '<span class="af af--' + frame.theme + '" style="--af-img:url(' + frame.img + ')" aria-hidden="true">'
      + '<span class="af-aura"></span>'
      + '<img class="af-frame" src="' + frame.img + '" alt="" draggable="false">'
      + '<span class="af-chase"></span>'
      + '<span class="af-sweep"></span>'
      + '<span class="af-fx">' + fx + '</span>'
      + '</span>';
  }

  /** Gắn (hoặc gỡ khi code rỗng) khung vào phần tử bọc avatar. Không vẽ lại nếu khung không đổi. */
  function mount(host, code) {
    if (!host) return;
    var current = host.getAttribute('data-frame-mounted') || '';
    var next = code && FRAMES[code] ? code : '';
    if (current === next) return;
    var old = host.querySelector(':scope > .af');
    if (old) old.remove();
    host.classList.add('af-host');
    host.classList.toggle('has-frame', !!next);
    if (next) host.insertAdjacentHTML('beforeend', markup(next));
    if (next) host.setAttribute('data-frame-mounted', next);
    else host.removeAttribute('data-frame-mounted');
  }

  function applyNameStyle(element, style) {
    if (!element) return;
    if (style) element.setAttribute('data-name-style', style);
    else element.removeAttribute('data-name-style');
  }

  /**
   * Bọc HTML avatar của người khác (phòng game, bảng xếp hạng…) để gắn khung sau bằng mountAll().
   * Luôn bọc (kể cả khi không có khung) để bố cục không đổi giữa người có và không có khung.
   */
  function host(avatarHtml, frameCode, extraClass) {
    return '<span class="af-host' + (extraClass ? ' ' + extraClass : '') + '"'
      + (frameCode && FRAMES[frameCode] ? ' data-frame-host="' + frameCode + '"' : '') + '>' + avatarHtml + '</span>';
  }

  function mountAll(root) {
    if (!root) return;
    root.querySelectorAll('[data-frame-host]').forEach(function (element) {
      mount(element, element.getAttribute('data-frame-host'));
    });
  }

  /** Thuộc tính HTML cho tên có màu (chuỗi rỗng khi màu mặc định). */
  function nameAttr(style) {
    return style && /^[a-z]+$/.test(style) ? ' data-name-style="' + style + '"' : '';
  }

  function applyUser(user) {
    document.querySelectorAll('[data-avatar-frame]').forEach(function (host) {
      mount(host, user && user.avatarFrame);
    });
    document.querySelectorAll('[data-user-name]').forEach(function (element) {
      applyNameStyle(element, user && user.nameStyle);
    });
  }

  // Trang vẽ avatar người khác (bình luận…) trước khi file này nạp xong: gắn khung cho những chỗ đã chờ sẵn.
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function () { mountAll(document); }, { once: true });
  } else {
    mountAll(document);
  }

  window.JlasAvatarFrame = {
    FRAMES: FRAMES,
    markup: markup,
    mount: mount,
    host: host,
    mountAll: mountAll,
    nameAttr: nameAttr,
    applyNameStyle: applyNameStyle,
    applyUser: applyUser
  };
})();
