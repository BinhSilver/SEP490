/* cpath-situation.js — Trang Học Tình Huống 4 Bước (situation.html):
 *   1. WORDS    — Từ và mẫu câu (thẻ lật qua lại, có nút nghe)
 *   2. LISTEN   — Nghe hội thoại từng câu (phát lần lượt, furigana/bản dịch theo display, rê chuột hiện dịch)
 *   3. ROLEPLAY — Nhập vai chọn câu của リン (1 trong 3 câu, kèm giải thích whyVi)
 *   4. SPEAK    — Luyện nói câu của リン (máy phát lời bên kia, nút ẩn chữ, chấm điểm từng câu, <70 nút Nói lại, nút Bỏ qua)
 * Xong cả 4 bước hiện nút "Nói chuyện tự do với AI" (aiCallUrl) và "Về lộ trình".
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

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function list(v) { return Array.isArray(v) ? v : []; }

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
      return root.JpathFurigana.toRuby(normalized);
    }
    var rx = /([㐀-鿿々ヶ]+)\(([ぁ-ゖァ-ヺー]+)\)/g;
    var out = '';
    var last = 0;
    var m;
    while ((m = rx.exec(normalized)) !== null) {
      out += esc(normalized.slice(last, m.index)) + '<ruby>' + esc(m[1]) + '<rt>' + esc(m[2]) + '</rt></ruby>';
      last = rx.lastIndex;
    }
    return out + esc(normalized.slice(last));
  }

  function speakJapanese(text, audioUrl, onEnd) {
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

    var cardBody = !flipped
      ? '<span class="cp-flashcard__tag">' + (card.kind === 'WORD' ? 'Từ vựng' : 'Mẫu câu') + ' · ' + (idx + 1) + '/' + deck.length + '</span>'
        + '<div class="cp-flashcard__ja" lang="ja">' + esc(card.ja) + '</div>'
        + (state.display.furigana && card.reading ? '<div class="cp-flashcard__rd" lang="ja">' + esc(card.reading) + '</div>' : '')
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

    return '<div class="cp-sit-stage">'
      + '<div class="cp-deck-switch">'
      + '<div class="cp-deck-tabs" role="tablist">'
      + '<button type="button" class="cp-deck-tab' + (idx < wordCount ? ' is-active' : '') + '" data-cp-jump-card="0">Từ vựng (' + wordCount + ')</button>'
      + '<button type="button" class="cp-deck-tab' + (idx >= wordCount ? ' is-active' : '') + '" data-cp-jump-card="' + wordCount + '">Mẫu câu (' + Math.max(0, deck.length - wordCount) + ')</button>'
      + '</div>'
      + '<span class="cp-survey__sub">Thẻ <strong>' + (idx + 1) + '/' + deck.length + '</strong></span>'
      + '</div>'
      + '<div class="cp-flashcard" data-cp-flip-card tabindex="0" role="button" aria-label="Thẻ học lật hai chiều">'
      + cardBody
      + '</div>'
      + '</div>'
      + '<footer class="cp-sit-foot">'
      + '<button type="button" class="cp-btn-ghost" data-cp-card-prev' + (idx <= 0 ? ' disabled' : '') + '>‹ Thẻ trước</button>'
      + '<button type="button" class="cp-btn-ghost" data-cp-flip-btn>' + (flipped ? 'Xem mặt trước (Space)' : 'Lật xem nghĩa (Space)') + '</button>'
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
        + '<span class="cp-line__speaker">' + esc(ln.speaker) + '</span>'
        + '<div class="cp-line__content">'
        + '<div class="cp-line__ja" lang="ja">' + toRubyHtml(furiSource, showFuri) + '</div>'
        + '<div class="cp-line__vi">' + esc(ln.vi) + '</div>'
        + '</div>'
        + '<button type="button" class="cp-audio-btn" data-cp-listen-line="' + idx + '" aria-label="Nghe câu ' + (idx + 1) + '">'
        + svgSpeaker()
        + '</button>'
        + '</li>';
    }).join('');

    return '<div class="cp-sit-stage">'
      + '<div class="cp-listen-bar" data-tip="Rê chuột vào câu bất kỳ để xem bản dịch tiếng Việt" title="Rê chuột vào câu bất kỳ để xem bản dịch tiếng Việt">'
      + '<div><strong>Bối cảnh:</strong> ' + esc(sit.situationVi || sit.titleVi)
      + ' <span class="cp-survey__sub">(Rê chuột vào câu bất kỳ để xem bản dịch tiếng Việt)</span></div>'
      + '<button type="button" class="cp-btn-primary" data-cp-play-all>'
      + svgSpeaker()
      + '<span>' + (state.playingAll ? 'Đang phát lần lượt…' : 'Phát lần lượt cả hội thoại') + '</span>'
      + '</button>'
      + '</div>'
      + '<ol class="' + listCls + '">' + rowsHtml + '</ol>'
      + '</div>'
      + '<footer class="cp-sit-foot">'
      + '<button type="button" class="cp-btn-ghost" data-cp-goto-step="WORDS">‹ Quay lại Từ & mẫu câu</button>'
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
        + (state.display.translation ? '<div class="cp-line__vi">' + esc(prevLine.vi) + '</div>' : '')
        + '</div>'
      : '<div class="cp-roleplay-context"><span class="cp-survey__sub">Mở đầu cuộc hội thoại — Đến lượt <strong>リン</strong> nói câu đầu tiên:</span></div>';

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
        + (isAnswered || state.display.translation ? '<div class="cp-line__vi">' + esc(opt.vi) + '</div>' : '')
        + '</div>'
        + '</button>';
    }).join('');

    var whyText = turn.whyVi || turn.why_vi || '';
    var whyHtml = isAnswered
      ? '<div class="cp-why-box" role="status">'
        + '<img src="' + esc(kitsuSrc(ansCorrect ? 'kitsu-cheer-30.webp' : 'kitsu-thinking-30.webp')) + '" width="44" height="44" alt="" />'
        + '<div><strong>' + (ansCorrect ? 'Chính xác!' : 'Chưa đúng rồi:') + '</strong> ' + esc(whyText) + '</div>'
        + '</div>'
      : '';

    return '<div class="cp-sit-stage">'
      + '<div class="cp-deck-switch">'
      + '<strong>Nhập vai リン · Lượt ' + (tIdx + 1) + '/' + turns.length + '</strong>'
      + '<span class="cp-survey__sub">Chọn 1 trong 3 câu trả lời phù hợp nhất</span>'
      + '</div>'
      + promptHtml
      + (isAnswered && !ansCorrect
        ? '<div class="jp-review__rows cp-roleplay-review">'
          + '<div class="jp-review__row jp-review__row--picked jp-bad"><span class="jp-review__tag">Bạn chọn</span><span class="jp-review__val" lang="ja">' + esc((list(turn.options)[answeredIdx] || {}).ja || '—') + '</span></div>'
          + '<div class="jp-review__row jp-review__row--answer jp-ok"><span class="jp-review__tag">Đáp án</span><span class="jp-review__val" lang="ja">' + esc((list(turn.options)[turn.answerIndex] || {}).ja || '') + '</span></div>'
          + '</div>'
          + '<details class="jp-review__more"><summary class="jp-link-btn">Xem cả câu</summary><div class="cp-options" role="group" aria-label="Chọn câu trả lời của リン">' + optsHtml + '</div></details>'
        : '<div class="cp-options" role="group" aria-label="Chọn câu trả lời của リン">' + optsHtml + '</div>')
      + whyHtml
      + '</div>'
      + '<footer class="cp-sit-foot">'
      + '<button type="button" class="cp-btn-ghost" data-cp-goto-step="LISTEN">‹ Quay lại Nghe</button>'
      + (tIdx + 1 < turns.length
        ? '<button type="button" class="cp-btn-primary" data-cp-roleplay-next' + (!isAnswered ? ' disabled' : '') + '>Lượt tiếp theo ›</button>'
        : '<button type="button" class="cp-btn-primary" data-cp-finish-step="ROLEPLAY"' + (!isAnswered ? ' disabled' : '') + '>Hoàn thành Bước 3 · Sang Luyện nói ›</button>')
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
      return '<div class="cp-sit-stage"><p>Không có câu luyện nói.</p></div>'
        + '<footer class="cp-sit-foot"><button type="button" class="cp-btn-primary" data-cp-finish-step="SPEAK">Hoàn thành tình huống</button></footer>';
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
        + '<img src="' + esc(kitsuSrc(isPass ? 'kitsu-celebrate-30.webp' : 'kitsu-encouraging.webp')) + '" width="48" height="48" alt="" />'
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
        : (outOfAttempts ? 'Đã hết lượt ghi âm hôm nay' : '● Bấm ghi âm câu của リン'));

    return '<div class="cp-sit-stage">'
      + '<div class="cp-deck-switch">'
      + '<div style="display:flex;align-items:center;gap:10px;flex-wrap:wrap;">'
      + '<strong>Đóng vai リン · Câu ' + (sIdx + 1) + '/' + lLines.length + '</strong>'
      + attemptsInfoHtml
      + '</div>'
      + '<button type="button" class="cp-btn-ghost" data-cp-toggle-hide-text aria-pressed="' + (state.hideSpeakText ? 'true' : 'false') + '">'
      + (state.hideSpeakText ? 'Hiện chữ câu của リン' : 'Ẩn chữ để tự nhớ')
      + '</button>'
      + '</div>'
      + partnerHtml
      + '<div class="cp-speak-box">'
      + '<div class="cp-speak-target' + (state.hideSpeakText ? ' is-hidden-text' : '') + '">'
      + '<span class="cp-survey__sub">Câu của <strong>リン</strong>:</span>'
      + '<div class="cp-line__ja cp-speak-target__ja" lang="ja">' + toRubyHtml(targetLine.furigana || targetLine.ja, state.display.furigana) + '</div>'
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
      : (state.lastSpeakBody && typeof state.lastSpeakBody.score === 'number'
        ? '<p class="cp-survey__sub">Điểm nói thấp nhất các câu trong tình huống: <strong>' + esc(state.lastSpeakBody.score) + '/100</strong>.</p>'
        : '');
    return '<div class="cp-sit-stage" style="align-items:center;justify-content:center;text-align:center;">'
      + '<img src="' + esc(kitsuSrc('kitsu-celebrate-30.webp')) + '" width="78" height="78" alt="Kitsu chúc mừng" />'
      + '<h2 class="cp-survey__title">Hoàn thành tình huống: ' + esc(sit.titleVi) + '!</h2>'
      + '<p class="cp-survey__sub">Bạn đã hoàn thành trọn vẹn 4 bước (Từ & mẫu câu · Nghe · Nhập vai · Nói)'
      + (state.awardedCp ? (' và nhận được <strong>+' + esc(state.awardedCp) + ' CP</strong>') : '') + '.</p>'
      + skippedNote
      + '<div style="display:flex;flex-wrap:wrap;gap:12px;justify-content:center;margin-top:8px;">'
      + (aiHref ? '<a class="cp-btn-primary" href="' + esc(aiHref) + '">Nói chuyện tự do với AI</a>' : '')
      + '<a class="cp-btn-ghost" href="/learning/comm/weekly?mode=quick&amp;code=' + encodeURIComponent(sit.id) + '">Luyện nhanh</a>'
      + '<a class="cp-btn-ghost" href="' + esc(pathHref) + '">Về lộ trình</a>'
      + '</div>'
      + '</div>';
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
        + '<span>' + esc(STEP_TITLES[key]) + '</span>'
        + '</button>';
    }).join('');

    var mainBody = '';
    if (state.showDoneScreen) {
      mainBody = renderDoneScreen(s, state);
    } else if (state.activeStep === 'WORDS') {
      mainBody = renderStepWords(s, state);
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
      + '<button type="button" class="cp-btn-ghost" data-cp-disp-toggle="furigana" aria-pressed="' + (state.display.furigana ? 'true' : 'false') + '">Furigana</button>'
      + '<button type="button" class="cp-btn-ghost" data-cp-disp-toggle="translation" aria-pressed="' + (state.display.translation ? 'true' : 'false') + '">Bản dịch</button>'
      + '<a class="cp-btn-ghost" href="' + esc(pageUrl('path')) + '">Về lộ trình</a>'
      + '</div>'
      + '</header>'
      + '<nav class="cp-sit-steps" aria-label="4 bước học tình huống">' + stepTabsHtml + '</nav>'
      + mainBody
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
      var minScore = Math.min.apply(null, scores);
      return { score: minScore };
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
        }, function () {
          sitData.steps = sitData.steps || {};
          sitData.steps[stepKey.toLowerCase()] = true;
          if (stepKey === 'SPEAK') state.showDoneScreen = true;
          else if (nextStepKey) state.activeStep = nextStepKey;
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
      var fd = new FormData();
      fd.append('text', target.ja || '');
      if (target.order != null) fd.append('lineId', sitData.id + '-L' + target.order);
      if (blob) {
        fd.append('audio', blob, 'speech.webm');
      }
      state.recording = false;
      state.scoring = true;
      render();

      root.JpathApi.upload('/api/learning/comm/speak', fd).then(function (res) {
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

    function load() {
      root.JpathApi.get('/api/learning/comm/situations/' + encodeURIComponent(sitId)).then(function (data) {
        if (!data) throw new Error('Không có dữ liệu tình huống.');
        sitData = data;
        render();
      }).catch(function (error) {
        el.innerHTML = '<p class="jp-alert" role="alert">' + esc(error && error.message || 'Không tải được tình huống.')
          + ' <button type="button" class="jp-btn" data-cp-retry>Thử lại</button></p>';
        el.querySelector('[data-cp-retry]').addEventListener('click', load);
      });
    }
    load();
  }

  return {
    situationHtml: situationHtml,
    toRubyHtml: toRubyHtml,
    buildDeck: buildDeck,
    hasMicrophone: hasMicrophone,
    normalizeSpeakResponse: normalizeSpeakResponse,
    mount: mount
  };
});
