(function () {
  'use strict';
  if (!document.getElementById('jlptStudyApp')) return;
  var params = new URLSearchParams(location.search), code = params.get('code') || '';
  var kind = params.get('type') === 'grammar' ? 'grammar' : params.get('type') === 'vocabulary' ? 'vocabulary' : '';
  var data = null, $ = function (id) { return document.getElementById(id); };
  function esc(v) { return String(v == null ? '' : v).replace(/[&<>"']/g, function (c) { return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]; }); }
  function url(type) { return '/jlpt/study?code=' + encodeURIComponent(code) + '&type=' + type; }
  async function get(path) {
    var r = await fetch(path, {headers: window.JlasAuth ? window.JlasAuth.authHeaders() : {}});
    var body = await r.json(); if (!r.ok || body.success === false) throw new Error(body.message || 'Không tải được nội dung ôn tập.'); return body.data;
  }
  function sourceLinks(item) {
    return item.sources.map(function (s) { return '<a href="/jlpt/exam?code=' + encodeURIComponent(code) + '&mode=practice&question=' + s.questionId + '">Xem câu ' + s.number + ' · ' + esc(s.part === 'vocabulary' ? 'Từ vựng' : 'Ngữ pháp') + ' ↗</a>'; }).join('');
  }
  function example(item) { return item.exampleJa ? '<div class="js-study-example"><span lang="ja">' + esc(item.exampleJa) + '</span>' + (item.exampleVi ? '<small>' + esc(item.exampleVi) + '</small>' : '') + '</div>' : ''; }
  function entry(item) {
    var heading = '<div class="js-study-term"><strong lang="ja">' + esc(item.title) + '</strong>' + (item.reading ? '<span class="reading" lang="ja">' + esc(item.reading) + '</span>' : '') + (item.hanViet ? '<small class="js-study-hanviet">' + esc(item.hanViet) + '</small>' : '') + (item.level ? '<small>' + esc(item.level) + '</small>' : '') + '</div>';
    var meaning = '<p>' + esc(item.meaning || (kind === 'grammar' ? 'Chưa có bài giảng cho mẫu này trong kho ngữ pháp.' : 'Chưa có nghĩa cho từ này trong từ điển.')) + '</p>';
    var lesson = kind === 'grammar' && item.meaning ? '<details class="js-study-lesson"><summary>Xem bài giảng</summary>' + meaning + example(item) + (item.note ? '<p>' + esc(item.note) + '</p>' : '') + '</details>' : '';
    var surfaces = item.expressions && item.expressions.length ? item.expressions : item.sources.map(function(s){return s.surface;});
    return '<article class="js-study-entry">' + heading + (kind === 'vocabulary' ? meaning + example(item) : '<p>Trong đề: ' + esc(surfaces.filter(function(v,i,a){return a.indexOf(v)===i;}).join(' / ')) + '</p>' + lesson + (!item.meaning ? meaning : '')) + '<div class="js-study-links">' + (kind === 'vocabulary' ? '<a href="/dictionary/index.html?q=' + encodeURIComponent(item.title) + '">▤ Tra từ</a>' : '') + sourceLinks(item) + '</div></article>';
  }
  function render() {
    var all = data[kind], q = $('studyQuery').value.trim().toLocaleLowerCase('vi');
    var filtered = all.filter(function (x) { return [x.title,x.reading,x.meaning,x.note].join(' ').toLocaleLowerCase('vi').includes(q); });
    $('studySubtitle').hidden = true;
    $('studyEntries').innerHTML = filtered.length ? filtered.map(entry).join('') : '<p class="js-study-empty">' + (all.length ? 'Không có nội dung khớp bộ lọc.' : 'Chưa tìm thấy nội dung ôn tập từ dữ liệu đề này.') + '</p>';
  }
  $('studyFilters').onsubmit = function(e){e.preventDefault();};
  $('studyQuery').oninput = function(){if(data && kind) render();};
  async function load() {
    if (!/^N[1-5]_(?:\d{4}_(?:07|12)|MOCK_\d+)$/.test(code)) { $('studyTitle').textContent='Chưa chọn đề'; $('studyNotice').textContent='Quay về thư viện JLPT và chọn một đề để xem nội dung ôn tập.'; $('studyNotice').hidden=false; return; }
    try {
      if (kind) {
        $('studyBack').href = '/jlpt/detail?code=' + encodeURIComponent(code);
        data = await get('/api/jlpt/exams/' + encodeURIComponent(code) + '/study');
        $('studyTitle').textContent = (kind === 'grammar' ? 'Ngữ pháp — ' : 'Từ vựng — ') + data.title;
        $('studyListView').hidden = false; render();
      } else {
        var exam = await get('/api/jlpt/exams/' + encodeURIComponent(code));
        $('studyTitle').textContent = exam.title; $('studyOverview').hidden = false;
        $('studyDuration').textContent = 'Tổng ' + exam.parts.reduce(function(n,p){return n+(p.durationMinutes||0);},0) + ' phút';
        $('studyParts').innerHTML = exam.parts.map(function(p){var count=p.mondai.reduce(function(n,m){return n+m.questions.length;},0);return '<a class="js-study-part" href="/jlpt/exam?code='+encodeURIComponent(code)+'&mode=practice&part='+encodeURIComponent(p.code)+'"><span aria-hidden="true">'+(p.code==='listening'?'♫':p.code==='vocabulary'?'▤':'▣')+'</span><div><strong>'+esc(p.title)+'</strong><small>'+(p.durationMinutes||0)+' phút · '+count+' câu · Luyện tập</small></div><span>›</span></a>';}).join('');
        $('studyExam').href='/jlpt/exam?code='+encodeURIComponent(code)+'&mode=practice';
        $('studyVocabularyLink').href=url('vocabulary'); $('studyGrammarLink').href=url('grammar');
        data = await get('/api/jlpt/exams/' + encodeURIComponent(code) + '/study');
        $('studyWordCount').textContent=data.vocabulary.length+' từ'; $('studyGrammarCount').textContent=data.grammar.length+' mục';
      }
    } catch(error) { $('studyNotice').textContent=error.message; $('studyNotice').hidden=false; $('studyTitle').textContent=data ? data.title : 'Nội dung ôn tập JLPT'; }
  }
  load();
})();
