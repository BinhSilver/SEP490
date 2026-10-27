/* Two paste-data import modes, chosen with options.mode:
 *
 * 'pair' (default, Quizlet-style): one separator between term and definition, like Quizlet's own
 * "giữa thuật ngữ và định nghĩa" / "giữa các thẻ" pickers. A 3rd column (via the same separator)
 * becomes Hán Việt. Lines the chosen separator can't split fall back to DEFAULT_TEMPLATE below.
 *
 * 'quad': our card has 4 fields (Từ / Cách đọc / Nghĩa / Hán Việt), not 2 like Quizlet, so the user
 * builds a line template instead: an ordered list of slots, each "after this separator comes this
 * field". Nothing about the order or the separators is fixed — e.g. "Từ ( Hán Việt ) = Nghĩa" works.
 *
 * Slot separators: several alternatives may be typed separated by spaces (": -" accepts ":" or "-",
 * since real glossaries mix punctuation line to line); empty means whitespace; a lone opening bracket
 * such as "(" wraps the field and its closing bracket is stripped. Every slot after the first is
 * optional per line, but a line needs the first field plus at least one more to count as a card. */
(function (root, factory) {
  var api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.FlashImportSeparators = api;
})(typeof window !== 'undefined' ? window : this, function () {
  'use strict';

  var TERM_OPTIONS = [
    { value: 'tab', label: 'Tab', split: '\t' },
    { value: 'comma', label: 'Phẩy', split: ',' },
    { value: 'custom', label: 'Tùy chỉnh' }
  ];
  var CARD_OPTIONS = [
    { value: 'newline', label: 'Dòng mới' },
    { value: 'semicolon', label: 'Chấm phẩy', split: ';' },
    { value: 'custom', label: 'Tùy chỉnh' }
  ];
  var FIELDS = [
    { value: 'FRONT', label: 'Kanji' },
    { value: 'READING', label: 'Cách đọc' },
    { value: 'MEANING', label: 'Nghĩa' },
    { value: 'HAN_VIET', label: 'Hán Việt' }
  ];
  var MAX_SLOTS = FIELDS.length;
  var DEFAULT_TERM = { type: 'tab', custom: '' };
  var DEFAULT_CARD = { type: 'newline', custom: '' };
  // "Kanji - Cách đọc : Nghĩa (Hán Việt)"; the Nghĩa slot also accepts "-" because pasted lists mix both.
  var DEFAULT_TEMPLATE = [
    { sep: '', field: 'FRONT' },
    { sep: '-', field: 'READING' },
    { sep: ': -', field: 'MEANING' },
    { sep: '(', field: 'HAN_VIET' }
  ];
  var BRACKETS = { '(': ')', '（': '）', '[': ']', '【': '】', '「': '」', '『': '』', '{': '}' };

  // A word without kanji (katakana loanword like コーヒー, or a hiragana-only word) has no Hán Việt;
  // same rule as the server (com.jlas.flashcard.util.KanaWords).
  var HAN_CHAR = /\p{Script=Han}/u;
  var KANA_ONLY = /^[぀-ゟ゠-ヿー・\s]+$/;
  function hasKanji(value) { return HAN_CHAR.test(String(value || '')); }
  function isKanaWord(value) {
    var v = String(value || '').trim();
    return Boolean(v) && !hasKanji(v) && KANA_ONLY.test(v);
  }

  function optionOf(options, type) {
    for (var i = 0; i < options.length; i++) if (options[i].value === type) return options[i];
    return null;
  }

  function escapeRegExp(value) { return String(value).replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }

  /* The literal string a selection resolves to, falling back to `fallback` while a chosen
   * "Tùy chỉnh" value is still empty, so the preview stays sensible as the user types it. */
  function resolveValue(options, selection, fallback) {
    var sel = selection || {};
    if (sel.type === 'custom') return sel.custom || fallback;
    var option = optionOf(options, sel.type);
    return (option && option.split) || fallback;
  }

  function termSplitValue(selection) { return resolveValue(TERM_OPTIONS, selection || DEFAULT_TERM, TERM_OPTIONS[0].split); }

  /* Splits the whole textarea into one chunk per card. Falls back to newlines while a custom
   * separator is still empty, so the preview stays sensible as the user types it. */
  function cardChunks(text, selection) {
    if (!text) return [];
    var sel = selection || DEFAULT_CARD;
    if (sel.type === 'newline') return text.split(/\r?\n/);
    var value = resolveValue(CARD_OPTIONS, sel, '');
    return value ? text.split(value) : text.split(/\r?\n/);
  }

  /* '' when the template is usable, otherwise the Vietnamese reason it isn't. */
  function validateTemplate(template) {
    var slots = Array.isArray(template) ? template : [];
    if (slots.length < 2) return 'Mẫu cần ít nhất 2 trường';
    if (slots.length > MAX_SLOTS) return 'Mẫu có tối đa ' + MAX_SLOTS + ' trường';
    var seen = {};
    for (var i = 0; i < slots.length; i++) {
      if (!optionOf(FIELDS, slots[i].field)) return 'Trường không hợp lệ';
      if (seen[slots[i].field]) return 'Mỗi trường chỉ dùng một lần';
      seen[slots[i].field] = true;
    }
    if (!seen.FRONT) return 'Mẫu cần có trường Kanji';
    if (!seen.MEANING && !seen.READING) return 'Mẫu cần có Nghĩa hoặc Cách đọc';
    return '';
  }

  function slotPattern(sep) {
    var tokens = String(sep || '').trim().split(/\s+/).filter(Boolean);
    if (!tokens.length) return '\\s+(.+?)';
    if (tokens.length === 1 && BRACKETS[tokens[0]]) {
      var close = BRACKETS[tokens[0]];
      return '\\s*' + escapeRegExp(tokens[0]) + '\\s*([^' + escapeRegExp(close) + ']*?)\\s*' + escapeRegExp(close);
    }
    return '\\s*(?:' + tokens.map(escapeRegExp).join('|') + ')\\s*(.+?)';
  }

  /* First slot required, every later slot optional; capture group i+1 holds slot i's field. */
  function buildTemplateRegex(template) {
    var source = '^\\s*(.+?)';
    for (var i = 1; i < template.length; i++) source += '(?:' + slotPattern(template[i].sep) + ')?';
    return new RegExp(source + '\\s*$');
  }

  function parseWithTemplate(line, template, re) {
    var m = line.match(re);
    if (!m) return null;
    var values = {}, filled = 0;
    template.forEach(function (slot, i) {
      var value = m[i + 1] ? m[i + 1].trim() : '';
      if (value) { values[slot.field] = value; filled++; }
    });
    if (!values.FRONT || filled < 2 || !(values.MEANING || values.READING)) return null;
    // reading/meaning are kept separately so the editor (and the 4-column preview) need not re-split back.
    return { front: values.FRONT,
      back: [values.READING, values.MEANING].filter(Boolean).join(' — '),
      reading: values.READING || '', meaning: values.MEANING || '',
      hanViet: isKanaWord(values.FRONT) ? '' : (values.HAN_VIET || '') };
  }

  var DEFAULT_TEMPLATE_RE = buildTemplateRegex(DEFAULT_TEMPLATE);

  // A reading is kana (or romaji); anything else usually means a separator was missed and the next
  // field spilled into Cách đọc (e.g. "たね- Hạt, hạt giống" when the Nghĩa slot only accepts ":").
  var READING_LIKE = /^(?:[぀-ヿー・〜～\s]+|[A-Za-z\s'-]+)$/;

  /* Per-field doubts about a quad-mode card, so the preview can flag rows to re-check. Parsing itself
   * stays purely positional — these never change which text went into which field. */
  function cardIssues(card, template) {
    var issues = {};
    var meaningSlot = template.filter(function (slot) { return slot.field === 'MEANING'; })[0];
    // Name the user's own separator, so the warning reads as "your rule was applied", not "use the default".
    var tokens = meaningSlot ? String(meaningSlot.sep || '').trim().split(/\s+/).filter(Boolean) : [];
    var sepText = tokens.length ? tokens.map(function (t) { return '"' + t + '"'; }).join(' hoặc ') : 'khoảng trắng';
    if (meaningSlot && !card.meaning) {
      issues.meaning = 'Dòng này không có dấu ' + sepText + ' (dấu bạn đặt trước Nghĩa), nên Nghĩa để trống';
    }
    if (card.reading && !READING_LIKE.test(card.reading)) {
      issues.reading = meaningSlot && !card.meaning
        ? 'Không gặp dấu ' + sepText + ' nên phần còn lại của dòng nằm trong Cách đọc'
        : 'Cách đọc có chữ không phải kana';
    }
    return issues;
  }

  var KANA_READING = /^[぀-ヿー・〜～\s]+$/;

  /* "かいはつ — phát triển" (the editor's own back format) → reading + meaning; anything else is all meaning. */
  function splitBackText(back) {
    var at = back.indexOf(' — ');
    if (at > 0 && KANA_READING.test(back.slice(0, at))) return { reading: back.slice(0, at).trim(), meaning: back.slice(at + 3).trim() };
    return { reading: '', meaning: back };
  }

  function cardFromFields(front, reading, meaning, hanViet) {
    return { front: front, back: [reading, meaning].filter(Boolean).join(' — '), reading: reading, meaning: meaning,
      hanViet: isKanaWord(front) ? '' : hanViet };
  }

  /* 'pair' mode: split by the chosen term separator. The number of columns decides their meaning, so
   * both the current export (Kanji, Cách đọc, Nghĩa, Hán Việt) and older/hand-made lists import:
   *   4+ columns → Kanji, Cách đọc, Nghĩa, Hán Việt
   *   3 columns  → Kanji, Cách đọc, Nghĩa when the 2nd is kana or empty (export without Hán Việt);
   *                otherwise Kanji, Mặt sau, Hán Việt (older export / hand-made list)
   *   2 columns  → Kanji, Mặt sau
   * A "Mặt sau" written as "かいはつ — phát triển" is split into Cách đọc and Nghĩa. Lines that don't
   * split at all fall back to the default "Kanji - Cách đọc : Nghĩa (Hán Việt)" template. */
  function parseCard(raw, termSelection) {
    var line = String(raw == null ? '' : raw).trim();
    if (!line) return null;
    var c = line.split(termSplitValue(termSelection)).map(function (cell) { return cell.trim(); });
    if (c[0] && c.length >= 4 && (c[1] || c[2])) return cardFromFields(c[0], c[1], c[2], c[3] || '');
    if (c[0] && c.length === 3 && c[2] && (!c[1] || KANA_READING.test(c[1]))) return cardFromFields(c[0], c[1], c[2], '');
    if (c[0] && c.length >= 2 && c[1]) {
      var parts = splitBackText(c[1]);
      return cardFromFields(c[0], parts.reading, parts.meaning, c[2] || '');
    }
    return parseWithTemplate(line, DEFAULT_TEMPLATE, DEFAULT_TEMPLATE_RE);
  }

  /* Parses the whole textarea. `options`: {mode: 'pair'|'quad', term, card, template} — term/card
   * are {type, custom} selections; `card` applies in both modes, `term` only in 'pair', `template`
   * (list of {sep, field}) only in 'quad'. An invalid template yields no cards. */
  function parse(text, options) {
    var opts = options || {};
    var isQuad = opts.mode === 'quad';
    var template = opts.template || DEFAULT_TEMPLATE;
    var templateError = isQuad ? validateTemplate(template) : '';
    var re = isQuad && !templateError ? buildTemplateRegex(template) : null;
    var cards = [], issues = [], invalidCount = 0;
    cardChunks(text, opts.card).forEach(function (raw) {
      var line = String(raw == null ? '' : raw).trim();
      if (!line) return;
      var card = !isQuad ? parseCard(line, opts.term) : (re ? parseWithTemplate(line, template, re) : null);
      if (!card) { invalidCount++; return; }
      cards.push(Object.assign({ image: '', _selected: false }, card));
      issues.push(isQuad ? cardIssues(card, template) : {});
    });
    // issues[i] describes cards[i]: {reading?, meaning?} → Vietnamese reason that field looks wrong.
    var issueCount = issues.filter(function (issue) { return Object.keys(issue).length; }).length;
    return { cards: cards, invalidCount: invalidCount, templateError: templateError, issues: issues, issueCount: issueCount };
  }

  /* The mode pasted/imported text calls for: 'pair' when most lines split into columns with the term
   * separator (spreadsheets, exported files), 'quad' when most lines follow the line template
   * ("Kanji - Cách đọc : Nghĩa (Hán Việt)"), null when neither is clear (keep the user's choice). */
  function detectMode(text, options) {
    var opts = options || {};
    var lines = cardChunks(text, opts.card).map(function (line) { return String(line || '').trim(); }).filter(Boolean);
    if (!lines.length) return null;
    var sep = termSplitValue(opts.term);
    var columnar = lines.filter(function (line) {
      return line.split(sep).filter(function (cell) { return cell.trim(); }).length >= 2;
    }).length;
    if (columnar * 2 >= lines.length) return 'pair';
    var template = opts.template || DEFAULT_TEMPLATE;
    if (validateTemplate(template)) return null;
    var re = buildTemplateRegex(template);
    var structured = lines.filter(function (line) { return parseWithTemplate(line, template, re); }).length;
    return structured * 2 >= lines.length ? 'quad' : null;
  }

  /* "Kanji - Cách đọc : Nghĩa ( Hán Việt )" — a readable sample of what one line should look like. */
  function describeTemplate(template) {
    return (template || []).map(function (slot, i) {
      var label = (optionOf(FIELDS, slot.field) || {}).label || '?';
      var tokens = String(slot.sep || '').trim().split(/\s+/).filter(Boolean);
      if (i === 0) return label;
      if (!tokens.length) return ' ' + label;
      if (tokens.length === 1 && BRACKETS[tokens[0]]) return ' ' + tokens[0] + label + BRACKETS[tokens[0]];
      return ' ' + tokens[0] + ' ' + label;
    }).join('');
  }

  return {
    TERM_OPTIONS: TERM_OPTIONS, CARD_OPTIONS: CARD_OPTIONS, FIELDS: FIELDS, MAX_SLOTS: MAX_SLOTS,
    DEFAULT_TERM: DEFAULT_TERM, DEFAULT_CARD: DEFAULT_CARD, DEFAULT_TEMPLATE: DEFAULT_TEMPLATE,
    hasKanji: hasKanji, isKanaWord: isKanaWord, termSplitValue: termSplitValue, cardChunks: cardChunks,
    validateTemplate: validateTemplate, describeTemplate: describeTemplate, detectMode: detectMode, parseCard: parseCard, parse: parse
  };
});
