/* Which card fields each study face shows: 1-3 of Kanji / Hiragana / Hán Việt / Nghĩa per face. */
(function (root, factory) {
  var api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.FlashStudyFaceFields = api;
})(typeof window !== 'undefined' ? window : this, function () {
  'use strict';

  var FIELDS = [
    { value: 'FRONT', label: 'Kanji' },
    { value: 'HIRAGANA', label: 'Hiragana' },
    { value: 'HAN_VIET', label: 'Hán Việt' },
    { value: 'MEANING', label: 'Nghĩa' }
  ];
  var MAX_PER_FACE = 3;
  var DEFAULTS = { front: ['FRONT'], back: ['HIRAGANA', 'MEANING'] };
  // Older single-field settings (select values) map onto the new field names.
  var LEGACY = { BACK: ['HIRAGANA', 'MEANING'], READING: ['HIRAGANA'] };

  function clean(value) { return String(value == null ? '' : value).trim(); }

  function value(card, field) {
    if (!card) return '';
    switch (field) {
      case 'FRONT': return clean(card.front) || clean(card.kanji);
      case 'HIRAGANA': return clean(card.hiragana) || clean(card.reading);
      case 'HAN_VIET': return clean(card.hanViet);
      case 'MEANING': return clean(card.meaning) || clean(card.back);
      default: return '';
    }
  }

  /* A field can be chosen when at least one card has it; cards missing it just show the other fields. */
  function available(cards) {
    var list = cards || [];
    return FIELDS.filter(function (field) {
      return list.some(function (card) { return value(card, field.value); });
    }).map(function (field) { return field.value; });
  }

  /* Cards that would show nothing on a face with these fields. */
  function blankCount(cards, fields) {
    var chosen = normalize(fields);
    return (cards || []).filter(function (card) {
      return !chosen.some(function (field) { return value(card, field); });
    }).length;
  }

  function missingCount(cards, field) {
    return (cards || []).filter(function (card) { return !value(card, field); }).length;
  }

  function normalize(fields) {
    var list = Array.isArray(fields) ? fields : [fields];
    var out = [];
    list.forEach(function (field) {
      var key = clean(field).toUpperCase();
      (LEGACY[key] || [key]).forEach(function (name) {
        if (FIELDS.some(function (f) { return f.value === name; }) && out.indexOf(name) < 0) out.push(name);
      });
    });
    // Keep the canonical order Kanji → Hiragana → Hán Việt → Nghĩa on the card.
    return FIELDS.map(function (f) { return f.value; }).filter(function (name) { return out.indexOf(name) >= 0; });
  }

  /* '' when valid, otherwise the Vietnamese reason the selection cannot be applied. */
  function validate(selection, allowed, cards) {
    var front = normalize(selection.front), back = normalize(selection.back);
    if (!front.length || !back.length) return 'Mỗi mặt cần ít nhất một trường';
    if (front.length > MAX_PER_FACE || back.length > MAX_PER_FACE) return 'Mỗi mặt chọn tối đa ' + MAX_PER_FACE + ' trường';
    if (front.some(function (field) { return back.indexOf(field) >= 0; })) return 'Một trường không thể nằm ở cả hai mặt';
    if (allowed && front.concat(back).some(function (field) { return allowed.indexOf(field) < 0; })) {
      return 'Có trường không thẻ nào trong bộ có dữ liệu';
    }
    if (cards) {
      var blankFront = blankCount(cards, front), blankBack = blankCount(cards, back);
      if (blankFront) return blankFront + ' thẻ sẽ bị trống ở mặt trước — hãy chọn thêm trường';
      if (blankBack) return blankBack + ' thẻ sẽ bị trống ở mặt sau — hãy chọn thêm trường';
    }
    return '';
  }

  /* Drops fields this set cannot show; falls back to the first valid default-like pairing. */
  function sanitize(selection, allowed, cards) {
    var pick = function (fields) {
      return normalize(fields).filter(function (field) { return allowed.indexOf(field) >= 0; }).slice(0, MAX_PER_FACE);
    };
    var front = pick(selection && selection.front);
    var back = pick(selection && selection.back).filter(function (field) { return front.indexOf(field) < 0; });
    if (!validate({ front: front, back: back }, allowed, cards)) return { front: front, back: back };
    front = pick(DEFAULTS.front);
    back = pick(DEFAULTS.back).filter(function (field) { return front.indexOf(field) < 0; });
    if (!front.length) front = allowed.slice(0, 1);
    if (!back.length) back = allowed.filter(function (field) { return front.indexOf(field) < 0; }).slice(0, 1);
    return { front: front, back: back };
  }

  function text(card, fields, separator) {
    return normalize(fields).map(function (field) { return value(card, field); })
      .filter(Boolean).join(separator == null ? '\n' : separator);
  }

  return { FIELDS: FIELDS, MAX_PER_FACE: MAX_PER_FACE, DEFAULTS: DEFAULTS, value: value, available: available,
    missingCount: missingCount, blankCount: blankCount, normalize: normalize, validate: validate, sanitize: sanitize, text: text };
});
