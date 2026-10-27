(function (root) {
  'use strict';
  const modes = [
    { value: 'ALL', label: 'Tất cả thẻ hợp lệ' },
    { value: 'LEARNING', label: 'Thẻ mới / chưa nhớ' },
    { value: 'CUSTOM', label: 'Chọn cặp từ tùy ý' },
    { value: 'RANDOM', label: 'Ngẫu nhiên' }
  ];
  const PAGE_SIZE = 10;
  // Callers re-render the whole table after each checkbox; keep the page while the card list is unchanged.
  let remembered = { key: '', page: 1 };
  const esc = value => String(value ?? '').replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]));
  function pool(cards, mode, eligible, learning, selected) {
    return cards.filter(card => eligible(card) && (mode !== 'LEARNING' || learning(card))
      && (mode !== 'CUSTOM' || selected.has(card.id)));
  }
  function sample(cards, count, random = Math.random) {
    const result = cards.slice();
    for (let i = result.length - 1; i > 0; i--) {
      const j = Math.floor(random() * (i + 1));
      [result[i], result[j]] = [result[j], result[i]];
    }
    return result.slice(0, Math.max(0, Math.min(cards.length, count)));
  }
  function pageLabel(page, pages, total) {
    const first = (page - 1) * PAGE_SIZE + 1, last = Math.min(total, page * PAGE_SIZE);
    return `Trang ${page}/${pages} · thẻ ${first}–${last}`;
  }
  function pager(page, pages, total) {
    if (pages < 2) return '';
    return `<nav class="fc-selection__pager" aria-label="Phân trang danh sách thẻ">
        <button type="button" class="fc-selection__page-btn" data-selection-page-step="-1" aria-label="Trang trước" ${page === 1 ? 'disabled' : ''}><i class="fa-solid fa-chevron-left" aria-hidden="true"></i></button>
        <span class="fc-selection__page-label" data-selection-page-label aria-live="polite">${pageLabel(page, pages, total)}</span>
        <button type="button" class="fc-selection__page-btn" data-selection-page-step="1" aria-label="Trang sau" ${page === pages ? 'disabled' : ''}><i class="fa-solid fa-chevron-right" aria-hidden="true"></i></button>
      </nav>`;
  }
  function table({ cards, mode, selected, eligible, questionLabel, answerLabel, question, answer, reason }) {
    const custom = mode === 'CUSTOM';
    const count = cards.filter(card => eligible(card) && selected.has(card.id)).length;
    const pages = Math.max(1, Math.ceil(cards.length / PAGE_SIZE));
    const key = mode + ':' + cards.map(card => card.id).join(',');
    const page = remembered.key === key ? Math.min(remembered.page, pages) : 1;
    remembered = { key, page };
    return `<section class="fc-selection" aria-label="Danh sách thẻ của chế độ" data-selection-key="${esc(key)}">
      <div class="fc-selection__heading"><strong>${custom ? 'Chọn cặp từ' : 'Thẻ trong lượt học'}</strong>
        <span aria-live="polite">${custom ? `${count}/${cards.length} thẻ đã chọn` : `${cards.length} thẻ`}</span>
        ${mode === 'RANDOM' ? '<button type="button" class="fc-selection__shuffle" data-selection-shuffle><i class="fa-solid fa-shuffle" aria-hidden="true"></i> Bốc lại</button>' : ''}</div>
      ${cards.length ? `<table class="fc-selection__table"><thead><tr><th scope="col" aria-label="Chọn"></th><th scope="col">#</th><th scope="col">${esc(questionLabel)}</th><th scope="col">${esc(answerLabel)}</th></tr></thead>
        <tbody>${cards.map((card, index) => {
          const rowPage = Math.floor(index / PAGE_SIZE) + 1;
          return `<tr class="${eligible(card) ? '' : 'is-unavailable'}" data-selection-row-page="${rowPage}"${rowPage === page ? '' : ' hidden'}>
          <td><input type="checkbox" data-selection-card="${esc(card.id)}" ${eligible(card) && selected.has(card.id) ? 'checked' : ''} ${custom && eligible(card) ? '' : 'disabled'} aria-label="Chọn ${esc(question(card) || answer(card) || 'thẻ ' + (index + 1))}"></td>
          <th scope="row">${index + 1}</th><td>${esc(question(card))}${eligible(card) ? '' : `<small>${esc(reason(card))}</small>`}</td><td>${esc(answer(card))}</td>
        </tr>`;
        }).join('')}</tbody></table>${pager(page, pages, cards.length)}` : '<p class="fc-selection__empty" role="status">Không có thẻ hợp lệ trong chế độ này. Hãy đổi chế độ hoặc cặp hỏi–đáp.</p>'}
    </section>`;
  }
  /* Shows another page of a rendered table in place (no re-render, so checkbox state is untouched). */
  function turnPage(section, step) {
    const rows = Array.from(section.querySelectorAll('[data-selection-row-page]'));
    const pages = Math.max(1, ...rows.map(row => Number(row.dataset.selectionRowPage)));
    const current = Number((rows.find(row => !row.hidden) || rows[0])?.dataset.selectionRowPage || 1);
    const page = Math.max(1, Math.min(pages, current + step));
    rows.forEach(row => { row.hidden = Number(row.dataset.selectionRowPage) !== page; });
    section.querySelectorAll('[data-selection-page-step]').forEach(button => {
      button.disabled = Number(button.dataset.selectionPageStep) < 0 ? page === 1 : page === pages;
    });
    const label = section.querySelector('[data-selection-page-label]');
    if (label) label.textContent = pageLabel(page, pages, rows.length);
    if (remembered.key === section.dataset.selectionKey) remembered.page = page;
    return page;
  }
  if (root.document && !root.__flashcardSelectionPager) {
    root.__flashcardSelectionPager = true;
    root.document.addEventListener('click', event => {
      const button = event.target.closest && event.target.closest('[data-selection-page-step]');
      if (!button || button.disabled) return;
      turnPage(button.closest('.fc-selection'), Number(button.dataset.selectionPageStep));
    });
  }
  const api = { modes, pool, sample, table, turnPage, PAGE_SIZE };
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.FlashcardSelection = api;
})(typeof window !== 'undefined' ? window : globalThis);
