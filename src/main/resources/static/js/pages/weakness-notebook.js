(function () {
  'use strict';
  var app = document.getElementById('notebookApp');
  if (!app) return;
  var $ = function (id) { return document.getElementById(id); };
  var skills = { KANJI: 'Kanji', VOCABULARY: 'Từ vựng', GRAMMAR: 'Ngữ pháp', READING: 'Đọc hiểu', LISTENING: 'Nghe hiểu' };
  var statuses = { OPEN: 'Cần ôn', REVIEWED: 'Đã ôn', MASTERED: 'Thành thạo' };
  var state = { skill: '', page: 0, data: null, sequence: 0, cards: [], cursor: 0, flipped: false, saving: false };
  function esc(value) { return String(value == null ? '' : value).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); }
  function date(value) { if (!value) return '—'; var d = new Date(value); return isNaN(d.getTime()) ? '—' : d.toLocaleDateString('vi-VN'); }
  function notice(message, review) { var el = $(review ? 'notebookReviewNotice' : 'notebookNotice'); el.textContent = message || ''; el.hidden = !message; }
  async function request(path, method, body) {
    var headers = window.JlasAuth ? window.JlasAuth.authHeaders() : {};
    if (body) headers['Content-Type'] = 'application/json';
    var response = await fetch('/api/learning/notebook' + path, { method: method || 'GET', headers: headers, body: body ? JSON.stringify(body) : undefined });
    var result; try { result = await response.json(); } catch (e) { throw new Error('Không đọc được phản hồi. Vui lòng thử lại.'); }
    if (!response.ok || result.success === false) throw new Error(response.status === 401 ? 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.' : result.message || 'Không thể tải sổ tay. Vui lòng thử lại.');
    return result.data;
  }
  function params(page, size) {
    return '?' + new URLSearchParams({ skill: state.skill, level: $('notebookLevel').value, status: $('notebookStatus').value,
      q: $('notebookQuery').value.trim(), sort: $('notebookSort').value, page: page, size: size });
  }
  function renderStats(data) {
    var s = data.summary;
    $('notebookTotal').textContent = s.total + ' mục';
    $('notebookStats').innerHTML = ['MASTERED', 'REVIEWED', 'OPEN'].map(function (key) {
      return '<span><i class="wn-dot ' + key + '"></i>' + statuses[key] + '<strong>' + ({ MASTERED: s.mastered, REVIEWED: s.reviewed, OPEN: s.open })[key] + '</strong></span>';
    }).join('');
    var percent = s.total ? Math.round((s.reviewed + s.mastered) / s.total * 100) : 0;
    $('notebookProgress').setAttribute('aria-valuenow', percent);
    $('notebookProgress').firstElementChild.style.width = percent + '%';
    $('notebookSkills').innerHTML = [''].concat(Object.keys(skills)).map(function (key) {
      return '<button type="button" data-skill="' + key + '" aria-pressed="' + (state.skill === key) + '">' + (skills[key] || 'Tất cả') + '<small>' + (key ? s.skills[key] || 0 : s.total) + '</small></button>';
    }).join('');
    var a = data.latestAssessment;
    var readiness = { HIGH: 'Sẵn sàng cao', MEDIUM: 'Gần mục tiêu', LOW: 'Cần củng cố' };
    $('notebookReadiness').textContent = a ? 'Smart Test ' + date(a.submittedAt) + ' · Trình độ ' + (a.assessedLevel || 'chưa xác định') + (a.targetLevel ? ' → mục tiêu ' + a.targetLevel : '') + ' · ' + (readiness[a.readiness] || 'Chưa đủ đánh giá') : 'Chưa có Smart Test hoàn thành. Làm bài đánh giá để xác định nội dung cần ôn.';
  }
  function entry(item) {
    var spoken = item.skill === 'KANJI' || item.skill === 'VOCABULARY' || item.skill === 'GRAMMAR';
    return '<details class="wn-entry"><summary><i class="wn-dot ' + esc(item.status) + '"></i><div><span class="wn-term" lang="ja">' + esc(item.title) + '</span>' + (item.reading ? '<span class="wn-reading" lang="ja">' + esc(item.reading) + '</span>' : '') +
      '<div class="wn-badges"><span class="wn-badge">' + esc(skills[item.skill] || item.skill) + '</span>' + (item.level ? '<span class="wn-badge">' + esc(item.level) + '</span>' : '') + '<span class="wn-badge">' + esc(statuses[item.status] || item.status) + '</span></div></div></summary>' +
      '<div class="wn-detail"><p>' + esc(item.description || 'Mở tài liệu học để xem nội dung chi tiết.') + '</p>' + example(item) +
      '<div class="wn-evidence"><span>Sai ' + item.wrongCount + ' câu</span><span>Độ chính xác: ' + (item.accuracy == null ? 'chưa đủ dữ liệu' : Number(item.accuracy) + '%') + '</span><span>Ghi nhận ' + date(item.detectedAt) + '</span>' + (item.lastReviewedAt ? '<span>Ôn gần nhất ' + date(item.lastReviewedAt) + '</span>' : '') + '</div>' +
      (item.topic ? '<p class="wn-muted">Chủ đề: ' + esc(item.topic) + '</p>' : '') +
      '<div class="wn-card-actions">' + (spoken && 'speechSynthesis' in window ? '<button class="wn-button wn-outline" data-speak="' + item.id + '" type="button">♫ Phát âm</button>' : '') +
      (item.status !== 'MASTERED' ? '<button class="wn-button wn-outline" type="button" data-review="' + item.id + '" data-status="' + (item.status === 'OPEN' ? 'REVIEWED' : 'OPEN') + '">' + (item.status === 'OPEN' ? '✓ Đánh dấu đã ôn' : '↻ Cần ôn lại') + '</button>' : '') +
      '<a class="wn-button wn-outline" href="' + esc(item.studyUrl) + '">' + (item.studyUrl.indexOf('/dictionary/') === 0 ? 'Tra cứu từ điển' : 'Mở lộ trình học') + ' ↗</a></div></div></details>';
  }
  function example(item) { return item.example ? '<div class="wn-example"><span lang="ja">' + esc(item.example) + '</span>' + (item.exampleTranslation ? '<small>' + esc(item.exampleTranslation) + '</small>' : '') + '</div>' : ''; }
  function render(data) {
    renderStats(data);
    $('notebookResultCount').textContent = data.total + ' kết quả';
    $('notebookEntries').innerHTML = data.items.length ? data.items.map(entry).join('') : '<div class="wn-empty"><span class="wn-book" style="margin:auto">▤</span><h3>' + (data.summary.total ? 'Chưa có mục khớp bộ lọc' : 'Sổ tay đang chờ những điều bạn cần ôn') + '</h3><p>' + (data.summary.total ? 'Thử chọn kỹ năng, cấp độ hoặc trạng thái khác.' : 'Cập nhật từ Smart Test gần nhất để lưu nội dung cần củng cố.') + '</p><a class="wn-button wn-outline" href="/survey">Mở Smart Test</a></div>';
    $('notebookPage').textContent = (data.page + 1) + ' / ' + Math.max(1, Math.ceil(data.total / data.size));
    $('notebookPrev').disabled = data.page === 0;
    $('notebookNext').disabled = (data.page + 1) * data.size >= data.total;
    $('notebookStudy').disabled = !data.total || $('notebookStatus').value === 'MASTERED';
  }
  async function load() {
    var sequence = ++state.sequence;
    $('notebookEntries').setAttribute('aria-busy', 'true');
    $('notebookStudy').disabled = true;
    $('notebookPrev').disabled = $('notebookNext').disabled = true;
    try {
      var data = await request(params(state.page, 12));
      if (sequence !== state.sequence) return;
      state.data = data; render(data);
    } catch (error) { if (sequence === state.sequence) { notice(error.message); $('notebookEntries').innerHTML = '<div class="wn-empty"><h3>Chưa tải được sổ tay</h3><button class="wn-button wn-outline" data-retry type="button">Thử lại</button></div>'; } }
    finally { if (sequence === state.sequence) $('notebookEntries').setAttribute('aria-busy', 'false'); }
  }
  function reset() { state.page = 0; notice(''); load(); }
  $('notebookSkills').addEventListener('click', function (e) { var b = e.target.closest('[data-skill]'); if (b) { state.skill = b.dataset.skill; reset(); } });
  $('notebookFilters').addEventListener('submit', function (e) { e.preventDefault(); reset(); });
  ['notebookLevel', 'notebookStatus', 'notebookSort'].forEach(function (id) { $(id).addEventListener('change', reset); });
  var timer; $('notebookQuery').addEventListener('input', function () { clearTimeout(timer); timer = setTimeout(reset, 250); });
  $('notebookPrev').onclick = function () { if (state.page) { state.page--; load(); } };
  $('notebookNext').onclick = function () { state.page++; load(); };
  $('notebookSync').onclick = async function () {
    this.disabled = true; notice('');
    try { await request('/sync', 'POST'); state.page = 0; await load(); notice('Đã cập nhật từ Smart Test gần nhất. Những mục đã ôn của cùng bài đánh giá được giữ nguyên.'); }
    catch (error) { notice(error.message); } finally { this.disabled = false; }
  };
  $('notebookEntries').addEventListener('click', async function (e) {
    if (e.target.closest('[data-retry]')) { notice(''); load(); return; }
    var speak = e.target.closest('[data-speak]');
    if (speak) { var item = state.data.items.find(function (x) { return x.id === Number(speak.dataset.speak); }); var utterance = new SpeechSynthesisUtterance(item.reading || item.title); utterance.lang = 'ja-JP'; window.speechSynthesis.cancel(); window.speechSynthesis.speak(utterance); return; }
    var button = e.target.closest('[data-review]'); if (!button) return;
    button.disabled = true;
    try { await request('/' + button.dataset.review + '/status', 'PATCH', { status: button.dataset.status }); notice('Đã lưu trạng thái ôn tập.'); await load(); }
    catch (error) { notice(error.message); button.disabled = false; }
  });
  function paintCard() {
    var item = state.cards[state.cursor];
    $('notebookReviewCounter').textContent = (state.cursor + 1) + ' / ' + state.cards.length;
    $('notebookReviewBar').style.width = (state.cursor / state.cards.length * 100) + '%';
    $('notebookCard').innerHTML = '<small>' + esc(skills[item.skill]) + (item.level ? ' · ' + esc(item.level) : '') + '</small><strong lang="ja">' + esc(item.title) + '</strong>' + (state.flipped ? '<p>' + esc(item.reading || '') + '</p><p>' + esc(item.description || 'Xem thêm nội dung trong từ điển hoặc lộ trình học.') + '</p>' + example(item) : '<small>Chạm để lật</small>');
    $('notebookReviewActions').hidden = !state.flipped;
    $('notebookCard').setAttribute('aria-label', state.flipped ? 'Ẩn nội dung thẻ' : 'Lật thẻ');
  }
  $('notebookStudy').onclick = async function () {
    this.disabled = true; notice('');
    try {
      var query = new URLSearchParams(params(0, 50).slice(1));
      var cards = [], page = 0, data;
      do { query.set('page', page++); data = await request('?' + query); cards = cards.concat(data.items.filter(function (x) { return x.status !== 'MASTERED'; })); } while (page * 50 < data.total);
      if (!cards.length) { notice('Không có mục cần ôn trong bộ lọc hiện tại.'); return; }
      state.cards = cards; state.cursor = 0; state.flipped = false;
      $('notebookBrowse').hidden = true; $('notebookReview').hidden = false; notice('', true); paintCard(); $('notebookCard').focus();
    } catch (error) { notice(error.message); } finally { this.disabled = !state.data || !state.data.total || $('notebookStatus').value === 'MASTERED'; }
  };
  $('notebookCard').onclick = function () { if (!state.saving) { state.flipped = !state.flipped; paintCard(); } };
  $('notebookExit').onclick = function () { if (state.saving) return; $('notebookReview').hidden = true; $('notebookBrowse').hidden = false; load(); $('notebookStudy').focus(); };
  async function grade(status) {
    if (!state.flipped || state.saving) return;
    state.saving = true; $('notebookAgain').disabled = $('notebookRemembered').disabled = $('notebookExit').disabled = true; notice('', true);
    try {
      await request('/' + state.cards[state.cursor].id + '/status', 'PATCH', { status: status });
      state.cursor++; state.flipped = false;
      if (state.cursor === state.cards.length) {
        $('notebookReview').hidden = true; $('notebookBrowse').hidden = false; await load(); notice('Đã hoàn thành và lưu phiên ôn ' + state.cards.length + ' thẻ.'); $('notebookStudy').focus();
      } else { paintCard(); $('notebookCard').focus(); }
    } catch (error) { notice(error.message, true); }
    finally { state.saving = false; $('notebookAgain').disabled = $('notebookRemembered').disabled = $('notebookExit').disabled = false; }
  }
  $('notebookAgain').onclick = function () { grade('OPEN'); };
  $('notebookRemembered').onclick = function () { grade('REVIEWED'); };
  load();
})();
