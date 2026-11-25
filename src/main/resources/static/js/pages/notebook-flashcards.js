/* Adapt saved dictionary words and map characters to the same notebook and Flashcard fields. */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory();
  else root.NotebookFlashcards = factory();
})(typeof window !== 'undefined' ? window : this, function () {
  'use strict';

  function text(value) { return String(value == null ? '' : value).trim(); }

  function notebookItems(words, characters) {
    return (words || []).map(function (entry) {
      return {
        type: 'vocabulary', key: 'word:' + entry.vocabularyId,
        term: text(entry.expression), reading: text(entry.reading), meaning: text(entry.meaning),
        hanViet: text(entry.hanViet), exampleJa: entry.exampleJa || '', exampleVi: entry.exampleVi || '',
        savedAt: entry.createdAt, source: entry
      };
    }).concat((characters || []).map(function (entry) {
      var readings = Array.from(new Set([].concat(entry.on || [], entry.kun || []).map(text).filter(Boolean)));
      return {
        type: 'kanji', key: 'kanji:' + entry.character,
        term: text(entry.character), reading: readings.join(' · '), meaning: text(entry.meaningVi),
        hanViet: text(entry.hanViet), exampleJa: entry.exampleJa || '', exampleVi: entry.exampleVi || '',
        savedAt: entry.savedAt, source: entry
      };
    }));
  }

  function buildDraft(book, entries, selectedIds) {
    var selected = new Set(Array.from(selectedIds || []).map(String)), seen = new Set();
    if (!selected.size) throw new Error('Chọn ít nhất một từ để tạo bộ thẻ.');
    var cards = [];
    (entries || []).forEach(function (entry) {
      var id = entry.key;
      if (!selected.has(id) || seen.has(id)) return;
      seen.add(id);
      var front = text(entry.term), meaning = text(entry.meaning), reading = text(entry.reading), hanViet = text(entry.hanViet);
      if (!front) throw new Error('Một từ đã chọn chưa có nội dung. Hãy kiểm tra lại sổ tay.');
      if (front.length > 500 || meaning.length > 500 || reading.length > 255 || hanViet.length > 255) {
        throw new Error('Từ “' + front + '” có nội dung quá dài để tạo thẻ. Hãy kiểm tra lại trước khi tạo.');
      }
      cards.push({
        front: front, back: meaning, reading: reading, meaning: meaning, hanViet: hanViet,
        exampleJa: entry.exampleJa || '', exampleVi: entry.exampleVi || '', position: cards.length
      });
    });
    if (seen.size !== selected.size) throw new Error('Một số từ đã chọn không còn trong sổ tay. Hãy chọn lại.');
    return {
      title: (text(book && book.name) || 'Từ đã lưu trong sổ tay').slice(0, 100),
      description: 'Các từ được chọn từ sổ tay', visibility: 'PRIVATE', folderId: null, cards: cards
    };
  }

  function editorUrl(draft) {
    var id = Number(draft && draft.id);
    if (!Number.isSafeInteger(id) || id <= 0) throw new Error('Không nhận được bộ thẻ vừa tạo. Hãy kiểm tra trong Flashcard.');
    return '/flashcard/editor.html?id=' + id;
  }

  return { notebookItems: notebookItems, buildDraft: buildDraft, editorUrl: editorUrl };
});
