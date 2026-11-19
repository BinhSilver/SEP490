/* Guide to the Shadowing "Tạo bài học mới" page (engine: js/components/page-guide.js). Explains the open tab. */
(function () {
  'use strict';

  if (!window.JlasPageGuide) return;

  window.ShadowingCreateGuide = window.JlasPageGuide.setup({
    key: 'jlas.shadowing.createGuide.v1',
    button: document.getElementById('create-guide-open'),
    label: 'Hướng dẫn tạo bài Shadowing',
    steps: function () {
      const ai = ['Bật: AI thêm cách đọc trên chữ Hán và dịch từng câu', 'Tắt: tạo nhanh hơn, không có furigana và bản dịch AI'];
      return [
        ['.create-mode-tabs', 'Chọn cách tạo bài', [
          'Tải file lên: video hoặc audio trên máy',
          'Link YouTube: video YouTube tiếng Nhật',
          'Đổi tab rồi bấm “Hướng dẫn” để xem tab kia'
        ]],
        ['#create-quota', 'Lượt tạo trong tuần', [
          'Mỗi tuần: có lượt tạo miễn phí',
          'Hết lượt: trả bằng CP, luôn hỏi trước khi trừ'
        ]],
        // Upload a file
        ['#dropzone', 'Chọn file', [
          'Kéo thả hoặc bấm vào ô này',
          'Video: MP4, MOV, WebM',
          'Audio: MP3, WAV, M4A, OGG',
          'Tối đa: 20 phút (link YouTube thì không giới hạn)'
        ]],
        ['#panel-file .furigana-option', 'Tạo furigana + dịch bằng AI', ai],
        // YouTube link
        ['.youtube-url-field', 'Dán link YouTube', 'Link video tiếng Nhật (youtube.com hoặc youtu.be).'],
        ['#analyze-btn', 'Phân tích video', [
          'Kiểm tra: tiếng Nhật, phụ đề, có được phát ngoài YouTube',
          'Xong: tùy chọn tạo bài hiện bên dưới'
        ]],
        ['#panel-youtube .furigana-option', 'Tạo furigana + dịch bằng AI', ai]
      ];
    }
  });
}());
