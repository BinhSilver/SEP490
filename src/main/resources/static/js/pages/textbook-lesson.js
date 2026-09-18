/**
 * /textbooks/{textbookId}/lessons/{lessonId} — nội dung đầy đủ một bài học.
 * Dữ liệu: GET /api/dictionary/lessons/{id}/content (Bearer từ JlasAuth → CONTENT_CREATOR mở được bài DRAFT).
 *
 * Audio: src = AUDIO_BASE + content_audio.audio_url (đường dẫn tương đối trong content.audio.path,
 * phục vụ bởi WebMvcConfig tại /uploads/content-audio/**). Mỗi mục một nút phát; seed hiện có một giọng
 * (chuan-nu), backend giữ cấu trúc đa giọng nên seed thêm giọng không phải sửa code.
 */
(function () {
  'use strict';

  var main = document.getElementById('jlabBaseContent');
  var textbookId = main ? main.getAttribute('data-textbook-id') : null;
  var lessonId = main ? main.getAttribute('data-lesson-id') : null;

  var loadingEl = document.getElementById('lessonLoading');
  var errorEl = document.getElementById('lessonError');
  var contentEl = document.getElementById('lessonContent');
  var headerEl = document.getElementById('lessonHeader');

  var SKILL_LABELS = {
    VOCABULARY: 'Từ vựng', GRAMMAR: 'Ngữ pháp', READING: 'Đọc', LISTENING: 'Nghe',
    SPEAKING: 'Nói', ONYOMI: 'Onyomi', KUNYOMI: 'Kunyomi'
  };

  /** Prefix URL phục vụ audio giáo trình (WebMvcConfig: /uploads/content-audio/** → content.audio.path). */
  var AUDIO_BASE = '/uploads/content-audio/';
  /** Giọng ưu tiên khi API trả nhiều giọng cho một mục; seed hiện chỉ có chuan-nu. Backend vẫn đa giọng (owner_type + voice_code). */
  var PREFERRED_VOICE = 'chuan-nu';

  /**
   * Hồ sơ giọng TTS theo content_voice.code của vai hội thoại (dialogue_role.voice_code).
   * File mp3 thu sẵn hiện chỉ có chuan-nu cho mọi vai (giới hạn dữ liệu) → chỉ TTS mới phân biệt được vai:
   *  - gender: ưu tiên chọn giọng ja-JP nam/nữ của máy nếu có nhiều giọng;
   *  - pitch/rate: luôn áp dụng, để máy chỉ có 1 giọng ja-JP (phổ biến) vẫn nghe khác nhau giữa các vai.
   */
  var VOICE_PROFILES = {
    'chuan-nu':    { gender: 'female', pitch: 1.0, rate: 1.0 },
    'chuan-nam':   { gender: 'male',   pitch: 0.8, rate: 0.95 },
    'nam-tre':     { gender: 'male',   pitch: 0.9, rate: 1.1 },
    'nu-tre':      { gender: 'female', pitch: 1.2, rate: 1.1 },
    'ong-cu':      { gender: 'male',   pitch: 0.7, rate: 0.85 },
    'tinh-nghich': { gender: 'female', pitch: 1.3, rate: 1.05 }
  };
  var DEFAULT_PROFILE = VOICE_PROFILES['chuan-nu'];
  /** Web Speech không cho biết giới tính giọng → đoán theo tên giọng ja-JP phổ biến (Windows/Edge, macOS, Google). */
  var MALE_VOICE_NAMES = /keita|圭太|ichiro|一郎|otoya|hattori|daichi|naoki|takumi|shinji/i;
  var FEMALE_VOICE_NAMES = /nanami|七海|ayumi|歩美|haruka|春香|sayaka|紗弥香|kyoko|o-ren|mayu|shiori|aoi|nao|google/i;

  var lesson = null;

  function escapeHtml(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function showError(message) {
    loadingEl.hidden = true;
    errorEl.textContent = message;
    errorEl.classList.add('is-visible');
    if (window.JlasUI && typeof JlasUI.toast === 'function') JlasUI.toast(message, 'error');
  }

  /* ---------- Trạng thái duyệt: badge (mọi người) hoặc nút bật/tắt (chỉ CONTENT_CREATOR — ADMIN thuần xem như learner) ----------
   * Quyền thật chặn ở server (@PreAuthorize); JS chỉ ẩn/hiện nút theo role trong localStorage.
   */
  var isContentCreator = (function () {
    var u = window.JlasAuth && typeof JlasAuth.getUser === 'function' ? JlasAuth.getUser() : null;
    return !!(u && u.role === 'CONTENT_CREATOR');
  })();

  function statusBadge(status) {
    if (status === 'PUBLISHED') return '';
    return '<span class="tb-badge tb-badge--draft">' + escapeHtml(status) + '</span>';
  }

  /** Nút đổi trạng thái cho CONTENT_CREATOR: hiện trạng thái hiện tại, bấm để chuyển PUBLISHED ↔ DRAFT. */
  function statusToggle(kind, id, status) {
    if (!isContentCreator) return statusBadge(status);
    var published = status === 'PUBLISHED';
    return '<button type="button" class="status-toggle' + (published ? ' status-toggle--published' : ' status-toggle--draft') + '"' +
      ' data-kind="' + kind + '" data-id="' + id + '" data-status="' + escapeHtml(status) + '"' +
      ' title="' + (published ? 'Đang công bố — bấm để rút về nháp' : 'Đang nháp — bấm để công bố') + '">' +
      '<i class="fa-solid ' + (published ? 'fa-eye' : 'fa-eye-slash') + '" aria-hidden="true"></i> ' + escapeHtml(status) +
      '</button>';
  }

  function toggleStatus(btn) {
    var kind = btn.getAttribute('data-kind'), id = btn.getAttribute('data-id'), current = btn.getAttribute('data-status');
    var next = current === 'PUBLISHED' ? 'DRAFT' : 'PUBLISHED';
    var label = kind === 'textbook' ? 'giáo trình' : 'bài học';
    if (next === 'DRAFT' && !window.confirm('Rút ' + label + ' này về nháp? Học viên sẽ không xem được nữa.')) return;
    btn.disabled = true;
    var headers = window.JlasAuth && typeof JlasAuth.authHeaders === 'function' ? JlasAuth.authHeaders() : { 'Content-Type': 'application/json' };
    headers['Content-Type'] = 'application/json';
    fetch('/api/dictionary/' + (kind === 'textbook' ? 'textbooks' : 'lessons') + '/' + encodeURIComponent(id) + '/status', {
      method: 'PATCH', headers: headers, body: JSON.stringify({ status: next })
    })
      .then(function (res) {
        return res.json().catch(function () { return null; }).then(function (json) {
          if (!res.ok || !json || json.success === false) throw new Error((json && json.message) || ('Lỗi HTTP ' + res.status));
          return json.data;
        });
      })
      .then(function (data) {
        // Cập nhật tại chỗ, không reload.
        btn.outerHTML = statusToggle(kind, id, data.status);
        if (window.JlasUI && typeof JlasUI.toast === 'function') {
          JlasUI.toast((data.status === 'PUBLISHED' ? 'Đã công bố ' : 'Đã rút về nháp ') + label + '.', 'success');
        }
      })
      .catch(function (err) {
        btn.disabled = false;
        if (window.JlasUI && typeof JlasUI.toast === 'function') JlasUI.toast(err.message || 'Không đổi được trạng thái.', 'error');
      });
  }

  function setupStatusToggles() {
    main.addEventListener('click', function (event) {
      var btn = event.target.closest('.status-toggle');
      if (!btn || btn.disabled) return;
      event.preventDefault();
      event.stopPropagation();
      toggleStatus(btn);
    });
  }

  /* ---------- Chọn audio cho một mục ---------- */

  /** Mỗi mục một audio: ưu tiên PREFERRED_VOICE, không có thì lấy audio đầu tiên (không phụ thuộc số giọng đã seed). */
  function audioForVoice(audios) {
    if (!audios || audios.length === 0) return null;
    for (var i = 0; i < audios.length; i++) {
      if (audios[i].voiceCode === PREFERRED_VOICE) return audios[i];
    }
    return audios[0];
  }

  /* ---------- Nút loa: phát file mp3, thiếu file thì fallback TTS trình duyệt ----------
   * - Có file (200) → phát mp3 giọng thu sẵn.
   * - File lỗi/404 → speechSynthesis với giọng ja-JP; không có giọng ja-JP nào → ẩn nút loa
   *   (không đọc bằng giọng mặc định để người học không nhớ sai phát âm).
   * - Từ vựng đọc bằng reading (kana), câu ví dụ / lời thoại đọc text JA.
   */
  var jaVoice = null;            // SpeechSynthesisVoice ja-JP mặc định (từ vựng, ví dụ, kana), null = máy không có
  var jaVoices = [];             // mọi giọng ja-JP của máy (đã bỏ trùng tên), dùng để chia vai hội thoại
  var jaVoiceResolved = false;   // đã có kết quả getVoices() (Chrome trả rỗng lần đầu)
  var filesAvailable = null;     // null = chưa biết, true/false = kết quả kiểm tra 1 file mẫu
  var currentAudio = null;       // HTMLAudioElement đang phát
  var currentBtn = null;
  var roleVoices = {};           // dialogue_role.id → { voice, pitch, rate } cố định trong một bài

  function pickJaVoice() {
    if (!('speechSynthesis' in window)) { jaVoice = null; jaVoiceResolved = true; return; }
    var voices = window.speechSynthesis.getVoices() || [];
    var ja = voices.filter(function (v) { return (v.lang || '').toLowerCase().indexOf('ja') === 0; });
    if (ja.length === 0) { if (voices.length > 0) { jaVoice = null; jaVoiceResolved = true; } return; }
    // Ưu tiên giọng local (không cần mạng), rồi giọng mặc định.
    ja.sort(function (a, b) {
      if (a.localService !== b.localService) return a.localService ? -1 : 1;
      if (a.default !== b.default) return a.default ? -1 : 1;
      return 0;
    });
    // Edge liệt kê cùng một giọng 2 lần (voiceURI khác) → bỏ trùng theo tên để không "chia vai" vào cùng giọng.
    var seen = {};
    jaVoices = ja.filter(function (v) { if (seen[v.name]) return false; seen[v.name] = true; return true; });
    jaVoice = jaVoices[0];
    jaVoiceResolved = true;
    assignRoleVoices();
    applySpeakButtonVisibility();
  }

  function voiceGender(voice) {
    var name = voice.name || '';
    if (MALE_VOICE_NAMES.test(name)) return 'male';
    if (FEMALE_VOICE_NAMES.test(name)) return 'female';
    return 'unknown';
  }

  /**
   * Gán giọng cho từng vai của bài (ổn định: cùng vai → cùng giọng, cùng pitch/rate trong suốt bài).
   * Với mỗi vai: hồ sơ theo voice_code → ứng viên = giọng đúng giới tính (+ giọng không rõ giới tính),
   * không có thì mọi giọng ja-JP; chọn giọng ít được gán nhất để các vai tách nhau tối đa.
   */
  function assignRoleVoices() {
    roleVoices = {};
    if (!jaVoice || !lesson) return;
    var usage = {};
    (lesson.dialogueRoles || []).forEach(function (role) {
      var profile = VOICE_PROFILES[role.voiceCode] || DEFAULT_PROFILE;
      var pool = jaVoices.filter(function (v) { var g = voiceGender(v); return g === profile.gender || g === 'unknown'; });
      if (pool.length === 0) pool = jaVoices;
      var pick = pool[0];
      pool.forEach(function (v) { if ((usage[v.name] || 0) < (usage[pick.name] || 0)) pick = v; });
      usage[pick.name] = (usage[pick.name] || 0) + 1;
      roleVoices[role.id] = { voice: pick, pitch: profile.pitch, rate: profile.rate, code: role.voiceCode || '' };
    });
  }

  function initTtsVoices() {
    if (!('speechSynthesis' in window)) { jaVoiceResolved = true; return; }
    pickJaVoice();
    window.speechSynthesis.addEventListener('voiceschanged', pickJaVoice);
    // Một số trình duyệt không bắn voiceschanged nếu danh sách rỗng thật: chốt sau 1.5s.
    window.setTimeout(function () { if (!jaVoiceResolved) { jaVoiceResolved = true; jaVoice = null; applySpeakButtonVisibility(); } }, 1500);
  }

  /** Kiểm tra 1 file mẫu (HEAD) để biết máy này có bộ mp3 hay không; quyết định chế độ toàn trang. */
  function probeAudioFiles(data) {
    var sample = null;
    (data.vocabularies || []).some(function (v) { var a = audioForVoice(v.audios); if (a && a.audioUrl) { sample = a.audioUrl; return true; } return false; });
    if (!sample) { filesAvailable = false; applySpeakButtonVisibility(); return; }
    fetch(AUDIO_BASE + sample, { method: 'HEAD' })
      .then(function (res) { filesAvailable = res.ok; })
      .catch(function () { filesAvailable = false; })
      .then(applySpeakButtonVisibility);
  }

  /** Ẩn nút loa khi: không có file VÀ không có giọng ja-JP. Đánh dấu chế độ để CSS/DOM biết. */
  function applySpeakButtonVisibility() {
    if (filesAvailable === null || !jaVoiceResolved) return;
    var mode = filesAvailable ? 'file' : (jaVoice ? 'tts' : 'none');
    contentEl.setAttribute('data-audio-mode', mode);
    contentEl.setAttribute('data-ja-voice', jaVoice ? jaVoice.name : '');
    document.querySelectorAll('.speak-btn').forEach(function (btn) {
      var hasFile = !!btn.getAttribute('data-src');
      var silent = !(hasFile && filesAvailable) && !jaVoice;
      // Ô kana là nội dung học: không ẩn, chỉ tắt chức năng nghe khi máy không có giọng ja-JP.
      if (btn.classList.contains('kana-tile')) { btn.classList.toggle('speak-btn--silent', silent); return; }
      btn.hidden = silent;
    });
  }

  function stopCurrent() {
    if (currentAudio) { try { currentAudio.pause(); } catch (e) {} currentAudio = null; }
    if ('speechSynthesis' in window) window.speechSynthesis.cancel();
    if (currentBtn) { currentBtn.classList.remove('is-playing', 'is-tts', 'is-loading'); currentBtn = null; }
  }

  function speakWithTts(text, btn) {
    if (!jaVoice || !text) { btn.hidden = true; return; }
    // Lời thoại: giọng + pitch/rate theo vai (roleVoices); mục khác: giọng mặc định, đọc chậm vừa.
    var rv = roleVoices[btn.getAttribute('data-role-id')];
    var u = new SpeechSynthesisUtterance(text);
    u.voice = rv ? rv.voice : jaVoice;
    u.lang = u.voice.lang;
    u.rate = rv ? rv.rate : 0.9;
    u.pitch = rv ? rv.pitch : 1.0;
    btn.classList.add('is-playing', 'is-tts');
    btn.title = 'Giọng máy (' + u.voice.name + (rv ? ', vai ' + rv.code + ' pitch ' + rv.pitch + ' rate ' + rv.rate : '') + ')';
    u.onend = u.onerror = function () { if (currentBtn === btn) stopCurrent(); };
    window.speechSynthesis.speak(u);
  }

  function playFile(src, text, btn) {
    var audio = new Audio(src);
    currentAudio = audio;
    btn.classList.add('is-loading');
    audio.onplaying = function () { btn.classList.remove('is-loading'); btn.classList.add('is-playing'); };
    audio.onended = function () { if (currentBtn === btn) stopCurrent(); };
    audio.onerror = function () {
      // File thiếu/lỗi → chuyển sang TTS cho mục này (và các mục sau nếu xác định thiếu cả bộ).
      if (currentAudio !== audio) return;
      currentAudio = null;
      btn.classList.remove('is-loading');
      btn.setAttribute('data-file-missing', 'true');
      if (jaVoice) speakWithTts(text, btn); else { btn.hidden = true; stopCurrent(); }
    };
    audio.play().catch(function () { audio.onerror(); });
  }

  function onSpeakClick(event) {
    var btn = event.target.closest('.speak-btn');
    if (!btn) return;
    if (btn.classList.contains('speak-btn--silent')) {
      if (window.JlasUI && typeof JlasUI.toast === 'function') JlasUI.toast('Máy này không có giọng đọc tiếng Nhật (ja-JP) để phát âm.', 'error');
      return;
    }
    if (currentBtn === btn) { stopCurrent(); return; }
    stopCurrent();
    currentBtn = btn;
    var src = btn.getAttribute('data-src');
    var text = btn.getAttribute('data-text') || '';
    var useFile = src && filesAvailable !== false && btn.getAttribute('data-file-missing') !== 'true';
    if (useFile) playFile(src, text, btn);
    else speakWithTts(text, btn);
  }

  /**
   * Nút loa cho một mục. audios: audio của mục (chọn theo giọng cố định); text: nội dung đọc khi fallback TTS.
   * Không có cả file lẫn giọng ja-JP → nút bị ẩn bởi applySpeakButtonVisibility().
   */
  function speakButton(audios, text, label, roleId) {
    var audio = audioForVoice(audios);
    var src = audio && audio.audioUrl ? AUDIO_BASE + audio.audioUrl : '';
    if (!src && !text) return '';
    return '<button type="button" class="speak-btn" data-src="' + escapeHtml(src) + '" data-text="' + escapeHtml(text || '') + '"' +
      (roleId != null ? ' data-role-id="' + escapeHtml(roleId) + '"' : '') +
      ' aria-label="' + escapeHtml(label || 'Nghe') + '" title="' + escapeHtml(label || 'Nghe') + '">' +
      '<i class="fa-solid fa-volume-high speak-btn__icon" aria-hidden="true"></i>' +
      '<i class="fa-solid fa-circle-notch fa-spin speak-btn__spinner" aria-hidden="true"></i>' +
      '<span class="speak-btn__tts" aria-hidden="true">TTS</span>' +
      '</button>';
  }

  /* ---------- Header ---------- */

  function renderHeader(data) {
    document.getElementById('breadcrumbLesson').textContent = data.title || '';
    if (data.unitTitle) document.getElementById('breadcrumbTextbook').textContent = data.unitTitle;
    document.getElementById('lessonUnit').textContent = data.unitTitle || '';
    document.getElementById('lessonTitle').textContent = data.title || '';
    var meta = [];
    if (data.skill) meta.push('<span class="tb-badge tb-badge--level">' + escapeHtml(SKILL_LABELS[data.skill] || data.skill) + '</span>');
    if (data.knowledgeTagName) meta.push('<span class="tb-badge"><i class="fa-solid fa-tag" aria-hidden="true"></i> ' + escapeHtml(data.knowledgeTagName) + '</span>');
    if (data.estimatedMinutes) meta.push('<span class="tb-badge"><i class="fa-regular fa-clock" aria-hidden="true"></i> ' + data.estimatedMinutes + ' phút</span>');
    meta.push(statusToggle('lesson', data.id, data.status));
    document.getElementById('lessonMeta').innerHTML = meta.join('');
    document.title = (data.title || 'Bài học') + ' - JLAS';
    headerEl.hidden = false;
  }

  /* ---------- Bảng chữ (giáo trình bảng chữ cái): content_item READING, textJa = kana, textVi = romaji ----------
   * Chỉ hiện khi lesson.skill = READING (bài N5/N4 cũng có 1 item READING là đoạn chú thích → không hiện).
   * Không có audio thu sẵn → mỗi ô là một nút .speak-btn không data-src → đọc bằng TTS ja-JP khi bấm.
   */
  var SKILL_READING = 'READING';

  function kanaItems() {
    return lesson && lesson.skill === SKILL_READING ? (lesson.readingItems || []) : [];
  }

  function kanaTile(item) {
    var kana = item.textJa || '';
    var romaji = item.textVi || item.title || '';
    return '<button type="button" class="speak-btn kana-tile" data-src="" data-text="' + escapeHtml(kana) + '"' +
      ' aria-label="Đọc chữ ' + escapeHtml(kana) + ' (' + escapeHtml(romaji) + ')" title="Bấm để nghe">' +
      '<span class="kana-tile__kana" lang="ja">' + escapeHtml(kana) + '</span>' +
      '<span class="kana-tile__romaji">' + escapeHtml(romaji) + '</span>' +
      '<i class="fa-solid fa-circle-notch fa-spin speak-btn__spinner" aria-hidden="true"></i>' +
      '<span class="speak-btn__tts" aria-hidden="true">TTS</span>' +
      '</button>';
  }

  function renderKana() {
    var items = kanaItems();
    var panel = document.getElementById('panelKana');
    if (items.length === 0) { panel.innerHTML = ''; return; }
    // Gom theo section (mỗi bài kana hiện 1 section "Bảng chữ"; giữ tổng quát nếu sau này nhiều section).
    var groups = [], bySection = {};
    items.forEach(function (it) {
      var key = it.sectionId == null ? 'none' : String(it.sectionId);
      if (!bySection[key]) { bySection[key] = { title: it.sectionTitle || '', items: [] }; groups.push(bySection[key]); }
      bySection[key].items.push(it);
    });
    panel.innerHTML = groups.map(function (g) {
      return (g.title && groups.length > 1 ? '<h3 class="dialogue-scene__title">' + escapeHtml(g.title) + '</h3>' : '') +
        '<div class="kana-grid">' + g.items.map(kanaTile).join('') + '</div>';
    }).join('') + '<p class="kana-hint">Bấm vào chữ để nghe cách đọc.</p>';
  }

  /* ---------- Kanji (dữ liệu KANJIDIC2 — EDRDG, CC BY-SA 4.0) ---------- */

  var READING_TYPE_LABELS = { ON: 'Âm On', KUN: 'Âm Kun', NANORI: 'Nanori', OTHER: 'Khác' };

  /** isCommon = gợi ý "học trước" (suy từ thứ tự KANJIDIC2), KHÔNG phải cách đọc thông dụng theo tần suất. */
  function readingGroup(type, readings) {
    var items = readings.filter(function (r) { return r.readingType === type; });
    if (items.length === 0) return '';
    return '<div class="kanji-readings__group">' +
      '<span class="kanji-readings__label">' + escapeHtml(READING_TYPE_LABELS[type] || type) + '</span>' +
      items.map(function (r) {
        return '<span class="kanji-reading' + (r.isCommon ? ' kanji-reading--common' : '') + '" lang="ja"' +
          (r.isCommon ? ' title="Học trước — gợi ý thứ tự học theo KANJIDIC2, không phải thống kê tần suất"' : '') + '>' + escapeHtml(r.readingText) + '</span>';
      }).join('') +
      '</div>';
  }

  function kanjiWord(w) {
    return '<li class="kanji-word">' +
      '<span class="kanji-word__ja" lang="ja">' + escapeHtml(w.expression) + '</span>' +
      (w.reading ? '<span class="kanji-word__reading" lang="ja">' + escapeHtml(w.reading) + '</span>' : '') +
      (w.meaning ? '<span class="kanji-word__meaning">' + escapeHtml(w.meaning) + '</span>' : '') +
      '</li>';
  }

  function kanjiItem(k) {
    var readings = k.readings || [];
    var words = k.words || [];
    var facts = [];
    if (k.hanViet) facts.push('<span class="kanji-fact"><span class="kanji-fact__label">Hán Việt</span><strong>' + escapeHtml(k.hanViet) + '</strong></span>');
    if (k.strokeCount != null) facts.push('<span class="kanji-fact"><span class="kanji-fact__label">Số nét</span><strong>' + k.strokeCount + '</strong></span>');
    if (k.radicalCharacter) {
      facts.push('<span class="kanji-fact"><span class="kanji-fact__label">Bộ thủ</span><strong lang="ja">' + escapeHtml(k.radicalCharacter) + '</strong>' +
        (k.radicalNumber != null ? ' <span class="kanji-fact__muted">#' + k.radicalNumber + '</span>' : '') +
        (k.radicalNameVi ? ' <span class="kanji-fact__muted">' + escapeHtml(k.radicalNameVi) + '</span>' : '') + '</span>');
    }
    // meaning (meaning_vi) hiện chưa có dữ liệu — không render ô rỗng.
    var typeOrder = ['ON', 'KUN', 'NANORI', 'OTHER'];
    return '' +
      '<article class="kanji-item">' +
        '<div class="kanji-item__glyph" lang="ja">' + escapeHtml(k.character) + '</div>' +
        '<div class="kanji-item__body">' +
          '<div class="kanji-item__facts">' + facts.join('') + '</div>' +
          (readings.length
            ? '<div class="kanji-readings">' + typeOrder.map(function (t) { return readingGroup(t, readings); }).join('') + '</div>'
            : '') +
          (words.length
            ? '<div class="kanji-words"><span class="kanji-readings__label">Từ trong bài</span><ul class="kanji-words__list">' + words.map(kanjiWord).join('') + '</ul></div>'
            : '') +
        '</div>' +
      '</article>';
  }

  function renderKanji() {
    var list = lesson.kanjis || [];
    var host = document.getElementById('kanjiList');
    host.innerHTML = list.length
      ? '<div class="kanji-list">' + list.map(kanjiItem).join('') + '</div>'
      : '<p class="lesson-panel__empty">Bài này chưa có Kanji.</p>';
  }

  /* ---------- Từ vựng ---------- */

  function vocabItem(v) {
    var tags = [];
    if (v.wordType) tags.push('<span class="tb-badge">' + escapeHtml(v.wordType) + '</span>');
    var example = v.exampleJa
      ? '<p class="vocab-item__example"><span class="ja">' + escapeHtml(v.exampleJa) + '</span>' + escapeHtml(v.exampleVi || '') + '</p>'
      : '';
    return '' +
      '<article class="vocab-item">' +
        '<div class="vocab-item__word">' +
          '<span class="vocab-item__expression" lang="ja">' + escapeHtml(v.expression) + '</span>' +
          '<span class="vocab-item__reading" lang="ja">' + escapeHtml(v.reading || '') + '</span>' +
          (v.readingRomaji ? '<span class="vocab-item__romaji">' + escapeHtml(v.readingRomaji) + '</span>' : '') +
          '<p class="vocab-item__meaning">' + escapeHtml(v.meaning || '') + '</p>' +
          (tags.length ? '<div class="vocab-item__tags">' + tags.join('') + '</div>' : '') +
          example +
        '</div>' +
        '<div class="vocab-item__audio">' + speakButton(v.audios, v.reading || v.expression, 'Nghe ' + (v.expression || '')) + '</div>' +
      '</article>';
  }

  function renderVocab() {
    var list = lesson.vocabularies || [];
    var panel = document.getElementById('panelVocab');
    panel.innerHTML = list.length
      ? '<div class="vocab-list">' + list.map(vocabItem).join('') + '</div>'
      : '<p class="lesson-panel__empty">Bài này chưa có từ vựng.</p>';
  }

  /* ---------- Ngữ pháp + câu ví dụ (một tab, như giáo trình in) ----------
   * Mỗi mẫu ngữ pháp là một khối, dưới là TẤT CẢ câu ví dụ gắn với mẫu (content_item.grammar_point_id).
   * Câu không gắn mẫu nào (grammarPointId null) gom vào khối cuối "Câu ví dụ khác".
   * grammar_point.example (= câu #1 + bản dịch) chỉ hiện khi mẫu không có câu gắn, để không lặp.
   */
  var OTHER_EXAMPLES_TITLE = 'Câu ví dụ khác';

  function exampleItem(s, index) {
    return '' +
      '<article class="example-item">' +
        '<span class="example-item__order">' + (index + 1) + '</span>' +
        '<div>' +
          '<p class="example-item__ja" lang="ja">' + escapeHtml(s.textJa || '') + '</p>' +
          (s.textVi ? '<p class="example-item__vi">' + escapeHtml(s.textVi) + '</p>' : '') +
        '</div>' +
        speakButton(s.audios, s.textJa, 'Nghe câu ví dụ ' + (index + 1)) +
      '</article>';
  }

  /** Khối hiển thị: mẫu ngữ pháp (+ câu gắn với nó) theo thứ tự API, rồi khối câu không gắn mẫu (nếu có). */
  function grammarBlocks() {
    var points = lesson.grammarPoints || [];
    var byPoint = {};
    var others = [];
    (lesson.exampleSentences || []).forEach(function (s) {
      var key = s.grammarPointId == null ? '' : String(s.grammarPointId);
      if (!key) { others.push(s); return; }
      if (!byPoint[key]) byPoint[key] = [];
      byPoint[key].push(s);
    });
    var blocks = points.map(function (g) { return { point: g, examples: byPoint[String(g.id)] || [] }; });
    // Câu gắn id không có trong grammarPoints của bài (dữ liệu lệch) cũng không được mất: đưa vào khối "khác".
    var known = {};
    points.forEach(function (g) { known[String(g.id)] = true; });
    Object.keys(byPoint).forEach(function (key) { if (!known[key]) others = others.concat(byPoint[key]); });
    if (others.length) blocks.push({ point: null, examples: others });
    return blocks;
  }

  function grammarBlock(block) {
    var g = block.point;
    var examples = block.examples;
    var listHtml = examples.length
      ? '<div class="example-list grammar-item__examples">' + examples.map(exampleItem).join('') + '</div>'
      : '';
    if (!g) {
      return '<article class="grammar-item grammar-item--other">' +
        '<h3 class="grammar-item__pattern">' + escapeHtml(OTHER_EXAMPLES_TITLE) + '</h3>' +
        '<p class="grammar-item__meaning">Câu ví dụ của bài không thuộc riêng mẫu ngữ pháp nào.</p>' +
        listHtml +
      '</article>';
    }
    return '' +
      '<article class="grammar-item">' +
        '<h3 class="grammar-item__pattern" lang="ja">' + escapeHtml(g.pattern) + '</h3>' +
        '<p class="grammar-item__meaning">' + escapeHtml(g.meaning || '') + '</p>' +
        (g.note ? '<p class="grammar-item__note"><span class="grammar-item__label">Ghi chú: </span>' + escapeHtml(g.note) + '</p>' : '') +
        (examples.length === 0 && g.example
          ? '<p class="grammar-item__example"><span class="grammar-item__label">Ví dụ: </span>' + escapeHtml(g.example) + '</p>'
          : listHtml) +
      '</article>';
  }

  function renderGrammar() {
    var blocks = grammarBlocks();
    var panel = document.getElementById('panelGrammar');
    panel.innerHTML = blocks.length
      ? '<div class="grammar-list">' + blocks.map(grammarBlock).join('') + '</div>'
      : '<p class="lesson-panel__empty">Bài này chưa có mẫu ngữ pháp.</p>';
  }

  /* ---------- Hội thoại ---------- */

  function chunkSpan(c) {
    var isPunct = c.meaningVi == null || c.meaningVi === '';
    return '<span class="chunk' + (isPunct ? ' chunk--punct' : '') + '"' +
      (isPunct ? '' : ' title="' + escapeHtml(c.meaningVi) + '"') + ' lang="ja">' +
        '<span>' + escapeHtml(c.surface) + '</span>' +
        (isPunct ? '' : '<span class="chunk__meaning">' + escapeHtml(c.meaningVi) + '</span>') +
      '</span>';
  }

  function dialogueLine(line) {
    var right = line.bubbleSide === 'phai';
    var ja = line.chunks && line.chunks.length
      ? line.chunks.map(chunkSpan).join('')
      : '<span lang="ja">' + escapeHtml(line.text || '') + '</span>';
    return '' +
      '<div class="dialogue-line' + (right ? ' dialogue-line--right' : '') + '">' +
        '<span class="dialogue-line__role">' + escapeHtml(line.roleNameVi || line.roleCode || '') + '</span>' +
        '<div class="dialogue-bubble">' +
          '<div class="dialogue-bubble__ja">' + ja + '</div>' +
          (line.translatedText ? '<p class="dialogue-bubble__vi">' + escapeHtml(line.translatedText) + '</p>' : '') +
        '</div>' +
        speakButton(line.audios, line.text, 'Nghe lời thoại', line.roleId) +
      '</div>';
  }

  function renderDialogue() {
    var lines = lesson.dialogueLines || [];
    var roles = lesson.dialogueRoles || [];
    var panel = document.getElementById('panelDialogue');
    if (lines.length === 0) {
      panel.innerHTML = '<p class="lesson-panel__empty">Bài này chưa có hội thoại.</p>';
      return;
    }
    // Gom theo section (mỗi đoạn hội thoại là một lesson_section) để giữ đúng thứ tự dữ liệu.
    var scenes = [];
    var bySection = {};
    lines.forEach(function (line) {
      var key = line.sectionId == null ? 'none' : String(line.sectionId);
      if (!bySection[key]) {
        bySection[key] = { title: line.sectionTitle || '', lines: [] };
        scenes.push(bySection[key]);
      }
      bySection[key].lines.push(line);
    });
    var rolesHtml = roles.length
      ? '<div class="dialogue-roles">' + roles.map(function (r) {
          return '<span class="tb-badge tb-badge--level"><i class="fa-solid fa-user" aria-hidden="true"></i> ' +
            escapeHtml(r.nameVi) + (r.nameJa ? ' (' + escapeHtml(r.nameJa) + ')' : '') + '</span>';
        }).join('') + '</div>'
      : '';
    var toolbar = '<div class="dialogue-toolbar">' +
      '<button type="button" class="btn btn--secondary btn--sm" id="toggleChunkMeaning">Hiện nghĩa từng cụm</button></div>';
    panel.innerHTML = rolesHtml + toolbar + scenes.map(function (scene, i) {
      return '<section class="dialogue-scene">' +
        '<h3 class="dialogue-scene__title">' + escapeHtml(scene.title || ('Hội thoại ' + (i + 1))) + '</h3>' +
        scene.lines.map(dialogueLine).join('') +
      '</section>';
    }).join('');
    var toggle = document.getElementById('toggleChunkMeaning');
    if (toggle) {
      toggle.addEventListener('click', function () {
        var on = contentEl.classList.toggle('show-chunk-meaning');
        toggle.textContent = on ? 'Ẩn nghĩa từng cụm' : 'Hiện nghĩa từng cụm';
      });
    }
  }

  /* ---------- Tabs / tổng hợp ---------- */

  function renderPanels() {
    renderKana();
    renderKanji();
    renderVocab();
    renderGrammar();
    renderDialogue();
  }

  /** Ẩn tab có 0 mục và chọn tab đầu tiên còn hiện; nếu mọi khối đều rỗng thì hiện thông báo. */
  function applyTabVisibility(counts) {
    var tabs = Array.prototype.slice.call(document.querySelectorAll('.lesson-tab'));
    var panels = Array.prototype.slice.call(document.querySelectorAll('.lesson-panel'));
    var first = null;
    tabs.forEach(function (tab) {
      var key = tab.getAttribute('data-tab');
      var visible = (counts[key] || 0) > 0;
      tab.hidden = !visible;
      if (visible && !first) first = key;
    });
    tabs.forEach(function (tab) {
      var active = tab.getAttribute('data-tab') === first;
      tab.classList.toggle('is-active', active);
      tab.setAttribute('aria-selected', active ? 'true' : 'false');
    });
    panels.forEach(function (p) { p.hidden = p.getAttribute('data-panel') !== first; });
    if (!first) {
      var kanjiPanel = document.getElementById('panelKanji');
      kanjiPanel.hidden = false;
      document.getElementById('kanjiList').innerHTML = '<p class="lesson-panel__empty">Bài này chưa có nội dung.</p>';
    }
  }

  function setupTabs() {
    var tabs = Array.prototype.slice.call(document.querySelectorAll('.lesson-tab'));
    var panels = Array.prototype.slice.call(document.querySelectorAll('.lesson-panel'));
    tabs.forEach(function (tab) {
      tab.addEventListener('click', function () {
        var target = tab.getAttribute('data-tab');
        stopCurrent();
        tabs.forEach(function (t) {
          var active = t === tab;
          t.classList.toggle('is-active', active);
          t.setAttribute('aria-selected', active ? 'true' : 'false');
        });
        panels.forEach(function (p) { p.hidden = p.getAttribute('data-panel') !== target; });
      });
    });
  }

  function render(data) {
    lesson = data;
    loadingEl.hidden = true;
    renderHeader(data);

    // Số mục từng khối; khối 0 mục → ẩn tab (bài kana không có Kanji/Từ vựng/..., bài N5 không có Bảng chữ).
    var counts = {
      kana: kanaItems().length,
      kanji: (data.kanjis || []).length,
      vocab: (data.vocabularies || []).length,
      grammar: grammarBlocks().length,   // mẫu ngữ pháp + khối "Câu ví dụ khác" nếu có
      dialogue: (data.dialogueLines || []).length
    };
    Object.keys(counts).forEach(function (key) {
      var id = 'count' + key.charAt(0).toUpperCase() + key.slice(1);
      document.getElementById(id).textContent = counts[key];
    });
    applyTabVisibility(counts);

    renderPanels();
    contentEl.hidden = false;
    assignRoleVoices();   // giọng có thể đã sẵn sàng trước khi dữ liệu về
    probeAudioFiles(data);
    applySpeakButtonVisibility();
  }

  function load() {
    if (!lessonId) { showError('Thiếu mã bài học.'); return; }
    var headers = window.JlasAuth && typeof JlasAuth.authHeaders === 'function' ? JlasAuth.authHeaders() : {};
    fetch('/api/dictionary/lessons/' + encodeURIComponent(lessonId) + '/content', { headers: headers })
      .then(function (res) {
        return res.json().catch(function () { return null; }).then(function (json) {
          if (res.status === 404) throw new Error('Không tìm thấy bài học hoặc bài chưa được công bố.');
          if (!res.ok || !json || json.success === false) {
            throw new Error((json && json.message) || ('Không tải được bài học (HTTP ' + res.status + ')'));
          }
          return json.data;
        });
      })
      .then(render)
      .catch(function (err) { showError(err.message || 'Không tải được bài học.'); });
  }

  setupTabs();
  initTtsVoices();
  setupStatusToggles();
  contentEl.addEventListener('click', onSpeakClick);
  load();
})();
