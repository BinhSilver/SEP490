(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.InteractiveDialogueStudyPage = api;
  if (root && root.document) {
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', api.mount, { once: true });
    else api.mount();
  }
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';
  // [JLAS-W0193] Task 7 — Dialogue study page: read-only canonical script + server-timed completion. Unlike
  // Vocabulary/Kanji/Grammar there is no micro-check here — completion is server-computed from periodic
  // visible-heartbeat signals (this file never sends a duration or a "completed" flag, only an idempotency
  // token) plus a dedicated reached-bottom event. The server owns accumulated time, the cap/reset arithmetic
  // and the completion decision throughout; this file only renders what the API returns.

  var HEARTBEAT_INTERVAL_MS = 15000;

  function esc(value) { return String(value == null ? '' : value).replace(/[&<>"']/g, function (c) { return ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#039;'})[c]; }); }
  function num(value) { value = Number(value); return Number.isFinite(value) ? value : 0; }
  function list(value) { return Array.isArray(value) ? value : []; }
  function activityId() {
    if (typeof window !== 'undefined' && window.location) return new URLSearchParams(window.location.search).get('activity');
    return null;
  }

  function viewModel(data) {
    data = data || {};
    var lines = list(data.lines).slice().sort(function (a, b) { return num(a.orderIndex) - num(b.orderIndex); });
    // Owner 2026-10-19: communication dialogues are practised turn by turn instead of timed.
    var lineByLine = data.lineByLinePractice === true;
    var required = lineByLine ? 0 : (num(data.requiredSeconds) || 180);
    var accumulated = num(data.accumulatedSeconds);
    var practicedIds = list(data.practicedLineIds).map(String);
    var totalLines = data.totalLines != null ? num(data.totalLines) : lines.length;
    var practicedCount = data.practicedLines != null ? num(data.practicedLines)
      : lines.filter(function (line) { return practicedIds.indexOf(String(line.lineId)) !== -1; }).length;
    var percent = lineByLine
      ? (totalLines ? Math.min(100, Math.round(practicedCount / totalLines * 100)) : 0)
      : (required ? Math.min(100, Math.round(accumulated / required * 100)) : 0);
    var aids = data.aids && typeof data.aids === 'object' ? {
      label: data.aids.label || 'Nội dung bổ sung J-LAS', reviewStatus: data.aids.reviewStatus || null,
      phrases: list(data.aids.phrases).filter(function (p) { return p && p.japanese; }),
      patterns: list(data.aids.patterns).filter(function (p) { return p && p.pattern; })
    } : null;
    // §2.24 "Từ & cụm từ" flip cards: counts come from the progress result or from the study state's cards.
    var totalPhrases = data.totalPhrases != null ? num(data.totalPhrases) : (aids ? aids.phrases.length : 0);
    var flippedPhrases = data.flippedPhrases != null ? num(data.flippedPhrases)
      : (aids ? aids.phrases.filter(function (p) { return p.flipped === true; }).length : 0);
    return {
      flippedPhrases: flippedPhrases, totalPhrases: totalPhrases,
      activityId: data.activityId, activityState: data.activityState, sourceRevision: data.sourceRevision,
      requiredSeconds: required, accumulatedSeconds: accumulated,
      remainingSeconds: Math.max(0, num(data.remainingSeconds)), reachedBottom: !!data.reachedBottom,
      completed: !!data.completed, percent: percent, lines: lines,
      lineByLinePractice: lineByLine, practicedLineIds: practicedIds, practicedCount: practicedCount,
      totalLines: totalLines, aids: aids
    };
  }

  var RUBY = /\{([^{}|]+)\|([^{}|]+)\}/g;
  /** {漢字|かな} groups → escaped ruby HTML, only when stripping the groups gives back the line itself. */
  function rubyHtml(markup, japanese) {
    var text = String(japanese == null ? '' : japanese);
    if (!markup || String(markup).replace(RUBY, '$1') !== text) return esc(text);
    var html = '';
    var last = 0;
    String(markup).replace(RUBY, function (match, base, reading, offset) {
      html += esc(markup.slice(last, offset)) + '<ruby>' + esc(base) + '<rt>' + esc(reading) + '</rt></ruby>';
      last = offset + match.length;
      return match;
    });
    return html + esc(String(markup).slice(last));
  }

  function hasFurigana(v) { return v.lines.some(function (line) { return !!line.furigana; }); }

  function speakButton(text, label) {
    return '<button type="button" class="ilp-dialogue__audio" data-play-audio="tts:ja-JP" data-speak-rate="0.8"' +
      ' data-speak-text="' + esc(text) + '" aria-label="' + esc(label) + '"><span aria-hidden="true">🔊</span></button>';
  }

  /** Owner 2026-10-19 (C2, §2.24): flip cards of the words and phrases taken from this dialogue, before the script.
   * The front shows the Japanese and its reading; flipping reveals the meaning and is saved by the server. */
  function renderPhrases(v) {
    if (!v.aids || !v.aids.phrases.length) return '';
    var items = v.aids.phrases.map(function (phrase, position) {
      var index = phrase.index != null ? num(phrase.index) : position;
      var flipped = phrase.flipped === true;
      return '<li class="ilp-dialogue__phrase' + (flipped ? ' is-flipped' : '') + '">' +
        '<button type="button" class="ilp-dialogue__card" data-flip-phrase="' + index + '" aria-pressed="' + flipped +
        '" aria-label="' + (flipped ? 'Đã lật thẻ ' : 'Lật thẻ ') + esc(phrase.japanese) + '">' +
        '<span class="ilp-dialogue__phrase-ja" lang="ja">' + esc(phrase.japanese) + '</span>' +
        (phrase.reading ? '<span class="ilp-dialogue__phrase-reading" lang="ja">' + esc(phrase.reading) + '</span>' : '') +
        (flipped ? '<span class="ilp-dialogue__phrase-vi">' + esc(phrase.meaningVi || '') + '</span>'
          : '<span class="ilp-dialogue__phrase-hint">Bấm để lật xem nghĩa</span>') + '</button>' +
        '<span class="ilp-dialogue__phrase-actions">' + speakButton(phrase.japanese, 'Nghe ' + phrase.japanese) +
        (phrase.lineId != null ? '<button type="button" class="ilp-dialogue__jump" data-jump-line="' + esc(phrase.lineId) +
          '">Xem trong hội thoại</button>' : '') + '</span></li>';
    }).join('');
    return '<details class="ilp-dialogue__phrases" open><summary>Từ &amp; cụm từ trong hội thoại (' + v.flippedPhrases + '/' +
      v.totalPhrases + ' thẻ đã lật)</summary><p class="ilp-dialogue__supplement">' + esc(v.aids.label) + '</p><ul>' +
      items + '</ul></details>';
  }

  var CHALLENGE_CTA = '<p class="ilp-challenge__cta"><button type="button" class="ilp-check" data-challenge-start>' +
    'Thử thách vượt ải (+2 CP lần đầu)</button> <span>Trò chơi tùy chọn — không ảnh hưởng việc hoàn thành bài.</span></p>';

  /** Owner 2026-10-19 (G1–G3): the dialogue runs turn by turn; at the learner's turn they pick the right reply (1 of 3).
   * {@code state}: {turn, answers, feedback?: {chosen, correct, correctOptionId, explanationVi}}. Correctness comes only
   * from the server's answer to a choice; options never carry it. */
  function renderChallenge(view, lines, state, prefs) {
    prefs = prefs || {};
    state = state || { turn: 0, answers: [] };
    var close = '<button type="button" class="ilp-check ilp-check--ghost" data-challenge-close>Xem lại hội thoại</button>';
    var turns = view && view.available ? list(view.turns) : [];
    if (!turns.length) {
      return '<section class="ilp-dialogue ilp-challenge"><h1>Thử thách vượt ải</h1><p>Tình huống này chưa có thử thách.</p>' + close + '</section>';
    }
    var index = Math.max(0, Math.min(num(state.turn), turns.length - 1));
    var turn = turns[index];
    lines = list(lines);
    var stop = lines.findIndex(function (line) { return String(line.lineId) === String(turn.lineId); });
    var before = lines.slice(0, stop < 0 ? 0 : stop).map(function (line) {
      return renderLine(line, { showFurigana: prefs.showFurigana !== false, showTranslation: prefs.showTranslation });
    }).join('');
    var feedback = state.feedback || null;
    var options = list(turn.options).map(function (option) {
      var mark = feedback ? (option.id === feedback.correctOptionId ? ' is-right' : (option.id === feedback.chosen ? ' is-wrong' : '')) : '';
      if (mark === ' is-right') mark = ' is-correct';
      return '<li><button type="button" data-challenge-option="' + esc(option.id) + '" class="ilp-challenge__option' + mark + '"' +
        (feedback ? ' disabled' : '') + '><span lang="ja">' +
        (prefs.showFurigana !== false && option.furigana ? rubyHtml(option.furigana, option.japanese) : esc(option.japanese)) + '</span>' +
        (prefs.showTranslation !== false && option.meaningVi ? '<span class="ilp-challenge__vi">' + esc(option.meaningVi) + '</span>' : '') +
        '</button></li>';
    }).join('');
    var last = index === turns.length - 1;
    var after = !feedback ? '' : feedback.correct
      ? '<div class="ilp-challenge__feedback is-pass" role="status"><p><strong>Đúng rồi!</strong> ' + esc(feedback.explanationVi || '') + '</p>' +
        '<button type="button" class="ilp-check" data-challenge-next>' + (last ? 'Hoàn thành ải' : 'Lượt tiếp theo') + '</button></div>'
      : '<div class="ilp-challenge__feedback is-fail" role="status"><p><strong>Chưa đúng.</strong> Câu đúng đã được đánh dấu. ' +
        esc(feedback.explanationVi || '') + '</p><p>Làm sai không mất gì — bạn có thể thử lại bao nhiêu lần cũng được.</p>' +
        '<button type="button" class="ilp-check" data-challenge-retry>Thử lại ải</button></div>';
    return '<section class="ilp-dialogue ilp-challenge"><header class="ilp-study__head"><div><span class="ilp-kicker">THỬ THÁCH VƯỢT ẢI · LƯỢT ' +
      (index + 1) + '/' + turns.length + '</span><h1>Thử thách vượt ải</h1><p>Bạn đóng vai ' + esc(view.learnerRole || 'người học') +
      '. Tới lượt bạn, hãy chọn câu đáp đúng trong 3 câu.</p><p class="ilp-challenge__reward">' + (view.cleared
        ? 'Bạn đã vượt ải này — chơi lại thoải mái, không cộng thêm CP.'
        : 'Vượt ải lần đầu: +' + num(view.rewardCredits) + ' CP') + '</p></div></header>' +
      '<p class="ilp-dialogue__supplement">' + esc(view.label || 'Nội dung bổ sung J-LAS') + '</p>' +
      '<p class="ilp-dialogue__audio-status" data-audio-status role="status" aria-live="polite"></p>' +
      '<ol class="ilp-dialogue__script">' + before + '</ol>' +
      '<div class="ilp-challenge__turn"><h2>Bạn sẽ nói gì?</h2><ul class="ilp-challenge__options">' + options + '</ul>' + after + '</div>' +
      close + '</section>';
  }

  function renderChallengeResult(result) {
    result = result || {};
    var cleared = !!result.cleared;
    var message = !cleared
      ? 'Chưa vượt ải (' + num(result.correctCount) + '/' + num(result.questionCount) + ' lượt đúng). Làm sai không mất gì — thử lại nhé!'
      : num(result.creditsAwarded) > 0 ? 'Bạn đã chọn đúng mọi lượt. +' + num(result.creditsAwarded) + ' CP đã được cộng cho lần vượt ải đầu tiên.'
        : result.firstClear ? 'Bạn đã chọn đúng mọi lượt.' : 'Bạn đã vượt ải này trước đó nên không cộng thêm CP.';
    return '<section class="ilp-result ilp-challenge"><header class="ilp-study__head"><div><span class="ilp-kicker">THỬ THÁCH VƯỢT ẢI</span>' +
      '<h1>' + (cleared ? 'Vượt ải thành công!' : 'Chưa vượt ải') + '</h1><p>' + esc(message) + '</p></div></header>' +
      '<div class="ilp-result__actions"><button type="button" class="ilp-check" data-challenge-retry>Chơi lại</button>' +
      '<button type="button" class="ilp-check ilp-check--ghost" data-challenge-close>Xem lại hội thoại</button>' +
      '<a class="ilp-check ilp-check--ghost" href="/learning/path/v2">Về lộ trình</a></div></section>';
  }

  /** Owner 2026-10-19 (C2): "Tóm tắt cuối bài" — what was practised, the patterns used and the phrases to keep. */
  function renderSummary(v) {
    if (!v || !v.aids) return '';
    var turns = v.lineByLinePractice ? '<p>Đã luyện ' + v.practicedCount + '/' + v.totalLines + ' lượt thoại.</p>' : '';
    var patterns = v.aids.patterns.length ? '<h3>Mẫu câu đã dùng</h3><ul>' + v.aids.patterns.map(function (p) {
      return '<li><span lang="ja">' + esc(p.pattern) + '</span> — ' + esc(p.meaningVi || '') + '</li>';
    }).join('') + '</ul>' : '';
    var phrases = v.aids.phrases.length ? '<h3>Từ &amp; cụm từ nên nhớ</h3><ul>' + v.aids.phrases.map(function (p) {
      return '<li><span lang="ja">' + esc(p.japanese) + '</span>' + (p.reading ? ' (' + esc(p.reading) + ')' : '') +
        ' — ' + esc(p.meaningVi || '') + '</li>';
    }).join('') + '</ul>' : '';
    return '<section class="ilp-dialogue__summary" aria-label="Tóm tắt cuối bài"><h2>Tóm tắt cuối bài</h2>' + turns +
      patterns + phrases + '<p class="ilp-dialogue__supplement">' + esc(v.aids.label) + '</p></section>';
  }

  /** Formats a remaining-seconds count as a short, friendly Vietnamese duration, e.g. "1 phút 42 giây". */
  function formatRemaining(seconds) {
    seconds = Math.max(0, Math.round(num(seconds)));
    var m = Math.floor(seconds / 60);
    var s = seconds % 60;
    if (m <= 0) return s + ' giây';
    return m + ' phút' + (s ? ' ' + s + ' giây' : '');
  }

  /** {@code opts}: practice (show the practised toggle), practiced, hidden (role-play turn not yet revealed),
   * showTranslation (default true). */
  function renderLine(line, opts) {
    opts = opts || {};
    var side = line.bubbleSide === 'phai' ? ' is-right' : ' is-left';
    var synthetic = line.audioUrl === 'tts:ja-JP';
    var who = line.roleName ? ' của ' + esc(line.roleName) : '';
    var audio = line.audioUrl
      ? '<button type="button" class="ilp-dialogue__audio" data-play-audio="' + esc(line.audioUrl) +
        '"' + (synthetic ? ' data-speak-text="' + esc(line.japanese) + '"' : '') +
        ' aria-label="' + (synthetic ? 'Đọc bằng giọng tổng hợp' : 'Phát âm thanh') + ' lời thoại' + who + '">' +
        '<span aria-hidden="true">🔊</span></button>'
      : '';
    var slow = synthetic && opts.practice
      ? '<button type="button" class="ilp-dialogue__audio is-slow" data-play-audio="tts:ja-JP" data-speak-rate="0.7"' +
        ' data-speak-text="' + esc(line.japanese) + '" aria-label="Nghe chậm lời thoại' + who + '">' +
        '<span aria-hidden="true">🐢</span> Nghe chậm</button>'
      : '';
    var practice = opts.practice
      ? '<button type="button" class="ilp-dialogue__practice' + (opts.practiced ? ' is-done' : '') +
        '" data-practice-line="' + line.lineId + '" aria-pressed="' + (opts.practiced ? 'true' : 'false') + '">' +
        (opts.practiced ? '✓ Đã luyện' : 'Tôi đã luyện câu này') + '</button>'
      : '';
    var japanese = opts.hidden
      ? '<p class="ilp-dialogue__ja is-hidden">Đến lượt bạn — hãy tự nói câu này.</p>' +
        '<button type="button" class="ilp-dialogue__reveal" data-reveal-line="' + line.lineId + '">Xem câu</button>'
      : '<p class="ilp-dialogue__ja" lang="ja">' +
        (opts.showFurigana && line.furigana ? rubyHtml(line.furigana, line.japanese) : esc(line.japanese)) + '</p>';
    var translation = line.meaningVi && opts.showTranslation !== false && !opts.hidden
      ? '<p class="ilp-dialogue__vi">' + esc(line.meaningVi) + '</p>' : '';
    return '<li class="ilp-dialogue__line' + side + '" data-line="' + line.lineId + '">' +
      (line.roleName ? '<span class="ilp-dialogue__role">' + esc(line.roleName) + '</span>' : '') +
      '<div class="ilp-dialogue__bubble">' + japanese + translation + (opts.hidden ? '' : audio + slow) + practice +
      '</div></li>';
  }

  function roles(v) {
    var seen = {};
    return v.lines.filter(function (line) {
      if (!line.roleCode || seen[line.roleCode]) return false;
      seen[line.roleCode] = true;
      return true;
    }).map(function (line) { return { code: line.roleCode, name: line.roleName || line.roleCode }; });
  }

  function renderToolbar(v, prefs) {
    var options = roles(v).map(function (role) {
      return '<option value="' + esc(role.code) + '"' + (prefs.rolePlay === role.code ? ' selected' : '') + '>' +
        'Tôi đóng vai ' + esc(role.name) + '</option>';
    }).join('');
    return '<div class="ilp-dialogue__toolbar">' +
      (hasFurigana(v) ? '<label><input type="checkbox" data-toggle-furigana' + (prefs.showFurigana !== false ? ' checked' : '') +
        '> Hiện furigana</label>' : '') +
      '<label><input type="checkbox" data-toggle-translation' + (prefs.showTranslation !== false ? ' checked' : '') +
      '> Hiện bản dịch</label>' +
      '<label>Luyện đóng vai <select data-roleplay><option value="">Không đóng vai</option>' + options + '</select></label>' +
      '</div>';
  }

  function renderScript(v, prefs) {
    if (!v.lines.length) {
      return '<section class="ilp-state"><h1>Chưa có kịch bản hội thoại</h1><p>Hoạt động này chưa có nội dung.</p></section>';
    }
    prefs = prefs || {};
    var revealed = list(prefs.revealed).map(String);
    var lines = v.lines.map(function (line) {
      return renderLine(line, v.lineByLinePractice ? {
        practice: !v.completed,
        practiced: v.practicedLineIds.indexOf(String(line.lineId)) !== -1,
        hidden: !!prefs.rolePlay && line.roleCode === prefs.rolePlay && revealed.indexOf(String(line.lineId)) === -1,
        showTranslation: prefs.showTranslation,
        showFurigana: prefs.showFurigana !== false
      } : {});
    }).join('');
    var intro = v.lineByLinePractice
      ? 'Nghe từng lượt, tự nói theo rồi bấm “Tôi đã luyện câu này”. Chọn một vai để luyện đóng vai; bạn có thể tắt bản dịch khi đã quen.'
      : 'Đọc và nghe toàn bộ đoạn hội thoại, cuộn tới cuối để hoàn thành.';
    return '<section class="ilp-dialogue">' +
      '<header class="ilp-study__head"><div><span class="ilp-kicker">HỘI THOẠI // ' +
      (v.completed ? 'ĐÃ HOÀN THÀNH' : 'ĐANG LUYỆN TẬP') + '</span><h1>Luyện hội thoại</h1>' +
      '<p>' + esc(intro) + '</p></div></header>' + (v.lineByLinePractice ? renderToolbar(v, prefs) : '') +
      (v.lines.some(function (line) { return line.audioUrl === 'tts:ja-JP'; })
        ? '<p class="ilp-dialogue__audio-note">Nội dung bổ sung J-LAS · Giọng đọc tiếng Nhật tổng hợp</p>' : '') +
      '<p class="ilp-dialogue__audio-status" data-audio-status role="status" aria-live="polite"></p>' +
      (v.lineByLinePractice ? renderPhrases(v) : '') + (v.lineByLinePractice && v.aids ? CHALLENGE_CTA : '') +
      '<ol class="ilp-dialogue__script">' + lines + '</ol>' +
      '<div id="dialogueBottomSentinel" class="ilp-dialogue__sentinel" aria-hidden="true"></div>' +
      '</section>';
  }

  /** The friendly nudge text is derived purely from server-reported numbers — never invents progress. */
  function renderProgress(v) {
    if (v.lineByLinePractice) {
      var left = Math.max(0, v.totalLines - v.practicedCount);
      var cardsLeft = Math.max(0, v.totalPhrases - v.flippedPhrases);
      var hint = v.completed ? 'Bạn đã hoàn thành phần hội thoại này.'
        : left > 0 ? 'Còn ' + left + ' lượt thoại cần luyện.'
        : cardsLeft > 0 ? 'Còn ' + cardsLeft + ' thẻ Từ & cụm từ chưa lật.'
        : v.reachedBottom ? 'Đang xác nhận hoàn thành...' : 'Hãy cuộn xuống hết đoạn hội thoại để hoàn thành.';
      var cards = v.totalPhrases > 0
        ? '<li class="' + (cardsLeft === 0 ? 'is-done' : '') + '">Đã lật ' + v.flippedPhrases + '/' + v.totalPhrases +
          ' thẻ Từ &amp; cụm từ</li>' : '';
      return '<div class="ilp-dialogue__progress" role="status" aria-live="polite">' +
        '<div class="ilp-progress" role="progressbar" aria-valuenow="' + v.practicedCount +
        '" aria-valuemin="0" aria-valuemax="' + v.totalLines + '"><span style="width:' + v.percent + '%"></span></div>' +
        '<p class="ilp-dialogue__note">' + esc(hint) + '</p><ul class="ilp-dialogue__evidence">' +
        cards + '<li class="' + (left === 0 && v.totalLines > 0 ? 'is-done' : '') + '">Đã luyện ' + v.practicedCount + '/' +
        v.totalLines + ' lượt</li>' +
        '<li class="' + (v.reachedBottom ? 'is-done' : '') + '">Đã cuộn đến cuối</li></ul></div>';
    }
    var note = v.completed
      ? 'Bạn đã hoàn thành phần hội thoại này.'
      : v.remainingSeconds > 0
        ? 'Hãy nán lại thêm ' + formatRemaining(v.remainingSeconds) + ' để luyện hội thoại nhé.'
        : v.reachedBottom
          ? 'Đang xác nhận hoàn thành...'
          : 'Hãy cuộn xuống hết đoạn hội thoại để hoàn thành.';
    return '<div class="ilp-dialogue__progress" role="status" aria-live="polite">' +
      '<div class="ilp-progress" role="progressbar" aria-valuenow="' + v.accumulatedSeconds +
      '" aria-valuemin="0" aria-valuemax="' + v.requiredSeconds + '">' +
      '<span style="width:' + v.percent + '%"></span></div>' +
      '<p class="ilp-dialogue__note">' + esc(note) + '</p>' +
      '<ul class="ilp-dialogue__evidence">' +
      '<li class="' + (v.reachedBottom ? 'is-done' : '') + '">Đã cuộn đến cuối</li>' +
      '<li class="' + (v.accumulatedSeconds >= v.requiredSeconds ? 'is-done' : '') + '">Đủ ' +
      v.requiredSeconds + ' giây hoạt động</li></ul></div>';
  }

  /** {@code v} (optional): the study view model, whose aids feed "Tóm tắt cuối bài" for communication dialogues. */
  function renderCompleted(activityId, lineByLine, v) {
    return '<section class="ilp-result"><header class="ilp-study__head"><div><span class="ilp-kicker">HOÀN THÀNH</span>' +
      '<h1>Phần Hội thoại đã đạt</h1><p>' + (lineByLine
        ? 'Bạn đã luyện đủ các lượt thoại và đọc hết đoạn hội thoại.'
        : 'Bạn đã đọc đủ thời gian và cuộn hết đoạn hội thoại.') + '</p></div></header>' +
      (lineByLine ? renderSummary(v) : '') + (lineByLine && v && v.aids ? CHALLENGE_CTA : '') +
      '<div class="ilp-result__actions">' + (num(activityId) > 0
        ? '<a class="ilp-check" href="/learning/v2/dialogue?activity=' + encodeURIComponent(activityId) +
          '&amp;mode=review">Xem lại bài học</a>' : '') +
      '<a class="ilp-check ilp-check--ghost" href="/learning/path/v2">Về lộ trình</a></div></section>';
  }

  function renderReplay(mode, v) {
    var title = mode === 'REVIEW' ? 'Xem lại hội thoại' : 'Luyện lại hội thoại';
    var lines = v.lines.length ? v.lines.map(function (line) { return renderLine(line, { showFurigana: true }); }).join('') :
      '<li class="ilp-state">Hoạt động này chưa có nội dung.</li>';
    var StudyMode = typeof window !== 'undefined' ? window.InteractiveStudyMode : null;
    return (StudyMode ? StudyMode.banner(mode) : '') +
      '<section class="ilp-dialogue"><header class="ilp-study__head"><div><span class="ilp-kicker">HỘI THOẠI // ' +
      (mode === 'REVIEW' ? 'XEM LẠI' : 'LUYỆN TẬP') + '</span><h1>' + title + '</h1>' +
      '<p>Đọc lại kịch bản và nghe các dòng có âm thanh.</p></div></header>' +
      (v.lines.some(function (line) { return line.audioUrl === 'tts:ja-JP'; })
        ? '<p class="ilp-dialogue__audio-note">Nội dung bổ sung J-LAS · Giọng đọc tiếng Nhật tổng hợp</p>' : '') +
      '<p class="ilp-dialogue__audio-status" data-audio-status role="status" aria-live="polite"></p>' +
      '<ol class="ilp-dialogue__script">' + lines + '</ol>' + renderSummary(v) +
      '<div class="ilp-result__actions"><a class="ilp-check" href="/learning/path/v2">Về lộ trình</a></div></section>';
  }

  function error(message) {
    return '<section class="ilp-state"><h1>Chưa thể mở phần Hội thoại</h1><p>' +
      esc(message || 'Vui lòng thử lại sau.') + '</p><button data-retry type="button">Thử lại</button></section>';
  }

  function genToken() {
    return String(Date.now()) + '-' + Math.random().toString(36).slice(2);
  }

  function mount() {
    var node = document.getElementById('interactiveDialogueStudyRoot');
    var id = activityId();
    if (!node || !window.JlasAuth || !window.JlasAuth.requireLogin('/learning/v2/dialogue' + (id ? '?activity=' + encodeURIComponent(id) : ''))) return;
    if (!id) { node.innerHTML = error('Thiếu mã hoạt động Hội thoại.'); return; }
    var base = '/api/learning/v2/activities/' + encodeURIComponent(id) + '/dialogue';
    var StudyMode = window.InteractiveStudyMode;
    if (StudyMode && StudyMode.bindNextAction) StudyMode.bindNextAction(node);
    var mode = StudyMode ? StudyMode.resolveMode(window.location.search) : 'LEARN';
    var heartbeatTimer = null;
    var observer = null;
    var bottomReported = false;
    var bottomVisible = false;
    var finished = false;

    async function api(path, options) {
      var response = await fetch(base + path, Object.assign({ headers: window.JlasAuth.authHeaders() }, options));
      var body = await response.json().catch(function () { return {}; });
      if (!response.ok) throw new Error(body.message || 'Không thể kết nối tới máy chủ.');
      return body.data;
    }

    async function postJson(path, payload) {
      return api(path, {
        method: 'POST',
        headers: Object.assign({ 'Content-Type': 'application/json' }, window.JlasAuth.authHeaders()),
        body: JSON.stringify(payload || {})
      });
    }

    function stopTracking() {
      if (heartbeatTimer) { clearInterval(heartbeatTimer); heartbeatTimer = null; }
      if (observer) { observer.disconnect(); observer = null; }
    }

    // Communication practice view state: the server owns progress; these are display preferences only.
    var current = null;
    // Owner 2026-10-19 (C4): furigana and translation default on for low-level learners; both can be turned off.
    var prefs = { showTranslation: readPref('jlasDialogueTranslation') !== 'off',
      showFurigana: readPref('jlasDialogueFurigana') !== 'off', rolePlay: null, revealed: [] };
    function readPref(key) {
      try { return window.localStorage ? window.localStorage.getItem(key) : null; } catch (e) { return null; }
    }
    function writePref(key, value) {
      try { if (window.localStorage) window.localStorage.setItem(key, value); } catch (e) { /* storage unavailable */ }
    }
    function rerender() {
      if (!current || finished) return;
      if (observer) { observer.disconnect(); observer = null; }
      node.innerHTML = renderScript(current, prefs) + renderProgress(current);
      watchBottom();
    }

    async function practiceLine(lineId) {
      if (mode !== 'LEARN' || finished || !current || !current.lineByLinePractice) return;
      try {
        var result = await postJson('/lines/' + encodeURIComponent(lineId) + '/practiced', {});
        if (current.practicedLineIds.indexOf(String(lineId)) === -1) current.practicedLineIds.push(String(lineId));
        current.practicedCount = num(result.practicedLines);
        if (result.totalPhrases != null) { current.flippedPhrases = num(result.flippedPhrases); current.totalPhrases = num(result.totalPhrases); }
        current.reachedBottom = !!result.reachedBottom;
        current.percent = current.totalLines ? Math.min(100, Math.round(current.practicedCount / current.totalLines * 100)) : 0;
        rerender();
        applyProgress(result);
      } catch (e) {
        var status = node.querySelector('[data-audio-status]');
        if (status) status.textContent = 'Chưa lưu được lượt luyện. Vui lòng thử lại.';
      }
    }

    // Owner 2026-10-19 (G1–G3): the optional reply challenge, played in place of the script. Correctness and the CP
    // reward are decided by the server; the client keeps only the run's chosen option ids.
    var completedView = null;
    var challenge = null;
    function scriptLines() { return (current || completedView || { lines: [] }).lines; }
    function challengeStatus(message) {
      var status = node.querySelector('[data-audio-status]');
      if (status) status.textContent = message;
    }
    function showChallenge() {
      if (observer) { observer.disconnect(); observer = null; }
      node.innerHTML = renderChallenge(challenge.view, scriptLines(), challenge.state, prefs);
    }
    async function startChallenge() {
      try {
        challenge = { view: await api('/challenge'), state: { turn: 0, answers: [] } };
        showChallenge();
      } catch (e) { challengeStatus('Chưa mở được thử thách. Vui lòng thử lại.'); }
    }
    async function chooseChallengeOption(optionId) {
      if (!challenge || challenge.state.feedback) return;
      var turn = challenge.state.turn;
      try {
        var answer = await postJson('/challenge/answer', { turn: turn, option: optionId });
        challenge.state.feedback = { chosen: optionId, correct: !!answer.correct, correctOptionId: answer.correctOptionId,
          explanationVi: answer.explanationVi };
        if (answer.correct) challenge.state.answers[turn] = optionId;
        showChallenge();
      } catch (e) { challengeStatus('Chưa kiểm tra được câu trả lời. Vui lòng thử lại.'); }
    }
    async function nextChallengeTurn() {
      if (!challenge) return;
      var turns = list(challenge.view.turns);
      if (challenge.state.turn < turns.length - 1) {
        challenge.state = { turn: challenge.state.turn + 1, answers: challenge.state.answers };
        return showChallenge();
      }
      try {
        var result = await postJson('/challenge/complete', { answers: challenge.state.answers });
        if (result.cleared) challenge.view.cleared = true;
        node.innerHTML = renderChallengeResult(result);
      } catch (e) { challengeStatus('Chưa ghi nhận được kết quả thử thách. Vui lòng thử lại.'); }
    }
    function retryChallenge() {
      if (!challenge) return;
      challenge.state = { turn: 0, answers: [] };
      showChallenge();
    }
    function closeChallenge() {
      challenge = null;
      if (finished) {
        node.innerHTML = renderCompleted(id, true, current || completedView);
        if (StudyMode && StudyMode.showNextAction) StudyMode.showNextAction(node, id);
      } else rerender();
    }

    async function flipPhrase(index) {
      if (mode !== 'LEARN' || finished || !current || !current.aids) return;
      var phrase = current.aids.phrases.filter(function (p, position) {
        return (p.index != null ? num(p.index) : position) === index;
      })[0];
      if (!phrase || phrase.flipped === true) return;
      try {
        var result = await postJson('/phrases/' + encodeURIComponent(index) + '/flipped', {});
        phrase.flipped = true;
        current.flippedPhrases = num(result.flippedPhrases);
        current.totalPhrases = num(result.totalPhrases);
        current.reachedBottom = !!result.reachedBottom;
        rerender();
        applyProgress(result);
      } catch (e) {
        var status = node.querySelector('[data-audio-status]');
        if (status) status.textContent = 'Chưa lưu được thẻ vừa lật. Vui lòng thử lại.';
      }
    }

    function applyProgress(result) {
      if (finished) return;
      var panel = node.querySelector('.ilp-dialogue__progress');
      var v = viewModel(Object.assign({}, result, { lines: [] }));
      if (current && v.lineByLinePractice) current.reachedBottom = v.reachedBottom;
      if (panel) panel.outerHTML = renderProgress(v);
      if (result.completed) {
        finished = true;
        stopTracking();
        if (current) { current.practicedCount = v.practicedCount; current.totalLines = v.totalLines; }
        node.innerHTML = renderCompleted(id, v.lineByLinePractice, current);
        if (StudyMode && StudyMode.showNextAction) StudyMode.showNextAction(node, id);
        if (StudyMode && StudyMode.celebrate) {
          StudyMode.celebrate(node, { title: 'Chúc mừng!', message: 'Bạn đã hoàn thành phần Hội thoại.', activityId: id });
        }
      }
    }

    async function sendHeartbeat() {
      if (mode !== 'LEARN' || finished || typeof document === 'undefined' || document.visibilityState !== 'visible') return;
      try { applyProgress(await postJson('/heartbeat', { heartbeatToken: genToken() })); }
      catch (e) { return; /* transient network errors do not interrupt the reading session */ }
      // A short script is already in view at load, so its reached-bottom report can race ahead of the first
      // stored heartbeat and be rejected; the observer never fires again while the end stays visible.
      if (bottomVisible) reportBottom();
    }

    async function reportBottom() {
      if (mode !== 'LEARN' || finished || bottomReported) return;
      bottomReported = true;
      try { applyProgress(await postJson('/reached-bottom', {})); }
      catch (e) { bottomReported = false; }
    }

    function watchBottom() {
      var sentinel = document.getElementById('dialogueBottomSentinel');
      if (!sentinel || typeof IntersectionObserver === 'undefined') return;
      observer = new IntersectionObserver(function (entries) {
        entries.forEach(function (entry) { bottomVisible = entry.isIntersecting; if (entry.isIntersecting) reportBottom(); });
      });
      observer.observe(sentinel);
    }

    async function load() {
      node.setAttribute('aria-busy', 'true');
      try {
        if (mode === 'REVIEW' || mode === 'PRACTICE') {
          var replay = await api(mode === 'REVIEW' ? '/review' : '/practice');
          node.innerHTML = renderReplay(mode, viewModel(replay));
          node.setAttribute('aria-busy', 'false');
          return;
        }
        var study = await api('/study');
        var v = viewModel(study);
        if (v.completed) { completedView = v; node.innerHTML = renderCompleted(id, v.lineByLinePractice, v); finished = true;
          if (StudyMode && StudyMode.showNextAction) StudyMode.showNextAction(node, id); }
        else if (v.lineByLinePractice) {
          // Owner 2026-10-19: no time requirement, so no heartbeat — practice and the end are the evidence.
          current = v;
          node.innerHTML = renderScript(v, prefs) + renderProgress(v);
          watchBottom();
        }
        else {
          node.innerHTML = renderScript(v) + renderProgress(v);
          watchBottom();
          heartbeatTimer = setInterval(sendHeartbeat, HEARTBEAT_INTERVAL_MS);
          sendHeartbeat();
        }
      } catch (e) { node.innerHTML = error(e.message); }
      node.setAttribute('aria-busy', 'false');
    }

    node.addEventListener('change', function (event) {
      var target = event.target;
      if (!target || typeof target.matches !== 'function') return;
      if (target.matches('[data-toggle-translation]')) {
        prefs.showTranslation = !!target.checked;
        writePref('jlasDialogueTranslation', prefs.showTranslation ? 'on' : 'off');
        rerender();
      } else if (target.matches('[data-toggle-furigana]')) {
        prefs.showFurigana = !!target.checked;
        writePref('jlasDialogueFurigana', prefs.showFurigana ? 'on' : 'off');
        rerender();
      } else if (target.matches('[data-roleplay]')) {
        prefs.rolePlay = target.value || null;
        prefs.revealed = [];
        rerender();
      }
    });

    node.addEventListener('click', function (event) {
      if (event.target.closest('[data-retry]')) return load();
      if (event.target.closest('[data-challenge-start]')) return startChallenge();
      var challengeOption = event.target.closest('[data-challenge-option]');
      if (challengeOption) return chooseChallengeOption(challengeOption.getAttribute('data-challenge-option'));
      if (event.target.closest('[data-challenge-next]')) return nextChallengeTurn();
      if (event.target.closest('[data-challenge-retry]')) return retryChallenge();
      if (event.target.closest('[data-challenge-close]')) return closeChallenge();
      var practiceButton = event.target.closest('[data-practice-line]');
      if (practiceButton) return practiceLine(practiceButton.getAttribute('data-practice-line'));
      var flipButton = event.target.closest('[data-flip-phrase]');
      if (flipButton) return flipPhrase(num(flipButton.getAttribute('data-flip-phrase')));
      var revealButton = event.target.closest('[data-reveal-line]');
      if (revealButton) {
        prefs.revealed.push(String(revealButton.getAttribute('data-reveal-line')));
        return rerender();
      }
      var jumpButton = event.target.closest('[data-jump-line]');
      if (jumpButton) {
        var target = node.querySelector('[data-line="' + String(jumpButton.getAttribute('data-jump-line')).replace(/[^0-9]/g, '') + '"]');
        if (target) {
          var reduce = typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
          target.scrollIntoView({ block: 'center', behavior: reduce ? 'auto' : 'smooth' });
          target.classList.add('is-highlighted');
          window.setTimeout(function () { target.classList.remove('is-highlighted'); }, 1600);
        }
        return;
      }
      var playButton = event.target.closest('[data-play-audio]');
      if (playButton) {
        var source = playButton.getAttribute('data-play-audio');
        if (source === 'tts:ja-JP') {
          var status = node.querySelector('[data-audio-status]');
          if (typeof window.speechSynthesis === 'undefined' || typeof window.SpeechSynthesisUtterance === 'undefined') {
            if (status) status.textContent = 'Thiết bị chưa hỗ trợ giọng đọc tiếng Nhật; bạn vẫn có thể đọc nội dung hội thoại.';
            return;
          }
          try {
            var synth = window.speechSynthesis;
            var findJapaneseVoice = function () {
              var voices = typeof synth.getVoices === 'function' ? synth.getVoices() : [];
              return voices.find(function (voice) { return /^ja(?:-|$)/i.test(voice.lang || ''); });
            };
            var speakWithVoice = function (voice) {
              var utterance = new window.SpeechSynthesisUtterance(playButton.getAttribute('data-speak-text') || '');
              utterance.lang = 'ja-JP';
              // "Nghe chậm" for beginners (owner 2026-10-19); ordinary playback keeps the voice's own rate.
              var rate = Number(playButton.getAttribute('data-speak-rate'));
              if (rate > 0) utterance.rate = rate;
              utterance.voice = voice;
              synth.cancel();
              synth.speak(utterance);
              if (status) status.textContent = 'Đang đọc bằng giọng tổng hợp tiếng Nhật.';
            };
            var japaneseVoice = findJapaneseVoice();
            if (!japaneseVoice) {
              if (typeof synth.addEventListener === 'function' && typeof window.setTimeout === 'function') {
                if (status) status.textContent = 'Đang tìm giọng tiếng Nhật trên thiết bị…';
                var timeoutId;
                var onVoicesChanged = function () {
                  var loadedVoice = findJapaneseVoice();
                  if (!loadedVoice) return;
                  synth.removeEventListener('voiceschanged', onVoicesChanged);
                  window.clearTimeout(timeoutId);
                  try { speakWithVoice(loadedVoice); }
                  catch (e) { if (status) status.textContent = 'Không thể phát giọng tổng hợp trên thiết bị này.'; }
                };
                synth.addEventListener('voiceschanged', onVoicesChanged);
                timeoutId = window.setTimeout(function () {
                  synth.removeEventListener('voiceschanged', onVoicesChanged);
                  if (status) status.textContent = 'Thiết bị chưa có giọng tiếng Nhật; bạn vẫn có thể đọc nội dung hội thoại.';
                }, 2500);
                return;
              }
              if (status) status.textContent = 'Thiết bị chưa có giọng tiếng Nhật; bạn vẫn có thể đọc nội dung hội thoại.';
              return;
            }
            speakWithVoice(japaneseVoice);
          } catch (e) {
            if (status) status.textContent = 'Không thể phát giọng tổng hợp trên thiết bị này.';
          }
        } else if (typeof Audio !== 'undefined') {
          try { new Audio(source).play().catch(function () {}); } catch (e) { /* best-effort */ }
        }
      }
    });

    document.addEventListener('visibilitychange', function () {
      if (document.visibilityState === 'visible') sendHeartbeat();
    });

    load();
  }

  return {
    viewModel: viewModel, formatRemaining: formatRemaining, renderLine: renderLine, renderScript: renderScript,
    renderProgress: renderProgress, renderCompleted: renderCompleted, renderReplay: renderReplay, error: error, mount: mount,
    rubyHtml: rubyHtml, renderPhrases: renderPhrases, renderSummary: renderSummary,
    renderChallenge: renderChallenge, renderChallengeResult: renderChallengeResult
  };
});
