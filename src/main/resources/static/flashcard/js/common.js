/* Shared utilities — 日本語 Mastery */
const API = '/api';
const STORAGE_SET = 'jm-active-set-id';
const STORAGE_THEME = 'jm-theme';
const STORAGE_PREFS = 'jm-prefs';

function authHeaders() {
  if (window.JlasAuth && typeof window.JlasAuth.authHeaders === 'function') {
    return window.JlasAuth.authHeaders();
  }
  const headers = { 'Content-Type': 'application/json' };
  const token = localStorage.getItem('accessToken');
  if (token) headers.Authorization = `Bearer ${token}`;
  return headers;
}

const api = {
  async get(path) {
    const r = await fetch(API + path, { headers: authHeaders() });
    if (r.status === 204) return null;
    handleUnauthorized(r);
    return parseApiResponse(r);
  },
  async post(path, body) {
    const r = await fetch(API + path, {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify(body ?? {})
    });
    handleUnauthorized(r);
    return parseApiResponse(r);
  },
  async patch(path, body) {
    const r = await fetch(API + path, {
      method: 'PATCH',
      headers: authHeaders(),
      body: JSON.stringify(body ?? {})
    });
    handleUnauthorized(r);
    return parseApiResponse(r);
  }
};

async function parseApiResponse(response) {
  const text = await response.text();
  let body = null;
  try { body = text ? JSON.parse(text) : null; } catch { body = null; }
  if (!response.ok) {
    const message = body?.message || body?.error || text || response.statusText || 'Request failed';
    const error = new Error(message);
    error.status = response.status;
    error.body = body;
    throw error;
  }
  return body;
}

function handleUnauthorized(response) {
  if (response.status !== 401) return;
  if (window.JlasAuth && typeof window.JlasAuth.clearSession === 'function') {
    window.JlasAuth.clearSession();
  }
  const target = `${location.pathname}${location.search}`;
  if (!/^\/login(?:\.html)?\/?$/i.test(location.pathname)) {
    location.replace(`/login?redirect=${encodeURIComponent(target)}`);
  }
}

function esc(s) {
  return String(s ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function showToast(msg, type = 'info') {
  const toast = document.getElementById('toast');
  if (!toast) return;
  const icon = type === 'ok' || type === 'correct' ? 'fa-circle-check'
    : type === 'err' || type === 'wrong' ? 'fa-circle-xmark' : 'fa-circle-info';
  const cls = type === 'ok' || type === 'correct' ? 'is-ok'
    : type === 'err' || type === 'wrong' ? 'is-err' : '';
  toast.innerHTML = `<i class="fa-solid ${icon}"></i> ${esc(msg)}`;
  toast.className = `toast is-show ${cls}`;
  clearTimeout(toast._timer);
  toast._timer = setTimeout(() => toast.classList.remove('is-show'), 2800);
}

function getTheme() {
  return localStorage.getItem(STORAGE_THEME) || 'light';
}

function applyTheme(theme) {
  document.documentElement.setAttribute('data-theme', theme);
  localStorage.setItem(STORAGE_THEME, theme);
  const icon = document.querySelector('#btn-theme i');
  if (icon) icon.className = theme === 'dark' ? 'fa-solid fa-sun' : 'fa-solid fa-moon';
}

function toggleTheme() {
  applyTheme(getTheme() === 'dark' ? 'light' : 'dark');
}

function getPrefs() {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_PREFS) || '{}');
  } catch {
    return {};
  }
}

function savePrefs(partial) {
  const next = { ...getPrefs(), ...partial };
  localStorage.setItem(STORAGE_PREFS, JSON.stringify(next));
  return next;
}

function getSavedSetId() {
  const id = localStorage.getItem(STORAGE_SET);
  return id ? Number(id) : null;
}

function saveSetId(id) {
  if (id == null) localStorage.removeItem(STORAGE_SET);
  else localStorage.setItem(STORAGE_SET, String(id));
}

async function loadSets() {
  return api.get('/vocab/sets');
}

async function loadSet(id) {
  return api.get(`/vocab/sets/${id}`);
}

function fillSetSelector(sets, activeId) {
  const select = document.getElementById('set-selector');
  if (!select) return;
  if (!sets?.length) {
    select.style.display = 'none';
    return;
  }
  select.style.display = 'block';
  select.innerHTML = '<option value="">Chọn bộ từ...</option>' +
    sets.map(s =>
      `<option value="${s.id}" ${Number(activeId) === s.id ? 'selected' : ''}>${esc(s.name)} (${s.cardCount})</option>`
    ).join('');
}

