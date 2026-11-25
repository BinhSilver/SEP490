/* [JLAS-W0108] path-io-core.js (F240, mentor + owner 24/11): the entry and exit of a path, drawn from
 * GET /api/learning/path-io. Pure builders (no DOM, no fetch): one summary line per path card
 * ("Bắt đầu … → Kết thúc …" + progress) and the popup of one level or need (word / kanji / grammar counts and
 * three can-do lines written for this site, not copied from the JLPT texts). */
(function (root, factory) {
  var api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.PathIoCore = api;
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  var CAN_DO = {
    'Nhập môn': ['Đọc và viết được hiragana, katakana.', 'Đọc to được từ ngắn viết bằng kana.', 'Sẵn sàng học từ vựng và kanji N5.'],
    N5: ['Đọc được câu ngắn viết bằng kana và kanji cơ bản về đời sống hằng ngày.',
      'Nghe hiểu hội thoại chậm, ngắn trong lớp học và quanh nhà.', 'Tự giới thiệu, hỏi giờ, hỏi giá, nói điều mình thích.'],
    N4: ['Đọc được đoạn văn ngắn về chủ đề quen thuộc.', 'Nghe hiểu hội thoại hằng ngày nói hơi chậm.',
      'Kể việc đã làm, rủ rê, xin phép, nhờ vả bằng câu đơn giản.'],
    N3: ['Đọc hiểu bài viết cụ thể về chủ đề thường ngày, nắm ý chính tiêu đề báo.',
      'Nghe hiểu hội thoại tốc độ gần tự nhiên và nắm quan hệ người nói.', 'Trình bày ý kiến, giải thích lý do một cách mạch lạc.'],
    N2: ['Đọc bài báo, bình luận dễ và nắm được lập luận.', 'Nghe hiểu tin tức, hội thoại tự nhiên trong nhiều tình huống.',
      'Trao đổi công việc, viết thư và báo cáo đơn giản.'],
    N1: ['Đọc văn bản phức tạp, trừu tượng và hiểu cả ý ngầm.', 'Nghe hiểu bài giảng, tin tức, hội thoại tốc độ tự nhiên.',
      'Tranh luận, thuyết trình và xử lý tình huống chuyên môn.']
  };
  var NEED_CAN_DO = {
    TRAVEL: ['Làm thủ tục ở sân bay, khách sạn (check-in, check-out).', 'Hỏi đường, mua vé tàu xe.', 'Gọi món, hỏi giá, trả tiền khi mua sắm.'],
    DAILY_LIFE: ['Chào hỏi và nói chuyện với hàng xóm, đồng nghiệp.', 'Đi chợ, siêu thị, bưu điện, ngân hàng.', 'Đi khám bệnh, nói triệu chứng đơn giản.'],
    BA: ['Báo cáo tiến độ, xác nhận yêu cầu với khách hàng Nhật.', 'Hỏi lại và làm rõ đặc tả trong cuộc họp.', 'Viết mail công việc ngắn, lịch sự.'],
    WORK: ['Chào hỏi, báo cáo – liên lạc – thảo luận ở nơi làm việc.', 'Nhận và xác nhận chỉ thị của cấp trên.', 'Nói chuyện với khách hàng bằng kính ngữ cơ bản.']
  };

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }
  function levelBtn(label, key) {
    return '<button type="button" class="mp-io__lv" data-io-level="' + esc(key) + '">' + esc(label) + '</button>';
  }

  /** The card line: "Bắt đầu: N4 — theo Smart Test 20/11 → Kết thúc: N3 · đạt khi …" plus the progress line. */
  function lineHtml(io) {
    if (!io || !io.entry || !io.exit) return '';
    var e = io.entry, x = io.exit;
    var start, end;
    if (io.kind === 'COMM') {
      start = esc(e.label);
      end = levelBtn(x.label, 'NEED:' + (io.goal || '')) + ' · ' + esc(x.condition);
    } else {
      var reason = e.reason || ''; // the server already names the Smart Test day
      start = levelBtn(e.label, e.level) + (reason ? ' — ' + esc(reason) : '');
      end = levelBtn(x.label, x.level) + ' · ' + esc(x.condition);
    }
    return '<div class="mp-io">'
      + '<p class="mp-io__row"><span class="mp-io__k">Bắt đầu:</span> ' + start + '</p>'
      + '<p class="mp-io__row"><span class="mp-io__k">Kết thúc:</span> ' + end + '</p>'
      + (io.progress ? '<p class="mp-io__progress">' + esc(io.progress) + '</p>' : '')
      + (io.kind === 'JLPT' && io.retestUrl && !(x.done) ? '<a class="jp-link-btn mp-io__retest" href="' + esc(io.retestUrl) + '">Làm lại Smart Test</a>' : '')
      + '</div>';
  }

  /** Popup body for one level (N5…N1, Nhập môn) or a need ("NEED:TRAVEL"). */
  function popupHtml(key, levels) {
    var title, counts = '', lines;
    if (String(key).indexOf('NEED:') === 0) {
      var goal = String(key).slice(5);
      title = 'Bạn làm được gì khi xong';
      lines = NEED_CAN_DO[goal] || [];
    } else {
      title = 'Chuẩn đầu ra ' + key;
      lines = CAN_DO[key] || [];
      var info = (levels || []).filter(function (l) { return l && l.level === key; })[0];
      if (info) {
        counts = '<p class="mp-io__counts">' + Number(info.vocab || 0) + ' từ · ' + Number(info.kanji || 0) + ' kanji · '
          + Number(info.grammar || 0) + ' mẫu ngữ pháp</p>';
      }
    }
    return '<h2 class="mp-io__title">' + esc(title) + '</h2>' + counts
      + (lines.length ? '<ul class="mp-io__cando">' + lines.map(function (l) { return '<li>' + esc(l) + '</li>'; }).join('') + '</ul>' : '');
  }

  return { lineHtml: lineHtml, popupHtml: popupHtml, CAN_DO: CAN_DO, NEED_CAN_DO: NEED_CAN_DO, esc: esc };
});
