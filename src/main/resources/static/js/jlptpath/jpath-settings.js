/* [JLAS-W0108] jpath-settings.js — settings page of the JLPT path (/learning/jlpt/settings).
 *
 * window.JpathSettings (pure helpers, tested with node --test):
 *   RESTART_TEXT                   'BẮT ĐẦU LẠI'
 *   restartTextOk(text)            same rule as the server: outer whitespace trimmed, NFC, upper-cased
 *   stateFromView(view)            flat editable state from GET /settings (auto third day = '', no exam = '')
 *   pick(state, block)             the fields of one block: 'target' | 'schedule' | 'display' | 'sound'
 *                                  (display and sound save on every change, F61)
 *   diff(original, current)        only the changed fields; day lists compare as sets; a removed exam date
 *                                  becomes {clearExamDate:true}
 *   thirdDayOptions(skill, rest)   days left for the 3rd skill day
 *   settleThirdDay(state)          drops a chosen 3rd day that became a skill or rest day ('' = automatic)
 *   previewBody(view, state)       POST /estimate body for the target block (saved schedule, recommended sliders)
 *   paceTone(pace)                 'block' (blocked or late) | 'warn' | 'ok'
 *   historyLabel(entry)            one line of the slider history, e.g. '03/11 · Từ vựng 20→25 · tính 1 lượt'
 *   adjustmentsLine(view), lowerLevelText(level, openLevel)
 *   mount(root, deps)              fills the page skeleton; every text goes through textContent.
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathSettings = api;
  if (root && root.document && typeof root.document.addEventListener === 'function') {
    var start = function () {
      var el = root.document.querySelector('[data-jpath-settings]');
      if (el) api.mount(el, {});
    };
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', start);
    else start();
  }
})(typeof window !== 'undefined' ? window : null, function (root) {
  'use strict';

  var RESTART_TEXT = 'BẮT ĐẦU LẠI';
  var DAYS = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'];
  var JLPT_LEVELS = ['N5', 'N4', 'N3', 'N2', 'N1'];
  var DAY_NAMES = { MON: 'Thứ Hai', TUE: 'Thứ Ba', WED: 'Thứ Tư', THU: 'Thứ Năm', FRI: 'Thứ Sáu', SAT: 'Thứ Bảy',
    SUN: 'Chủ nhật' };
  // the server re-plans today only when nothing was learned on it yet and today becomes a study day; a rest or
  // skill day moved onto today waits for tomorrow (JpathTodayTargetsPort.replanUntouchedToday, F130)
  var MSG_DAYS_SAVED = 'Đã lưu. Ngày nghỉ và ngày kỹ năng mới áp dụng từ ngày mai. Nếu hôm nay bạn bỏ ngày nghỉ và chưa học gì, hôm nay thành ngày học ngay.';
  var BLOCKS = {
    target: ['targetLevel', 'examDate'],
    schedule: ['vocabPerDay', 'skillDays', 'restDays', 'thirdSkillDay'],
    display: ['studyMode', 'furiganaHiddenAll', 'romajiMode', 'fontSize'],
    sound: ['soundOn', 'autoplayAudio', 'quizTimerOn', 'grammarTimerOn', 'reminderWeb', 'reminderEmail', 'reminderTime']
  };
  // F61: these blocks save as soon as a field changes (no Lưu button); their fields lock while the PUT runs
  var INSTANT = { display: true, sound: true };
  var TABS = [['vocab', 'Từ vựng'], ['kanji', 'Kanji'], ['grammar', 'Ngữ pháp']];
  var KIND_LABELS = { INITIAL: 'Ban đầu', CONFIRM: 'Xác nhận', FIRST_DRAG: 'Lần kéo đầu', REBALANCE: 'Cân lại',
    TARGET_CHANGE: 'Đổi đích' };
  // Java String.strip(): Character.isWhitespace, which leaves out the no-break spaces U+00A0, U+2007, U+202F.
  var SPACE_CLASS = "[\t\n\u000B\f\r\u001C-\u001F \u1680\u2000-\u2006\u2008-\u200A\u2028\u2029\u205F\u3000]";
  var OUTER_SPACE = new RegExp("^" + SPACE_CLASS + "+|" + SPACE_CLASS + "+$", "g");
  var SETTINGS_URL = '/api/learning/jlpt/settings';
  var DEBOUNCE_MS = 120;
  var MSG_LOAD_FAILED = 'Không tải được cài đặt. Thử tải lại trang.';
  var MSG_FAILED = 'Không lưu được. Thử lại sau.';

  // ---------------------------------------------------------------------------------------------- helpers

  function upper(text) {
    try { return text.toLocaleUpperCase('vi'); } catch (e) { return text.toUpperCase(); }
  }

  function restartTextOk(text) {
    if (typeof text !== 'string') return false;
    return upper(text.replace(OUTER_SPACE, '').normalize('NFC')) === RESTART_TEXT.normalize('NFC');
  }

  function list(value) {
    return Array.isArray(value) ? value.slice() : [];
  }

  function stateFromView(view) {
    var v = view || {};
    var d = v.display || {};
    return {
      targetLevel: v.targetLevel || '',
      examDate: v.examDate || '',
      dailyMinutes: v.dailyMinutes,
      // owner 22/11 (F230): the learner picks words per day; Kanji, grammar and the minutes follow
      vocabPerDay: v.sliders && typeof v.sliders.vocab === 'number' ? v.sliders.vocab : null,
      skillDays: list(v.skillDays),
      restDays: list(v.restDays),
      thirdSkillDay: v.thirdSkillDayAuto || !v.thirdSkillDay ? '' : v.thirdSkillDay,
      reminderTime: typeof v.reminderTime === 'string' ? v.reminderTime.slice(0, 5) : '',
      studyMode: v.studyMode || 'COMBINED',
      furiganaHiddenAll: !!d.furiganaHiddenAll,
      romajiMode: d.romajiMode || 'AUTO',
      fontSize: d.fontSize || 'M',
      soundOn: !!d.soundOn,
      autoplayAudio: !!d.autoplayAudio,
      quizTimerOn: !!d.quizTimerOn,
      grammarTimerOn: !!d.grammarTimerOn,
      reminderWeb: !!v.reminderWeb,
      reminderEmail: !!v.reminderEmail
    };
  }

  function pick(state, block) {
    var out = {};
    (BLOCKS[block] || []).forEach(function (key) { out[key] = state[key]; });
    return out;
  }

  function sameValue(a, b) {
    if (Array.isArray(a) || Array.isArray(b)) {
      return list(a).sort().join(',') === list(b).sort().join(',');
    }
    return a === b;
  }

  function diff(original, current) {
    var out = {};
    Object.keys(current || {}).forEach(function (key) {
      var before = original ? original[key] : undefined;
      var after = current[key];
      if (sameValue(before, after)) return;
      if (key === 'examDate') {
        if (!after) { if (before) out.clearExamDate = true; } else out.examDate = after;
        return;
      }
      out[key] = Array.isArray(after) ? after.slice() : after;
    });
    return out;
  }

  function thirdDayOptions(skillDays, restDays) {
    var taken = list(skillDays).concat(list(restDays));
    return DAYS.filter(function (d) { return taken.indexOf(d) < 0; });
  }

  function copy(state) {
    var out = {};
    Object.keys(state).forEach(function (k) { out[k] = Array.isArray(state[k]) ? state[k].slice() : state[k]; });
    return out;
  }

  function settleThirdDay(state) {
    var next = copy(state);
    if (next.thirdSkillDay && thirdDayOptions(next.skillDays, next.restDays).indexOf(next.thirdSkillDay) < 0) {
      next.thirdSkillDay = '';
    }
    return next;
  }

  function previewBody(view, state) {
    return {
      openLevel: view.openLevel, targetLevel: state.targetLevel, examDate: state.examDate || null,
      vocabPerDay: state.vocabPerDay, skillDays: list(state.skillDays), restDays: list(state.restDays),
      sliders: null, kanaTab: false, mode: view.studyMode || 'COMBINED'
    };
  }

  var WORD_OPTIONS = [5, 10, 15, 20, 25, 30, 40, 50];

  /** Words-per-day choices inside the level's limits, with the current value kept (F230). */
  function wordOptions(bounds, current) {
    var b = bounds && bounds.VOCAB && typeof bounds.VOCAB.min === 'number' ? bounds.VOCAB : { min: 5, max: 50 };
    var out = WORD_OPTIONS.filter(function (w) { return w >= b.min && w <= b.max; });
    var c = Number(current);
    if (current != null && isFinite(c) && out.indexOf(c) < 0) out.push(c);
    return out.sort(function (x, y) { return x - y; });
  }

  /** '20 từ + 3 Kanji + 2 ngữ pháp mỗi ngày · khoảng 30 phút' from a pace with balanced counts; '' without. */
  function dayLoadLine(pace) {
    var b = pace && pace.balanced;
    if (!b || b.vocab == null) return '';
    return b.vocab + ' từ + ' + b.kanji + ' Kanji + ' + b.grammar + ' ngữ pháp mỗi ngày'
      + (pace.estimatedMinutes ? ' · khoảng ' + pace.estimatedMinutes + ' phút' : '');
  }

  function paceTone(pace) {
    if (!pace) return 'warn';
    if (pace.blocked || pace.late) return 'block';
    if (pace.overBudget || (Array.isArray(pace.warnings) && pace.warnings.length > 0)) return 'warn';
    return 'ok';
  }

  function shortDate(iso) {
    var m = typeof iso === 'string' ? /^(\d{4})-(\d{2})-(\d{2})/.exec(iso) : null;
    return m ? m[3] + '/' + m[2] : '';
  }

  function historyLabel(entry) {
    var e = entry || {};
    var after = e.after || {};
    var parts = [shortDate(e.createdAt)];
    var kind = e.kind === 'LEVEL_START' ? 'Bắt đầu cấp ' + (e.level || '') : KIND_LABELS[e.kind];
    if (kind) parts.push(kind);
    var changes;
    if (!e.before) {
      changes = TABS.map(function (t) { return t[1] + ' ' + after[t[0]]; });
    } else {
      changes = TABS.filter(function (t) { return e.before[t[0]] !== after[t[0]]; })
        .map(function (t) { return t[1] + ' ' + e.before[t[0]] + '→' + after[t[0]]; });
    }
    parts.push(changes.length ? changes.join(', ') : 'Không đổi số mục');
    parts.push(e.counted ? 'tính 1 lượt' : 'không tính lượt');
    return parts.join(' · ');
  }

  function adjustmentsLine(view) {
    var left = view && view.sliders ? view.sliders.adjustmentsLeft : 0;
    return 'Còn ' + left + ' lần chỉnh trong cấp ' + (view && view.openLevel ? view.openLevel : '');
  }

  function lowerLevelText(level, openLevel) {
    return 'Chuyển về ' + level + '. Tiến độ ' + openLevel + ' giữ nguyên để quay lại. Nếu ' + level +
      ' đã vượt qua, bạn quay lại ' + openLevel + ' ngay trong mục này; nếu chưa, lên lại qua Thi thử ' + level + '.';
  }

  // ---------------------------------------------------------------------------------------------- page

  function mount(el, deps) {
    var d = deps || {};
    var win = root || {};
    var doc = el.ownerDocument;
    var api = d.api || win.JpathApi;
    var dayPicker = d.dayPicker || win.JpathDayPicker;
    var ask = d.confirm || win.JlasConfirm || function (msg) { return Promise.resolve(win.confirm(msg)); };
    var go = d.go || function (url) { win.location.assign(url); };
    var later = d.setTimeout || win.setTimeout.bind(win);
    var cancel = d.clearTimeout || win.clearTimeout.bind(win);

    var view = null;
    var original = null;
    var current = null;
    var scheduleUndo = null;
    var picker = null;
    var timer = null;
    var seq = 0;
    var busy = {};

    function part(name, scope) { return (scope || el).querySelector('[data-role="' + name + '"]'); }
    function block(name) { return el.querySelector('[data-block="' + name + '"]'); }
    function make(tag, cls, text) {
      var node = doc.createElement(tag);
      if (cls) node.className = cls;
      if (text != null) node.textContent = text;
      return node;
    }
    function option(value, text) {
      var node = make('option', null, text);
      node.value = value;
      return node;
    }
    function message(scope, role, text, toastFlag) {
      var box = part(role, scope);
      var toast = d.toast || (root && root.JlasToast) || (typeof globalThis !== 'undefined' && globalThis.JlasToast);
      if (toastFlag !== false && text && /saved$/.test(role) && toast && toast.show(text, 'success')) text = '';
      if (!box) return;
      box.textContent = text || '';
      box.hidden = !text;
      if (text && role === 'block-error' && typeof box.scrollIntoView === 'function') {
        box.scrollIntoView({ block: 'nearest' });
      }
    }
    function errorText(error) { return error && (error.code || error.status) && error.message ? error.message : MSG_FAILED; }

    // ---- fields ----------------------------------------------------------------------------------

    function readField(input) {
      if (input.type === 'checkbox') return input.checked;
      if (input.dataset.field === 'vocabPerDay') return Number(input.value);
      return input.value;
    }

    function writeFields(blockName) {
      block(blockName).querySelectorAll('[data-field]').forEach(function (input) {
        var value = current[input.dataset.field];
        if (input.type === 'checkbox') input.checked = !!value;
        else if (input.type === 'radio') input.checked = input.value === value;
        else input.value = value == null ? '' : String(value);
        // bug 41: the server takes only an exam date after today and within 3 years; the picker offers the same range
        if (input.dataset.field === 'examDate') {
          input.min = isoDay(1, 0);
          input.max = isoDay(0, 3);
        }
      });
    }

    function isoDay(addDays, addYears) {
      var d = new Date();
      d.setFullYear(d.getFullYear() + addYears);
      d.setDate(d.getDate() + addDays);
      return d.getFullYear() + '-' + ('0' + (d.getMonth() + 1)).slice(-2) + '-' + ('0' + d.getDate()).slice(-2);
    }

    function drawOptions() {
      var target = el.querySelector('[data-field="targetLevel"]');
      target.textContent = '';
      var levels = list(view.targetOptions);
      if (levels.indexOf(current.targetLevel) < 0 && current.targetLevel) levels.unshift(current.targetLevel);
      levels.forEach(function (level) { target.appendChild(option(level, level)); });

      var words = el.querySelector('[data-field="vocabPerDay"]');
      words.textContent = '';
      wordOptions(view.sliders && view.sliders.bounds, current.vocabPerDay).forEach(function (w) {
        words.appendChild(option(String(w), w + ' từ'));
      });
    }

    function drawThirdDay() {
      var wrap = part('third-wrap');
      wrap.hidden = view.stage !== 'SKILL_PRACTICE';
      var select = el.querySelector('[data-field="thirdSkillDay"]');
      select.textContent = '';
      var auto = view.thirdSkillDayAuto && view.thirdSkillDay ? ' (đang là ' + DAY_NAMES[view.thirdSkillDay] + ')' : '';
      select.appendChild(option('', 'Máy chọn' + auto));
      thirdDayOptions(current.skillDays, current.restDays).forEach(function (day) {
        select.appendChild(option(day, DAY_NAMES[day]));
      });
      select.value = current.thirdSkillDay || '';
    }

    function drawSuggestion() {
      var s = view.suggestion;
      var hint = part('mode-hint');
      hint.textContent = s && s.mode ? 'Gợi ý: ' + (s.mode === 'SPLIT' ? 'Tách' : 'Gộp') +
        (s.reasonVi ? ' — ' + s.reasonVi : '') : '';
      hint.hidden = !hint.textContent;
      var email = el.querySelector('[data-field="reminderEmail"]');
      email.disabled = !view.emailAvailable;
      part('email-off').hidden = !!view.emailAvailable;
    }

    function drawPace(pace, preview) {
      var box = part('pace');
      box.dataset.tone = paceTone(pace);
      part('pace-text').textContent = (preview ? 'Xem trước: ' : '') + (pace && pace.messageVi ? pace.messageVi : '');
      var warnings = part('warnings');
      warnings.textContent = '';
      (pace && Array.isArray(pace.warnings) ? pace.warnings : []).forEach(function (w) {
        if (w && w.messageVi && w.messageVi !== pace.messageVi) warnings.appendChild(make('li', null, w.messageVi));
      });
      warnings.hidden = !warnings.firstChild;
      var load = part('day-load');
      if (load) load.textContent = dayLoadLine(pace);
      var scheduleText = part('schedule-preview');
      if (scheduleText) {
        scheduleText.textContent = (preview ? 'Xem trước: ' : '') + (pace && pace.messageVi ? pace.messageVi : '');
        scheduleText.dataset.tone = paceTone(pace);
      }
      var scheduleWarnings = part('schedule-warnings');
      if (scheduleWarnings) {
        scheduleWarnings.textContent = '';
        (pace && Array.isArray(pace.warnings) ? pace.warnings : []).forEach(function (w) {
          if (w && w.messageVi) scheduleWarnings.appendChild(make('li', null, w.messageVi));
        });
        scheduleWarnings.hidden = !scheduleWarnings.firstChild;
      }
    }

    function drawSliders(history) {
      var s = view.sliders || {};
      part('slider-values').textContent = 'Từ vựng ' + s.vocab + ' · Kanji ' + s.kanji + ' · Ngữ pháp ' + s.grammar;
      part('adjustments').textContent = adjustmentsLine(view);
      if (history === undefined) return;
      var box = part('history');
      box.textContent = '';
      if (history === null) {
        box.appendChild(make('li', 'jp-settings-history__empty', 'Không tải được lịch sử chỉnh.'));
        return;
      }
      if (!history.length) box.appendChild(make('li', 'jp-settings-history__empty', 'Chưa có lần chỉnh nào.'));
      history.forEach(function (entry) { box.appendChild(make('li', null, historyLabel(entry))); });
    }

    function drawChange() {
      var lower = part('lower');
      var levels = list(view.lowerLevels);
      lower.hidden = !levels.length;
      var select = part('lower-level');
      select.textContent = '';
      levels.forEach(function (level) { select.appendChild(option(level, level)); });
      part('lower-level-wrap').hidden = levels.length < 2;
      updateLowerText();
      // AI 1 #13: higher levels already reached come back without a new mock test
      var ups = list(view.returnLevels);
      part('return').hidden = !ups.length;
      var box = part('return-list');
      box.textContent = '';
      ups.forEach(function (level) {
        var b = make('button', 'jp-settings-btn jp-settings-btn--primary', 'Quay lại ' + level);
        b.type = 'button';
        b.setAttribute('data-return-level', level);
        box.appendChild(b);
      });
    }

    function updateLowerText() {
      var level = part('lower-level').value;
      part('lower-text').textContent = level ? lowerLevelText(level, view.openLevel) : '';
    }

    function applyFont() {
      ['S', 'M', 'L'].forEach(function (size) { el.classList.toggle('jp-font-' + size, original.fontSize === size); });
    }

    function refreshButtons() {
      Object.keys(BLOCKS).forEach(function (name) {
        var changes = diff(pick(original, name), pick(current, name));
        var dirty = Object.keys(changes).length > 0;
        var daysOk = name !== 'schedule' || current.skillDays.length === 2;
        if (INSTANT[name]) {
          block(name).querySelectorAll('[data-field]').forEach(function (input) {
            input.disabled = !!busy[name] || (input.dataset.field === 'reminderEmail' && !view.emailAvailable);
          });
          return;
        }
        var save = part('save', block(name));
        save.disabled = !dirty || !daysOk || !!busy[name];
        save.setAttribute('aria-disabled', save.disabled ? 'true' : 'false');
        if (name === 'schedule') message(block(name), 'days-hint', daysOk ? '' : 'Chọn đủ 2 ngày kỹ năng để lưu.');
      });
      var undoBtn = part('undo-schedule');
      if (undoBtn) undoBtn.disabled = !!busy.schedule;
      var daysBox = part('days');
      if (daysBox && typeof daysBox.querySelectorAll === 'function') {
        daysBox.querySelectorAll('button').forEach(function (b) { b.disabled = !!busy.schedule; });
      }
      var scheduleScope = block('schedule');
      if (scheduleScope && typeof scheduleScope.querySelectorAll === 'function') {
        scheduleScope.querySelectorAll('[data-field]').forEach(function (input) {
          input.disabled = !!busy.schedule;
        });
      }
      part('exam-clear').hidden = !current.examDate;
      var restart = part('restart');
      restart.disabled = !restartTextOk(part('restart-text').value) || !!busy.change;
    }

    // ---- changes ---------------------------------------------------------------------------------

    function changed(blockName) {
      current = settleThirdDay(current);
      message(block(blockName), 'saved', '');
      message(block(blockName), 'block-error', '');
      if (blockName === 'schedule') drawThirdDay();
      refreshButtons();
      if (blockName === 'target' || blockName === 'schedule') schedulePreview();
    }

    function showPreviewLoading() {
      var previewBox = part('preview') || part('schedule-preview');
      if (previewBox) previewBox.textContent = 'Đang tính ngày hoàn thành…';
      var paceBox = part('pace-text');
      if (paceBox) paceBox.textContent = 'Đang tính ngày hoàn thành…';
    }

    function previewPayload() {
      var base = previewBody(view, current);
      base.thirdSkillDay = current.thirdSkillDay || '';
      // F151 review: the server reads examDate null as "keep the saved date"; a cleared date must say so, as save does
      if (!current.examDate && original && original.examDate) base.clearExamDate = true;
      return base;
    }

    function requestPreview() {
      return api.send('POST', SETTINGS_URL + '/preview', previewPayload()).then(function (res) {
        return res && res.pace && typeof res.pace === 'object' ? res.pace : res;
      }, function (err) {
        if (err && (err.status === 404 || err.status === 405 || err.code === 'HTTP_404' || err.code === 'HTTP_405')) {
          return api.send('POST', '/api/learning/jlpt/estimate', previewBody(view, current));
        }
        throw err;
      });
    }

    function schedulePreview() {
      if (timer) cancel(timer);
      timer = null;
      var mine = ++seq;
      if (!Object.keys(diff(pick(original, 'target'), pick(current, 'target'))).length
          && !Object.keys(diff(pick(original, 'schedule'), pick(current, 'schedule'))).length) {
        drawPace(view.pace, false);
        return;
      }
      showPreviewLoading();
      timer = later(function () {
        timer = null;
        requestPreview().then(function (pace) {
          if (mine === seq) drawPace(pace, true);
        }, function (error) {
          if (mine === seq) {
            drawPace(view.pace, false);
            message(block('schedule'), 'block-error', errorText(error));
          }
        });
      }, DEBOUNCE_MS);
    }

    function save(blockName, forcedBody) {
      var body = forcedBody || diff(pick(original, blockName), pick(current, blockName));
      if (!Object.keys(body).length || busy[blockName]) return;
      var isUndo = blockName === 'schedule' && !!forcedBody;
      if (blockName === 'target' || blockName === 'schedule') {
        if (timer) cancel(timer);
        timer = null;
        seq++;
      }
      var scope = block(blockName);
      var previousSchedule = blockName === 'schedule' ? pick(original, 'schedule') : null;
      busy[blockName] = true;
      message(scope, 'block-error', '');
      message(scope, 'saved', isUndo ? 'Đang hoàn tác…' : 'Đang lưu…', false);
      refreshButtons();
      api.send('PUT', SETTINGS_URL, body).then(function (next) {
        busy[blockName] = false;
        applyView(next, blockName);
        if (blockName === 'schedule') {
          scheduleUndo = forcedBody ? null : previousSchedule;
          part('undo-schedule').hidden = !scheduleUndo;
        }
        var daysMoved = blockName === 'schedule' && ['skillDays', 'restDays', 'thirdSkillDay'].some(function (k) {
          return Object.prototype.hasOwnProperty.call(body, k);
        });
        message(scope, 'saved', isUndo ? 'Đã hoàn tác lịch tuần.' : (daysMoved ? MSG_DAYS_SAVED : 'Đã lưu.'));
        loadHistory();
      }, function (error) {
        busy[blockName] = false;
        message(scope, 'saved', '');
        message(scope, 'block-error', errorText(error));
        refreshButtons();
      });
    }

    /** New server view; keeps the unsaved edits of the other blocks. */
    function applyView(next, savedBlock) {
      var fresh = stateFromView(next);
      var kept = copy(fresh);
      Object.keys(BLOCKS).forEach(function (name) {
        var dirty = Object.keys(diff(pick(original, name), pick(current, name))).length > 0;
        if (name !== savedBlock && dirty) BLOCKS[name].forEach(function (k) { kept[k] = current[k]; });
      });
      view = next;
      original = fresh;
      current = kept;
      drawOptions();
      Object.keys(BLOCKS).forEach(writeFields);
      if (picker) picker.setState({ skillDays: current.skillDays, restDays: current.restDays });
      drawThirdDay();
      drawSuggestion();
      if (timer) cancel(timer);
      timer = null;
      seq++;
      drawPace(view.pace, false);
      drawSliders();
      drawChange();
      applyFont();
      refreshButtons();
    }

    function loadHistory() {
      return api.get(SETTINGS_URL + '/slider-history').then(function (h) { drawSliders(list(h)); },
        function () { drawSliders(null); });
    }

    // ---- "Đổi lộ trình" --------------------------------------------------------------------------

    function danger(text, opts, request, fallbackUrl) {
      if (busy.change) return;
      var scope = block('change');
      message(scope, 'block-error', '');
      Promise.resolve(ask(text, opts)).then(function (yes) {
        if (!yes) return;
        busy.change = true;
        refreshButtons();
        request().then(function (data) {
          go(data && data.redirectUrl ? data.redirectUrl : fallbackUrl);
        }, function (error) {
          busy.change = false;
          refreshButtons();
          message(scope, 'block-error', errorText(error));
        });
      });
    }

    function bind() {
      var toc = el.querySelector('[data-jp-toc-auto]');
      if (toc) toc.addEventListener('click', function (event) {
        var link = event.target.closest('[data-jp-toc-for]');
        if (!link) return;
        var section = doc.getElementById(link.getAttribute('data-jp-toc-for'));
        var details = section && section.closest('.jp-settings-advanced');
        if (details) details.open = true;
      });
      Object.keys(BLOCKS).forEach(function (name) {
        var scope = block(name);
        scope.addEventListener('change', function (event) {
          var input = event.target;
          if (!input || !input.dataset || !input.dataset.field) return;
          if (input.type === 'radio' && !input.checked) return;
          current[input.dataset.field] = readField(input);
          changed(name);
          if (INSTANT[name]) save(name);
        });
        var saveButton = part('save', scope);
        if (saveButton) saveButton.addEventListener('click', function () {
          if (name === 'target' && view.stage === 'PASSED'
              && JLPT_LEVELS.indexOf(current.targetLevel) > JLPT_LEVELS.indexOf(view.openLevel)) {
            var nextLevel = JLPT_LEVELS[JLPT_LEVELS.indexOf(view.openLevel) + 1];
            Promise.resolve(ask('Bạn đã đỗ ' + view.openLevel + '. Mở ' + nextLevel + ' ngay hôm nay?',
              { confirmText: 'Mở ' + nextLevel, cancelText: 'Hủy', label: 'Mở cấp tiếp theo' }))
              .then(function (confirmed) { if (confirmed) save(name); });
            return;
          }
          save(name);
        });
      });
      part('undo-schedule').addEventListener('click', function () {
        if (scheduleUndo) save('schedule', scheduleUndo);
      });
      part('exam-clear').addEventListener('click', function () {
        current.examDate = '';
        el.querySelector('[data-field="examDate"]').value = '';
        changed('target');
        el.querySelector('[data-field="examDate"]').focus();
      });
      if (dayPicker) {
        picker = dayPicker.mount(part('days'), { skillDays: current.skillDays, restDays: current.restDays },
          function (days) {
            current.skillDays = days.skillDays;
            current.restDays = days.restDays;
            changed('schedule');
          });
      }

      part('lower-level').addEventListener('change', updateLowerText);
      part('lower-go').addEventListener('click', function () {
        var level = part('lower-level').value;
        if (!level) return;
        danger(lowerLevelText(level, view.openLevel),
          { confirmText: 'Chuyển về ' + level, cancelText: 'Hủy', label: 'Học cấp thấp hơn' },
          function () { return api.send('POST', '/api/learning/jlpt/path/lower-level', { level: level }); },
          '/learning');
      });
      part('return-list').addEventListener('click', function (e) {
        var b = e.target.closest('[data-return-level]');
        if (!b) return;
        var level = b.getAttribute('data-return-level');
        danger('Quay lại học ' + level + '? Tiến độ ' + level + ' vẫn như lúc bạn rời đi.',
          { confirmText: 'Quay lại ' + level, cancelText: 'Hủy', label: 'Quay lại cấp đã lên' },
          function () { return api.send('POST', '/api/learning/jlpt/path/return-level', { level: level }); },
          '/learning');
      });
      part('goal-go').addEventListener('click', function () {
        danger('Đổi mục tiêu học? Lộ trình JLPT được giữ, quay về được.',
          { confirmText: 'Đổi mục tiêu', cancelText: 'Hủy', label: 'Đổi mục tiêu' },
          function () { return api.send('POST', '/api/learning/jlpt/path/pause'); },
          '/survey?edit=goal');
      });
      part('restart-text').addEventListener('input', refreshButtons);
      part('restart').addEventListener('click', function () {
        var text = part('restart-text').value;
        if (!restartTextOk(text)) return;
        danger('Xóa toàn bộ tiến độ JLPT? Mọi từ, Kanji, ngữ pháp đã học và lịch học sẽ mất, bạn làm lại khảo sát từ ' +
          'đầu. Không hoàn tác được.',
          { confirmText: 'Xóa và bắt đầu lại', cancelText: 'Hủy', label: 'Bắt đầu lại từ đầu' },
          function () { return api.send('POST', '/api/learning/jlpt/path/restart', { confirmText: text }); },
          '/survey?edit=1');
      });
    }

    function start(next, history) {
      view = next;
      original = stateFromView(view);
      current = copy(original);
      drawOptions();
      Object.keys(BLOCKS).forEach(writeFields);
      bind();
      drawThirdDay();
      drawSuggestion();
      drawPace(view.pace, false);
      drawSliders(history);
      drawChange();
      applyFont();
      refreshButtons();
      part('loading').hidden = true;
      part('content').hidden = false;
    }

    var historyRequest = api.get(SETTINGS_URL + '/slider-history').then(list, function () { return null; });
    return api.get(SETTINGS_URL).then(function (next) {
      return historyRequest.then(function (history) { start(next, history); });
    }, function (error) {
      part('loading').hidden = true;
      message(el, 'error', error && (error.code || error.status) && error.message ? error.message : MSG_LOAD_FAILED);
    });
  }

  return {
    RESTART_TEXT: RESTART_TEXT,
    restartTextOk: restartTextOk,
    stateFromView: stateFromView,
    pick: pick,
    diff: diff,
    thirdDayOptions: thirdDayOptions,
    settleThirdDay: settleThirdDay,
    previewBody: previewBody,
    paceTone: paceTone,
    wordOptions: wordOptions,
    dayLoadLine: dayLoadLine,
    historyLabel: historyLabel,
    adjustmentsLine: adjustmentsLine,
    lowerLevelText: lowerLevelText,
    mount: mount
  };
});
