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
  // Giao diện mới (hero, tab trượt, lưới thẻ, phân trang...) chỉ cho textbooks/lesson.html (main.lesson-page);
  // path-lesson.html dùng chung file này nhưng không có class nên giữ nguyên giao diện cũ.
  var NEW_UI = !!(main && main.classList.contains('lesson-page'));

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

  /** Media của câu hỏi (assessment_question.image_url): tương đối → ghép AUDIO_BASE; URL tuyệt đối hoặc '/...' giữ nguyên. */
  function mediaUrl(path) {
    var p = String(path || '').trim();
    if (!p) return '';
    if (/^(https?:|data:|blob:|\/)/i.test(p)) return p;
    return AUDIO_BASE + p.replace(/^\.\//, '');
  }

  /**
   * passage_html → chữ thuần giữ xuống dòng (</p> = dòng trống, <br> = xuống dòng); bỏ mọi thẻ khác rồi mới escape
   * lúc hiển thị, nên đoạn văn từ nguồn ngoài không chèn được HTML vào trang.
   */
  function passageText(html) {
    return String(html || '')
      .replace(/<br\s*\/?>/gi, '\n')
      .replace(/<\/p\s*>\s*/gi, '\n\n')
      .replace(/<[^>]*>/g, '')
      .replace(/&nbsp;/g, ' ').replace(/&lt;/g, '<').replace(/&gt;/g, '>')
      .replace(/&quot;/g, '"').replace(/&#0*39;/g, "'").replace(/&amp;/g, '&')
      .replace(/\n{3,}/g, '\n\n')
      .trim();
  }
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

  /*
   * [JLAS2-17] Ảnh đoạn đọc (giao diện mới): reading_image_override theo mã ổn định của bài đọc. available = false khi DB
   * chưa chạy migration 022 → không hiện nút, trang dùng ảnh seed như cũ. Nút chỉ là giao diện; backend tự chặn 401/403.
   */
  var readingImages = { available: false, byCode: {} };
  /** [JLAS2-17] Ảnh trang sách gốc theo tab (lesson_page_image, migration 037); rỗng khi DB chưa có bảng. */
  var pageImages = { VOCAB: [], GRAMMAR: [], KANJI: [] };
  var PAGE_IMAGE_LABELS = { VOCAB: 'từ vựng', GRAMMAR: 'ngữ pháp', KANJI: 'kanji' };
  var readingImageToolsOpen = {};   // [F] mã bài đọc đã mở khung thêm ảnh (giữ qua các lần vẽ lại tab)
  var canEditReadingImages = (function () {
    var u = window.JlasAuth && typeof JlasAuth.getUser === 'function' ? JlasAuth.getUser() : null;
    return !!(u && u.role === 'CONTENT_CREATOR'); // cùng quyền với API ảnh (hasRole CONTENT_CREATOR)
  })();
  var READING_IMAGE_MAX = 5 * 1024 * 1024;
  var READING_IMAGE_TYPES = ['image/png', 'image/jpeg', 'image/webp'];

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
      // Ô kana / bong bóng hội thoại / nút có nhãn là nội dung học: không ẩn, chỉ tắt chức năng nghe khi máy không có giọng ja-JP.
      if (btn.classList.contains('kana-tile') || btn.classList.contains('lp-keep')) {
        btn.classList.toggle('speak-btn--silent', silent);
        return;
      }
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
    try {
      var u = new SpeechSynthesisUtterance(text);
      u.voice = rv ? rv.voice : jaVoice;
      u.lang = u.voice.lang || 'ja-JP';
      u.rate = rv ? rv.rate : (NEW_UI ? 0.85 : 0.9);
      u.pitch = rv ? rv.pitch : 1.0;
      btn.classList.add('is-playing', 'is-tts');
      btn.title = 'Giọng máy (' + u.voice.name + (rv ? ', vai ' + rv.code + ' pitch ' + rv.pitch + ' rate ' + rv.rate : '') + ')';
      u.onend = u.onerror = function () { if (currentBtn === btn) stopCurrent(); };
      window.speechSynthesis.speak(u);
    } catch (e) {
      stopCurrent();
    }
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
      (NEW_UI ? WAVE_HTML : '') +
      '</button>';
  }

  /** 4 vạch sóng + vòng lan hiện khi nút loa đang phát (chỉ giao diện mới, CSS lesson-page.css). */
  var WAVE_HTML = '<span class="lp-wave" aria-hidden="true"><i></i><i></i><i></i><i></i></span>' +
    '<span class="lp-ring" aria-hidden="true"></span>';

  /* ---------- Header ---------- */

  function renderHeader(data) {
    if (NEW_UI) { renderHeroNew(data); return; }
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

  /*
   * Giao diện mới: bảng gojūon chuẩn — mỗi hàng một phụ âm, cột theo nguyên âm cuối của romaji (a i u e o), ô trống
   * đúng chỗ (や・ゆ・よ, わ・を, ん đứng riêng một hàng). Âm ghép (きゃ…) dùng 3 cột ya/yu/yo. Hàng mới khi cột không tăng
   * nữa, nên dữ liệu chỉ cần đúng thứ tự gojūon như seed hiện có; không suy ra được cột thì dùng lưới cũ.
   */
  var KANA_VOWEL_COLS = { a: 0, i: 1, u: 2, e: 3, o: 4 };
  var KANA_YOUON_COLS = { a: 0, u: 1, o: 2 };
  var KANA_SMALL_Y = /[ゃゅょャュョ]$/;

  function gojuonRows(items) {
    var youon = items.every(function (it) { return Array.from(it.textJa || '').length === 2 && KANA_SMALL_Y.test(it.textJa); });
    var cols = youon ? KANA_YOUON_COLS : KANA_VOWEL_COLS;
    var width = youon ? 3 : 5;
    var rows = [];
    var row = null;
    var last = -1;
    for (var i = 0; i < items.length; i++) {
      var romaji = String(items[i].textVi || items[i].title || '').trim().toLowerCase();
      var n = romaji === 'n' || romaji === 'nn' || /^[んン]$/.test(items[i].textJa || '');
      var col = n ? 0 : cols[romaji.slice(-1)];
      if (col == null) return null;
      if (!row || n || col <= last) { row = new Array(width).fill(null); rows.push(row); }
      row[col] = items[i];
      last = n ? width : col;   // ん đứng riêng một hàng
    }
    return { width: width, rows: rows };
  }

  function gojuonHtml(items) {
    var grid = gojuonRows(items);
    if (!grid) return null;
    return '<div class="lp-gojuon lp-gojuon--' + grid.width + '" role="grid" aria-label="Bảng chữ">' +
      grid.rows.map(function (row) {
        return '<div class="lp-gojuon__row" role="row">' + row.map(function (it) {
          return it ? '<div class="lp-gojuon__cell" role="gridcell">' + kanaTile(it) + '</div>'
            : '<div class="lp-gojuon__cell lp-gojuon__cell--empty" role="gridcell" aria-hidden="true"></div>';
        }).join('') + '</div>';
      }).join('') + '</div>';
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
          ((NEW_UI && gojuonHtml(g.items)) || '<div class="kana-grid">' + g.items.map(kanaTile).join('') + '</div>');
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

  /*
   * Dòng "từ tiếng Nhật (+ cách đọc trong ngoặc) + chú thích tiếng Việt" ("謝る（あやまる）xin lỗi"): tách để tô
   * hai phần, CHỈ ở tầng hiển thị của giao diện mới. Chỉ tách khi chắc chắn: dòng bắt đầu bằng kana/kanji (dòng mở đầu
   * bằng số, romaji, ◇, ＊, 《… thì không), phần sau là chữ Latin CÓ dấu tiếng Việt và không còn chữ Nhật nào.
   */
  var MIX_JA_START = /^(?:[\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff\uff66-\uff9f々〆]|[「『（【(][\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff々〆])/;
  var MIX_LATIN = /[A-Za-zÀ-ỹĐđ]/;
  var MIX_SEP_END = /\s*([:：\-–—=])\s*$/;

  function splitMixedLine(line) {
    var t = String(line || '').trim();
    if (!MIX_JA_START.test(t)) return null;
    var m = t.match(MIX_LATIN);
    if (!m) return null;
    var jp = t.slice(0, m.index);
    var vi = t.slice(m.index).trim();
    if (!vi || JA_CHAR.test(vi) || !VI_CHAR.test(vi)) return null;
    // [JLAS2-17] G6: "質問（ : các bạn chọn câu đúng)" — ngoặc mở chưa đóng thì không phải cặp "từ：nghĩa".
    // Chỉ ngoặc toàn khổ: dòng "ふしぎ (: a-na) kì lạ", "昼食はご持参ください。(: Xin vui lòng …)" vẫn là cặp như cũ.
    if (/（[\s:：]*$/.test(jp) && /[)）]\s*$/.test(vi)) return null;
    var sep = '：';
    var sm = jp.match(MIX_SEP_END);
    if (sm) { sep = sm[1] === ':' ? '：' : sm[1]; jp = jp.slice(0, sm.index); }
    jp = jp.trim();
    if (!jp || jp.length > 40) return null;
    return { jp: jp, sep: sep, vi: vi };
  }

  function mixedLineHtml(parts) {
    return '<span class="reading-line reading-line--pair">' +
      '<span class="jp" lang="ja">' + escapeHtml(parts.jp) + '</span>' +
      '<span class="sep">' + escapeHtml(parts.sep) + '</span>' +
      '<span class="vi" lang="vi">' + escapeHtml(parts.vi) + '</span></span>';
  }

  /* ---------- Khối văn bản dài (script bài nghe, bài đọc, lời giải) — giao diện mới ----------
   * Mỗi dòng được phân loại (chỉ ở tầng hiển thị, không sửa dữ liệu):
   *   tiêu đề mục (学習のポイント, スクリプト, 解説, Dịch...) · đáp án (答：) · ví dụ (例：) · người nói (男：/女：/A「...)
   *   · mục đánh số (1）, ①, (1)...) · dòng "tiếng Nhật + chú thích Việt" · dòng thường (màu/cỡ theo ngôn ngữ).
   * Mục đánh số + giải thích + ví dụ gom thành một thẻ nhỏ; không kẻ đứt từng dòng.
   */
  var SCRIPT_HEADING = /^(学習のポイント|ポイント|スクリプト|解説|問題|ことば|語彙|Dịch|Bản dịch|Script|Từ vựng|Dịch\s*&\s*Đáp án|Đáp án\s*&\s*Dịch|Script\s*&\s*Dịch)\s*[:：]?$/i;
  var SCRIPT_ANSWER = /^(答え?|Đáp án)\s*[:：]\s*(.+)$/;
  var SCRIPT_EXAMPLE = /^例\s*[:：]\s*(.*)$/;
  var SCRIPT_SPEAKER = /^(男の人|女の人|男|女|[A-DＡ-Ｄ])\s*(?:[:：]\s*|(?=「))(.+)$/;
  // [JLAS2-17] G6: tên người nói viết dính liền lời thoại ("女子学生これからどうするの？") — chỉ tên nhiều chữ, cần ≥ 2 dòng.
  // Tên + trợ từ ("女子学生はどうしますか。") là câu hỏi, không phải lời thoại dính tên.
  var STUCK_SPEAKER = /^(男子学生|女子学生|修理の人|店員|駅員|係員)(?![\s:：「はがをにのもとへでや、。A-DＡ-Ｄ])(.+)$/;
  // Dòng từ vựng thiếu "：" ("あまい adj 甘い CAM ngọt") → "あまい 甘い：adj CAM ngọt" để hiện như các dòng "từ：nghĩa".
  var VOCAB_NO_COLON = /^([\u3040-\u30ffー〜～（）()]+)\s+((?:n|v|adj|adv|a-i|a-na|na-adj|i-adj|v,n|n,v|vt|vi)\b\.?)\s+(\S*[\u3400-\u9fff々]\S*)\s+(.+)$/;
  var SCRIPT_BREAK = /^(CHECK|[QＱ]\s*[0-9０-９]{1,2}\b|問\s*[0-9０-９]{1,2}(?![0-9０-９]))/;
  var SCRIPT_NUMBER = /^(?:[(（]\s*([0-9０-９]{1,2})\s*[)）]|([0-9０-９]{1,2})\s*[)）.．]|([①-⑳]))\s*(.+)$/;

  function digits(value) {
    return String(value).replace(/[０-９]/g, function (d) { return String.fromCharCode(d.charCodeAt(0) - 0xFEE0); });
  }

  function speakerTone(name) {
    return /^(女|B|Ｂ|D|Ｄ)/.test(name) ? 'b' : 'a';
  }

  function plainSpan(text) {
    var kind = lineKind(text);
    return '<span class="lp-sline__text lp-sline__text--' + kind + '"' +
      (kind === 'ja' ? ' lang="ja"' : (kind === 'vi' ? ' lang="vi"' : '')) + '>' + escapeHtml(text) + '</span>';
  }

  /** Phần sau tiền tố (số / 例：): người nói A「…」 → chip; "Nhật + Việt" → tách; còn lại giữ nguyên. */
  function inlineHtml(text) {
    var sp = text.match(SCRIPT_SPEAKER);
    if (sp) {
      return '<span class="lp-chip-s lp-chip-s--spk lp-spk--' + speakerTone(sp[1]) + '" lang="ja">' + escapeHtml(sp[1]) + '</span>' + plainSpan(sp[2].trim());
    }
    var mixed = splitMixedLine(text);
    if (mixed) {
      return '<span class="jp" lang="ja">' + escapeHtml(mixed.jp) + '</span><span class="sep">' + escapeHtml(mixed.sep) +
        '</span><span class="vi" lang="vi">' + escapeHtml(mixed.vi) + '</span>';
    }
    return plainSpan(text);
  }

  /** Một dòng → { type, html }. type quyết định cách gom nhóm. */
  function scriptEntry(line) {
    var t = String(line || '').trim();
    var m;
    if (t.length <= 24 && (SCRIPT_HEADING.test(t) || /^【[^】]{1,20}】$/.test(t))) {
      return { type: 'heading', html: '<div class="lp-sline lp-sline--heading">' + escapeHtml(t.replace(/\s*[:：]$/, '')) + '</div>' };
    }
    if ((m = t.match(SCRIPT_ANSWER))) {
      return { type: 'answer', html: '<div class="lp-sline lp-sline--answer"><span class="lp-chip-s lp-chip-s--answer">Đáp án</span>' +
        '<strong class="lp-sline__answer">' + escapeHtml(m[2]) + '</strong></div>' };
    }
    if ((m = t.match(SCRIPT_EXAMPLE))) {
      return { type: 'example', html: '<div class="lp-sline lp-sline--example"><span class="lp-chip-s lp-chip-s--ex">Ví dụ</span>' +
        (m[1] ? inlineHtml(m[1].trim()) : '') + '</div>' };
    }
    if ((m = t.match(SCRIPT_SPEAKER))) {
      var colon = /^[^「]*[:：]/.test(t.slice(0, m[1].length + 2));
      return { type: colon ? 'dialogue' : 'quote', html: '<div class="lp-sline lp-sline--speaker">' +
        '<span class="lp-chip-s lp-chip-s--spk lp-spk--' + speakerTone(m[1]) + '" lang="ja">' + escapeHtml(m[1]) + '</span>' +
        plainSpan(m[2].trim()) + '</div>' };
    }
    // [JLAS2-17] G3: tiêu đề con (CHECK, Q1, 問1) đóng thẻ của mục đánh số trước — câu hỏi không dồn vào thẻ ví dụ.
    if (SCRIPT_BREAK.test(t)) {
      return { type: 'break', html: '<div class="lp-sline' + (/^CHECK$/.test(t) ? ' lp-sline--heading' : '') + '">' + plainSpan(t) + '</div>' };
    }
    if ((m = t.match(SCRIPT_NUMBER))) {
      var n = digits(m[1] || m[2] || String('①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳'.indexOf(m[3]) + 1));
      return { type: 'point', html: '<div class="lp-sline lp-sline--point"><span class="lp-num" aria-hidden="true">' + escapeHtml(n) + '</span>' +
        '<span class="lp-sline__body">' + inlineHtml(m[4].trim()) + '</span></div>' };
    }
    var mixed = splitMixedLine(t);
    if (mixed) return { type: 'line', html: '<div class="lp-sline lp-sline--pair">' + inlineHtml(t) + '</div>' };
    return { type: 'line', html: '<div class="lp-sline">' + plainSpan(t) + '</div>' };
  }

  /**
   * Gom nhóm: mục đánh số mở một thẻ, kéo dài qua giải thích / ví dụ cho tới mục kế tiếp, tiêu đề, đáp án, hoặc dòng
   * ngay trước một đoạn hội thoại "男：…" (thường là câu hỏi của bài tiếp theo). translations: dòng dịch gắn sau dòng chính.
   */
  /** Số của mục đánh số (1）, ①, (1)…) hoặc null. */
  function pointNumber(line) {
    var m = String(line || '').trim().match(SCRIPT_NUMBER);
    if (!m) return null;
    return parseInt(digits(m[1] || m[2] || String('①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳'.indexOf(m[3]) + 1)), 10);
  }

  function isTranslationLine(line) {
    var t = String(line || '').trim();
    return !!t && lineKind(t) === 'vi' && !BRACKET_LINE.test(t) && !SCRIPT_HEADING.test(t) && !sectionLabel(t);
  }

  /** Các dãy mục đánh số tiếng Nhật liên tiếp (số tăng dần): [{start, end}] (end không gồm). */
  function pointRuns(items) {
    var runs = [];
    var i = 0;
    while (i < items.length) {
      var n = pointNumber(items[i].main);
      if (n == null || lineKind(items[i].main) !== 'ja') { i++; continue; }
      var j = i + 1;
      var last = n;
      while (j < items.length) {
        var nj = pointNumber(items[j].main);
        if (nj == null || nj <= last || lineKind(items[j].main) !== 'ja') break;
        last = nj;
        j++;
      }
      runs.push({ start: i, end: j });
      i = j;
    }
    return runs;
  }

  /**
   * [JLAS2-17] Chuẩn hoá danh sách câu đánh số (chỉ tầng hiển thị):
   * G3 — k câu đánh số liên tiếp rồi đúng k dòng dịch dồn sau câu cuối → mỗi dòng dịch về dưới câu của nó;
   * G4 — cùng một danh sách câu (giống hệt, chưa có dịch) lặp lại ngay trước bản có dịch → chỉ giữ một bản.
   */
  function normalizeScriptItems(items) {
    var list = items.map(function (x) { return { main: x.main, vi: (x.vi || []).slice() }; });
    if (list.filter(function (x) { return STUCK_SPEAKER.test(String(x.main).trim()); }).length >= 2) {
      list.forEach(function (x) {
        var m = String(x.main).trim().match(STUCK_SPEAKER);
        if (m) x.main = m[1] + '：' + m[2];
      });
    }
    if (list.filter(function (x) { return VOCAB_NO_COLON.test(String(x.main).trim()); }).length >= 3 &&
        list.some(function (x) { return /^[\u3040-\u30ff\u3400-\u9fff々ー〜～（）()]+[:：]\s*\S/.test(String(x.main).trim()); })) {
      list.forEach(function (x) {
        var m = String(x.main).trim().match(VOCAB_NO_COLON);
        if (m && VI_CHAR.test(m[4]) && !JA_CHAR.test(m[4])) x.main = m[1] + ' ' + m[3] + '：' + m[2] + ' ' + m[4];
      });
    }
    pointRuns(list).reverse().forEach(function (r) {
      var k = r.end - r.start;
      if (k < 2) return;
      for (var t = r.start; t < r.end - 1; t++) if (list[t].vi.length) return;
      var lastItem = list[r.end - 1];
      var vis = lastItem.vi.slice();
      var m = r.end;
      while (m < list.length && !list[m].vi.length && isTranslationLine(list[m].main)) { vis.push(list[m].main); m++; }
      if (vis.length !== k || vis.length < 2) return;
      for (var q = 0; q < k; q++) list[r.start + q].vi = [vis[q]];
      list.splice(r.end, m - r.end);
    });
    var runs = pointRuns(list);
    var drop = {};
    for (var a = 0; a + 1 < runs.length; a++) {
      var A = runs[a];
      var Bn = runs[a + 1];
      var gap = Bn.start - A.end;
      if (gap > 1 || A.end - A.start !== Bn.end - Bn.start || A.end - A.start < 2) continue;
      if (gap === 1 && !(A.start > 0 && matchKey(list[A.start - 1].main) === matchKey(list[A.end].main))) continue;
      var same = true;
      for (var u = 0; u < A.end - A.start; u++) {
        if (list[A.start + u].vi.length || matchKey(list[A.start + u].main) !== matchKey(list[Bn.start + u].main)) { same = false; break; }
      }
      if (!same) continue;
      for (var w = A.start; w < A.end; w++) drop[w] = true;
      if (gap === 1) drop[A.end] = true;
    }
    return list.filter(function (x, i) { return !drop[i]; });
  }

  function scriptBlockHtml(items) {
    var out = [];
    var card = null;
    function close() { if (card) { out.push('<div class="lp-card">' + card.join('') + '</div>'); card = null; } }
    items = normalizeScriptItems(items);
    items.forEach(function (item, i) {
      var e = scriptEntry(item.main);
      var next = items[i + 1] ? scriptEntry(items[i + 1].main).type : null;
      if (e.type === 'heading' || e.type === 'answer' || e.type === 'dialogue' || e.type === 'break') close();
      if (e.type === 'point') { close(); card = []; }
      if (card && e.type === 'line' && next === 'dialogue') close();
      var html = e.html + (item.vi || []).map(function (v) {
        return '<div class="lp-sline lp-sline--tr reading-line--vi" lang="vi">' + escapeHtml(v) + '</div>';
      }).join('');
      if (card) card.push(html); else out.push(html);
    });
    close();
    return '<div class="lp-script">' + out.join('') + '</div>';
  }

  function readingLines(text) {
    if (NEW_UI) {
      return scriptBlockHtml(String(text).split('\n').filter(function (l) { return l.trim(); })
        .map(function (l) { return { main: l, vi: [] }; }));
    }
    return String(text).split('\n').map(function (line) {
      if (!line.trim()) return '<span class="reading-line reading-line--blank" aria-hidden="true"></span>';
      var mixed = NEW_UI ? splitMixedLine(line) : null;
      if (mixed) return mixedLineHtml(mixed);
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

  /**
   * Đoạn văn (chữ) của câu đọc hiểu, đặt TRƯỚC đề. media.passage = false khi đã hiện ở chỗ khác (bài đọc chứa câu,
   * hoặc câu liền trước cùng đoạn văn) để không lặp.
   */
  function practiceMediaHtml(q, number, media) {
    var showPassage = !!q.passageHtml && (!media || media.passage !== false);
    var text = showPassage ? passageText(q.passageHtml) : '';
    return text ? '<div class="practice-q__passage reading-block__text">' + readingLines(text) + '</div>' : '';
  }

  /**
   * [E] Ảnh của câu, ngay dưới đề và trước phương án: ảnh đoạn văn (image_url / context_json.passage_images — bỏ khi
   * media.image = false: câu liền trước đã hiện cùng ảnh, hoặc người biên soạn đã ẩn ảnh gốc) + ảnh của chính câu hỏi
   * (context_json.question_images). Nhiều ảnh xếp lưới; bấm ảnh để phóng to.
   */
  function practiceImagesHtml(q, number, media) {
    var passage = !media || media.image !== false ? passageImagesOf(q) : [];
    var seen = {};
    // [JLAS2-17] Tranh dùng chung của nhóm câu đã hiện một lần ở đầu nhóm (sharedImagesHtml) → thẻ câu không lặp lại.
    var own = media && media.sharedImages ? [] : (q.questionImages || []);
    var urls = passage.concat(own).filter(function (u) {
      u = String(u || '').trim();
      if (!u || seen[u]) return false;
      seen[u] = true;
      return true;
    });
    if (!urls.length) return '';
    return '<div class="practice-q__images' + (urls.length > 1 ? ' practice-q__images--grid' : '') + '">' +
      urls.map(function (u, i) {
        var src = escapeHtml(mediaUrl(u));
        var alt = escapeHtml('Hình ' + (urls.length > 1 ? (i + 1) + ' ' : '') + 'của câu ' + number);
        return '<button type="button" class="practice-q__zoom" data-zoom-src="' + src + '" data-zoom-alt="' + alt + '"' +
          ' aria-label="Phóng to ' + alt + '"><img class="practice-q__image" loading="lazy" src="' + src + '" alt="' + alt + '"></button>';
      }).join('') + '</div>';
  }

  /** Danh sách ảnh của chính câu hỏi (bỏ rỗng / trùng, giữ thứ tự) — khoá so nhóm tranh dùng chung. */
  function ownImagesOf(q) {
    var seen = {};
    return (q.questionImages || []).map(function (u) { return String(u || '').trim(); })
      .filter(function (u) { if (!u || seen[u]) return false; seen[u] = true; return true; });
  }

  /**
   * [JLAS2-17] Bộ tranh dùng chung của một nhóm câu LIỀN NHAU có question_images giống hệt (vd Minna Topic 25: tranh a–f
   * xếp trình tự): hiện một lần ở đầu nhóm, lưới, bấm phóng to (cùng lớp phóng to với ảnh câu hỏi).
   */
  function sharedImagesHtml(urls, from, to) {
    var label = 'Tranh dùng chung cho câu ' + from + '–' + to;
    return '<figure class="practice-q-shared" role="group" aria-label="' + escapeHtml(label) + '">' +
      '<figcaption class="practice-q-shared__caption">' + escapeHtml(label) + '</figcaption>' +
      '<div class="practice-q__images' + (urls.length > 1 ? ' practice-q__images--grid' : '') + '">' +
      urls.map(function (u, i) {
        var src = escapeHtml(mediaUrl(u));
        var alt = escapeHtml('Tranh ' + (urls.length > 1 ? (i + 1) + ' ' : '') + 'dùng chung cho câu ' + from + '–' + to);
        return '<button type="button" class="practice-q__zoom" data-zoom-src="' + src + '" data-zoom-alt="' + alt + '"' +
          ' aria-label="Phóng to ' + alt + '"><img class="practice-q__image" loading="lazy" src="' + src + '" alt="' + alt + '"></button>';
      }).join('') + '</div></figure>';
  }

  /** Lớp phóng to ảnh câu hỏi: bấm nền / nút đóng / Esc để thoát, trả focus về ảnh vừa bấm. */
  var zoomEl = null;
  var zoomReturn = null;

  function closeZoom() {
    if (!zoomEl || zoomEl.hidden) return;
    zoomEl.hidden = true;
    document.documentElement.classList.remove('lp-zoom-open');
    if (zoomReturn) zoomReturn.focus();
  }

  function openZoom(btn) {
    if (!zoomEl) {
      zoomEl = document.createElement('div');
      zoomEl.className = 'lp-zoom';
      zoomEl.setAttribute('role', 'dialog');
      zoomEl.setAttribute('aria-modal', 'true');
      zoomEl.innerHTML = '<button type="button" class="lp-zoom__close" aria-label="Đóng ảnh phóng to">' +
        '<i class="fa-solid fa-xmark" aria-hidden="true"></i></button><img class="lp-zoom__img" alt="">';
      zoomEl.addEventListener('click', function (event) { if (!event.target.closest('.lp-zoom__img')) closeZoom(); });
      document.addEventListener('keydown', function (event) { if (event.key === 'Escape') closeZoom(); });
      document.body.appendChild(zoomEl);
    }
    var img = zoomEl.querySelector('.lp-zoom__img');
    img.src = btn.getAttribute('data-zoom-src');
    img.alt = btn.getAttribute('data-zoom-alt') || '';
    zoomEl.setAttribute('aria-label', img.alt || 'Ảnh phóng to');
    zoomReturn = btn;
    zoomEl.hidden = false;
    document.documentElement.classList.add('lp-zoom-open');
    zoomEl.querySelector('.lp-zoom__close').focus();
  }

  function onZoomClick(event) {
    var btn = event.target.closest('[data-zoom-src]');
    if (btn) openZoom(btn);
  }

  function practiceCard(q, number, hatsuwa, media) {
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
      practiceMediaHtml(q, number, media) +
      (q.audioUrl ? '<audio class="reading-block__audio" controls preload="none" src="' +
        escapeHtml(AUDIO_BASE + q.audioUrl) + '"></audio>' : '') +
      (hideOptionText ? '<p class="practice-q__hint">Đọc tình huống, nghe 3 câu đáp rồi chọn số.</p>' : '') +
      (listenFirst
        ? '<p class="practice-q__hint">Nghe rồi chọn đáp án.</p>' +
          '<details class="practice-q__translation practice-q__script"><summary>Xem lời thoại</summary>' +
          textHtml + translationHtml + '</details>'
        : textHtml + translationHtml) +
      (text && placeholder ? '<p class="practice-q__hint">' + escapeHtml(text) + '</p>' : '') +
      practiceImagesHtml(q, number, media) +
      // Phương án không có chữ (nguồn chỉ có hình, ví dụ "Nghe chọn tranh"): một hàng nút số gọn.
      '<div class="practice-q__options' + (numbersOnly ? ' practice-q__options--numbers' : '') +
        (options.some(function (o) { return String(o || '').trim().length > 5; }) ? ' practice-q__options--long' : '') +
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
          (/^Hình số /.test(q.answerText) && !questionImages(q).length
            ? ' <span class="practice-q__hint">(nguồn chưa có hình minh hoạ)</span>' : '') +
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
    if (!(t.length > 0 && t.length <= 40 && !JA_CHAR.test(t) && SOLUTION_MARKER.test(t) && !/^POPUP SCRIPT$/i.test(t))) return false;
    // [JLAS2-17] G1: câu văn tiếng Việt có chữ "đáp án / dịch / giải thích" ("Không có vườn nên đáp án này sai.", "Chọn đáp án
    // nào?") không phải nhãn mục. Dòng có khung nhãn (☞, 【】, <<< >>>, [ ], kết thúc ":") vẫn là nhãn như cũ.
    // Dòng gạch đầu dòng ("– Giải thích", "- Đề nghị …") là nội dung liệt kê, không phải nhãn.
    if (/^[–—\-・]\s/.test(t) && !/[–—\-]$/.test(t)) return false;
    // Ngoặc đơn ( … ) / （ … ）: chỉ là khung nhãn khi bọc cả dòng VÀ bên trong là nhãn ngắn ("(Đáp án)"); câu văn trong
    // ngoặc ("(Đáp án: Một tách cà phê…)", "※Rẻ hơn quầy giao dịch (210 Yên)") là nội dung.
    var paren = /^[(（](.*)[)）]$/.exec(t);
    if (paren || /[)）]$/.test(t)) {
      var inner = paren ? paren[1].trim() : '';
      var innerWords = inner.split(/\s+/).filter(function (w) { return /[0-9A-Za-zÀ-ỹĐđ]/.test(w); }).length;
      if (!paren || /[.?!。？！:：]/.test(inner) || innerWords >= 4) return false;
      return true;
    }
    var framed = /^[☞✏■●◆◇▶►<\[【]/.test(t) || /[:：>\]】]$/.test(t);
    if (framed) return true;
    var words = t.split(/\s+/).filter(function (w) { return /[0-9A-Za-zÀ-ỹĐđ]/.test(w); }).length;
    return !(/[.?!。？！]$/.test(t) || words >= 5);
  }

  /*
   * Giao diện mới: chia khối theo NHÃN đứng riêng một dòng (【Luyện Tập】, ☞ Đáp Án, ☞ Dịch, スクリプト…), chỉ ở tầng
   * hiển thị. Phần trước nhãn đầu tiên là ĐOẠN VĂN. Dòng nhãn thành tiêu đề của phần, không dòng nào bị bỏ (trừ dòng
   * rác 【】/POPUP SCRIPT như trước). Mốc lời giải không khớp nhãn nào (logic cũ isSolutionMarker) vẫn thành phần đáp án.
   */
  var SECTION_LABELS = [
    { kind: 'answer', re: /^(đáp\s*án(\s*(&|và)\s*(dịch|giải\s*thích))?|(dịch|giải\s*thích)\s*(&|và)\s*đáp\s*án|答え|解答|正答)$/i },
    { kind: 'translation', re: /^(dịch|bản\s*dịch|dịch\s*(&|và)\s*giải\s*thích)$/i },
    { kind: 'practice', re: /^(luyện\s*tập|練習)$/i },
    { kind: 'script', re: /^(スクリプト|s(c)?ript(\s*(&|và)\s*dịch)?)$/i }
  ];

  function sectionLabel(line) {
    var t = String(line || '').trim();
    if (!t || t.length > 40) return null;
    var core = t.replace(/^[\s☞✏■●◆◇▶►・*＊]+/, '').replace(/^[【\[（(<]\s*/, '')
      .replace(/\s*[】\]）)>]\s*[:：]?\s*$/, '').replace(/\s*[:：]\s*$/, '').trim();
    for (var i = 0; i < SECTION_LABELS.length; i++) {
      if (SECTION_LABELS[i].re.test(core)) return { kind: SECTION_LABELS[i].kind, label: core };
    }
    if (isSolutionMarker(t)) return { kind: 'answer', label: core };
    return null;
  }

  var BRACKET_LINE = /^[☞\s]*【[^】]{1,30}】\s*[:：]?$/;

  // Nhãn đứng riêng dạng 【…】 không khớp nhãn nào ở trên: mục khác (Từ vựng, Ngữ pháp, Hướng dẫn…) hoặc đoạn văn mới.
  var PASSAGE_LABEL = /^(đọc\s*hiểu|đề|đề\s*bài|読解|本文|文章|問題\s*[0-9０-９]+|mondai\s*[0-9]+)$/i;

  function bracketLabel(line) {
    var m = String(line || '').trim().match(/^[☞\s]*【\s*([^】]{1,20}?)\s*】\s*[:：]?$/);
    return m ? m[1].trim() : null;
  }

  /*
   * { main: đoạn văn (chuỗi), sections: [{kind, label, lines, owner}] } — phần rỗng bị bỏ.
   * owner = phần sở hữu khối đáp án / bản dịch: 'reading' (sau đoạn văn), 'practice' (sau 【Luyện Tập】), hoặc null khi
   * ngay trước nó là một mục khác (Từ vựng, Ngữ pháp…) — không đoán, hiển thị ở "Đáp án / bản dịch khác".
   */
  function splitSections(text) {
    var main = [];
    var sections = [];
    var cur = null;
    var owner = 'reading';
    String(text || '').split('\n').forEach(function (line) {
      var lab = sectionLabel(line);
      var bracket = lab ? null : bracketLabel(line);
      if (!lab && bracket && cur) {
        lab = PASSAGE_LABEL.test(bracket) ? { kind: 'passage', label: bracket } : { kind: 'note', label: bracket };
      }
      if (lab) {
        if (lab.kind === 'practice') owner = 'practice';
        else if (lab.kind === 'passage') owner = 'reading';
        else if (lab.kind === 'note') owner = null;
        cur = { kind: lab.kind, label: lab.label, lines: [], owner: owner };
        sections.push(cur);
        return;
      }
      if (cur) { if (!JUNK_LINE.test(line.trim())) cur.lines.push(line); }
      else main.push(line);
    });
    return {
      main: main.join('\n'),
      sections: sections.filter(function (x) { return x.lines.some(function (l) { return l.trim(); }); })
    };
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
      // [JLAS2-17] G2: dòng nhãn 【…】 (vd 【Đọc Hiểu】 sau phần ví dụ ngữ pháp của Marugoto) không bao giờ là bản dịch của câu
      // trước — thành tiêu đề riêng, đóng thẻ câu ví dụ (đoạn văn phía sau không còn dồn vào thẻ).
      if (NEW_UI && BRACKET_LINE.test(t)) { pairs.push({ main: t, kind: 'plain', vi: [] }); return; }
      // Phần lời giải: dòng chữ Latin không dấu ngay sau câu Nhật chưa có dịch là bản dịch tên riêng ("山川" → "Yamakawa").
      if (latinIsTranslation && kind === 'plain' && /[A-Za-z]/.test(t) && prev && prev.kind === 'ja' && !prev.vi.length) {
        prev.vi.push(t);
        return;
      }
      if (kind === 'vi' && pairs.length) pairs[pairs.length - 1].vi.push(t);
      else pairs.push({ main: t, kind: kind, vi: [] });
    });
    if (NEW_UI) return scriptBlockHtml(pairs);
    return pairs.map(function (p) {
      var mixed = NEW_UI && p.kind === 'ja' ? splitMixedLine(p.main) : null;
      return '<div class="reading-pair">' +
        (mixed ? mixedLineHtml(mixed)
          : '<span class="reading-line reading-line--' + p.kind + '"' + (p.kind === 'ja' ? ' lang="ja"' : (p.kind === 'vi' ? ' lang="vi"' : '')) + '>' +
            escapeHtml(p.main) + '</span>') +
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
    var known = knownKeys(questions);
    return String(text || '').split('\n').filter(function (line) {
      var t = line.trim();
      if (!t) return true;
      if (JUNK_LINE.test(t) || QUESTION_MARKER.test(t)) return false;
      if (questions.length === 0) return true;
      if (/^[0-9０-９]+$/.test(t)) return false;              // số phương án "1".."4" của bài nghe chọn tranh
      return !isKnownLine(t, known);
    });
  }

  /** Khoá so khớp của mọi dòng đã có trên thẻ câu hỏi: đề, đề khôi phục, nghĩa của đề, phương án. */
  function knownKeys(questions) {
    var known = [];
    questions.forEach(function (q) {
      if (q.questionText) known.push(matchKey(q.questionText));
      if (q.recoveredText) known.push(matchKey(q.recoveredText));
      if (q.translation) known.push(matchKey(q.translation));
      (q.options || []).forEach(function (o) { if (o) known.push(matchKey(o)); });
    });
    return known.filter(Boolean);
  }

  /*
   * So theo matchKey (bỏ khoảng trắng, NFKC). Trùng nguyên dòng thì khớp ở mọi độ dài (phương án một chữ như "き");
   * "chứa trong dòng" chỉ với chuỗi đủ dài, để không xoá nhầm câu văn của đoạn đọc. Dòng phương án "１ aとb" /
   * "2. 楽しい": bỏ số thứ tự đầu dòng rồi so.
   */
  function isKnownLine(line, known) {
    var key = matchKey(line);
    var bare = key.replace(/^[0-9]+[.、)]?/, '');
    return known.some(function (k) {
      return key === k || bare === k || (k.length >= 4 && key.indexOf(k) !== -1 && key.length <= k.length + 6);
    });
  }

  /*
   * [D] Khối "Bản dịch đoạn văn" / "Đáp án & bản dịch" của phần đọc hiểu: chỉ giữ dịch của đoạn văn (và dòng đáp án
   * 答え… của nguồn). Bỏ dòng 問NN, dòng trùng đề / nghĩa / phương án đã có trên thẻ câu hỏi, và dòng tiếng Việt
   * đứng ngay sau một dòng vừa bỏ (là bản dịch của đề / phương án đó, đã có ở "Xem nghĩa" từng câu).
   */
  function translationLines(lines, questions) {
    if (!questions.length) return lines;
    var known = knownKeys(questions);
    var afterDropped = false;
    var lastQuestion = null;   // câu có đề vừa bị bỏ: thẻ chưa có nghĩa thì dòng dịch ngay sau thành "Xem nghĩa"
    return lines.filter(function (line) {
      var t = line.trim();
      if (!t) return true;
      if (QUESTION_MARKER.test(t) || /^[0-9０-９]+$/.test(t) || isKnownLine(t, known)) {
        afterDropped = true;
        lastQuestion = questionOfText(t, questions);
        return false;
      }
      if (afterDropped && lineKind(t) === 'vi') {
        if (lastQuestion && !lastQuestion.translation) lastQuestion.translation = t;
        lastQuestion = null;
        return false;
      }
      afterDropped = false;
      lastQuestion = null;
      return true;
    });
  }

  /** Câu có đề trùng nguyên dòng (theo matchKey, kể cả đề khôi phục); null nếu dòng không phải đề. */
  function questionOfText(line, questions) {
    var key = matchKey(line);
    for (var i = 0; i < questions.length; i++) {
      var q = questions[i];
      if ((q.questionText && matchKey(q.questionText) === key) || (q.recoveredText && matchKey(q.recoveredText) === key)) return q;
    }
    return null;
  }

  /*
   * [D] Dạng một mục nối nhiều đoạn văn (Gokaku…): "đoạn văn → Dịch & Giải thích → 問NN + đề + phương án → đoạn văn kế
   * tiếp…". Tách thành từng phần { passage, trans, transLabel, qs } để mỗi đoạn văn đi cùng đúng câu hỏi và bản dịch của
   * nó. Câu thứ N của mục ↔ dòng 問N; dòng thuộc câu hỏi nhận ra bằng nội dung (đề / nghĩa / phương án của câu đó).
   * Chỉ tách khi chắc chắn, không thì trả null (giữ cách hiển thị cũ): không có nhãn khác (Luyện tập, script, 【…】),
   * mỗi câu của mục có đúng một dòng 問N, có ít nhất 2 phần và phần nào cũng có câu hỏi.
   */
  function segmentPassages(text, ownAll) {
    if (ownAll.length < 2) return null;
    var segs = [{ passage: [], trans: [], transLabel: null, qs: [] }];
    var cur = segs[0];
    var mode = 'passage';
    var curKeys = [];
    var seen = 0;
    var used = {};
    var lines = String(text || '').split('\n');
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i];
      var t = line.trim();
      if (!t) { if (mode !== 'question') (mode === 'trans' ? cur.trans : cur.passage).push(line); continue; }
      if (JUNK_LINE.test(t)) continue;
      var lab = sectionLabel(t);
      if (lab) {
        if ((lab.kind !== 'translation' && lab.kind !== 'answer') || cur.transLabel) return null;
        cur.transLabel = lab.label;
        mode = 'trans';
        continue;
      }
      if (bracketLabel(t)) return null;
      if (QUESTION_MARKER.test(t)) {
        var qi = Number(digits(t.match(/[0-9０-９]+/)[0])) - 1;
        var q = ownAll[qi];
        if (!q || used[qi]) return null;
        used[qi] = true;
        seen++;
        cur.qs.push(q);
        curKeys = knownKeys([q]);
        mode = 'question';
        continue;
      }
      if (mode === 'question') {
        if (/^[0-9０-９]+$/.test(t) || isKnownLine(t, curKeys)) continue;   // dòng của câu hỏi (đã có trên thẻ)
        if (lineKind(t) !== 'ja') return null;   // dòng lạ không phải đoạn văn tiếng Nhật: không đoán
        cur = { passage: [line], trans: [], transLabel: null, qs: [] };
        segs.push(cur);
        mode = 'passage';
        continue;
      }
      (mode === 'trans' ? cur.trans : cur.passage).push(line);
    }
    var ok = seen === ownAll.length && segs.length >= 2 && segs.every(function (s) { return s.qs.length > 0; });
    return ok ? segs : null;
  }

  /** [C] Có ít nhất một dòng nội dung thật: không tính dòng trống, nhãn (【…】, Đáp án, Dịch…), 問N, số trơn, dòng rác. */
  function hasText(lines) {
    return (lines || []).some(function (line) {
      var t = String(line || '').trim();
      return !!t && !JUNK_LINE.test(t) && !QUESTION_MARKER.test(t) && !/^[0-9０-９.．、)）(（\s]+$/.test(t) &&
        !sectionLabel(t) && !bracketLabel(t);
    });
  }

  /** Câu của một mục theo thứ tự trong quiz (orderIndex), để câu thứ N khớp dòng 問N. */
  function ownByOrder(list) {
    return list.map(function (q, i) { return { q: q, i: i }; }).sort(function (a, b) {
      var oa = a.q.orderIndex == null ? Infinity : a.q.orderIndex;
      var ob = b.q.orderIndex == null ? Infinity : b.q.orderIndex;
      return oa !== ob ? oa - ob : a.i - b.i;
    }).map(function (x) { return x.q; });
  }

  /**
   * Mỗi phần thành một khối: phần đầu mang tiêu đề + mã của mục (ảnh Bước 7, audio), các phần sau là "Đoạn văn N".
   * passageShown: đoạn văn của câu (passage_code = mã mục) đã hiện qua các phần → thẻ câu hỏi không lặp lại.
   */
  function pushSegmentBlocks(blocks, it, listening, audio, segs) {
    var passageNo = 0;
    var questionNo = 0;   // số "Câu N" chạy tiếp qua các khối của mục, khớp số 問N của nguồn
    segs.forEach(function (s, k) {
      var visible = s.qs.filter(function (q) { return !q.pictureChoice; });
      var lines = passageLines(s.passage.join('\n'), s.qs);
      var hasPassage = lines.some(function (l) { return l.trim(); });
      if (hasPassage) passageNo++;
      var trans = translationLines(s.trans, s.qs);
      var sections = trans.some(function (l) { return l.trim(); })
        ? [{ kind: 'translation', label: s.transLabel, lines: trans, owner: 'reading' }] : [];
      if (!hasPassage && !visible.length && !(k === 0 && audio)) return;   // [C] chỉ còn bản dịch: không hiện
      blocks.push({
        item: k === 0 ? it : { title: hasPassage ? 'Đoạn văn ' + passageNo : 'Câu hỏi', sectionId: it.sectionId, sectionTitle: it.sectionTitle },
        listening: listening, lines: hasPassage ? lines : [], audio: k === 0 ? audio : null, extra: [], extraLabel: null,
        sections: sections, translation: null, questions: visible, passageShown: it.stableCode || true,
        numberStart: questionNo
      });
      questionNo += visible.length;
    });
  }

  /** [E] Mọi ảnh của câu (đoạn văn + chính câu hỏi), không trùng. */
  /** Ảnh đoạn văn của câu: passageImages của API (đã bỏ image_url khi đó là ảnh đề); API cũ không có trường này → image_url. */
  function passageImagesOf(q) {
    return Array.isArray(q.passageImages) ? q.passageImages : (q.imageUrl ? [q.imageUrl] : []);
  }

  function questionImages(q) {
    var seen = {};
    return [].concat(q.passageImages && q.passageImages.length ? q.passageImages : (q.imageUrl ? [q.imageUrl] : []),
      q.questionImages || []).filter(function (u) {
      u = String(u || '').trim();
      if (!u || seen[u]) return false;
      seen[u] = true;
      return true;
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
      // [E] Câu chọn tranh chỉ ẩn khi CHƯA có ảnh; seed / tool bổ sung ảnh thì câu tự hiện lại.
      q.pictureChoice = isPictureChoice(q, parent) && questionImages(q).length === 0;
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
      // [D] Mục nối nhiều đoạn văn: mỗi đoạn văn thành một khối riêng (đoạn văn → câu hỏi của nó → bản dịch của nó).
      var segs = NEW_UI ? segmentPassages(it.textJa, ownByOrder(own.concat(hiddenOwn))) : null;
      if (segs) {
        pushSegmentBlocks(blocks, it, x.listening, itemAudio(it), segs);
        return;
      }
      // Phần sau dòng mốc "Dịch & Đáp án"/"Script & Dịch" là lời giải: giữ nguyên, thu gọn khi hiển thị.
      var split = splitSolution(it.textJa);
      var sp = NEW_UI ? splitSections(it.textJa) : null;
      var lines = passageLines(NEW_UI ? sp.main : split.main, own.concat(hiddenOwn));
      var hasPassage = lines.some(function (l) { return l.trim(); });
      // Phần "Luyện tập" lọc như đoạn văn: dòng trùng câu hỏi đã hiện thành thẻ thì không lặp lại.
      // [D] Đáp án / bản dịch của phần đọc hiểu: bỏ dòng của câu hỏi (đề, phương án, 問NN) — đã có trên thẻ.
      var sections = NEW_UI ? sp.sections.map(function (x) {
        if (x.kind === 'practice') {
          return { kind: x.kind, label: x.label, lines: passageLines(x.lines.join('\n'), own.concat(hiddenOwn)), owner: x.owner };
        }
        if ((x.kind === 'translation' || x.kind === 'answer') && x.owner === 'reading') {
          return { kind: x.kind, label: x.label, lines: translationLines(x.lines, own.concat(hiddenOwn)), owner: x.owner };
        }
        return x;
      }).filter(function (x) { return x.lines.some(function (l) { return l.trim(); }); }) : [];
      if (NEW_UI) {
        hasPassage = hasText(lines);
        // Mục không có đoạn văn, không câu hỏi, mở đầu ngay bằng nhãn "đáp án / dịch" (【Bài tập – đáp án mẫu】…): nhãn
        // đó là tiêu đề của nội dung, không phải lời giải của phần nào → hiện mở như một mục, không gói sau nút.
        if (!hasPassage && own.length === 0 && sections.length && sections[0].owner === 'reading' &&
            (sections[0].kind === 'answer' || sections[0].kind === 'translation')) {
          sections[0] = { kind: 'note', label: sections[0].label, lines: sections[0].lines, owner: null };
        }
      }
      var hasExtra = NEW_UI ? sections.length > 0 : split.extra.some(function (l) { return l.trim(); });
      var audio = itemAudio(it);
      var hasTranslation = !!(it.textVi && it.textVi.trim()) && own.length === 0;
      if (!hasPassage && !hasExtra && !audio && !hasTranslation && own.length === 0) return;
      // [C] Giao diện mới: khối không có chữ (đoạn văn / đề / mục khác — chỉ còn nhãn, số, dòng rác thì coi như
      // không), không câu hỏi, không audio, không ảnh → không hiện, kể cả nút đáp án / bản dịch của nó.
      if (NEW_UI && !hasPassage && !audio && own.length === 0 && !readingImageFor(it) &&
          !sections.some(function (x) { return x.kind !== 'answer' && x.kind !== 'translation' && hasText(x.lines); })) return;
      blocks.push({ item: it, listening: x.listening, lines: hasPassage ? lines : [], audio: audio,
        extra: !NEW_UI && hasExtra ? split.extra : [], extraLabel: split.label, sections: sections,
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

  /**
   * Cờ hiện đoạn văn/ảnh cho từng câu của khối: đoạn văn đã hiện bằng bài đọc chứa câu (cùng mã, hoặc câu không ghi
   * mã đoạn văn) thì không hiện lại; nhiều câu liền nhau chung một đoạn văn/ảnh thì chỉ câu đầu hiện.
   */
  function questionMediaFlags(block) {
    var it = block.item || {};
    var override = readingImageFor(it);
    var hideSeed = !!(override && override.hideSeedImage);
    var blockHasPassage = block.lines && block.lines.length > 0 && !!it.stableCode;
    var lastPassage = null;
    var lastImage = null;
    return block.questions.map(function (q) {
      // Khối tách từ một mục nhiều đoạn văn: đoạn văn của mục đã hiện qua các khối của nó (kể cả khối chỉ có câu hỏi).
      var shownByBlock = block.passageShown
        ? (!q.passageCode || block.passageShown === true || q.passageCode === block.passageShown)
        : blockHasPassage && (!q.passageCode || q.passageCode === it.stableCode);
      // Ảnh đoạn văn: nhiều câu liền nhau chung ảnh thì chỉ câu đầu hiện (so cả danh sách ảnh).
      var passageImgs = passageImagesOf(q).join('|');
      var flags = {
        passage: !!q.passageHtml && !shownByBlock && q.passageHtml !== lastPassage,
        image: !hideSeed && !!passageImgs && passageImgs !== lastImage
      };
      if (q.passageHtml) lastPassage = q.passageHtml;
      if (passageImgs) lastImage = passageImgs;
      return flags;
    }).map(function (flags, i, all) {
      // [JLAS2-17] Nhóm tranh dùng chung: ≥ 2 câu liền nhau có question_images giống hệt → câu đầu nhóm mang
      // groupImages (+ số câu của nhóm), mọi câu trong nhóm sharedImages = true (thẻ câu không vẽ lại tranh).
      var key = ownImagesOf(block.questions[i]).join('|');
      if (!key) return flags;
      var prev = i > 0 ? ownImagesOf(block.questions[i - 1]).join('|') : '';
      if (prev === key) { flags.sharedImages = true; return flags; }
      var size = 1;
      while (i + size < all.length && ownImagesOf(block.questions[i + size]).join('|') === key) size++;
      if (size > 1) {
        flags.sharedImages = true;
        flags.groupImages = ownImagesOf(block.questions[i]);
        flags.groupSize = size;
      }
      return flags;
    });
  }

  /** Một phần đã chia theo nhãn (giao diện mới). Nhãn nút ghi rõ phần (đọc hiểu / luyện tập / khác). */
  function sectionHtml(sec) {
    var body = '<div class="reading-block__text">' + scriptBlockHtml(sec.lines.filter(function (l) { return l.trim(); })
      .map(function (l) { return { main: l, vi: [] }; })) + '</div>';
    if (sec.kind === 'practice') {
      return '<section class="lp-sec lp-sec--practice"><h5 class="lp-sec__title">Đề luyện tập</h5>' + body + '</section>';
    }
    if (sec.kind === 'note') {
      return '<section class="lp-sec lp-sec--note"><h5 class="lp-sec__title">' + escapeHtml(sec.label) + '</h5>' + body + '</section>';
    }
    if (sec.kind === 'passage') {
      return '<section class="lp-sec lp-sec--passage"><h5 class="lp-sec__title">' + escapeHtml(sec.label) + '</h5>' + body + '</section>';
    }
    if (sec.kind === 'script') {
      return '<details class="lp-sec lp-sec--script"><summary class="lp-sec__summary">Xem lời thoại</summary>' + body + '</details>';
    }
    // [D] Khối đáp án / bản dịch trùng nội dung với một khối đã hiện trong tab → chỉ hiện một lần.
    var dupKey = matchKey(sec.lines.join(''));
    if (dupKey && shownSolutions[dupKey]) return '';
    shownSolutions[dupKey] = true;
    var part = sec.owner === 'practice' ? ' luyện tập' : (sec.owner === 'reading' ? ' đọc hiểu' : '');
    var label;
    if (sec.kind === 'translation') {
      label = sec.owner === 'practice' ? 'Bản dịch luyện tập' : (sec.owner === 'reading' ? 'Bản dịch đoạn văn' : 'Bản dịch');
    } else {
      label = /dịch/i.test(sec.label || '') ? 'Đáp án &amp; bản dịch' + part : 'Đáp án' + part;
    }
    if (!sec.owner) label += ' (' + escapeHtml(sec.label || 'khác') + ')';
    return '<details class="lp-sec lp-sec--' + (sec.kind === 'translation' ? 'translation' : 'answer') + '">' +
      '<summary class="lp-sec__summary">' + label + '</summary>' + body + '</details>';
  }

  // Phần Luyện tập và "Đáp án / bản dịch khác" của mọi khối: renderReading() đặt ở CUỐI tab.
  var tailParts = { practice: [], other: [] };
  // Khoá nội dung các khối đáp án / bản dịch đã vẽ trong lần renderReading() hiện tại.
  var shownSolutions = {};
  /** Ảnh chung đã hiện (theo bài đọc / nghe cha): khối tách từ cùng một mục chỉ hiện ảnh chung ở khối đầu. */
  var shownCommonImages = {};

  /**
   * [JLAS2-17] Ảnh dùng chung của bài đọc / bài nghe (context_json.common_images của các câu): hiện một lần ở đầu khối,
   * trước audio / đoạn văn; nhiều ảnh xếp lưới, bấm để phóng to (cùng lớp phóng to với ảnh câu hỏi).
   */
  function commonImagesHtml(block) {
    var urls = [];
    var seen = {};
    (block.questions || []).forEach(function (q) {
      var key = q.parentCode || '';
      if (shownCommonImages[key + '|' + (q.commonImages || []).join('|')]) return;
      (q.commonImages || []).forEach(function (u) {
        u = String(u || '').trim();
        if (u && !seen[u]) { seen[u] = true; urls.push(u); }
      });
    });
    (block.questions || []).forEach(function (q) { shownCommonImages[(q.parentCode || '') + '|' + (q.commonImages || []).join('|')] = true; });
    if (!urls.length) return '';
    var title = (block.item && block.item.title) || 'bài';
    return '<div class="lp-common-images' + (urls.length > 1 ? ' lp-common-images--grid' : '') + '">' +
      urls.map(function (u, i) {
        var src = escapeHtml(mediaUrl(u));
        var alt = escapeHtml('Hình ' + (urls.length > 1 ? (i + 1) + ' ' : '') + 'của ' + title);
        return '<button type="button" class="practice-q__zoom lp-common-images__item" data-zoom-src="' + src + '" data-zoom-alt="' + alt + '"' +
          ' aria-label="Phóng to ' + alt + '"><img class="lp-common-images__img" loading="lazy" src="' + src + '" alt="' + alt + '"></button>';
      }).join('') + '</div>';
  }

  /**
   * Phần của khối theo thứ tự gốc: khối đọc hiểu (đáp án / bản dịch / đoạn văn mới / lời thoại / mục khác) trả về để
   * vẽ ngay dưới câu hỏi; đề luyện tập + đáp án / bản dịch của nó, và khối không rõ phần, gom vào tailParts.
   */
  /** [JLAS2-17] G5: mọi câu đánh số của phần luyện tập đã có trên thẻ câu hỏi của khối (so nội dung câu) → không vẽ lại. */
  function practiceCoveredByCards(sec, questions) {
    if (!questions || !questions.length) return false;
    var keys = questions.map(function (q) { return matchKey(q.questionText || ''); });
    var nums = sec.lines.filter(function (l) { return pointNumber(l) != null; });
    if (nums.length < 2) return false;
    return nums.every(function (l) {
      var m = l.trim().match(SCRIPT_NUMBER);
      var body = matchKey(m ? m[4] : l);
      return body.length >= 4 && keys.some(function (k) { return k.indexOf(body) !== -1; });
    });
  }

  function readingTailHtml(block, sections) {
    var here = [];
    var practice = [];
    sections = sections.filter(function (x) { return !(x.kind === 'practice' && practiceCoveredByCards(x, block.questions)); });
    sections.forEach(function (x) {
      if (x.kind === 'practice' || ((x.kind === 'answer' || x.kind === 'translation') && x.owner === 'practice')) practice.push(x);
      else if ((x.kind === 'answer' || x.kind === 'translation') && !x.owner) tailParts.other.push({ title: block.item.title, sec: x });
      else here.push(x);
    });
    // [C] Đáp án / bản dịch luyện tập mà không còn đề luyện tập nào → không hiện.
    if (practice.some(function (x) { return x.kind === 'practice'; })) tailParts.practice.push({ title: block.item.title, secs: practice });
    return here.map(sectionHtml).join('');
  }

  function tailPartsHtml() {
    // [JLAS2-17] Phần đã bị bỏ hết (đáp án / bản dịch trùng nội dung đã hiện) → không vẽ khối rỗng.
    var practice = tailParts.practice.map(function (p) { return { title: p.title, body: p.secs.map(sectionHtml).join('') }; })
      .filter(function (p) { return p.body; });
    var many = practice.length > 1;
    var html = practice.length
      ? '<section class="reading-section lp-part lp-part--practice"><h3 class="reading-section__title">Luyện tập</h3>' +
        practice.map(function (p) {
          return '<article class="reading-block">' + (many && p.title ? '<h4 class="reading-block__title">' + escapeHtml(p.title) + '</h4>' : '') +
            p.body + '</article>';
        }).join('') + '</section>'
      : '';
    var other = tailParts.other.map(function (o) { return sectionHtml(o.sec); }).join('');
    html += other
      ? '<section class="reading-section lp-part lp-part--other"><h3 class="reading-section__title">Đáp án / bản dịch khác</h3>' +
        '<article class="reading-block">' + other + '</article></section>'
      : '';
    return html;
  }

  /** Track audio đứng riêng (mục "Audio N" không có lời thoại, không câu hỏi): thẻ nhỏ có số thứ tự. */
  function trackHtml(block) {
    var m = String(block.item.title || '').match(/(\d+)\s*$/);
    return '<article class="reading-block lp-track">' +
      '<span class="lp-track__num" aria-hidden="true">' + escapeHtml(m ? m[1] : '♪') + '</span>' +
      '<div class="lp-track__body"><h4 class="reading-block__title">' + escapeHtml(block.item.title || 'Audio') + '</h4>' +
      '<audio class="reading-block__audio" controls preload="none" src="' + escapeHtml(AUDIO_BASE + block.audio.audioUrl) + '"' +
      ' aria-label="' + escapeHtml(block.item.title || 'Audio') + '"></audio></div></article>';
  }

  /**
   * [JLAS2-17] G5: khối là TRANG GIẢI THÍCH sau đề (500 câu hỏi: kanji, từ, mẫu ◇… kèm đáp án) — có câu hỏi riêng và ≥ 3 dòng
   * "◇". Hiện câu hỏi trước, giải thích thu gọn sau ("xem sau khi làm") để không lộ đáp án.
   */
  function isExplanationBlock(block) {
    if (!NEW_UI || !block.questions.length) return false;
    var diamonds = (block.lines || []).filter(function (l) { return /^\s*◇/.test(l); }).length;
    return diamonds >= 3;
  }

  function readingBlockHtml(block) {
    var it = block.item;
    var number = block.numberStart || 0;
    var mediaFlags = questionMediaFlags(block);
    var explain = isExplanationBlock(block);
    if (NEW_UI && block.audio && !block.lines.length && !block.questions.length && !(block.sections || []).length &&
        !block.translation) {
      return trackHtml(block);
    }
    var sections = NEW_UI ? (block.sections || []) : [];
    return '<article class="reading-block"' + (NEW_UI && it.stableCode ? ' data-reading-code="' + escapeHtml(it.stableCode) + '"' : '') + '>' +
      (it.title ? '<h4 class="reading-block__title">' + escapeHtml(it.title) + '</h4>' : '') +
      (NEW_UI ? readingImageHtml(block) + commonImagesHtml(block) : '') +
      // preload="none": bài nghe có thể vài chục file, không tải trước khi người học bấm phát.
      (block.audio ? '<audio class="reading-block__audio" controls preload="none" src="' +
        escapeHtml(AUDIO_BASE + block.audio.audioUrl) + '"></audio>' : '') +
      (block.lines.length && !explain
        ? (isListeningBlock(block)
          // Bài nghe: transcript thu gọn để nghe trước, không đọc trước được đáp án.
          ? '<details class="reading-block__script"><summary>Xem lời thoại (transcript)</summary>' +
            '<div class="reading-block__text">' + bilingualHtml(block.lines) + '</div></details>'
          : passageHtml(block.lines))
        : '') +
      (block.questions.length
        ? '<div class="practice-list">' + block.questions.map(function (q, i) {
            number++;
            var f = mediaFlags[i];
            return (f.groupImages ? sharedImagesHtml(f.groupImages, number, number + f.groupSize - 1) : '') +
              practiceCard(q, number, q.hatsuwa, f);
          }).join('') + '</div>'
        : '') +
      (explain
        ? '<details class="lp-sec lp-sec--explain"><summary class="lp-sec__summary">Giải thích &amp; từ vựng (xem sau khi làm)</summary>' +
          passageHtml(block.lines) + '</details>'
        : '') +
      readingTailHtml(block, sections) +
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

  function readingImageFor(item) {
    return item && item.stableCode && readingImages.available ? readingImages.byCode[item.stableCode] || null : null;
  }

  /** Ảnh đã chỉnh của bài đọc (nếu có) + công cụ thêm / thay / ẩn ảnh gốc / gỡ cho người biên soạn. */
  function readingImageHtml(block) {
    var it = block.item || {};
    var override = readingImageFor(it);
    var img = override && override.imageUrl
      ? '<figure class="lp-rimg"><img class="lp-rimg__img" loading="lazy" src="' + escapeHtml(mediaUrl(override.imageUrl)) +
        '" alt="' + escapeHtml(it.title || 'Ảnh bài đọc') + '"></figure>'
      : '';
    if (!canEditReadingImages || !readingImages.available || !it.stableCode) return img;
    var hasSeed = block.questions.some(function (q) { return passageImagesOf(q).length > 0; });
    var noImage = !override && !hasSeed;
    // [F] Chưa có ảnh nào: chỉ một nút nhỏ "Thêm ảnh"; bấm mới mở khung kéo thả / chọn file (vẫn thả ảnh lên nút được).
    if (noImage && !readingImageToolsOpen[it.stableCode]) {
      return img +
        '<div class="lp-rimg-tools is-collapsed" data-code="' + escapeHtml(it.stableCode) + '" data-title="' + escapeHtml(it.title || '') + '">' +
          '<input type="file" class="lp-rimg-tools__file" accept="image/png,image/jpeg,image/webp" hidden>' +
          '<button type="button" class="lp-rimg-btn lp-rimg-btn--sm" data-rimg-action="expand" aria-expanded="false">' +
            '<i class="fa-regular fa-image" aria-hidden="true"></i> Thêm ảnh</button>' +
          '<span class="lp-rimg-tools__spinner" aria-hidden="true"></span>' +
          '<p class="lp-rimg-tools__error" role="alert" hidden></p>' +
        '</div>';
    }
    var note = override
      ? (override.imageUrl ? 'Đang dùng ảnh tải lên' + (hasSeed ? ' (thay ảnh gốc)' : '') : 'Đã ẩn ảnh gốc')
      : (hasSeed ? 'Đang dùng ảnh gốc của nguồn' : 'Chưa có ảnh');
    return img +
      '<div class="lp-rimg-tools" data-code="' + escapeHtml(it.stableCode) + '" data-title="' + escapeHtml(it.title || '') + '">' +
        '<input type="file" class="lp-rimg-tools__file" accept="image/png,image/jpeg,image/webp" hidden>' +
        '<p class="lp-rimg-tools__hint"><i class="fa-regular fa-image" aria-hidden="true"></i> ' + escapeHtml(note) +
          ' · Kéo thả ảnh vào đây hoặc bấm nút (PNG/JPG/WEBP, tối đa 5MB)</p>' +
        '<div class="lp-rimg-tools__actions">' +
          '<button type="button" class="lp-rimg-btn" data-rimg-action="pick">' +
            (override && override.imageUrl ? 'Thay ảnh' : 'Thêm ảnh') + '</button>' +
          (!override && hasSeed ? '<button type="button" class="lp-rimg-btn lp-rimg-btn--ghost" data-rimg-action="hide">Ẩn ảnh gốc</button>' : '') +
          (override ? '<button type="button" class="lp-rimg-btn lp-rimg-btn--danger" data-rimg-action="remove">' +
            (override.imageUrl ? 'Xoá ảnh' : 'Hiện lại ảnh gốc') + '</button>' : '') +
          (noImage ? '<button type="button" class="lp-rimg-btn lp-rimg-btn--ghost" data-rimg-action="collapse">Thu gọn</button>' : '') +
          '<span class="lp-rimg-tools__spinner" aria-hidden="true"></span>' +
        '</div>' +
        '<p class="lp-rimg-tools__error" role="alert" hidden></p>' +
      '</div>';
  }

  function readingImageError(res, json) {
    if (res.status === 401) return 'Phiên đăng nhập đã hết, vui lòng đăng nhập lại.';
    if (res.status === 403) return 'Bạn không có quyền sửa ảnh bài đọc.';
    if (res.status === 413) return 'Ảnh vượt quá 5MB.';
    return (json && json.message) || ('Không thực hiện được (HTTP ' + res.status + ').');
  }

  function readingImageRequest(tools, method, suffix, body) {
    var code = tools.getAttribute('data-code');
    var errEl = tools.querySelector('.lp-rimg-tools__error');
    var headers = window.JlasAuth && typeof JlasAuth.authHeaders === 'function' ? JlasAuth.authHeaders() : {};
    delete headers['Content-Type'];   // FormData tự đặt boundary
    errEl.hidden = true;
    tools.classList.add('is-busy');
    tools.querySelectorAll('button').forEach(function (b) { b.disabled = true; });
    return fetch('/api/dictionary/reading-images/' + encodeURIComponent(code) + (suffix || ''), { method: method, headers: headers, body: body })
      .then(function (res) {
        return res.json().catch(function () { return null; }).then(function (json) {
          if (!res.ok || !json || json.success === false) throw new Error(readingImageError(res, json));
          return json.data;
        });
      })
      .then(function (data) {
        if (data) readingImages.byCode[code] = data; else delete readingImages.byCode[code];
        rerenderReadingKeepPlace(code);
        if (window.JlasUI && typeof JlasUI.toast === 'function') {
          JlasUI.toast(method === 'DELETE' ? 'Đã gỡ ảnh bài đọc.' : (suffix ? 'Đã ẩn ảnh gốc.' : 'Đã cập nhật ảnh bài đọc.'), 'success');
        }
      })
      .catch(function (err) {
        tools.classList.remove('is-busy');
        tools.querySelectorAll('button').forEach(function (b) { b.disabled = false; });
        errEl.textContent = err.message || 'Không thực hiện được, vui lòng thử lại.';
        errEl.hidden = false;
      });
  }

  /** Vẽ lại tab Bài đọc (không tải lại trang) và giữ vị trí ở bài đọc vừa sửa. */
  function rerenderReadingKeepPlace(code) {
    renderReading();
    var article = document.querySelector('#panelReading [data-reading-code="' + (window.CSS && CSS.escape ? CSS.escape(code) : code) + '"]');
    if (article) article.scrollIntoView({ block: 'nearest' });
  }

  function uploadReadingImage(tools, file) {
    var errEl = tools.querySelector('.lp-rimg-tools__error');
    var fail = function (msg) { errEl.textContent = msg; errEl.hidden = false; };
    if (!file) return;
    if (READING_IMAGE_TYPES.indexOf(String(file.type).toLowerCase()) < 0) return fail('Chỉ nhận ảnh PNG, JPG hoặc WEBP.');
    if (file.size > READING_IMAGE_MAX) return fail('Ảnh vượt quá 5MB (ảnh này ' + (file.size / 1048576).toFixed(1) + 'MB).');
    var form = new FormData();
    form.append('file', file);
    readingImageRequest(tools, 'POST', '', form);
  }

  function onReadingImageClick(event) {
    var btn = event.target.closest('[data-rimg-action]');
    if (!btn) return;
    var tools = btn.closest('.lp-rimg-tools');
    var action = btn.getAttribute('data-rimg-action');
    if (action === 'pick') { tools.querySelector('.lp-rimg-tools__file').click(); return; }
    if (action === 'expand' || action === 'collapse') {
      var code = tools.getAttribute('data-code');
      if (action === 'expand') readingImageToolsOpen[code] = true; else delete readingImageToolsOpen[code];
      rerenderReadingKeepPlace(code);
      var again = document.querySelector('#panelReading .lp-rimg-tools[data-code="' +
        (window.CSS && CSS.escape ? CSS.escape(code) : code) + '"] [data-rimg-action="' + (action === 'expand' ? 'pick' : 'expand') + '"]');
      if (again) again.focus();
      return;
    }
    if (action === 'hide') {
      if (!window.confirm('Ẩn ảnh gốc của bài đọc này? Học viên sẽ không thấy ảnh gốc nữa (có thể hiện lại sau).')) return;
      readingImageRequest(tools, 'PUT', '/hidden');
      return;
    }
    if (action === 'remove') {
      if (!window.confirm('Gỡ ảnh đã chỉnh của bài đọc này? Bài đọc sẽ quay về ảnh gốc của nguồn (nếu có).')) return;
      readingImageRequest(tools, 'DELETE', '');
    }
  }

  function setupReadingImageTools() {
    contentEl.addEventListener('click', onReadingImageClick);
    contentEl.addEventListener('change', function (event) {
      var input = event.target.closest('.lp-rimg-tools__file');
      if (!input) return;
      uploadReadingImage(input.closest('.lp-rimg-tools'), input.files && input.files[0]);
      input.value = '';
    });
    contentEl.addEventListener('dragover', function (event) {
      var tools = event.target.closest('.lp-rimg-tools');
      if (!tools || tools.classList.contains('is-busy')) return;
      event.preventDefault();
      tools.classList.add('is-drag');
    });
    contentEl.addEventListener('dragleave', function (event) {
      var tools = event.target.closest('.lp-rimg-tools');
      if (tools && !tools.contains(event.relatedTarget)) tools.classList.remove('is-drag');
    });
    contentEl.addEventListener('drop', function (event) {
      var tools = event.target.closest('.lp-rimg-tools');
      if (!tools) return;
      event.preventDefault();
      tools.classList.remove('is-drag');
      if (tools.classList.contains('is-busy')) return;
      uploadReadingImage(tools, event.dataTransfer && event.dataTransfer.files && event.dataTransfer.files[0]);
    });
  }

  /**
   * [JLAS2-17] Ảnh trang sách gốc ở đầu tab Từ vựng / Ngữ pháp / Kanji: lưới ảnh, bấm để phóng to (cùng lớp phóng to
   * với ảnh câu hỏi). Rỗng khi bài không có ảnh trang cho tab đó.
   */
  function pageImagesHtml(tab) {
    var list = pageImages[tab] || [];
    if (!list.length) return '';
    var label = 'Trang ' + PAGE_IMAGE_LABELS[tab] + ' trong sách gốc';
    return '<figure class="lp-page-images" role="group" aria-label="' + escapeHtml(label) + '">' +
      '<figcaption class="lp-page-images__caption"><i class="fa-regular fa-image" aria-hidden="true"></i> ' +
        escapeHtml(label) + (list.length > 1 ? ' (' + list.length + ' trang)' : '') + ' · bấm ảnh để phóng to</figcaption>' +
      '<div class="lp-page-images__grid">' + list.map(function (u, i) {
        var src = escapeHtml(mediaUrl(u));
        var alt = escapeHtml(label + (list.length > 1 ? ' – trang ' + (i + 1) : ''));
        return '<button type="button" class="practice-q__zoom lp-page-images__item" data-zoom-src="' + src + '" data-zoom-alt="' + alt + '"' +
          ' aria-label="Phóng to ' + alt + '"><img class="lp-page-images__img" loading="lazy" src="' + src + '" alt="' + alt + '"></button>';
      }).join('') + '</div></figure>';
  }

  /** Tải ảnh trang sau khi bài học đã hiện (kèm token: người biên soạn xem được bài Nháp); lỗi / chưa có bảng → bỏ qua. */
  function loadPageImages() {
    if (!NEW_UI || !lessonId) return;
    var headers = window.JlasAuth && typeof JlasAuth.authHeaders === 'function' ? JlasAuth.authHeaders() : {};
    fetch('/api/dictionary/lessons/' + encodeURIComponent(lessonId) + '/page-images', { headers: headers })
      .then(function (res) { return res.ok ? res.json() : null; })
      .then(function (json) {
        var data = json && json.data;
        if (!data || !data.available || !(data.items || []).length) return;
        pageImages = { VOCAB: [], GRAMMAR: [], KANJI: [] };
        data.items.forEach(function (x) { if (pageImages[x.tab] && x.imageUrl) pageImages[x.tab].push(x.imageUrl); });
        if (pageImages.VOCAB.length) renderVocab();
        if (pageImages.GRAMMAR.length) renderGrammar();
        if (pageImages.KANJI.length) renderKanji();
      })
      .catch(function () { /* không có ảnh trang */ });
  }

  /** Tải ảnh đã chỉnh sau khi bài học đã hiện; lỗi (kể cả DB chưa có bảng) thì giữ nguyên ảnh seed. */
  function loadReadingImages() {
    if (!NEW_UI || !lessonId || !document.getElementById('panelReading')) return;
    fetch('/api/dictionary/lessons/' + encodeURIComponent(lessonId) + '/reading-images')
      .then(function (res) { return res.ok ? res.json() : null; })
      .then(function (json) {
        var data = json && json.data;
        if (!data || !data.available) return;
        readingImages.available = true;
        readingImages.byCode = {};
        (data.items || []).forEach(function (x) { readingImages.byCode[x.stableCode] = x; });
        if ((data.items || []).length || canEditReadingImages) renderReading();
      })
      .catch(function () { /* giữ ảnh seed */ });
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
    if (!panel) return;   // path-lesson.html (lo trinh) khong co tab Bai doc
    var labelEl = document.getElementById('labelReading');
    if (labelEl) labelEl.textContent = model.label;
    if (model.blocks.length === 0 && !model.cta) { panel.innerHTML = ''; return; }
    tailParts = { practice: [], other: [] };
    shownSolutions = {};
    shownCommonImages = {};
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
      (NEW_UI ? tailPartsHtml() : '') +
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
    if (NEW_UI) { renderKanjiNew(); return; }
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
    // Ví dụ của chính bài (lessonExamples) trước; bài không có thì dùng ví dụ chung của từ điển (exampleJa).
    var lessonExamples = (v.lessonExamples || []).filter(function (e) { return e && e.textJa; });
    var example = lessonExamples.length
      ? lessonExamples.map(function (e) {
          return '<p class="vocab-item__example"><span class="ja" lang="ja">' + escapeHtml(e.textJa) + '</span>' +
            escapeHtml(e.textVi || '') + '</p>';
        }).join('')
      : (v.exampleJa
        ? '<p class="vocab-item__example"><span class="ja">' + escapeHtml(v.exampleJa) + '</span>' + escapeHtml(v.exampleVi || '') + '</p>'
        : '');
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
    if (NEW_UI) { renderVocabNew(); return; }
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
    if (NEW_UI) { renderGrammarNew(); return; }
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
    if (NEW_UI) { renderDialogueNew(); return; }
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

  /* ======================================================================
   * GIAO DIỆN MỚI (main.lesson-page) — component JS thuần: hero, tab trượt, lưới từ vựng / kanji có phân trang,
   * ngữ pháp 2 cột, hội thoại dạng chat. Chỉ đổi cách vẽ; dữ liệu, audio, câu hỏi luyện tập giữ nguyên logic cũ.
   * Style: css/pages/lesson-page.css (mọi selector nằm trong .lesson-page).
   * ====================================================================== */
  var VOCAB_PER_PAGE = 12;
  var KANJI_PER_PAGE = 24;
  var ui = { vocabPage: 1, kanjiPage: 1, kanjiIndex: 0, grammarIndex: 0 };
  var FLASHCARD_URL = '/flashcard/library.html';   // module flashcard (thư viện bộ thẻ); chưa nhận lessonId
  var PARTICLES = ['は', 'も', 'の', 'か', 'が', 'を', 'に', 'で', 'へ', 'と', 'や', 'から', 'まで', 'より', 'ね', 'よ',
    'には', 'では', 'とは', 'でも', 'にも', 'へは'];
  var COPULAS = ['です', 'じゃありません', 'ではありません', 'ます', 'ません', 'でした', 'ました', 'ませんでした',
    'でしょう', 'ましょう', 'だ', 'である', 'じゃない', 'ではない', 'でしたか', 'ですか', 'ますか'];
  var SLOT_TOKEN = /^(N|V|A|Na|Adj|S|Vる|Vて|Vた|Vない|Vます|イA|ナA)([0-9０-９]+|[^\x00-\x7F]|$)/;

  function reducedMotion() {
    return !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
  }

  function clamp(n, lo, hi) { return Math.max(lo, Math.min(hi, n)); }

  /** Mô tả bài: bỏ dòng "Mã nguồn: ..." (mã nội bộ của dữ liệu nhập) và gộp dòng. */
  function cleanDescription(text) {
    return String(text || '').split('\n')
      .map(function (l) { return l.trim(); })
      .filter(function (l) { return l && !/^Mã nguồn:/i.test(l); })
      .join(' ');
  }

  /** Chữ Hán trang trí ở hero: kanji đầu tiên của bài, không có thì lấy từ tiêu đề / từ vựng. */
  function decoKanji(data) {
    var sources = [((data.kanjis || [])[0] || {}).character, data.title, ((data.vocabularies || [])[0] || {}).expression];
    for (var i = 0; i < sources.length; i++) {
      var m = String(sources[i] || '').match(/[一-鿿々]/);
      if (m) return m[0];
    }
    return '学';
  }

  function renderHeroNew(data) {
    document.getElementById('breadcrumbLesson').textContent = data.title || '';
    if (data.textbookTitle || data.unitTitle) {
      document.getElementById('breadcrumbTextbook').textContent = data.textbookTitle || data.unitTitle;
    }
    document.getElementById('lessonUnit').innerHTML = [data.textbookTitle, data.unitTitle].filter(Boolean)
      .map(escapeHtml).join('<span class="lp-dot" aria-hidden="true">·</span>');
    document.getElementById('lessonTitle').textContent = data.title || '';
    var desc = cleanDescription(data.description);
    var descEl = document.getElementById('lessonDesc');
    if (descEl) { descEl.textContent = desc; descEl.hidden = !desc; }
    var deco = document.getElementById('lessonKanjiDeco');
    if (deco) deco.textContent = decoKanji(data);
    document.title = (data.title || 'Bài học') + ' - JLAS';
    headerEl.hidden = false;
  }

  function heroChip(icon, text) {
    return '<span class="lp-hero__chip"><i class="' + icon + '" aria-hidden="true"></i> ' + escapeHtml(text) + '</span>';
  }

  /** Meta hero: trạng thái thật (nút đổi trạng thái cho CONTENT_CREATOR), thời lượng, loại bài, tổng số mục = tổng các tab. */
  function renderHeroMeta(data, counts) {
    var total = Object.keys(counts).reduce(function (n, k) { return n + (counts[k] || 0); }, 0);
    var published = data.status === 'PUBLISHED';
    var meta = [isContentCreator ? statusToggle('lesson', data.id, data.status)
      : '<span class="lp-status ' + (published ? 'lp-status--published' : 'lp-status--draft') + '">' +
        (published ? 'Đã công bố' : 'Bản nháp') + '</span>'];
    if (data.estimatedMinutes) meta.push(heroChip('fa-regular fa-clock', data.estimatedMinutes + ' phút'));
    if (data.skill) meta.push(heroChip('fa-solid fa-book-open', SKILL_LABELS[data.skill] || data.skill));
    if (data.lessonType === 'REVIEW') meta.push(heroChip('fa-solid fa-rotate', 'Ôn tập'));
    meta.push(heroChip('fa-solid fa-layer-group', total + ' mục'));
    document.getElementById('lessonMeta').innerHTML = meta.join('');
  }

  /* ---------- Tab trượt + xuất hiện lần lượt ---------- */

  function activePanel() {
    var panels = document.querySelectorAll('.lesson-panel');
    for (var i = 0; i < panels.length; i++) if (!panels[i].hidden) return panels[i];
    return null;
  }

  /** Tab: aria-controls + roving tabindex (chỉ tab đang chọn nhận Tab), ←/→/Home/End đổi tab. */
  function syncTabA11y() {
    Array.prototype.forEach.call(document.querySelectorAll('.lesson-tab'), function (t) {
      var panel = document.querySelector('.lesson-panel[data-panel="' + t.getAttribute('data-tab') + '"]');
      if (panel) {
        if (!t.id) t.id = 'lessonTab-' + t.getAttribute('data-tab');
        t.setAttribute('aria-controls', panel.id);
        panel.setAttribute('aria-labelledby', t.id);
        panel.setAttribute('tabindex', '0');
      }
      t.setAttribute('tabindex', t.classList.contains('is-active') ? '0' : '-1');
    });
  }

  function onTabKeydown(event) {
    var tab = event.target.closest('.lesson-tab');
    if (!tab) return;
    var tabs = Array.prototype.filter.call(document.querySelectorAll('.lesson-tab'), function (t) { return !t.hidden; });
    var i = tabs.indexOf(tab);
    var next = null;
    if (event.key === 'ArrowRight') next = tabs[(i + 1) % tabs.length];
    else if (event.key === 'ArrowLeft') next = tabs[(i - 1 + tabs.length) % tabs.length];
    else if (event.key === 'Home') next = tabs[0];
    else if (event.key === 'End') next = tabs[tabs.length - 1];
    if (!next) return;
    event.preventDefault();
    next.click();
    next.focus();
  }

  function moveTabSlider() {
    var slider = document.querySelector('.lp-tabs__slider');
    if (!slider) return;
    var tab = document.querySelector('.lesson-tab.is-active:not([hidden])');
    if (!tab || !tab.offsetWidth) { slider.style.opacity = '0'; return; }
    slider.style.opacity = '1';
    slider.style.width = tab.offsetWidth + 'px';
    slider.style.height = tab.offsetHeight + 'px';
    slider.style.transform = 'translate(' + tab.offsetLeft + 'px,' + tab.offsetTop + 'px)';
    var bar = tab.parentElement;
    if (bar && bar.scrollWidth > bar.clientWidth) {
      var left = tab.offsetLeft - (bar.clientWidth - tab.offsetWidth) / 2;
      bar.scrollTo({ left: Math.max(0, left), behavior: reducedMotion() ? 'auto' : 'smooth' });
    }
  }

  /** Phần tử .lp-anim / khối bài đọc trong panel hiện lần lượt (trễ ~50ms mỗi phần tử, tối đa 12). */
  function staggerPanel(panel) {
    if (!panel) return;
    var items = panel.querySelectorAll('.lp-anim, .reading-block, .kana-tile');
    Array.prototype.forEach.call(items, function (el, i) {
      el.style.setProperty('--i', Math.min(i, 12));
      el.classList.remove('lp-in');
      void el.offsetWidth;   // khởi động lại animation
      el.classList.add('lp-in');
    });
  }

  function scrollToHead(id) {
    var head = document.getElementById(id);
    if (head) head.scrollIntoView({ behavior: reducedMotion() ? 'auto' : 'smooth', block: 'start' });
  }

  /* ---------- Component dùng chung ---------- */

  function sectionHead(title, id, sub, actionHtml) {
    return '<div class="lp-section-head" id="' + id + '">' +
      '<div><h2 class="lp-section-title">' + escapeHtml(title) + '</h2>' +
      (sub ? '<p class="lp-section-sub">' + escapeHtml(sub) + '</p>' : '') + '</div>' +
      (actionHtml ? '<div class="lp-section-actions">' + actionHtml + '</div>' : '') +
      '</div>';
  }

  function hideMeaningButton() {
    return '<button type="button" class="lp-btn lp-btn--ghost" data-hide-meaning aria-pressed="false">' +
      '<i class="fa-regular fa-eye-slash" aria-hidden="true"></i><span>Ẩn nghĩa để tự kiểm tra</span></button>';
  }

  /** Số trang hiển thị: ≤ 7 trang hiện hết; nhiều hơn rút gọn kiểu 1 … 4 5 6 … 20. */
  function pageNumbers(page, total) {
    var out = [];
    if (total <= 7) { for (var i = 1; i <= total; i++) out.push(i); return out; }
    var start = Math.max(2, page - 1), end = Math.min(total - 1, page + 1);
    if (page <= 3) { start = 2; end = 4; }
    if (page >= total - 2) { start = total - 3; end = total - 1; }
    out.push(1);
    if (start > 2) out.push('…');
    for (var j = start; j <= end; j++) out.push(j);
    if (end < total - 1) out.push('…');
    out.push(total);
    return out;
  }

  /** Phân trang: ‹ số › + "Hiển thị từ a–b trong N từ"; kind = vocab | kanji. */
  function renderPagination(kind, page, total, count, perPage, unit) {
    if (total <= 1) return '';
    var from = (page - 1) * perPage + 1, to = Math.min(count, page * perPage);
    function btn(target, label, aria, disabled, current) {
      return '<button type="button" class="lp-pager__btn' + (current ? ' is-current' : '') + '" data-pager="' + kind + '"' +
        ' data-page="' + target + '" aria-label="' + escapeHtml(aria) + '"' + (current ? ' aria-current="page"' : '') +
        (disabled ? ' disabled' : '') + '>' + label + '</button>';
    }
    var nums = pageNumbers(page, total).map(function (n) {
      return n === '…' ? '<span class="lp-pager__gap" aria-hidden="true">…</span>' : btn(n, n, 'Trang ' + n, false, n === page);
    }).join('');
    return '<nav class="lp-pager" aria-label="Phân trang">' +
      '<div class="lp-pager__buttons">' +
        btn(page - 1, '<i class="fa-solid fa-chevron-left" aria-hidden="true"></i>', 'Trang trước', page <= 1, false) +
        nums +
        btn(page + 1, '<i class="fa-solid fa-chevron-right" aria-hidden="true"></i>', 'Trang sau', page >= total, false) +
      '</div>' +
      '<p class="lp-pager__info">Hiển thị ' + unit + ' ' + from + '–' + to + ' trong ' + count + ' ' + unit + '</p>' +
      '</nav>';
  }

  /* ---------- Tab Từ vựng ---------- */

  function vocabCard(v) {
    var lessonExamples = (v.lessonExamples || []).filter(function (e) { return e && e.textJa; })
      .map(function (e) { return { ja: e.textJa, vi: e.textVi }; });
    var examples = lessonExamples.length ? lessonExamples
      : (v.exampleJa ? [{ ja: v.exampleJa, vi: v.exampleVi }] : []);
    var showKana = v.reading && v.reading !== v.expression;
    return '<article class="lp-vcard lp-anim">' +
      '<div class="lp-vcard__head">' +
        '<div class="lp-vcard__word">' +
          '<span class="lp-vcard__expr" lang="ja">' + escapeHtml(v.expression) + '</span>' +
          (showKana ? '<span class="lp-vcard__kana" lang="ja">' + escapeHtml(v.reading) + '</span>' : '') +
          (v.readingRomaji ? '<span class="lp-vcard__romaji">' + escapeHtml(v.readingRomaji) + '</span>' : '') +
        '</div>' +
        speakButton(v.audios, v.reading || v.expression, 'Nghe ' + (v.expression || '')) +
      '</div>' +
      (v.meaning ? '<p class="lp-vcard__meaning">' + escapeHtml(v.meaning) + '</p>' : '') +
      (v.wordType ? '<span class="lp-tag">' + escapeHtml(v.wordType) + '</span>' : '') +
      (examples.length
        ? '<div class="lp-vcard__example">' + examples.slice(0, 2).map(function (e) {
            return '<p class="lp-ex__ja" lang="ja">' + escapeHtml(e.ja) + '</p>' +
              (e.vi ? '<p class="lp-ex__vi">' + escapeHtml(e.vi) + '</p>' : '');
          }).join('') + '</div>'
        : '') +
      '</article>';
  }

  function renderVocabNew(fromPager) {
    var list = lesson.vocabularies || [];
    var panel = document.getElementById('panelVocab');
    if (!list.length) { panel.innerHTML = '<p class="lesson-panel__empty">Bài này chưa có từ vựng.</p>'; return; }
    var total = Math.ceil(list.length / VOCAB_PER_PAGE);
    ui.vocabPage = clamp(ui.vocabPage, 1, total);
    var start = (ui.vocabPage - 1) * VOCAB_PER_PAGE;
    var action = isLearningPath ? '' : '<a class="lp-btn lp-btn--ghost" href="' + FLASHCARD_URL + '">' +
      '<i class="fa-solid fa-layer-group" aria-hidden="true"></i><span>Ôn tập bằng Flashcard</span></a>';
    panel.innerHTML = sectionHead('Từ vựng', 'lpVocabHead', 'Trang ' + ui.vocabPage + ' / ' + total + ' · ' + list.length + ' từ', action) +
      pageImagesHtml('VOCAB') +
      '<div class="lp-vgrid">' + list.slice(start, start + VOCAB_PER_PAGE).map(vocabCard).join('') + '</div>' +
      renderPagination('vocab', ui.vocabPage, total, list.length, VOCAB_PER_PAGE, 'từ');
    applySpeakButtonVisibility();
    if (fromPager) { scrollToHead('lpVocabHead'); staggerPanel(panel); }
  }

  /* ---------- Tab Kanji ---------- */

  /** "NGHIÊN, NGHIỄN, NGHIẾN" → ['NGHIÊN', 'NGHIỄN', 'NGHIẾN'] (dữ liệu ngăn bằng dấu phẩy / chấm phẩy / gạch chéo). */
  function hanVietList(hanViet) {
    return String(hanViet || '').split(/\s*[,，、;；\/]\s*/).map(function (x) { return x.trim(); }).filter(Boolean);
  }

  /** Ô lưới cao cố định: chỉ âm Hán Việt ĐẦU TIÊN (+N nếu còn) và nghĩa 1 dòng; đầy đủ ở cột chi tiết. */
  function kanjiTile(k, index) {
    var selected = index === ui.kanjiIndex;
    var hv = hanVietList(k.hanViet);
    return '<button type="button" class="lp-ktile lp-anim' + (selected ? ' is-selected' : '') + '" data-kanji-index="' + index + '"' +
      ' aria-pressed="' + selected + '" aria-label="Kanji ' + escapeHtml(k.character) + (hv.length ? ' – ' + escapeHtml(hv.join(', ')) : '') + '">' +
      '<span class="lp-ktile__char" lang="ja">' + escapeHtml(k.character) + '</span>' +
      (hv.length
        ? '<span class="lp-ktile__hvrow"><span class="lp-ktile__hv">' + escapeHtml(hv[0]) + '</span>' +
          (hv.length > 1 ? '<span class="lp-ktile__more" aria-hidden="true">+' + (hv.length - 1) + '</span>' : '') + '</span>'
        : '') +
      (k.meaning ? '<span class="lp-ktile__meaning">' + escapeHtml(k.meaning) + '</span>' : '') +
      '</button>';
  }

  function renderKanjiNew(fromPager) {
    var list = lesson.kanjis || [];
    var host = document.getElementById('kanjiList');
    if (!list.length) { host.innerHTML = '<p class="lesson-panel__empty">Bài này chưa có Kanji.</p>'; return; }
    var total = Math.ceil(list.length / KANJI_PER_PAGE);
    ui.kanjiPage = clamp(ui.kanjiPage, 1, total);
    var start = (ui.kanjiPage - 1) * KANJI_PER_PAGE;
    if (ui.kanjiIndex < start || ui.kanjiIndex >= Math.min(list.length, start + KANJI_PER_PAGE)) ui.kanjiIndex = start;
    host.innerHTML = sectionHead('Kanji', 'lpKanjiHead', 'Trang ' + ui.kanjiPage + ' / ' + total + ' · ' + list.length + ' chữ') +
      pageImagesHtml('KANJI') +
      '<div class="lp-kanji-layout">' +
        '<div class="lp-kanji-main"><div class="lp-kgrid">' +
          list.slice(start, start + KANJI_PER_PAGE).map(function (k, i) { return kanjiTile(k, start + i); }).join('') +
        '</div>' + renderPagination('kanji', ui.kanjiPage, total, list.length, KANJI_PER_PAGE, 'chữ') + '</div>' +
        '<aside class="lp-kdetail" id="lpKanjiDetail" aria-live="polite"></aside>' +
      '</div>';
    renderKanjiDetail();
    if (fromPager) { scrollToHead('lpKanjiHead'); staggerPanel(document.getElementById('panelKanji')); }
  }

  function kanjiReadBox(label, items) {
    return '<div class="lp-kread"><span class="lp-kread__label">' + label + '</span><div class="lp-kread__list">' +
      (items.length
        ? items.map(function (r) { return '<span class="lp-kread__item" lang="ja">' + escapeHtml(r.readingText) + '</span>'; }).join('')
        : '<span class="lp-muted">—</span>') +
      '</div></div>';
  }

  function renderKanjiDetail() {
    var k = (lesson.kanjis || [])[ui.kanjiIndex];
    var host = document.getElementById('lpKanjiDetail');
    if (!k || !host) return;
    var readings = k.readings || [];
    var on = readings.filter(function (r) { return r.readingType === 'ON'; });
    var kun = readings.filter(function (r) { return r.readingType === 'KUN'; });
    var words = (k.words || []).slice(0, 6);
    // Đọc On rồi Kun; bỏ dấu tách okurigana (た.べる) / tiền tố (-か) để TTS đọc liền.
    var speakText = on.concat(kun).map(function (r) { return String(r.readingText || '').replace(/[.\-]/g, ''); })
      .filter(Boolean).join('、') || k.character;
    host.innerHTML =
      '<div class="lp-kdetail__char" lang="ja">' + escapeHtml(k.character) + '</div>' +
      (hanVietList(k.hanViet).length
        ? '<div class="lp-hvchips">' + hanVietList(k.hanViet).map(function (h) {
            return '<span class="lp-hvchip">' + escapeHtml(h) + '</span>';
          }).join('') + '</div>'
        : '') +
      (k.meaning ? '<p class="lp-kdetail__hv">' + escapeHtml(k.meaning) + '</p>' : '') +
      (k.strokeCount != null ? '<p class="lp-muted lp-kdetail__strokes">' + k.strokeCount + ' nét</p>' : '') +
      kanjiReadBox('Âm On', on) + kanjiReadBox('Âm Kun', kun) +
      (words.length
        ? '<div class="lp-kwords"><span class="lp-kread__label">Từ ví dụ</span><ul>' + words.map(function (w) {
            return '<li><span class="lp-kwords__ja" lang="ja">' + escapeHtml(w.expression) + '</span>' +
              (w.reading ? '<span class="lp-kwords__kana" lang="ja">' + escapeHtml(w.reading) + '</span>' : '') +
              (w.meaning ? '<span class="lp-kwords__vi">' + escapeHtml(w.meaning) + '</span>' : '') + '</li>';
          }).join('') + '</ul></div>'
        : '') +
      '<button type="button" class="speak-btn lp-keep lp-listen" data-src="" data-text="' + escapeHtml(speakText) + '"' +
        ' aria-label="Nghe cách đọc ' + escapeHtml(k.character) + '">' +
        '<i class="fa-solid fa-volume-high speak-btn__icon" aria-hidden="true"></i>' +
        '<i class="fa-solid fa-circle-notch fa-spin speak-btn__spinner" aria-hidden="true"></i>' +
        '<span>Nghe cách đọc</span></button>';
    Array.prototype.forEach.call(document.querySelectorAll('.lp-ktile'), function (t) {
      var sel = Number(t.getAttribute('data-kanji-index')) === ui.kanjiIndex;
      t.classList.toggle('is-selected', sel);
      t.setAttribute('aria-pressed', sel ? 'true' : 'false');
    });
    applySpeakButtonVisibility();
  }

  /* ---------- Tab Ngữ pháp ---------- */

  /** Badge tab Ngữ pháp = số mẫu câu (bài chỉ có câu ví dụ không gắn mẫu vẫn đếm 1 khối để tab không bị ẩn). */
  function grammarCount() {
    var n = (lesson.grammarPoints || []).length;
    return n || grammarBlocks().length;
  }

  function chipKind(token) {
    var t = token.replace(/^[～~]/, '');
    if (PARTICLES.indexOf(t) !== -1) return 'particle';
    if (COPULAS.indexOf(t) !== -1) return 'copula';
    if (SLOT_TOKEN.test(t)) return 'slot';
    return 'plain';
  }

  /** Công thức mẫu câu → chip: tách theo khoảng trắng, phân loại bằng danh sách cố định ở trên. */
  function patternChips(pattern) {
    var raw = String(pattern || '').trim();
    var tokens = raw.split(/[\s\u3000]+/).filter(Boolean);
    // [JLAS2-17] G8: tên mẫu là cụm tiếng Việt ("Trợ từ は", "N3 dễ nhầm", "Thể khả năng") hiện nguyên một khối, không tách
    // từng chữ thành chip. Mẫu có cấu trúc (V / N / + / ～ / ngoặc) giữ cách tách cũ.
    var head = /^[\u3040-\u309f]{1,2}$/.test(tokens[tokens.length - 1] || '') ? tokens.slice(0, -1) : tokens;
    if (tokens.length > 1 && /^([A-Za-zÀ-ỹĐđ]*[a-zà-ỹđ]|[A-Z][0-9])/.test(tokens[0]) && !/[+＋\/／～〜()（）]/.test(raw) &&
        !head.some(function (t) { return JA_CHAR.test(t); })) tokens = [raw];
    if (!tokens.length) return '';
    return '<div class="lp-chips" lang="ja">' + tokens.map(function (t, i) {
      return '<span class="lp-chip lp-chip--' + chipKind(t) + '" style="--i:' + Math.min(i, 12) + '">' + escapeHtml(t) + '</span>';
    }).join('') + '</div>';
  }

  /*
   * Giải thích dài: dòng "[n. Tiêu đề] nội dung" mở một mục. Mục chính hiện sẵn với tiêu đề nhỏ; mục phụ (ghi chú,
   * biến thể, lỗi thường gặp, so sánh, mở rộng…) thu gọn, bấm để mở. Không có dạng mục thì trả null (giữ cách cũ).
   */
  var GRAMMAR_SECTION = /^\[\s*(\d+)\s*[.．]\s*([^\]]{1,60})\]\s*(.*)$/;
  var GRAMMAR_SECONDARY = /ghi\s*chú|biến\s*thể|lỗi|bẫy|mở\s*rộng|so\s*sánh|lưu\s*ý|tham\s*khảo|cụm\s*cố\s*định/i;

  function grammarSectionsHtml(text) {
    var lines = String(text || '').split('\n');
    if (!lines.some(function (l) { return GRAMMAR_SECTION.test(l.trim()); })) return null;
    var lead = [];
    var secs = [];
    lines.forEach(function (l) {
      var m = l.trim().match(GRAMMAR_SECTION);
      if (m) { secs.push({ num: m[1], title: m[2].trim(), body: m[3] ? [m[3]] : [] }); return; }
      if (secs.length) secs[secs.length - 1].body.push(l); else lead.push(l);
    });
    function bodyHtml(body) {
      return body.filter(function (l) { return l.trim(); }).map(function (l) {
        return '<p class="lp-gsec__line"' + (JA_CHAR.test(l) && !VI_CHAR.test(l) ? ' lang="ja"' : '') + '>' + escapeHtml(l.trim()) + '</p>';
      }).join('');
    }
    return (lead.some(function (l) { return l.trim(); }) ? '<div class="lp-gsec__lead">' + bodyHtml(lead) + '</div>' : '') +
      secs.map(function (x) {
        var head = '<span class="lp-gsec__num">' + escapeHtml(x.num) + '</span>' + escapeHtml(x.title);
        return GRAMMAR_SECONDARY.test(x.title)
          ? '<details class="lp-gsec"><summary class="lp-gsec__title">' + head + '</summary>' + bodyHtml(x.body) + '</details>'
          : '<section class="lp-gsec lp-gsec--open"><h4 class="lp-gsec__title">' + head + '</h4>' + bodyHtml(x.body) + '</section>';
      }).join('');
  }

  /** Nghĩa: dòng đầu là nghĩa ngắn (đậm); các dòng "Nhãn: giải thích" thành danh sách có nhãn đậm. */
  function grammarMeaningHtml(text) {
    var lines = String(text || '').split('\n').map(function (l) { return l.trim(); }).filter(Boolean);
    if (lines.length < 2) return '<p class="lp-gmeaning">' + escapeHtml(text || '') + '</p>';
    return '<div class="lp-gmeaning lp-gmeaning--rich"><p class="lp-gmeaning__short">' + escapeHtml(lines[0]) + '</p><ul class="lp-gmeaning__list">' +
      lines.slice(1).map(function (l) {
        var m = l.match(/^([^:：]{2,40})[:：]\s*(.+)$/);
        return '<li>' + (m ? '<strong>' + escapeHtml(m[1]) + ':</strong> ' + escapeHtml(m[2]) : escapeHtml(l)) + '</li>';
      }).join('') + '</ul></div>';
  }

  function grammarDetail(block, index) {
    var g = block.point;
    var examples = block.examples;
    var exHtml = examples.length
      ? '<ol class="lp-examples">' + examples.map(function (s, i) {
          return '<li class="lp-example lp-anim" tabindex="0">' +
            '<span class="lp-example__num" aria-hidden="true">' + (i + 1) + '</span>' +
            '<div class="lp-example__body"><p class="lp-example__ja" lang="ja">' + escapeHtml(s.textJa || '') + '</p>' +
            (s.textVi ? '<p class="lp-example__vi lp-vi">' + escapeHtml(s.textVi) + '</p>' : '') + '</div>' +
            speakButton(s.audios, s.textJa, 'Nghe câu ví dụ ' + (i + 1)) +
            '</li>';
        }).join('') + '</ol>'
      : (g && g.example ? '<p class="lp-example__single" lang="ja">' + escapeHtml(g.example) + '</p>' : '');
    if (!g) {
      return '<p class="lp-glabel">' + escapeHtml(OTHER_EXAMPLES_TITLE.toUpperCase()) + '</p>' +
        '<p class="lp-gmeaning">Câu ví dụ của bài không thuộc riêng mẫu ngữ pháp nào.</p>' + exHtml;
    }
    return '<p class="lp-glabel">MẪU CÂU ' + (index + 1) + '</p>' +
      patternChips(g.pattern) +
      (g.meaning ? (grammarSectionsHtml(g.meaning) || grammarMeaningHtml(g.meaning)) : '') +
      (g.note
        ? (grammarSectionsHtml(g.note)
          ? '<div class="lp-gsecs"><h3 class="lp-gsub">Giải thích chi tiết</h3>' + grammarSectionsHtml(g.note) + '</div>'
          : '<div class="lp-note"><i class="fa-regular fa-lightbulb" aria-hidden="true"></i>' +
            '<div><strong>Ghi chú</strong><p>' + escapeHtml(g.note) + '</p></div></div>')
        : '') +
      (exHtml ? '<h3 class="lp-gsub">Ví dụ</h3>' + exHtml : '');
  }

  function renderGrammarNew() {
    var blocks = grammarBlocks();
    var panel = document.getElementById('panelGrammar');
    if (!blocks.length) { panel.innerHTML = '<p class="lesson-panel__empty">Bài này chưa có mẫu ngữ pháp.</p>'; return; }
    ui.grammarIndex = clamp(ui.grammarIndex, 0, blocks.length - 1);
    var points = (lesson.grammarPoints || []).length;
    panel.innerHTML = sectionHead('Ngữ pháp', 'lpGrammarHead', points ? points + ' mẫu câu' : '', hideMeaningButton()) +
      pageImagesHtml('GRAMMAR') +
      '<div class="lp-grammar">' +
        '<nav class="lp-glist" aria-label="Danh sách mẫu câu">' + blocks.map(function (b, i) {
          var selected = i === ui.grammarIndex;
          return '<button type="button" class="lp-gitem lp-anim' + (selected ? ' is-selected' : '') + '" data-grammar-index="' + i + '"' +
            ' aria-pressed="' + selected + '">' +
            '<span class="lp-gitem__num">' + (b.point ? i + 1 : '+') + '</span>' +
            '<span class="lp-gitem__text"' + (b.point ? ' lang="ja"' : '') + '>' +
              escapeHtml(b.point ? b.point.pattern : OTHER_EXAMPLES_TITLE) + '</span></button>';
        }).join('') + '</nav>' +
        '<article class="lp-gdetail" id="lpGrammarDetail" aria-live="polite">' + grammarDetail(blocks[ui.grammarIndex], ui.grammarIndex) + '</article>' +
      '</div>';
    applySpeakButtonVisibility();
  }

  function selectGrammar(index) {
    var blocks = grammarBlocks();
    if (!blocks[index]) return;
    ui.grammarIndex = index;
    var detail = document.getElementById('lpGrammarDetail');
    if (detail) detail.innerHTML = grammarDetail(blocks[index], index);
    Array.prototype.forEach.call(document.querySelectorAll('.lp-gitem'), function (b) {
      var sel = Number(b.getAttribute('data-grammar-index')) === index;
      b.classList.toggle('is-selected', sel);
      b.setAttribute('aria-pressed', sel ? 'true' : 'false');
    });
    applySpeakButtonVisibility();
    if (detail) staggerPanel(detail);
  }

  /* ---------- Tab Hội thoại ---------- */

  /** Đoạn hội thoại = một lesson_section (giữ thứ tự dữ liệu); badge tab đếm số đoạn, không đếm số câu. */
  function dialogueScenes() {
    var scenes = [];
    var bySection = {};
    (lesson.dialogueLines || []).forEach(function (line) {
      var key = line.sectionId == null ? 'none' : String(line.sectionId);
      if (!bySection[key]) { bySection[key] = { title: line.sectionTitle || '', lines: [] }; scenes.push(bySection[key]); }
      bySection[key].lines.push(line);
    });
    return scenes;
  }

  function chatBubble(line, roleOrder) {
    var right = line.bubbleSide ? line.bubbleSide === 'phai' : (roleOrder[line.roleId] || 0) % 2 === 1;
    var name = line.roleNameVi || line.roleCode || '';
    var initial = (name.trim().charAt(0) || '?').toUpperCase();
    var audio = audioForVoice(line.audios);
    var src = audio && audio.audioUrl ? AUDIO_BASE + audio.audioUrl : '';
    return '<div class="lp-msg lp-anim' + (right ? ' lp-msg--right' : '') + '">' +
      '<span class="lp-msg__avatar" aria-hidden="true">' + escapeHtml(initial) + '</span>' +
      '<div class="lp-msg__col">' +
        (name ? '<span class="lp-msg__name">' + escapeHtml(name) + '</span>' : '') +
        '<button type="button" class="speak-btn lp-keep lp-bubble" data-src="' + escapeHtml(src) + '"' +
          ' data-text="' + escapeHtml(line.text || '') + '"' +
          (line.roleId != null ? ' data-role-id="' + escapeHtml(line.roleId) + '"' : '') +
          ' aria-label="Nghe câu của ' + escapeHtml(name || 'nhân vật') + '">' +
          '<span class="lp-bubble__ja" lang="ja">' + escapeHtml(line.text || '') + '</span>' +
          (line.translatedText ? '<span class="lp-bubble__vi lp-vi">' + escapeHtml(line.translatedText) + '</span>' : '') +
          '<i class="fa-solid fa-volume-high lp-bubble__icon speak-btn__icon" aria-hidden="true"></i>' +
          '<i class="fa-solid fa-circle-notch fa-spin speak-btn__spinner" aria-hidden="true"></i>' +
        '</button>' +
      '</div></div>';
  }

  function renderDialogueNew() {
    var scenes = dialogueScenes();
    var panel = document.getElementById('panelDialogue');
    if (!scenes.length) { panel.innerHTML = '<p class="lesson-panel__empty">Bài này chưa có hội thoại.</p>'; return; }
    var roleOrder = {};
    (lesson.dialogueRoles || []).forEach(function (r, i) { roleOrder[r.id] = i; });
    panel.innerHTML = sectionHead('Hội thoại', 'lpDialogueHead', scenes.length + ' đoạn hội thoại · bấm vào câu để nghe', hideMeaningButton()) +
      scenes.map(function (scene, i) {
        return '<section class="lp-scene">' +
          '<h3 class="lp-scene__title">' + escapeHtml(scene.title || ('Hội thoại ' + (i + 1))) + '</h3>' +
          '<div class="lp-chat">' + scene.lines.map(function (l) { return chatBubble(l, roleOrder); }).join('') + '</div>' +
        '</section>';
      }).join('');
    applySpeakButtonVisibility();
  }

  /* ---------- Sự kiện của giao diện mới (delegation trên #lessonContent) ---------- */

  function onLessonPageClick(event) {
    var pager = event.target.closest('[data-pager]');
    if (pager) {
      if (pager.disabled) return;
      var page = Number(pager.getAttribute('data-page'));
      if (pager.getAttribute('data-pager') === 'vocab') { ui.vocabPage = page; renderVocabNew(true); }
      else { ui.kanjiPage = page; ui.kanjiIndex = (page - 1) * KANJI_PER_PAGE; renderKanjiNew(true); }
      return;
    }
    var tile = event.target.closest('[data-kanji-index]');
    if (tile) {
      ui.kanjiIndex = Number(tile.getAttribute('data-kanji-index'));
      renderKanjiDetail();
      // Màn hình hẹp: cột chi tiết nằm dưới lưới -> cuộn tới để thấy chữ vừa chọn.
      var detail = document.getElementById('lpKanjiDetail');
      if (detail && window.matchMedia && window.matchMedia('(max-width: 960px)').matches) {
        detail.scrollIntoView({ behavior: reducedMotion() ? 'auto' : 'smooth', block: 'start' });
      }
      return;
    }
    var gitem = event.target.closest('[data-grammar-index]');
    if (gitem) { selectGrammar(Number(gitem.getAttribute('data-grammar-index'))); return; }
    var hide = event.target.closest('[data-hide-meaning]');
    if (hide) {
      var panel = hide.closest('.lesson-panel');
      var on = panel.classList.toggle('lp-hide-vi');
      hide.setAttribute('aria-pressed', on ? 'true' : 'false');
      hide.querySelector('span').textContent = on ? 'Hiện nghĩa' : 'Ẩn nghĩa để tự kiểm tra';
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
      document.getElementById('kanjiList').innerHTML = '<p class="lesson-panel__empty">Bài học đang được cập nhật.</p>';
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
        if (NEW_UI) { syncTabA11y(); moveTabSlider(); staggerPanel(activePanel()); }
      });
    });
    if (NEW_UI) {
      window.addEventListener('resize', moveTabSlider);
      var bar = document.querySelector('.lesson-tabs');
      if (bar) bar.addEventListener('keydown', onTabKeydown);
    }
  }

  /** [JLAS2-17] Dòng nhỏ cuối trang ghi nguồn nội dung nhập từ ngoài (content_source); nội dung nhóm biên soạn: không hiện. */
  var SOURCE_LABELS = { VNJPCLUB: 'vnjpclub.com' };
  function renderSourceNote(data) {
    var old = document.getElementById('lessonSourceNote');
    if (old) old.remove();
    var label = data && SOURCE_LABELS[data.contentSource];
    if (!label) return;
    var note = document.createElement('p');
    note.id = 'lessonSourceNote';
    note.className = 'lp-source-note';
    note.textContent = 'Nguồn nội dung: ' + label;
    contentEl.parentNode.insertBefore(note, contentEl.nextSibling);
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
      grammar: NEW_UI ? grammarCount() : grammarBlocks().length,   // giao diện mới: số mẫu câu
      dialogue: NEW_UI ? dialogueScenes().length : (data.dialogueLines || []).length,   // giao diện mới: số đoạn hội thoại
      reading: readingModel().count
    };
    Object.keys(counts).forEach(function (key) {
      var id = 'count' + key.charAt(0).toUpperCase() + key.slice(1);
      var countEl = document.getElementById(id);   // path-lesson.html (lo trinh) khong co tab Bai doc
      if (countEl) countEl.textContent = counts[key];
    });
    // Vẽ panel TRƯỚC rồi mới ẩn/hiện tab: applyTabVisibility() ghi "Bài này chưa có nội dung." vào panel Kanji
    // khi mọi khối rỗng — vẽ sau thì renderKanji() ghi đè thành "chưa có Kanji", sai nghĩa với bài trống.
    renderPanels();
    applyTabVisibility(counts);
    contentEl.hidden = false;
    renderSourceNote(data);
    if (NEW_UI) { renderHeroMeta(data, counts); syncTabA11y(); moveTabSlider(); staggerPanel(activePanel()); }
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
      .then(loadReadingImages)
      .then(loadPageImages)
      .catch(function (err) { showError(err.message || 'Không tải được bài học.'); });
  }

  setupTabs();
  initTtsVoices();
  setupStatusToggles();
  setupKanaFlashcard();
  contentEl.addEventListener('click', onSpeakClick);
  contentEl.addEventListener('click', onPracticeClick);
  contentEl.addEventListener('click', onToggleTranslation);
  if (NEW_UI) contentEl.addEventListener('click', onLessonPageClick);
  if (NEW_UI) setupReadingImageTools();
  contentEl.addEventListener('click', onZoomClick);
  // Nút "Học bằng flashcard" được render động trong renderKana() → dùng delegation.
  contentEl.addEventListener('click', function (event) {
    if (event.target.closest('#kanaFlashcardBtn')) openKanaFlashcard();
  });
  load();
})();
