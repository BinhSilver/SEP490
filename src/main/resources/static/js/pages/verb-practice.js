(function () {
  'use strict';
  if (!document.getElementById('verbApp')) return;
  if (window.JlasAuth && !window.JlasAuth.requireLogin('/practice/verbs')) return;
  var $ = function (id) { return document.getElementById(id); };
  var verbGroups = document.querySelectorAll('.vp-rule-columns > details');
  verbGroups.forEach(function (group) {
    group.querySelector('summary').addEventListener('click', function (event) {
      event.preventDefault();
      var open = !group.open;
      verbGroups.forEach(function (item) { item.open = open; });
    });
  });
  // Inline icons keep the practice interface usable without an external icon font.
  function renderIcons(root) {
    var paths = {
      'list-check':'M9 5h12M9 12h12M9 19h12M2 5l2 2 3-4M2 12l2 2 3-4M2 19l2 2 3-4',
      'pen-nib':'M15 3l6 6-9 9-9 3 3-9zM13 5l6 6M3 21l7-7',
      'shuffle':'M3 5h3c6 0 6 14 12 14h3M17 15l4 4-4 4M3 19h3c2 0 3-2 4-4M14 9c1-2 2-4 4-4h3M17 1l4 4-4 4',
      'comment-dots':'M4 3h16v14H9l-5 4zM8 10h.01M12 10h.01M16 10h.01',
      'bolt':'M13 2L4 14h7l-1 8 10-13h-8z',
      'rotate-left':'M3 4v6h6M3 10a9 9 0 1 1 2 9',
      'circle-check':'M22 12a10 10 0 1 1-6-9M7 11l4 4L21 5',
      'layer-group':'M12 2L2 7l10 5 10-5zM2 12l10 5 10-5M2 17l10 5 10-5',
      'wand-magic-sparkles':'M3 21L17 7l3 3L6 24M15 2v3M21 3v3M21 16h3',
      'seedling':'M12 22V10M12 15C3 16 2 10 2 7c7 0 10 3 10 8M12 10c0-7 5-8 10-8 0 6-4 8-10 8',
      'bullseye':'M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0M18 12a6 6 0 1 1-12 0 6 6 0 0 1 12 0M12 10v4M10 12h4',
      'check-double':'M2 12l4 4L16 6M12 16l10-10',
      'bookmark':'M5 3h14v19l-7-4-7 4z',
      'sliders':'M4 3v5M4 12v9M12 3v10M12 17v4M20 3v2M20 9v12M1 8h6v4H1zM9 13h6v4H9zM17 5h6v4h-6z',
      'sun':'M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0M12 1v2M12 21v2M1 12h2M21 12h2M4 4l2 2M18 18l2 2M4 20l2-2M18 6l2-2',
      'chart-simple':'M4 21V11M12 21V3M20 21V7',
      'lightbulb':'M9 21h6M9 17c0-4-4-4-4-9a7 7 0 0 1 14 0c0 5-4 5-4 9z',
      'arrow-left':'M20 12H4M10 6l-6 6 6 6',
      'arrow-right':'M4 12h16M14 6l6 6-6 6',
      'stopwatch':'M9 2h6M12 2v3M19 5l2-2M21 14a9 9 0 1 1-18 0 9 9 0 0 1 18 0M12 8v6l3 2'
    };
    root.querySelectorAll('i.fa-solid').forEach(function(el) {
      var key=Array.from(el.classList).find(function(c){return paths[c.slice(3)];});
      if(!key)return;
      el.innerHTML='<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="'+paths[key.slice(3)]+'"/></svg>';
      Array.from(el.classList).filter(function(c){return c.indexOf('fa-')===0;}).forEach(function(c){el.classList.remove(c);});
      el.classList.add('vp-svg-icon');
    });
  }
  var modes = [
    { id: 'CHOICE', name: 'Chọn đáp án', hint: 'Nhìn đúng, chọn nhanh. Làm quen từng quy tắc.', icon: 'list-check', color: 'violet' },
    { id: 'INPUT', name: 'Tự nhập đáp án', hint: 'Tự nhớ, tự chia. Biến kiến thức thành phản xạ.', icon: 'pen-nib', color: 'blue' },
    { id: 'CHAIN', name: 'Chia liên tiếp', hint: 'Một động từ, nhiều thể. Kết nối điều đã học.', icon: 'shuffle', color: 'pink' },
    { id: 'CONTEXT', name: 'Điền vào câu', hint: 'Đặt động từ vào những tình huống quen thuộc.', icon: 'comment-dots', color: 'green' },
    { id: 'SPEED', name: 'Thử thách 60 giây', hint: 'Một phút tập trung. Vượt kỷ lục của chính bạn.', icon: 'bolt', color: 'peach' },
    { id: 'REVIEW', name: 'Luyện lại lỗi sai', hint: 'Quay lại chỗ chưa chắc. Từng câu một.', icon: 'rotate-left', color: 'violet' }
  ];
  var state = { mode: 'INPUT', forms: ['MASU','TE','NAI','TA'], data: null, session: null, question: null,
    feedback: false, finishFailed: false, sending: false, choice: '', timer: null, deadline: 0, lastSettings: null, view: 'home' };
  function esc(v) { return String(v == null ? '' : v).replace(/[&<>"']/g, function(c) { return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]; }); }
  function mode(id) { return modes.find(function(m) { return m.id === id; }) || modes[1]; }
  function notice(message) { $('vpNotice').textContent = message || ''; $('vpNotice').hidden = !message; }
  async function api(path, body) {
    var headers = window.JlasAuth ? window.JlasAuth.authHeaders() : {};
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    var response = await fetch('/api/verb-practice' + path, { method: body === undefined ? 'GET' : 'POST', headers: headers, body: body === undefined ? undefined : JSON.stringify(body) });
    var result; try { result = await response.json(); } catch (e) { throw new Error('Chưa kết nối được vòng luyện. Bạn thử lại nhé.'); }
    if (!response.ok || result.success === false) throw new Error(response.status === 401 ? 'Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại để tiếp tục.' : result.message || 'Chưa thể lưu kết quả. Bạn thử lại nhé.');
    return result.data;
  }
  function renderModes() {
    var canReview = state.data && state.data.mistakes > 0;
    if (state.data && !canReview && state.mode === 'REVIEW') state.mode = 'INPUT';
    $('vpModes').innerHTML = modes.map(function (item) {
      var disabled = item.id === 'REVIEW' && !canReview;
      return '<option value="' + item.id + '"' + (state.mode === item.id ? ' selected' : '') +
        (disabled ? ' disabled' : '') + '>' + item.name + (disabled && state.data ? ' (chưa có lỗi sai)' : '') + '</option>';
    }).join('');
    $('vpModeName').textContent = mode(state.mode).name;
    $('vpModeHint').textContent = state.mode === 'SPEED' ? '60 giây, không giới hạn ở số lượt đã chọn.' : state.mode === 'CHAIN' ? 'Các thể đã chọn xuất hiện lần lượt cho mỗi động từ.' : mode(state.mode).hint;
    $('vpCount').disabled = state.mode === 'SPEED';
  }
  function renderForms() {
    if (!state.data) return;
    [false,true].forEach(function(advanced) {
      $(advanced ? 'vpAdvancedForms' : 'vpBasicForms').innerHTML = state.data.forms.filter(function(f) { return f.advanced === advanced; }).map(function(f) { return '<label title="' + esc(f.description) + '"><input type="checkbox" value="' + f.id + '" ' + (state.forms.indexOf(f.id) >= 0 ? 'checked' : '') + ' /><span>' + esc(f.label) + '</span></label>'; }).join('');
    });
    $('vpSelectAll').textContent = state.forms.length === state.data.forms.length ? 'Bỏ chọn tất cả' : 'Chọn tất cả';
  }
  function ring(id, percent) { $(id).style.setProperty('--percent', Math.max(0,Math.min(100,percent)) + '%'); }
  function renderDashboard(d) {
    if (d.levels) d.levels.forEach(function(level) {
      var option=Array.from($('vpLevel').options).find(function(o){return o.value===level.id;});
      if(option)option.textContent=level.id;
    });
    $('vpResume').hidden = !d.active;
    renderForms(); renderModes();
  }
  async function load() {
    $('vpReload').hidden = true;
    try { state.data = await api(''); renderDashboard(state.data); $('vpSetupFields').disabled = false; notice(''); }
    catch (e) { notice(e.message); $('vpReload').hidden = false; }
  }
  function show(view) {
    state.view = view;
    $('vpHome').hidden = view !== 'home'; $('vpPlay').hidden = view !== 'play'; $('vpResults').hidden = view !== 'results';
    window.scrollTo({ top: 0, behavior: 'auto' });
  }
  function settings() { return { mode: state.mode, forms: state.forms.slice(), group: Number($('vpGroup').value), level: $('vpLevel').value, count: Number($('vpCount').value) }; }
  async function start(custom) {
    if (state.sending) return;
    var config = custom || settings();
    if (!config.forms.length) { notice('Chọn ít nhất một thể để bắt đầu nhé.'); return; }
    state.sending = true; $('vpStart').disabled = true; $('vpAgain').disabled = true; $('vpReviewMistakes').disabled = true; notice('');
    try { state.session = await api('/sessions',config); state.lastSettings = config; state.mode = config.mode; state.feedback = false; show('play'); renderQuestion(); }
    catch (e) { notice(e.message); }
    finally { state.sending = false; $('vpStart').disabled = false; $('vpAgain').disabled = false; $('vpReviewMistakes').disabled = false; }
  }
  function clearTimer() { if (state.timer) clearInterval(state.timer); state.timer = null; }
  function setTimer() {
    clearTimer(); var s = state.session;
    $('vpTimer').hidden = !s.deadline;
    if (!s.deadline || s.finished) return;
    state.deadline = performance.now() + Math.max(0,Date.parse(s.deadline)-Date.parse(s.serverNow));
    function tick() {
      var left = Math.max(0,Math.ceil((state.deadline-performance.now())/1000));
      $('vpTimer').querySelector('b').textContent = left;
      $('vpTimer').classList.toggle('urgent',left <= 10);
      if (!left && !state.sending) finish();
    }
    state.timer = setInterval(tick,250); tick();
  }
  function renderQuestion() {
    var s = state.session;
    if (s.finished) { renderResults(); return; }
    var q = s.question; state.question = q; state.choice = ''; state.feedback = false; state.finishFailed = false;
    $('vpPlayTitle').textContent = mode(s.mode).name;
    $('vpQuestionCount').textContent = s.mode === 'SPEED' ? 'Lượt ' + (s.answered+1) : 'Lượt ' + (s.answered+1) + ' / ' + s.total;
    $('vpSessionCorrect').textContent = s.correct + ' đúng';
    $('vpProgressBar').style.width = (s.mode === 'SPEED' ? Math.min(100,s.answered/20*100) : s.answered/s.total*100) + '%';
    $('vpQuestionGroup').textContent = 'Nhóm ' + q.group + ' · ' + q.level;
    $('vpChainHint').textContent = s.mode === 'CHAIN' ? 'Chia lần lượt từng thể' : '';
    $('vpTargetForm').textContent = q.formLabel;
    $('vpWord').textContent = q.word; $('vpReading').textContent = q.reading; $('vpReading').hidden = !$('vpShowReading').checked;
    $('vpMeaning').textContent = q.meaning;
    $('vpContext').hidden = !q.sentence;
    $('vpSentence').innerHTML = q.sentence ? esc(q.sentence).replace('___','<mark>　　　</mark>') : '';
    $('vpTranslation').textContent = q.translation || '';
    var choice = s.mode === 'CHOICE';
    $('vpChoices').hidden = !choice; $('vpTyped').hidden = choice;
    $('vpChoices').innerHTML = (q.choices || []).map(function(c,i) { return '<button class="vp-choice" type="button" data-index="' + i + '" aria-pressed="false"><span>' + (i+1) + '</span>' + esc(c) + '</button>'; }).join('');
    $('vpAnswer').value = ''; $('vpAnswer').disabled = false; $('vpCheck').disabled = false; $('vpSkip').disabled = false;
    $('vpAnswerActions').hidden = false; $('vpFeedback').hidden = true; $('vpNext').hidden = true;
    setTimer();
    if (choice) $('vpWord').focus({preventScroll:true}); else $('vpAnswer').focus({preventScroll:true});
  }
  async function submit(skip) {
    if (state.sending || state.feedback || state.view !== 'play') return;
    var value = state.session.mode === 'CHOICE' ? state.choice : $('vpAnswer').value;
    if (!skip && !value.trim()) { notice(state.session.mode === 'CHOICE' ? 'Bạn chọn một đáp án nhé.' : 'Bạn nhập đáp án trước nhé.'); return; }
    state.sending = true; $('vpCheck').disabled = true; $('vpSkip').disabled = true; notice('');
    try {
      var s = await api('/sessions/' + state.session.id + '/answer',{questionId:state.question.id,answer:value,skip:!!skip});
      state.session = s;
      if (!s.feedback) { if (s.finished) renderResults(); else renderQuestion(); return; }
      state.feedback = true; var a = s.feedback;
      $('vpAnswer').disabled = true; $('vpAnswerActions').hidden = true;
      Array.from($('vpChoices').children).forEach(function(b) { b.disabled = true; var option = state.question.choices[Number(b.dataset.index)]; if (option === a.expected) b.classList.add('is-correct'); else if (option === state.choice) b.classList.add('is-wrong'); });
      $('vpFeedback').classList.toggle('wrong',!a.correct); $('vpFeedback').hidden = false;
      $('vpFeedbackIcon').textContent = a.correct ? '✓' : '↻';
      $('vpFeedbackTitle').textContent = a.correct ? 'Chính xác! Bạn nhớ rất tốt.' : skip ? 'Cùng ghi nhớ cách chia này nhé.' : 'Chưa đúng rồi. Mình thử ghi nhớ lại nhé.';
      $('vpCorrectAnswer').textContent = a.expected + (a.expectedReading !== a.expected ? '（' + a.expectedReading + '）' : '');
      $('vpExplanation').textContent = a.explanation;
      $('vpSessionCorrect').textContent = s.correct + ' đúng';
      $('vpProgressBar').style.width = (s.mode === 'SPEED' ? Math.min(100,s.answered/20*100) : s.answered/s.total*100) + '%';
      $('vpNext').textContent = s.finished ? 'Xem kết quả →' : 'Câu tiếp theo →'; $('vpNext').hidden = false; $('vpNext').focus({preventScroll:true});
      if (s.finished) clearTimer();
    } catch (e) { notice(e.message); }
    finally { state.sending = false; $('vpCheck').disabled = false; $('vpSkip').disabled = false; }
  }
  async function finish() {
    if (state.sending || !state.session || state.view !== 'play') return;
    state.sending = true; $('vpFinish').disabled = true;
    try { state.session = await api('/sessions/' + state.session.id + '/finish',{}); clearTimer(); if ($('vpExitDialog').open) $('vpExitDialog').close(); renderResults(); }
    catch (e) { state.finishFailed = true; if ($('vpExitDialog').open) $('vpExitDialog').close(); notice(e.message); clearTimer(); $('vpNext').hidden = false; $('vpNext').textContent = 'Thử lưu kết quả lại'; state.feedback = false; state.deadline = 0; }
    finally { state.sending = false; $('vpFinish').disabled = false; }
  }
  function renderResults() {
    clearTimer(); show('results'); var s = state.session, wrong = s.answered-s.correct;
    var percent = s.answered ? Math.round(s.correct/s.answered*100) : 0;
    $('vpResultTitle').textContent = !s.answered ? 'Hẹn bạn ở vòng luyện tiếp theo.' : percent >= 80 ? 'Bạn vừa tiến thêm một bước!' : 'Mỗi lần luyện là một lần nhớ hơn.';
    $('vpResultSubtitle').textContent = mode(s.mode).name + ' · Kết quả đã được lưu vào tiến độ của bạn.';
    ring('vpResultRing',percent); $('vpResultPercent').textContent = percent+'%';
    $('vpResultCorrect').textContent = s.correct; $('vpResultWrong').textContent = wrong; $('vpResultTotal').textContent = s.answered;
    $('vpReviewMistakes').hidden = !wrong;
    $('vpResultList').innerHTML = s.results.length ? s.results.map(function(a) { return '<div class="vp-result-row ' + (a.correct ? '' : 'is-wrong') + '"><span aria-label="' + (a.correct ? 'Đúng' : 'Sai') + '">' + (a.correct ? '✓' : '↻') + '</span><div><b lang="ja">' + esc(a.verb) + '</b><small>' + esc(a.form) + '</small></div><div>' + (!a.correct ? '<del lang="ja">' + esc(a.supplied) + '</del>' : '') + '<p lang="ja">' + esc(a.expected) + '</p><small lang="ja">' + esc(a.expectedReading) + '</small></div></div>'; }).join('') : '<p class="vp-muted">Bạn chưa trả lời câu nào trong vòng này.</p>';
    $('vpResultTitle').focus({preventScroll:true});
  }
  $('vpModes').addEventListener('change', function () {
    state.mode = $('vpModes').value;
    renderModes();
  });
  $('vpSetupForm').addEventListener('change',function(e) { if (e.target.matches('.vp-form-chips input')) { state.forms=Array.from(document.querySelectorAll('.vp-form-chips input:checked')).map(function(i){return i.value;}); $('vpSelectAll').textContent=state.forms.length===state.data.forms.length?'Bỏ chọn tất cả':'Chọn tất cả'; } });
  $('vpSelectAll').addEventListener('click',function() { state.forms=state.forms.length===state.data.forms.length?[]:state.data.forms.map(function(f){return f.id;});renderForms(); });
  $('vpSetupForm').addEventListener('submit',function(e) { e.preventDefault();start(); });
  $('vpAnswerForm').addEventListener('submit',function(e) { e.preventDefault();submit(false); });
  $('vpAnswer').addEventListener('keydown',function(e) { if(e.key==='Enter'&&(e.isComposing||e.keyCode===229))e.preventDefault(); });
  $('vpChoices').addEventListener('click',function(e) { var b=e.target.closest('[data-index]');if(!b||state.feedback||state.sending)return;state.choice=state.question.choices[Number(b.dataset.index)];Array.from($('vpChoices').children).forEach(function(el){el.setAttribute('aria-pressed',String(el===b));}); });
  $('vpSkip').addEventListener('click',function(){submit(true);});
  $('vpNext').addEventListener('click',function(){if(state.finishFailed||(state.session.deadline&&state.deadline<=performance.now()&&!state.session.finished)){finish();return;}notice('');renderQuestion();});
  $('vpExit').addEventListener('click',function(){$('vpExitDialog').showModal();});
  $('vpStay').addEventListener('click',function(){$('vpExitDialog').close();});
  $('vpFinish').addEventListener('click',finish);
  $('vpHomeButton').addEventListener('click',function(){show('home');load();});
  $('vpAgain').addEventListener('click',function(){start(state.lastSettings||Object.assign(settings(),{mode:state.session.mode}));});
  $('vpReviewMistakes').addEventListener('click',function(){start({mode:'REVIEW',forms:state.data.forms.map(function(f){return f.id;}),group:0,level:'ALL',count:30});});
  $('vpResumeButton').addEventListener('click',async function(){if(state.sending)return;state.sending=true;try{state.session=await api('/sessions/'+state.data.active.id);state.mode=state.session.mode;state.lastSettings=state.session.settings;show('play');renderQuestion();}catch(e){notice(e.message);}finally{state.sending=false;}});
  $('vpReload').addEventListener('click',load);
  document.addEventListener('keydown',function(e){if(state.view!=='play'||state.sending||e.isComposing||$('vpExitDialog').open)return;if(state.feedback&&e.key==='Enter'&&e.target!==$('vpNext')){e.preventDefault();$('vpNext').click();}else if(!state.feedback&&state.session.mode==='CHOICE'&&/^[1-4]$/.test(e.key)){var b=$('vpChoices').children[Number(e.key)-1];if(b)b.click();}});
  window.addEventListener('pagehide',clearTimer);
  renderIcons($('verbApp'));renderModes();load();
})();
