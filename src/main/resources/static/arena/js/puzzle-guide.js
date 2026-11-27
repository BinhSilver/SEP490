/* First-play guide for the three puzzle games (engine: js/components/page-guide.js). Short, for skimming.
   Steps whose element is not on screen are left out, so the drawer and the guesser each see their own. */
(function () {
  'use strict';

  if (!window.JlasPageGuide) return;
  var root = document.querySelector('.puzzle-page');
  var game = document.querySelector('[data-game]');
  if (!root || !game) return;
  var slug = root.dataset.gameSlug;
  var answer = ['#typing-slots', 'Ô trả lời', [
    'Mỗi ô là một chữ kana của đáp án',
    'Gõ Romaji (neko) hoặc tiếng Nhật, Enter để kiểm tra'
  ]];
  var side = ['.puzzle-side', 'Bảng điểm', [
    'Chấm tròn: các lượt đã chơi và lượt hiện tại',
    'Thẻ người chơi: điểm, ✓ khi đã giải xong'
  ]];
  var STEPS = {
    sphinx: [
      ['[data-question]', 'Câu đố của Nhân Sư', ['Đọc câu đố tiếng Nhật', 'Đáp án là một từ, đoán xem đó là gì']],
      answer,
      ['[data-timer]', 'Đồng hồ', ['Hết giờ là lộ đáp án', 'Đấu đôi: đúng trong 10 giây đầu được 3 điểm, sau đó 2 điểm']],
      side,
      ['[data-streak]', 'Chuỗi đúng', ['Số câu đúng liên tiếp', 'Bỏ lỡ một câu thì chuỗi về 0']]
    ],
    'picture-rebus': [
      ['[data-images]', 'Các hình gợi ý', ['Mỗi hình là một phần ý nghĩa', 'Ghép ý các hình thành một từ Kanji']],
      ['[data-hint-timer]', 'Gợi ý thêm', 'Cứ 30 giây mở thêm một gợi ý.'],
      answer,
      ['[data-timer]', 'Đồng hồ', 'Hết giờ là lộ đáp án.'],
      side,
      ['[data-streak]', 'Chuỗi đúng', ['Số câu đúng liên tiếp', 'Bỏ lỡ một câu thì chuỗi về 0']]
    ],
    'draw-guess': [
      ['[data-secret]', 'Từ bạn cần vẽ', ['Chỉ bạn thấy từ này', 'Vẽ cho bạn chơi đoán ra, không viết chữ']],
      ['[data-tools]', 'Công cụ vẽ', ['Bút, Đổ màu (tô kín một vùng), Tẩy', 'Chọn màu và cỡ nét ngay trên thanh', 'Ctrl+Z: hoàn tác']],
      ['[data-canvas]', 'Bảng vẽ', 'Hai người thay nhau vẽ mỗi lượt.'],
      answer,
      ['[data-guess-panel]', 'Lượt đoán', ['Mọi lượt đoán hiện ở đây cho cả hai cùng thấy', 'Người vẽ xem để biết vẽ thêm gì']]
    ]
  };
  var steps = STEPS[slug];
  if (!steps) return;

  window.PuzzleGameGuide = window.JlasPageGuide.setup({
    key: 'jlas.puzzle.' + slug + '.guide.v1',
    button: document.getElementById('puzzle-guide-open'),
    label: 'Hướng dẫn chơi',
    // Open once the first round is on screen, so every step has something to point at.
    isReady: function () { return !game.hidden; },
    steps: function () { return steps; }
  });
}());
