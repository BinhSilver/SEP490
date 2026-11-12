/* cpath-situation.js — Trang Học Tình Huống 4 Bước (situation.html):
 *   1. WORDS    — Từ và mẫu câu (thẻ lật qua lại, có nút nghe)
 *   2. LISTEN   — Nghe hội thoại từng câu (phát lần lượt, furigana/bản dịch theo display, rê chuột hiện dịch)
 *   3. ROLEPLAY — Nhập vai chọn câu của リン (1 trong 3 câu, kèm giải thích whyVi)
 *   4. SPEAK    — Luyện nói câu của リン (máy phát lời bên kia, nút ẩn chữ, chấm điểm từng câu, <70 nút Nói lại, nút Bỏ qua)
 * Xong cả 4 bước hiện nút "Nói chuyện tự do với AI" (aiCallUrl) và "Về lộ trình".
 *
 * [JLAS-W0108] F68 (chủ dự án 11/11) — Du lịch (sit.flow = 'LISTEN_SPEAK') chỉ nghe và nói:
 *   bước 1 (khoá WORDS giữ nguyên) = "Nghe & chọn nghĩa": nghe câu/từ của tình huống, chọn nghĩa, chữ Nhật hiện sau khi chọn;
 *   bước Nghe có nút "Chú thích" xổ ra từ + mẫu câu/ngữ pháp trong hội thoại; mặc định chữ Nhật + dịch, nút Romaji bật/tắt;
 *   gọi AI theo tình huống là luyện thêm, không bắt buộc.
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.CpathSituation = api;
  if (root && root.document && typeof module === 'undefined') {
    if (root.document.readyState === 'loading') {
      root.document.addEventListener('DOMContentLoaded', function () { api.mount(); });
    } else {
      api.mount();
    }
  }
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var STEPS_ORDER = ['WORDS', 'LISTEN', 'ROLEPLAY', 'SPEAK'];
  var STEP_TITLES = {
    WORDS: 'Từ & mẫu câu',
    LISTEN: 'Nghe hội thoại',
    ROLEPLAY: 'Nhập vai chọn câu',
    SPEAK: 'Luyện nói'
  };

  /** F49 (11/11): the learner's role name, from the lines (BA = グエン); リン when unknown. */
  var learnerName = 'リン';

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function list(v) { return Array.isArray(v) ? v : []; }

  // [JLAS-W0108] B2: spots drawn at 64px or less load the 128px copy
  function kitsuSmall(file) { return kitsuSrc(String(file).replace(/\.webp$/, '-128.webp')); }
  function kitsuSrc(file) {
    return '/images/mascots/kitsu/' + file;
  }

  function pageUrl(page, params) {
    var base = '/learning/comm/' + page;
    var q = [];
    if (params) {
      Object.keys(params).forEach(function (k) {
        if (params[k] != null && params[k] !== '') q.push(encodeURIComponent(k) + '=' + encodeURIComponent(params[k]));
      });
    }
    return q.length ? (base + '?' + q.join('&')) : base;
  }

  /* Hỗ trợ cả định dạng {漢字|かんじ} lẫn 漢字(かんじ) sang thẻ <ruby> */
  function toRubyHtml(text, showFurigana) {
    if (text == null) return '';
    var s = String(text);
    if (!showFurigana) {
      var stripped = s.replace(/\{([^|{}]+)\|([^{}]+)\}/g, '$1').replace(/([㐀-鿿々ヶ]+)\(([ぁ-ゖァ-ヺー]+)\)/g, '$1');
      return esc(stripped);
    }
    var normalized = s.replace(/\{([^|{}]+)\|([^{}]+)\}/g, '$1($2)');
    if (root && root.JpathFurigana && typeof root.JpathFurigana.toRuby === 'function') {
      return root.JpathFurigana.toRuby(normalized)
        .replace(/<\/ruby>\s+(?=<ruby>|[㐀-鿿々ヶぁ-ゖァ-ヺー])/g, '</ruby>');
    }
    var rx = /([㐀-鿿々ヶ]+)\(([ぁ-ゖァ-ヺー]+)\)/g;
    var out = '';
    var last = 0;
    var m;
    while ((m = rx.exec(normalized)) !== null) {
      out += esc(normalized.slice(last, m.index)) + '<ruby>' + esc(m[1]) + '<rt>' + esc(m[2]) + '</rt></ruby>';
      last = rx.lastIndex;
    }
    return (out + esc(normalized.slice(last)))
      .replace(/<\/ruby>\s+(?=<ruby>|[㐀-鿿々ヶぁ-ゖァ-ヺー])/g, '</ruby>');
  }

  function speakJapanese(text, audioUrl, onEnd) {
    // F70/F79: every clip plays at the learner's listening speed (CpathAudio), the browser voice too
    if (root && root.CpathAudio && typeof root.CpathAudio.play === 'function') {
      root.CpathAudio.play(audioUrl, text, onEnd);
      return;
    }
    if (audioUrl && root && root.JpathDisplay && typeof root.JpathDisplay.playAudio === 'function') {
      root.JpathDisplay.playAudio(audioUrl).then(function (ok) {
        if (!ok) fallbackTts(text, onEnd);
        else if (typeof onEnd === 'function') setTimeout(onEnd, 1100);
      });
      return;
    }
    fallbackTts(text, onEnd);
  }

  function fallbackTts(text, onEnd) {
    try {
      if (root && root.speechSynthesis && root.SpeechSynthesisUtterance) {
        root.speechSynthesis.cancel();
        var u = new root.SpeechSynthesisUtterance(String(text || ''));
        u.lang = 'ja-JP';
        u.rate = 0.95;
        if (typeof onEnd === 'function') {
          u.onend = function () { onEnd(); };
          u.onerror = function () { onEnd(); };
        }
        root.speechSynthesis.speak(u);
        return;
      }
    } catch (e) { /* ignore TTS error */ }
    if (typeof onEnd === 'function') setTimeout(onEnd, 750);
  }

  function svgSpeaker() {
    return '<svg viewBox="0 0 20 20" width="16" height="16" fill="none" aria-hidden="true">'
      + '<path d="M9.5 4.5L5.8 7.5H3.2v5h2.6l3.7 3v-11z" stroke="currentColor" stroke-width="1.7" stroke-linejoin="round"/>'
      + '<path d="M12.8 7.2a4 4 0 0 1 0 5.6M15 5.2a7 7 0 0 1 0 9.6" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"/>'
      + '</svg>';
  }

  function svgCheckSmall() {
    return '<svg viewBox="0 0 16 16" width="14" height="14" fill="none" aria-hidden="true">'
      + '<path d="M3.5 8.2l3 3 6-6.2" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round"/>'
      + '</svg>';
  }

  function buildDeck(sit) {
    var words = list(sit && sit.words).map(function (w) {
      return {
        kind: 'WORD',
        id: w.id,
        ja: w.word,
        reading: w.reading || '',
        vi: w.meaningVi || w.meaning_vi || '',
        note: 'Từ vựng cốt lõi trong tình huống "' + (sit.titleVi || '') + '".',
        audioUrl: w.audioUrl || ''
      };
    });
    var phrases = list(sit && sit.phrases).map(function (p) {
      return {
        kind: 'PHRASE',
        id: p.id,
        ja: p.ja,
        reading: p.reading || '',
        vi: p.vi || '',
        note: p.note || 'Mẫu câu giao tiếp quan trọng.',
        audioUrl: p.audioUrl || ''
      };
    });
    return words.concat(phrases);
  }

  function learnerLines(sit) {
    return list(sit && sit.lines).filter(function (ln) {
      return ln && (ln.learner === true || ln.speaker === 'リン');
    });
  }

  /* ---------------------------------------------------------------- F68: Du lịch */
  var MEANING_MAX = 8;
  function isTravel(sit) { return !!(sit && sit.flow === 'LISTEN_SPEAK'); }
  var TRAVEL_TITLES = { WORDS: 'Nghe & chọn nghĩa' };
  function stepTitle(key, sit) { return (isTravel(sit) && TRAVEL_TITLES[key]) || STEP_TITLES[key]; }

  // Romaji gần đúng (Hepburn) từ cách đọc: 漢字(かんじ) / {漢字|かんじ} lấy phần đọc, katakana đổi sang hiragana.
  var KANA = {
    'あ': 'a', 'い': 'i', 'う': 'u', 'え': 'e', 'お': 'o', 'か': 'ka', 'き': 'ki', 'く': 'ku', 'け': 'ke', 'こ': 'ko',
    'が': 'ga', 'ぎ': 'gi', 'ぐ': 'gu', 'げ': 'ge', 'ご': 'go', 'さ': 'sa', 'し': 'shi', 'す': 'su', 'せ': 'se', 'そ': 'so',
    'ざ': 'za', 'じ': 'ji', 'ず': 'zu', 'ぜ': 'ze', 'ぞ': 'zo', 'た': 'ta', 'ち': 'chi', 'つ': 'tsu', 'て': 'te', 'と': 'to',
    'だ': 'da', 'ぢ': 'ji', 'づ': 'zu', 'で': 'de', 'ど': 'do', 'な': 'na', 'に': 'ni', 'ぬ': 'nu', 'ね': 'ne', 'の': 'no',
    'は': 'ha', 'ひ': 'hi', 'ふ': 'fu', 'へ': 'he', 'ほ': 'ho', 'ば': 'ba', 'び': 'bi', 'ぶ': 'bu', 'べ': 'be', 'ぼ': 'bo',
    'ぱ': 'pa', 'ぴ': 'pi', 'ぷ': 'pu', 'ぺ': 'pe', 'ぽ': 'po', 'ま': 'ma', 'み': 'mi', 'む': 'mu', 'め': 'me', 'も': 'mo',
    'や': 'ya', 'ゆ': 'yu', 'よ': 'yo', 'ら': 'ra', 'り': 'ri', 'る': 'ru', 'れ': 're', 'ろ': 'ro', 'わ': 'wa', 'を': ' o ',
    'ん': 'n', 'ゔ': 'vu', 'ぁ': 'a', 'ぃ': 'i', 'ぅ': 'u', 'ぇ': 'e', 'ぉ': 'o'
  };
  var YOON = { 'ゃ': 'a', 'ゅ': 'u', 'ょ': 'o' };
  var SMALL_VOWEL = { 'ぁ': 'a', 'ぃ': 'i', 'ぅ': 'u', 'ぇ': 'e', 'ぉ': 'o' };
  var SMALL_COMBO = { 'ふ': 'f', 'ゔ': 'v', 'う': 'w', 'ち': 'ch', 'じ': 'j', 'し': 'sh', 'て': 't', 'で': 'd', 'と': 't', 'ど': 'd' };
  var PUNCT = { '。': '. ', '、': ', ', '？': '? ', '！': '! ', '　': ' ', '「': '"', '」': '"', '・': ' ', '～': '~', '…': '...' };

  function readingText(text) {
    return String(text == null ? '' : text)
      .replace(/\{([^|{}]+)\|([^{}]+)\}/g, '$2')
      .replace(/([㐀-鿿々ヶ]+)\(([ぁ-ゖァ-ヺー]+)\)/g, '$2')
      .replace(/[ァ-ヶ]/g, function (c) { return String.fromCharCode(c.charCodeAt(0) - 0x60); });
  }

  function toRomaji(text) {
    var t = readingText(text);
    var out = '';
    for (var i = 0; i < t.length; i++) {
      var c = t[i], next = t[i + 1];
      if (c === 'っ') {
        var n = next && SMALL_VOWEL[t[i + 2]] && SMALL_COMBO[next] ? SMALL_COMBO[next] : (KANA[next] || '');
        out += n.indexOf('ch') === 0 ? 't' : (n[0] || '');
        continue;
      }
      if (c === 'ー') { var v = /[aiueo]$/.exec(out); out += v ? v[0] : ''; continue; }
      if (next && SMALL_VOWEL[next] && SMALL_COMBO[c]) { out += SMALL_COMBO[c] + SMALL_VOWEL[next]; i++; continue; }
      if (next && YOON[next] && KANA[c]) {
        var base = KANA[c];
        out += (/^(shi|chi|ji)$/.test(base) ? base.slice(0, -1) : base.slice(0, -1) + 'y') + YOON[next];
        i++;
        continue;
      }
      if (KANA[c] != null) out += KANA[c];
      else if (PUNCT[c] != null) out += PUNCT[c];
      else out += c;
    }
    return out.replace(/\s+/g, ' ').trim();
  }

  function romajiHtml(text, state) {
    if (!state || !state.display || !state.display.romaji || !text) return '';
    return '<div class="cp-line__romaji">' + esc(toRomaji(text)) + '</div>';
  }

  function inDialogue(sit, ja) {
    return !!ja && list(sit && sit.lines).some(function (l) { return l && l.ja && String(l.ja).indexOf(String(ja)) >= 0; });
  }

  /** F68 step 1: the situation's phrases first, then its words heard in the dialogue (other words fill up), at most 8. */
  function meaningItems(sit) {
    var phrases = list(sit && sit.phrases).filter(function (p) { return p && p.ja && p.vi; }).map(function (p) {
      return { id: p.id, kind: 'PHRASE', ja: p.ja, reading: p.reading || '', vi: p.vi, audioUrl: p.audioUrl || '' };
    });
    var words = list(sit && sit.words).filter(function (w) { return w && w.word && w.meaningVi; }).map(function (w) {
      return { id: w.id, kind: 'WORD', ja: w.word, reading: w.reading || '', vi: w.meaningVi, audioUrl: w.audioUrl || '', heard: inDialogue(sit, w.word) };
    });
    var heard = words.filter(function (w) { return w.heard; });
    var rest = words.filter(function (w) { return !w.heard; });
    return phrases.concat(heard, rest).slice(0, MEANING_MAX);
  }

  /** Up to 4 meanings: the right one plus others of the same kind (sentences among sentences), in a fixed order. */
  function meaningOptions(sit, items, idx) {
    var item = items[idx];
    var all = list(sit && sit.phrases).map(function (p) { return { kind: 'PHRASE', vi: p.vi }; })
      .concat(list(sit && sit.words).map(function (w) { return { kind: 'WORD', vi: w.meaningVi }; }));
    var seen = {};
    seen[String(item.vi).toLowerCase()] = true;
    function pick(sameKind) {
      return all.filter(function (o) {
        return !!o.vi && !seen[String(o.vi).toLowerCase()] && (!sameKind || o.kind === item.kind);
      }).map(function (o) { return o.vi; });
    }
    var pool = pick(true);
    if (pool.length < 3) pool = pool.concat(pick(false).filter(function (v) { return pool.indexOf(v) < 0; }));
    var others = [];
    var start = pool.length ? (idx * 7) % pool.length : 0;
    for (var k = 0; k < pool.length && others.length < 3; k++) {
      var v = pool[(start + k) % pool.length];
      if (!seen[String(v).toLowerCase()]) { seen[String(v).toLowerCase()] = true; others.push(v); }
    }
    var answer = (idx * 3) % (others.length + 1);
    others.splice(answer, 0, item.vi);
    return { options: others, answer: answer };
  }

  /* F68 Bước 1 (Du lịch): nghe câu → chọn nghĩa; chữ Nhật, cách đọc và romaji hiện sau khi chọn */
  function renderStepMeaning(sit, state) {
    var items = meaningItems(sit);
    var nextLabel = 'Hoàn thành Bước 1 · Sang Nghe hội thoại ›';
    if (!items.length) {
      return '<div class="cp-sit-stage"><p>Tình huống chưa có câu để nghe và chọn nghĩa. Bạn sang nghe hội thoại nhé.</p></div>'
        + '<footer class="cp-sit-foot"><button type="button" class="cp-btn-primary" data-cp-finish-step="WORDS">' + nextLabel + '</button></footer>';
    }
    var idx = Math.max(0, Math.min(items.length - 1, state.meaningIdx || 0));
    var item = items[idx];
    var q = meaningOptions(sit, items, idx);
    var picked = state.meaningAnswers[idx];
    var answered = picked !== undefined && picked !== null;
    var right = answered && Number(picked) === q.answer;
    var correct = 0;
    items.forEach(function (it, i) {
      var a = state.meaningAnswers[i];
      if (a !== undefined && a !== null && Number(a) === meaningOptions(sit, items, i).answer) correct++;
    });
    var optsHtml = q.options.map(function (vi, k) {
      var cls = 'cp-option';
      if (answered) {
        if (k === q.answer) cls += ' is-correct';
        else if (k === Number(picked)) cls += ' is-wrong';
      }
      return '<button type="button" class="' + cls + '" data-cp-meaning-pick="' + k + '"' + (answered ? ' disabled' : '') + '>'
        + '<span class="cp-step-tab__num">' + (k + 1) + '</span><div class="cp-line__content"><div class="cp-line__vi">' + esc(vi) + '</div></div></button>';
    }).join('');
    var reveal = answered
      ? '<div class="cp-meaning-reveal' + (right ? ' is-right' : ' is-wrong') + '" role="status">'
        + '<strong>' + (right ? 'Chính xác!' : 'Chưa đúng — nghĩa đúng ở ô màu xanh.') + '</strong>'
        + '<div class="cp-line__ja" lang="ja">' + esc(item.ja) + '</div>'
        + (state.display.furigana && item.reading && item.reading !== item.ja ? '<div class="cp-flashcard__rd" lang="ja">' + esc(item.reading) + '</div>' : '')
        + romajiHtml(item.reading || item.ja, state)
        + '<div class="cp-line__vi">' + esc(item.vi) + '</div>'
        + '</div>'
      : '<p class="cp-survey__sub">Bấm nghe rồi chọn nghĩa đúng. Chữ Nhật hiện sau khi bạn chọn.</p>';
    var foot = '<button type="button" class="cp-btn-ghost" data-cp-meaning-prev' + (idx <= 0 ? ' disabled' : '') + '>‹ Câu trước</button>'
      + (!answered ? '<span class="cp-survey__sub">Chọn một nghĩa để tiếp tục</span>'
        : (idx + 1 < items.length ? '<button type="button" class="cp-btn-primary" data-cp-meaning-next>Câu tiếp ›</button>'
          : '<button type="button" class="cp-btn-primary" data-cp-finish-step="WORDS">' + nextLabel + '</button>'));
    return '<div class="cp-sit-stage cp-sit-stage--meaning">'
      + '<div class="cp-deck-switch"><strong>' + (item.kind === 'PHRASE' ? 'Mẫu câu' : 'Từ') + ' · ' + (idx + 1) + '/' + items.length + '</strong>'
      + '<span class="cp-survey__sub">Đúng <strong>' + correct + '</strong>/' + items.length + '</span></div>'
      + '<button type="button" class="cp-btn-primary cp-meaning-play" data-cp-play-audio="' + esc(item.audioUrl) + '" data-cp-play-text="' + esc(item.ja) + '">'
      + svgSpeaker() + '<span>' + (answered ? 'Nghe lại' : 'Nghe câu') + '</span></button>'
      + '<div class="cp-options" role="group" aria-label="Chọn nghĩa của câu vừa nghe">' + optsHtml + '</div>'
      + reveal
      + '</div>'
      + '<footer class="cp-sit-foot">' + foot + '</footer>';
  }

  /* F68: "Chú thích" của bước Nghe — từ có trong hội thoại + mẫu câu/ngữ pháp kèm ghi chú */
  function notesHtml(sit, state) {
    if (!state.notesOpen) return '';
    var words = list(sit && sit.words).filter(function (w) { return w && inDialogue(sit, w.word); });
    if (!words.length) words = list(sit && sit.words).slice(0, 12);
    var phrases = list(sit && sit.phrases);
    var wordRows = words.map(function (w) {
      return '<li class="cp-notes__item"><span class="cp-notes__ja" lang="ja">' + esc(w.word) + '</span>'
        + (w.reading && w.reading !== w.word ? ' <small lang="ja">' + esc(w.reading) + '</small>' : '')
        + romajiHtml(w.reading || w.word, state)
        + '<span class="cp-notes__vi">' + esc(w.meaningVi) + '</span>'
        + '<button type="button" class="cp-audio-btn" data-cp-play-audio="' + esc(w.audioUrl || '') + '" data-cp-play-text="' + esc(w.word) + '" aria-label="Nghe ' + esc(w.word) + '">' + svgSpeaker() + '</button></li>';
    }).join('');
    var phraseRows = phrases.map(function (p) {
      return '<li class="cp-notes__item"><span class="cp-notes__ja" lang="ja">' + esc(p.ja) + '</span>'
        + romajiHtml(p.reading || p.ja, state)
        + '<span class="cp-notes__vi">' + esc(p.vi) + '</span>'
        + (p.note ? '<span class="cp-notes__note">' + esc(p.note) + '</span>' : '')
        + '<button type="button" class="cp-audio-btn" data-cp-play-audio="' + esc(p.audioUrl || '') + '" data-cp-play-text="' + esc(p.ja) + '" aria-label="Nghe mẫu câu">' + svgSpeaker() + '</button></li>';
    }).join('');
    return '<section class="cp-notes" id="cpNotes" aria-label="Chú thích hội thoại">'
      + (wordRows ? '<h3 class="cp-notes__title">Từ trong hội thoại</h3><ul class="cp-notes__list">' + wordRows + '</ul>' : '')
      + (phraseRows ? '<h3 class="cp-notes__title">Mẫu câu &amp; ngữ pháp</h3><ul class="cp-notes__list">' + phraseRows + '</ul>' : '')
      + (!wordRows && !phraseRows ? '<p class="cp-survey__sub">Tình huống này chưa có chú thích.</p>' : '')
      + '</section>';
  }

  function findLineByOrder(sit, orderNum) {
    var all = list(sit && sit.lines);
    for (var i = 0; i < all.length; i++) {
      if (Number(all[i].order) === Number(orderNum)) return all[i];
    }
    return null;
  }

  /* Bước 1: Từ và mẫu câu (thẻ lật qua lại + nút nghe) */
  function renderStepWords(sit, state) {
    var deck = buildDeck(sit);
    if (!deck.length) {
      return '<div class="cp-sit-stage"><p>Không có thẻ từ hoặc mẫu câu nào.</p></div>';
    }
    var idx = Math.max(0, Math.min(deck.length - 1, state.cardIdx || 0));
    var card = deck[idx];
    var flipped = !!state.cardFlipped;
    var wordCount = list(sit.words).length;
    var tabCount = card.kind === 'WORD' ? wordCount : deck.length - wordCount;
    var tabIndex = card.kind === 'WORD' ? idx + 1 : idx - wordCount + 1;
    var example = list(sit.lines).filter(function (line) {
      return line && line.ja && card.ja && String(line.ja).indexOf(String(card.ja)) >= 0;
    })[0];

    var cardBody = !flipped
      ? '<span class="cp-flashcard__tag">' + (card.kind === 'WORD' ? 'Từ vựng' : 'Mẫu câu') + ' · ' + tabIndex + '/' + tabCount + '</span>'
        + '<div class="cp-flashcard__ja" lang="ja">' + esc(card.ja) + '</div>'
        + (state.display.furigana && card.reading ? '<div class="cp-flashcard__rd" lang="ja">' + esc(card.reading) + '</div>' : '')
        + (example ? '<p class="cp-flashcard__example"><small>Ví dụ trong hội thoại</small><span lang="ja">' + esc(example.ja) + '</span></p>' : '')
        + '<button type="button" class="cp-btn-ghost" data-cp-play-audio="' + esc(card.audioUrl) + '" data-cp-play-text="' + esc(card.ja) + '">'
        + svgSpeaker() + '<span>Nghe phát âm</span></button>'
        + ''
      : '<span class="cp-flashcard__tag">Mặt sau · ' + (card.kind === 'WORD' ? 'Từ vựng' : 'Mẫu câu') + '</span>'
        + '<div class="cp-flashcard__ja" lang="ja">' + esc(card.ja) + '</div>'
        + (card.reading ? '<div class="cp-flashcard__rd" lang="ja">' + esc(card.reading) + '</div>' : '')
        + '<div class="cp-flashcard__vi">' + esc(card.vi) + '</div>'
        + '<div class="cp-flashcard__note">' + esc(card.note) + '</div>'
        + '<button type="button" class="cp-btn-ghost" data-cp-play-audio="' + esc(card.audioUrl) + '" data-cp-play-text="' + esc(card.ja) + '">'
        + svgSpeaker() + '<span>Nghe lại</span></button>'
        + '';

    return '<div class="cp-sit-stage cp-sit-stage--words">'
      + '<div class="cp-deck-switch">'
      + '<div class="cp-deck-tabs" role="tablist">'
      + '<button type="button" class="cp-deck-tab' + (idx < wordCount ? ' is-active' : '') + '" data-cp-jump-card="0">Từ vựng (' + wordCount + ')</button>'
      + '<button type="button" class="cp-deck-tab' + (idx >= wordCount ? ' is-active' : '') + '" data-cp-jump-card="' + wordCount + '">Mẫu câu (' + Math.max(0, deck.length - wordCount) + ')</button>'
      + '</div>'
      + '<span class="cp-survey__sub">Thẻ <strong>' + tabIndex + '/' + tabCount + '</strong></span>'
      + '</div>'
      + '<div class="cp-flashcard" data-cp-flip-card tabindex="0" role="button" aria-label="Thẻ học lật hai chiều">'
      + cardBody
      + '</div>'
      + '</div>'
      + '<footer class="cp-sit-foot">'
      + '<button type="button" class="cp-btn-ghost" data-cp-card-prev' + (idx <= 0 ? ' disabled' : '') + ' aria-label="Thẻ trước">‹<span class="cp-wide-only"> Thẻ trước</span></button>'
      + '<button type="button" class="cp-btn-ghost" data-cp-flip-btn>' + (flipped ? 'Xem mặt trước' : 'Lật xem nghĩa') + '<span class="cp-wide-only"> (Space)</span>' + '</button>'
      + (idx + 1 < deck.length
        ? '<button type="button" class="cp-btn-primary" data-cp-card-next>Đã nhớ · Thẻ tiếp ›</button>'
        : '<button type="button" class="cp-btn-primary" data-cp-finish-step="WORDS">Hoàn thành Bước 1 · Sang Nghe hội thoại ›</button>')
      + '</footer>';
  }

  /* Bước 2: Nghe hội thoại từng câu (phát lần lượt, rê chuột vào câu hiện bản dịch) */
  function renderStepListen(sit, state) {
    var lines = list(sit && sit.lines);
    var showFuri = !!state.display.furigana;
    var showTrans = !!state.display.translation;
    var listCls = 'cp-lines' + (showTrans ? '' : ' cp-translation-hover');

    var rowsHtml = lines.map(function (ln, idx) {
      var isLearner = ln.learner === true || ln.speaker === 'リン';
      var isPlaying = state.playingLineIdx === idx;
      var furiSource = ln.furigana || ln.ja;
      return '<li class="cp-line' + (isLearner ? ' is-learner' : '') + (isPlaying ? ' is-playing' : '') + '" tabindex="0">'
        + '<span class="cp-line__avatar" aria-hidden="true">' + (isLearner ? '私' : '相') + '</span>'
        + '<span class="cp-line__speaker">' + (isLearner ? 'Bạn · ' : 'Người đối thoại · ') + esc(ln.speaker) + '</span>'
        + '<div class="cp-line__content">'
        + '<div class="cp-line__ja" lang="ja">' + toRubyHtml(furiSource, showFuri) + '</div>'
        + romajiHtml(furiSource, state)
        + '<div class="cp-line__vi">' + esc(ln.vi) + '</div>'
        + '</div>'
        + '<button type="button" class="cp-audio-btn" data-cp-listen-line="' + idx + '" aria-label="Nghe câu ' + (idx + 1) + '">'
        + svgSpeaker()
        + '</button>'
        + '</li>';
    }).join('');

    return '<div class="cp-sit-stage">'
      + '<div class="cp-listen-bar">'
      + '<div><strong>Bối cảnh:</strong> ' + esc(sit.situationVi || sit.titleVi)
      + (showTrans ? '' : ' <span class="cp-survey__sub">(Bản dịch đang ẩn; bấm nút Bản dịch ở đầu trang để hiện.)</span>') + '</div>'
      + '<button type="button" class="cp-btn-primary" data-cp-play-all>'
      + svgSpeaker()
      + '<span>' + (state.playingAll ? 'Đang phát lần lượt…' : 'Phát lần lượt cả hội thoại') + '</span>'
      + '</button>'
      + (isTravel(sit) ? '<button type="button" class="cp-btn-ghost" data-cp-notes-toggle aria-expanded="' + (state.notesOpen ? 'true' : 'false')
        + '" aria-controls="cpNotes">' + (state.notesOpen ? 'Ẩn chú thích' : 'Chú thích') + '</button>' : '')
      + '</div>'
      + (isTravel(sit) ? notesHtml(sit, state) : '')
      + '<ol class="' + listCls + '">' + rowsHtml + '</ol>'
      + '</div>'
      + '<footer class="cp-sit-foot">'
      + '<button type="button" class="cp-btn-ghost" data-cp-goto-step="WORDS">‹ Quay lại ' + esc(stepTitle('WORDS', sit)) + '</button>'
      + '<button type="button" class="cp-btn-primary" data-cp-finish-step="LISTEN">Hoàn thành Bước 2 · Sang Nhập vai ›</button>'
      + '</footer>';
  }

  /* Bước 3: Nhập vai chọn câu (đến lượt リン chọn 1 trong 3 câu, kèm giải thích whyVi) */
  function renderStepRoleplay(sit, state) {
    var turns = list(sit && sit.challenge && sit.challenge.turns);
    if (!turns.length) {
      return '<div class="cp-sit-stage"><p>Không có lượt nhập vai cho tình huống này.</p></div>'
        + '<footer class="cp-sit-foot"><button type="button" class="cp-btn-primary" data-cp-finish-step="ROLEPLAY">Sang Bước 4 ›</button></footer>';
    }
    var tIdx = Math.max(0, Math.min(turns.length - 1, state.turnIdx || 0));
    var turn = turns[tIdx];
    var prevLine = findLineByOrder(sit, Number(turn.line) - 1);
    var answeredIdx = state.roleplayAnswers[tIdx];
    var isAnswered = answeredIdx !== undefined && answeredIdx !== null;
    var ansCorrect = isAnswered && Number(answeredIdx) === Number(turn.answerIndex);

    var promptHtml = prevLine
      ? '<div class="cp-roleplay-context">'
        + '<span class="cp-survey__sub">Lời của <strong>' + esc(prevLine.speaker) + '</strong>:</span>'
        + '<div class="cp-line__ja" lang="ja">' + toRubyHtml(prevLine.furigana || prevLine.ja, state.display.furigana) + '</div>'
        + romajiHtml(prevLine.furigana || prevLine.ja, state)
        + (state.display.translation ? '<div class="cp-line__vi">' + esc(prevLine.vi) + '</div>' : '')
        + '</div>'
      : '<div class="cp-roleplay-context"><span class="cp-survey__sub">Mở đầu cuộc hội thoại — Đến lượt <strong>' + esc(learnerName) + '</strong> nói câu đầu tiên:</span></div>';

    var optsHtml = list(turn.options).map(function (opt, oIdx) {
      var cls = 'cp-option';
      if (isAnswered) {
        if (oIdx === Number(turn.answerIndex)) cls += ' is-correct';
        else if (oIdx === Number(answeredIdx)) cls += ' is-wrong';
      }
      return '<button type="button" class="' + cls + '" data-cp-roleplay-opt="' + oIdx + '"' + (isAnswered ? ' disabled' : '') + '>'
        + '<span class="cp-step-tab__num">' + (oIdx + 1) + '</span>'
        + '<div class="cp-line__content">'
        + '<div class="cp-line__ja" lang="ja">' + esc(opt.ja) + '</div>'
        + romajiHtml(opt.ja, state)
        + (isAnswered || sit.selfLevel === 'BEGINNER' ? '<div class="cp-line__vi">' + esc(opt.vi) + '</div>' : '')
        + '</div>'
        + '</button>';
    }).join('');

    var whyText = turn.whyVi || turn.why_vi || '';
    var pickedOptObj = isAnswered ? (list(turn.options)[answeredIdx] || {}) : {};
    var rightOptObj = list(turn.options)[turn.answerIndex] || {};
    var aiRoleplayHtml = isAnswered && rightOptObj.ja
      ? '<div class="jp-ai-slot" data-jpath-ai-explain="' + esc(JSON.stringify({
        source: 'CPATH_ROLEPLAY',
        contextText: ((sit && (sit.titleVi || sit.titleJa)) ? 'Tình huống: ' + (sit.titleVi || sit.titleJa) + '\n' : '')
          + (prevLine ? (prevLine.speaker || 'Đối phương') + ': ' + (prevLine.ja || '') + (prevLine.vi ? ' (' + prevLine.vi + ')' : '') : 'Mở đầu hội thoại'),
        prompt: 'Nhập vai ' + esc(learnerName) + ' — chọn câu đáp tiếng Nhật phù hợp nhất',
        options: list(turn.options).map(function (o) { return (o.ja || '') + (o.vi ? ' (' + o.vi + ')' : ''); }),
        userAnswer: pickedOptObj.ja || '—',
        correctAnswer: rightOptObj.ja || '',
        baseExplanation: whyText
      })) + '"></div>'
      : '';
    var whyHtml = isAnswered
      ? '<div class="cp-why-box" role="status">'
        + '<img src="' + esc(kitsuSmall(ansCorrect ? 'kitsu-cheer-30.webp' : 'kitsu-thinking-30.webp')) + '" width="44" height="44" alt="" />'
        + '<div><strong>' + (ansCorrect ? 'Chính xác!' : 'Chưa đúng rồi:') + '</strong> ' + esc(whyText) + '</div>'
        + '</div>' + aiRoleplayHtml
      : '';

    return '<div class="cp-sit-stage cp-sit-stage--roleplay">'
      + '<div class="cp-deck-switch">'
      + '<strong>Nhập vai ' + esc(learnerName) + ' · Lượt ' + (tIdx + 1) + '/' + turns.length + '</strong>'
      + '<span class="cp-survey__sub">Chọn 1 trong 3 câu trả lời phù hợp nhất</span>'
      + '</div>'
      + promptHtml
      + (isAnswered && !ansCorrect
        ? '<div class="jp-review__rows cp-roleplay-review">'
          + '<div class="jp-review__row jp-review__row--picked jp-bad"><span class="jp-review__tag">Bạn chọn</span><span class="jp-review__val" lang="ja">' + esc((list(turn.options)[answeredIdx] || {}).ja || '—') + '</span></div>'
          + '<div class="jp-review__row jp-review__row--answer jp-ok"><span class="jp-review__tag">Đáp án</span><span class="jp-review__val" lang="ja">' + esc((list(turn.options)[turn.answerIndex] || {}).ja || '') + '</span></div>'
          + '</div>'
          + '<details class="jp-review__more"><summary class="jp-link-btn">Xem cả câu</summary><div class="cp-options" role="group" aria-label="Chọn câu trả lời của ' + esc(learnerName) + '">' + optsHtml + '</div></details>'
        : '<div class="cp-options" role="group" aria-label="Chọn câu trả lời của ' + esc(learnerName) + '">' + optsHtml + '</div>')
      + whyHtml
      + '</div>'
      + '<footer class="cp-sit-foot">'
      + '<button type="button" class="cp-btn-ghost" data-cp-goto-step="LISTEN">‹ Quay lại Nghe</button>'
      + (tIdx + 1 < turns.length
        ? (isAnswered ? '<button type="button" class="cp-btn-primary" data-cp-roleplay-next>Lượt tiếp theo ›</button>'
          : '<span class="cp-survey__sub">Chọn một câu để tiếp tục</span>')
        : (isAnswered ? '<button type="button" class="cp-btn-primary" data-cp-finish-step="ROLEPLAY">Hoàn thành Bước 3 · Sang Luyện nói ›</button>'
          : '<span class="cp-survey__sub">Chọn một câu để tiếp tục</span>'))
      + '</footer>';
  }

  function hasMicrophone(env) {
    var e = env || root || {};
    var nav = e.navigator;
    return !!(nav && nav.mediaDevices && typeof nav.mediaDevices.getUserMedia === 'function'
      && typeof e.MediaRecorder === 'function');
  }

  function normalizeSpeakResponse(res, state) {
    var r = res || {};
    var mainScore = typeof r.score === 'number'
      ? Math.round(r.score)
      : (typeof r.pronunciation === 'number' ? Math.round(r.pronunciation) : 0);
    var accuracy = typeof r.accuracy === 'number' ? Math.round(r.accuracy) : mainScore;
    var fluency = typeof r.fluency === 'number' ? Math.round(r.fluency) : mainScore;
    var completeness = typeof r.completeness === 'number' ? Math.round(r.completeness) : mainScore;
    var words = list(r.words).map(function (w) {
      var wScore = typeof w.score === 'number'
        ? Math.round(w.score)
        : (typeof w.accuracy === 'number' ? Math.round(w.accuracy) : 0);
      return {
        word: w.word || '',
        score: wScore,
        errorType: w.errorType || ''
      };
    });
    var attemptsLeft = typeof r.attemptsLeft === 'number'
      ? Math.max(0, Math.round(r.attemptsLeft))
      : (state && typeof state.attemptsLeft === 'number' ? state.attemptsLeft : null);
    return {
      score: mainScore,
      accuracy: accuracy,
      fluency: fluency,
      completeness: completeness,
      words: words,
      feedbackVi: r.feedbackVi ? String(r.feedbackVi) : '',
      recognizedText: r.recognizedText ? String(r.recognizedText) : '',
      attemptsLeft: attemptsLeft
    };
  }

  /* Bước 4: Luyện nói câu của リン (máy phát lời bên kia, nút ẩn chữ, chấm điểm từng câu, <70 nút Nói lại, nút Bỏ qua) */
  function renderStepSpeak(sit, state) {
    var lLines = learnerLines(sit);
    if (!lLines.length) {
      return '<div class="cp-sit-stage"><p class="jp-alert" role="alert">Tình huống này chưa có câu luyện nói. Bạn chưa thể hoàn thành tình huống hoặc đạt chủ đề. Hãy quay về lộ trình và báo cho nhóm nội dung bổ sung câu thoại.</p>'
        + '<a class="cp-btn-ghost" href="/learning/comm/path">Về lộ trình</a></div>';
    }
    var sIdx = Math.max(0, Math.min(lLines.length - 1, state.speakIdx || 0));
    var targetLine = lLines[sIdx];
    var partnerLine = findLineByOrder(sit, Number(targetLine.order) - 1);
    var scoreObj = state.speakScores[sIdx];
    var isLow = scoreObj && Number(scoreObj.score) < 70;
    var isPass = scoreObj && Number(scoreObj.score) >= 70;
    var noMicSupport = !hasMicrophone(root);
    var micBlocked = noMicSupport || !!state.micUnavailable;
    var outOfAttempts = state.attemptsLeft === 0;
    var recordLocked = outOfAttempts || !!state.scoring;

    var partnerHtml = partnerLine
      ? '<div class="cp-roleplay-context">'
        + '<div class="cp-deck-switch">'
        + '<span class="cp-survey__sub">Nhân vật đối thoại (<strong>' + esc(partnerLine.speaker) + '</strong>) nói trước:</span>'
        + '<button type="button" class="cp-btn-ghost" data-cp-play-audio="' + esc(partnerLine.audioUrl) + '" data-cp-play-text="' + esc(partnerLine.ja) + '">'
        + svgSpeaker() + '<span>Phát lời bên kia</span></button>'
        + '</div>'
        + '<div class="cp-line__ja" lang="ja">' + toRubyHtml(partnerLine.furigana || partnerLine.ja, state.display.furigana) + '</div>'
        + romajiHtml(partnerLine.furigana || partnerLine.ja, state)
        + '<div class="cp-line__vi">' + esc(partnerLine.vi) + '</div>'
        + '</div>'
      : '';

    var wordsBreakdown = scoreObj && Array.isArray(scoreObj.words) && scoreObj.words.length
      ? '<div class="cp-score-words">' + scoreObj.words.map(function (w) {
          var wCls = 'cp-score-word ' + (Number(w.score) >= 70 ? 'is-good' : 'is-low');
          return '<span class="' + wCls + '" lang="ja">' + esc(w.word) + ' <small>(' + esc(w.score) + ')</small></span>';
        }).join('') + '</div>'
      : '';

    var recognizedHtml = (scoreObj && scoreObj.recognizedText)
      ? '<div class="cp-score-recognized" data-cp-recognized-text>Máy nghe được: <strong lang="ja">「' + esc(scoreObj.recognizedText) + '」</strong></div>'
      : '';

    var feedbackText = (scoreObj && scoreObj.feedbackVi)
      ? scoreObj.feedbackVi
      : (isLow
        ? 'Điểm dưới 70 — Hãy bấm "Nói lại" để luyện rõ hơn câu này nhé!'
        : 'Đạt yêu cầu (≥ 70 điểm)! Phát âm rất tự nhiên.');

    var attemptsInfoHtml = (typeof state.attemptsLeft === 'number')
      ? '<span class="cp-attempts-badge" data-cp-attempts-left>Số lần còn lại: <strong>' + esc(state.attemptsLeft) + '</strong></span>'
      : '';

    var scoreHtml = scoreObj
      ? '<div class="cp-score-panel ' + (isPass ? 'is-pass' : 'is-retry') + '" role="status">'
        + '<div style="display:flex;align-items:flex-start;gap:12px;flex:1 1 auto;">'
        + '<img src="' + esc(kitsuSmall(isPass ? 'kitsu-celebrate-30.webp' : 'kitsu-encouraging.webp')) + '" width="48" height="48" alt="" />'
        + '<div style="display:grid;gap:4px;">'
        + '<div><strong>Điểm phát âm: ' + esc(scoreObj.score) + '/100</strong> '
        + '<span class="cp-survey__sub">(Chính xác: ' + esc(scoreObj.accuracy) + ' · Trôi chảy: ' + esc(scoreObj.fluency) + ' · Đầy đủ: ' + esc(scoreObj.completeness) + ')</span>'
        + (scoreObj.attemptsLeft != null ? ' · <span class="cp-survey__sub">Còn <strong>' + esc(scoreObj.attemptsLeft) + '</strong> lượt hôm nay</span>' : '')
        + '</div>'
        + recognizedHtml
        + '<div class="cp-score-feedback" data-cp-feedback-vi>' + esc(feedbackText) + '</div>'
        + wordsBreakdown
        + '</div>'
        + '</div>'
        + (isLow && !outOfAttempts ? '<button type="button" class="cp-btn-primary" data-cp-speak-retry>Nói lại câu này</button>' : '')
        + '</div>'
      : '';

    var noticeMsg = '';
    if (outOfAttempts) {
      noticeMsg = state.micError || 'Bạn đã dùng hết lượt chấm phát âm hôm nay (còn 0 lần). Nút ghi âm đã khoá, hãy bấm "Bỏ qua" để tiếp tục.';
    } else if (state.micError) {
      noticeMsg = state.micError;
    } else if (noMicSupport) {
      noticeMsg = 'Thiết bị không có micro hoặc trình duyệt không hỗ trợ ghi âm. Bạn có thể bấm "Bỏ qua" để tiếp tục.';
    }

    var noticeHtml = noticeMsg
      ? '<div class="cp-mic-notice" role="alert" data-cp-mic-notice>'
        + '<span>' + esc(noticeMsg) + '</span>'
        + '<button type="button" class="cp-btn-ghost" data-cp-speak-skip>Bỏ qua</button>'
        + '</div>'
      : '';

    var recLabel = state.scoring
      ? 'Đang chấm điểm phát âm…'
      : (state.recording
        ? '■ Dừng ghi âm & Chấm điểm (tự dừng sau 10s)'
        : (outOfAttempts ? 'Đã hết lượt ghi âm hôm nay' : '● Bấm ghi âm câu của ' + esc(learnerName) + ''));

    return '<div class="cp-sit-stage">'
      + '<div class="cp-deck-switch">'
      + '<div style="display:flex;align-items:center;gap:10px;flex-wrap:wrap;">'
      + '<strong>Đóng vai ' + esc(learnerName) + ' · Câu ' + (sIdx + 1) + '/' + lLines.length + '</strong>'
      + attemptsInfoHtml
      + '</div>'
      + '<button type="button" class="cp-btn-ghost" data-cp-toggle-hide-text aria-pressed="' + (state.hideSpeakText ? 'true' : 'false') + '">'
      + (state.hideSpeakText ? 'Hiện chữ câu của ' + esc(learnerName) + '' : 'Ẩn chữ để tự nhớ')
      + '</button>'
      + '</div>'
      + partnerHtml
      + '<div class="cp-speak-box">'
      + '<div class="cp-speak-target' + (state.hideSpeakText ? ' is-hidden-text' : '') + '">'
      + '<span class="cp-survey__sub">Câu của <strong>' + esc(learnerName) + '</strong>:</span>'
      + '<div class="cp-line__ja cp-speak-target__ja" lang="ja">' + toRubyHtml(targetLine.furigana || targetLine.ja, state.display.furigana) + '</div>'
      + romajiHtml(targetLine.furigana || targetLine.ja, state)
      + '<div class="cp-line__vi">' + esc(targetLine.vi) + '</div>'
      + '</div>'
      + '<div class="cp-speak-controls">'
      + '<button type="button" class="cp-rec-btn' + (state.recording ? ' is-recording' : '') + '" data-cp-record' + (recordLocked ? ' disabled' : '') + '>'
      + '<span>' + esc(recLabel) + '</span>'
      + '</button>'
      + '<button type="button" class="cp-btn-ghost" data-cp-play-audio="' + esc(targetLine.audioUrl) + '" data-cp-play-text="' + esc(targetLine.ja) + '">'
      + svgSpeaker() + '<span>Nghe mẫu</span></button>'
      + '<button type="button" class="' + ((outOfAttempts || micBlocked) ? 'cp-btn-primary' : 'cp-btn-ghost') + '" data-cp-speak-skip>Bỏ qua</button>'
      + '</div>'
      + noticeHtml
      + '</div>'
      + scoreHtml
      + '</div>'
      + '<footer class="cp-sit-foot">'
      + '<button type="button" class="cp-btn-ghost" data-cp-goto-step="ROLEPLAY">‹ Quay lại Nhập vai</button>'
      + (sIdx + 1 < lLines.length
        ? '<button type="button" class="cp-btn-primary" data-cp-speak-next' + (!isPass && !state.speakSkipped[sIdx] ? ' disabled' : '') + '>Câu tiếp theo ›</button>'
        : '<button type="button" class="cp-btn-primary" data-cp-finish-step="SPEAK"' + (!isPass && !state.speakSkipped[sIdx] ? ' disabled' : '') + '>Hoàn thành 4 bước ›</button>')
      + '</footer>';
  }

  /* Màn hình hoàn thành cả 4 bước: hiện nút "Nói chuyện tự do với AI" (aiCallUrl) và "Về lộ trình" */
  function renderDoneScreen(sit, state) {
    var aiHref = sit && sit.aiCallUrl;
    var pathHref = pageUrl('path');
    var skippedNote = (state.lastSpeakBody && state.lastSpeakBody.skippedMic)
      ? '<p class="cp-survey__sub">Đã bỏ qua phần nói. Tình huống vẫn tính xong, chủ đề cần điểm nói để đạt; bạn luyện nói lại lúc nào cũng được.</p>'
      : (state.lastSpeakBody && typeof state.lastSpeakBody.speakBest === 'number'
        ? '<p class="cp-survey__sub">Điểm nói trung bình các câu đã chấm: <strong>' + esc(state.lastSpeakBody.speakBest) + '/100</strong>.</p>'
        : '');
    return '<div class="cp-sit-stage" style="align-items:center;justify-content:center;text-align:center;">'
      + '<img src="' + esc(kitsuSrc('kitsu-celebrate-30.webp')) + '" width="78" height="78" alt="Kitsu chúc mừng" />'
      + '<h2 class="cp-survey__title">Hoàn thành tình huống: ' + esc(sit.titleVi) + '!</h2>'
      + '<p class="cp-survey__sub">Bạn đã hoàn thành trọn vẹn 4 bước (' + esc(stepTitle('WORDS', sit)) + ' · Nghe · Nhập vai · Nói)'
      + (state.awardedCp ? (' và nhận được <strong>+' + esc(state.awardedCp) + ' CP</strong>') : '') + '.</p>'
      + skippedNote
      + (aiHref && isTravel(sit) ? '<p class="cp-survey__sub">Gọi AI là luyện thêm, <strong>không bắt buộc</strong>: tình huống đã tính xong.</p>' : '')
      + '<div style="display:flex;flex-wrap:wrap;gap:12px;justify-content:center;margin-top:8px;">'
      + (aiHref ? '<a class="cp-btn-primary" href="' + esc(aiHref) + '">' + (isTravel(sit) ? 'Luyện gọi AI theo tình huống' : 'Nói chuyện tự do với AI') + '</a>' : '')
      + '<a class="cp-btn-ghost" href="/learning/comm/weekly?mode=quick&amp;code=' + encodeURIComponent(sit.id) + '">Luyện nhanh</a>'
      + '<a class="cp-btn-ghost" href="' + esc(pathHref) + '">Về lộ trình</a>'
      + '</div>'
      + '</div>';
  }

  /** F64: offered while no step of the situation is done yet. */
  function canKnow(sit, state) {
    var st = (sit && sit.steps) || {};
    if (state && state.showDoneScreen) return false;
    return !Object.keys(st).some(function (k) { return st[k]; });
  }

  /**
   * F64/F69 (owner 11/11): "Tôi biết tình huống này" — a short meaning check (>= 90%) instead of the 4 steps.
   * k = state.known: { open, loading, error, data: { questions, passScore }, answers: {id: index}, result }.
   */
  function knownPanelHtml(k) {
    if (!k || !k.open) return '';
    var head = '<div class="cp-known__head"><h2>Tôi biết tình huống này</h2>'
      + '<button type="button" class="cp-btn-ghost" data-cp-known-close>Đóng</button></div>';
    if (k.loading) return '<section class="cp-known" aria-live="polite">' + head + '<p>Đang tải câu hỏi...</p></section>';
    if (k.error) return '<section class="cp-known">' + head + '<p class="jp-alert" role="alert">' + esc(k.error) + '</p></section>';
    var d = k.data || {};
    var pass = d.passScore || 90;
    if (k.result) {
      var r = k.result;
      var wrong = (r.review || []).filter(function (x) { return !x.correct; });
      return '<section class="cp-known" aria-live="polite">' + head
        + (r.passed
          ? '<p class="cp-known__ok"><strong>Đạt ' + r.score + '%.</strong> Tình huống này được tính là đã học, không cần làm 4 bước.</p>'
            + '<a class="cp-btn-primary" href="/learning/comm/path">Về lộ trình</a>'
          : '<p class="cp-known__no"><strong>Đạt ' + r.score + '%</strong> (cần từ ' + pass + '%). Học tình huống như bình thường nhé.</p>'
            + (wrong.length ? '<ul class="cp-known__wrong">' + wrong.map(function (x) {
              return '<li><span lang="ja">' + esc(x.ja) + '</span> — ' + esc(x.answer) + '</li>';
            }).join('') + '</ul>' : '')
            + '<button type="button" class="cp-btn-primary" data-cp-known-close>Học tình huống</button>')
        + '</section>';
    }
    var qs = d.questions || [];
    if (!qs.length) return '<section class="cp-known">' + head + '<p>Tình huống này chưa có câu để kiểm tra.</p></section>';
    var answers = k.answers || {};
    var answered = qs.filter(function (q) { return answers[q.id] != null; }).length;
    return '<section class="cp-known">' + head
      + '<p class="cp-known__sub">' + qs.length + ' câu chọn nghĩa đúng. Đúng từ ' + pass + '% thì tình huống tính là đã học.</p>'
      + '<ol class="cp-known__list">' + qs.map(function (q) {
        return '<li class="cp-known__q"><p class="cp-known__ja" lang="ja">' + esc(q.ja)
          + (q.reading ? ' <small>' + esc(q.reading) + '</small>' : '') + '</p>'
          + '<div class="cp-known__opts" role="group">' + (q.options || []).map(function (o, i) {
            var on = answers[q.id] === i;
            return '<button type="button" class="cp-known__opt' + (on ? ' is-on' : '') + '" aria-pressed="' + (on ? 'true' : 'false')
              + '" data-cp-known-pick="' + esc(q.id) + '" data-cp-known-idx="' + i + '">' + esc(o) + '</button>';
          }).join('') + '</div></li>';
      }).join('') + '</ol>'
      + '<button type="button" class="cp-btn-primary" data-cp-known-submit' + (answered < qs.length ? ' disabled' : '') + '>Nộp bài ('
      + answered + '/' + qs.length + ')</button>'
      + '</section>';
  }

  function situationHtml(sit, state) {
    var s = sit || {};
    var stepsStatus = s.steps || {};

    var stepTabsHtml = STEPS_ORDER.map(function (key, idx) {
      var isDone = !!stepsStatus[key.toLowerCase()];
      var isActive = !state.showDoneScreen && state.activeStep === key;
      var cls = 'cp-step-tab' + (isActive ? ' is-active' : '') + (isDone ? ' is-done' : '');
      return '<button type="button" class="' + cls + '" data-cp-goto-step="' + key + '">'
        + '<span class="cp-step-tab__num">' + (isDone ? svgCheckSmall() : (idx + 1)) + '</span>'
        + '<span>' + esc(stepTitle(key, s)) + '</span>'
        + '</button>';
    }).join('');

    var mainBody = '';
    if (state.showDoneScreen) {
      mainBody = renderDoneScreen(s, state);
    } else if (state.activeStep === 'WORDS') {
      mainBody = isTravel(s) ? renderStepMeaning(s, state) : renderStepWords(s, state);
    } else if (state.activeStep === 'LISTEN') {
      mainBody = renderStepListen(s, state);
    } else if (state.activeStep === 'ROLEPLAY') {
      mainBody = renderStepRoleplay(s, state);
    } else {
      mainBody = renderStepSpeak(s, state);
    }
    if (!state.showDoneScreen && state.missingNote) {
      mainBody = '<p class="jp-alert" role="status">' + esc(state.missingNote) + '</p>' + mainBody;
    }

    return '<div class="cp-sit-shell">'
      + '<header class="cp-sit-topbar">'
      + '<div class="cp-sit-head-info">'
      + '<span class="cp-sit-topic-badge">' + esc((s.topic && s.topic.nameVi) || 'Giao tiếp') + '</span>'
      + '<h1 class="cp-sit-title">' + esc(s.titleVi || 'Tình huống giao tiếp') + '</h1>'
      + '</div>'
      + '<div style="display:flex;align-items:center;gap:8px;">'
      + (canKnow(s, state) ? '<button type="button" class="cp-btn-ghost" data-cp-known-open>Tôi biết tình huống này</button>' : '')
      + '<button type="button" class="cp-btn-ghost" data-cp-disp-toggle="furigana" aria-pressed="' + (state.display.furigana ? 'true' : 'false') + '">Furigana</button>'
      + '<button type="button" class="cp-btn-ghost" data-cp-disp-toggle="translation" aria-pressed="' + (state.display.translation ? 'true' : 'false') + '">Bản dịch</button>'
      + (isTravel(s) || state.display.fullRomaji ? '<button type="button" class="cp-btn-ghost" data-cp-disp-toggle="romaji" title="Romaji gần đúng theo cách đọc của câu" aria-pressed="' + (state.display.romaji ? 'true' : 'false') + '">Romaji</button>' : '')
      + '</div>'
      + '</header>'
      + (root && root.CpathAudio ? root.CpathAudio.sliderHtml({ compact: true }) : '')
      + knownPanelHtml(state.known)
      // F64: while the known-check is open the steps (and their sticky footer) step aside
      + (state.known && state.known.open ? '' : '<nav class="cp-sit-steps" aria-label="4 bước học tình huống">' + stepTabsHtml + '</nav>'
        + mainBody)
      + '</div>';
  }

  function mount(targetEl) {
    var doc = root && root.document;
    var el = targetEl || (doc && doc.getElementById && doc.getElementById('cpathSituationRoot'));
    if (!el || !root.JpathApi) return;

    var search = (root && root.location && root.location.search) ? String(root.location.search) : '';
    var mId = /[?&]id=([^&#]+)/.exec(search);
    var sitId = mId ? decodeURIComponent(mId[1]) : el.getAttribute('data-situation-id');
    if (!sitId) {
      el.innerHTML = '<p class="jp-alert" role="alert">Thiếu mã tình huống. Vui lòng quay lại lộ trình.</p>';
      return;
    }

    var sitData = null;
    var recorder = null;
    var activeStream = null;
    var chunks = [];
    var recordTimer = null;

    var state = {
      activeStep: 'WORDS',
      cardIdx: 0,
      cardFlipped: false,
      meaningIdx: 0,
      meaningAnswers: {},
      notesOpen: false,
      playingLineIdx: -1,
      playingAll: false,
      turnIdx: 0,
      roleplayAnswers: {},
      speakIdx: 0,
      speakScores: {},
      speakSkipped: {},
      anyMicSkipped: false,
      attemptsLeft: null,
      micUnavailable: !hasMicrophone(root),
      micError: '',
      hideSpeakText: false,
      recording: false,
      scoring: false,
      showDoneScreen: false,
      awardedCp: 0,
      missingNote: '',
      lastSpeakBody: null,
      display: { furigana: true, romaji: false, translation: true }
    };

    function render() {
      el.setAttribute('aria-busy', 'false');
      el.innerHTML = situationHtml(sitData, state);
    }

    function clearRecordTimer() {
      if (recordTimer) {
        clearTimeout(recordTimer);
        recordTimer = null;
      }
    }

    function releaseStream() {
      if (activeStream) {
        try {
          activeStream.getTracks().forEach(function (trk) { trk.stop(); });
        } catch (e) { /* ignore */ }
        activeStream = null;
      }
    }

    function cancelRecordingSilently() {
      clearRecordTimer();
      if (recorder) {
        try {
          recorder.onstop = null;
          if (recorder.state !== 'inactive') recorder.stop();
        } catch (e) { /* ignore */ }
        recorder = null;
      }
      releaseStream();
      state.recording = false;
    }

    function buildSpeakStepPayload() {
      var lLines = learnerLines(sitData);
      var scores = [];
      var skippedAny = !!state.anyMicSkipped;
      for (var i = 0; i < lLines.length; i++) {
        if (state.speakSkipped[i]) {
          skippedAny = true;
        } else if (state.speakScores[i] && typeof state.speakScores[i].score === 'number') {
          scores.push(Math.round(state.speakScores[i].score));
        } else {
          skippedAny = true;
        }
      }
      if (skippedAny || !scores.length) {
        return { skippedMic: true };
      }
      var average = scores.reduce(function (sum, score) { return sum + score; }, 0) / scores.length;
      return { clientAverage: Math.round(average) };
    }

    function submitStep(stepKey, nextStepKey) {
      var body = { correct: 1, total: 1 };
      if (stepKey === 'ROLEPLAY') {
        var turns = list(sitData && sitData.challenge && sitData.challenge.turns);
        var corr = 0;
        turns.forEach(function (trn, idx) {
          if (Number(state.roleplayAnswers[idx]) === Number(trn.answerIndex)) corr++;
        });
        body = { correct: corr, total: Math.max(1, turns.length) };
      } else if (stepKey === 'SPEAK') {
        cancelRecordingSilently();
        body = buildSpeakStepPayload();
        state.lastSpeakBody = body;
      }
      root.JpathApi.send('POST', '/api/learning/comm/situations/' + encodeURIComponent(sitData.id) + '/steps/' + stepKey, body)
        .then(function (res) {
          if (res && res.steps) sitData.steps = res.steps;
          else {
            sitData.steps = sitData.steps || {};
            sitData.steps[stepKey.toLowerCase()] = true;
          }
          // CP comes only from the server (2 CP the first time all 4 steps are done)
          if (res && typeof res.cp === 'number') state.awardedCp = res.cp;
          if (stepKey === 'SPEAK' && res) state.lastSpeakBody = { skippedMic: res.speakBest == null, speakBest: res.speakBest };
          var missing = STEPS_ORDER.filter(function (k) { return !(sitData.steps || {})[k.toLowerCase()]; });
          if (missing.length === 0 && (stepKey === 'SPEAK' || (res && res.situationDone))) {
            state.missingNote = '';
            state.showDoneScreen = true;
          } else if (stepKey === 'SPEAK') {
            // a step was left out: go back to it instead of a "done" screen
            state.missingNote = 'Còn bước chưa xong. Làm nốt bước này để hoàn thành tình huống.';
            state.activeStep = missing[0];
          } else if (nextStepKey) {
            state.activeStep = nextStepKey;
          }
          render();
        }, function (error) {
          state.missingNote = error && error.message || 'Chưa lưu được bước học. Hãy thử lại.';
          render();
        });
    }

    function runVoiceScore(audioBlob) {
      var lLines = learnerLines(sitData);
      var target = lLines[state.speakIdx] || { ja: '' };
      var blob = audioBlob;
      if (!blob && typeof Blob === 'function') {
        blob = new Blob([], { type: 'audio/webm' });
      }
      state.recording = false;
      state.scoring = true;
      render();

      // F72: send WAV 16 kHz mono so the server scores without ffmpeg
      var toWav = root.JpathWav && blob ? root.JpathWav.toWav(blob) : Promise.resolve({ blob: blob, name: 'speech.webm' });
      toWav.then(function (clip) {
        var fd = new FormData();
        fd.append('text', target.ja || '');
        if (target.order != null) fd.append('lineId', sitData.id + '-L' + target.order);
        if (clip.blob) fd.append('audio', clip.blob, clip.name);
        return root.JpathApi.upload('/api/learning/comm/speak', fd);
      }).then(function (res) {
        state.scoring = false;
        state.micError = '';
        var normalized = normalizeSpeakResponse(res, state);
        if (typeof normalized.attemptsLeft === 'number') {
          state.attemptsLeft = normalized.attemptsLeft;
        }
        state.speakScores[state.speakIdx] = normalized;
        render();
      }, function (err) {
        state.scoring = false;
        if (err && (err.status === 429 || (err.code && String(err.code).indexOf('LIMIT') >= 0))) {
          state.attemptsLeft = 0;
          state.micError = (err && err.message) || 'Đã hết lượt chấm phát âm hôm nay (0 lần còn lại). Hãy bấm "Bỏ qua" để tiếp tục.';
        } else {
          state.micError = (err && err.message) || 'Chưa chấm được phát âm lúc này. Bạn có thể ghi âm lại hoặc bấm "Bỏ qua".';
        }
        render();
      });
    }

    function stopRecording() {
      clearRecordTimer();
      if (!recorder) {
        state.recording = false;
        render();
        return;
      }
      try {
        if (recorder.state !== 'inactive') {
          recorder.stop();
        }
      } catch (e) {
        releaseStream();
        state.recording = false;
        render();
      }
    }

    function startRecording() {
      if (state.attemptsLeft === 0) {
        state.micError = 'Đã hết lượt chấm phát âm hôm nay (0 lần còn lại). Hãy bấm "Bỏ qua" để tiếp tục.';
        render();
        return;
      }
      if (!hasMicrophone(root)) {
        state.micUnavailable = true;
        state.micError = 'Thiết bị không có micro hoặc trình duyệt không hỗ trợ ghi âm. Hãy bấm "Bỏ qua" để tiếp tục.';
        render();
        return;
      }
      state.micError = '';
      root.navigator.mediaDevices.getUserMedia({ audio: true }).then(function (stream) {
        activeStream = stream;
        chunks = [];
        try {
          recorder = new root.MediaRecorder(stream);
        } catch (errRec) {
          releaseStream();
          state.micUnavailable = true;
          state.micError = 'Không khởi tạo được bộ ghi âm. Bạn có thể bấm "Bỏ qua" để tiếp tục.';
          render();
          return;
        }
        recorder.ondataavailable = function (e) {
          if (e && e.data && e.data.size) chunks.push(e.data);
        };
        recorder.onstop = function () {
          clearRecordTimer();
          var mimeType = (recorder && recorder.mimeType) || 'audio/webm';
          recorder = null;
          releaseStream();
          var blob = typeof Blob === 'function' ? new Blob(chunks, { type: mimeType }) : null;
          runVoiceScore(blob);
        };
        recorder.start();
        state.micUnavailable = false;
        state.recording = true;
        render();
        clearRecordTimer();
        recordTimer = setTimeout(function () {
          stopRecording();
        }, 10000);
      }, function () {
        releaseStream();
        state.recording = false;
        state.micUnavailable = true;
        state.micError = 'Không tìm thấy micro hoặc bạn đã từ chối quyền micro. Bạn có thể bấm "Bỏ qua" để tiếp tục.';
        render();
      });
    }

    el.addEventListener('click', function (ev) {
      var t = ev.target;
      if (!t || typeof t.closest !== 'function' || !sitData) return;

      if (t.closest('[data-cp-known-open]')) {
        state.known = { open: true, loading: true, answers: {} };
        render();
        root.JpathApi.get('/api/learning/comm/situations/' + encodeURIComponent(sitId) + '/known-check').then(function (d) {
          state.known.loading = false;
          state.known.data = d || {};
          render();
        }).catch(function (error) {
          state.known.loading = false;
          state.known.error = (error && error.message) || 'Không mở được bài kiểm tra.';
          render();
        });
        return;
      }
      if (t.closest('[data-cp-known-close]')) {
        state.known = null;
        render();
        return;
      }
      var knownPick = t.closest('[data-cp-known-pick]');
      if (knownPick && state.known) {
        state.known.answers[knownPick.getAttribute('data-cp-known-pick')] = Number(knownPick.getAttribute('data-cp-known-idx'));
        render();
        return;
      }
      var knownSubmit = t.closest('[data-cp-known-submit]');
      if (knownSubmit && state.known && !knownSubmit.disabled) {
        knownSubmit.disabled = true;
        root.JpathApi.send('POST', '/api/learning/comm/situations/' + encodeURIComponent(sitId) + '/known-check',
          { answers: state.known.answers }).then(function (r) {
          state.known.result = r || {};
          if (r && r.passed) {
            sitData.steps = { words: true, listen: true, roleplay: true, speak: true };
          }
          render();
        }).catch(function (error) {
          state.known.error = (error && error.message) || 'Chưa nộp được bài. Thử lại sau.';
          render();
        });
        return;
      }

      var playBtn = t.closest('[data-cp-play-text]');
      if (playBtn) {
        ev.stopPropagation();
        speakJapanese(playBtn.getAttribute('data-cp-play-text'), playBtn.getAttribute('data-cp-play-audio'));
        return;
      }

      if (t.closest('[data-cp-flip-card]') || t.closest('[data-cp-flip-btn]')) {
        state.cardFlipped = !state.cardFlipped;
        render();
        return;
      }

      var jumpCard = t.closest('[data-cp-jump-card]');
      if (jumpCard) {
        state.cardIdx = Number(jumpCard.getAttribute('data-cp-jump-card')) || 0;
        state.cardFlipped = false;
        render();
        return;
      }

      if (t.closest('[data-cp-card-prev]')) {
        state.cardIdx = Math.max(0, state.cardIdx - 1);
        state.cardFlipped = false;
        render();
        return;
      }

      if (t.closest('[data-cp-card-next]')) {
        var deckLen = buildDeck(sitData).length;
        state.cardIdx = Math.min(deckLen - 1, state.cardIdx + 1);
        state.cardFlipped = false;
        render();
        return;
      }

      var stepTab = t.closest('[data-cp-goto-step]');
      if (stepTab) {
        cancelRecordingSilently();
        var targetStep = stepTab.getAttribute('data-cp-goto-step') || 'WORDS';
        if (state.showDoneScreen && targetStep === 'SPEAK') {
          state.speakIdx = 0;
          state.speakSkipped = {};
          state.anyMicSkipped = false;
        }
        state.showDoneScreen = false;
        state.activeStep = targetStep;
        render();
        return;
      }

      var meaningPick = t.closest('[data-cp-meaning-pick]');
      if (meaningPick && !meaningPick.disabled) {
        state.meaningAnswers[state.meaningIdx || 0] = Number(meaningPick.getAttribute('data-cp-meaning-pick'));
        render();
        return;
      }
      if (t.closest('[data-cp-meaning-next]')) { state.meaningIdx = (state.meaningIdx || 0) + 1; render(); return; }
      if (t.closest('[data-cp-meaning-prev]')) { state.meaningIdx = Math.max(0, (state.meaningIdx || 0) - 1); render(); return; }
      if (t.closest('[data-cp-notes-toggle]')) { state.notesOpen = !state.notesOpen; render(); return; }

      var dispBtn = t.closest('[data-cp-disp-toggle]');
      if (dispBtn) {
        var dk = dispBtn.getAttribute('data-cp-disp-toggle');
        state.display[dk] = !state.display[dk];
        render();
        return;
      }

      var lineBtn = t.closest('[data-cp-listen-line]');
      if (lineBtn) {
        var lIdx = Number(lineBtn.getAttribute('data-cp-listen-line')) || 0;
        var ln = list(sitData.lines)[lIdx];
        if (ln) {
          state.playingLineIdx = lIdx;
          render();
          speakJapanese(ln.ja, ln.audioUrl, function () {
            state.playingLineIdx = -1;
            render();
          });
        }
        return;
      }

      if (t.closest('[data-cp-play-all]')) {
        var allLines = list(sitData.lines);
        if (!allLines.length || state.playingAll) return;
        state.playingAll = true;
        var curI = 0;
        var playNext = function () {
          if (curI >= allLines.length) {
            state.playingAll = false;
            state.playingLineIdx = -1;
            render();
            return;
          }
          state.playingLineIdx = curI;
          render();
          var lnObj = allLines[curI++];
          speakJapanese(lnObj.ja, lnObj.audioUrl, playNext);
        };
        playNext();
        return;
      }

      var rpOpt = t.closest('[data-cp-roleplay-opt]');
      if (rpOpt) {
        state.roleplayAnswers[state.turnIdx] = Number(rpOpt.getAttribute('data-cp-roleplay-opt'));
        render();
        return;
      }

      if (t.closest('[data-cp-roleplay-next]')) {
        state.turnIdx += 1;
        render();
        return;
      }

      if (t.closest('[data-cp-toggle-hide-text]')) {
        state.hideSpeakText = !state.hideSpeakText;
        render();
        return;
      }

      if (t.closest('[data-cp-record]')) {
        if (!state.recording) {
          startRecording();
        } else {
          stopRecording();
        }
        return;
      }

      if (t.closest('[data-cp-speak-retry]')) {
        cancelRecordingSilently();
        delete state.speakScores[state.speakIdx];
        render();
        return;
      }

      if (t.closest('[data-cp-speak-skip]')) {
        cancelRecordingSilently();
        state.speakSkipped[state.speakIdx] = true;
        state.anyMicSkipped = true;
        var totalSpeak = learnerLines(sitData).length;
        if (state.speakIdx + 1 < totalSpeak) {
          state.speakIdx += 1;
          render();
        } else {
          submitStep('SPEAK', '');
        }
        return;
      }

      if (t.closest('[data-cp-speak-next]')) {
        cancelRecordingSilently();
        state.speakIdx += 1;
        render();
        return;
      }

      var finishBtn = t.closest('[data-cp-finish-step]');
      if (finishBtn) {
        var finStep = finishBtn.getAttribute('data-cp-finish-step');
        var nextIdx = STEPS_ORDER.indexOf(finStep) + 1;
        var nextKey = nextIdx < STEPS_ORDER.length ? STEPS_ORDER[nextIdx] : '';
        submitStep(finStep, nextKey);
      }
    });

    if (doc) {
      doc.addEventListener('keydown', function (ev) {
        if (ev.code === 'Space' && state.activeStep === 'WORDS' && !state.showDoneScreen) {
          var activeTag = doc.activeElement && doc.activeElement.tagName;
          if (activeTag !== 'INPUT' && activeTag !== 'TEXTAREA') {
            ev.preventDefault();
            state.cardFlipped = !state.cardFlipped;
            render();
          }
        }
      });
    }

    if (root.CpathAudio) root.CpathAudio.bind(el, root.CpathAudio.saveToServer);

    function load() {
      root.JpathApi.get('/api/learning/comm/situations/' + encodeURIComponent(sitId)).then(function (data) {
        if (!data) throw new Error('Không có dữ liệu tình huống.');
        sitData = data;
        // F61: open with the display saved in Cài đặt (the buttons on the page still flip it for this visit)
        if (data.display) {
          state.display = { furigana: data.display.furigana !== false, romaji: data.display.romaji === true,
            translation: data.display.translation !== false, fullRomaji: data.display.fullRomaji === true };
          if (root.CpathAudio) root.CpathAudio.fromDisplay(data.display);
        }
        var lnL = (data.lines || []).filter(function (ln) { return ln && ln.learner === true; })[0];
        learnerName = lnL && lnL.speaker ? lnL.speaker : 'リン';
        render();
      }).catch(function (error) {
        if (error && error.code === 'CPATH_PREMIUM_REQUIRED') {
          // owner 11/11: the communication path is Premium
          el.innerHTML = '<p class="jp-alert" role="alert">' + esc(error.message)
            + ' <a class="jp-btn jp-btn--primary" href="/premium">Nâng cấp Premium</a></p>';
          return;
        }
        el.innerHTML = '<p class="jp-alert" role="alert">' + esc(error && error.message || 'Không tải được tình huống.')
          + ' <button type="button" class="jp-btn" data-cp-retry>Thử lại</button></p>';
        el.querySelector('[data-cp-retry]').addEventListener('click', load);
      });
    }
    load();
  }

  return {
    situationHtml: situationHtml,
    knownPanelHtml: knownPanelHtml,
    toRubyHtml: toRubyHtml,
    buildDeck: buildDeck,
    meaningItems: meaningItems,
    meaningOptions: meaningOptions,
    toRomaji: toRomaji,
    stepTitle: stepTitle,
    hasMicrophone: hasMicrophone,
    normalizeSpeakResponse: normalizeSpeakResponse,
    mount: mount
  };
});
