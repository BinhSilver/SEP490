/* Trang Flashcard */
const {
  api, esc, showToast, loadSets, loadSet, fillSetSelector, bindHeaderChrome,
  saveSetId, getSavedSetId, getPrefs, savePrefs, shuffle, filterCards,
  renderCardList, bindFilterSidebar, loadFilterMeta, renderEmptySet, renderAiResponseEl
} = window.JM;

const state = {
  sets: [],
  activeSet: null,
  selectedIds: new Set(),
  mode: 'KANJI',
  filters: {
    showFurigana: true,
    onlyKanji: false,
    starredOnly: false,
    notRememberedOnly: false,
    jlpt: '',
    lesson: '',
    wordType: ''
  },
  isStudying: false,
  studyFinished: false,
  studyCards: [],
  currentIndex: 0,
  isFlipped: false,
  studyRemembered: 0,
  studyNotRemembered: 0,
  autoFlipSec: 0,
  autoFlipTimer: null,
  showExample: false,
  fullscreen: false,
  aiOpen: false,
  aiMode: 'explain',
  aiCard: null,
  aiBusy: false,
  groupCards: []
};

document.addEventListener('DOMContentLoaded', init);

async function init() {
  const prefs = getPrefs();
  Object.assign(state.filters, {
    showFurigana: prefs.showFurigana !== false,
    onlyKanji: !!prefs.onlyKanji,
    starredOnly: !!prefs.starredOnly,
    notRememberedOnly: !!prefs.notRememberedOnly,
    jlpt: prefs.jlpt || '',
    lesson: prefs.lesson || '',
    wordType: prefs.wordType || ''
  });

  bindHeaderChrome({
    page: 'flashcard',
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
    if (!state.isStudying) render();
    else softUpdateStudyCard();
  });

  bindAiPanel();
  bindGroupModal();
  document.addEventListener('keydown', onKeydown);

  try {
    state.sets = await loadSets();
    fillSetSelector(state.sets, getSavedSetId());
    const sid = getSavedSetId();
    if (sid) await selectSet(sid);
    else render();
  } catch {
    showToast('Không tải được bộ từ', 'err');
    render();
  }
}

async function selectSet(id) {
  try {
    state.activeSet = await loadSet(id);
    saveSetId(id);
    state.selectedIds = new Set((state.activeSet.cards || []).map(c => c.id));
    await loadFilterMeta(id);
    const jlpt = document.getElementById('filter-jlpt');
    const wt = document.getElementById('filter-word-type');
    const lesson = document.getElementById('filter-lesson');
    if (jlpt) jlpt.value = state.filters.jlpt || '';
    if (wt) wt.value = state.filters.wordType || '';
    if (lesson) lesson.value = state.filters.lesson || '';
    fillSetSelector(state.sets, id);
    render();
    maybeStartPendingReview();
  } catch {
    showToast('Lỗi tải bộ từ', 'err');
  }
}

/** Nhận danh sách id từ Quiz "Học lại (Flashcard)" */
function maybeStartPendingReview() {
  try {
    const raw = sessionStorage.getItem('jm-pending-study-ids');
    if (!raw || !state.activeSet) return;
    const ids = JSON.parse(raw);
    sessionStorage.removeItem('jm-pending-study-ids');
    sessionStorage.removeItem('jm-study-ids');
    if (!Array.isArray(ids) || !ids.length) return;
    state.selectedIds = new Set(ids.map(Number));
    startStudy(false);
  } catch {
    /* ignore */
  }
}

function visibleCards() {
  return filterCards(state.activeSet?.cards || [], state.filters);
}

function render() {
  document.body.classList.toggle('is-fullscreen', state.fullscreen);
  document.getElementById('study-layout').classList.toggle('is-ai-open', state.aiOpen);
  document.getElementById('ai-col').hidden = !state.aiOpen;

  const root = document.getElementById('page-root');
  if (!state.activeSet) return renderEmptySet(root);

  if (state.studyFinished) return renderSummary(root);
  if (state.isStudying) return renderStudy(root);
  return renderSetup(root);
}

