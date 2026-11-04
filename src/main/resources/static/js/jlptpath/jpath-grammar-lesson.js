/* [JLAS-W0108] jpath-grammar-lesson.js — one grammar lesson: in the list pane of the path page (wide screens) and on its
 * own page /learning/jlpt/grammar/{code} (#jpathGrammarRoot[data-grammar-code], auto-mounted).
 *
 * Reads GET /api/learning/jlpt/grammar/{code} (+ GET /settings for the display block on the lesson page); writes
 * POST /checks {kind GRAMMAR_PRACTICE|GRAMMAR_EXTRA, tab:'GRAMMAR', level, itemCode} and POST /grammar/{code}/queue-today.
 *
 * window.JpathGrammarLesson:
 *   structureChips(formation)           'V-て + は + いけません' → one chip per part
 *   grammarLessonHtml(lesson)           order of the approved sample; empty parts left out
 *   lessonUrl(code)
 *   lessonAction(el, lesson, container, env)
 *                                       env = {api, navigate, returnTo}; handles play / grammar-practice / queue-today,
 *                                       true when it handled el. Practice mounts through JpathCheck.mountInline when
 *                                       jpath-check.js is loaded, else goes to the check page.
 *   mountGrammarLesson(rootEl?, deps?)  the lesson page; applies the learner display settings and the furigana toggle
 * Needs jpath-display.js, jpath-furigana.js and jpath-item-drawer.js loaded first.
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathGrammarLesson = api;
  if (root && root.document && typeof root.document.getElementById === 'function') {
    var start = function () {
      if (root.document.getElementById('jpathGrammarRoot')) api.mountGrammarLesson();
    };
    if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', start);
    else start();
  }
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var BASE = '/api/learning/jlpt';
  var MSG_QUEUED = 'Đã thêm vào lượt hôm nay.';

  function D() {
    return (root && root.JpathDisplay) || (typeof require === 'function' ? require('./jpath-display.js') : null);
  }
  function F() {
    return (root && root.JpathFurigana) || (typeof require === 'function' ? require('./jpath-furigana.js') : null);
  }
  function H() {
    return (root && root.JpathItemDrawer) || (typeof require === 'function' ? require('./jpath-item-drawer.js') : null);
  }
  function esc(v) { return D().esc(v); }
  function icon(name) {
    var nav = (root && root.JpathNav) || (typeof require === 'function' ? require('./jpath-nav.js') : null);
    return nav && nav.iconHtml ? nav.iconHtml(name) : '';
  }
  function list(v) { return Array.isArray(v) ? v : []; }
  function has(v) { return v !== null && v !== undefined && v !== ''; }
  function num(v) { return Number(v) || 0; }

  function structureChips(formation) {
    if (!has(formation)) return '';
    var parts = String(formation).split(/\s*[+＋]\s*/).filter(function (p) { return p !== ''; });
    return '<span class="jp-struct" lang="ja">' + parts.map(function (p) {
      return '<span class="jp-struct__chip">' + esc(p) + '</span>';
    }).join('<span class="jp-struct__plus" aria-hidden="true">+</span>') + '</span>';
  }

  function lessonPart(label, body) {
    return body ? '<div class="jp-lesson__part"><span class="jp-lesson__label">' + esc(label) + '</span>' + body + '</div>' : '';
  }

  function textPart(label, value) { return has(value) ? lessonPart(label, '<p>' + esc(value) + '</p>') : ''; }

  function grammarLessonHtml(lesson) {
    var g = lesson || {};
    var state = H().stateOf(g.state);
    var html = '<article class="jp-lesson" data-jpath-report="'
      + D().dataJson({ targetType: 'GRAMMAR', targetCode: g.code, pageContext: 'GRAMMAR_LESSON' }) + '">';
    var eyebrow = ['NGỮ PHÁP ' + (g.level || ''), has(g.unitNo) ? 'BÀI ' + g.unitNo : null,
      num(g.unitSize) > 0 ? 'MẪU ' + num(g.indexInUnit) + '/' + num(g.unitSize) : null].filter(has).join(' · ');
    html += '<p class="jp-lesson__eyebrow">' + esc(eyebrow) + '</p>';
    html += '<h2 class="jp-lesson__pattern" id="jp-lesson-title" lang="ja">' + esc(g.pattern) + '</h2>';
    var variants = list(g.variants).filter(has);
    if (variants.length) html += '<p class="jp-muted" lang="ja">' + esc(variants.join(' · ')) + '</p>';
    html += textPart('Nghĩa', g.meaning);
    html += lessonPart('Cấu trúc', structureChips(g.formation));
    html += textPart('Cách dùng', g.usage) + textPart('Sắc thái', g.nuance) + textPart('Lỗi thường gặp', g.commonMistakes);
    html += list(g.related).filter(function (r) { return r && has(r.pattern); }).map(function (r) {
      return '<p class="jp-lesson__compare">' + esc('So sánh: ' + r.pattern + (has(r.meaning) ? ' (' + r.meaning + ')' : ''))
        + '</p>';
    }).join('');
    var examples = list(g.examples).map(function (e) {
      var ex = F().exampleHtml(e);
      if (!ex) return '';
      return '<li class="jp-example">' + (e && has(e.audioUrl) ? '<button type="button" class="jp-icon-btn" data-action="play" data-audio="'
        + esc(e.audioUrl) + '" aria-label="Nghe câu ví dụ">' + icon('volume-2') + '</button>' : '') + '<div>' + ex + '</div></li>';
    }).filter(Boolean).join('');
    html += lessonPart('Ví dụ', examples ? '<ul class="jp-examples">' + examples + '</ul>' : '');
    // secondary patterns attached to this one: learned together with it, never on their own
    var attached = list(g.attached).filter(function (a) { return a && has(a.pattern); }).map(function (a) {
      var ex = has(a.exampleJa) ? F().exampleHtml({ ja: a.exampleJa, jaFurigana: a.exampleFurigana, vi: a.exampleVi }) : '';
      return '<li class="jp-attached"><p class="jp-attached__pattern" lang="ja">' + esc(a.pattern) + '</p>'
        + (has(a.meaning) ? '<p class="jp-attached__meaning">' + esc(a.meaning) + '</p>' : '')
        + (ex ? '<div class="jp-attached__example">' + ex + '</div>' : '') + '</li>';
    }).join('');
    html += lessonPart('Mẫu phụ đi kèm', attached ? '<ul class="jp-attached-list">' + attached + '</ul>' : '');
    html += '</article>';
    if (g.practiceAvailable || g.extraAvailable) {
      html += '<section class="jp-lesson-practice no-inline-dictionary" data-practice="GRAMMAR_PRACTICE"'
        + ' aria-labelledby="jp-practice-title"><h3 class="jp-lesson-practice__title" id="jp-practice-title">Luyện ngay</h3>';
      if (g.practiceAvailable) {
        html += '<p class="jp-muted">1 câu xếp từ và 1 câu điền.</p><button type="button" class="jp-btn jp-btn--primary"'
          + ' data-action="grammar-practice" data-kind="GRAMMAR_PRACTICE">Luyện ngay</button>';
      }
      if (g.extraAvailable) {
        html += '<button type="button" class="jp-link-btn" data-action="grammar-practice" data-kind="GRAMMAR_EXTRA">'
          + '+ Luyện thêm 3 câu</button>';
      }
      html += '<div class="jp-lesson-practice__mount" data-part="practice-mount"></div>'
        + '<p class="jp-alert" role="alert" data-part="lesson-error" hidden></p></section>';
    } else {
      html += '<p class="jp-alert" role="alert" data-part="lesson-error" hidden></p>';
    }
    var foot = '';
    if (state === 'NEW' && g.learnable !== false) {
      foot += '<button type="button" class="jp-btn" data-action="queue-today">Học mẫu này trong lượt hôm nay</button>';
    }
    var nav = '';
    if (has(g.prevCode)) {
      nav += '<a class="jp-link-btn" data-action="open-grammar" data-code="' + esc(g.prevCode) + '" href="/learning/jlpt/grammar/'
        + encodeURIComponent(g.prevCode) + '">' + icon('arrow-left') + ' Mẫu trước</a>';
    }
    if (has(g.nextCode)) {
      nav += '<a class="jp-link-btn" data-action="open-grammar" data-code="' + esc(g.nextCode) + '" href="/learning/jlpt/grammar/'
        + encodeURIComponent(g.nextCode) + '">Mẫu sau ' + icon('arrow-right') + '</a>';
    }
    if (nav) foot += '<nav class="jp-lesson__nav" aria-label="Mẫu khác">' + nav + '</nav>';
    if (foot) html += '<div class="jp-lesson__foot">' + foot + '<p class="jp-status" role="status" data-part="lesson-status"></p></div>';
    return html;
  }

  function lessonUrl(code) { return BASE + '/grammar/' + encodeURIComponent(code); }

  function lessonAction(el, lesson, container, env) {
    var action = el.getAttribute('data-action');
    var g = lesson || {};
    if (action === 'play') { D().playAudio(el.getAttribute('data-audio')); return true; }
    if (action === 'grammar-practice') {
      el.disabled = true;
      env.api.send('POST', BASE + '/checks', { kind: el.getAttribute('data-kind') || 'GRAMMAR_PRACTICE', tab: 'GRAMMAR',
        level: g.level, itemCode: g.code }).then(function (res) {
        var token = res && (res.token || res.checkToken);
        var mountEl = container.querySelector('[data-part="practice-mount"]');
        var check = root && root.JpathCheck;
        if (token && check && typeof check.mountInline === 'function' && mountEl) {
          el.disabled = false;
          return check.mountInline(mountEl, token, {});
        }
        if (token) env.navigate(H().checkUrl(token, env.returnTo));
        return null;
      }).catch(function (e) {
        el.disabled = false;
        H().showError(container, 'lesson-error', H().messageOf(e));
      });
      return true;
    }
    if (action === 'queue-today') {
      el.disabled = true;
      env.api.send('POST', BASE + '/grammar/' + encodeURIComponent(g.code) + '/queue-today').then(function () {
        var ui = root && root.JlasUI;
        if (ui && typeof ui.toast === 'function') ui.toast(MSG_QUEUED, 'success');
        var status = container.querySelector('[data-part="lesson-status"]');
        if (status) status.textContent = MSG_QUEUED;
      }, function (e) {
        el.disabled = false;
        H().showError(container, 'lesson-error', H().messageOf(e));
      });
      return true;
    }
    return false;
  }

  function displayTarget(el) {
    var main = el && typeof el.closest === 'function' ? el.closest('.jp-path') : null;
    return main || el;
  }

  function mountGrammarLesson(rootEl, deps) {
    var d = deps || {};
    var doc = d.document || (root && root.document);
    var el = rootEl || (doc && doc.getElementById('jpathGrammarRoot'));
    if (!el) return Promise.resolve();
    var apiClient = d.api || root.JpathApi;
    var loc = d.location || root.location;
    var rendered = d.rendered || function (x) { D().rendered(x); };
    var code = el.getAttribute('data-grammar-code');
    var lesson = null;
    var env = { api: apiClient, navigate: d.navigate || function (u) { root.location.assign(u); },
      returnTo: ((loc && loc.pathname) || '/learning') + ((loc && loc.search) || '') };
    el.addEventListener('click', function (ev) {
      var t = ev.target;
      var btn = t && typeof t.closest === 'function' ? t.closest('[data-action]') : null;
      if (!btn || btn.disabled || !lesson) return;
      lessonAction(btn, lesson, el, env);
    });
    var settings = Promise.resolve().then(function () { return apiClient.get(D().SETTINGS_URL); })
      .then(function (s) { return (s && s.display) || {}; }, function () { return {}; });
    var view = Promise.resolve().then(function () { return apiClient.get(lessonUrl(code)); });
    return Promise.all([view, settings]).then(function (res) {
      lesson = res[0] || {};
      var display = res[1];
      try { D().applyDisplay(displayTarget(el), display); } catch (e) { /* defaults stay */ }
      F().bindToggle(el, !!display.furiganaHiddenAll);
      el.innerHTML = grammarLessonHtml(lesson);
      el.setAttribute('aria-busy', 'false');
      if (doc && has(lesson.pattern)) doc.title = lesson.pattern + ' - JLAS';
      rendered(el);
    }, function (e) {
      el.innerHTML = '<p class="jp-alert" role="alert">' + esc(H().messageOf(e, 'Không tải được bài ngữ pháp.')) + '</p>';
      el.setAttribute('aria-busy', 'false');
    });
  }

  return { structureChips: structureChips, grammarLessonHtml: grammarLessonHtml, lessonUrl: lessonUrl,
    lessonAction: lessonAction, mountGrammarLesson: mountGrammarLesson, MSG_QUEUED: MSG_QUEUED };
});