function bindHeaderChrome({ page, onSetChange } = {}) {
  applyTheme(getTheme());

  const themeBtn = document.getElementById('btn-theme');
  if (themeBtn) themeBtn.onclick = toggleTheme;

  const helpBtn = document.getElementById('btn-help');
  const helpModal = document.getElementById('help-modal');
  if (helpBtn && helpModal) {
    helpBtn.onclick = () => { helpModal.hidden = false; };
    helpModal.querySelectorAll('[data-close-help]').forEach(b => {
      b.onclick = () => { helpModal.hidden = true; };
    });
  }

  const select = document.getElementById('set-selector');
  if (select && onSetChange) {
    select.onchange = () => {
      const id = select.value ? Number(select.value) : null;
      saveSetId(id);
      onSetChange(id);
    };
  }
}

function shuffle(arr) {
  const a = [...arr];
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}

function formatDuration(sec) {
  const m = Math.floor((sec || 0) / 60);
  const s = (sec || 0) % 60;
  return m > 0 ? `${m}m ${s}s` : `${s}s`;
}

function filterCards(cards, filters) {
  return (cards || []).filter(c => {
    if (filters.starredOnly && !c.starred) return false;
    if (filters.notRememberedOnly && c.memoryStatus !== 'NOT_REMEMBERED') return false;
    if (filters.jlpt && (c.jlptLevel || '').toUpperCase() !== filters.jlpt) return false;
    if (filters.wordType && (c.wordType || '').toUpperCase() !== filters.wordType) return false;
    if (filters.lesson && (c.lesson || '') !== filters.lesson) return false;
    return true;
  });
}

function renderCardList(listEl, cards, selectedIds, onToggle) {
  listEl.innerHTML = '';
  if (!cards.length) {
    listEl.innerHTML = '<p class="muted">Không có thẻ nào khớp bộ lọc.</p>';
    return;
  }
  cards.forEach(c => {
    const item = document.createElement('div');
    item.className = `card-item ${selectedIds.has(c.id) ? 'is-selected' : ''}`;
    item.innerHTML = `
      <div class="checkbox"><i class="fa-solid fa-check"></i></div>
      <div style="flex:1;min-width:0">
        <div style="font-family:var(--font-jp);font-weight:700">${esc(c.kanji)}</div>
        <div style="font-size:.8rem;color:var(--text-muted)">${esc(c.hiragana)}</div>
      </div>
      <div style="font-size:.88rem;color:var(--text-secondary);text-align:right">
        ${esc(c.meaning)}
        <div style="display:flex;gap:4px;justify-content:flex-end;margin-top:4px;flex-wrap:wrap">
          ${c.starred ? '<span class="tag tag-star"><i class="fa-solid fa-star"></i></span>' : ''}
          ${c.memoryStatus === 'REMEMBERED' ? '<span class="tag tag-green">Nhớ</span>' : ''}
          ${c.memoryStatus === 'NOT_REMEMBERED' ? '<span class="tag tag-red">Chưa</span>' : ''}
          ${c.jlptLevel ? `<span class="tag">${esc(c.jlptLevel)}</span>` : ''}
        </div>
      </div>
    `;
    item.onclick = () => onToggle(c.id);
    listEl.appendChild(item);
  });
}

function bindFilterSidebar(filters, onChange) {
  const map = {
    'toggle-furigana': (el) => { filters.showFurigana = el.checked; if (el.checked) filters.onlyKanji = false; },
    'toggle-only-kanji': (el) => { filters.onlyKanji = el.checked; if (el.checked) filters.showFurigana = false; },
    'toggle-starred-filter': (el) => { filters.starredOnly = el.checked; },
    'toggle-not-remembered': (el) => { filters.notRememberedOnly = el.checked; }
  };

  Object.entries(map).forEach(([id, fn]) => {
    const el = document.getElementById(id);
    if (!el) return;
    if (id === 'toggle-furigana') el.checked = filters.showFurigana !== false;
    if (id === 'toggle-only-kanji') el.checked = !!filters.onlyKanji;
    if (id === 'toggle-starred-filter') el.checked = !!filters.starredOnly;
    if (id === 'toggle-not-remembered') el.checked = !!filters.notRememberedOnly;
    el.onchange = () => {
      fn(el);
      if (id === 'toggle-furigana' || id === 'toggle-only-kanji') {
        const f = document.getElementById('toggle-furigana');
        const k = document.getElementById('toggle-only-kanji');
        if (f) f.checked = filters.showFurigana !== false;
        if (k) k.checked = !!filters.onlyKanji;
        savePrefs({ showFurigana: filters.showFurigana, onlyKanji: filters.onlyKanji });
      }
      onChange();
    };
  });

  const apply = document.getElementById('btn-apply-filter');
  if (apply) {
    apply.onclick = () => {
      filters.jlpt = document.getElementById('filter-jlpt')?.value || '';
      filters.wordType = document.getElementById('filter-word-type')?.value || '';
      filters.lesson = document.getElementById('filter-lesson')?.value || '';
      savePrefs({
        jlpt: filters.jlpt,
        wordType: filters.wordType,
        lesson: filters.lesson,
        starredOnly: filters.starredOnly,
        notRememberedOnly: filters.notRememberedOnly
      });
      showToast('Đã áp dụng bộ lọc', 'ok');
      onChange();
    };
  }
}