function renderSetup(root) {
  const cards = visibleCards();
  const count = cards.filter(c => state.selectedIds.has(c.id)).length;
  root.innerHTML = `
    <div class="animate-up">
      <div class="card">
        <div class="controls-bar" style="margin-bottom:12px">
          <h2 class="section-title" style="margin:0">
            <i class="fa-solid fa-layer-group"></i> ${esc(state.activeSet.name)}
          </h2>
          <div style="display:flex;gap:8px;flex-wrap:wrap">
            <button class="btn btn-ghost btn-sm" id="sel-all"><i class="fa-solid fa-check-double"></i> Chọn tất cả</button>
            <button class="btn btn-ghost btn-sm" id="sel-none"><i class="fa-solid fa-xmark"></i> Bỏ chọn</button>
          </div>
        </div>
        <p class="muted" style="margin-bottom:12px">
          Đang hiển thị <strong>${cards.length}</strong> / ${state.activeSet.cards.length} thẻ theo bộ lọc.
        </p>
        <div class="card-list" id="card-list"></div>
      </div>
      <div class="card controls-bar">
        <div style="display:flex;gap:8px;flex-wrap:wrap" id="mode-btns">
          ${modeBtn('KANJI', 'Học Kanji')}
          ${modeBtn('HIRAGANA', 'Học Hiragana')}
          ${modeBtn('MEANING', 'Học Nghĩa')}
        </div>
        <button class="btn btn-primary btn-gold" id="start-study" ${count === 0 ? 'disabled' : ''}>
          <i class="fa-solid fa-play"></i> Bắt đầu học (${count})
        </button>
      </div>
    </div>
  `;

  renderCardList(root.querySelector('#card-list'), cards, state.selectedIds, (id) => {
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
  root.querySelectorAll('[data-mode]').forEach(btn => {
    btn.onclick = () => { state.mode = btn.dataset.mode; render(); };
  });
  root.querySelector('#start-study').onclick = () => startStudy(false);
}

function modeBtn(m, label) {
  return `<button type="button" class="btn ${state.mode === m ? 'btn-primary' : 'btn-ghost'} btn-sm" data-mode="${m}">${label}</button>`;
}

function startStudy(notRememberedOnly) {
  let pool = (state.activeSet?.cards || []).filter(c => state.selectedIds.has(c.id));
  if (notRememberedOnly) pool = pool.filter(c => c.memoryStatus === 'NOT_REMEMBERED');
  if (!pool.length) return showToast('Không có thẻ để học', 'err');

  state.studyCards = shuffle(pool);
  state.currentIndex = 0;
  state.isFlipped = false;
  state.isStudying = true;
  state.studyFinished = false;
  state.studyRemembered = 0;
  state.studyNotRemembered = 0;
  state.showExample = false;
  render();
  scheduleAutoFlip();
}

function renderStudy(root) {
  const card = state.studyCards[state.currentIndex];
  if (!card) return;
  const total = state.studyCards.length;
  const idx = state.currentIndex + 1;
  const pct = (idx / total) * 100;

  root.innerHTML = `
    <div class="animate-up">
      <div class="progress-meta">
        <span class="chip"><i class="fa-solid fa-layer-group"></i> ${idx} / ${total}</span>
        <span class="chip chip-ok"><i class="fa-solid fa-thumbs-up"></i> ${state.studyRemembered}</span>
        <span class="chip chip-bad"><i class="fa-solid fa-thumbs-down"></i> ${state.studyNotRemembered}</span>
        <span class="chip chip-star"><i class="fa-solid fa-star"></i> ${card.starred ? 'Đã sao' : 'Chưa sao'}</span>
      </div>
      <div class="progress-row">
        <div class="progress-bar"><div class="progress-fill" style="width:${pct}%"></div></div>
        <button class="btn-icon" id="btn-stop" title="Thoát" aria-label="Thoát"><i class="fa-solid fa-xmark"></i></button>
      </div>

      <div class="toolbar">
        <button class="btn-icon ${card.starred ? 'is-star' : ''}" id="btn-star" title="Sao (S)" aria-label="Sao">
          <i class="fa-${card.starred ? 'solid' : 'regular'} fa-star"></i>
        </button>
        <button class="btn-icon" id="btn-speak" title="Phát âm" aria-label="Phát âm">
          <i class="fa-solid fa-volume-high"></i>
        </button>
        <button class="btn-icon ${state.fullscreen ? 'is-on' : ''}" id="btn-fs" title="Toàn màn hình (F)" aria-label="Toàn màn hình">
          <i class="fa-solid fa-${state.fullscreen ? 'compress' : 'expand'}"></i>
        </button>
        <button class="btn-icon ${state.showExample ? 'is-on' : ''}" id="btn-ex" title="Ví dụ" aria-label="Ví dụ">
          <i class="fa-solid fa-quote-left"></i>
        </button>
        <button class="btn-icon ${state.aiOpen ? 'is-on' : ''}" id="btn-ai" title="Trợ lý AI" aria-label="Trợ lý AI">
          <i class="fa-solid fa-wand-magic-sparkles"></i>
        </button>
        <button class="btn btn-ghost btn-sm" id="btn-ai-ex"><i class="fa-solid fa-comments"></i> Ví dụ AI</button>
        <button class="btn btn-ghost btn-sm" id="btn-ai-kj"><i class="fa-solid fa-language"></i> Phân tích Kanji</button>
        <label class="inline-control">
          <i class="fa-solid fa-rotate"></i> Tự lật
          <select id="auto-flip" aria-label="Tự lật thẻ">
            <option value="0" ${state.autoFlipSec === 0 ? 'selected' : ''}>Tắt</option>
            <option value="2" ${state.autoFlipSec === 2 ? 'selected' : ''}>2 giây</option>
            <option value="3" ${state.autoFlipSec === 3 ? 'selected' : ''}>3 giây</option>
            <option value="5" ${state.autoFlipSec === 5 ? 'selected' : ''}>5 giây</option>
          </select>
        </label>
      </div>

      <div class="flashcard-scene" id="fc-scene">
        <div class="flashcard ${state.isFlipped ? 'is-flipped' : ''}" id="flashcard">
          <div class="flashcard-face flashcard-front">
            ${buildFront(card)}
            <div class="flip-hint"><i class="fa-solid fa-rotate"></i> Space để lật</div>
          </div>
          <div class="flashcard-face flashcard-back">
            ${buildBack(card)}
            <div class="flip-hint"><i class="fa-solid fa-rotate"></i> Space để lật lại</div>
          </div>
        </div>
      </div>

      <div id="example-area"></div>

      <div class="memory-row">
        <button class="btn btn-wrong" id="btn-not"><i class="fa-solid fa-thumbs-down"></i> Chưa nhớ <kbd>N</kbd></button>
        <button class="btn btn-correct" id="btn-yes"><i class="fa-solid fa-thumbs-up"></i> Đã nhớ <kbd>M</kbd></button>
      </div>

      <div class="actions">
        <button class="btn btn-ghost" id="btn-prev" ${state.currentIndex === 0 ? 'disabled' : ''}>
          <i class="fa-solid fa-arrow-left"></i> Trước
        </button>
        <button class="btn btn-primary" id="btn-flip">
          <i class="fa-solid fa-rotate"></i> ${state.isFlipped ? 'Ẩn đáp án' : 'Xem đáp án'}
        </button>
        <button class="btn btn-ghost" id="btn-next">
          ${state.currentIndex >= total - 1 ? 'Kết thúc' : 'Tiếp'}
        </button>
      </div>
    </div>
  `;

  if (state.showExample) {
    const jp = card.exampleJp || `今日は${card.kanji}ですね。`;
    const vi = card.exampleVi || `Hôm nay thật là ${card.meaning}.`;
    root.querySelector('#example-area').innerHTML = `
      <div class="example-box">
        <div class="label">Ví dụ</div>
        <div class="fc-meaning" style="font-family:var(--font-jp);font-size:1.05rem;color:var(--text-primary)">${esc(jp)}</div>
        ${state.filters.showFurigana && !state.filters.onlyKanji ? `<div class="fc-hiragana" style="font-size:1rem">${esc(card.hiragana)}</div>` : ''}
        <div class="muted" style="margin-top:6px"><strong>Dịch:</strong> ${esc(vi)}</div>
      </div>
    `;
  }

  root.querySelector('#btn-stop').onclick = stopStudy;
  root.querySelector('#btn-star').onclick = toggleStar;
  root.querySelector('#btn-speak').onclick = speakCard;
  root.querySelector('#btn-fs').onclick = toggleFullscreen;
  root.querySelector('#btn-ex').onclick = () => { state.showExample = !state.showExample; render(); };
  root.querySelector('#btn-ai').onclick = () => openAi('explain');
  root.querySelector('#btn-ai-ex').onclick = () => openAi('examples');
  root.querySelector('#btn-ai-kj').onclick = () => openAi('kanji');
  root.querySelector('#auto-flip').onchange = (e) => {
    state.autoFlipSec = parseInt(e.target.value, 10) || 0;
    scheduleAutoFlip();
  };
  root.querySelector('#btn-not').onclick = () => markMemory('NOT_REMEMBERED');
  root.querySelector('#btn-yes').onclick = () => markMemory('REMEMBERED');
  root.querySelector('#btn-prev').onclick = prevCard;
  root.querySelector('#btn-flip').onclick = toggleFlip;
  root.querySelector('#btn-next').onclick = nextCard;

  root.querySelector('#fc-scene').onclick = (e) => {
    if (e.target.closest('.fc-kanji-char') || e.target.closest('.fc-hanviet')) return;
    toggleFlip();
  };
  root.querySelectorAll('.fc-kanji-char').forEach(el => {
    el.onclick = (e) => { e.stopPropagation(); openKanjiGroup(el.dataset.char); };
  });
  const hv = root.querySelector('.fc-hanviet');
  if (hv) hv.onclick = (e) => { e.stopPropagation(); openHanVietGroup(hv.dataset.hv); };
}

function buildFront(card) {
  if (state.mode === 'MEANING') {
    return `<div class="fc-meaning" style="font-size:1.4rem">${esc(card.meaning)}</div>
      ${card.hanViet ? `<div class="fc-hanviet" data-hv="${esc(card.hanViet)}">${esc(card.hanViet)}</div>` : ''}`;
  }
  if (state.mode === 'HIRAGANA' && !state.filters.onlyKanji) {
    return `<div class="fc-kanji">${esc(card.hiragana)}</div>`;
  }
  const kanjiHtml = [...(card.kanji || '')].map(ch => {
    if (/\p{Script=Han}/u.test(ch)) {
      return `<span class="fc-kanji-char" data-char="${esc(ch)}">${esc(ch)}</span>`;
    }
    return esc(ch);
  }).join('');
  return `<div class="fc-kanji">${kanjiHtml}</div>`;
}

function buildBack(card) {
  if (state.filters.onlyKanji) {
    return `<div class="fc-meaning">${esc(card.meaning)}</div>
      ${card.hanViet ? `<div class="fc-hanviet" data-hv="${esc(card.hanViet)}">${esc(card.hanViet)}</div>` : ''}`;
  }
  if (state.mode === 'MEANING') {
    return `<div class="fc-kanji" style="font-size:2.4rem">${esc(card.kanji)}</div>
      ${state.filters.showFurigana ? `<div class="fc-hiragana">${esc(card.hiragana)}</div>` : ''}`;
  }
  if (state.mode === 'HIRAGANA') {
    return `<div class="fc-kanji" style="font-size:2.2rem">${esc(card.kanji)}</div>
      <div class="fc-meaning">${esc(card.meaning)}</div>
      ${card.hanViet ? `<div class="fc-hanviet" data-hv="${esc(card.hanViet)}">${esc(card.hanViet)}</div>` : ''}`;
  }
  return `${state.filters.showFurigana ? `<div class="fc-hiragana">${esc(card.hiragana)}</div>` : ''}
    <div class="fc-meaning">${esc(card.meaning)}</div>
    ${card.hanViet ? `<div class="fc-hanviet" data-hv="${esc(card.hanViet)}">${esc(card.hanViet)}</div>` : ''}`;
}

function renderSummary(root) {
  root.innerHTML = `
    <div class="card animate-up" style="text-align:center">
      <h2 class="section-title" style="justify-content:center">
        <i class="fa-solid fa-flag-checkered"></i> Kết thúc buổi học
      </h2>
      <div class="stats-grid">
        <div class="stat-card" style="border-top:3px solid var(--correct)">
          <div class="stat-val" style="color:var(--correct)">${state.studyRemembered}</div>
          <div class="stat-label">Đã nhớ</div>
        </div>
        <div class="stat-card" style="border-top:3px solid var(--wrong)">
          <div class="stat-val" style="color:var(--wrong)">${state.studyNotRemembered}</div>
          <div class="stat-label">Chưa nhớ</div>
        </div>
        <div class="stat-card" style="border-top:3px solid var(--accent)">
          <div class="stat-val" style="color:var(--accent)">${state.studyCards.length}</div>
          <div class="stat-label">Tổng thẻ</div>
        </div>
      </div>
      <div style="display:flex;flex-wrap:wrap;gap:10px;justify-content:center;margin-top:24px">
        <button class="btn btn-primary" id="retry-nr" ${state.studyNotRemembered === 0 ? 'disabled' : ''}>
          <i class="fa-solid fa-rotate-left"></i> Chỉ học lại "Chưa nhớ"
        </button>
        <button class="btn btn-ghost" id="reset-mem">
          <i class="fa-solid fa-eraser"></i> Đặt lại trạng thái học
        </button>
        <button class="btn btn-ghost" id="back-list">
          <i class="fa-solid fa-house"></i> Về danh sách
        </button>
      </div>
    </div>
  `;
  root.querySelector('#retry-nr').onclick = async () => {
    await refreshSet();
    state.studyFinished = false;
    startStudy(true);
  };
  root.querySelector('#reset-mem').onclick = async () => {
    try {
      await api.post(`/vocab/sets/${state.activeSet.id}/reset-memory`);
      await refreshSet();
      state.studyRemembered = 0;
      state.studyNotRemembered = 0;
      showToast('Đã đặt lại trạng thái học', 'ok');
      render();
    } catch {
      showToast('Không đặt lại được', 'err');
    }
  };
  root.querySelector('#back-list').onclick = stopStudy;
}

async function refreshSet() {
  if (!state.activeSet) return;
  state.activeSet = await loadSet(state.activeSet.id);
}

function stopStudy() {
  stopAutoFlip();
  state.isStudying = false;
  state.studyFinished = false;
  state.fullscreen = false;
  document.body.classList.remove('is-fullscreen');
  document.exitFullscreen?.().catch(() => {});
  render();
}

function toggleFlip() {
  if (!state.isStudying || state.studyFinished) return;
  state.isFlipped = !state.isFlipped;
  const el = document.getElementById('flashcard');
  if (el) el.classList.toggle('is-flipped', state.isFlipped);
  else render();
  scheduleAutoFlip();
}

function nextCard() {
  if (!state.isStudying) return;
  if (state.currentIndex >= state.studyCards.length - 1) {
    stopAutoFlip();
    state.studyFinished = true;
    render();
    return;
  }
  state.currentIndex++;
  state.isFlipped = false;
  state.showExample = false;
  render();
  scheduleAutoFlip();
}

function prevCard() {
  if (!state.isStudying || state.currentIndex <= 0) return;
  state.currentIndex--;
  state.isFlipped = false;
  state.showExample = false;
  render();
  scheduleAutoFlip();
}

function currentCard() {
  if (!state.isStudying || state.studyFinished) return null;
  return state.studyCards[state.currentIndex] || null;
}

function mergeCard(updated) {
  if (!updated?.id) return;
  const apply = (list) => {
    const i = list.findIndex(c => c.id === updated.id);
    if (i >= 0) list[i] = { ...list[i], ...updated };
  };
  if (state.activeSet?.cards) apply(state.activeSet.cards);
  apply(state.studyCards);
}

async function toggleStar() {
  const card = currentCard();
  if (!card) return;
  try {
    const updated = await api.patch(`/vocab/cards/${card.id}/progress`, { starred: !card.starred });
    mergeCard(updated);
    showToast(updated.starred ? 'Đã đánh dấu sao' : 'Đã bỏ sao', 'ok');
    render();
  } catch {
    showToast('Không lưu được sao', 'err');
  }
}

async function markMemory(status) {
  const card = currentCard();
  if (!card) return;
  const prev = card.memoryStatus;
  try {
    const updated = await api.patch(`/vocab/cards/${card.id}/progress`, { memoryStatus: status });
    mergeCard(updated);
    if (status === 'REMEMBERED' && prev !== 'REMEMBERED') state.studyRemembered++;
    if (status === 'NOT_REMEMBERED' && prev !== 'NOT_REMEMBERED') state.studyNotRemembered++;
    if (status === 'REMEMBERED' && prev === 'NOT_REMEMBERED') {
      state.studyNotRemembered = Math.max(0, state.studyNotRemembered - 1);
    }
    if (status === 'NOT_REMEMBERED' && prev === 'REMEMBERED') {
      state.studyRemembered = Math.max(0, state.studyRemembered - 1);
    }
    showToast(status === 'REMEMBERED' ? 'Đã nhớ' : 'Chưa nhớ', status === 'REMEMBERED' ? 'ok' : 'err');
    setTimeout(() => nextCard(), 280);
  } catch {
    showToast('Không lưu trạng thái', 'err');
  }
}

function speakCard() {
  const card = currentCard();
  if (!card) return;
  if (!window.speechSynthesis) return showToast('Trình duyệt không hỗ trợ phát âm', 'err');
  window.speechSynthesis.cancel();
  const u = new SpeechSynthesisUtterance(card.hiragana || card.kanji);
  const ja = window.speechSynthesis.getVoices().find(v => v.lang?.startsWith('ja'));
  if (ja) u.voice = ja;
  u.lang = 'ja-JP';
  u.rate = 0.9;
  window.speechSynthesis.speak(u);
}

function toggleFullscreen() {
  state.fullscreen = !state.fullscreen;
  if (state.fullscreen) document.documentElement.requestFullscreen?.().catch(() => {});
  else document.exitFullscreen?.().catch(() => {});
  render();
}

function scheduleAutoFlip() {
  stopAutoFlip();
  if (!state.autoFlipSec || !state.isStudying || state.studyFinished) return;
  state.autoFlipTimer = setTimeout(() => {
    if (!state.isFlipped) {
      toggleFlip();
      state.autoFlipTimer = setTimeout(() => nextCard(), state.autoFlipSec * 1000);
    } else {
      nextCard();
    }
  }, state.autoFlipSec * 1000);
}

function stopAutoFlip() {
  if (state.autoFlipTimer) {
    clearTimeout(state.autoFlipTimer);
    state.autoFlipTimer = null;
  }
}

function softUpdateStudyCard() {
  render();
}

function onKeydown(e) {
  const tag = (e.target.tagName || '').toLowerCase();
  if (tag === 'input' || tag === 'textarea' || tag === 'select') return;
  if (!state.isStudying || state.studyFinished) return;
  if (e.code === 'Space') { e.preventDefault(); toggleFlip(); }
  if (e.code === 'ArrowRight') { e.preventDefault(); nextCard(); }
  if (e.code === 'ArrowLeft') { e.preventDefault(); prevCard(); }
  if (e.code === 'KeyF') { e.preventDefault(); toggleFullscreen(); }
  if (e.code === 'KeyS') { e.preventDefault(); toggleStar(); }
  if (e.code === 'KeyM') { e.preventDefault(); markMemory('REMEMBERED'); }
  if (e.code === 'KeyN') { e.preventDefault(); markMemory('NOT_REMEMBERED'); }
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
        context: card ? `${card.kanji} ${card.hiragana} ${card.meaning}` : null
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
  const card = currentCard();
  if (!card) return showToast('Chưa có thẻ để hỏi AI', 'err');
  state.aiCard = card;
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

/* Group modal */
function bindGroupModal() {
  const modal = document.getElementById('group-modal');
  document.getElementById('btn-close-group').onclick = () => { modal.hidden = true; };
  document.getElementById('btn-cancel-group').onclick = () => { modal.hidden = true; };
  document.getElementById('btn-study-group').onclick = () => {
    if (!state.groupCards.length) return;
    state.selectedIds = new Set(state.groupCards.map(c => c.id));
    const existing = new Map((state.activeSet.cards || []).map(c => [c.id, c]));
    state.groupCards.forEach(c => { if (!existing.has(c.id)) state.activeSet.cards.push(c); });
    modal.hidden = true;
    state.studyFinished = false;
    startStudy(false);
  };
}

async function openKanjiGroup(ch) {
  if (!state.activeSet || !ch) return;
  try {
    const cards = await api.get(`/vocab/sets/${state.activeSet.id}/by-kanji?q=${encodeURIComponent(ch)}`);
    openGroup(`Từ chứa kanji「${ch}」`, cards);
  } catch {
    showToast('Không tải nhóm kanji', 'err');
  }
}

async function openHanVietGroup(hv) {
  if (!state.activeSet || !hv) return;
  const token = hv.trim().split(/\s+/)[0];
  try {
    const cards = await api.get(`/vocab/sets/${state.activeSet.id}/by-hanviet?q=${encodeURIComponent(token)}`);
    openGroup(`Từ có Hán Việt「${token}」`, cards);
  } catch {
    showToast('Không tải nhóm Hán Việt', 'err');
  }
}

function openGroup(title, cards) {
  state.groupCards = cards || [];
  document.getElementById('group-modal-title').textContent = title;
  const body = document.getElementById('group-modal-body');
  if (!state.groupCards.length) {
    body.innerHTML = '<p class="muted">Không tìm thấy từ liên quan.</p>';
  } else {
    body.innerHTML = state.groupCards.map(c => `
      <div class="card-item" style="cursor:default">
        <div style="flex:1">
          <div style="font-family:var(--font-jp);font-weight:700">${esc(c.kanji)}</div>
          <div class="muted" style="font-size:.85rem">${esc(c.hiragana)} — ${esc(c.meaning)}</div>
        </div>
        ${c.hanViet ? `<span class="tag">${esc(c.hanViet)}</span>` : ''}
      </div>
    `).join('');
  }
  document.getElementById('group-modal').hidden = false;
}

if (typeof speechSynthesis !== 'undefined') {
  speechSynthesis.onvoiceschanged = () => speechSynthesis.getVoices();
}
