/* [JLAS-W0108] jpath-study.js — the study card screen of the JLPT path (/learning/jlpt/study?session=).
 *
 * Draws one card of GET /api/learning/jlpt/sessions/{id} (JpathSessionView: steps, minutesLeft, current, upcoming;
 * JpathCardView per card). Every text goes through JpathDisplay.esc / JpathFurigana.toRuby.
 *
 * window.JpathStudy:
 *   RATINGS, RATING_LABEL, STEP_LABEL
 *   intervalLabel(rating, days)        FORGOT or < 1 → "< 1 ngày"; < 30 "{d} ngày"; < 365 "{round(d/30)} tháng";
 *                                      else "{1 decimal, comma} năm"
 *   cardHtml(card, display, flipped, state?)
 *                                      the card body; state.retype = {message, verdict} once a RETYPE was answered
 *   ratingButtonsHtml(card, flipped, state?)
 *                                      .jp-rate-bar: NEW 2 buttons, REVIEW "Lật thẻ (Space)" then 4 buttons with the
 *                                      spaced interval, GRAMMAR "Tiếp", RETYPE "Tiếp" after the answer
 *   stepsHtml(steps, minutesLeft, tab) "Ôn 18 → Từ mới 6/20 → … · ~22 phút còn lại" (split tab: "Từ vựng")
 *   keyAction(key, ctx)                {type: AUDIO|FLIP|RATE|TOGGLE_FURIGANA|SUBMIT, rating?} | null;
 *                                      ctx = {card, flipped, inputFocused, modifier, retypeDone}
 *   controlPatch(display, control, font) → {display, patch}; bindControls(el, {display, saver, onChange})
 *   initFlow(session) → {session, cards:[current, ...upcoming], position, total, flipped, retype, ...}
 *   reduce(flow, action, {uuid, now}) → {flow, event?, effect?}; actions RATE{rating, elapsedMs, code} | FLIP |
 *                                      NEXT | RETYPE_SUBMIT{typed, elapsedMs} | RETYPE_RESULT{outcome?} |
 *                                      SERVER_SYNC{session, pending} | DROPPED{n}; effects {navigate} | {reload} |
 *                                      {issueCheck:{kind, tab, sessionId}} | {play}
 *   stepEffect(flow)                   check step → navigate (token) or issueCheck; no card → done page / reload
 *   classifySwipe(dx, dy, ms), swipeAllowed({selectionText, pointerType}), swipeRating(card, gesture),
 *   newCardEffect(card, display), retypeMessage(verdict?, expected), skipMessage(correct),
 *   shouldShowSwipeHint(display, coarse), swipeHintHtml(), OFFLINE_TEXT, NEED_NETWORK_TEXT, CHECK_NEEDS_NETWORK_TEXT
 *   mountPage(doc)                     wires #jpathStudyRoot: events go through JpathOfflineQueue (one uuid each,
 *                                      flushed at once, again on 'online' and 'pagehide'); a flush answer syncs the
 *                                      session; a REJECTED outcome or a dropped batch reloads it
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathStudy = api;
  if (root && root.document && typeof root.document.getElementById === 'function') {
    var start = function () { if (root.document.getElementById('jpathStudyRoot')) api.mountPage(root.document); };
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', start);
    else start();
  }
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var BASE = '/api/learning/jlpt';
  var RATINGS = Object.freeze({ NEW: Object.freeze(['FORGOT', 'REMEMBERED']),
    REVIEW: Object.freeze(['FORGOT', 'HARD', 'GOOD', 'EASY']) });
  var RATING_LABEL = Object.freeze({ FORGOT: 'Chưa nhớ', REMEMBERED: 'Nhớ rồi', HARD: 'Khó', GOOD: 'Nhớ tốt',
    EASY: 'Rất dễ' });
  var STEP_LABEL = Object.freeze({ REVIEW: 'Ôn', VOCAB: 'Từ mới', KANJI: 'Kanji', GRAMMAR: 'Ngữ pháp', KANA: 'Chữ cái',
    SKILL_QUIZ: 'Đọc · Nghe' });
  var SPLIT_VOCAB_LABEL = 'Từ vựng';
  var CHECK_KINDS = { MICRO_CHECK: true, GROUP_EXERCISE: true };
  var PAGE_CONTEXT = 'STUDY_CARD';

  function D() { return root.JpathDisplay; }
  function F() { return root.JpathFurigana; }
  function esc(v) { return D().esc(v); }
  function has(v) { return v !== null && v !== undefined && v !== ''; }
  function list(v) { return Array.isArray(v) ? v : []; }
  function isCheck(card) { return !!card && !!CHECK_KINDS[card.kind]; }
  function isFlippable(card) {
    return !!card && !isCheck(card) && card.kind !== 'RETYPE' && card.itemType !== 'GRAMMAR'
      && (card.kind === 'REVIEW' || card.kind === 'NEW');
  }

  // ---- labels ---------------------------------------------------------------------------------------------

  function intervalLabel(rating, days) {
    var d = Number(days);
    if (rating === 'FORGOT' || days === null || days === undefined || isNaN(d) || d < 1) return '< 1 ngày';
    if (d < 30) return Math.round(d) + ' ngày';
    if (d < 365) return Math.round(d / 30) + ' tháng';
    return (Math.round(d / 36.5) / 10).toFixed(1).replace('.', ',') + ' năm';
  }

  // ---- card body ------------------------------------------------------------------------------------------

  function attrJson(obj) { return D().dataJson(obj); }

  function line(cls, html) { return html ? '<div class="' + cls + '">' + html + '</div>' : ''; }

  function examplesHtml(card) {
    var html = '';
    list(card.examples).forEach(function (ex) {
      if (!ex) return;
      var body = F().exampleHtml(ex);
      if (!body) return;
      var report = has(ex.code)
        ? ' data-jpath-report="' + attrJson({ targetType: 'EXAMPLE', targetCode: ex.code, pageContext: PAGE_CONTEXT }) + '"'
        : '';
      html += '<div class="jp-ex"' + report + '>' + body + '</div>';
    });
    // Owner 05/11: a collapsed "Ví dụ" looked empty; the first time an item is shown its examples are open.
    return html ? '<details class="jp-study-examples"' + (card.kind === 'NEW' ? ' open' : '') + '><summary>Ví dụ ('
      + list(card.examples).filter(Boolean).length + ')</summary>' + html + '</details>' : '';
  }

  function mnemonicHtml(card) {
    if (!has(card.mnemonic)) return '';
    return '<details' + (card.mnemonicAutoOpen ? ' open' : '') + ' class="jp-mnemonic"><summary>Mẹo nhớ</summary>'
      + '<p>' + esc(card.mnemonic) + '</p></details>';
  }

  function wordListHtml(words, title, cls) {
    var items = list(words).filter(function (w) { return w && has(w.expression); });
    if (!items.length) return '';
    return '<details class="' + cls + '"><summary>' + esc(title) + '</summary><ul>'
      + items.map(function (w) {
        return '<li><span class="jp-ja">' + esc(w.expression) + '</span>'
          + (has(w.reading) ? ' <span class="jp-study-muted">' + esc(w.reading) + '</span>' : '')
          + (has(w.meaning) ? ' — ' + esc(w.meaning) : '')
          + (w.learned ? ' <span class="jp-ok" aria-label="Đã học">' + icon('circle-check') + '</span>' : '') + '</li>';
      }).join('') + '</ul></details>';
  }

  function toolsHtml(card, display) {
    var html = '';
    if (has(card.audioUrl)) {
      html += '<button type="button" class="jp-study-icon" data-jp-audio="' + esc(card.audioUrl)
        + '" aria-label="Nghe phát âm">' + icon('volume-2') + '</button>';
    }
    if (card.itemType === 'VOCAB' || card.itemType === 'KANJI') html += D().controlsHtml(display);
    return '<div class="jp-study-tools">' + html + '</div>';
  }

  /**
   * Owner 05/11: "N1 か N2" read like JLPT levels. Placeholders become word-type tags (N1 → Danh từ 1, V → Động từ…),
   * the pattern's own kana stay as they are, joined by "+" under the label Cấu trúc.
   */
  var SLOT = [[/^N(\d?)$/, 'Danh từ'], [/^V(?:る|ます|て|た|ない)?(\d?)$/, 'Động từ'], [/^Aい(\d?)$/, 'Tính từ い'],
    [/^Aな(\d?)$/, 'Tính từ な'], [/^A(\d?)$/, 'Tính từ'], [/^S(\d?)$/, 'Câu']];
  function slotLabel(p) {
    for (var i = 0; i < SLOT.length; i++) {
      var m = SLOT[i][0].exec(p);
      if (m) return SLOT[i][1] + (m[1] ? ' ' + m[1] : '');
    }
    return null;
  }
  function chipsHtml(text) {
    if (!has(text)) return '';
    var parts = String(text).split(/\s*\+\s*|\s+/).filter(has);
    return '<div class="jp-study-formula"><span class="jp-study-label">Cấu trúc</span><div class="jp-study-chips">'
      + parts.map(function (p) {
        var slot = slotLabel(p);
        return slot ? '<span class="jp-chip jp-chip--slot">' + esc(slot) + '</span>'
          : '<span class="jp-chip jp-chip--ja jp-ja">' + esc(p) + '</span>';
      }).join('<span class="jp-study-plus" aria-hidden="true">+</span>') + '</div></div>';
  }

  function icon(name) {
    return root && root.JpathNav && root.JpathNav.iconHtml ? root.JpathNav.iconHtml(name)
      : '<svg class="jp-i" aria-hidden="true"><use href="/images/icons/jpath-icons.svg#i-' + name + '"></use></svg>';
  }

  function kanjiReadingsHtml(k, strokeCount) {
    var onList = list(k.readingsOn).filter(has);
    var kunList = list(k.readingsKun).filter(has);
    var levels = list(k.levelReadings);
    var groups = [];
    if (onList.length) {
      groups.push('<span class="jp-study-reading-group"><span class="jp-study-reading-tag">On</span><span class="jp-ja">'
        + onList.map(function (r) { return F().kanjiReadingHtml(r, levels); }).join('、') + '</span></span>');
    }
    if (kunList.length) {
      groups.push('<span class="jp-study-reading-group"><span class="jp-study-reading-tag">Kun</span><span class="jp-ja">'
        + kunList.map(function (r) { return F().kanjiReadingHtml(r, levels); }).join('、') + '</span></span>');
    }
    if (strokeCount !== null && strokeCount !== undefined && !isNaN(Number(strokeCount))) {
      groups.push('<span class="jp-study-strokes">' + esc(Number(strokeCount)) + ' nét</span>');
    }
    return groups.length ? '<div class="jp-study-readings">' + groups.join('') + '</div>' : '';
  }

  function headChips(card, flippedOrNew) {
    var html = '';
    if (card.notebook && card.kind !== 'NEW') html += '<span class="jp-study-flag jp-study-flag--notebook">Sổ tay điểm yếu</span>';
    if (card.hard && flippedOrNew) html += '<span class="jp-study-flag jp-study-flag--hard">Khó nhớ</span>';
    return html ? '<div class="jp-study-flags">' + html + '</div>' : '';
  }

  function extrasWrap(html, forceContainer) {
    if (!html && !forceContainer) return '';
    return '<div class="jp-study-extras">' + (html || '') + '</div>';
  }

  function vocabBody(card, flipped) {
    var word = line('jp-study-word jp-ja', esc(card.expression));
    var reading = line('jp-study-reading jp-ja', esc(card.reading));
    var romaji = has(card.romaji) ? '<div class="jp-romaji">' + esc(card.romaji) + '</div>' : '';
    var meaning = line('jp-study-meaning', esc(card.meaning));
    if (card.kind === 'RETYPE') return '<div class="jp-study-core">' + word + meaning + '</div>';
    if (!flipped) {
      var topicFront = card.kind === 'NEW' && has(card.topic)
        ? '<div class="jp-study-label">Chủ đề · ' + esc(card.topic) + '</div>' : '';
      return '<div class="jp-study-core">' + topicFront + word + '</div>';
    }
    if (card.kind === 'NEW') {
      var html = '';
      if (has(card.meaning)) html += '<div class="jp-study-label">Ý nghĩa</div>' + meaning;
      if (has(card.topic)) html += '<div class="jp-study-label">Chủ đề · ' + esc(card.topic) + '</div>';
      return '<div class="jp-study-core">' + html + word + reading + romaji + '</div>'
        + extrasWrap(examplesHtml(card) + mnemonicHtml(card), true);
    }
    var conf = card.hard ? wordListHtml(card.confusables, 'Dễ nhầm với', 'jp-study-confusables') : '';
    return '<div class="jp-study-core">' + word + reading + romaji + meaning + '</div>'
      + extrasWrap(examplesHtml(card) + mnemonicHtml(card) + conf, true);
  }

  function kanjiBody(card, flipped) {
    var k = card.kanji || {};
    var glyph = line('jp-study-glyph jp-kanji', esc(card.expression));
    if (!flipped) return '<div class="jp-study-core">' + glyph + '</div>';
    var html = glyph;
    if (has(k.hanViet)) html += '<div class="jp-study-hanviet">' + esc(String(k.hanViet).toUpperCase()) + '</div>';
    html += line('jp-study-meaning', esc(card.meaning));
    html += kanjiReadingsHtml(k, k.strokeCount);
    var extras = wordListHtml(card.compounds, 'Từ ghép', 'jp-study-compounds')
      + examplesHtml(card) + mnemonicHtml(card)
      + (card.hard ? wordListHtml(card.confusables, 'Dễ nhầm với', 'jp-study-confusables') : '');
    return '<div class="jp-study-core">' + html + '</div>' + extrasWrap(extras, true);
  }

  function grammarBody(card) {
    var g = card.grammar || {};
    var html = line('jp-study-word jp-ja', esc(has(g.pattern) ? g.pattern : card.expression));
    html += line('jp-study-meaning', esc(card.meaning));
    html += chipsHtml(g.formation);
    html += line('jp-study-usage', esc(g.usage));
    html += line('jp-study-muted', esc(g.nuance));
    return '<div class="jp-study-core">' + html + '</div>' + extrasWrap(examplesHtml(card) + mnemonicHtml(card), true);
  }

  function kanaBody(card, flipped) {
    var k = card.kana || {};
    var glyph = line('jp-study-glyph jp-kanji', esc(has(k.kana) ? k.kana : card.expression));
    if (!flipped) return '<div class="jp-study-core">' + glyph + '</div>';
    var romaji = has(k.romaji) ? k.romaji : card.romaji;
    return '<div class="jp-study-core">' + glyph + line('jp-study-reading', esc(romaji)) + '</div>'
      + extrasWrap(mnemonicHtml(card));
  }

  function retypeHtml(state) {
    var r = state && state.retype;
    var answered = !!r;
    var html = '<form class="jp-retype" data-jp-retype>'
      + '<input class="jp-input jp-ja" type="text" name="typed" autocomplete="off" autocapitalize="off" spellcheck="false"'
      + ' lang="ja" aria-label="Gõ cách đọc"' + (answered ? ' disabled' : '') + ' value="' + esc(r && r.typed) + '">'
      + '<button type="submit" class="jp-btn jp-btn--primary"' + (answered ? ' disabled' : '') + '>Kiểm tra</button>'
      + '</form><p class="jp-retype-msg' + (r && r.verdict ? ' jp-retype-msg--' + esc(r.verdict) : '')
      + '" role="status" aria-live="polite">' + esc(r && r.message) + '</p>';
    return html;
  }

  function cardHtml(card, display, flipped, state) {
    if (!card) return '';
    if (isCheck(card)) {
      return '<div class="jp-study-card jp-study-card--check"><p class="jp-study-muted" role="status">Đang mở bài kiểm tra…</p></div>';
    }
    var type = card.itemType;
    var flippable = isFlippable(card);
    var isFlipped = !!flipped;
    var everFlipped = !!(isFlipped || (state && state.flippedOnce));
    var flipCount = state && typeof state.flipCount === 'number' ? state.flipCount : (isFlipped ? 1 : 0);
    var body;
    if (type === 'KANJI') body = kanjiBody(card, isFlipped);
    else if (type === 'GRAMMAR') body = grammarBody(card);
    else if (type === 'KANA') body = kanaBody(card, isFlipped);
    else body = vocabBody(card, isFlipped);
    if (card.kind === 'RETYPE') body += retypeHtml(state);
    var attrs = ' data-jp-code="' + esc(card.code) + '"';
    if (flippable) {
      attrs += ' data-jp-flippable="true" data-flip-state="' + (isFlipped ? 'back' : 'front') + '"';
      if (flipCount > 0 && (!state || state.justFlipped !== false)) {
        attrs += ' data-flip-anim="' + (isFlipped ? 'to-back' : 'to-front') + '"';
      }
    }
    if (has(card.code) && has(type)) {
      attrs += ' data-jpath-report="' + attrJson({ targetType: type, targetCode: card.code, pageContext: PAGE_CONTEXT }) + '"';
      if (type === 'VOCAB' || type === 'KANJI') attrs += ' data-jpath-writing="' + attrJson({ itemType: type, code: card.code }) + '"';
    }
    // F64 (owner 11/11): "Đã biết" on every new card of Từ vựng, Kanji and Ngữ pháp (one question decides)
    var SKIP_ASK = { VOCAB: 'Đã biết từ này?', KANJI: 'Đã biết chữ này?', GRAMMAR: 'Đã biết mẫu này?' };
    var skip = card.kind === 'NEW' && SKIP_ASK[type]
      ? '<p class="jp-study-skip">' + SKIP_ASK[type] + ' <button type="button" class="jp-link-btn" data-jp-skip>Bỏ qua (kiểm 1 câu)</button></p>'
      : '';
    var kindCls = esc(String(card.kind || '').toLowerCase());
    var stateCls = flippable ? (isFlipped ? ' jp-study-card--flipped' : ' jp-study-card--front') : '';
    var typeCls = has(type) ? ' jp-study-card--type-' + esc(String(type).toLowerCase()) : '';
    var backWhat = type === 'KANA' ? 'cách đọc' : 'nghĩa';
    var faceTag = flippable
      ? (isFlipped
        ? '<span class="jp-study-face-tag jp-study-face-tag--back" data-label="Mặt sau · Chạm vào thẻ hoặc nhấn phím Space để xem lại mặt trước" aria-hidden="true"></span>'
        : '<span class="jp-study-face-tag jp-study-face-tag--front" data-label="Mặt trước · Chạm vào thẻ hoặc nhấn phím Space để lật xem ' + backWhat + '" aria-hidden="true"></span>')
      : '';
    var flipCue = '';
    var mImg = '/images/mascots/kitsu/kitsu-thinking-30.webp';
    var mSpeech = 'Thử nhớ lại xem nào!';
    var mMood = 'think';
    var rt = state && state.retype;
    if (card.kind === 'RETYPE') {
      if (rt && (rt.verdict === 'EXACT' || rt.verdict === 'MINOR')) {
        mImg = '/images/mascots/kitsu/kitsu-cheer-30.webp'; mSpeech = 'Giỏi lắm, chuẩn rồi!'; mMood = 'ok';
      } else if (rt && rt.verdict === 'WRONG') {
        mImg = '/images/mascots/kitsu/kitsu-confused-30.webp'; mSpeech = 'Chú ý cách đọc nhé!'; mMood = 'bad';
      } else {
        mImg = '/images/mascots/kitsu/kitsu-thinking-30.webp'; mSpeech = 'Gõ lại cách đọc nào!'; mMood = 'think';
      }
    } else if (card.kind === 'NEW') {
      if (isFlipped) {
        mImg = '/images/mascots/kitsu/kitsu-cheer-30.webp'; mSpeech = 'Cùng ghi nhớ mục mới nhé!'; mMood = 'flip';
      } else {
        mImg = '/images/mascots/kitsu/kitsu-encouraging.webp'; mSpeech = 'Chạm thẻ để xem ' + backWhat + ' nhé!'; mMood = 'encourage';
      }
    } else if (isFlipped) {
      mImg = '/images/mascots/kitsu/kitsu-cheer-30.webp'; mSpeech = 'Đối chiếu đáp án nhé!'; mMood = 'flip';
    }
    var mascot = '<div class="jp-study-mascot jp-study-mascot--' + mMood + '" aria-hidden="true">'
      + '<img class="jp-study-mascot__img" src="' + mImg + '" alt="" width="76" height="76" loading="eager">'
      + '<span class="jp-study-mascot__bubble" data-speech="' + esc(mSpeech) + '"></span></div>';
    return '<article class="jp-study-card jp-study-card--' + kindCls + stateCls + typeCls + '"' + attrs + '>'
      + '<div class="jp-study-card__top">' + mascot + headChips(card, everFlipped) + toolsHtml(card, display) + '</div>'
      + '<div class="jp-study-face">' + faceTag + body + flipCue + '</div>'
      + '</article>' + skip;
  }

  // ---- buttons --------------------------------------------------------------------------------------------

  function hint(text) { return '<span class="jp-key-hint">' + esc(text) + '</span>'; }

  function rateButton(rating, index, interval) {
    return '<button type="button" class="jp-rate jp-rate--' + rating.toLowerCase() + '" data-jp-rate="' + rating + '">'
      + '<span class="jp-rate__label">' + esc(RATING_LABEL[rating]) + '</span>'
      + (interval !== null ? '<span class="jp-rate__ivl">' + esc(interval) + '</span>' : '')
      + hint('phím ' + (index + 1)) + '</button>';
  }

  function bar(cls, inner) { return '<div class="jp-rate-bar ' + cls + '">' + inner + '</div>'; }

  function nextButton() {
    return bar('jp-rate-bar--next', '<button type="button" class="jp-rate jp-rate--next" data-jp-next>'
      + '<span class="jp-rate__label">Tiếp</span>' + hint('phím Enter') + '</button>');
  }

  function flipButtonHtml(flipped) {
    var label = flipped ? 'Xem mặt trước (Space)' : 'Lật thẻ (Space)';
    var ico = '<svg class="jp-i jp-i--morph-flip" viewBox="0 0 20 20" width="16" height="16" fill="none" aria-hidden="true">'
      + '<path d="M4 10a6 6 0 0 1 10.2-4.2L16 7.5M16 4v3.5h-3.5M16 10a6 6 0 0 1-10.2 4.2L4 12.5M4 16v-3.5h3.5" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"/></svg>';
    return '<button type="button" class="jp-rate jp-rate--flip" data-jp-flip>'
      + ico + '<span class="jp-rate__label">' + esc(label) + '</span></button>';
  }

  function ratingButtonsHtml(card, flipped, state) {
    if (!card || isCheck(card)) return '';
    if (card.kind === 'RETYPE') return state && state.retype && state.retype.done ? nextButton() : '';
    if (card.itemType === 'GRAMMAR') return nextButton();
    var everFlipped = !!(flipped || (state && state.flippedOnce));
    if (!everFlipped) {
      return bar('jp-rate-bar--flip', flipButtonHtml(false));
    }
    if (card.kind === 'NEW') {
      return '<div class="jp-rate-stack">' + bar('jp-rate-bar--two', RATINGS.NEW.map(function (r, i) { return rateButton(r, i, null); }).join('')) + '</div>';
    }
    var days = card.intervalDays || {};
    return '<div class="jp-rate-stack">' + bar('jp-rate-bar--four', RATINGS.REVIEW.map(function (r, i) {
      return rateButton(r, i, intervalLabel(r, days[r]));
    }).join('')) + '</div>';
  }

  // ---- step bar -------------------------------------------------------------------------------------------

  function stepsHtml(steps, minutesLeft, tab) {
    var items = list(steps).filter(function (s) { return s && STEP_LABEL[s.key]; });
    var done = 0;
    var total = 0;
    var parts = items.map(function (s, idx) {
      var label = s.key === 'VOCAB' && tab && tab !== 'COMBINED' ? SPLIT_VOCAB_LABEL : STEP_LABEL[s.key];
      var d = Number(s.done) || 0;
      var t = Number(s.total) || 0;
      done += Math.min(d, t);
      total += t;
      var countStr = s.complete ? String(t) : (d + '/' + t);
      var dotIcon = s.complete
        ? '<svg class="jp-step__check" viewBox="0 0 16 16" fill="none" aria-hidden="true"><path d="M3.5 8.5L6.5 11.5L12.5 4.5" stroke="currentColor" stroke-width="2.3" stroke-linecap="round" stroke-linejoin="round"/></svg>'
        : '';
      var dot = '<span class="jp-step__dot" data-step-num="' + (idx + 1) + '" aria-hidden="true">' + dotIcon + '</span>';
      var sep = idx < items.length - 1
        ? '<span class="jp-step-sep' + (s.complete ? ' jp-step-sep--done' : '') + '" aria-hidden="true"> → </span>'
        : '';
      return '<span class="jp-step' + (s.complete ? ' jp-step--done' : '') + (s.active ? ' jp-step--active' : '') + '"'
        + (s.active ? ' aria-current="step"' : '') + '>' + dot
        + '<span class="jp-step__text"><span class="jp-step__name">' + esc(label) + '</span> <span class="jp-step__count">'
        + esc(countStr) + '</span></span></span>' + sep;
    });
    var pct = total > 0 ? Math.round(done * 100 / total) : 0;
    var minutes = Math.max(0, Math.round(Number(minutesLeft) || 0));
    return '<div class="jp-study-steps"><p class="jp-study-steps__line">'
      + parts.join('')
      + '<span class="jp-study-steps__left"> · ~' + minutes + ' phút còn lại</span></p>'
      + '<div class="jp-study-progress" role="progressbar" aria-label="Tiến độ lượt học" aria-valuemin="0"'
      + ' aria-valuemax="100" aria-valuenow="' + pct + '"><span style="width:' + pct + '%"></span></div></div>';
  }

  // ---- keys -----------------------------------------------------------------------------------------------

  function keyAction(key, ctx) {
    var c = ctx || {};
    var card = c.card;
    if (!card || isCheck(card) || c.inputFocused || c.modifier) return null;
    if (key === 'f' || key === 'F') return { type: 'TOGGLE_FURIGANA' };
    if (key === 'a' || key === 'A') return has(card.audioUrl) ? { type: 'AUDIO' } : null;
    if (key === 'Enter') {
      if (card.kind === 'RETYPE') return c.retypeDone ? { type: 'SUBMIT' } : null;
      return card.itemType === 'GRAMMAR' ? { type: 'SUBMIT' } : null;
    }
    if (card.itemType === 'GRAMMAR' || card.kind === 'RETYPE') return null;
    if (key === ' ' || key === 'Spacebar') {
      return isFlippable(card) ? { type: 'FLIP' } : null;
    }
    var n = /^[1-4]$/.test(String(key)) ? Number(key) - 1 : -1;
    if (n < 0) return null;
    var everFlipped = !!(c.flipped || c.flippedOnce);
    var choices = !everFlipped ? [] : (card.kind === 'NEW' ? RATINGS.NEW : RATINGS.REVIEW);
    return n < choices.length ? { type: 'RATE', rating: choices[n] } : null;
  }

  // ---- display controls -----------------------------------------------------------------------------------

  function controlPatch(display, control, font) {
    var d = Object.assign({}, display || {});
    var patch = null;
    if (control === 'furigana') {
      d.furiganaHiddenAll = !d.furiganaHiddenAll;
      patch = { furiganaHiddenAll: d.furiganaHiddenAll };
    } else if (control === 'romaji') {
      d.romajiOn = !D().romajiOn(d);
      patch = { romajiOn: d.romajiOn };
    } else if (control === 'font' && (font === 'S' || font === 'M' || font === 'L')) {
      d.fontSize = font;
      patch = { fontSize: font };
    }
    return { display: d, patch: patch };
  }

  function bindControls(el, options) {
    var o = options || {};
    var state = { display: Object.assign({}, o.display || {}) };
    el.addEventListener('click', function (ev) {
      var t = ev && ev.target;
      var btn = t && typeof t.closest === 'function' ? t.closest('[data-jp-control]') : null;
      if (!btn) return;
      var r = controlPatch(state.display, btn.getAttribute('data-jp-control'), btn.getAttribute('data-jp-font'));
      if (!r.patch) return;
      state.display = r.display;
      if (o.saver) o.saver.save(r.patch);
      if (typeof o.onChange === 'function') o.onChange(Object.assign({}, state.display));
    });
    return {
      get: function () { return Object.assign({}, state.display); },
      set: function (d) { state.display = Object.assign({}, d || {}); }
    };
  }

  // ---- flow (pure) ----------------------------------------------------------------------------------------

  var DONE_URL = '/learning?saved=1'; // owner 05/11: no summary page
  var MAX_REPEATS = 2;
  var DAY_TABS = { VOCAB: true, KANJI: true, GRAMMAR: true, KANA: true, SKILL: true };
  var STEP_TAB = { VOCAB: 'VOCAB', KANJI: 'KANJI', GRAMMAR: 'GRAMMAR', KANA: 'KANA', SKILL_QUIZ: 'SKILL' };
  var OFFLINE_TEXT = 'Mất mạng — tiến độ được giữ tạm trên máy này và sẽ gửi khi có mạng.';
  var NEED_NETWORK_TEXT = 'Cần có mạng để học tiếp.';
  var CHECK_NEEDS_NETWORK_TEXT = 'Cần có mạng để làm micro-check.';
  var SWIPE_HINT_TEXT = 'Vuốt phải = Nhớ rồi · Vuốt trái = Chưa nhớ';

  function repeatKey(c) { return c.kind + '|' + c.itemType + '|' + c.code; }

  function initFlow(session) {
    var s = session || {};
    var cards = (s.current ? [s.current] : []).concat(list(s.upcoming)).filter(Boolean);
    var repeats = {};
    cards.forEach(function (c) { if (c.repeat) repeats[repeatKey(c)] = (repeats[repeatKey(c)] || 0) + 1; });
    var position = Number(s.position) || 0;
    var total = Number(s.total);
    return { session: s, sessionId: s.sessionId, tab: s.tab, status: s.status, cards: cards, position: position,
      total: isNaN(total) ? position + cards.length : total, flipped: false, flippedOnce: false, flipCount: 0,
      retype: null, repeats: repeats, lastType: null, deferred: null };
  }

  function withFlow(flow, changes) { return Object.assign({}, flow, changes); }

  function checkTab(flow) {
    if (DAY_TABS[flow.tab]) return flow.tab;
    var steps = list(flow.session && flow.session.steps);
    for (var i = 0; i < steps.length; i++) {
      if (steps[i] && steps[i].active && STEP_TAB[steps[i].key]) return STEP_TAB[steps[i].key];
    }
    return DAY_TABS[flow.lastType] ? flow.lastType : 'VOCAB';
  }

  function stepEffect(flow) {
    var c = flow.cards[0];
    if (!c) return flow.position < flow.total && flow.status !== 'COMPLETED' ? { reload: true } : { navigate: DONE_URL };
    if (!isCheck(c)) return null;
    if (has(c.checkToken)) {
      return { navigate: '/learning/jlpt/check/' + encodeURIComponent(c.checkToken) + '?session=' + flow.sessionId };
    }
    return { issueCheck: { kind: c.checkKind || c.kind, tab: checkTab(flow), sessionId: flow.sessionId } };
  }

  function cardStepKey(c) {
    if (!c) return null;
    if (c.kind === 'SKILL_QUIZ' || c.checkKind === 'SKILL_QUIZ') return 'SKILL_QUIZ';
    if (c.kind === 'REVIEW' || c.kind === 'RETYPE' || c.checkKind === 'GRAMMAR_REVIEW') return 'REVIEW';
    return c.itemType || null;
  }

  function bumpSteps(session, key, dDone, dTotal, nextActiveKey) {
    if (!session || !Array.isArray(session.steps) || !key) return session;
    var changed = false;
    var steps = session.steps.map(function (st) {
      if (!st || st.key !== key) return st;
      changed = true;
      var total = Math.max(0, (Number(st.total) || 0) + (dTotal || 0));
      var done = Math.min(total, Math.max(0, (Number(st.done) || 0) + (dDone || 0)));
      var complete = total > 0 && done >= total;
      var active = complete ? false : st.active;
      return Object.assign({}, st, { done: done, total: total, complete: complete, active: !!active });
    });
    if (!changed) return session;
    if (nextActiveKey && nextActiveKey !== key) {
      var prevDone = steps.some(function (st) { return st && st.key === key && st.complete; });
      if (prevDone) {
        steps = steps.map(function (st) {
          return st ? Object.assign({}, st, { active: st.key === nextActiveKey && !st.complete }) : st;
        });
      }
    }
    return Object.assign({}, session, { steps: steps });
  }

  /** Queues a repeat of {@code c} like the server (at most two per item): shown locally only when the window
   *  already reaches the end of the queue, otherwise the next server sync brings it. */
  function withRepeat(flow, c) {
    var key = repeatKey(c);
    var count = flow.repeats[key] || 0;
    if (count >= MAX_REPEATS) return flow;
    var repeats = Object.assign({}, flow.repeats);
    repeats[key] = count + 1;
    var cards = flow.cards.slice();
    var rep = Object.assign({}, c, { repeat: true });
    // F149: same place as the server (JpathQueueBuilder.insertRepeatBeforeCheck) — before the next mini-check of
    // this tab, else at the end; a different place made the next event fail the server's position check
    // a check card carries no tab: it closes the run of cards before it, so its tab is that run's item type
    var checkAt = -1;
    var runType = c.itemType;
    for (var i = 1; i < cards.length; i++) {
      var k = cards[i];
      if (!k) continue;
      if (k.kind === 'MICRO_CHECK') {
        if ((k.itemType || runType) === c.itemType) { checkAt = i; break; }
      } else if (k.itemType) {
        runType = k.itemType;
      }
    }
    if (checkAt > 0) cards.splice(checkAt, 0, rep);
    else if (flow.position + cards.length >= flow.total) cards.push(rep);
    var session = bumpSteps(flow.session, cardStepKey(c), 0, 1);
    return withFlow(flow, { session: session, cards: cards, total: flow.total + 1, repeats: repeats });
  }

  function advance(flow) {
    var c = flow.cards[0];
    var cards = flow.cards.slice(1);
    var session = bumpSteps(flow.session, cardStepKey(c), 1, 0, cardStepKey(cards[0]));
    var next = withFlow(flow, { session: session, cards: cards, position: flow.position + 1, flipped: false,
      flippedOnce: false, flipCount: 0, retype: null, lastType: c && c.itemType ? c.itemType : flow.lastType });
    var effect = stepEffect(next);
    return effect ? { flow: next, effect: effect } : { flow: next };
  }

  function makeEvent(flow, c, action, rating, typed, elapsedMs, deps) {
    var ms = Number(elapsedMs);
    return { clientEventId: deps.uuid(), itemType: c.itemType, itemCode: c.code, action: action,
      rating: rating || null, typed: typed === undefined ? null : typed,
      elapsedMs: isNaN(ms) || elapsedMs === null || elapsedMs === undefined ? null : Math.max(0, Math.round(ms)),
      position: flow.position };
  }

  function emit(result, event) { result.event = event; return result; }

  function reduce(flow, action, deps) {
    var a = action || {};
    var c = flow.cards[0];
    switch (a.type) {
      case 'FLIP':
        return isFlippable(c)
          ? { flow: withFlow(flow, { flipped: !flow.flipped, flippedOnce: true, flipCount: (flow.flipCount || 0) + 1 }) }
          : { flow: flow };
      case 'RATE': {
        if (!c || a.code !== c.code || isCheck(c) || c.itemType === 'GRAMMAR' || c.kind === 'RETYPE') return { flow: flow };
        var allowed = c.kind === 'NEW' ? RATINGS.NEW : RATINGS.REVIEW;
        if (allowed.indexOf(a.rating) < 0) return { flow: flow };
        var ev = makeEvent(flow, c, c.kind === 'NEW' ? 'FIRST_RATE' : 'REVIEW_RATE', a.rating, null, a.elapsedMs, deps);
        var f = a.rating === 'FORGOT' ? withRepeat(flow, c) : flow;
        return emit(advance(f), ev);
      }
      case 'NEXT': {
        if (!c) return { flow: flow };
        if (c.kind === 'RETYPE' && flow.retype && flow.retype.done) {
          if (flow.deferred) {
            var synced = initFlow(flow.deferred);
            var eff = stepEffect(synced);
            return eff ? { flow: synced, effect: eff } : { flow: synced };
          }
          return advance(flow);
        }
        if (c.itemType === 'GRAMMAR' && c.kind === 'NEW') {
          return emit(advance(flow), makeEvent(flow, c, 'FIRST_RATE', null, null, a.elapsedMs, deps));
        }
        return { flow: flow };
      }
      case 'RETYPE_SUBMIT': {
        var typed = typeof a.typed === 'string' ? a.typed.trim() : '';
        if (!c || c.kind !== 'RETYPE' || flow.retype || !typed) return { flow: flow };
        var rev = makeEvent(flow, c, 'RETYPE', null, typed, a.elapsedMs, deps);
        return { flow: withFlow(flow, { retype: { code: c.code, typed: typed, eventId: rev.clientEventId, done: true,
          verdict: null, expected: null, message: '' } }), event: rev };
      }
      case 'RETYPE_RESULT': {
        if (!flow.retype || flow.retype.verdict) return { flow: flow };
        var o = a.outcome || null;
        var verdict = o && o.verdict ? o.verdict : null;
        var expected = o && has(o.expected) ? o.expected : null;
        var rf = withFlow(flow, { retype: Object.assign({}, flow.retype, { verdict: verdict, expected: expected,
          message: retypeMessage(verdict, expected) }) });
        if (verdict === 'WRONG' && c && c.kind === 'RETYPE') rf = withRepeat(rf, c);
        return { flow: rf };
      }
      case 'SERVER_SYNC': {
        if (!a.session || Number(a.pending) > 0) return { flow: flow };
        if (flow.retype) return { flow: withFlow(flow, { deferred: a.session }) };
        var nf = initFlow(a.session);
        var cur = nf.cards[0];
        if (c && cur && nf.position === flow.position && cur.code === c.code && cur.kind === c.kind) {
          nf.flipped = flow.flipped;
          nf.flippedOnce = flow.flippedOnce;
          nf.flipCount = flow.flipCount;
        }
        nf.lastType = flow.lastType;
        if (nf.status === 'COMPLETED' || !cur) return { flow: nf, effect: stepEffect(nf) || { navigate: DONE_URL } };
        var se = nf.position !== flow.position ? stepEffect(nf) : null;
        return se ? { flow: nf, effect: se } : { flow: nf };
      }
      case 'DROPPED':
        return { flow: flow, effect: { reload: true } };
      default:
        return { flow: flow };
    }
  }

  // ---- gestures, hints, messages ---------------------------------------------------------------------------

  function classifySwipe(dx, dy, ms) {
    if (Math.abs(dx) < 60 || Math.abs(dy) > 40 || ms > 800) return null;
    return dx > 0 ? 'RIGHT' : 'LEFT';
  }

  function swipeAllowed(ctx) {
    var c = ctx || {};
    if (c.pointerType !== 'touch') return false;
    return !(typeof c.selectionText === 'string' && c.selectionText.trim() !== '');
  }

  function swipeRating(c, gesture) {
    if (!c || c.kind !== 'NEW' || isCheck(c) || c.itemType === 'GRAMMAR') return null;
    if (gesture === 'RIGHT') return 'REMEMBERED';
    if (gesture === 'LEFT') return 'FORGOT';
    return null;
  }

  function newCardEffect(c, display) {
    if (!c || c.kind !== 'NEW' || !has(c.audioUrl) || !(display && display.autoplayAudio)) return null;
    return { play: c.audioUrl };
  }

  function retypeMessage(verdict, expected) {
    if (verdict === 'EXACT') return 'Chính xác!';
    if (verdict === 'MINOR') return 'Gần đúng — chú ý ん, っ và trường âm. Đáp án: ' + (expected || '');
    if (verdict === 'WRONG') return 'Chưa đúng. Đáp án: ' + (expected || '');
    return has(expected) ? 'Đáp án: ' + expected + ' · sẽ chấm khi có mạng' : 'Sẽ chấm khi có mạng';
  }

  function skipMessage(correct) {
    return correct ? 'Đúng rồi — đã tính mục này vào tiến độ.' : 'Chưa đúng — mục này vẫn ở hàng học.';
  }

  function shouldShowSwipeHint(display, coarse) { return !!coarse && !(display && display.swipeHintDone); }

  function swipeHintHtml() {
    return '<div class="jp-swipe-hint" role="note"><span>' + esc(SWIPE_HINT_TEXT) + '</span>'
      + '<button type="button" class="jp-btn" data-jp-swipe-hint-done>Đã hiểu</button></div>';
  }

  // ---- page -----------------------------------------------------------------------------------------------

  var RATE_GUARD_MS = 300;
  var LEAVE_WAIT_MS = 1500;

  function isTyping(doc) {
    var a = doc && doc.activeElement;
    if (!a) return false;
    var tag = String(a.tagName || '').toUpperCase();
    return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || !!a.isContentEditable;
  }

  function safeStorage() {
    try {
      var s = root.localStorage;
      if (s) { s.getItem('jpath.probe'); return s; }
    } catch (e) { /* private mode or blocked storage: the queue keeps events in memory */ }
    return null;
  }

  function coarsePointer() {
    try { return !!(root.matchMedia && root.matchMedia('(pointer: coarse)').matches); } catch (e) { return false; }
  }

  function mountPage(doc) {
    var d = doc || root.document;
    var el = d && d.getElementById('jpathStudyRoot');
    if (!el) return null;
    var sessionId = Number(el.getAttribute('data-session-id'));
    var api = root.JpathApi;
    var page = { el: el, doc: d, sessionId: sessionId, display: {}, flow: null, shownKey: null, shownAt: Date.now(),
      offline: root.navigator && root.navigator.onLine === false, blocked: false, notice: '', busy: false,
      checkFailed: null };
    var saver = D().createSaver({});
    page.saver = saver;
    page.controls = bindControls(el, { display: page.display, saver: saver, onChange: function (disp) {
      page.display = disp;
      draw(page);
    } });
    page.queue = root.JpathOfflineQueue.create({ sessionId: sessionId, storage: safeStorage(),
      send: function (batch) { return api.send('POST', BASE + '/sessions/' + sessionId + '/events', { events: batch }); },
      onChange: function (info) {
        if (info && info.dropped) dispatch(page, { type: 'DROPPED', n: info.dropped });
        else if (info && info.authRequired) setNotice(page, (info.error && info.error.message) || '');
      } });
    page.queue.attach(root);
    page.deps = { uuid: function () { return root.JpathOfflineQueue.uuid(root.crypto); }, now: Date.now };

    el.addEventListener('click', function (ev) { onClick(page, ev); });
    el.addEventListener('submit', function (ev) { onSubmit(page, ev); });
    el.addEventListener('pointerdown', function (ev) { onPointerDown(page, ev); });
    el.addEventListener('pointerup', function (ev) { onPointerUp(page, ev); });
    d.addEventListener('keydown', function (ev) { onKey(page, ev); });
    if (typeof root.addEventListener === 'function') {
      root.addEventListener('offline', function () { page.offline = true; draw(page); });
      root.addEventListener('online', function () {
        page.offline = false;
        draw(page);
        page.queue.flush().then(function (r) { afterFlush(page, r); }, function () {});
      });
      root.addEventListener('pagehide', function () { page.queue.flush().catch(function () {}); });
    }

    var settings = api.get(BASE + '/settings').then(function (s) { return (s && s.display) || {}; },
      function () { return {}; });
    var flushed = page.queue.pending().length > 0
      ? page.queue.flush().catch(function () { return null; }) : Promise.resolve(null);
    Promise.all([settings, flushed.then(function () { return api.get(BASE + '/sessions/' + sessionId); })])
      .then(function (res) {
        page.display = res[0];
        page.controls.set(page.display);
        start(page, res[1]);
      }, function (err) { fail(page, err); });
    return page;
  }

  function fail(page, err) {
    page.el.setAttribute('aria-busy', 'false');
    page.el.innerHTML = '<p class="jp-alert" role="alert">' + esc((err && err.message) || 'Không tải được lượt học.')
      + '</p><a class="jp-btn" href="/learning">Về lộ trình</a>';
  }

  function start(page, session) {
    page.flow = initFlow(session);
    draw(page);
    var effect = stepEffect(page.flow);
    if (effect) runEffect(page, effect);
  }

  function reload(page) {
    return root.JpathApi.get(BASE + '/sessions/' + page.sessionId).then(function (s) { start(page, s); },
      function (err) { setNotice(page, (err && err.message) || NEED_NETWORK_TEXT); });
  }

  function setNotice(page, text) {
    page.notice = text || '';
    var st = page.el.querySelector('[data-jp-status]');
    if (st) st.textContent = page.notice;
  }

  function dispatch(page, action) {
    if (!page.flow) return;
    var r = reduce(page.flow, action, page.deps);
    if (r.event) {
      if (!page.queue.enqueue(r.event)) {
        page.blocked = true;
        page.notice = NEED_NETWORK_TEXT;
        draw(page);
        return;
      }
    }
    var changed = r.flow !== page.flow;
    if (changed && action && action.type === 'FLIP') page.justFlipped = true;
    page.flow = r.flow;
    if (changed) draw(page);
    if (r.event) page.queue.flush().then(function (res) { afterFlush(page, res); }, function () {});
    if (r.effect) runEffect(page, r.effect);
  }

  function afterFlush(page, res) {
    if (!res || !page.flow) return;
    var retype = page.flow.retype;
    var batch = res.result;
    if (!batch) {
      if (retype && !retype.verdict && res.kept > 0) dispatch(page, { type: 'RETYPE_RESULT', outcome: null });
      return;
    }
    var outcomes = list(batch.outcomes);
    if (retype && !retype.verdict) {
      var mine = outcomes.filter(function (o) { return o && o.clientEventId === retype.eventId; })[0];
      if (mine) dispatch(page, { type: 'RETYPE_RESULT', outcome: mine });
    }
    if (outcomes.some(function (o) { return o && o.status === 'REJECTED'; })) {
      // events still queued were made on the stale queue: drop them, the reloaded session is the truth
      page.queue.clear();
      reload(page);
      return;
    }
    if (batch.session) dispatch(page, { type: 'SERVER_SYNC', session: batch.session, pending: page.queue.pending().length });
  }

  function leave(page, url) {
    var wait = new Promise(function (resolve) { setTimeout(resolve, LEAVE_WAIT_MS); });
    Promise.race([page.queue.flush().catch(function () {}), wait]).then(function () { root.location.assign(url); });
  }

  function runEffect(page, effect) {
    if (effect.navigate) leave(page, effect.navigate);
    else if (effect.reload) reload(page);
    else if (effect.issueCheck) issueCheck(page, effect.issueCheck);
    else if (effect.play) D().playAudio(effect.play);
  }

  function issueCheck(page, req) {
    if (page.busy) return;
    page.checkFailed = null;
    if (page.offline) { page.checkFailed = req; draw(page); return; }
    page.busy = true;
    page.queue.flush().then(function () {
      if (page.queue.pending().length) throw new Error(CHECK_NEEDS_NETWORK_TEXT);
      return root.JpathApi.send('POST', BASE + '/checks', req);
    }).then(function (check) {
      page.busy = false;
      if (!check || !check.token) throw new Error(CHECK_NEEDS_NETWORK_TEXT);
      root.location.assign('/learning/jlpt/check/' + encodeURIComponent(check.token) + '?session=' + page.sessionId);
    }).catch(function () {
      page.busy = false;
      page.checkFailed = req;
      draw(page);
    });
  }

  function skip(page) {
    var c = page.flow && page.flow.cards[0];
    if (!c || page.busy) return;
    if (page.offline) { setNotice(page, NEED_NETWORK_TEXT); return; }
    page.busy = true;
    var api = root.JpathApi;
    // SKIP_KNOWN on the current step: the server issues the session-bound SKIP_ONE check, so a right answer counts
    // the item and moves the session on (the list drawer's /known route is a free check that moves nothing).
    var ev = makeEvent(page.flow, c, 'SKIP_KNOWN', null, null, Date.now() - (page.shownAt || Date.now()), page.deps);
    page.queue.flush().then(function () {
      if (page.queue.pending().length) throw new Error(NEED_NETWORK_TEXT);
      return api.send('POST', BASE + '/sessions/' + page.sessionId + '/events', { events: [ev] });
    }).then(function (batch) {
      var res = list(batch && batch.outcomes).filter(function (o) { return o && o.clientEventId === ev.clientEventId; })[0];
      if (!res || res.status === 'REJECTED' || !res.checkToken) {
        page.busy = false;
        return reload(page).then(function () { return null; });
      }
      var slot = page.el.querySelector('[data-jp-inline-check]');
      if (!slot || !root.JpathCheck || typeof root.JpathCheck.mountInline !== 'function') {
        root.location.assign('/learning/jlpt/check/' + encodeURIComponent(res.checkToken) + '?session=' + page.sessionId);
        return null;
      }
      slot.hidden = false;
      return root.JpathCheck.mountInline(slot, res.checkToken, { embedded: true }).then(function (result) {
        return skipMessage(!!(result && result.passed));
      });
    }).then(function (message) {
      page.busy = false;
      if (message === null) return;
      return reload(page).then(function () { setNotice(page, message); });
    }).catch(function (err) {
      page.busy = false;
      setNotice(page, (err && err.message) || NEED_NETWORK_TEXT);
    });
  }

  function unavailable(c) {
    return !!c && !isCheck(c) && !has(c.expression) && !(c.kana && has(c.kana.kana))
      && !(c.grammar && has(c.grammar.pattern));
  }

  function draw(page) {
    var el = page.el;
    var f = page.flow;
    if (!f) return;
    var s = f.session || {};
    var c = f.cards[0];
    el.setAttribute('aria-busy', 'false');
    var key = c ? f.position + '|' + c.kind + '|' + c.code : 'none';
    var fresh = key !== page.shownKey;
    if (fresh) { page.shownKey = key; page.shownAt = Date.now(); }
    // A redraw of the same card (server sync) keeps what the learner is typing.
    var oldInput = fresh ? null : el.querySelector('.jp-retype input');
    var typing = oldInput && !oldInput.disabled ? { value: oldInput.value, focused: page.doc.activeElement === oldInput }
      : null;
    var banner = page.offline ? '<p class="jp-study-offline" role="status">' + esc(OFFLINE_TEXT) + '</p>' : '';
    if (!c) {
      el.innerHTML = banner + '<p class="jp-study-muted" role="status">Đã học xong lượt này.</p>';
      return;
    }
    var body;
    if (isCheck(c) && page.checkFailed) {
      body = '<div class="jp-study-card jp-study-card--check"><p class="jp-alert" role="alert">'
        + esc(CHECK_NEEDS_NETWORK_TEXT) + '</p><button type="button" class="jp-btn jp-btn--primary" data-jp-retry-check>'
        + 'Thử lại</button></div>';
    } else if (unavailable(c)) {
      body = '<article class="jp-study-card"><p class="jp-study-muted">Mục này đang được cập nhật nội dung.</p></article>';
    } else {
      body = cardHtml(c, page.display, f.flipped, { retype: f.retype, flippedOnce: f.flippedOnce, flipCount: f.flipCount, justFlipped: !!page.justFlipped });
      page.justFlipped = false;
    }
    var hint = c.kind === 'NEW' && swipeRating(c, 'RIGHT') && shouldShowSwipeHint(page.display, coarsePointer())
      ? swipeHintHtml() : '';
    el.innerHTML = banner + stepsHtml(s.steps, s.minutesLeft, s.tab)
      + '<div class="jp-study-main">' + body + '<div class="jp-study-inline-check"'
      + ' data-jp-inline-check hidden></div></div>' + hint
      + ratingButtonsHtml(c, f.flipped, { retype: f.retype, flippedOnce: f.flippedOnce, flipCount: f.flipCount })
      + '<p class="jp-study-status" role="status" aria-live="polite" data-jp-status>' + esc(page.notice) + '</p>';
    if (page.blocked) {
      Array.prototype.forEach.call(el.querySelectorAll('.jp-rate-bar button, .jp-retype button'), function (b) {
        b.disabled = true;
      });
    }
    D().applyDisplay(el, page.display);
    var cardEl = el.querySelector('.jp-study-card');
    if (cardEl) page.furigana = F().bindToggle(cardEl, !!page.display.furiganaHiddenAll);
    D().rendered(el, page.doc);
    var newInput = typing ? el.querySelector('.jp-retype input') : null;
    if (newInput) {
      newInput.value = typing.value;
      if (typing.focused && typeof newInput.focus === 'function') newInput.focus();
    }
    if (f.retype && f.retype.done) {
      var nextBtn = el.querySelector('[data-jp-next]');
      if (nextBtn && typeof nextBtn.focus === 'function') nextBtn.focus();
    } else if (fresh && c.kind === 'RETYPE') {
      var input = el.querySelector('.jp-retype input');
      if (input && typeof input.focus === 'function') input.focus();
    }
    if (fresh) {
      page.notice = '';
      var effect = newCardEffect(c, page.display);
      if (effect) runEffect(page, effect);
    }
  }

  function elapsed(page) { return Math.max(0, Date.now() - page.shownAt); }

  function current(page) { return page.flow && page.flow.cards[0]; }

  function rate(page, rating) {
    var c = current(page);
    if (!c || page.blocked || elapsed(page) < RATE_GUARD_MS) return;
    dispatch(page, { type: 'RATE', rating: rating, code: c.code, elapsedMs: elapsed(page) });
  }

  function next(page) {
    if (page.blocked) return;
    dispatch(page, { type: 'NEXT', elapsedMs: elapsed(page) });
  }

  function playCurrent(page) {
    var c = current(page);
    if (c && c.audioUrl) D().playAudio(c.audioUrl);
  }

  function toggleFurigana(page) {
    var r = controlPatch(page.display, 'furigana');
    page.display = r.display;
    page.controls.set(page.display);
    page.saver.save(r.patch);
    draw(page);
  }

  // Focus stays where it was: moving it onto a rating button would let a second Space rate the card.
  function flip(page) { dispatch(page, { type: 'FLIP' }); }

  function onClick(page, ev) {
    var t = ev.target;
    if (!t || typeof t.closest !== 'function') return;
    if (t.closest('[data-jp-audio]')) { playCurrent(page); return; }
    if (t.closest('[data-jp-flip]')) { flip(page); return; }
    var btn = t.closest('[data-jp-rate]');
    if (btn) { rate(page, btn.getAttribute('data-jp-rate')); return; }
    if (t.closest('[data-jp-next]')) { next(page); return; }
    if (t.closest('[data-jp-skip]')) { skip(page); return; }
    if (t.closest('[data-jp-retry-check]')) {
      var req = page.checkFailed;
      page.offline = root.navigator && root.navigator.onLine === false;
      if (req) issueCheck(page, req);
      return;
    }
    if (t.closest('[data-jp-swipe-hint-done]')) {
      page.display = Object.assign({}, page.display, { swipeHintDone: true });
      page.controls.set(page.display);
      page.saver.save({ swipeHintDone: true });
      draw(page);
      return;
    }
    var cardEl = t.closest('.jp-study-card[data-jp-flippable="true"]');
    if (cardEl && !t.closest('button, a, input, textarea, select, details, summary, [data-jp-control], [data-jp-retype], ruby')) {
      var sel = '';
      try { sel = String(page.doc && page.doc.getSelection ? page.doc.getSelection() : ''); } catch (e) { sel = ''; }
      if (!sel || !sel.trim()) flip(page);
    }
  }

  function onSubmit(page, ev) {
    var form = ev.target && typeof ev.target.closest === 'function' ? ev.target.closest('[data-jp-retype]') : null;
    if (!form) return;
    ev.preventDefault();
    if (page.blocked) return;
    var input = form.querySelector('input');
    dispatch(page, { type: 'RETYPE_SUBMIT', typed: input ? input.value : '', elapsedMs: elapsed(page) });
  }

  function onPointerDown(page, ev) {
    var t = ev.target;
    if (ev.pointerType !== 'touch' || !t || typeof t.closest !== 'function' || !t.closest('.jp-study-card')) {
      page.swipe = null;
      return;
    }
    page.swipe = { x: ev.clientX, y: ev.clientY, t: Date.now() };
  }

  function onPointerUp(page, ev) {
    var s = page.swipe;
    page.swipe = null;
    if (!s) return;
    var sel = '';
    try { sel = String(page.doc.getSelection ? page.doc.getSelection() : ''); } catch (e) { sel = ''; }
    if (!swipeAllowed({ selectionText: sel, pointerType: ev.pointerType })) return;
    var r = swipeRating(current(page), classifySwipe(ev.clientX - s.x, ev.clientY - s.y, Date.now() - s.t));
    if (r) rate(page, r);
  }

  function onKey(page, ev) {
    if (ev.repeat) return;
    if (root.JpathDialog && root.JpathDialog.isOpen && root.JpathDialog.isOpen()) return;
    var f = page.flow;
    var act = keyAction(ev.key, { card: current(page), flipped: !!(f && f.flipped), flippedOnce: !!(f && f.flippedOnce),
      inputFocused: isTyping(page.doc), modifier: !!(ev.ctrlKey || ev.metaKey || ev.altKey),
      retypeDone: !!(f && f.retype && f.retype.done) });
    if (!act) return;
    ev.preventDefault();
    if (act.type === 'AUDIO') playCurrent(page);
    else if (act.type === 'FLIP') flip(page);
    else if (act.type === 'TOGGLE_FURIGANA') toggleFurigana(page);
    else if (act.type === 'RATE') rate(page, act.rating);
    else if (act.type === 'SUBMIT') next(page);
  }

  return { afterFlush: afterFlush, RATINGS: RATINGS, RATING_LABEL: RATING_LABEL, STEP_LABEL: STEP_LABEL, intervalLabel: intervalLabel,
    cardHtml: cardHtml, ratingButtonsHtml: ratingButtonsHtml, stepsHtml: stepsHtml, keyAction: keyAction,
    controlPatch: controlPatch, bindControls: bindControls, cardStepKey: cardStepKey, bumpSteps: bumpSteps, initFlow: initFlow, reduce: reduce, stepEffect: stepEffect,
    classifySwipe: classifySwipe, swipeAllowed: swipeAllowed, swipeRating: swipeRating, newCardEffect: newCardEffect,
    retypeMessage: retypeMessage, skipMessage: skipMessage, shouldShowSwipeHint: shouldShowSwipeHint,
    swipeHintHtml: swipeHintHtml, OFFLINE_TEXT: OFFLINE_TEXT, NEED_NETWORK_TEXT: NEED_NETWORK_TEXT,
    CHECK_NEEDS_NETWORK_TEXT: CHECK_NEEDS_NETWORK_TEXT, mountPage: mountPage };
});
