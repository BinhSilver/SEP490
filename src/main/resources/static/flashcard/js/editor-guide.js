/* Guide to the flashcard set editor (engine: js/components/page-guide.js). Short, for skimming. */
(function () {
  'use strict';

  if (!window.JlasPageGuide) return;
  const list = document.getElementById('card-list');

  window.FlashcardEditorGuide = window.JlasPageGuide.setup({
    key: 'jlas.flashcard.editorGuide.v1',
    button: document.getElementById('editor-guide-open'),
    label: 'Hướng dẫn tạo bộ thẻ từ',
    // Wait for the editor to draw its first card, so the card steps have something to point at.
    isReady: function () { return Boolean(list && list.querySelector('.edit-card')); },
    steps: function () {
      return [
        ['.editor-meta__title', 'Tên bộ thẻ', ['Bắt buộc: 3–100 ký tự', 'Ví dụ: Từ vựng N5 · Bài 1']],
        ['.editor-meta__privacy', 'Quyền riêng tư', [
          'Chỉ mình tôi: học riêng',
          'Công khai: được kiểm tra trước khi hiện cho mọi người'
        ]],
        ['.editor-meta__folder', 'Thư mục', ['Xếp bộ thẻ cho dễ tìm', 'Nút +: tạo thư mục mới']],
        ['.editor-meta__description', 'Mô tả', 'Không bắt buộc.'],
        ['#card-list .edit-card', 'Một thẻ từ', [
          'Bắt buộc: Từ vựng, Nghĩa',
          'Tùy chọn: Cách đọc, Hán Việt, Ảnh',
          'Enter: sang ô tiếp, hết ô thì thêm thẻ mới'
        ]],
        ['#card-list .table-icon-btn--ai', 'AI điền', [
          'Nhập Từ vựng trước',
          'Bấm: AI điền cách đọc, nghĩa, Hán Việt còn trống'
        ]],
        ['.editor-toolbar__group .chk', 'Chọn nhiều thẻ', [
          'Tích “Chọn” trên thẻ, hoặc “Chọn tất cả”',
          'Rồi: AI điền hoặc xóa cùng lúc'
        ]],
        ['#btn-import', 'Nhập nhanh từ danh sách', [
          'Dán danh sách từ',
          'Nhập file Excel, CSV, TXT',
          'Nhờ AI tạo thẻ từ ghi chú',
          'Luôn xem trước rồi mới thêm'
        ]],
        ['#btn-add-card', 'Thêm thẻ', 'Thêm một thẻ trống.'],
        ['#btn-save', 'Lưu bộ thẻ', ['Tự lưu nháp: khi bạn sửa', 'Xong: bấm “Lưu bộ thẻ”']]
      ];
    }
  });
}());
