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
  // Chỉ trang lộ trình (path-lesson.html) mới có cờ này → nút "Học bằng flashcard" chỉ hiện ở lộ trình, không đụng giáo trình.
  var isLearningPath = main ? main.getAttribute('data-learning-path') === 'true' : false;

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
    // OD-45 (owner 2026-10-20): learners may open the DRAFT J-LAS N3 textbook; it is never shown as reviewed.
    if (status === 'DRAFT') {
      return '<span class="tb-badge tb-badge--draft" title="Giáo trình chưa được duyệt — nội dung có thể còn sai sót.">Chưa duyệt</span>';
    }
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
   * Quyết định theo CẢ BÀI, không theo từng mục, để một bài không vừa có ô kana vừa có khung bài đọc:
   * bài bảng chữ cái = skill READING + tag KANA-* (chỉ giáo trình Bảng chữ cái dùng) + mọi mục đều một dòng,
   * tối đa 4 ký tự. Bài READING khác (bài đọc vnjpclub...) đi vào khung bài đọc ở renderReading().
   * Không có audio thu sẵn → mỗi ô là một nút .speak-btn không data-src → đọc bằng TTS ja-JP khi bấm.
   */
  var SKILL_READING = 'READING';
  var KANA_TAG_PREFIX = 'KANA-';
  var KANA_MAX_CHARS = 4;

  function isKanaEntry(item) {
    var text = item.textJa || '';
    return text.length > 0 && text.indexOf('\n') === -1 && Array.from(text).length <= KANA_MAX_CHARS;
  }

  function isKanaLesson() {
    var items = lesson ? (lesson.readingItems || []) : [];
    return !!lesson && lesson.skill === SKILL_READING && items.length > 0
      && String(lesson.knowledgeTagCode || '').indexOf(KANA_TAG_PREFIX) === 0
      && items.every(isKanaEntry);
  }

  function kanaItems() {
    return isKanaLesson() ? lesson.readingItems : [];
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
    panel.innerHTML = (isLearningPath
        ? '<div class="kana-toolbar">' +
            '<button type="button" class="btn btn--primary btn--sm" id="kanaFlashcardBtn">' +
              '<i class="fa-solid fa-layer-group" aria-hidden="true"></i> Học bằng flashcard' +
            '</button>' +
          '</div>'
        : '') +
      groups.map(function (g) {
        return (g.title && groups.length > 1 ? '<h3 class="dialogue-scene__title">' + escapeHtml(g.title) + '</h3>' : '') +
          '<div class="kana-grid">' + g.items.map(kanaTile).join('') + '</div>';
      }).join('') + '<p class="kana-hint">Bấm vào chữ để nghe cách đọc.</p>';
  }

  /* ---------- Bài đọc / bài nghe: content_item READING (trừ bài bảng chữ cái) + LISTENING không phải lời thoại ----------
   * Hiện với MỌI loại bài. Nhiều nguồn (vnjpclub) để bản dịch tiếng Việt xen giữa các dòng tiếng Nhật trong
   * content thay vì cột translated_text, nên mỗi dòng được xếp loại để đặt lang/kiểu chữ — CHỈ là hiển thị,
   * không sửa dữ liệu. Xuống dòng gốc được giữ nguyên.
   */
  var JA_CHAR = /[぀-ヿㇰ-ㇿ㐀-䶿一-鿿豈-﫿ｦ-ﾟ]/;
  var VI_CHAR = /[àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđ]/i;

  /** ja = có kana/Kanji (kể cả lẫn chữ Latin); vi = chữ Latin có dấu tiếng Việt; plain = số, "1.", romaji... */
  function lineKind(line) {
    if (JA_CHAR.test(line)) return 'ja';
    if (VI_CHAR.test(line)) return 'vi';
    return 'plain';
  }

  function readingLines(text) {
    return String(text).split('\n').map(function (line) {
      if (!line.trim()) return '<span class="reading-line reading-line--blank" aria-hidden="true"></span>';
      var kind = lineKind(line);
      var lang = kind === 'ja' ? ' lang="ja"' : (kind === 'vi' ? ' lang="vi"' : '');
      return '<span class="reading-line reading-line--' + kind + '"' + lang + '>' + escapeHtml(line) + '</span>';
    }).join('');
  }

  function readingItems() {
    return isKanaLesson() ? [] : ((lesson && lesson.readingItems) || []);
  }

  function listeningItems() {
    return (lesson && lesson.listeningItems) || [];
  }

  /** File audio thu sẵn của mục (ưu tiên giọng mặc định); null nếu không có. */
  function itemAudio(item) {
    var audio = audioForVoice(item.audios) || (item.audios || []).filter(function (a) { return a && a.audioUrl; })[0];
    return audio && audio.audioUrl ? audio : null;
  }

  /* ---------- Câu hỏi luyện tập (lesson.practiceQuestions, đọc từ quiz của bài) ----------
   * Mỗi câu một thẻ: đề (gạch chân chữ Hán cần đọc khi xác định được), audio riêng của câu nếu có, nghĩa ẩn/hiện,
   * phương án dạng nút. Chọn là báo đúng/sai ngay; KHÔNG lưu điểm — bài kiểm tra chấm điểm đi qua /quiz.
   */
  var KANJI_RUN = /[一-鿿々]+/g;
  var KANA_ONLY = /^[぀-ヿー\s・]+$/;
  var QUESTION_PLACEHOLDER = /^\((Nghe|Đọc) và chọn đáp án đúng\)$/;
  var BLANK_MARK = /[（(]\s*[）)]|＿|_{2,}|★|\[\s*\]/;

  /**
   * Dạng "cách đọc chữ Hán": phương án toàn kana và đề có đúng MỘT cụm chữ Hán → gạch chân cụm đó, kéo dài qua
   * phần okurigana khớp với đuôi đáp án (白い ↔ しろい, 作って ↔ つくって). Không chắc (nhiều cụm, phương án có
   * chữ Hán, thiếu đáp án) thì không gạch, để không gạch sai.
   */
  function underlineTarget(q) {
    var text = q.questionText || '';
    // Backend đã đối chiếu từ điển (chữ Hán có cách đọc = đáp án) → gạch đúng cụm đó.
    if (q.targetText && text.indexOf(q.targetText) !== -1) {
      var at = text.indexOf(q.targetText);
      return escapeHtml(text.slice(0, at)) + '<u class="practice-q__target">' + escapeHtml(q.targetText) + '</u>' +
        escapeHtml(text.slice(at + q.targetText.length));
    }
    var answer = q.correctOption ? (q.options || [])[q.correctOption - 1] : null;
    var runs = text.match(KANJI_RUN) || [];
    var allKana = (q.options || []).length > 0 && q.options.every(function (o) { return KANA_ONLY.test(o || ''); });
    // Đề có chỗ trống （）/＿/★ là câu ngữ pháp (phương án là trợ từ...), không phải câu đọc chữ Hán.
    if (!answer || !allKana || runs.length !== 1 || BLANK_MARK.test(text)) return escapeHtml(text);
    var start = text.indexOf(runs[0]);
    var end = start + runs[0].length;
    var following = text.slice(end);
    var okurigana = 0;
    for (var k = Math.min(answer.length - 1, following.length); k > 0; k--) {
      if (answer.slice(answer.length - k) === following.slice(0, k)) { okurigana = k; break; }
    }
    end += okurigana;
    return escapeHtml(text.slice(0, start)) + '<u class="practice-q__target">' + escapeHtml(text.slice(start, end)) +
      '</u>' + escapeHtml(text.slice(end));
  }

  /*
   * Dạng câu mà đề gốc có tranh — nguồn vnjpclub không kèm ảnh nào:
   *  - 発話表現 (問題3): audio đọc câu tình huống, tranh chỉ minh hoạ → vẫn làm được: hiện câu tình huống + nghĩa,
   *    ẩn chữ của 3 câu đáp (phải nghe), chọn xong mới hiện.
   *  - Chọn tranh: phương án CHÍNH LÀ tranh → không làm được khi thiếu tranh: ẩn thẻ, audio giữ làm bài nghe.
   */
  var HATSUWA_ITEM_TITLE = /発話表現/;
  var HATSUWA_LESSON_TITLE = /biểu hiện trong hội thoại/i;
  var PICTURE_ITEM_TITLE = /絵/;
  var PICTURE_LESSON_TITLE = /chọn tranh/i;

  function isHatsuwa(q, item) {
    return HATSUWA_ITEM_TITLE.test((item && item.title) || '') || HATSUWA_LESSON_TITLE.test((lesson && lesson.title) || '');
  }

  function isPictureChoice(q, item) {
    if (isHatsuwa(q, item)) return false;
    return /^Hình số /.test(q.answerText || '')
      || PICTURE_ITEM_TITLE.test((item && item.title) || '')
      || PICTURE_LESSON_TITLE.test((lesson && lesson.title) || '');
  }

  function practiceCard(q, number, hatsuwa) {
    var text = (q.questionText || '').trim();
    var placeholder = QUESTION_PLACEHOLDER.test(text);
    if (placeholder && q.recoveredText) { text = q.recoveredText; placeholder = false; }
    // Câu nghe có audio: lời thoại (đề) ẩn sau nút để nghe trước — như đề JLPT; chọn xong thì tự mở.
    // 発話表現: câu tình huống thay cho tranh nên HIỆN sẵn (kèm nghĩa); thứ phải nghe là 3 câu đáp.
    var listenFirst = !!q.audioUrl && !!text && !placeholder && !hatsuwa;
    var textHtml = text && !placeholder
      ? '<p class="practice-q__text" lang="ja">' + underlineTarget({
          questionText: text, options: q.options, correctOption: q.correctOption, targetText: q.targetText
        }) + '</p>'
      : '';
    var translationHtml = q.translation
      ? (listenFirst || hatsuwa ? '<p class="practice-q__script-vi" lang="vi">' + escapeHtml(q.translation) + '</p>'
        : '<details class="practice-q__translation"><summary>Xem nghĩa</summary><p lang="vi">' +
          escapeHtml(q.translation) + '</p></details>')
      : '';
    var options = q.options || [];
    var numbersOnly = options.length > 0 && options.every(function (o) { return !String(o || '').trim(); });
    // 発話表現 có audio: chữ của câu đáp ẩn (chỉ còn nút số) để phải nghe; chọn xong mới hiện (CSS .is-answered).
    var hideOptionText = hatsuwa && !!q.audioUrl && !numbersOnly;
    var correct = (q.correctOptions && q.correctOptions.length) ? q.correctOptions : (q.correctOption ? [q.correctOption] : []);
    return '<article class="practice-q' + (hideOptionText ? ' practice-q--listen-options' : '') +
      '" data-qid="' + escapeHtml(q.id) + '" data-correct="' + escapeHtml(correct.join(',')) + '"' +
      (q.recoveredText ? ' data-recovered="1"' : '') + '>' +
      '<div class="practice-q__head"><span class="practice-q__num">Câu ' + number + '</span></div>' +
      (q.audioUrl ? '<audio class="reading-block__audio" controls preload="none" src="' +
        escapeHtml(AUDIO_BASE + q.audioUrl) + '"></audio>' : '') +
      (hideOptionText ? '<p class="practice-q__hint">Đọc tình huống, nghe 3 câu đáp rồi chọn số.</p>' : '') +
      (listenFirst
        ? '<p class="practice-q__hint">Nghe rồi chọn đáp án.</p>' +
          '<details class="practice-q__translation practice-q__script"><summary>Xem lời thoại</summary>' +
          textHtml + translationHtml + '</details>'
        : textHtml + translationHtml) +
      (text && placeholder ? '<p class="practice-q__hint">' + escapeHtml(text) + '</p>' : '') +
      // Phương án không có chữ (nguồn chỉ có hình, ví dụ "Nghe chọn tranh"): một hàng nút số gọn.
      '<div class="practice-q__options' + (numbersOnly ? ' practice-q__options--numbers' : '') +
        '" role="group" aria-label="Phương án câu ' + number + '">' +
      options.map(function (o, i) {
        var kind = lineKind(o || '');
        return '<button type="button" class="practice-q__option" data-option="' + (i + 1) + '"' +
          (kind === 'ja' ? ' lang="ja"' : '') + '>' +
          '<span class="practice-q__option-no">' + (i + 1) + '</span>' +
          '<span class="practice-q__option-text">' + escapeHtml(o) + '</span></button>';
      }).join('') +
      '</div>' +
      // Câu điền (không có phương án): đáp án dạng chữ ẩn sau nút.
      (options.length === 0 && q.answerText
        ? '<details class="practice-q__translation"><summary>Xem đáp án</summary><p' +
          (lineKind(q.answerText) === 'ja' ? ' lang="ja"' : '') + '>' + escapeHtml(q.answerText) +
          (/^Hình số /.test(q.answerText) ? ' <span class="practice-q__hint">(nguồn chưa có hình minh hoạ)</span>' : '') +
          '</p></details>'
        : '') +
      '<p class="practice-q__feedback" aria-live="polite"></p>' +
      (q.explanation ? '<p class="practice-q__explanation" hidden>' + escapeHtml(q.explanation) + '</p>' : '') +
      '</article>';
  }

  function onPracticeClick(event) {
    var btn = event.target.closest('.practice-q__option');
    var retry = event.target.closest('.practice-q__retry');
    var card = (btn || retry) ? (btn || retry).closest('.practice-q') : null;
    if (!card) return;
    var buttons = Array.prototype.slice.call(card.querySelectorAll('.practice-q__option'));
    var feedback = card.querySelector('.practice-q__feedback');
    var explanation = card.querySelector('.practice-q__explanation');
    if (retry) {
      card.classList.remove('is-answered');
      buttons.forEach(function (b) { b.disabled = false; b.classList.remove('is-correct', 'is-wrong', 'is-chosen'); });
      feedback.textContent = '';
      feedback.className = 'practice-q__feedback';
      if (explanation) explanation.hidden = true;
      return;
    }
    if (btn.disabled) return;
    var chosen = Number(btn.getAttribute('data-option'));
    // Một hoặc nhiều đáp án đúng ("1,3"); rỗng = nguồn không có đáp án chắc chắn.
    var correct = (card.getAttribute('data-correct') || '').split(',').filter(Boolean).map(Number);
    var right = correct.indexOf(chosen) !== -1;
    buttons.forEach(function (b) {
      b.disabled = true;
      var n = Number(b.getAttribute('data-option'));
      if (n === chosen) b.classList.add('is-chosen');
      if (correct.indexOf(n) !== -1) b.classList.add('is-correct');
      else if (n === chosen) b.classList.add('is-wrong');
    });
    feedback.className = 'practice-q__feedback ' + (!correct.length ? 'is-unknown' : (right ? 'is-correct' : 'is-wrong'));
    feedback.innerHTML = (!correct.length ? 'Câu này chưa có đáp án chắc chắn.'
        : (right ? '<i class="fa-solid fa-circle-check" aria-hidden="true"></i> Chính xác!'
          : '<i class="fa-solid fa-circle-xmark" aria-hidden="true"></i> Chưa đúng — đáp án là ' + correct.join(', ') + '.')) +
      ' <button type="button" class="practice-q__retry">Làm lại</button>';
    if (explanation) explanation.hidden = false;
    // Câu nghe: chọn xong thì mở lời thoại / hiện chữ câu đáp để đối chiếu.
    var script = card.querySelector('.practice-q__script');
    if (script) script.open = true;
    card.classList.add('is-answered');
  }

  /* ---------- Mô hình tab "Bài đọc" (dùng chung cho bộ đếm tab và cho render) ---------- */
  var VOCAB_CONTAINER = /^Danh sách từ vựng/;
  var KANJI_CONTAINER = /^Danh sách kanji$/;
  var PRACTICE_STUB = /^Bài có \d+ câu hỏi/;
  var JUNK_LINE = /^(【\s*】|POPUP SCRIPT)$/;
  var QUESTION_MARKER = /^(問|もんだい|問題)\s*[0-9０-９]+\s*[:：.．]?$/;

  function isContainerItem(item) {
    var title = item.title || '';
    if (VOCAB_CONTAINER.test(title)) return true;   // danh sách thật ở tab Từ vựng
    return KANJI_CONTAINER.test(title) && ((lesson && lesson.kanjis) || []).length > 0;
  }

  function isPracticeStub(item) {
    return PRACTICE_STUB.test((item.textJa || '').trim());
  }

  /*
   * Dòng mốc mở phần dịch / đáp án / script trong dữ liệu nguồn: "<<< Đáp án & Dịch >>>", "Dịch & Giải thích",
   * "☞ Dịch", "Script & Dịch", "【Dịch】", "Đáp án:"... — dòng ngắn, không có chữ Nhật. Mọi thứ sau mốc là lời giải.
   */
  var SOLUTION_MARKER = /(dịch|đáp\s*án|s(c)?ript|giải\s*thích)/i;

  function isSolutionMarker(line) {
    var t = String(line || '').trim();
    return t.length > 0 && t.length <= 40 && !JA_CHAR.test(t) && SOLUTION_MARKER.test(t) && !/^POPUP SCRIPT$/i.test(t);
  }

  /** Tách đoạn văn thành phần chính và phần lời giải (sau dòng mốc đầu tiên); label = chữ của mốc đã bỏ trang trí. */
  function splitSolution(text) {
    var lines = String(text || '').split('\n');
    for (var i = 0; i < lines.length; i++) {
      if (isSolutionMarker(lines[i])) {
        var label = lines[i].replace(/[☞✏<>【】\[\]():：]/g, ' ').replace(/\s+/g, ' ').trim();
        return {
          main: lines.slice(0, i).join('\n'),
          extra: lines.slice(i + 1).filter(function (l) { return !JUNK_LINE.test(l.trim()); }),
          label: label
        };
      }
    }
    return { main: String(text || ''), extra: [], label: null };
  }

  /**
   * Song ngữ: mỗi dòng Nhật (hoặc trung tính) mở một cặp, các dòng Việt ngay sau nó thuộc cặp đó — để bản dịch nằm
   * dưới đúng câu của nó thay vì xen lẫn từng dòng.
   */
  function bilingualHtml(lines, latinIsTranslation) {
    var pairs = [];
    lines.forEach(function (line) {
      var t = line.trim();
      if (!t) return;
      var kind = lineKind(t);
      var prev = pairs[pairs.length - 1];
      // Phần lời giải: dòng chữ Latin không dấu ngay sau câu Nhật chưa có dịch là bản dịch tên riêng ("山川" → "Yamakawa").
      if (latinIsTranslation && kind === 'plain' && /[A-Za-z]/.test(t) && prev && prev.kind === 'ja' && !prev.vi.length) {
        prev.vi.push(t);
        return;
      }
      if (kind === 'vi' && pairs.length) pairs[pairs.length - 1].vi.push(t);
      else pairs.push({ main: t, kind: kind, vi: [] });
    });
    return pairs.map(function (p) {
      return '<div class="reading-pair">' +
        '<span class="reading-line reading-line--' + p.kind + '"' + (p.kind === 'ja' ? ' lang="ja"' : (p.kind === 'vi' ? ' lang="vi"' : '')) + '>' +
        escapeHtml(p.main) + '</span>' +
        p.vi.map(function (v) { return '<span class="reading-line reading-line--vi" lang="vi">' + escapeHtml(v) + '</span>'; }).join('') +
        '</div>';
    }).join('');
  }

  function hasVietnameseLine(lines) {
    return lines.some(function (l) { return l.trim() && lineKind(l.trim()) === 'vi'; });
  }

  /** Khoá so khớp dòng: NFKC + bỏ mọi khoảng trắng ("Ⓜ： ここ" và "Ⓜ：ここ", "１．" và "1." là một). */
  function matchKey(value) {
    return String(value || '').normalize('NFKC').replace(/\s+/g, '');
  }

  var OPTION_LINE = /^(\S{1,3}[:：])?\s*[1-9１-９][.．、]/;
  var JA_CHAR_GLOBAL = /[぀-ヿㇰ-ㇿ㐀-䶿一-鿿豈-﫿ｦ-ﾟ]/g;

  /**
   * Câu mất đề (chỉ còn "(Nghe/Đọc và chọn đáp án đúng)") nhưng transcript có đủ: tìm dòng chứa phương án 1 mà dòng
   * kế tiếp chứa phương án 2, rồi lấy dòng lời thoại gần nhất phía trên làm đề. Ghép theo NỘI DUNG phương án nên
   * không phụ thuộc thứ tự; không tìm thấy chắc chắn thì giữ nguyên câu. Chỉ là hiển thị, không sửa dữ liệu.
   */
  function recoverPrompts(text, questions) {
    var lines = String(text || '').split('\n');
    var keys = lines.map(matchKey);
    questions.forEach(function (q) {
      if (!QUESTION_PLACEHOLDER.test((q.questionText || '').trim())) return;
      var opts = (q.options || []).map(matchKey).filter(function (o) { return o.length >= 2; });
      if (!opts.length) return;
      for (var i = 0; i < keys.length; i++) {
        if (keys[i].indexOf(opts[0]) === -1) continue;
        if (opts[1] && !(i + 1 < keys.length && keys[i + 1].indexOf(opts[1]) !== -1)) continue;
        for (var j = i - 1; j >= 0; j--) {
          var line = lines[j].trim();
          if (!line) continue;
          if (OPTION_LINE.test(line) || QUESTION_MARKER.test(line) || JUNK_LINE.test(line)) break;
          // Nhãn số thứ tự ("(3)", "①", "例:", "① [ 〇 ]") không phải lời hỏi: cần ít nhất 2 chữ tiếng Nhật.
          if ((line.match(JA_CHAR_GLOBAL) || []).length >= 2) q.recoveredText = line;
          break;
        }
        return;
      }
    });
  }

  /**
   * Bỏ khỏi đoạn văn những dòng đã có trong thẻ câu hỏi (đề, nghĩa, phương án, "問01:") và dòng rác của nguồn
   * ("【】", "POPUP SCRIPT"). Còn lại là đoạn đọc/transcript thật; bài chỉ gồm câu hỏi thì không còn gì.
   */
  function passageLines(text, questions) {
    var known = [];
    questions.forEach(function (q) {
      if (q.questionText) known.push(matchKey(q.questionText));
      if (q.recoveredText) known.push(matchKey(q.recoveredText));
      if (q.translation) known.push(matchKey(q.translation));
      (q.options || []).forEach(function (o) { if (o) known.push(matchKey(o)); });
    });
    known = known.filter(Boolean);
    return String(text || '').split('\n').filter(function (line) {
      var t = line.trim();
      if (!t) return true;
      if (JUNK_LINE.test(t) || QUESTION_MARKER.test(t)) return false;
      if (questions.length === 0) return true;
      if (/^[0-9０-９]+$/.test(t)) return false;              // số phương án "1".."4" của bài nghe chọn tranh
      // So theo matchKey (bỏ khoảng trắng, NFKC). Trùng nguyên dòng thì bỏ ở mọi độ dài (phương án một chữ như
      // "き"); "chứa trong dòng" chỉ với chuỗi đủ dài, để không xoá nhầm câu văn của đoạn đọc.
      var key = matchKey(t);
      // Dòng phương án "１ aとb" / "2. 楽しい": bỏ số thứ tự đầu dòng rồi so với phương án.
      var bare = key.replace(/^[0-9]+[.、)]?/, '');
      return !known.some(function (k) {
        return key === k || bare === k || (k.length >= 4 && key.indexOf(k) !== -1 && key.length <= k.length + 6);
      });
    });
  }

  function readingModel() {
    var empty = { blocks: [], cta: false, label: 'Bài đọc', count: 0 };
    if (!lesson || isKanaLesson()) return empty;
    var reads = readingItems(), listens = listeningItems();
    var allQuestions = (lesson.practiceQuestions || []).slice();
    // Nút "Làm bài kiểm tra" chỉ khi luồng quiz mở được (quiz LESSON đã xuất bản).
    var cta = !!lesson.quizAvailable;
    // Phân loại theo mục cha (bài đọc/bài nghe chứa câu): câu chọn tranh không làm được khi thiếu tranh → không vẽ
    // thẻ; audio của nó KHÔNG đánh dấu "đã nằm trong thẻ" nên vẫn hiện làm bài nghe.
    var byCode = {};
    reads.concat(listens).forEach(function (it) { if (it.stableCode) byCode[it.stableCode] = it; });
    allQuestions.forEach(function (q) {
      var parent = byCode[q.parentCode];
      q.hatsuwa = isHatsuwa(q, parent);
      q.pictureChoice = isPictureChoice(q, parent);
    });
    var questions = allQuestions.filter(function (q) { return !q.pictureChoice; });
    var pictureHidden = allQuestions.length - questions.length;
    var usedAudio = {};
    questions.forEach(function (q) { if (q.audioContentItemId != null) usedAudio[q.audioContentItemId] = true; });

    // Hai danh sách đã đúng thứ tự bên trong; ghép lại thì xếp theo thứ tự phần trong bài (ổn định).
    var items = reads.map(function (it) { return { it: it, listening: false }; })
      .concat(listens.map(function (it) { return { it: it, listening: true }; }))
      .map(function (x, i) { x.i = i; return x; })
      .sort(function (a, b) {
        var sa = a.it.sectionOrderIndex == null ? Infinity : a.it.sectionOrderIndex;
        var sb = b.it.sectionOrderIndex == null ? Infinity : b.it.sectionOrderIndex;
        return sa !== sb ? sa - sb : a.i - b.i;
      });

    var assigned = {};
    var blocks = [];
    items.forEach(function (x) {
      var it = x.it;
      if (isContainerItem(it)) return;
      if (isPracticeStub(it)) return;   // "Bài có N câu hỏi…": câu hỏi đã hiện thành thẻ / nút kiểm tra
      if (it.contentItemId != null && usedAudio[it.contentItemId]) return;  // audio đã nằm trong thẻ câu hỏi
      var own = it.stableCode
        ? questions.filter(function (q) { return q.parentCode === it.stableCode && !assigned[q.id]; })
        : [];
      own.forEach(function (q) { assigned[q.id] = true; });
      recoverPrompts(it.textJa, own);
      // Lọc dòng đã có trong câu hỏi theo MỌI câu của mục (kể cả câu chọn tranh đã ẩn: "問01: 1 2 3 4"...).
      var hiddenOwn = it.stableCode
        ? allQuestions.filter(function (q) { return q.pictureChoice && q.parentCode === it.stableCode; })
        : [];
      // Phần sau dòng mốc "Dịch & Đáp án"/"Script & Dịch" là lời giải: giữ nguyên, thu gọn khi hiển thị.
      var split = splitSolution(it.textJa);
      var lines = passageLines(split.main, own.concat(hiddenOwn));
      var hasPassage = lines.some(function (l) { return l.trim(); });
      var hasExtra = split.extra.some(function (l) { return l.trim(); });
      var audio = itemAudio(it);
      var hasTranslation = !!(it.textVi && it.textVi.trim()) && own.length === 0;
      if (!hasPassage && !hasExtra && !audio && !hasTranslation && own.length === 0) return;
      blocks.push({ item: it, listening: x.listening, lines: hasPassage ? lines : [], audio: audio,
        extra: hasExtra ? split.extra : [], extraLabel: split.label,
        translation: hasTranslation ? it.textVi : null, questions: own });
    });
    var rest = questions.filter(function (q) { return !assigned[q.id]; });
    if (rest.length) {
      blocks.push({ item: { title: 'Câu hỏi luyện tập', sectionTitle: 'Luyện tập', sectionId: 'practice' },
        listening: false, lines: [], audio: null, translation: null, questions: rest });
    }
    var hasRead = blocks.some(function (b) { return !b.listening; });
    var hasListen = blocks.some(function (b) { return b.listening || b.questions.some(function (q) { return !!q.audioUrl; }); });
    var label = hasRead && hasListen ? 'Bài đọc & nghe' : (hasListen ? 'Bài nghe' : 'Bài đọc');
    var count = blocks.reduce(function (n, b) { return n + (b.questions.length || 1); }, 0) + (cta && blocks.length === 0 ? 1 : 0);
    return { blocks: blocks, cta: cta, label: label, count: count, pictureHidden: pictureHidden };
  }

  /** Khối là bài nghe: mục LISTENING, hoặc mục có câu hỏi mang audio riêng. */
  function isListeningBlock(block) {
    return block.listening || block.questions.some(function (q) { return !!q.audioUrl; });
  }

  function readingBlockHtml(block) {
    var it = block.item;
    var number = 0;
    return '<article class="reading-block">' +
      (it.title ? '<h4 class="reading-block__title">' + escapeHtml(it.title) + '</h4>' : '') +
      // preload="none": bài nghe có thể vài chục file, không tải trước khi người học bấm phát.
      (block.audio ? '<audio class="reading-block__audio" controls preload="none" src="' +
        escapeHtml(AUDIO_BASE + block.audio.audioUrl) + '"></audio>' : '') +
      (block.lines.length
        ? (isListeningBlock(block)
          // Bài nghe: transcript thu gọn để nghe trước, không đọc trước được đáp án.
          ? '<details class="reading-block__script"><summary>Xem lời thoại (transcript)</summary>' +
            '<div class="reading-block__text">' + bilingualHtml(block.lines) + '</div></details>'
          : passageHtml(block.lines))
        : '') +
      (block.questions.length
        ? '<div class="practice-list">' + block.questions.map(function (q) { number++; return practiceCard(q, number, q.hatsuwa); }).join('') + '</div>'
        : '') +
      // Lời giải để CUỐI (sau câu hỏi) và thu gọn: chứa bản dịch lẫn đáp án.
      (block.extra && block.extra.length
        ? '<details class="reading-block__script reading-block__solution"><summary>Xem ' +
          escapeHtml(solutionLabel(block.extraLabel)) + '</summary>' +
          '<div class="reading-block__text">' + bilingualHtml(block.extra, true) + '</div></details>'
        : '') +
      (block.translation
        ? '<details class="reading-block__script reading-block__solution"><summary>Xem bản dịch</summary>' +
          '<div class="reading-block__text">' + bilingualHtml(block.translation.split('\n')) + '</div></details>'
        : '') +
      '</article>';
  }

  /** Nhãn nút lời giải theo mốc của nguồn ("Đáp án & Dịch", "Script & Dịch"...), viết thường cho câu "Xem …". */
  function solutionLabel(label) {
    var t = String(label || '').trim();
    if (!t || t.length > 30) return 'dịch & đáp án';
    return t.charAt(0).toLowerCase() + t.slice(1).replace(/^sript/i, 'script');
  }

  /**
   * Đoạn văn chính chỉ hiện tiếng Nhật; dòng dịch xen kẽ (nếu có) ẩn sau nút "Hiện bản dịch" — bấm thì mỗi câu
   * dịch hiện ngay dưới câu Nhật của nó.
   */
  function passageHtml(lines) {
    if (!hasVietnameseLine(lines)) return '<div class="reading-block__text">' + readingLines(lines.join('\n')) + '</div>';
    return '<div class="reading-block__bilingual is-vi-hidden">' +
      '<button type="button" class="reading-block__toggle-vi" aria-pressed="false">' +
      '<i class="fa-solid fa-language" aria-hidden="true"></i> <span>Hiện bản dịch</span></button>' +
      '<div class="reading-block__text">' + bilingualHtml(lines) + '</div></div>';
  }

  function onToggleTranslation(event) {
    var btn = event.target.closest('.reading-block__toggle-vi');
    if (!btn) return;
    var box = btn.closest('.reading-block__bilingual');
    var hidden = box.classList.toggle('is-vi-hidden');
    btn.setAttribute('aria-pressed', hidden ? 'false' : 'true');
    btn.querySelector('span').textContent = hidden ? 'Hiện bản dịch' : 'Ẩn bản dịch';
  }

  function renderReading() {
    var model = readingModel();
    var panel = document.getElementById('panelReading');
    document.getElementById('labelReading').textContent = model.label;
    if (model.blocks.length === 0 && !model.cta) { panel.innerHTML = ''; return; }
    // Gom theo phần nội dung (Bài đọc / Bài nghe / Luyện tập...) theo thứ tự xuất hiện.
    var groups = [], bySection = {};
    model.blocks.forEach(function (b) {
      var key = b.item.sectionId == null ? 'none' : String(b.item.sectionId);
      if (!bySection[key]) { bySection[key] = { title: b.item.sectionTitle || '', blocks: [] }; groups.push(bySection[key]); }
      bySection[key].blocks.push(b);
    });
    panel.innerHTML = (model.pictureHidden
        ? '<p class="practice-q__note reading-picture-note"><i class="fa-regular fa-image" aria-hidden="true"></i> ' +
          model.pictureHidden + ' câu dạng chọn tranh chưa có tranh từ nguồn nên tạm ẩn — phần audio bên dưới dùng để' +
          ' luyện nghe.</p>'
        : '') +
      groups.map(function (g) {
      return '<section class="reading-section">' +
        (g.title ? '<h3 class="reading-section__title">' + escapeHtml(g.title) + '</h3>' : '') +
        g.blocks.map(readingBlockHtml).join('') +
        '</section>';
    }).join('') +
      (model.cta
        ? '<div class="practice-cta"><div><strong>Bài kiểm tra của bài học</strong>' +
          '<p>Làm bài có chấm điểm để ghi nhận tiến độ.</p></div>' +
          '<a class="btn btn--primary btn--sm" href="/learning/lesson/' + encodeURIComponent(lesson.id) + '/quiz">' +
          '<i class="fa-solid fa-pen-to-square" aria-hidden="true"></i> Làm bài kiểm tra</a></div>'
        : '');
  }

  /* ---------- Flashcard bảng chữ (nút "Học bằng flashcard") ----------
   * Overlay lật thẻ: mặt trước = kana, mặt sau = romaji; ←/→ lướt, xáo trộn tùy chọn.
   * Không thay thế lưới chữ (kana-grid) — là chế độ ôn bổ sung, dùng lại đúng kanaItems().
   */
  var kanaFc = { items: [], index: 0, flipped: false };

  function kanaRomaji(item) { return item.textVi || item.title || ''; }

  function renderKanaFlashcard() {
    var item = kanaFc.items[kanaFc.index];
    var card = document.getElementById('kanaFcCard');
    if (!card) return;
    card.querySelector('.kana-fc__front').textContent = item.textJa || '';
    card.querySelector('.kana-fc__back').textContent = kanaRomaji(item);
    document.getElementById('kanaFcCount').textContent = (kanaFc.index + 1) + ' / ' + kanaFc.items.length;
    card.classList.toggle('is-flipped', kanaFc.flipped);
  }

  function openKanaFlashcard() {
    var items = kanaItems();
    if (!items.length) return;
    kanaFc.items = items.slice();
    kanaFc.index = 0;
    kanaFc.flipped = false;
    document.getElementById('kanaFlashcardOverlay').hidden = false;
    renderKanaFlashcard();
  }

  function closeKanaFlashcard() {
    var overlay = document.getElementById('kanaFlashcardOverlay');
    if (overlay) overlay.hidden = true;
  }

  function stepKanaFlashcard(delta) {
    if (!kanaFc.items.length) return;
    kanaFc.index = (kanaFc.index + delta + kanaFc.items.length) % kanaFc.items.length;
    kanaFc.flipped = false;
    renderKanaFlashcard();
  }

  function shuffleKanaFlashcard() {
    if (!kanaFc.items.length) return;
    for (var i = kanaFc.items.length - 1; i > 0; i--) {
      var j = Math.floor(Math.random() * (i + 1));
      var t = kanaFc.items[i]; kanaFc.items[i] = kanaFc.items[j]; kanaFc.items[j] = t;
    }
    kanaFc.index = 0;
    kanaFc.flipped = false;
    renderKanaFlashcard();
  }

  function setupKanaFlashcard() {
    var overlay = document.getElementById('kanaFlashcardOverlay');
    var close = document.getElementById('kanaFcClose');
    var card = document.getElementById('kanaFcCard');
    var prev = document.getElementById('kanaFcPrev');
    var next = document.getElementById('kanaFcNext');
    var shuffle = document.getElementById('kanaFcShuffle');
    if (close) close.addEventListener('click', closeKanaFlashcard);
    if (card) card.addEventListener('click', function () {
      kanaFc.flipped = !kanaFc.flipped;
      card.classList.toggle('is-flipped', kanaFc.flipped);
    });
    if (prev) prev.addEventListener('click', function () { stepKanaFlashcard(-1); });
    if (next) next.addEventListener('click', function () { stepKanaFlashcard(1); });
    if (shuffle) shuffle.addEventListener('click', shuffleKanaFlashcard);
    if (overlay) {
      // Bấm phông nền (ngoài thẻ) để đóng.
      overlay.addEventListener('click', function (event) { if (event.target === overlay) closeKanaFlashcard(); });
      document.addEventListener('keydown', function (event) {
        if (overlay.hidden) return;
        if (event.key === 'Escape') closeKanaFlashcard();
        else if (event.key === 'ArrowLeft') stepKanaFlashcard(-1);
        else if (event.key === 'ArrowRight') stepKanaFlashcard(1);
      });
    }
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
    renderReading();
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
        // Trình phát bài nghe là thẻ <audio> riêng, không đi qua stopCurrent(): dừng khi rời tab.
        Array.prototype.forEach.call(contentEl.querySelectorAll('audio'), function (a) { a.pause(); });
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
      dialogue: (data.dialogueLines || []).length,
      reading: readingModel().count
    };
    Object.keys(counts).forEach(function (key) {
      var id = 'count' + key.charAt(0).toUpperCase() + key.slice(1);
      document.getElementById(id).textContent = counts[key];
    });
    // Vẽ panel TRƯỚC rồi mới ẩn/hiện tab: applyTabVisibility() ghi "Bài này chưa có nội dung." vào panel Kanji
    // khi mọi khối rỗng — vẽ sau thì renderKanji() ghi đè thành "chưa có Kanji", sai nghĩa với bài trống.
    renderPanels();
    applyTabVisibility(counts);
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
  setupKanaFlashcard();
  contentEl.addEventListener('click', onSpeakClick);
  contentEl.addEventListener('click', onPracticeClick);
  contentEl.addEventListener('click', onToggleTranslation);
  // Nút "Học bằng flashcard" được render động trong renderKana() → dùng delegation.
  contentEl.addEventListener('click', function (event) {
    if (event.target.closest('#kanaFlashcardBtn')) openKanaFlashcard();
  });
  load();
})();
