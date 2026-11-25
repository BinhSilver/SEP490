/* [JLAS-W0108] jpath-verbs.js — "Luyện chia động từ" on the grammar lessons that teach a verb form (owner 18/11).
 *
 * The approved map: level → grammar unit → forms of the team's verb practice page (/practice/verbs). The button is
 * optional practice (no CP, does not block the day) and opens that page with the forms and level preselected and a
 * way back to the lesson.
 *
 * window.JpathVerbs = { MAP, LEVEL_REVIEW, formsFor(level, unitNo), practiceUrl(level, forms, back, from),
 *                       blockHtml(level, unitNo, back) }
 */
(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathVerbs = api;
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';

  var LABEL = {
    MASU: 'thể ます', TE: 'thể て', NAI: 'thể ない', TA: 'thể た', NAKATTA: 'thể なかった',
    POTENTIAL: 'thể khả năng', VOLITIONAL: 'thể ý chí', BA: 'điều kiện ば', TARA: 'điều kiện たら',
    PASSIVE: 'thể bị động', CAUSATIVE: 'thể sai khiến', CAUSATIVE_PASSIVE: 'sai khiến bị động'
  };
  var ALL = ['MASU', 'TE', 'NAI', 'TA', 'NAKATTA', 'POTENTIAL', 'VOLITIONAL', 'BA', 'TARA', 'PASSIVE', 'CAUSATIVE', 'CAUSATIVE_PASSIVE'];

  // owner 18/11: the unit where the form is first taught, plus the mixed reviews
  var MAP = {
    N5: { 4: ['MASU'], 14: ['TE'], 17: ['NAI'], 19: ['TA'], 20: ['NAI', 'TA', 'NAKATTA'], 25: ['TARA'] },
    N4: { 1: ['MASU', 'TE', 'NAI', 'TA'], 15: ['BA'], 27: ['POTENTIAL'], 31: ['VOLITIONAL'], 37: ['PASSIVE'],
      48: ['CAUSATIVE', 'CAUSATIVE_PASSIVE'] },
    N3: { 6: ['CAUSATIVE_PASSIVE'], 8: ['BA', 'TARA'] }
  };
  // every form a level has taught so far: the all-forms review before the level test
  var LEVEL_REVIEW = {
    N5: ['MASU', 'TE', 'NAI', 'TA', 'NAKATTA', 'TARA'],
    N4: ALL.slice(),
    N3: ALL.slice(), N2: ALL.slice(), N1: ALL.slice()
  };

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function formsFor(level, unitNo) {
    var lv = MAP[String(level || '').toUpperCase()];
    var f = lv && lv[Number(unitNo)];
    return f ? f.slice() : [];
  }

  /** The practice page only takes back links inside /learning. */
  function practiceUrl(level, forms, back, from) {
    var q = 'forms=' + encodeURIComponent((forms || []).join(','));
    if (level) q += '&level=' + encodeURIComponent(level);
    if (back && /^\/learning(\/|\?|$)/.test(back)) q += '&back=' + encodeURIComponent(back);
    if (from) q += '&from=' + encodeURIComponent(from);
    return '/practice/verbs?' + q;
  }

  function label(forms) {
    return forms.length > 2 ? 'ôn trộn ' + forms.map(function (f) { return LABEL[f]; }).join(', ')
      : forms.map(function (f) { return LABEL[f]; }).join(' và ');
  }

  /** Card under the lesson; '' when the unit teaches no verb form. */
  function blockHtml(level, unitNo, back) {
    var forms = formsFor(level, unitNo);
    if (!forms.length) return '';
    var lv = String(level).toUpperCase();
    return '<section class="jp-card jp-verbs" aria-label="Luyện chia động từ">'
      + '<div class="jp-verbs__text"><h2 class="jp-verbs__title">Luyện chia động từ</h2>'
      + '<p class="jp-verbs__sub">Bài này dùng ' + esc(label(forms)) + '. Luyện thêm cho quen tay, không bắt buộc.</p></div>'
      + '<a class="jp-btn jp-btn--primary" href="' + esc(practiceUrl(lv, forms, back, lv + ' · Bài ' + unitNo)) + '">Luyện '
      + esc(forms.length > 2 ? 'trộn các thể' : label(forms)) + '</a></section>';
  }

  return { MAP: MAP, LEVEL_REVIEW: LEVEL_REVIEW, LABEL: LABEL, formsFor: formsFor, practiceUrl: practiceUrl, blockHtml: blockHtml };
});
