(function (global) {
  'use strict';

  var labels = ['Từ điển', 'た', 'ない', 'ます', 'て', 'Khả năng', 'Thụ động',
    'Sai khiến', 'Sai khiến thụ động', 'Điều kiện ば', 'Mệnh lệnh', 'Ý chí', 'Cấm chỉ'];
  var godan = {
    'う': ['わ', 'い', 'え', 'お', 'って', 'った'],
    'つ': ['た', 'ち', 'て', 'と', 'って', 'った'],
    'る': ['ら', 'り', 'れ', 'ろ', 'って', 'った'],
    'む': ['ま', 'み', 'め', 'も', 'んで', 'んだ'],
    'ぶ': ['ば', 'び', 'べ', 'ぼ', 'んで', 'んだ'],
    'ぬ': ['な', 'に', 'ね', 'の', 'んで', 'んだ'],
    'く': ['か', 'き', 'け', 'こ', 'いて', 'いた'],
    'ぐ': ['が', 'ぎ', 'げ', 'ご', 'いで', 'いだ'],
    'す': ['さ', 'し', 'せ', 'そ', 'して', 'した']
  };

  function conjugate(expression, posLabels) {
    var word = String(expression || '');
    var type = null;
    (posLabels || []).some(function (label) {
      if (/\(する\)/.test(label)) type = 'suru';
      else if (/\(来る\)/.test(label)) type = 'kuru';
      else if (/nhóm I(?:\b|$)/i.test(label)) type = 'godan';
      else if (/nhóm II(?:\b|$)/i.test(label)) type = 'ichidan';
      return !!type;
    });
    if (!word || !type) return null;
    var forms;
    if (type === 'godan') {
      var end = word.slice(-1);
      var row = godan[end];
      if (!row) return null;
      var stem = word.slice(0, -1);
      var a = stem + row[0], i = stem + row[1], e = stem + row[2], o = stem + row[3];
      var iku = word.endsWith('行く') || word.endsWith('いく');
      var te = iku ? stem + 'って' : stem + row[4];
      var ta = iku ? stem + 'った' : stem + row[5];
      forms = [word, ta, a + 'ない', i + 'ます', te, e + 'る', a + 'れる',
        a + 'せる', a + 'せられる', e + 'ば', e, o + 'う', word + 'な'];
    } else if (type === 'ichidan') {
      if (!word.endsWith('る')) return null;
      var base = word.slice(0, -1);
      forms = [word, base + 'た', base + 'ない', base + 'ます', base + 'て',
        base + 'られる', base + 'られる', base + 'させる', base + 'させられる',
        base + 'れば', base + 'ろ', base + 'よう', word + 'な'];
    } else if (type === 'suru') {
      var prefix = word.endsWith('する') ? word.slice(0, -2) : word;
      forms = ['する', 'した', 'しない', 'します', 'して', 'できる', 'される',
        'させる', 'させられる', 'すれば', 'しろ', 'しよう', 'するな']
        .map(function (form) { return prefix + form; });
    } else {
      var kana = word.endsWith('くる');
      var kanji = word.endsWith('来る');
      if (!kana && !kanji) return null;
      var prefixK = word.slice(0, -2);
      forms = (kana
        ? ['くる', 'きた', 'こない', 'きます', 'きて', 'こられる', 'こられる',
          'こさせる', 'こさせられる', 'くれば', 'こい', 'こよう', 'くるな']
        : ['来る', '来た', '来ない', '来ます', '来て', '来られる', '来られる',
          '来させる', '来させられる', '来れば', '来い', '来よう', '来るな'])
        .map(function (form) { return prefixK + form; });
    }
    return labels.map(function (label, index) { return { label: label, form: forms[index] }; });
  }

  global.DictConjugation = { conjugate: conjugate };
  if (typeof module !== 'undefined' && module.exports) module.exports = global.DictConjugation;
})(typeof window !== 'undefined' ? window : globalThis);
