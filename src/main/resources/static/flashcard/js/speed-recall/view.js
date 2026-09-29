export const $ = id => document.getElementById(id);
export function text(id, value) {
  const element = $(id);
  if (element) element.textContent = value ?? '';
}
export function show(id, visible) { $(id).hidden = !visible; }
export function slots(container, values) {
  container.replaceChildren();
  container.classList.toggle('is-free', !!values.free);
  if (values.free) {
    const box = document.createElement('span');
    box.className = 'sr-free-answer' + (values.text ? '' : ' is-empty');
    box.textContent = values.text || 'Gõ đáp án…';
    container.append(box);
    return;
  }
  for (const value of values) {
    const slot = document.createElement('span');
    slot.className = 'sr-slot is-' + value.status;
    const char = document.createElement('span');
    char.textContent = value.char || '＿'; char.lang = 'ja';
    const mark = document.createElement('small');
    mark.textContent = value.status === 'correct' ? '✓' : value.status === 'wrong' ? '×' : '';
    mark.setAttribute('aria-hidden', 'true');
    slot.append(char, mark); container.append(slot);
  }
}
export function review(container, results) {
  container.replaceChildren();
  for (const item of results) {
    const row = document.createElement('li');
    const word = document.createElement('strong'); word.lang = 'ja'; word.textContent = item.question;
    const reading = document.createElement('span'); reading.textContent = item.expectedAnswer || item.reading;
    const meaning = document.createElement('span'); meaning.textContent = item.meaning || '';
    const answer = document.createElement('p');
    answer.textContent = (item.result === 'TIMEOUT' ? 'Hết giờ' : 'Chưa đúng') +
      ' · Bạn nhập: ' + (item.userAnswer || '(trống)') + ' · ' + (item.responseMs / 1000).toFixed(1) + ' giây';
    row.append(word, reading, meaning, answer); container.append(row);
  }
}
