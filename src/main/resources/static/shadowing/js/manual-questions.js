(function (root) {
  'use strict';
  const hints = {
    EASY: 'Dễ: nhập một từ hoặc cụm ngắn; phần còn lại cần đủ ngữ cảnh.',
    MEDIUM: 'Vừa: nhập cụm từ chiếm khoảng 30–65% câu (ít nhất 5 ký tự).',
    HARD: 'Khó: chép cả câu (12–90 ký tự, đoạn nghe tối đa 18 giây).'
  };

  function payload(values, sources) {
    if (!sources.length || sources.length > 6 || (values.kind === 'GAP' && sources.length !== 1))
      throw new Error('Hãy chọn dẫn chứng liên tiếp; câu điền dùng đúng một dòng.');
    const choices = values.kind === 'GAP' ? [] : values.choices.map(value => value.trim());
    if (choices.length && (choices.length !== 4 || choices.some(value => !value)
        || new Set(choices).size !== 4 || !Number.isInteger(values.correct) || values.correct < 0 || values.correct > 3))
      throw new Error('Nhập bốn lựa chọn khác nhau và chọn một đáp án đúng.');
    const answer = values.kind === 'GAP' ? values.answer.trim() : choices[values.correct];
    if (!answer || !sources.some(line => line.text.includes(answer)))
      throw new Error('Đáp án cần có nguyên văn trong phụ đề dẫn chứng.');
    if (values.kind !== 'GAP' && !values.prompt.trim()) throw new Error('Hãy nhập câu hỏi bằng tiếng Nhật.');
    if (!values.explanation.trim()) throw new Error('Hãy giải thích đáp án trước khi lưu.');
    return { sourceIds: sources.map(line => Number(line.id)), sourceHashes: sources.map(line => line.hash),
      kind: values.kind, level: values.level, prompt: values.kind === 'GAP' ? '' : values.prompt.trim(),
      answer, choices, explanation: values.explanation.trim() };
  }

  function gapPreview(text, answer, level) {
    if (level === 'HARD') return '音声の一文を日本語で書いてください。';
    const value = answer.trim();
    return value && text.includes(value) ? text.replace(value, '＿'.repeat(Math.max(3, Math.min(12, Array.from(value).length)))) : text;
  }

  function create(section, options) {
    const find = id => section.querySelector('#manual-' + id);
    const form = section.querySelector('form'), fields = find('fields'), kind = find('kind'), level = find('level');
    const first = find('source-start'), last = find('source-end'), answer = find('answer'), prompt = find('prompt');
    const choiceInputs = [0, 1, 2, 3].map(i => find('choice-' + i));
    const status = find('status'), listen = find('listen');
    listen.dataset.label = 'Nghe dẫn chứng';
    let sources = [], loaded = false, owner = false, saving = false;
    function time(value) { return Math.floor(value / 60) + ':' + String(Math.floor(value % 60)).padStart(2, '0'); }
    function option(value, text) {
      const node = document.createElement('option'); node.value = String(value); node.textContent = text; return node;
    }
    function selectedSources() {
      const from = sources.findIndex(line => String(line.id) === first.value);
      const to = sources.findIndex(line => String(line.id) === last.value);
      return from < 0 || to < from ? [] : sources.slice(from, to + 1);
    }
    function renderEvidence() {
      const selected = selectedSources();
      find('evidence').textContent = selected.map(line => time(line.startTime) + ' · ' + line.text).join('\n');
      listen.disabled = !selected.length;
      find('gap-preview').textContent = selected.length && kind.value === 'GAP'
        ? gapPreview(selected[0].text, answer.value, level.value) : '';
    }
    function endOptions() {
      const old = last.value, from = sources.findIndex(line => String(line.id) === first.value);
      const available = from < 0 ? [] : sources.slice(from, from + (kind.value === 'GAP' ? 1 : 6));
      last.replaceChildren(...available.map(line => option(line.id, time(line.startTime) + ' · ' + line.text)));
      if (available.some(line => String(line.id) === old)) last.value = old;
      last.disabled = kind.value === 'GAP' || !available.length;
      renderEvidence();
    }
    function mode() {
      const gap = kind.value === 'GAP';
      find('gap-fields').hidden = !gap;
      find('comprehension-fields').hidden = gap;
      answer.required = gap; prompt.required = !gap;
      choiceInputs.forEach(input => { input.required = !gap; });
      find('gap-hint').textContent = hints[level.value] || '';
      endOptions();
    }
    first.addEventListener('change', endOptions); last.addEventListener('change', renderEvidence);
    kind.addEventListener('change', mode); level.addEventListener('change', mode);
    answer.addEventListener('input', renderEvidence);
    listen.addEventListener('click', function () {
      const selected = selectedSources();
      if (selected.length) options.listen({ startTime: selected[0].startTime, endTime: selected.at(-1).endTime }, listen);
    });
    form.addEventListener('submit', async function (event) {
      event.preventDefault();
      if (!owner || saving || !form.reportValidity()) return;
      let data;
      try {
        data = payload({ kind: kind.value, level: level.value, prompt: prompt.value, answer: answer.value,
          choices: choiceInputs.map(input => input.value),
          correct: Number(section.querySelector('[name="manual-correct"]:checked')?.value),
          explanation: find('explanation').value }, selectedSources());
      } catch (error) { status.textContent = error.message; status.dataset.state = 'error'; return; }
      saving = true; fields.disabled = true; status.textContent = 'Đang lưu bản nháp…'; status.dataset.state = '';
      try {
        const response = await options.save(data);
        const question = response.data;
        if (!response.success || !question?.id) throw new Error(response.message || 'Không lưu được câu hỏi.');
        // Clear only after the server confirms persistence; refresh failures must not invite duplicate submissions.
        answer.value = ''; prompt.value = ''; find('explanation').value = '';
        choiceInputs.forEach(input => { input.value = ''; });
        renderEvidence();
        status.textContent = 'Đã lưu bản nháp #' + question.id + '. Kiểm tra và duyệt trong danh sách bên dưới.';
        status.dataset.state = 'success';
        try { await options.saved(); }
        catch (_) { status.textContent += ' Chưa tải lại được danh sách; hãy tải lại trang.'; }
      } catch (error) { status.textContent = error.message || 'Không lưu được. Hãy thử lại.'; status.dataset.state = 'error'; }
      finally { saving = false; fields.disabled = !owner || !sources.length; mode(); }
    });
    mode();
    return {
      update: function (lines, canEdit) {
        owner = Boolean(canEdit); section.hidden = !owner;
        if (!loaded && owner) {
          sources = lines; loaded = true;
          first.replaceChildren(option('', 'Chọn dòng phụ đề'), ...sources.map(line => option(line.id, time(line.startTime) + ' · ' + line.text)));
          mode();
          if (!sources.length) { status.textContent = 'Video chưa có phụ đề đủ điều kiện. Hãy bổ sung hoặc sửa phụ đề trước.'; }
        }
        fields.disabled = saving || !owner || !sources.length;
      }
    };
  }
  const api = { create, payload, gapPreview };
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.ShadowingManualQuestions = api;
}(typeof window === 'object' ? window : globalThis));
