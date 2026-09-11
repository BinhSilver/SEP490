/* Trang Nhập từ — format: Kanji - Hiragana : Nghĩa (Hán Việt) */
(function () {
  'use strict';

  document.addEventListener('DOMContentLoaded', function () {
    document.getElementById('do-import').addEventListener('click', doImport);
    document.getElementById('btn-ai-hanviet').addEventListener('click', genHanViet);
  });

  async function genHanViet() {
    var text = document.getElementById('import-text').value;
    if (!text.trim()) {
      FlashShell.showToast('Nhập danh sách từ trước', 'err');
      return;
    }

    if (window.JlasUI && JlasUI.showAiProgress) {
      JlasUI.showAiProgress({
        title: 'Tạo Hán Việt bằng AI 🌸',
        category: 'IMPORT ASSISTANT',
        subtitle: 'Đang phân tích danh sách từ vựng và tự động bổ sung Hán Việt...',
        total: 100,
        unit: '%',
        current: 25,
        percent: 25,
        status: 'Đang gửi danh sách từ tới AI...'
      });
    }

    try {
      if (window.JlasUI && JlasUI.updateAiProgress) {
        JlasUI.updateAiProgress({ percent: 65, status: 'AI đang phân tích và dịch Hán Việt...' });
      }

      var res = await FlashApi.post('/api/flashcards/import/generate-hanviet', { text: text });
      document.getElementById('import-text').value = res.text || text;

      if (window.JlasUI && JlasUI.completeAiProgress) {
        JlasUI.completeAiProgress('Đã bổ sung Hán Việt! 🌸', function () {
          FlashShell.showToast('Đã bổ sung Hán Việt (AI)', 'ok');
        });
      } else {
        FlashShell.showToast('Đã bổ sung Hán Việt (AI)', 'ok');
      }
    } catch (e) {
      if (window.JlasUI && JlasUI.hideAiProgress) JlasUI.hideAiProgress();
      FlashShell.showToast(e.message || 'Generate thất bại', 'err');
    }
  }

  async function doImport() {
    if (!FlashShell.requireLogin('Đăng nhập để nhập từ')) return;
    var title = document.getElementById('import-name').value.trim();
    var text = document.getElementById('import-text').value;
    if (!title) {
      FlashShell.showToast('Nhập tên bộ từ', 'err');
      return;
    }
    if (!text.trim()) {
      FlashShell.showToast('Nhập danh sách từ', 'err');
      return;
    }
    try {
      var set = await FlashApi.post('/api/flashcards/import', {
        title: title,
        description: document.getElementById('import-desc').value.trim() || null,
        visibility: document.getElementById('import-visibility').value,
        text: text
      });
      FlashShell.showToast('Đã nhập ' + (set.totalCards || 0) + ' thẻ', 'ok');
      setTimeout(function () {
        location.href = '/flashcard/editor.html?id=' + set.id;
      }, 500);
    } catch (e) {
      FlashShell.showToast(e.message || 'Nhập thất bại', 'err');
    }
  }
})();
