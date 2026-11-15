/* [JLAS-W0108] jpath-skills-tab.js — the real Đọc · Nghe tab of the JLPT path page (Plan 3 Task 19).
 *
 * window.JpathSkillsTab = { bannerHtml(o), cardHtml(card, o), typesHtml(rows, skill, todayCode), groupHtml(skill, card, o),
 *                           actionsHtml(o), readinessHtml(r), tabHtml(o), hasContent(o), mount(el, deps) }
 *   - mount(el) reads GET /api/learning/jlpt/skills/overview and draws the skill-day strip (skill days only), then one
 *     group per skill (Đọc, Nghe): today's session (Bắt đầu / Làm tiếp / Đã xong + Học lại) and that skill's types,
 *     each with Học / Học lại (a FREE session of the type, lesson first); then Luyện thi theo phần (locked before the
 *     skill stage), exam readiness and the mock card slot (Task 23).
 *   - A level without any available type keeps the "Sắp mở" text. Every draw ends with 'jpath:rendered'.
 *   - Bắt đầu → POST /skills/sessions {skill, kind DAY}; Học / Học lại → {skill, kind FREE, typeCode}; then the session page
 *     /learning/jlpt/skills/session/{id}. Luyện thi theo phần → POST /skills/sections {skill} → /learning/jlpt/skills/section/{token}.
 */
