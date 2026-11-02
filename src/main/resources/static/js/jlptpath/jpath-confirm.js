/* [JLAS-W0108] jpath-confirm.js — confirmation screen before a JLPT path is created (/learning/jlpt/confirm).
 *
 * window.JpathConfirm (pure helpers, tested with node --test):
 *   END_TEST_TEXT                 text of the "Kết thúc" dialog for an unfinished Smart Test
 *   paceLine(pace)                {tone:'ok'|'warn'|'block', text}; blocked wins, then late / over budget / any warning
 *   suggestionLabel(s)            button text of a MORE_MINUTES / LATER_EXAM / LOWER_TARGET suggestion
 *   initialState(view)            editable state from GET /confirmation
 *   setSlider / setMinutes / applySuggestion   new state, never mutate; minutes reset sliders to the recommendation
 *   recommend(minutes, bounds)    slider recommendation (policy #2) clamped to the level's bounds
 *   estimateBody(state) / createBody(state)    bodies of POST /estimate and POST /paths
 *   canCreate(state)              false while blocked, a Smart Test is unfinished, an estimate is pending, saving,
 *                                 or the 2 skill days are not picked
 *   confirmationUrl(source, id), endTestUrl(id), formatDate(iso)
 *   mount(root, deps)             draws the screen into the page skeleton; every text goes through textContent.
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathConfirm = api;
  if (root && root.document && typeof root.document.addEventListener === 'function') {
    var start = function () {
      var el = root.document.querySelector('[data-jpath-confirm]');
      if (el) api.mount(el, {});
    };
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', start);
    else start();
  }
})(typeof window !== 'undefined' ? window : null, function (root) {
  'use strict';

  var END_TEST_TEXT = 'Nếu kết thúc, bạn sẽ được tính ở cấp thấp nhất (N5), như chưa học gì';
  var SOURCES = ['SMART_TEST', 'FROM_ZERO', 'ABANDONED_TEST'];
  var SLIDERS = [
    { key: 'vocab', tab: 'VOCAB', label: 'Từ vựng', unit: 'từ' },
    { key: 'kanji', tab: 'KANJI', label: 'Kanji', unit: 'chữ' },
    { key: 'grammar', tab: 'GRAMMAR', label: 'Ngữ pháp', unit: 'mẫu' }
  ];
  var SHARE = { vocab: [0.40, 0.6], kanji: [0.33, 1.25], grammar: [0.27, 4.0] };
  var ATTEMPT_KEY = 'jlasSmartTestAttemptId';
  var DEBOUNCE_MS = 300;
  var MSG_LOAD_FAILED = 'Không tải được màn xác nhận. Thử tải lại trang.';

  function formatDate(iso) {
    if (typeof iso !== 'string') return '';
    var m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso);
    return m ? m[3] + '/' + m[2] + '/' + m[1] : '';
  }

  function paceLine(pace) {
    if (!pace) return { tone: 'warn', text: 'Chưa tính được ngày xong dự kiến.' };
    var text = pace.messageVi || '';
    if (pace.blocked) return { tone: 'block', text: text };
    var warned = pace.late || pace.overBudget || (Array.isArray(pace.warnings) && pace.warnings.length > 0);
    return { tone: warned ? 'warn' : 'ok', text: text };
  }

  function suggestionLabel(s) {
    if (!s) return '';
    switch (s.kind) {
      case 'MORE_MINUTES': return 'Học ' + s.minutes + ' phút/ngày → xong ' + formatDate(s.finish);
      case 'LATER_EXAM': return 'Thi kỳ ' + formatDate(s.examDate);
      case 'LOWER_TARGET': return 'Thi ' + s.level + ' trước → xong ' + formatDate(s.finish);
      default: return '';
    }
  }

  function bound(bounds, tab) {
    var b = bounds && bounds[tab];
    return b && typeof b.min === 'number' && typeof b.max === 'number' ? b : { min: 0, max: 999 };
  }

  function clamp(value, b) {
    return Math.max(b.min, Math.min(b.max, value));
  }

  function recommend(minutes, bounds) {
    var out = {};
    SLIDERS.forEach(function (s) {
      out[s.key] = clamp(Math.round(SHARE[s.key][0] * minutes / SHARE[s.key][1]), bound(bounds, s.tab));
    });
    return out;
  }

  function list(value) {
    return Array.isArray(value) ? value.slice() : [];
  }

  function initialState(view) {
    var v = view || {};
    var sliders = v.sliders || {};
    return {
      view: v,
      source: v.source,
      assessmentAttemptId: v.assessmentAttemptId == null ? null : v.assessmentAttemptId,
      openLevel: v.openLevel,
      kanaTab: !!v.kanaTab,
      targetLevel: v.targetLevel,
      examDate: v.examDate || '',
      dailyMinutes: v.dailyMinutes,
      skillDays: list(v.skillDays),
      restDays: list(v.restDays),
      sliders: { vocab: sliders.vocab, kanji: sliders.kanji, grammar: sliders.grammar },
      mode: v.suggestion && v.suggestion.mode ? v.suggestion.mode : 'COMBINED',
      pace: v.pace || null,
      pending: false,
      saving: false
    };
  }

  function with_(state, changes) {
    var next = {};
    Object.keys(state).forEach(function (k) { next[k] = state[k]; });
    Object.keys(changes).forEach(function (k) { next[k] = changes[k]; });
    return next;
  }

  function setSlider(state, key, value) {
    var spec = SLIDERS.filter(function (s) { return s.key === key; })[0];
    var number = Number(value);
    if (!spec || !isFinite(number)) return with_(state, {});
    var sliders = { vocab: state.sliders.vocab, kanji: state.sliders.kanji, grammar: state.sliders.grammar };
    sliders[key] = clamp(Math.round(number), bound(state.view && state.view.bounds, spec.tab));
    return with_(state, { sliders: sliders });
  }

  function setMinutes(state, minutes) {
    var m = Number(minutes);
    if (!isFinite(m) || m <= 0) return with_(state, {});
    return with_(state, { dailyMinutes: m, sliders: recommend(m, state.view && state.view.bounds) });
  }

  function applySuggestion(state, s) {
    if (!s) return with_(state, {});
    if (s.kind === 'MORE_MINUTES' && s.minutes) return setMinutes(state, s.minutes);
    if (s.kind === 'LATER_EXAM' && s.examDate) return with_(state, { examDate: s.examDate });
    if (s.kind === 'LOWER_TARGET' && s.level) return with_(state, { targetLevel: s.level });
    return with_(state, {});
  }

  function sliderBody(state) {
    return { vocab: state.sliders.vocab, kanji: state.sliders.kanji, grammar: state.sliders.grammar };
  }

  function estimateBody(state) {
    return {
      openLevel: state.openLevel, targetLevel: state.targetLevel, examDate: state.examDate || null,
      dailyMinutes: state.dailyMinutes, skillDays: list(state.skillDays), restDays: list(state.restDays),
      sliders: sliderBody(state), kanaTab: !!state.kanaTab, mode: state.mode
    };
  }

  function createBody(state) {
    return {
      source: state.source, assessmentAttemptId: state.assessmentAttemptId == null ? null : state.assessmentAttemptId,
      targetLevel: state.targetLevel, examDate: state.examDate || null, dailyMinutes: state.dailyMinutes,
      skillDays: list(state.skillDays), restDays: list(state.restDays), sliders: sliderBody(state), mode: state.mode
    };
  }

  function canCreate(state) {
    if (!state || !state.view || !state.pace) return false;
    if (state.view.smartTestInProgress || state.pace.blocked) return false;
    if (state.pending || state.saving) return false;
    return Array.isArray(state.skillDays) && state.skillDays.length === 2;
  }

  /** Why "Tạo lộ trình" is locked, or '' when it is not. */
  function lockReason(state) {
    if (!state || !state.view) return '';
    if (state.view.smartTestInProgress) return 'Hãy làm tiếp hoặc kết thúc bài Smart Test trước khi tạo lộ trình.';
    if (!Array.isArray(state.skillDays) || state.skillDays.length !== 2) return 'Chọn đủ 2 ngày kỹ năng.';
    if (state.pending) return 'Đang tính lại ngày xong dự kiến…';
    if (state.saving) return 'Đang tạo lộ trình…';
    if (state.pace && state.pace.blocked) return 'Đích quá gấp. Chọn một gợi ý ở trên hoặc đổi đích để tạo lộ trình.';
    return '';
  }

  function confirmationUrl(source, attemptId) {
    var s = SOURCES.indexOf(source) >= 0 ? source : 'FROM_ZERO';
    var url = '/api/learning/jlpt/confirmation?source=' + s;
    if (attemptId != null && attemptId !== '') url += '&assessmentAttemptId=' + encodeURIComponent(attemptId);
    return url;
  }

  function endTestUrl(attemptId) {
    return '/learning/jlpt/confirm?source=ABANDONED_TEST&assessmentAttemptId=' + encodeURIComponent(attemptId);
  }

  // ---------------------------------------------------------------------------------------------- page

  function mount(el, deps) {
    var d = deps || {};
    var win = root || {};
    var doc = el.ownerDocument;
    var api = d.api || win.JpathApi;
    var dayPicker = d.dayPicker || win.JpathDayPicker;
    var ask = d.confirm || win.JlasConfirm || function (msg) { return Promise.resolve(win.confirm(msg)); };
    var storage = d.storage || (function () { try { return win.localStorage; } catch (e) { return null; } })();
    var go = d.go || function (url) { win.location.assign(url); };
    var later = d.setTimeout || win.setTimeout.bind(win);
    var cancel = d.clearTimeout || win.clearTimeout.bind(win);

    var state = null;
    var timer = null;
    var seq = 0;

    function part(name) { return el.querySelector('[data-role="' + name + '"]'); }
    function make(tag, cls, text) {
      var node = doc.createElement(tag);
      if (cls) node.className = cls;
      if (text != null) node.textContent = text;
      return node;
    }
    function showError(message) {
      var box = part('error');
      box.textContent = message || '';
      box.hidden = !message;
      if (message && typeof box.scrollIntoView === 'function') box.scrollIntoView({ block: 'nearest' });
    }

    // ---- one-time drawing of the controls ------------------------------------------------------

    function drawSmart(view) {
      var box = part('smart');
      var st = view.smartTest;
      box.hidden = !(view.source === 'SMART_TEST' && st);
      if (box.hidden) return;
      part('smart-level').textContent = st.abilityBand || 'Chưa xác định';
      part('smart-partial').hidden = !st.partial;
      var scores = part('smart-scores');
      scores.textContent = '';
      [['Từ vựng', st.vocabScore], ['Kanji', st.kanjiScore], ['Ngữ pháp', st.grammarScore]].forEach(function (p) {
        var item = make('li', 'jp-confirm-score');
        item.appendChild(make('span', 'jp-confirm-score__label', p[0]));
        item.appendChild(make('strong', 'jp-confirm-score__value', p[1] == null ? '—' : String(p[1])));
        scores.appendChild(item);
      });
      part('smart-notebook').textContent = st.notebookCount + ' mục vào sổ tay điểm yếu';
    }

    function drawUnfinished(view) {
      var box = part('unfinished');
      box.hidden = !view.smartTestInProgress;
      if (box.hidden) return;
      part('resume-test').addEventListener('click', function () {
        try { if (storage) storage.setItem(ATTEMPT_KEY, String(view.assessmentAttemptId)); } catch (e) { /* ignore */ }
        go('/smart-test');
      });
      part('end-test').addEventListener('click', function () {
        Promise.resolve(ask(END_TEST_TEXT, { confirmText: 'Kết thúc', cancelText: 'Quay lại', label: 'Kết thúc Smart Test' }))
          .then(function (yes) { if (yes) go(endTestUrl(view.assessmentAttemptId)); });
      });
    }

    function drawPlan(view) {
      part('open-level').textContent = view.openLevel || '';
      var note = part('level-note');
      note.textContent = view.levelNoteVi || '';
      note.hidden = !view.levelNoteVi;
      part('kana-note').hidden = !view.kanaTab;

      var target = part('target');
      target.textContent = '';
      list(view.targetOptions).forEach(function (level) {
        var option = make('option', null, level);
        option.value = level;
        target.appendChild(option);
      });
      target.addEventListener('change', function () { change(with_(state, { targetLevel: target.value })); });

      var exam = part('exam');
      exam.addEventListener('change', function () { change(with_(state, { examDate: exam.value || '' })); });
      part('exam-clear').addEventListener('click', function () { change(with_(state, { examDate: '' })); });

      var minutes = part('minutes');
      minutes.textContent = '';
      list(view.minuteOptions).forEach(function (m) {
        var option = make('option', null, m + ' phút');
        option.value = String(m);
        minutes.appendChild(option);
      });
      minutes.addEventListener('change', function () { change(setMinutes(state, minutes.value)); });

      if (dayPicker) {
        dayPicker.mount(part('days'), { skillDays: state.skillDays, restDays: state.restDays }, function (days) {
          change(with_(state, { skillDays: days.skillDays, restDays: days.restDays }));
        });
      }
    }

    function drawSliders(view) {
      var box = part('slider-list');
      box.textContent = '';
      SLIDERS.forEach(function (s) {
        var b = bound(view.bounds, s.tab);
        var id = 'jp-confirm-slider-' + s.key;
        var row = make('div', 'jp-confirm-slider');
        var head = make('div', 'jp-confirm-slider__head');
        var label = make('label', 'jp-confirm-slider__label', s.label);
        label.htmlFor = id;
        var value = make('output', 'jp-confirm-slider__value');
        value.htmlFor = id;
        value.dataset.sliderValue = s.key;
        head.appendChild(label);
        head.appendChild(value);
        var input = make('input', 'jp-confirm-slider__input');
        input.type = 'range';
        input.id = id;
        input.min = String(b.min);
        input.max = String(b.max);
        input.step = '1';
        input.dataset.slider = s.key;
        input.addEventListener('input', function () { change(setSlider(state, s.key, input.value)); });
        var foot = make('p', 'jp-confirm-slider__foot');
        foot.dataset.sliderFinish = s.tab;
        row.appendChild(head);
        row.appendChild(input);
        row.appendChild(foot);
        box.appendChild(row);
      });
    }

    function drawModes(view) {
      var box = part('modes');
      box.textContent = '';
      var suggested = view.suggestion && view.suggestion.mode;
      [
        { mode: 'COMBINED', title: 'Học hôm nay (Gộp)',
          text: 'Một lượt học liền mạch: ôn thẻ đến hạn → từ mới → Kanji mới → ngữ pháp mới. Xong là hoàn thành cả ngày.' },
        { mode: 'SPLIT', title: 'Học riêng từng phần (Tách)',
          text: 'Vào từng tab Từ vựng / Kanji / Ngữ pháp, học phần nào trước cũng được, chia ra nhiều lúc trong ngày. Tiến độ vẫn cộng như nhau.' }
      ].forEach(function (m) {
        var label = make('label', 'jp-confirm-mode' + (m.mode === suggested ? ' jp-confirm-mode--suggested' : ''));
        var input = make('input', 'jp-confirm-mode__input');
        input.type = 'radio';
        input.name = 'jp-confirm-mode';
        input.value = m.mode;
        input.addEventListener('change', function () { if (input.checked) change(with_(state, { mode: m.mode })); });
        var body = make('span', 'jp-confirm-mode__body');
        var title = make('span', 'jp-confirm-mode__title', m.title);
        if (m.mode === suggested) title.appendChild(make('span', 'jp-confirm-mode__badge', 'Gợi ý cho bạn'));
        body.appendChild(title);
        body.appendChild(make('span', 'jp-confirm-mode__text', m.text));
        if (m.mode === suggested && view.suggestion.reasonVi) {
          body.appendChild(make('span', 'jp-confirm-mode__reason', 'Vì sao gợi ý: ' + view.suggestion.reasonVi));
        }
        label.appendChild(input);
        label.appendChild(body);
        box.appendChild(label);
      });
    }

    // ---- updates after each change -------------------------------------------------------------

    function update() {
      var view = state.view;
      var target = part('target');
      if (list(view.targetOptions).indexOf(state.targetLevel) < 0 && state.targetLevel) {
        var extra = make('option', null, state.targetLevel);
        extra.value = state.targetLevel;
        target.appendChild(extra);
      }
      target.value = state.targetLevel || '';
      part('exam').value = state.examDate || '';
      part('exam-clear').hidden = !state.examDate;
      part('minutes').value = String(state.dailyMinutes);

      SLIDERS.forEach(function (s) {
        var input = el.querySelector('[data-slider="' + s.key + '"]');
        if (input && String(input.value) !== String(state.sliders[s.key])) input.value = String(state.sliders[s.key]);
        var out = el.querySelector('[data-slider-value="' + s.key + '"]');
        if (out) out.textContent = state.sliders[s.key] + ' ' + s.unit + '/ngày';
        var foot = el.querySelector('[data-slider-finish="' + s.tab + '"]');
        var finish = state.pace && state.pace.tabFinish ? state.pace.tabFinish[s.tab] : null;
        if (foot) foot.textContent = finish ? 'Dự kiến xong ' + formatDate(finish) : '';
      });

      var pace = state.pace;
      var minutesLine = part('minutes-line');
      minutesLine.textContent = pace
        ? 'Ước tính khoảng ' + pace.estimatedMinutes + ' phút/ngày · bạn rảnh ' + state.dailyMinutes + ' phút'
        : '';
      minutesLine.classList.toggle('jp-confirm-minutes--over', !!(pace && pace.overBudget));

      var line = paceLine(pace);
      var paceBox = part('pace');
      paceBox.dataset.tone = line.tone;
      part('pace-text').textContent = line.text;
      var warnings = part('warnings');
      warnings.textContent = '';
      (pace && Array.isArray(pace.warnings) ? pace.warnings : []).forEach(function (w) {
        if (w && w.messageVi) warnings.appendChild(make('li', 'jp-confirm-warning', w.messageVi));
      });
      warnings.hidden = !warnings.firstChild;

      var suggestions = part('suggestions');
      suggestions.textContent = '';
      (pace && (pace.blocked || pace.late) && Array.isArray(pace.suggestions) ? pace.suggestions : [])
        .forEach(function (s) {
          var text = suggestionLabel(s);
          if (!text) return;
          var button = make('button', 'jp-confirm-suggestion', text);
          button.type = 'button';
          button.addEventListener('click', function () { change(applySuggestion(state, s)); });
          suggestions.appendChild(button);
        });
      part('suggestions-box').hidden = !suggestions.firstChild;

      el.querySelectorAll('input[name="jp-confirm-mode"]').forEach(function (input) {
        input.checked = input.value === state.mode;
      });

      var create = part('create');
      var allowed = canCreate(state);
      create.disabled = !allowed;
      create.setAttribute('aria-disabled', allowed ? 'false' : 'true');
      var reason = lockReason(state);
      var hint = part('create-hint');
      hint.textContent = reason;
      hint.hidden = !reason;
    }

    function change(next) {
      state = next;
      showError('');
      if (state.skillDays.length !== 2) {
        if (timer) cancel(timer);
        timer = null;
        seq++;
        state = with_(state, { pending: false });
        update();
        return;
      }
      state = with_(state, { pending: true });
      update();
      if (timer) cancel(timer);
      timer = later(estimate, DEBOUNCE_MS);
    }

    function estimate() {
      timer = null;
      var mine = ++seq;
      api.send('POST', '/api/learning/jlpt/estimate', estimateBody(state)).then(function (pace) {
        if (mine !== seq) return;
        state = with_(state, { pace: pace, pending: false });
        update();
      }, function (error) {
        if (mine !== seq) return;
        state = with_(state, { pending: false });
        update();
        showError(error && error.message ? error.message : MSG_LOAD_FAILED);
      });
    }

    function submit() {
      if (!canCreate(state)) return;
      state = with_(state, { saving: true });
      update();
      api.send('POST', '/api/learning/jlpt/paths', createBody(state)).then(function () {
        go('/learning');
      }, function (error) {
        if (error && error.code === 'JPATH_ACTIVE_PATH_EXISTS') { go('/learning'); return; }
        state = with_(state, { saving: false });
        update();
        showError(error && error.message ? error.message : MSG_LOAD_FAILED);
      });
    }

    function start(view) {
      state = initialState(view);
      drawSmart(view);
      drawUnfinished(view);
      drawPlan(view);
      drawSliders(view);
      drawModes(view);
      part('create').addEventListener('click', submit);
      part('loading').hidden = true;
      part('content').hidden = false;
      update();
    }

    var source = el.getAttribute('data-source');
    var attempt = el.getAttribute('data-attempt-id');
    return api.get(confirmationUrl(source, attempt || null)).then(start, function (error) {
      part('loading').hidden = true;
      showError(error && error.message ? error.message : MSG_LOAD_FAILED);
    });
  }

  return {
    END_TEST_TEXT: END_TEST_TEXT,
    paceLine: paceLine,
    suggestionLabel: suggestionLabel,
    formatDate: formatDate,
    recommend: recommend,
    initialState: initialState,
    setSlider: setSlider,
    setMinutes: setMinutes,
    applySuggestion: applySuggestion,
    estimateBody: estimateBody,
    createBody: createBody,
    canCreate: canCreate,
    lockReason: lockReason,
    confirmationUrl: confirmationUrl,
    endTestUrl: endTestUrl,
    mount: mount
  };
});
