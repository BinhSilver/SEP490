/* Trang Quiz */
(function () {
  'use strict';

const {
  api, esc, showToast, loadSets, loadSet, fillSetSelector, bindHeaderChrome,
  saveSetId, getSavedSetId, getPrefs, savePrefs, filterCards, renderCardList, shuffle,
  bindFilterSidebar, loadFilterMeta, renderEmptySet, formatDuration, renderAiResponseEl
} = window.JM;

const state = {
  sets: [],
  activeSet: null,
  selectedIds: new Set(),
  questionField: '',
  answerField: '',
  filters: {
    starredOnly: false,
    notRememberedOnly: false,
    jlpt: '',
    lesson: '',
    wordType: ''
  },
  phase: 'setup', // setup | playing | summary
  sessionId: null,
  previousSessionId: null,
  currentQuestion: null,
  quizResult: null,
  stats: null,
  aiOpen: false,
  aiMode: 'explain',
  aiCard: null,
  aiBusy: false,
  search: '',
  questionCount: 'ALL'
};

const pageParams = new URLSearchParams(location.search);
const requestedSetId = Number(pageParams.get('setId') || 0);
const sourceSetId = Number(pageParams.get('sourceSetId') || 0);
let quizHeightObserver = null;

document.addEventListener('DOMContentLoaded', init);

async function init() {
  const redirectTarget = `${location.pathname}${location.search}`;
  if (!window.JlasAuth || !window.JlasAuth.requireLogin(redirectTarget)) return;

  const prefs = getPrefs();
  Object.assign(state.filters, {
    starredOnly: !!prefs.starredOnly,
    notRememberedOnly: !!prefs.notRememberedOnly,
    jlpt: prefs.jlpt || '',
    lesson: prefs.lesson || '',
    wordType: prefs.wordType || ''
  });

  // Resume wrong-only from sessionStorage if redirected
  try {
    const prev = sessionStorage.getItem('jm-prev-quiz-session');
    if (prev) state.previousSessionId = Number(prev);
  } catch { /* ignore */ }

  bindHeaderChrome({
    page: 'quiz',
    onSetChange: async (id) => {
      if (!id) {
        state.activeSet = null;
        render();
        return;
      }
      await selectSet(id);
    }
  });

  bindFilterSidebar(state.filters, () => {
    if (state.phase === 'setup') render();
  });
  bindQuizSidebar();

  bindAiPanel();
  document.addEventListener('keydown', onKeydown);

  try {
    // This redirect carries a runtime quiz-set id. It must not be resolved
    // through /vocab/sets, whose ids belong to the normalized Flashcard store.
    if (sourceSetId) {
      state.activeSet = await loadRuntimeSet(requestedSetId);
      state.selectedIds = new Set((state.activeSet.cards || []).map(c => c.id));
      state.phase = 'setup';
      render();
      return;
    }
    state.sets = await loadSets();
    const sid = requestedSetId || getSavedSetId();
    fillSetSelector(state.sets, sid);
    if (sid) await selectSet(sid);
    else render();
  } catch {
    state.activeSet = null;
    state.selectedIds.clear();
    showToast('Không tải được bộ từ', 'err');
    render();
  }
}

async function loadRuntimeSet(runtimeId) {
  // The source Flashcard set is authoritative. Re-sync it on every resume so
  // a stale/reused in-memory runtime id can never show another set's cards.
  if (sourceSetId) {
    const response = window.FlashApi
      ? await window.FlashApi.post(`/api/quiz/from-flashcard/${sourceSetId}`, { onlyLearning: false })
      : await api.post(`/quiz/from-flashcard/${sourceSetId}`, { onlyLearning: false });
    const rebuiltId = Number(response?.vocabSetId || response?.data?.vocabSetId || 0);
    if (rebuiltId) {
      const params = new URLSearchParams(location.search);
      params.set('setId', String(rebuiltId));
      params.set('sourceSetId', String(sourceSetId));
      history.replaceState(null, '', `${location.pathname}?${params.toString()}`);
      return await api.get(`/quiz/runtime-set/${rebuiltId}`);
    }
  }
  try {
    return await api.get(`/quiz/runtime-set/${runtimeId}`);
  } catch (error) {
    // Runtime quiz sets are intentionally lightweight and may disappear when
    // the server restarts. Rebuild it from the persistent Flashcard set so a
    // login redirect/resume does not leave the user on a broken quiz URL.
    if (!sourceSetId || ![404, 500].includes(error?.status)) throw error;

    const response = window.FlashApi
      ? await window.FlashApi.post(`/api/quiz/from-flashcard/${sourceSetId}`, { onlyLearning: false })
      : await api.post(`/quiz/from-flashcard/${sourceSetId}`, { onlyLearning: false });
    const rebuiltId = Number(response?.vocabSetId || response?.data?.vocabSetId || 0);
    if (!rebuiltId) throw error;

    const params = new URLSearchParams(location.search);
    params.set('setId', String(rebuiltId));
    params.set('sourceSetId', String(sourceSetId));
    history.replaceState(null, '', `${location.pathname}?${params.toString()}`);
    return await api.get(`/quiz/runtime-set/${rebuiltId}`);
  }
}

async function selectSet(id) {
  try {
    state.activeSet = await loadSet(id);
    saveSetId(id);
    state.selectedIds = new Set((state.activeSet.cards || []).map(c => c.id));
    state.phase = 'setup';
    await loadFilterMeta(id);
    const jlpt = document.getElementById('filter-jlpt');
    const wt = document.getElementById('filter-word-type');
    const lesson = document.getElementById('filter-lesson');
    if (jlpt) jlpt.value = state.filters.jlpt || '';
    if (wt) wt.value = state.filters.wordType || '';
    if (lesson) lesson.value = state.filters.lesson || '';
    fillSetSelector(state.sets, id);
    render();
  } catch {
    state.activeSet = null;
    state.selectedIds.clear();
    showToast('Lỗi tải bộ từ', 'err');
  }
}

function visibleCards() {
  const search = state.search.trim().toLocaleLowerCase();
  return filterCards(state.activeSet?.cards || [], state.filters).filter(card => {
    if (!search) return true;
    return [card.kanji, card.hiragana, card.meaning, card.hanViet]
      .filter(Boolean)
      .some(value => String(value).toLocaleLowerCase().includes(search));
  });
}

function render() {
  const page = document.getElementById('jlabBaseContent');
  const hero = document.getElementById('quiz-hero-root');
  const toolbar = document.getElementById('quiz-toolbar-root');
  if (page) {
    page.classList.toggle('is-quiz-setup', state.phase === 'setup');
    page.classList.toggle('is-quiz-quick-setup', state.phase === 'setup' && !!sourceSetId);
    page.classList.toggle('is-quiz-playing', state.phase === 'playing');
    page.classList.toggle('is-quiz-summary', state.phase === 'summary');
  }
  document.getElementById('study-layout').classList.toggle('is-ai-open', state.aiOpen);
  document.getElementById('ai-col').hidden = !state.aiOpen;
  syncQuizSidebar();

  const root = document.getElementById('page-root');
  if (!state.activeSet) {
    if (hero) hero.innerHTML = '';
    if (toolbar) toolbar.innerHTML = '';
    return renderEmptySet(root);
  }
  if (state.phase === 'setup') {
    if (sourceSetId) {
      if (hero) hero.innerHTML = '';
      if (toolbar) toolbar.innerHTML = '';
      return renderQuickSetup(root);
    }
    if (hero) renderPrepHero(hero);
    if (toolbar) toolbar.innerHTML = '';
    return renderSetup(root);
  }
  if (state.phase === 'playing') {
    if (hero) hero.innerHTML = '';
    if (toolbar) renderPlayingToolbar(toolbar);
    return renderPlaying(root);
  }
  if (hero) renderSummaryHero(hero);
  if (toolbar) toolbar.innerHTML = '';
  return renderSummary(root);
}

function renderQuickSetup(root) {
  const total = (state.activeSet.cards || []).length;
  ensureAnswerField();
  const fields = availableQuizFields();
  const options = [4, 10, 20].filter(n => n <= total);
  if (total && !options.includes(total)) options.push(total);
  options.sort((a, b) => a - b);
  if (state.questionCount === 'ALL' && total >= 4) state.questionCount = options.includes(20) ? 20 : options[options.length - 1];
  if (!options.includes(Number(state.questionCount))) state.questionCount = options[options.length - 1] || 'ALL';
  const modeButtons = (target, selected, disabled) => fields.map(value => `
    <button type="button" class="${selected === value ? 'is-active' : ''}" data-quick-${target}="${value}" aria-pressed="${selected === value}" ${disabled === value ? 'disabled' : ''}>${QUIZ_FIELD_LABELS[value]}</button>
  `).join('');

  root.innerHTML = `
    <section class="quiz-quick-setup sr-setup" aria-labelledby="quiz-setup-title">
      <header class="sr-setup__heading">
        <h1 id="quiz-setup-title" class="sr-set-title">${esc(cleanSetName(state.activeSet.name))}</h1>
      </header>
      <form id="quiz-quick-form" class="sr-setup__card">
        <div class="sr-setup__grid">
          <label class="sr-setup__field">Thẻ
            <span class="sr-setup__control"><select disabled aria-label="Phạm vi thẻ"><option>Tất cả thẻ hợp lệ</option></select></span>
          </label>
          <label class="sr-setup__field">Số câu
            <span class="sr-setup__control"><select id="quiz-question-count" aria-label="Số câu hỏi">
            ${options.map(n => `<option value="${n}" ${Number(state.questionCount) === n ? 'selected' : ''}>${n} câu</option>`).join('')}
            ${total >= 4 ? `<option value="ALL" ${state.questionCount === 'ALL' ? 'selected' : ''}>Toàn bộ ${total} câu</option>` : ''}
            </select></span>
          </label>
          <div class="sr-setup__field"><span>Hỏi bằng</span><div class="sr-setup__segments quiz-mode-segments" style="--mode-count:${fields.length}" role="group" aria-label="Nội dung câu hỏi">${modeButtons('question', state.questionField, '')}</div></div>
          <div class="sr-setup__field"><span>Đáp bằng</span><div class="sr-setup__segments quiz-mode-segments" style="--mode-count:${fields.length}" role="group" aria-label="Nội dung đáp án">${modeButtons('answer', state.answerField, state.questionField)}</div></div>
        </div>
        <button type="submit" class="sr-button sr-primary" id="start-quick-quiz" ${total < 4 || fields.length < 2 ? 'disabled' : ''}>Bắt đầu kiểm tra</button>
      </form>
    </section>
  `;

  root.querySelector('#quiz-question-count').onchange = event => {
    state.questionCount = event.target.value === 'ALL' ? 'ALL' : Number(event.target.value);
  };
  root.querySelectorAll('[data-quick-question]').forEach(button => button.onclick = () => {
    state.questionField = button.dataset.quickQuestion;
    ensureAnswerField();
    renderQuickSetup(root);
  });
  root.querySelectorAll('[data-quick-answer]').forEach(button => button.onclick = () => {
    if (button.disabled) return;
    state.answerField = button.dataset.quickAnswer;
    renderQuickSetup(root);
  });
  root.querySelector('#quiz-quick-form').onsubmit = event => { event.preventDefault(); startQuiz(false); };
}

function bindQuizSidebar() {
  const question = document.getElementById('quiz-question-field');
  const answer = document.getElementById('quiz-answer-field');
  if (question) question.addEventListener('change', () => {
    state.questionField = question.value;
    ensureAnswerField();
    if (state.phase === 'setup') render();
  });
  if (answer) answer.addEventListener('change', () => {
    state.answerField = answer.value;
    if (state.phase === 'setup') render();
  });

  document.querySelectorAll('[data-filter-key]').forEach(button => {
    button.addEventListener('click', () => {
      const key = button.dataset.filterKey;
      const value = button.dataset.filterValue || '';
      state.filters[key] = value;
      const select = document.getElementById(`filter-${key === 'wordType' ? 'word-type' : key}`);
      if (select) select.value = value;
      if (state.phase === 'setup') render();
    });
  });

  const reset = document.getElementById('btn-reset-filters');
  if (reset) reset.addEventListener('click', () => {
    Object.assign(state.filters, { jlpt: '', wordType: '', lesson: '', starredOnly: false, notRememberedOnly: false });
    state.search = '';
    ['filter-jlpt', 'filter-word-type', 'filter-lesson'].forEach(id => {
      const select = document.getElementById(id);
      if (select) select.value = '';
    });
    savePrefs({ jlpt: '', wordType: '', lesson: '', starredOnly: false, notRememberedOnly: false });
    render();
  });
}

function syncQuizSidebar() {
  const question = document.getElementById('quiz-question-field');
  const answer = document.getElementById('quiz-answer-field');
  ensureAnswerField();
  if (question) {
    question.innerHTML = quizFieldOptions(state.questionField, state.answerField);
    question.value = state.questionField;
  }
  if (answer) {
    answer.innerHTML = quizFieldOptions(state.answerField, state.questionField);
    answer.value = state.answerField;
  }
  document.querySelectorAll('[data-filter-key]').forEach(button => {
    button.classList.toggle('is-active', (state.filters[button.dataset.filterKey] || '') === (button.dataset.filterValue || ''));
  });
}

function cleanSetName(name) {
  return String(name || 'Bộ Flashcard').replace(/^FC#\d+\s*·\s*/, '');
}

const QUIZ_FIELD_LABELS = {
  KANJI: 'Kanji',
  MEANING: 'Nghĩa',
  HIRAGANA: 'Hiragana',
  HAN_VIET: 'Hán Việt'
};

function availableQuizFields() {
  const cards = state.activeSet?.cards || [];
  const fields = ['KANJI', 'HIRAGANA', 'MEANING'];
  if (cards.length && cards.every(card => String(card.hanViet || '').trim())) fields.push('HAN_VIET');
  return fields;
}

function quizFieldOptions(selected, exclude) {
  const placeholder = selected ? '' : '<option value="" selected disabled>Chọn nội dung...</option>';
  return placeholder + availableQuizFields()
    .filter(value => value !== exclude)
    .map(value => `<option value="${value}" ${value === selected ? 'selected' : ''}>${QUIZ_FIELD_LABELS[value]}</option>`)
    .join('');
}

function quizFieldDescription(field) {
  return !field ? 'Chọn nội dung câu hỏi.'
    : field === 'KANJI' ? 'Hiển thị chữ Kanji để người học trả lời.'
      : field === 'HIRAGANA' ? 'Hiển thị cách đọc tiếng Nhật để người học trả lời.'
        : 'Hiển thị nghĩa tiếng Việt để người học trả lời.';
}

function ensureAnswerField() {
  const fields = availableQuizFields();
  if (!fields.includes(state.questionField)) state.questionField = fields[0] || '';
  if (!fields.includes(state.answerField)) state.answerField = fields.find(value => value !== state.questionField) || '';
  if (!state.questionField || !state.answerField) return;
  if (state.answerField === state.questionField) {
    state.answerField = fields.find(value => value !== state.questionField) || '';
  }
}

function quizModeValue() {
  return `${state.questionField}_${state.answerField}`;
}

function flashcardBackUrl() {
  return sourceSetId
    ? `/flashcard/study.html?setId=${encodeURIComponent(sourceSetId)}`
    : '/flashcard/library.html';
}

function getMastery() {
  const cards = state.activeSet?.cards || [];
  const remembered = cards.filter(c => c.memoryStatus === 'REMEMBERED').length;
  return {
    total: cards.length,
    remembered,
    percentage: cards.length ? Math.round((remembered / cards.length) * 100) : 0
  };
}

function renderPrepHero(root) {
  const mastery = getMastery();
  root.innerHTML = `
    <section class="quiz-hero quiz-hero--prep">
      <div class="quiz-hero__inner">
        <div class="quiz-hero__copy">
          <div class="quiz-hero__meta"><span class="quiz-eyebrow"><i class="fa-solid fa-vial"></i> Chuẩn bị bài</span><span>· Bộ Flashcard</span></div>
          <h1>${esc(cleanSetName(state.activeSet.name))}</h1>
          <p>Chuẩn bị từ vựng và chọn dạng câu hỏi để bắt đầu một phiên ôn tập hiệu quả.</p>
        </div>
        <div class="quiz-hero__metric">
          <i class="fa-solid fa-bullseye" aria-hidden="true"></i>
          <div><span>Độ thành thạo</span><strong>${mastery.percentage}% <small>(${mastery.remembered}/${mastery.total} thẻ)</small></strong></div>
        </div>
      </div>
    </section>
  `;
}

function renderSummaryHero(root) {
  const s = state.stats || { percentage: 0, correct: 0, total: 0, wrong: 0 };
  const tone = s.percentage >= 80 ? 'is-excellent' : s.percentage >= 50 ? 'is-progress' : 'is-needs-review';
  root.innerHTML = `
    <section class="quiz-hero quiz-hero--summary ${tone}">
      <div class="quiz-hero__inner">
        <div class="quiz-hero__copy">
          <div class="quiz-hero__meta"><span class="quiz-eyebrow"><i class="fa-solid fa-circle-check"></i> Kết quả hoàn thành</span><span>· ${esc(cleanSetName(state.activeSet?.name))}</span></div>
          <h1>${s.percentage >= 80 ? 'Xuất sắc! Bạn đã nắm vững bài học.' : 'Bạn đã hoàn thành bài kiểm tra.'}</h1>
          <p>${s.wrong ? 'Hãy ôn lại một số từ còn nhầm để củng cố khả năng ghi nhớ.' : 'Bạn đã hoàn thành toàn bộ câu hỏi trong phiên kiểm tra này.'}</p>
        </div>
        <div class="quiz-score-card">
          <div class="quiz-score-ring" style="--score:${Math.max(0, Math.min(100, Number(s.percentage) || 0))}"><strong>${s.percentage}%</strong></div>
          <div class="quiz-score-copy"><strong>Độ chính xác</strong><span>${s.correct}/${s.total} câu đúng</span></div>
        </div>
      </div>
    </section>
  `;
}

function renderPlayingToolbar(root) {
  root.innerHTML = '';
}

function renderSetup(root) {
  const cards = visibleCards();
  ensureAnswerField();
  const count = cards.filter(c => state.selectedIds.has(c.id)).length;
  const fieldsReady = !!state.questionField && !!state.answerField;
  root.innerHTML = `
    <div class="quiz-state quiz-state--setup animate-up">
      <section class="quiz-selection card">
        <div class="quiz-selection__toolbar card">
          <div class="quiz-selection__bulk">
            <button class="btn btn--secondary btn--sm" id="sel-all"><i class="fa-solid fa-check-double"></i> Chọn tất cả</button>
            <button class="btn btn--ghost btn--sm" id="sel-none"><i class="fa-solid fa-xmark"></i> Bỏ chọn</button>
            <span class="muted">Đã chọn <strong id="selected-count">${count}</strong>/${cards.length}</span>
          </div>
          <span class="quiz-count" id="visible-count">${cards.length} thẻ</span>
          <label class="quiz-search"><i class="fa-solid fa-magnifying-glass"></i><input id="quiz-search" type="search" value="${esc(state.search)}" placeholder="Tìm kiếm từ vựng, Hán tự..." aria-label="Tìm kiếm từ vựng"></label>
        </div>
        <div class="card-list quiz-card-list" id="card-list"></div>
      </section>

      <section class="quiz-start-bar card">
        <div class="quiz-start-bar__icon"><i class="fa-solid fa-bolt" aria-hidden="true"></i></div>
        <div class="quiz-start-bar__copy">
          <strong>Sẵn sàng kiểm tra</strong>
          <span id="ctaTitle">${count ? 'Bắt đầu phiên kiểm tra' : 'Chưa chọn thẻ nào'}</span>
          <small id="ctaSubtitle">${count} câu hỏi đã chọn</small>
        </div>
        <div class="quiz-start-bar__actions">
          <div class="quiz-mode-picker quiz-field-picker" role="group" aria-label="Cấu hình câu hỏi">
            <label>Hỏi bằng<select id="setup-question-field" class="select">${quizFieldOptions(state.questionField, state.answerField)}</select></label>
            <label>Đáp án bằng<select id="setup-answer-field" class="select">${quizFieldOptions(state.answerField, state.questionField)}</select></label>
          </div>
          <button class="btn btn--primary" id="start-quiz" ${count < 4 || !fieldsReady ? 'disabled' : ''}>
            ${count < 4 ? 'Cần ít nhất 4 thẻ' : !fieldsReady ? 'Chọn nội dung câu hỏi' : `Bắt đầu (${count} câu)`}
          </button>
        </div>
      </section>
    </div>
  `;

  renderQuizCardList(root.querySelector('#card-list'), cards, state.selectedIds, (id) => {
    if (state.selectedIds.has(id)) state.selectedIds.delete(id);
    else state.selectedIds.add(id);
    render();
  });

  root.querySelector('#sel-all').onclick = () => {
    cards.forEach(c => state.selectedIds.add(c.id));
    render();
  };
  root.querySelector('#sel-none').onclick = () => {
    cards.forEach(c => state.selectedIds.delete(c.id));
    render();
  };
  root.querySelector('#start-quiz').onclick = () => startQuiz(false);
  root.querySelector('#setup-question-field').onchange = event => {
    state.questionField = event.target.value;
    ensureAnswerField();
    render();
  };
  root.querySelector('#setup-answer-field').onchange = event => {
    state.answerField = event.target.value;
    render();
  };
  root.querySelector('#quiz-search').oninput = (event) => {
    state.search = event.target.value;
    render();
    const input = document.getElementById('quiz-search');
    if (input) {
      input.focus();
      input.setSelectionRange(state.search.length, state.search.length);
    }
  };
}

function renderQuizCardList(listEl, cards, selectedIds, onToggle) {
  listEl.innerHTML = '';
  if (!cards.length) {
    listEl.innerHTML = '<p class="muted quiz-empty-list">Không có thẻ nào khớp bộ lọc.</p>';
    return;
  }
  cards.forEach(card => {
    const item = document.createElement('article');
    item.className = `quiz-vocab-card ${selectedIds.has(card.id) ? 'is-selected' : ''}`;
    item.innerHTML = `
      <button type="button" class="quiz-card-check" aria-label="${selectedIds.has(card.id) ? 'Bỏ chọn' : 'Chọn'} ${esc(card.kanji)}"><i class="fa-solid fa-check"></i></button>
      <span class="quiz-card-star ${card.starred ? 'is-starred' : ''}"><i class="fa-solid fa-star"></i></span>
      <div class="quiz-vocab-card__copy"><div class="quiz-vocab-card__term"><strong>${esc(card.kanji)}</strong><span>${esc(card.hiragana || '')}</span></div><small>${esc(card.meaning || '')}</small></div>
      <div class="quiz-vocab-card__meta"><span>${esc(card.jlptLevel || 'Flashcard')}${card.wordType ? ` · ${esc(card.wordType)}` : ''}</span><em class="${card.memoryStatus === 'REMEMBERED' ? 'is-remembered' : 'is-learning'}">${card.memoryStatus === 'REMEMBERED' ? 'Đã nhớ' : 'Chưa nhớ'}</em></div>
      <button type="button" class="btn btn--ghost btn--sm quiz-card-audio" aria-label="Nghe ${esc(card.kanji)}"><i class="fa-solid fa-volume-high"></i></button>
    `;
    item.onclick = event => {
      if (event.target.closest('.quiz-card-audio')) {
        event.stopPropagation();
        speakText(card.hiragana || card.kanji);
        return;
      }
      onToggle(card.id);
    };
    listEl.appendChild(item);
  });
}

async function startQuiz(wrongOnly) {
  try {
    if (!state.questionField || !state.answerField) {
      showToast('Hãy chọn nội dung câu hỏi và đáp án', 'err');
      return;
    }
    let ids = [...state.selectedIds];
    if (sourceSetId) {
      ids = [...(state.activeSet.cards || [])].map(card => card.id);
      const requestedCount = state.questionCount === 'ALL' ? ids.length : Number(state.questionCount);
      if (requestedCount > 0 && requestedCount < ids.length) ids = shuffle(ids).slice(0, requestedCount);
    }
    const body = {
      setId: state.activeSet.id,
      sessionType: 'QUIZ',
      mode: quizModeValue(),
      questionField: state.questionField,
      answerField: state.answerField,
      selectedCardIds: wrongOnly ? null : (ids.length ? ids : null),
      wrongOnly: !!wrongOnly,
      previousSessionId: wrongOnly ? state.previousSessionId : null,
      jlptLevel: state.filters.jlpt || null,
      lesson: state.filters.lesson || null,
      wordType: state.filters.wordType || null,
      starredOnly: state.filters.starredOnly || null,
      notRememberedOnly: state.filters.notRememberedOnly || null
    };
    const res = await api.post('/quiz/start', body);
    state.sessionId = res.id ?? res;
    state.phase = 'playing';
    state.quizResult = null;
    await fetchNextQuestion();
  } catch (e) {
    console.error(e);
    showToast(e?.message || 'Không thể bắt đầu trắc nghiệm', 'err');
  }
}

function renderPlaying(root) {
  const q = state.currentQuestion;
  if (!q) {
    root.innerHTML = '<div class="card quiz-loading"><i class="fa-solid fa-spinner fa-spin"></i> Đang tải câu hỏi...</div>';
    return;
  }

  root.innerHTML = `
    <div class="quiz-state quiz-state--playing animate-up">
      <section class="quiz-question-card card">
        <div class="quiz-question-meta"><span>${q.questionIndex}/${q.totalQuestions}</span></div>
        <div class="quiz-question-term fc-kanji">${esc(q.question)}</div>
        <button type="button" class="btn btn--ghost btn--sm quiz-speak-button" id="btn-quiz-speak" title="Nghe phát âm" aria-label="Nghe phát âm">
          <i class="fa-solid fa-volume-high"></i>
        </button>
        <div class="quiz-answer-area">
          <p class="quiz-answer-label">Chọn đáp án đúng</p>
          <div class="quiz-options" id="quiz-options">
            ${q.options.map(opt => `
              <button type="button" class="quiz-option" data-id="${opt.cardId}">
                <span class="opt-label">${esc(opt.label)}</span>
                <span class="quiz-option__text">${esc(opt.text)}</span>
              </button>
            `).join('')}
          </div>
        </div>
      </section>
    </div>
  `;

  root.querySelector('#btn-quiz-speak').onclick = () => speakText(q.kanji || q.question);
  root.querySelector('#quiz-options').onclick = async (e) => {
    const btn = e.target.closest('.quiz-option');
    if (!btn || state.quizResult) return;
    await submitAnswer(parseInt(btn.dataset.id, 10));
  };
  observeQuizPanelHeight();
}

function observeQuizPanelHeight() {
  if (quizHeightObserver) quizHeightObserver.disconnect();
  const quiz = document.querySelector('.quiz-state--playing');
  if (!quiz) return;
  const sync = () => {
    const aiCol = document.getElementById('ai-col');
    const panel = aiCol?.querySelector('.ai-panel');
    const messages = panel?.querySelector('.ai-messages');
    const composer = panel?.querySelector('.ai-composer');
    if (!aiCol || !panel) return;
    if (!state.aiOpen || window.innerWidth <= 760) {
      aiCol.style.removeProperty('--quiz-ai-height');
      aiCol.style.removeProperty('height');
      panel.style.removeProperty('height');
      return;
    }
    const height = `${Math.ceil(quiz.getBoundingClientRect().height)}px`;
    aiCol.style.setProperty('--quiz-ai-height', height);
    aiCol.style.setProperty('height', height, 'important');
    panel.style.setProperty('display', 'grid', 'important');
    panel.style.setProperty('grid-template-rows', 'auto minmax(0, 1fr) auto', 'important');
    panel.style.setProperty('height', height, 'important');
    panel.style.setProperty('min-height', '0', 'important');
    panel.style.setProperty('max-height', 'none', 'important');
    if (messages) {
      messages.style.setProperty('min-height', '0', 'important');
      messages.style.setProperty('max-height', 'none', 'important');
      messages.style.setProperty('overflow-y', 'auto', 'important');
    }
    if (composer) composer.style.setProperty('align-self', 'end', 'important');
  };
  if ('ResizeObserver' in window) {
    quizHeightObserver = new ResizeObserver(sync);
    quizHeightObserver.observe(quiz);
  }
  requestAnimationFrame(sync);
}

function speakText(text) {
  if (!text || !('speechSynthesis' in window) || !('SpeechSynthesisUtterance' in window)) {
    showToast('Trình duyệt chưa hỗ trợ phát âm', 'err');
    return;
  }
  window.speechSynthesis.cancel();
  const utterance = new SpeechSynthesisUtterance(String(text));
  utterance.lang = 'ja-JP';
  utterance.rate = 0.85;
  window.speechSynthesis.speak(utterance);
}

async function submitAnswer(chosenId) {
  try {
    const response = await api.post('/quiz/answer', {
      sessionId: state.sessionId,
      cardId: state.currentQuestion.cardId,
      chosenCardId: chosenId
    });
    const res = response?.data && typeof response.data === 'object' ? response.data : response;
    const wasCorrect = res?.correct === true || res?.correct === 'true';
    state.quizResult = res;

    document.querySelectorAll('.quiz-option').forEach(opt => {
      const optId = parseInt(opt.dataset.id, 10);
      if (optId === chosenId) {
        const resultClass = wasCorrect ? 'is-correct' : 'is-wrong';
        const color = wasCorrect ? 'var(--color-success, #006947)' : 'var(--color-error, #ba1a1a)';
        const background = wasCorrect ? 'var(--color-success-bg, #d6f5e7)' : 'var(--color-error-container, #ffdad6)';
        opt.classList.add(resultClass, 'is-answer-bounce');
        opt.dataset.result = wasCorrect ? 'correct' : 'wrong';
        opt.style.setProperty('opacity', '1', 'important');
        opt.style.setProperty('color', color, 'important');
        opt.style.setProperty('background', background, 'important');
        opt.style.setProperty('border-color', color, 'important');
        opt.style.setProperty('box-shadow', `0 0 0 3px ${wasCorrect ? 'rgba(0, 105, 71, .18)' : 'rgba(186, 26, 26, .16)'}`, 'important');
        const label = opt.querySelector('.opt-label');
        if (label) {
          label.style.setProperty('color', '#fff', 'important');
          label.style.setProperty('background', color, 'important');
        }
        if (typeof opt.animate === 'function') {
          const frames = wasCorrect
            ? [
                { transform: 'translateY(0) scale(1)' },
                { transform: 'translateY(-9px) scale(1.03)', offset: .38 },
                { transform: 'translateY(3px) scale(.995)', offset: .7 },
                { transform: 'translateY(0) scale(1)' }
              ]
            : [
                { transform: 'translateX(0)' },
                { transform: 'translateX(-11px)' },
                { transform: 'translateX(10px)' },
                { transform: 'translateX(-7px)' },
                { transform: 'translateX(4px)' },
                { transform: 'translateX(0)' }
              ];
          opt.animate(frames, { duration: wasCorrect ? 700 : 620, easing: 'ease', iterations: 1 });
        }
      } else {
        opt.classList.add('is-dimmed');
        opt.style.setProperty('opacity', '.42', 'important');
      }
      opt.disabled = true;
    });

    const options = document.getElementById('quiz-options');
    if (options && !document.getElementById('quiz-next-question')) {
      const next = document.createElement('button');
      next.type = 'button';
      next.id = 'quiz-next-question';
      next.className = 'btn btn--primary quiz-next-question';
      next.textContent = 'Câu tiếp theo';
      next.onclick = () => fetchNextQuestion();
      options.insertAdjacentElement('afterend', next);
      next.focus();
    }
  } catch (e) {
    console.error(e);
    showToast(e?.message || 'Lỗi gửi đáp án', 'err');
  }
}

async function fetchNextQuestion() {
  state.quizResult = null;
  try {
    const q = await api.get(`/quiz/session/${state.sessionId}/next`);
    if (!q) {
      state.stats = await api.post(`/quiz/session/${state.sessionId}/finish`);
      state.previousSessionId = state.sessionId;
      state.aiOpen = false;
      state.aiCard = null;
      try { sessionStorage.setItem('jm-prev-quiz-session', String(state.sessionId)); } catch { /* ignore */ }
      state.phase = 'summary';
    } else {
      state.currentQuestion = q;
    }
    render();
  } catch (e) {
    console.error(e);
    showToast(e?.message || 'Không tải được câu hỏi tiếp theo', 'err');
    state.phase = 'summary';
    render();
  }
}

function renderSummary(root) {
  const s = state.stats || {
    total: 0, correct: 0, wrong: 0, percentage: 0,
    wrongCards: [], durationSeconds: 0, avgSecondsPerQuestion: 0
  };
  const maxBar = Math.max(s.correct, s.wrong, 1);
  const correctH = Math.round((s.correct / maxBar) * 110);
  const wrongH = Math.round((s.wrong / maxBar) * 110);

  root.innerHTML = `
    <div class="quiz-state quiz-state--summary animate-up">
      <section class="quiz-summary-stats" aria-label="Thống kê kết quả">
        <article class="quiz-summary-stat">
          <div><span>Tổng câu</span><i class="fa-solid fa-list-ol"></i></div>
          <strong>${s.total}</strong><small>Hoàn thành 100%</small>
        </article>
        <article class="quiz-summary-stat is-success">
          <div><span>Đúng</span><i class="fa-solid fa-circle-check"></i></div>
          <strong>${s.correct}</strong><small>Trả lời chính xác</small>
        </article>
        <article class="quiz-summary-stat is-error">
          <div><span>Sai</span><i class="fa-solid fa-circle-xmark"></i></div>
          <strong>${s.wrong}</strong><small>Cần ôn lại</small>
        </article>
        <article class="quiz-summary-stat">
          <div><span>Thời gian làm bài</span><i class="fa-solid fa-clock"></i></div>
          <strong>${formatDuration(s.durationSeconds || 0)}</strong><small>Tổng thời gian</small>
        </article>
        <article class="quiz-summary-stat">
          <div><span>Trung bình/câu</span><i class="fa-solid fa-gauge-high"></i></div>
          <strong>${s.avgSecondsPerQuestion || 0}s</strong><small>Tốc độ làm bài</small>
        </article>
      </section>

      <section class="quiz-summary-grid">
        <article class="quiz-summary-panel card">
          <div class="quiz-panel-heading">
            <div><h2>Phân tích kết quả</h2><p>Tổng quan số câu đúng và sai trong phiên.</p></div>
            <i class="fa-solid fa-chart-simple" aria-hidden="true"></i>
          </div>
          <div class="quiz-chart" aria-label="Biểu đồ câu đúng và câu sai">
            <div class="quiz-chart__axis"><span>Đúng</span><strong>${s.correct}</strong><div class="quiz-chart__bar is-success" style="--bar-height:${correctH}px"></div></div>
            <div class="quiz-chart__axis"><span>Sai</span><strong>${s.wrong}</strong><div class="quiz-chart__bar is-error" style="--bar-height:${wrongH}px"></div></div>
          </div>
          <div class="quiz-summary-tip"><i class="fa-solid fa-lightbulb"></i><span>${s.wrong ? 'Các câu sai đã được lưu để bạn có thể ôn lại ngay.' : 'Tuyệt vời, phiên này không có câu trả lời sai.'}</span></div>
        </article>

        <article class="quiz-summary-panel quiz-wrong-panel card">
          <div class="quiz-panel-heading">
            <div><h2>Câu trả lời sai (${s.wrongCards?.length || 0})</h2><p>${s.wrongCards?.length ? 'Các từ vựng cần được ôn tập lại ngay.' : 'Bạn đã trả lời đúng toàn bộ câu hỏi.'}</p></div>
            <i class="fa-solid fa-triangle-exclamation" aria-hidden="true"></i>
          </div>
          ${(s.wrongCards && s.wrongCards.length) ? `
            <div class="quiz-wrong-list">
              ${s.wrongCards.map(c => `
                <div class="quiz-wrong-item">
                  <div class="quiz-wrong-item__term"><strong>${esc(c.kanji)}</strong><span>${esc(c.hiragana || '')}</span><small>${esc(c.meaning || '')}</small></div>
                  <span class="quiz-wrong-badge">Cần ôn lại</span>
                  <button type="button" class="btn btn--ghost btn--sm quiz-audio-button" data-quiz-speak="${esc(c.hiragana || c.kanji)}" aria-label="Nghe ${esc(c.kanji)}"><i class="fa-solid fa-volume-high"></i></button>
                </div>
              `).join('')}
            </div>
            <div class="quiz-wrong-actions">
              <button class="btn btn--primary" id="study-wrong"><i class="fa-solid fa-graduation-cap"></i> Học lại bằng thẻ</button>
              <button class="btn btn--secondary" id="quiz-wrong"><i class="fa-solid fa-bolt"></i> Trắc nghiệm lại từ sai</button>
            </div>
          ` : '<div class="quiz-empty-wrong"><i class="fa-solid fa-circle-check"></i><span>Không có từ nào cần ôn lại.</span></div>'}
        </article>
      </section>

      <div class="quiz-summary-actions">
        <a class="btn btn--ghost" href="${flashcardBackUrl()}"><i class="fa-solid fa-book"></i> Quay lại Flashcard</a>
        <button class="btn btn--primary" id="retry-quiz"><i class="fa-solid fa-rotate-left"></i> Làm lại bài</button>
      </div>
    </div>
  `;

  root.querySelector('#retry-quiz').onclick = () => {
    state.phase = 'setup';
    state.stats = null;
    state.currentQuestion = null;
    state.quizResult = null;
    state.aiOpen = false;
    state.aiCard = null;
    render();
  };

  const studyWrong = root.querySelector('#study-wrong');
  if (studyWrong) {
    studyWrong.onclick = () => {
      const ids = (s.wrongCards || []).map(c => c.id);
      try {
        sessionStorage.setItem('jm-pending-study-ids', JSON.stringify(ids));
      } catch { /* ignore */ }
      window.location.href = '/flashcard/flashcard.html';
    };
  }
  const quizWrong = root.querySelector('#quiz-wrong');
  if (quizWrong) quizWrong.onclick = () => startQuiz(true);
  root.querySelectorAll('[data-quiz-speak]').forEach(button => {
    button.onclick = () => speakText(button.getAttribute('data-quiz-speak'));
  });
}

function onKeydown(e) {
  const tag = (e.target.tagName || '').toLowerCase();
  if (tag === 'input' || tag === 'textarea' || tag === 'select') return;
  if (state.phase !== 'playing') return;
  if (state.quizResult) {
    if (e.code === 'ArrowRight' || e.code === 'Enter') fetchNextQuestion();
    return;
  }
  if (e.code === 'Digit1' || e.code === 'KeyA') clickOption(0);
  if (e.code === 'Digit2' || e.code === 'KeyB') clickOption(1);
  if (e.code === 'Digit3' || e.code === 'KeyC') clickOption(2);
  if (e.code === 'Digit4' || e.code === 'KeyD') clickOption(3);
}

function clickOption(index) {
  const options = document.querySelectorAll('.quiz-option');
  if (options[index]) options[index].click();
}

/* AI */
function bindAiPanel() {
  document.getElementById('btn-close-ai').onclick = () => {
    state.aiOpen = false;
    render();
  };
  document.querySelectorAll('.ai-tab').forEach(tab => {
    tab.onclick = () => {
      state.aiMode = tab.dataset.ai;
      document.querySelectorAll('.ai-tab').forEach(t => t.classList.toggle('is-active', t === tab));
      if (state.aiCard) runAi(state.aiMode);
    };
  });
  document.getElementById('ai-composer').onsubmit = async (e) => {
    e.preventDefault();
    const input = document.getElementById('ai-input');
    const msg = input.value.trim();
    if (!msg || state.aiBusy) return;
    input.value = '';
    const box = document.getElementById('ai-messages');
    const user = document.createElement('div');
    user.className = 'ai-bubble is-user';
    user.textContent = msg;
    box.appendChild(user);
    const loading = document.createElement('div');
    loading.className = 'ai-loading';
    loading.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> Đang trả lời...';
    box.appendChild(loading);
    state.aiBusy = true;
    try {
      const card = state.aiCard;
      const res = await api.post('/ai/chat', {
        cardId: card?.id,
        term: card?.kanji,
        message: msg,
        context: card ? `${card.kanji} ${card.hiragana || ''} ${card.meaning || ''}` : null
      });
      loading.remove();
      box.appendChild(renderAiResponseEl(res));
      box.scrollTop = box.scrollHeight;
    } catch {
      loading.textContent = 'Lỗi chat AI';
    } finally {
      state.aiBusy = false;
    }
  };
}

async function openAi(mode) {
  const q = state.currentQuestion;
  if (!q) return showToast('Chưa có câu hỏi', 'err');
  state.aiCard = {
    id: q.cardId,
    kanji: q.kanji || q.question,
    hiragana: q.hiragana,
    meaning: q.meaning,
    hanViet: q.hanViet
  };
  state.aiOpen = true;
  state.aiMode = mode || 'explain';
  document.querySelectorAll('.ai-tab').forEach(t => t.classList.toggle('is-active', t.dataset.ai === state.aiMode));
  render();
  await runAi(state.aiMode);
}

async function runAi(mode) {
  const card = state.aiCard;
  const box = document.getElementById('ai-messages');
  if (!card || !box) return;
  box.innerHTML = '<div class="ai-loading"><i class="fa-solid fa-spinner fa-spin"></i> AI đang phân tích...</div>';
  state.aiBusy = true;
  try {
    const path = mode === 'examples' ? '/ai/examples' : mode === 'kanji' ? '/ai/kanji' : '/ai/explain';
    const res = await api.post(path, { cardId: card.id, term: card.kanji, type: mode });
    box.innerHTML = '';
    box.appendChild(renderAiResponseEl(res));
  } catch (e) {
    box.innerHTML = `<div class="ai-bubble">Không gọi được AI. ${esc(e.message || '')}</div>`;
  } finally {
    state.aiBusy = false;
  }
}

})();
