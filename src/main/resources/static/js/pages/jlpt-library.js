(function () {
  'use strict';

  var exams = [];
  var activeLevel = 'N3';
  var page = 1;
  var PAGE_SIZE = 10;
  var grid = document.getElementById('jlptExamGrid');
  var title = document.getElementById('examListTitle');
  var count = document.getElementById('examCount');
  var empty = document.getElementById('jlptExamEmpty');
  var pagination = document.getElementById('jlptPagination');
  var modeDialog = document.getElementById('jlptModeDialog');

  function esc(value) {
    return String(value == null ? '' : value).replace(/[&<>"']/g, function (character) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[character];
    });
  }

  function card(exam) {
    var period = exam.year == null || exam.month == null ? 'Đề thi thử' : 'Tháng ' + exam.month + ' · ' + exam.year;
    return '<article class="jlpt-exam-card"><div class="jlpt-exam-card__top"><span class="jlpt-level">' + esc(exam.level) + '</span><span>' + statusLabel(exam.status) + '</span></div>' +
      '<div class="jlpt-exam-card__body"><h3>' + esc(period) + '</h3><p>Gồm đầy đủ từ vựng, ngữ pháp, đọc hiểu và nghe hiểu.</p></div>' +
      '<div class="jlpt-exam-card__stats"><span><i class="material-symbols-outlined">format_list_numbered</i>' + exam.questionCount + ' câu</span><span><i class="material-symbols-outlined">timer</i>' + exam.durationMinutes + ' phút</span></div>' +
      '<button class="jlpt-exam-card__start" type="button" data-exam-code="' + esc(exam.code) + '" data-exam-title="' + esc(exam.level + ' · ' + period) + '">Bắt đầu làm đề <i class="material-symbols-outlined">arrow_forward</i></button></article>';
  }

  function statusLabel(status) { return status === 'PUBLISHED' ? 'ĐỀ ĐÃ XUẤT BẢN' : 'ĐỀ CHỜ DUYỆT'; }

  function render() {
    var visible = exams.filter(function (exam) { return exam.level === activeLevel; });
    var pages = Math.ceil(visible.length / PAGE_SIZE) || 1;
    page = Math.min(page, pages);
    title.textContent = 'Đề thi JLPT ' + activeLevel;
    count.textContent = visible.length + ' đề';
    grid.innerHTML = visible.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE).map(card).join('');
    empty.hidden = visible.length > 0;
    pagination.hidden = visible.length <= PAGE_SIZE;
    pagination.innerHTML = '<button type="button" data-page="' + (page - 1) + '" ' + (page === 1 ? 'disabled' : '') + ' aria-label="Trang trước"><span class="material-symbols-outlined">chevron_left</span></button>' +
      Array.from({ length: pages }, function (_, index) { var number = index + 1; return '<button type="button" data-page="' + number + '" class="' + (number === page ? 'is-active' : '') + '">' + number + '</button>'; }).join('') +
      '<button type="button" data-page="' + (page + 1) + '" ' + (page === pages ? 'disabled' : '') + ' aria-label="Trang sau"><span class="material-symbols-outlined">chevron_right</span></button>';
  }

  function openModeDialog(button) {
    var code = button.dataset.examCode;
    document.getElementById('jlptModeExamTitle').textContent = button.dataset.examTitle;
    document.getElementById('jlptModePractice').href = '/jlpt/exam?code=' + encodeURIComponent(code) + '&mode=practice';
    document.getElementById('jlptModeExam').href = '/jlpt/exam?code=' + encodeURIComponent(code) + '&mode=exam';
    modeDialog.showModal();
  }

  function load() {
    grid.innerHTML = '<p>Đang tải danh sách đề…</p>';
    fetch('/api/jlpt/exams?level=' + encodeURIComponent(activeLevel))
      .then(function (response) { return response.json().then(function (body) { if (!response.ok || body.success === false) throw new Error(body.message || 'Không tải được danh sách đề.'); return body.data; }); })
      .then(function (data) { exams = Array.isArray(data) ? data : []; render(); })
      .catch(function (error) { exams = []; grid.innerHTML = '<p>' + esc(error.message) + '</p>'; count.textContent = '0 đề'; empty.hidden = true; pagination.hidden = true; });
  }

  grid.addEventListener('click', function (event) {
    var button = event.target.closest('[data-exam-code]');
    if (button) openModeDialog(button);
  });
  document.getElementById('jlptModeClose').addEventListener('click', function () { modeDialog.close(); });
  modeDialog.addEventListener('click', function (event) { if (event.target === modeDialog) modeDialog.close(); });
  document.querySelector('.jlpt-library-levels').addEventListener('click', function (event) {
    var button = event.target.closest('[data-level]');
    if (!button) return;
    activeLevel = button.dataset.level;
    page = 1;
    Array.prototype.forEach.call(document.querySelectorAll('[data-level]'), function (item) {
      var selected = item === button;
      item.classList.toggle('is-active', selected);
      item.setAttribute('aria-selected', String(selected));
    });
    load();
  });
  pagination.addEventListener('click', function (event) {
    var button = event.target.closest('[data-page]');
    if (!button || button.disabled) return;
    page = Number(button.dataset.page);
    render();
    window.scrollTo({ top: 0, behavior: 'smooth' });
  });

  load();
})();
