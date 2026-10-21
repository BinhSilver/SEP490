(function () {
  'use strict';
  var $ = function (id) { return document.getElementById(id); };
  var rows = [], dirty = false, activeQuestionType = null, activeQuestionPage = 0;
  var sectionNames = { vocabulary: 'Từ vựng', grammar: 'Ngữ pháp', reading: 'Đọc hiểu', listening: 'Nghe hiểu' };
  function esc(value) { return String(value == null ? '' : value).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); }
  function headers(json) { var h = window.JlasAuth && JlasAuth.authHeaders ? JlasAuth.authHeaders() : {}; if (json) h['Content-Type'] = 'application/json'; return h; }
  async function api(path, options) {
    var response = await fetch(path, Object.assign({ headers: headers(false) }, options || {}));
    var body = await response.json().catch(function () { return {}; });
    if (!response.ok || body.success === false) throw new Error(body.message || 'Không thể kết nối tới máy chủ.');
    return body.data;
  }
  function toast(message, error) {
    var node = $('adminToast'); node.textContent = message; node.classList.toggle('is-error', !!error); node.hidden = false;
    clearTimeout(toast.timer); toast.timer = setTimeout(function () { node.hidden = true; }, 3500);
  }
  function markDirty() { dirty = true; $('saveButton').disabled = false; }
  function filtered() {
    var query = $('searchFilter').value.trim().toLocaleLowerCase('vi'), level = $('levelFilter').value;
    var section = $('sectionFilter').value, enabledOnly = $('enabledOnly').checked;
    return rows.filter(function (r) {
      var name = [r.defaultNameVi, r.defaultNameJa, r.nameVi, r.nameJa, r.code].join(' ').toLocaleLowerCase('vi');
      return (!query || name.includes(query)) && (!level || r.level === level) && (!section || r.section === section) && (!enabledOnly || r.enabled);
    });
  }
  function render() {
    var visible = filtered();
    $('typeRows').innerHTML = visible.map(function (r) {
      var key = esc(r.level + '|' + r.code);
      return '<tr data-key="' + key + '">' +
        '<td><strong>' + esc(r.defaultNameVi || 'Dạng khác') + '</strong><small lang="ja">' + esc(r.defaultNameJa || 'Chưa có tên tiếng Nhật') + '</small><button class="jpta__view" type="button" data-view-questions="' + key + '">Xem câu hỏi <span aria-hidden="true">→</span></button></td>' +
        '<td><span class="jpta__level">' + esc(r.level) + '</span><span class="jpta__section">' + esc(sectionNames[r.section] || r.section) + '</span></td>' +
        '<td><strong class="jpta__count">' + Number(r.questionCount || 0) + '</strong></td>' +
        '<td><input class="jpta__name" data-field="nameVi" maxlength="120" value="' + esc(r.nameVi) + '" placeholder="Mặc định: ' + esc(r.defaultNameVi) + '" aria-label="Tên tiếng Việt" /></td>' +
        '<td><input class="jpta__name" data-field="nameJa" maxlength="120" value="' + esc(r.nameJa) + '" placeholder="Mặc định: ' + esc(r.defaultNameJa || 'Không có') + '" aria-label="Tên tiếng Nhật" /></td>' +
        '<td><input class="jpta__order" data-field="orderIndex" type="number" min="1" max="999" value="' + (r.orderIndex == null ? '' : esc(r.orderIndex)) + '" placeholder="Tự động" aria-label="Thứ tự" /></td>' +
        '<td><label class="jpta__switch"><input data-field="enabled" type="checkbox" ' + (r.enabled ? 'checked' : '') + ' aria-label="Hiển thị ' + esc(r.defaultNameVi) + '" /><span></span></label></td>' +
      '</tr>';
    }).join('');
    $('emptyState').hidden = visible.length > 0;
    $('resultCount').textContent = visible.length + ' / ' + rows.length + ' dạng luyện';
  }
  async function load() {
    $('reloadButton').disabled = true; $('saveButton').disabled = true; $('loadingState').hidden = false; $('emptyState').hidden = true;
    try { rows = await api('/api/admin/jlpt/practice-types') || []; dirty = false; render(); }
    catch (error) { toast(error.message, true); }
    finally { $('loadingState').hidden = true; $('reloadButton').disabled = false; }
  }
  async function save() {
    $('saveButton').disabled = true;
    try {
      await api('/api/admin/jlpt/practice-types', { method: 'PUT', headers: headers(true), body: JSON.stringify(rows.map(function (r) {
        return { level: r.level, code: r.code, enabled: r.enabled, nameVi: r.nameVi, nameJa: r.nameJa, orderIndex: r.orderIndex };
      })) });
      dirty = false; toast('Đã lưu cấu hình dạng luyện.'); await load();
    } catch (error) { $('saveButton').disabled = false; toast(error.message, true); }
  }
  function renderQuestions(data) {
    $('questionsLoading').hidden = true;
    $('questionsError').hidden = true;
    $('questionsEmpty').hidden = data.questions.length > 0;
    $('questionsList').innerHTML = data.questions.map(function (q) {
      var choices = (q.options || []).map(function (option) {
        var correct = q.correctOption === option.number;
        return '<li class="' + (correct ? 'is-correct' : '') + '"><span>' + option.number + '</span><div>' + esc(option.text || '(Không có nội dung)') + (option.furigana ? '<small lang="ja">' + esc(option.furigana) + '</small>' : '') + (option.html ? '<details class="jpta-question__source"><summary>Nội dung HTML của lựa chọn</summary><pre>' + esc(option.html) + '</pre></details>' : '') + '</div>' + (correct ? '<b>Đáp án đúng</b>' : '') + '</li>';
      }).join('');
      var media = (q.imageUrl ? '<img class="jpta-question__image" src="' + esc(q.imageUrl) + '" alt="Hình của câu hỏi" loading="lazy" />' : '') +
        (q.audioUrl ? '<audio class="jpta-question__audio" controls preload="none" src="' + esc(q.audioUrl) + '">Trình duyệt không hỗ trợ audio.</audio>' : '');
      var rawHtml = q.html ? '<details class="jpta-question__source"><summary>Nội dung HTML gốc</summary><pre>' + esc(q.html) + '</pre></details>' : '';
      var context = (q.instruction ? '<div class="jpta-question__context"><b>Hướng dẫn</b><pre>' + esc(q.instruction) + '</pre></div>' : '') +
        (q.passage || q.passageHtml ? '<div class="jpta-question__context"><b>Đoạn văn dùng chung</b><div lang="ja">' + esc(q.passage || q.passageHtml) + '</div>' + (q.passageVi ? '<small>' + esc(q.passageVi) + '</small>' : '') + '</div>' : '');
      return '<article class="jpta-question"><header><span>Câu ' + esc(q.questionNumber) + ' · 問題' + esc(q.mondaiNumber) + '</span><span>' + esc(q.examCode) + ' · ' + esc(q.examTitle) + '</span></header>' + context +
        '<div class="jpta-question__prompt" lang="ja">' + esc(q.prompt || '(Câu hỏi được trình bày bằng HTML hoặc hình ảnh)') + '</div>' +
        (q.furigana ? '<div class="jpta-question__furigana" lang="ja">' + esc(q.furigana) + '</div>' : '') + media +
        (choices ? '<ol class="jpta-question__choices">' + choices + '</ol>' : '<p class="jpta-question__no-answer">Chưa có lựa chọn được lưu.</p>') + rawHtml + '</article>';
    }).join('');
    $('questionsPageLabel').textContent = data.totalQuestions ? 'Trang ' + (data.page + 1) + ' / ' + data.totalPages + ' · ' + data.totalQuestions + ' câu' : '0 câu';
    $('questionsPrev').disabled = data.page <= 0;
    $('questionsNext').disabled = data.totalPages === 0 || data.page >= data.totalPages - 1;
  }
  async function loadQuestions(row, page) {
    activeQuestionType = row; activeQuestionPage = page;
    $('questionsTitle').textContent = row.nameVi || row.defaultNameVi || 'Câu hỏi của dạng luyện';
    $('questionsMeta').textContent = row.level + ' · ' + (sectionNames[row.section] || row.section) + ' · ' + (row.nameJa || row.defaultNameJa || '');
    $('questionsLoading').hidden = false; $('questionsError').hidden = true; $('questionsEmpty').hidden = true; $('questionsList').innerHTML = '';
    $('questionsPageLabel').textContent = '';
    $('questionsPrev').disabled = true; $('questionsNext').disabled = true;
    try {
      var data = await api('/api/admin/jlpt/practice-types/' + encodeURIComponent(row.level) + '/' + encodeURIComponent(row.code) + '/questions?page=' + page + '&size=10');
      if (!data || !Array.isArray(data.questions)) throw new Error('Máy chủ không trả về danh sách câu hỏi hợp lệ.');
      activeQuestionPage = data.page; renderQuestions(data);
    } catch (error) {
      $('questionsLoading').hidden = true; $('questionsErrorText').textContent = error.message;
      $('questionsError').hidden = false;
    }
  }
  function updateRow(input) {
    var tr = input.closest('tr'), parts = tr && tr.dataset.key.split('|'); if (!parts) return;
    var row = rows.find(function (r) { return r.level === parts[0] && r.code === parts[1]; }); if (!row) return;
    var field = input.dataset.field;
    if (field === 'enabled') row.enabled = input.checked;
    else if (field === 'orderIndex') row.orderIndex = input.value === '' ? null : Number(input.value);
    else row[field] = input.value;
    markDirty();
    if (field === 'enabled') render();
  }
  $('typeRows').addEventListener('input', function (event) { if (event.target.matches('[data-field]')) updateRow(event.target); });
  $('typeRows').addEventListener('change', function (event) { if (event.target.matches('[data-field]')) updateRow(event.target); });
  $('typeRows').addEventListener('click', function (event) {
    var button = event.target.closest('[data-view-questions]'); if (!button) return;
    var parts = button.dataset.viewQuestions.split('|');
    var row = rows.find(function (r) { return r.level === parts[0] && r.code === parts[1]; });
    if (row) { $('questionsDialog').showModal(); loadQuestions(row, 0); }
  });
  ['searchFilter', 'levelFilter', 'sectionFilter', 'enabledOnly'].forEach(function (id) { $(id).addEventListener('input', render); $(id).addEventListener('change', render); });
  $('reloadButton').addEventListener('click', function () { if (!dirty || window.confirm('Tải lại sẽ bỏ các thay đổi chưa lưu. Tiếp tục?')) load(); });
  $('saveButton').addEventListener('click', save);
  $('closeQuestions').addEventListener('click', function () { $('questionsDialog').close(); });
  $('retryQuestions').addEventListener('click', function () { if (activeQuestionType) loadQuestions(activeQuestionType, activeQuestionPage); });
  $('questionsDialog').addEventListener('click', function (event) { if (event.target === $('questionsDialog')) $('questionsDialog').close(); });
  $('questionsPrev').addEventListener('click', function () { if (activeQuestionType && activeQuestionPage > 0) loadQuestions(activeQuestionType, activeQuestionPage - 1); });
  $('questionsNext').addEventListener('click', function () { if (activeQuestionType) loadQuestions(activeQuestionType, activeQuestionPage + 1); });
  load();
})();
