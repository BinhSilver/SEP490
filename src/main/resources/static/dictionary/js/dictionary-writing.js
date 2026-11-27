/* Adapts dictionary Kanji details to the shared JLPT writing dialog without requiring a path item. */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.DictWriting = api;
})(typeof window !== 'undefined' ? window : globalThis, function (root) {
  'use strict';

  function toView(kanji, svgUrl) {
    var character = kanji.character;
    var readings = (kanji.readings || []).filter(function (r) {
      return r && (r.readingType === 'KUN' || r.readingType === 'ON') && r.readingText;
    }).map(function (r) { return r.readingText; }).slice(0, 4);
    var codePoint = character.codePointAt(0);
    return {
      itemType: 'KANJI', code: character, word: character,
      reading: readings.join('、') || null,
      meaning: kanji.meaningVi || null, hanViet: kanji.hanViet || null,
      characters: [{ char: character, codePoint: codePoint, svgUrl: svgUrl || null,
        strokeCount: kanji.strokeCount || null, hasSvg: !!svgUrl }]
    };
  }

  /** A missing or unreachable SVG still leaves the tracing tab available. */
  function open(kanji, isCurrent) {
    if (!kanji || !kanji.character || !root.JpathWriting) return Promise.resolve(null);
    var svgUrl = kanji.strokeSvgUri;
    var available = svgUrl && root.fetch
      ? root.fetch(svgUrl).then(function (response) { return response.ok ? svgUrl : null; }, function () { return null; })
      : Promise.resolve(null);
    return Promise.resolve(available).then(function (url) {
      if (isCurrent && !isCurrent()) return null;
      return root.JpathWriting.open(toView(kanji, url));
    });
  }

  return { toView: toView, open: open };
});
