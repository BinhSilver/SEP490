(function () {
  'use strict';

  var page = 0;
  var size = 20;
  var loading = false;
  var hasMore = false;
  var items = document.getElementById('jlptHistoryItems');
  var status = document.getElementById('jlptHistoryStatus');
  var more = document.getElementById('jlptHistoryMore');

  function load(reset) {
    if (loading || (!hasMore && !reset)) return;
    loading = true;
    if (reset) {
      page = 0;
      items.replaceChildren();
      status.hidden = false;
      status.textContent = 'Đang tải lịch sử…';
      more.hidden = true;
    }
    fetch('/api/jlpt/history?page=' + page + '&size=' + size, {
      headers: window.JlasAuth ? window.JlasAuth.authHeaders() : { 'Content-Type': 'application/json' }
    }).then(function (response) {
      return response.json().then(function (body) {
        if (!response.ok || body.success === false) throw new Error(body.message || 'Không tải được lịch sử.');
        return body.data;
      });
    }).then(function (data) {
      data.items.forEach(function (entry) { items.appendChild(renderEntry(entry)); });
      hasMore = !!data.hasMore;
      page = data.page + 1;
      status.hidden = items.children.length > 0;
      status.textContent = 'Bạn chưa có lần nộp bài JLPT nào.';
      more.hidden = !hasMore;
    }).catch(function (error) {
      status.hidden = false;
      status.textContent = error.message || 'Không thể tải lịch sử. Vui lòng thử lại.';
      more.hidden = true;
    }).finally(function () { loading = false; });
  }

  function renderEntry(entry) {
    var card = document.createElement('article');
    card.className = 'jlpt-history-entry';

    var head = document.createElement('div');
    head.className = 'jlpt-history-entry__head';
    var title = document.createElement('div');
    title.className = 'jlpt-history-entry__title';
    var level = document.createElement('span');
    level.className = 'jlpt-level';
    level.textContent = entry.level || 'JLPT';
    var name = document.createElement('h3');
    name.textContent = entry.examTitle || entry.examCode;
    title.append(level, name);
    var result = document.createElement('strong');
    var hasEstimatedScore = entry.totalScore != null;
    result.className = 'jlpt-history-entry__result' + (!hasEstimatedScore || !entry.fullAttempt ? ' is-partial' : (entry.passed ? ' is-pass' : ' is-fail'));
    result.textContent = !hasEstimatedScore ? 'KẾT QUẢ CŨ' : (!entry.fullAttempt ? 'LUYỆN PHẦN' : (entry.passed ? 'ĐẠT' : 'TRƯỢT'));
    head.append(title, result);

    var meta = document.createElement('div');
    meta.className = 'jlpt-history-entry__meta';
    meta.append(
      metaItem(entry.mode === 'EXAM' ? 'Luyện thi' : 'Luyện tập'),
      metaItem(entry.partCode ? partName(entry.partCode) : 'Toàn đề'),
      metaItem(formatDate(entry.submittedAt))
    );

    var score = document.createElement('div');
    score.className = 'jlpt-history-entry__score';
    var correct = document.createElement('strong');
    correct.textContent = entry.totalScore == null
      ? entry.correctAnswers + ' / ' + entry.totalQuestions + ' câu'
      : entry.totalScore + (entry.fullAttempt ? ' / 180 điểm ước tính' : ' / 60 điểm phần');
    var detail = document.createElement('span');
    detail.textContent = ' · đúng ' + entry.correctAnswers + ' câu · đã trả lời ' + entry.answeredQuestions + '/' + entry.totalQuestions;
    var percent = document.createElement('b');
    percent.textContent = Number(entry.scorePercent || 0).toLocaleString('vi-VN', { maximumFractionDigits: 2 }) + '%';
    score.append(correct, detail, percent);
    var review = document.createElement(entry.reviewAvailable ? 'a' : 'span');
    review.className = 'jlpt-history-entry__review' + (entry.reviewAvailable ? '' : ' is-unavailable');
    review.textContent = entry.reviewAvailable ? 'Xem chi tiết bài làm' : 'Lần làm này chưa lưu dữ liệu chi tiết';
    if (entry.reviewAvailable) review.href = '/jlpt/exam?code=' + encodeURIComponent(entry.examCode) + '&history=' + encodeURIComponent(entry.id);
    card.append(head, meta, score, review);
    return card;
  }

  function metaItem(value) {
    var item = document.createElement('span');
    item.textContent = value || '';
    return item;
  }

  function partName(code) {
    return { vocabulary: 'Từ vựng', grammar_reading: 'Ngữ pháp & đọc hiểu', listening: 'Nghe hiểu' }[code] || code;
  }

  function formatDate(value) {
    var date = new Date(value);
    return Number.isNaN(date.getTime()) ? '' : date.toLocaleString('vi-VN', { dateStyle: 'medium', timeStyle: 'short' });
  }

  more.addEventListener('click', function () { load(false); });
  load(true);
})();
