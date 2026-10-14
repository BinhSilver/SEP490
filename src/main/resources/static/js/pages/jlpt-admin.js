window.AdminShell.ready.then(function (allowed) {
  'use strict';
  if (!allowed) return;

  var API = '/api/admin/jlpt';
  var state = { exams: [], activeCode: null, content: null, treeFilter: 'all', editingExamCode: null, editingQuestion: null, pendingAction: null };
  var $ = function (id) { return document.getElementById(id); };

  bindEvents();
  loadCatalog();

  function esc(value) { return String(value == null ? '' : value).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); }
  function stripHtml(value) { var el = document.createElement('div'); el.innerHTML = String(value || ''); return el.textContent || ''; }
  function cleanPrompt(value) { return String(value || '').replace(/^\s*\d+\s*[.)．。]\s*/, '').trim(); }

  function api(path, options) {
    options = options || {};
    var headers = window.JlasAuth && typeof JlasAuth.authHeaders === 'function' ? JlasAuth.authHeaders() : {};
    if (options.body) headers['Content-Type'] = 'application/json';
    options.headers = Object.assign(headers, options.headers || {});
    return fetch(API + path, options).then(function (response) {
      return response.json().catch(function () { return {}; }).then(function (body) {
        if (!response.ok || body.success === false) throw new Error(body.message || 'Không thể thực hiện thao tác.');
        return body.data;
      });
    });
  }

  function loadCatalog() {
    return api('/exams').then(function (data) { state.exams = Array.isArray(data) ? data : []; renderCatalog(); })
      .catch(function (error) { state.exams = []; renderCatalog(); toast(error.message); });
  }

  function activeExam() { return state.exams.find(function (exam) { return exam.code === state.activeCode; }) || null; }
  function statusLabel(status) { return ({ PUBLISHED: 'Đã phát hành', DRAFT: 'Bản nháp', ARCHIVED: 'Đã ẩn' })[status] || status; }
  function statusClass(status) { return status === 'PUBLISHED' ? 'is-published' : status === 'ARCHIVED' ? 'is-archived' : 'is-draft'; }
  function allQuestions(content) {
    var rows = [];
    (content && content.sections || []).forEach(function (section, sectionIndex) {
      (section.mondai || []).forEach(function (mondai, mondaiIndex) {
        (mondai.groups || []).forEach(function (group, groupIndex) {
          (group.questions || []).forEach(function (question, questionIndex) { rows.push({ section: section, sectionIndex: sectionIndex, mondai: mondai, mondaiIndex: mondaiIndex, group: group, groupIndex: groupIndex, question: question, questionIndex: questionIndex }); });
        });
      });
    });
    return rows;
  }
  function countQuestions(content) { return allQuestions(content).length; }

  function bindEvents() {
    document.querySelectorAll('[data-dialog-close]').forEach(function (button) { button.addEventListener('click', function () { var dialog = button.closest('dialog'); if (dialog) dialog.close('cancel'); }); });
    $('examSearch').addEventListener('input', renderCatalog); $('levelFilter').addEventListener('change', renderCatalog); $('statusFilter').addEventListener('change', renderCatalog);
    $('createExamButton').addEventListener('click', function () { openExamDialog(); });
    $('examList').addEventListener('click', function (event) {
      var button = event.target.closest('[data-exam-action]'); if (!button) return;
      var exam = state.exams.find(function (item) { return item.code === button.dataset.examCode; }); if (!exam) return;
      if (button.dataset.examAction === 'open') openWorkspace(exam.code);
      if (button.dataset.examAction === 'edit') openExamDialog(exam);
      if (button.dataset.examAction === 'visibility') toggleExamVisibility(exam);
    });
    $('closeWorkspace').addEventListener('click', function () { state.activeCode = null; state.content = null; $('contentWorkspace').hidden = true; $('catalogView').hidden = false; renderCatalog(); window.scrollTo({ top: 0, behavior: 'smooth' }); });
    $('contentTree').addEventListener('click', function (event) { var button = event.target.closest('[data-tree-filter]'); if (!button) return; state.treeFilter = button.dataset.treeFilter; renderWorkspace(); });
    $('questionSearch').addEventListener('input', renderQuestions); $('qualityFilter').addEventListener('change', renderQuestions);
    $('editExamButton').addEventListener('click', function () { openExamDialog(activeExam()); }); $('addQuestionButton').addEventListener('click', function () { openQuestionDialog(); });
    $('examForm').addEventListener('submit', saveExam);
    $('questionSection').addEventListener('change', function () { populateMondai(this.value); }); $('questionForm').addEventListener('submit', saveQuestion); $('deleteQuestionButton').addEventListener('click', requestDeleteQuestion);
    $('questionList').addEventListener('click', handleQuestionAction);
    $('confirmDialog').addEventListener('close', function () { var action = this.returnValue === 'confirm' ? state.pendingAction : null; state.pendingAction = null; if (action) Promise.resolve(action()).catch(function (error) { toast(error.message); }); });
  }

  function renderCatalog() {
    var query = $('examSearch').value.trim().toLowerCase(), level = $('levelFilter').value, status = $('statusFilter').value;
    var exams = state.exams.filter(function (exam) { return (!query || (exam.title + ' ' + exam.code).toLowerCase().indexOf(query) >= 0) && (!level || exam.level === level) && (!status || exam.status === status); });
    $('examList').innerHTML = exams.map(function (exam) {
      var date = exam.year == null ? 'Đề thi thử' : 'Tháng ' + exam.month + '/' + exam.year;
      return '<article class="jlpt-admin-exam"><span class="jlpt-admin-exam__level">' + esc(exam.level) + '</span><div><h3>' + esc(exam.title) + '</h3><p>' + esc(exam.code) + ' · ' + esc(date) + '</p><div class="jlpt-admin-exam__meta"><span><i class="material-symbols-outlined">quiz</i>' + Number(exam.questionCount || 0) + ' câu</span><span><i class="material-symbols-outlined">verified</i>' + esc(exam.passingScore || 95) + '/180 điểm</span></div></div><div class="jlpt-admin-exam__side"><span class="jlpt-admin-status ' + statusClass(exam.status) + '">' + statusLabel(exam.status) + '</span><div class="jlpt-admin-exam__actions"><button type="button" data-exam-action="edit" data-exam-code="' + esc(exam.code) + '">Sửa</button><button type="button" data-exam-action="visibility" data-exam-code="' + esc(exam.code) + '">' + (exam.status === 'ARCHIVED' ? 'Hiện lại' : 'Ẩn đề') + '</button><button class="is-open" type="button" data-exam-action="open" data-exam-code="' + esc(exam.code) + '">Soạn nội dung</button></div></div></article>';
    }).join('');
    $('examEmpty').hidden = exams.length > 0;
    $('examStat').textContent = state.exams.length; $('questionStat').textContent = state.exams.reduce(function (sum, exam) { return sum + Number(exam.questionCount || 0); }, 0); $('publishedStat').textContent = state.exams.filter(function (exam) { return exam.status === 'PUBLISHED'; }).length; $('draftStat').textContent = state.exams.filter(function (exam) { return exam.status === 'DRAFT'; }).length;
  }

  function openWorkspace(code) {
    state.activeCode = code; state.treeFilter = 'all'; $('catalogView').hidden = true; $('contentWorkspace').hidden = false;
    return api('/exams/' + encodeURIComponent(code)).then(function (detail) { state.content = adaptContent(detail.content); renderWorkspace(); window.scrollTo({ top: 0, behavior: 'smooth' }); }).catch(function (error) { $('contentWorkspace').hidden = true; $('catalogView').hidden = false; toast(error.message); });
  }

  function adaptContent(content) {
    return { exam: { code: content.code, level: content.level, year: content.year, month: content.month, title: content.title }, sections: (content.parts || []).map(function (part) {
      return { id: part.code, dbId: part.id, nameJa: part.code, nameVi: part.title, mondai: (part.mondai || []).map(function (mondai) {
        var groups = [], byKey = {};
        (mondai.questions || []).forEach(function (question) {
          var key = question.passage ? 'passage:' + question.passage.sourceId : 'question:' + question.id;
          if (!byKey[key]) { byKey[key] = { id: key, passage: question.passage ? (question.passage.html || question.passage.ja || '') : null, questions: [] }; groups.push(byKey[key]); }
          var transcript = question.audioClip ? (question.audioClip.transcript || []) : [];
          byKey[key].questions.push({ id: question.id, number: question.number, type: question.questionType || mondai.format || 'OTHER', prompt: question.html || question.text || '', choices: (question.options || []).map(function (option) { return { id: option.number, content: option.html || option.text || '' }; }), correctChoice: question.correctOption, correctOrder: question.correctOrder, score: question.score, imageRef: question.imageUrl, hasImage: !!question.imageUrl, sourcePage: question.sourcePage, explanation: question.explanation, scriptText: transcript.map(function (segment) { return segment.ja || ''; }).filter(Boolean).join('\n'), scriptTranslation: transcript.map(function (segment) { return segment.vi || ''; }).filter(Boolean).join('\n'), audioRequired: !!question.audioClip || !!mondai.audioUrl, audioUrl: question.audioClip ? question.audioClip.audioUrl : '', audioStart: question.audioClip ? question.audioClip.start : '', audioEnd: question.audioClip ? question.audioClip.end : '' });
        });
        return { id: mondai.id, number: mondai.number, type: mondai.format, nameJa: '問題' + mondai.number, nameVi: mondai.format || 'Nội dung', instructionJa: stripHtml(mondai.instructionHtml || ''), groups: groups };
      }) };
    }) };
  }

  function renderWorkspace() { var exam = activeExam(); if (!exam || !state.content) return; $('activeLevel').textContent = exam.level; $('activeExamTitle').textContent = exam.title; $('activeExamCode').textContent = exam.code; renderTree(); renderQuestions(); }
  function sectionIcon(id) { return ({ vocabulary: 'translate', grammar_reading: 'menu_book', listening: 'headphones' })[id] || 'folder'; }
  function renderTree() {
    var html = '<button class="' + (state.treeFilter === 'all' ? 'is-active' : '') + '" data-tree-filter="all"><i class="material-symbols-outlined">dashboard</i>Tất cả nội dung<span>' + countQuestions(state.content) + '</span></button>';
    (state.content.sections || []).forEach(function (section, sectionIndex) {
      var sectionKey = 'section:' + sectionIndex;
      html += '<button class="' + (state.treeFilter === sectionKey ? 'is-active' : '') + '" data-tree-filter="' + sectionKey + '"><i class="material-symbols-outlined">' + sectionIcon(section.id) + '</i>' + esc(section.nameVi || section.id) + '<span>' + allQuestions({ sections: [section] }).length + '</span></button>';
      (section.mondai || []).forEach(function (mondai, mondaiIndex) { var key = 'mondai:' + sectionIndex + ':' + mondaiIndex, count = (mondai.groups || []).reduce(function (sum, group) { return sum + (group.questions || []).length; }, 0); html += '<button class="is-mondai ' + (state.treeFilter === key ? 'is-active' : '') + '" data-tree-filter="' + key + '">問題' + esc(mondai.number) + ' · ' + esc(mondai.nameVi || mondai.type || 'Nội dung') + '<span>' + count + '</span></button>'; });
    });
    $('contentTree').innerHTML = html;
  }

  function filteredRows() {
    var rows = allQuestions(state.content), parts = state.treeFilter.split(':'), query = $('questionSearch').value.trim().toLowerCase(), quality = $('qualityFilter').value;
    if (parts[0] === 'section') rows = rows.filter(function (row) { return row.sectionIndex === Number(parts[1]); });
    if (parts[0] === 'mondai') rows = rows.filter(function (row) { return row.sectionIndex === Number(parts[1]) && row.mondaiIndex === Number(parts[2]); });
    if (query) rows = rows.filter(function (row) { return stripHtml(row.question.prompt).toLowerCase().indexOf(query) >= 0 || String(row.question.type || '').toLowerCase().indexOf(query) >= 0; });
    if (quality === 'complete') rows = rows.filter(function (row) { return row.question.correctChoice != null; }); if (quality === 'missing') rows = rows.filter(function (row) { return row.question.correctChoice == null; }); if (quality === 'media') rows = rows.filter(function (row) { return row.question.hasImage || row.question.audioRequired; });
    return rows;
  }

  function renderQuestions() {
    if (!state.content) return;
    var rows = filteredRows(), parts = state.treeFilter.split(':'), title = 'Nội dung đề thi', breadcrumb = 'Tất cả nội dung', description = 'Toàn bộ câu hỏi trong đề';
    if (parts[0] === 'section') { var section = state.content.sections[Number(parts[1])]; title = section.nameVi || section.id; breadcrumb = 'Phần thi · ' + (section.nameJa || section.id); description = 'Các câu hỏi thuộc ' + title.toLowerCase(); }
    if (parts[0] === 'mondai') { var selectedSection = state.content.sections[Number(parts[1])], mondai = selectedSection.mondai[Number(parts[2])]; title = '問題' + mondai.number + ' · ' + (mondai.nameVi || mondai.type); breadcrumb = (selectedSection.nameVi || selectedSection.id) + ' · ' + (mondai.nameJa || mondai.type); description = mondai.instructionJa || 'Nhóm câu hỏi'; }
    $('editorTitle').textContent = title; $('editorBreadcrumb').textContent = breadcrumb; $('editorDescription').textContent = description; $('visibleQuestionCount').textContent = rows.length; $('completeQuestionCount').textContent = rows.filter(function (row) { return row.question.correctChoice != null; }).length; $('missingAnswerCount').textContent = rows.filter(function (row) { return row.question.correctChoice == null; }).length;
    $('questionList').innerHTML = rows.map(function (row) { var q = row.question, key = [row.sectionIndex, row.mondaiIndex, row.groupIndex, row.questionIndex].join('-'), media = q.audioRequired ? 'Audio' : q.hasImage ? 'Hình ảnh' : 'Văn bản'; return '<article class="jlpt-admin-question"><span class="jlpt-admin-question__number">' + esc(q.number) + '</span><div><h3 lang="ja">' + esc(cleanPrompt(stripHtml(q.prompt)) || '(Chưa có nội dung câu hỏi)') + '</h3><div class="jlpt-admin-question__meta"><span>' + esc(row.section.nameVi || row.section.id) + '</span><span>問題' + esc(row.mondai.number) + '</span><span>' + esc(q.type || row.mondai.type || 'QUESTION') + '</span><span>' + media + '</span>' + (q.correctChoice == null ? '<span class="jlpt-admin-question__warning">Thiếu đáp án đúng</span>' : '<span>Đáp án ' + q.correctChoice + '</span>') + '</div></div><div class="jlpt-admin-question__actions"><button type="button" data-action="duplicate" data-key="' + key + '" title="Nhân bản"><i class="material-symbols-outlined">content_copy</i></button><button type="button" data-action="edit" data-key="' + key + '" title="Chỉnh sửa"><i class="material-symbols-outlined">edit</i></button><button class="is-delete" type="button" data-action="delete" data-key="' + key + '" title="Xóa"><i class="material-symbols-outlined">delete</i></button></div></article>'; }).join('');
    $('questionEmpty').hidden = rows.length > 0;
  }

  function openExamDialog(exam) {
    state.editingExamCode = exam ? exam.code : null; var form = $('examForm'); form.reset(); $('examDialogTitle').textContent = exam ? 'Sửa thông tin đề' : 'Tạo đề JLPT';
    if (exam) Object.keys(exam).forEach(function (key) { if (form.elements[key]) form.elements[key].value = exam[key] == null ? '' : exam[key]; }); else { form.elements.year.value = new Date().getFullYear(); form.elements.month.value = '7'; form.elements.status.value = 'DRAFT'; form.elements.passingScore.value = '95'; }
    form.elements.code.readOnly = false; $('examDialog').showModal();
  }

  function saveExam(event) {
    event.preventDefault(); var data = Object.fromEntries(new FormData(event.currentTarget).entries());
    var payload = { code: data.code.trim().toUpperCase(), title: data.title.trim(), level: data.level, year: data.year ? Number(data.year) : null, month: data.month ? Number(data.month) : null, status: data.status, passingScore: Number(data.passingScore || 95) }, editing = state.editingExamCode;
    confirmAction(editing ? 'Lưu thay đổi của đề?' : 'Tạo đề JLPT mới?', editing ? 'Thông tin của ' + editing + ' sẽ được cập nhật trong database.' : 'Đề ' + payload.code + ' sẽ được tạo ở trạng thái ' + statusLabel(payload.status) + '.', function () {
      return (editing ? api('/exams/' + encodeURIComponent(editing), { method: 'PUT', body: JSON.stringify(payload) }) : api('/exams', { method: 'POST', body: JSON.stringify(payload) })).then(function () { $('examDialog').close(); if (state.activeCode === editing) state.activeCode = payload.code; return loadCatalog(); }).then(function () { toast(editing ? 'Đã cập nhật thông tin đề.' : 'Đã tạo đề mới.'); if (state.activeCode) return openWorkspace(state.activeCode); });
    }, { confirmLabel: editing ? 'Lưu thay đổi' : 'Tạo đề' });
  }

  function toggleExamVisibility(exam) {
    var hidden = exam.status === 'ARCHIVED', nextStatus = hidden ? 'DRAFT' : 'ARCHIVED';
    var payload = { code: exam.code, title: exam.title, level: exam.level, year: exam.year, month: exam.month, status: nextStatus, passingScore: exam.passingScore };
    confirmAction(hidden ? 'Hiện lại đề ' + exam.code + '?' : 'Ẩn đề ' + exam.code + '?', hidden ? 'Đề sẽ trở về trạng thái bản nháp và xuất hiện lại trong khu vực biên tập.' : 'Đề sẽ được ẩn khỏi thư viện người học nhưng toàn bộ dữ liệu vẫn được giữ nguyên.', function () {
      return api('/exams/' + encodeURIComponent(exam.code), { method: 'PUT', body: JSON.stringify(payload) }).then(loadCatalog).then(function () { toast(hidden ? 'Đã hiện lại đề ' + exam.code + '.' : 'Đã ẩn đề ' + exam.code + '.'); });
    }, { confirmLabel: hidden ? 'Hiện lại' : 'Ẩn đề' });
  }

  function populateQuestionSelectors(sectionId, selectedMondaiId) { var sections = state.content.sections || [], select = $('questionSection'); select.innerHTML = sections.map(function (section) { return '<option value="' + esc(section.id) + '">' + esc(section.nameVi || section.id) + '</option>'; }).join(''); select.value = sectionId || ((sections[0] || {}).id || ''); populateMondai(select.value, selectedMondaiId); }
  function populateMondai(sectionId, selectedMondaiId) { var section = (state.content.sections || []).find(function (item) { return item.id === sectionId; }), mondais = section && section.mondai || []; $('questionMondai').innerHTML = mondais.map(function (mondai) { return '<option value="' + mondai.id + '">問題' + esc(mondai.number) + ' · ' + esc(mondai.nameVi || mondai.type) + '</option>'; }).join(''); if (selectedMondaiId != null) $('questionMondai').value = String(selectedMondaiId); }
  function selectedTreeLocation() { var parts = state.treeFilter.split(':'); if (parts[0] === 'mondai') return { sectionIndex: Number(parts[1]), mondaiIndex: Number(parts[2]) }; if (parts[0] === 'section') return { sectionIndex: Number(parts[1]), mondaiIndex: 0 }; return { sectionIndex: 0, mondaiIndex: 0 }; }
  function getQuestionRef(key) { var p = String(key).split('-').map(Number), section = state.content.sections[p[0]], mondai = section && section.mondai[p[1]], group = mondai && mondai.groups[p[2]], question = group && group.questions[p[3]]; return question ? { section: section, sectionIndex: p[0], mondai: mondai, mondaiIndex: p[1], group: group, groupIndex: p[2], question: question, questionIndex: p[3] } : null; }

  function openQuestionDialog(ref) {
    state.editingQuestion = ref || null; var form = $('questionForm'); form.reset(); $('questionDialogTitle').textContent = ref ? 'Sửa câu ' + ref.question.number : 'Thêm câu hỏi'; $('deleteQuestionButton').hidden = !ref;
    var location = ref || selectedTreeLocation(), section = ref ? ref.section : state.content.sections[location.sectionIndex], mondai = ref ? ref.mondai : section.mondai[location.mondaiIndex]; populateQuestionSelectors(section && section.id, mondai && mondai.id);
    if (ref) { var q = ref.question; form.elements.number.value = q.number; form.elements.type.value = q.type || ref.mondai.type || ''; form.elements.prompt.value = q.prompt || ''; form.elements.passage.value = typeof ref.group.passage === 'string' ? ref.group.passage : ''; (q.choices || []).slice(0, 4).forEach(function (choice, index) { form.elements['choice' + (index + 1)].value = choice.content || ''; }); form.elements.correctChoice.value = q.correctChoice == null ? '' : q.correctChoice; form.elements.correctOrder.value = (q.correctOrder || []).join(','); form.elements.imageRef.value = q.imageRef || ''; form.elements.sourcePage.value = q.sourcePage || ''; form.elements.explanation.value = q.explanation || ''; form.elements.scriptText.value = q.scriptText || ''; form.elements.scriptTranslation.value = q.scriptTranslation || ''; form.elements.audioUrl.value = q.audioUrl || ''; form.elements.audioStart.value = q.audioStart === '' ? '' : q.audioStart; form.elements.audioEnd.value = q.audioEnd === '' ? '' : q.audioEnd; }
    else { form.elements.number.value = allQuestions(state.content).reduce(function (max, row) { return Math.max(max, Number(row.question.number) || 0); }, 0) + 1; form.elements.type.value = section && section.id === 'listening' ? 'LISTENING_TASK_BASED' : section && section.id === 'grammar_reading' ? 'GRAMMAR_FORM' : 'KANJI_READING'; }
    form.querySelector('.jlpt-admin-media-section').open = !!(ref && (ref.question.imageRef || ref.question.audioUrl || ref.question.scriptText || ref.question.scriptTranslation));
    $('questionDialog').showModal();
  }

  function saveQuestion(event) {
    event.preventDefault(); var data = Object.fromEntries(new FormData(event.currentTarget).entries());
    var payload = { mondaiId: Number(data.mondaiIndex), number: Number(data.number), type: data.type.trim().toUpperCase(), prompt: data.prompt.trim(), passage: data.passage.trim() || null, choices: [data.choice1, data.choice2, data.choice3, data.choice4].map(function (value) { return value.trim(); }), correctChoice: data.correctChoice ? Number(data.correctChoice) : null, correctOrder: data.correctOrder.trim() ? data.correctOrder.trim().split(/[,，\s]+/).map(Number) : null, score: 1, imageUrl: data.imageRef.trim() || null, sourcePage: data.sourcePage ? Number(data.sourcePage) : null, explanation: data.explanation.trim() || null, scriptText: data.scriptText.trim() || null, scriptTranslation: data.scriptTranslation.trim() || null, audioUrl: data.audioUrl.trim() || null, audioStart: data.audioStart ? Number(data.audioStart) : null, audioEnd: data.audioEnd ? Number(data.audioEnd) : null }, ref = state.editingQuestion;
    if (payload.correctOrder && (payload.correctOrder.length !== 4 || new Set(payload.correctOrder).size !== 4 || payload.correctOrder.some(function (n) { return !Number.isInteger(n) || n < 1 || n > 4; }) || payload.type !== 'SENTENCE_COMPOSITION')) { toast('Thứ tự đúng cần đủ 1, 2, 3, 4, mỗi số một lần, cho dạng SENTENCE_COMPOSITION.'); return; }
    confirmAction(ref ? 'Lưu thay đổi câu ' + ref.question.number + '?' : 'Thêm câu hỏi mới?', ref ? 'Nội dung, đáp án và media của câu hỏi sẽ được cập nhật.' : 'Câu ' + payload.number + ' sẽ được thêm vào 問題 đã chọn.', function () {
      return (ref ? api('/questions/' + ref.question.id, { method: 'PUT', body: JSON.stringify(payload) }) : api('/exams/' + encodeURIComponent(state.activeCode) + '/questions', { method: 'POST', body: JSON.stringify(payload) })).then(function () { $('questionDialog').close(); return refreshActive(); }).then(function () { toast(ref ? 'Đã cập nhật câu hỏi.' : 'Đã thêm câu hỏi.'); });
    }, { confirmLabel: ref ? 'Lưu thay đổi' : 'Thêm câu hỏi' });
  }

  function handleQuestionAction(event) { var button = event.target.closest('[data-action]'); if (!button) return; var ref = getQuestionRef(button.dataset.key); if (!ref) return; if (button.dataset.action === 'edit') openQuestionDialog(ref); if (button.dataset.action === 'delete') requestQuestionDelete(ref); if (button.dataset.action === 'duplicate') confirmAction('Nhân bản câu ' + ref.question.number + '?', 'Một bản sao của câu hỏi cùng đáp án, giải thích và media sẽ được tạo.', function () { return api('/questions/' + ref.question.id + '/duplicate', { method: 'POST' }).then(refreshActive).then(function () { toast('Đã nhân bản câu hỏi.'); }); }, { confirmLabel: 'Nhân bản' }); }
  function requestDeleteQuestion() { if (state.editingQuestion) requestQuestionDelete(state.editingQuestion, true); }
  function requestQuestionDelete(ref, closeEditor) { confirmAction('Xóa câu ' + ref.question.number + '?', 'Câu hỏi, các lựa chọn và audio gắn riêng với câu sẽ bị xóa khỏi database.', function () { return api('/questions/' + ref.question.id, { method: 'DELETE' }).then(function () { if (closeEditor) $('questionDialog').close(); return refreshActive(); }).then(function () { toast('Đã xóa câu hỏi.'); }); }, { confirmLabel: 'Xóa câu hỏi', danger: true }); }
  function refreshActive() { var code = state.activeCode; return loadCatalog().then(function () { return openWorkspace(code); }); }
  function confirmAction(title, message, action, options) { options = options || {}; $('confirmTitle').textContent = title; $('confirmMessage').textContent = message; $('confirmActionButton').textContent = options.confirmLabel || 'Xác nhận'; $('confirmDialog').classList.toggle('is-danger', !!options.danger); state.pendingAction = action; $('confirmDialog').showModal(); }
  function toast(message) { var el = $('adminToast'); el.textContent = message; el.hidden = false; clearTimeout(toast.timer); toast.timer = setTimeout(function () { el.hidden = true; }, 3500); }
});
