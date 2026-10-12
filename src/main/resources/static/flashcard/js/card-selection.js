(function (root) {
  'use strict';
  const modes = [
    { value: 'ALL', label: 'Tất cả thẻ hợp lệ' },
    { value: 'LEARNING', label: 'Thẻ mới / chưa nhớ' },
    { value: 'CUSTOM', label: 'Chọn cặp từ tùy ý' },
    { value: 'RANDOM', label: 'Ngẫu nhiên' }
  ];
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
  function table({ cards, mode, selected, eligible, questionLabel, answerLabel, question, answer, reason }) {
    const custom = mode === 'CUSTOM';
    const count = cards.filter(card => eligible(card) && selected.has(card.id)).length;
    return `<section class="fc-selection" aria-label="Danh sách thẻ của chế độ">
      <div class="fc-selection__heading"><strong>${custom ? 'Chọn cặp từ' : 'Thẻ trong lượt học'}</strong>
        <span aria-live="polite">${custom ? `${count}/${cards.length} thẻ đã chọn` : `${cards.length} thẻ`}</span>
        ${mode === 'RANDOM' ? '<button type="button" class="fc-selection__shuffle" data-selection-shuffle><i class="fa-solid fa-shuffle" aria-hidden="true"></i> Bốc lại</button>' : ''}</div>
      ${cards.length ? `<table class="fc-selection__table"><thead><tr><th scope="col" aria-label="Chọn"></th><th scope="col">#</th><th scope="col">${esc(questionLabel)}</th><th scope="col">${esc(answerLabel)}</th></tr></thead>
        <tbody>${cards.map((card, index) => `<tr class="${eligible(card) ? '' : 'is-unavailable'}">
          <td><input type="checkbox" data-selection-card="${esc(card.id)}" ${eligible(card) && selected.has(card.id) ? 'checked' : ''} ${custom && eligible(card) ? '' : 'disabled'} aria-label="Chọn ${esc(question(card) || answer(card) || 'thẻ ' + (index + 1))}"></td>
          <th scope="row">${index + 1}</th><td>${esc(question(card))}${eligible(card) ? '' : `<small>${esc(reason(card))}</small>`}</td><td>${esc(answer(card))}</td>
        </tr>`).join('')}</tbody></table>` : '<p class="fc-selection__empty" role="status">Không có thẻ hợp lệ trong chế độ này. Hãy đổi chế độ hoặc cặp hỏi–đáp.</p>'}
    </section>`;
  }
  const api = { modes, pool, sample, table };
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.FlashcardSelection = api;
})(typeof window !== 'undefined' ? window : globalThis);
