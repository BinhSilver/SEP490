/* Guide to the buttons of the Shadowing exercise page (engine: js/components/page-guide.js). Short, for skimming. */
(function () {
  'use strict';

  const workspace = document.getElementById('exercise-workspace');
  if (!workspace || !window.JlasPageGuide) return;
  const hoverControls = window.matchMedia('(min-width: 861px) and (hover: hover)');

  window.ShadowingExerciseGuide = window.JlasPageGuide.setup({
    key: 'jlas.shadowing.exerciseGuide.v1',
    button: document.getElementById('exercise-guide-open'),
    label: 'Hướng dẫn các nút trong bài luyện',
    // The session has loaded once the workspace shows.
    isReady: function () { return !workspace.hidden; },
    steps: function () {
      return [
        ['#exercise-listen-button', 'Phát và dừng video', hoverControls.matches
          ? ['Bấm: phát, bấm lại để dừng', 'Nút ẩn đi? Rê chuột vào video là hiện']
          : 'Bấm để phát, bấm lại để dừng.'],
        ['#exercise-video-timeline', 'Thanh tiến trình', [
          'Chấm cam: câu hỏi',
          'Chấm xanh: câu đã làm, bấm để xem lại',
          'Bấm hoặc kéo thanh: tua video'
        ]],
        ['#exercise-speed-control', 'Tốc độ phát', ['0.75×: nghe chậm hơn', 'Tới 2×: nghe nhanh hơn']],
        ['#exercise-subtitle-toggle', 'Bật/tắt phụ đề', 'Tắt để luyện nghe không nhìn chữ.'],
        ['#exercise-furigana-toggle', 'Furigana', 'Hiện cách đọc trên chữ Hán.'],
        ['[data-media-action="mute"][data-media-target="exercise-media"]', 'Âm thanh', 'Tắt hoặc bật tiếng.'],
        ['#exercise-live-subtitle', 'Phụ đề đang phát', 'Câu đang nghe, kèm bản dịch.'],
        ['#exercise-transcript-box', 'Phụ đề cả bài', [
          'Bấm một dòng: phát từ câu đó',
          'Dòng có cờ: câu hỏi',
          'Nút “Ẩn”: thu gọn cho rộng chỗ trả lời'
        ]],
        ['#exercise-answer-box', 'Trả lời câu hỏi', [
          'Tới câu có cờ: video tự dừng',
          'Chọn hoặc gõ đáp án',
          'Sai: “Thử lại” hoặc “Hiện đáp án”',
          'Xong: “Tiếp tục video”'
        ]]
      ];
    }
  });
}());