async function loadFilterMeta(setId) {
  try {
    const meta = await api.get(`/vocab/sets/${setId}/filter-meta`);
    const lessonSel = document.getElementById('filter-lesson');
    if (lessonSel) {
      const lessons = meta.lessons || [];
      lessonSel.innerHTML = '<option value="">Tất cả</option>' +
        lessons.map(l => `<option value="${esc(l)}">${esc(l)}</option>`).join('');
    }
    return meta;
  } catch {
    return { lessons: [] };
  }
}

function renderEmptySet(container) {
  container.innerHTML = `
    <div class="card empty-state animate-up">
      <i class="fa-solid fa-book-open" style="font-size:3.5rem;opacity:.15;display:block;margin-bottom:16px"></i>
      <h3 style="margin-bottom:8px">Chưa chọn bộ từ</h3>
      <p class="muted">Chọn bộ từ ở góc phải header hoặc tạo bộ mới trong thư viện.</p>
      <a class="btn btn-primary" style="margin-top:20px" href="/flashcard/library.html">
        <i class="fa-solid fa-books"></i> Mở thư viện thẻ
      </a>
    </div>
  `;
}

function renderAiResponseEl(res) {
  const el = document.createElement('div');
  el.className = 'ai-bubble';
  let html = '';
  if (res.term) {
    html += `<div class="ai-term">${esc(res.term)}${res.reading ? ` <span class="fc-hiragana" style="font-size:1rem">（${esc(res.reading)}）</span>` : ''}</div>`;
  }
  if (res.meaning) html += `<div class="meta-line"><strong>Nghĩa:</strong> ${esc(res.meaning)}</div>`;
  if (res.hanViet) html += `<div class="meta-line"><strong>Hán Việt:</strong> ${esc(res.hanViet)}</div>`;
  if (res.jlptLevel) html += `<div class="meta-line"><strong>JLPT:</strong> ${esc(res.jlptLevel)}</div>`;
  if (res.usage) html += `<div class="meta-line">${esc(res.usage)}</div>`;
  if (res.tip) html += `<div class="tip-box"><i class="fa-solid fa-lightbulb"></i> ${esc(res.tip)}</div>`;
  if (res.reply) html += `<div class="meta-line" style="white-space:pre-wrap">${esc(res.reply)}</div>`;

  if (res.kanjiParts?.length) {
    html += '<div class="kanji-parts">';
    res.kanjiParts.forEach(p => {
      html += `<div class="kanji-part">
        <div class="ch">${esc(p.character || '')}</div>
        <div>
          <div><strong>${esc(p.hanViet || '')}</strong> — ${esc(p.meaning || '')}</div>
          <div class="muted" style="font-size:.82rem">音: ${esc(p.onyomi || '—')} · 訓: ${esc(p.kunyomi || '—')}</div>
        </div>
      </div>`;
    });
    html += '</div>';
  }

  if (res.examples?.length) {
    html += '<div class="ex-group"><h4>Ví dụ</h4>';
    res.examples.forEach(ex => {
      html += `<div class="ex-item">
        <div class="jp">${esc(ex.japanese || '')}</div>
        ${ex.reading ? `<div class="rd">${esc(ex.reading)}</div>` : ''}
        <div class="vi">${esc(ex.vietnamese || '')}</div>
      </div>`;
    });
    html += '</div>';
  }

  if (res.exampleGroups?.length) {
    res.exampleGroups.forEach(g => {
      html += `<div class="ex-group"><h4>${esc(g.level || '')}</h4>`;
      (g.examples || []).forEach(ex => {
        html += `<div class="ex-item">
          <div class="jp">${esc(ex.japanese || '')}</div>
          ${ex.reading ? `<div class="rd">${esc(ex.reading)}</div>` : ''}
          <div class="vi">${esc(ex.vietnamese || '')}</div>
          ${ex.explanation ? `<div class="muted" style="font-size:.82rem;margin-top:4px">${esc(ex.explanation)}</div>` : ''}
        </div>`;
      });
      html += '</div>';
    });
  }

  if (res.error) {
    html += `<div class="ai-error"><i class="fa-solid fa-triangle-exclamation"></i> ${esc(res.error)}</div>`;
  }

  el.innerHTML = html || '<div class="muted">Không có nội dung.</div>';
  return el;
}

window.JM = {
  api, esc, showToast, applyTheme, toggleTheme, getTheme,
  getPrefs, savePrefs, getSavedSetId, saveSetId,
  loadSets, loadSet, fillSetSelector, bindHeaderChrome,
  shuffle, formatDuration, filterCards, renderCardList,
  bindFilterSidebar, loadFilterMeta, renderEmptySet, renderAiResponseEl
};
