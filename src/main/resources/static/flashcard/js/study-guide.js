/* Guide to the flashcard study page (engine: js/components/page-guide.js). Short, for skimming. */
(function () {
  'use strict';

  if (!window.JlasPageGuide) return;
  const stage = document.getElementById('study-stage');

  window.FlashcardStudyGuide = window.JlasPageGuide.setup({
    key: 'jlas.flashcard.studyGuide.v1',
    button: document.getElementById('study-guide-open'),
    label: 'Hướng dẫn học flashcard',
    // Wait for the first card, so the card and action steps have something to point at.
    isReady: function () { return Boolean(stage && !stage.hidden); },
    steps: function () {
      return [
        ['.study-mode-nav [data-study-mode="BROWSE"]', 'Lướt thẻ', [
          'Xem lần lượt từng thẻ',
          'Bấm thẻ hoặc phím Space: lật',
          'Đánh dấu: ✓ Đã nhớ / ✗ Chưa thuộc'
        ]],
        ['.study-mode-nav [data-study-mode="RECALL"]', 'Ghi nhớ', [
          'Lật thẻ rồi tự chấm 0–5',
          '0–2: chưa nhớ, thẻ quay lại sớm',
          '3–5: nhớ được, thẻ quay lại thưa dần',
          'Chấm 5 đủ số lần: thẻ được tính là đã thuộc'
        ]],
        ['#btn-settings', 'Cài đặt học', [
          'Chọn mặt trước và mặt sau hiển thị gì',
          'Mỗi mặt: 1–3 trường (Kanji, Hiragana, Hán Việt, Nghĩa)',
          'Bấm “Áp dụng” để dùng'
        ]],
        ['#flip-card', 'Thẻ', [
          'Bấm hoặc Space: lật thẻ',
          'Điện thoại: vuốt phải = Đã nhớ, vuốt trái = Chưa thuộc'
        ]],
        ['#study-track-toggle', 'Theo dõi tiến độ', [
          'Bật: ghi lại thẻ nào đã nhớ, thẻ nào chưa',
          'Tắt: chỉ lướt xem, không ghi gì'
        ]],
        ['#study-quality-actions', 'Chấm điểm (Ghi nhớ)', [
          '0, 1, 2: sai',
          'Lật: xem mặt sau',
          '3, 4, 5: đúng, càng cao càng dễ'
        ]]
      ];
    }
  });
}());
