(function () {
  'use strict';
  window.ShadowingPreparationPreflight = {
    create: function (dialog, options) {
      const primary = dialog.querySelector('[data-preflight-primary]');
      const status = dialog.querySelector('[data-preflight-status]');
      const detail = dialog.querySelector('[data-preflight-detail]');
      const reopen = options.reopen;
      const steps = Array.from(dialog.querySelectorAll('[data-preflight-step]'));
      let phase = 'sources';
      let failedPhase = 'sources';

      function open() {
        if (!dialog.open) dialog.showModal();
        document.documentElement.classList.add('preparation-preflight-open');
        reopen.hidden = true;
      }

      function close() {
        dialog.close();
      }

      function setPhase(next, message) {
        phase = next;
        dialog.dataset.phase = next;
        const active = ['sources', 'analysis', 'finalizing'].indexOf(next);
        const failure = steps.findIndex(step => step.dataset.preflightStep === failedPhase);
        steps.forEach(function (step, index) {
          const complete = next === 'ready' || index < (next === 'error' ? failure : active);
          const current = index === (next === 'error' ? failure : active);
          step.classList.toggle('is-complete', complete);
          step.classList.toggle('is-current', current);
          step.classList.toggle('is-failed', next === 'error' && current);
          step.querySelector('[data-preflight-step-status]').textContent = complete ? 'Hoàn tất'
            : current ? next === 'error' ? 'Chưa xong' : 'Đang xử lý' : 'Chờ';
        });
        primary.disabled = next !== 'ready' && next !== 'error';
        primary.textContent = next === 'ready' ? 'Tiếp tục' : next === 'error' ? 'Thử lại' : 'Đang chuẩn bị…';
        status.textContent = next === 'sources' ? 'Tải phụ đề của video'
          : next === 'analysis' ? 'Đang phân tích các đoạn phụ đề và tìm dẫn chứng…'
          : next === 'finalizing' ? 'Đang cập nhật các dạng câu hỏi…'
          : next === 'error' ? 'Chưa hoàn tất chuẩn bị' : 'Đã chuẩn bị xong';
        detail.textContent = message || (next === 'analysis'
          ? 'Việc phân tích có thể mất một chút thời gian. Bạn có thể xem câu hỏi đã có trong khi chờ.'
          : 'Bạn có thể xem câu hỏi đã có trong khi chờ chuẩn bị.');
        dialog.querySelector('.preparation-preflight-steps').setAttribute('aria-busy', String(next !== 'ready' && next !== 'error'));
        reopen.textContent = next === 'ready' ? 'Xem kết quả chuẩn bị'
          : next === 'error' ? 'Thử chuẩn bị lại' : 'Xem tiến trình chuẩn bị';
      }

      dialog.querySelectorAll('[data-preflight-close]').forEach(button => button.addEventListener('click', close));
      dialog.addEventListener('close', function () {
        document.documentElement.classList.remove('preparation-preflight-open');
        reopen.hidden = false;
        // Native dialog returns focus automatically; send it to the next useful action when ready.
        if (phase === 'ready' && !options.next.disabled) options.next.focus();
        else reopen.focus();
      });
      reopen.addEventListener('click', open);
      primary.addEventListener('click', function () {
        if (phase === 'ready') close();
        else if (phase === 'error') options.retry();
      });

      return {
        get ready() { return phase === 'ready'; },
        begin: function () { setPhase('sources'); open(); },
        video: function (title, count) {
          const name = dialog.querySelector('[data-preflight-video]');
          name.textContent = title;
          name.title = title;
          dialog.querySelector('[data-preflight-count]').textContent = count + ' dòng phụ đề';
        },
        phase: function (next) { setPhase(next); },
        complete: function (remaining) {
          const kinds = Object.values(remaining || {}).filter(count => Number(count) > 0).length;
          setPhase('ready', kinds ? 'Có ' + kinds + ' dạng câu hỏi còn dẫn chứng để tạo. Tiếp tục để chọn mức nghe và số câu.'
            : 'Chưa có dẫn chứng cho câu hỏi mới. Bạn có thể xem lại các câu đã có.');
        },
        fail: function (message) { failedPhase = phase; setPhase('error', message); }
      };
    }
  };
}());