(function (root, factory) {
  'use strict';
  var api = factory(root);
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathSkillsTab = api;
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function (root) {
  'use strict';

  var BASE = '/api/learning/jlpt/skills';
  var SESSION_PAGE = '/learning/jlpt/skills/session/';
  var SECTION_PAGE = '/learning/jlpt/skills/section/';
  var SKILL = { READING: { icon: 'book-open', name: 'Đọc' }, LISTENING: { icon: 'headphones', name: 'Nghe' } };
  function icon(name) {
    var nav = root.JpathNav || (typeof require === 'function' ? require('./jpath-nav.js') : null);
    return nav ? nav.iconHtml(name) : '';
  }

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function list(x) { return Array.isArray(x) ? x : []; }

  function num(x) { var n = Number(x); return isNaN(n) ? 0 : n; }

  /** True when at least one question type of the level can be studied. */
  function hasContent(o) {
    return !!o && list(o.types).some(function (t) { return t && t.available; });
  }

  function typeOf(o, code) {
    return list(o && o.types).filter(function (t) { return t && t.code === code; })[0] || null;
  }

  /** Skill days only: no new items, reviews, minutes and this week's sessions. */
  function bannerHtml(o) {
    if (!o || !o.skillDay) return '';
    return '<p class="jp-skills__banner" role="status">Hôm nay là <strong>ngày kỹ năng'
      + '</strong>: không học mới, ' + esc(num(o.reviewsDue)) + ' thẻ ôn, ~' + esc(num(o.minutesEstimate)) + ' phút. Tuần này: '
      + esc(num(o.sessionsThisWeek)) + '/' + esc(num(o.sessionsRequiredThisWeek)) + ' buổi.</p>';
  }

  /** What today's session asks for, in plain words. */
  function floorText(card) {
    if (card.skill === 'LISTENING') {
      return 'Xem lý thuyết, nghe ít nhất ' + num(card.floorQuestions) + ' câu và chép chính tả ' + num(card.dictationFloor)
        + ' câu.';
    }
    return 'Xem lý thuyết, đọc ít nhất ' + num(card.floorPassages) + ' đoạn và làm ít nhất ' + num(card.floorQuestions)
      + ' câu.';
  }

  /** Re-opens one question type from its lesson: a FREE session of that type. */
  function againButton(skill, code, label) {
    return '<button type="button" class="jp-btn jp-btn--sm" data-skill-free="' + esc(skill) + '" data-skill-type="'
      + esc(code) + '">' + esc(label) + '</button>';
  }

  /** Today's session of one skill: its type, what it asks for and the button. */
  function cardHtml(card, o) {
    if (!card) return '';
    var s = SKILL[card.skill] || { icon: '', name: card.skill };
    if (!card.typeCode) {
      return '<div class="jp-skill-card is-empty"><p class="jp-muted">Chưa có bài ' + esc(s.name.toLowerCase())
        + ' ở cấp này.</p></div>';
    }
    var t = typeOf(o, card.typeCode);
    var name = card.typeNameVi || (t && t.nameVi) || card.typeCode;
    var ja = t && t.nameJa ? ' <span lang="ja">(' + esc(t.nameJa) + ')</span>' : '';
    var action;
    if (card.doneToday) {
      action = '<p class="jp-skill-card__done">' + icon('check') + ' Đã xong buổi hôm nay</p>'
        + againButton(card.skill, card.typeCode, 'Học lại dạng này');
    } else if (card.openSessionId != null) {
      action = '<a class="jp-btn jp-btn--primary" href="' + SESSION_PAGE + encodeURIComponent(card.openSessionId)
        + '">Làm tiếp</a>';
    } else if (o && o.skillDay) {
      action = '<button type="button" class="jp-btn jp-btn--primary" data-skill-start="' + esc(card.skill) + '">Bắt đầu '
        + esc(s.name) + '</button>';
    } else {
      action = '<p class="jp-muted">Buổi chính mở vào ngày kỹ năng. Hôm nay bạn vẫn học lại được từng dạng bên dưới.</p>';
    }
    return '<div class="jp-skill-card' + (card.doneToday ? ' is-done' : '') + '">'
      + '<p class="jp-skill-card__kicker">Buổi hôm nay</p>'
      + '<p class="jp-skill-card__type"><strong>' + esc(name) + '</strong>' + ja + '</p>'
      + '<p class="jp-muted">' + esc(floorText(card)) + '</p>' + action + '</div>';
  }

  /** The question types of one skill (all when skill is empty) in exam order; each one can be studied again. */
  function typesHtml(rows, skill, todayCode) {
    var items = list(rows).filter(function (t) { return t && t.available && (!skill || t.skill === skill); });
    if (!items.length) return '';
    return '<ul class="jp-skill-types">' + items.map(function (t) {
      var done = num(t.sessionsDone) > 0;
      var state = done ? 'Đã học' + (t.lastPct != null ? ' · ' + esc(num(t.lastPct)) + '%' : '') : 'Chưa học';
      var today = todayCode && t.code === todayCode ? ' <span class="jp-skill-type__today">Hôm nay</span>' : '';
      return '<li class="jp-skill-type"><span class="jp-skill-type__name"><span lang="ja">' + esc(t.nameJa)
        + '</span> <span>' + esc(t.nameVi) + '</span>' + today + '</span><span class="jp-skill-type__state">' + state
        + '</span>' + againButton(t.skill, t.code, done ? 'Học lại' : 'Học') + '</li>';
    }).join('') + '</ul>';
  }

  /** One skill together: today's session, then every type of that skill. */
  function groupHtml(skill, card, o) {
    var s = SKILL[skill];
    var id = 'jp-skill-' + skill.toLowerCase();
    return '<section class="jp-skill-group" aria-labelledby="' + id + '"><h3 class="jp-skill-group__title" id="' + id + '">'
      + icon(s.icon) + ' ' + esc(s.name + ' ' + ((o && o.level) || '')) + '</h3>'
      + cardHtml(card, o)
      + '<h4 class="jp-skill-group__sub">' + esc('Các dạng ' + s.name) + '</h4>'
      + typesHtml(o && o.types, skill, card && card.typeCode) + '</section>';
  }

  /** Section practice (locked before the skill stage). */
  function actionsHtml(o) {
    var open = !!(o && o.sectionOpen);
    var section = '<div class="jp-skills__section"><span>Luyện thi theo phần</span> '
      + ['READING', 'LISTENING'].map(function (k) {
        return '<button type="button" class="jp-btn" data-skill-section="' + k + '"' + (open ? '' : ' disabled') + '>'
          + SKILL[k].name + '</button>';
      }).join(' ') + (open ? '' : ' <span class="jp-muted">Mở ở chặng Rèn kỹ năng</span>') + '</div>';
    return '<div class="jp-skills__actions">' + section + '</div>';
  }

  function tryText(t) {
    return (t.passed ? icon('check') : icon('x')) + ' ' + (t.scorePct != null ? Math.round(num(t.scorePct)) + '%' : '—');
  }

  /** Exam readiness (#41): a suggestion, it never locks the mock test. */
  function readinessHtml(r) {
    if (!r) return '';
    if (r.ready) return '<p class="jp-skills__ready" role="status">Bạn đã sẵn sàng thi thử.</p>';
    var reading = list(r.lastReading).map(tryText).join(', ') || 'chưa có';
    var listening = list(r.lastListening).map(tryText).join(', ') || 'chưa có';
    return '<div class="jp-skills__readiness"><h3 class="jp-skills__sub">Sẵn sàng thi</h3><ul>'
      + '<li>Dạng câu đã làm: ' + esc(num(r.typesDone)) + '/' + esc(num(r.typesTotal)) + '</li>'
      + '<li>Luyện thi phần Đọc (2 lần gần nhất): ' + reading + '</li>'
      + '<li>Luyện thi phần Nghe (2 lần gần nhất): ' + listening + '</li></ul>'
      + '<p class="jp-muted">Cần làm mỗi dạng ít nhất một lần và đạt 2 lần luyện thi gần nhất của mỗi phần.</p></div>';
  }

  function comingSoonHtml() {
    return '<h2 class="jp-section-title">Đọc · Nghe <span class="jp-tab__soon">Sắp mở</span></h2>'
      + '<p>Phần luyện Đọc và Nghe của cấp này đang được chuẩn bị.</p>';
  }

  function tabHtml(o) {
    if (!hasContent(o)) return comingSoonHtml();
    return '<h2 class="jp-section-title">Đọc · Nghe ' + esc(o.level || '') + '</h2>' + bannerHtml(o)
      + '<p class="jp-muted jp-skills__how">Mỗi ngày kỹ năng, hệ thống chọn dạng tiếp theo theo thứ tự đề thi; dạng còn yếu'
      + ' sẽ được ôn lại. Bấm Học lại ở bất kỳ dạng nào để xem lại lý thuyết và luyện từ đầu.</p>'
      + '<div class="jp-skill-groups">' + groupHtml('READING', o.reading, o) + groupHtml('LISTENING', o.listening, o) + '</div>'
      + '<details class="jp-skills__more"><summary>Luyện thi theo phần và mức sẵn sàng thi</summary>'
      + actionsHtml(o) + readinessHtml(o.readiness) + '</details>'
      + '<div data-jpath-mock-card></div><p class="jp-skills__msg" role="alert" data-skill-msg hidden></p>';
  }

  function rendered(doc, el) {
    if (doc && typeof doc.dispatchEvent === 'function' && typeof root.CustomEvent === 'function') {
      doc.dispatchEvent(new root.CustomEvent('jpath:rendered', { detail: { root: el } }));
    }
  }

  /** Fills {@code el}; deps = {api, location, document, level}. Returns the overview promise. */
  function mount(el, deps) {
    var d = deps || {};
    var apiClient = d.api || root.JpathApi;
    var loc = d.location || root.location;
    var doc = d.document || root.document;
    if (!el || !apiClient) return Promise.resolve(null);
    var url = BASE + '/overview' + (d.level ? '?level=' + encodeURIComponent(d.level) : '');

    function message(text) {
      var m = el.querySelector('[data-skill-msg]');
      if (m) { m.textContent = text || ''; m.hidden = !text; }
    }

    /** F144: a request that fails (or gives no id/token) hands the button back with a message. */
    function go(button, promise, toUrl) {
      message('');
      promise.then(function (res) {
        var target = toUrl(res);
        if (!target) throw new Error('');
        loc.assign(target);
      }).then(null, function (e) {
        button.disabled = false;
        message((e && e.message) || 'Chưa mở được buổi luyện. Hãy thử lại.');
      });
    }

    if (!el.getAttribute('data-skills-bound')) {
      el.setAttribute('data-skills-bound', '1');
      el.addEventListener('click', function (ev) {
        var t = ev.target;
        var start = t.closest('[data-skill-start]');
        if (start) {
          if (start.disabled) return;
          start.disabled = true;
          go(start, apiClient.send('POST', BASE + '/sessions', { skill: start.getAttribute('data-skill-start'), kind: 'DAY' }),
            function (s) { return s && s.id != null ? SESSION_PAGE + encodeURIComponent(s.id) : null; });
          return;
        }
        var free = t.closest('[data-skill-free]');
        if (free) {
          if (free.disabled) return;
          free.disabled = true;
          go(free, apiClient.send('POST', BASE + '/sessions', { skill: free.getAttribute('data-skill-free'), kind: 'FREE',
            typeCode: free.getAttribute('data-skill-type') }),
            function (s) { return s && s.id != null ? SESSION_PAGE + encodeURIComponent(s.id) : null; });
          return;
        }
        var sec = t.closest('[data-skill-section]');
        if (sec && !sec.disabled) {
          sec.disabled = true;
          go(sec, apiClient.send('POST', BASE + '/sections', { skill: sec.getAttribute('data-skill-section') }),
            function (c) { return c && c.token ? SECTION_PAGE + encodeURIComponent(c.token) : null; });
        }
      });
    }

    return apiClient.get(url).then(function (o) {
      el.innerHTML = tabHtml(o);
      // Plan 3 Task 23: the mock test card of the open level
      var mock = el.querySelector('[data-jpath-mock-card]');
      if (mock && root.JpathMockCard && hasContent(o)) {
        root.JpathMockCard.mount(mock, { api: apiClient, location: loc, document: doc, readiness: o.readiness });
      }
      rendered(doc, el);
      return o;
    }, function (e) {
      el.innerHTML = '<p class="jp-alert" role="alert">' + esc((e && e.message) || 'Không tải được phần Đọc · Nghe.') + '</p>';
      return null;
    });
  }

  return { bannerHtml: bannerHtml, cardHtml: cardHtml, typesHtml: typesHtml, groupHtml: groupHtml, actionsHtml: actionsHtml,
    readinessHtml: readinessHtml, tabHtml: tabHtml, hasContent: hasContent, mount: mount };
});
