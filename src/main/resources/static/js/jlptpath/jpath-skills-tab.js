/* [JLAS-W0108] jpath-skills-tab.js — the real Đọc · Nghe tab of the JLPT path page (Plan 3 Task 19).
 *
 * window.JpathSkillsTab = { bannerHtml(o), cardHtml(card, o), typesHtml(rows), actionsHtml(o), readinessHtml(r),
 *                           tabHtml(o), hasContent(o), mount(el, deps) }
 *   - mount(el) reads GET /api/learning/jlpt/skills/overview and draws: the skill-day strip (skill days only), one
 *     card per skill (type of the day, floors, Bắt đầu / Làm tiếp / Đã xong ✓), the type table, Luyện tập tự do,
 *     Luyện thi theo phần (locked before the skill stage), exam readiness and the mock card slot (Task 23).
 *   - A level without any available type keeps the "Sắp mở" text. Every draw ends with 'jpath:rendered'.
 *   - Bắt đầu → POST /skills/sessions {skill, kind DAY}; tự do → {skill, kind FREE, typeCode}; then the session page
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
  var SKILL = { READING: { icon: '📖', name: 'Đọc' }, LISTENING: { icon: '🎧', name: 'Nghe' } };

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
    return '<p class="jp-skills__banner" role="status"><span aria-hidden="true">📅</span> Hôm nay là <strong>ngày kỹ năng'
      + '</strong>: không học mới, ' + esc(num(o.reviewsDue)) + ' thẻ ôn, ~' + esc(num(o.minutesEstimate)) + ' phút. Tuần này: '
      + esc(num(o.sessionsThisWeek)) + '/' + esc(num(o.sessionsRequiredThisWeek)) + ' buổi.</p>';
  }

  function floorText(card) {
    if (card.skill === 'LISTENING') {
      return 'Lý thuyết → ≥ ' + num(card.floorQuestions) + ' câu + ' + num(card.dictationFloor) + ' câu chép chính tả';
    }
    return 'Lý thuyết → ≥ ' + num(card.floorPassages) + ' đoạn · ≥ ' + num(card.floorQuestions) + ' câu';
  }

  /** One skill card: type of the day, floors and the session button for today. */
  function cardHtml(card, o) {
    if (!card) return '';
    var s = SKILL[card.skill] || { icon: '', name: card.skill };
    var head = '<h3 class="jp-skill-card__title"><span aria-hidden="true">' + s.icon + '</span> ' + esc(s.name + ' '
      + ((o && o.level) || '')) + '</h3>';
    if (!card.typeCode) {
      return '<article class="jp-skill-card is-empty">' + head + '<p class="jp-muted">Chưa có bài ' + esc(s.name.toLowerCase())
        + ' ở cấp này.</p></article>';
    }
    var t = typeOf(o, card.typeCode);
    var name = card.typeNameVi || (t && t.nameVi) || card.typeCode;
    var ja = t && t.nameJa ? ' <span lang="ja">(' + esc(t.nameJa) + ')</span>' : '';
    var action;
    if (card.doneToday) {
      action = '<span class="jp-skill-card__done">Đã xong ✓</span>';
    } else if (card.openSessionId != null) {
      action = '<a class="jp-btn jp-btn--primary" href="' + SESSION_PAGE + encodeURIComponent(card.openSessionId)
        + '">Làm tiếp</a>';
    } else if (o && o.skillDay) {
      action = '<button type="button" class="jp-btn jp-btn--primary" data-skill-start="' + esc(card.skill) + '">Bắt đầu '
        + esc(s.name) + '</button>';
    } else {
      action = '<span class="jp-muted">Buổi chính mở vào ngày kỹ năng.</span>';
    }
    return '<article class="jp-skill-card' + (card.doneToday ? ' is-done' : '') + '">' + head
      + '<p>Dạng hôm nay: <strong>' + esc(name) + '</strong>' + ja + '</p>'
      + '<p class="jp-muted">' + esc(floorText(card)) + '</p>' + action + '</article>';
  }

  /** The question types in exam order: done or not, last score. */
  function typesHtml(rows) {
    var items = list(rows).filter(function (t) { return t && t.available; });
    if (!items.length) return '';
    return '<h3 class="jp-skills__sub">Các dạng câu <span class="jp-muted">(học lần lượt, hết vòng quay lại dạng yếu)</span></h3>'
      + '<ul class="jp-skill-types">' + items.map(function (t) {
        var s = SKILL[t.skill] || { icon: '' };
        var state = num(t.sessionsDone) > 0
          ? 'Đã học' + (t.lastPct != null ? ' · ' + esc(num(t.lastPct)) + '%' : '') : 'Chưa học';
        return '<li class="jp-skill-type"><span aria-hidden="true">' + s.icon + '</span> <span lang="ja">' + esc(t.nameJa)
          + '</span> <span>' + esc(t.nameVi) + '</span> <span class="jp-skill-type__state">' + state + '</span></li>';
      }).join('') + '</ul>';
  }

  /** Free practice (pick a type) and section practice (locked before the skill stage). */
  function actionsHtml(o) {
    var options = list(o && o.types).filter(function (t) { return t && t.available; }).map(function (t) {
      return '<option value="' + esc(t.code) + '" data-skill="' + esc(t.skill) + '">' + esc((SKILL[t.skill] || {}).name + ' · '
        + t.nameVi) + '</option>';
    }).join('');
    var free = options ? '<div class="jp-skills__free"><label for="jpSkillFreeType">Luyện tập tự do</label> '
      + '<select id="jpSkillFreeType" data-skill-free-type>' + options + '</select> '
      + '<button type="button" class="jp-btn" data-skill-free>Luyện</button></div>' : '';
    var open = !!(o && o.sectionOpen);
    var section = '<div class="jp-skills__section"><span>Luyện thi theo phần <span aria-hidden="true">⏱</span></span> '
      + ['READING', 'LISTENING'].map(function (k) {
        return '<button type="button" class="jp-btn" data-skill-section="' + k + '"' + (open ? '' : ' disabled') + '>'
          + SKILL[k].name + '</button>';
      }).join(' ') + (open ? '' : ' <span class="jp-muted">Mở ở chặng Rèn kỹ năng</span>') + '</div>';
    return '<div class="jp-skills__actions">' + free + section + '</div>';
  }

  function tryText(t) {
    return (t.passed ? '✓ ' : '✗ ') + (t.scorePct != null ? Math.round(num(t.scorePct)) + '%' : '—');
  }

  /** Exam readiness (#41): a suggestion, it never locks the mock test. */
  function readinessHtml(r) {
    if (!r) return '';
    if (r.ready) return '<p class="jp-skills__ready" role="status">Bạn đã sẵn sàng thi thử.</p>';
    var reading = list(r.lastReading).map(tryText).join(', ') || 'chưa có';
    var listening = list(r.lastListening).map(tryText).join(', ') || 'chưa có';
    return '<div class="jp-skills__readiness"><h3 class="jp-skills__sub">Sẵn sàng thi</h3><ul>'
      + '<li>Dạng câu đã làm: ' + esc(num(r.typesDone)) + '/' + esc(num(r.typesTotal)) + '</li>'
      + '<li>Luyện thi phần Đọc (2 lần gần nhất): ' + esc(reading) + '</li>'
      + '<li>Luyện thi phần Nghe (2 lần gần nhất): ' + esc(listening) + '</li></ul>'
      + '<p class="jp-muted">Cần làm mỗi dạng ít nhất một lần và đạt 2 lần luyện thi gần nhất của mỗi phần.</p></div>';
  }

  function comingSoonHtml() {
    return '<h2 class="jp-section-title">Đọc · Nghe <span class="jp-tab__soon">Sắp mở</span></h2>'
      + '<p>Phần luyện Đọc và Nghe của cấp này đang được chuẩn bị.</p>';
  }

  function tabHtml(o) {
    if (!hasContent(o)) return comingSoonHtml();
    return '<h2 class="jp-section-title">Đọc · Nghe ' + esc(o.level || '') + '</h2>' + bannerHtml(o)
      + '<div class="jp-skill-cards">' + cardHtml(o.reading, o) + cardHtml(o.listening, o) + '</div>'
      + typesHtml(o.types) + actionsHtml(o) + readinessHtml(o.readiness)
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

    function go(promise, toUrl) {
      promise.then(function (res) {
        var target = toUrl(res);
        if (target) loc.assign(target);
      }, function (e) { message((e && e.message) || 'Chưa mở được buổi luyện.'); });
    }

    if (!el.getAttribute('data-skills-bound')) {
      el.setAttribute('data-skills-bound', '1');
      el.addEventListener('click', function (ev) {
        var t = ev.target;
        var start = t.closest('[data-skill-start]');
        if (start) {
          start.disabled = true;
          go(apiClient.send('POST', BASE + '/sessions', { skill: start.getAttribute('data-skill-start'), kind: 'DAY' }),
            function (s) { return s && s.id != null ? SESSION_PAGE + encodeURIComponent(s.id) : null; });
          return;
        }
        if (t.closest('[data-skill-free]')) {
          var sel = el.querySelector('[data-skill-free-type]');
          var opt = sel && sel.options[sel.selectedIndex];
          if (!opt) return;
          go(apiClient.send('POST', BASE + '/sessions', { skill: opt.getAttribute('data-skill'), kind: 'FREE',
            typeCode: opt.value }), function (s) { return s && s.id != null ? SESSION_PAGE + encodeURIComponent(s.id) : null; });
          return;
        }
        var sec = t.closest('[data-skill-section]');
        if (sec && !sec.disabled) {
          sec.disabled = true;
          go(apiClient.send('POST', BASE + '/sections', { skill: sec.getAttribute('data-skill-section') }),
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

  return { bannerHtml: bannerHtml, cardHtml: cardHtml, typesHtml: typesHtml, actionsHtml: actionsHtml,
    readinessHtml: readinessHtml, tabHtml: tabHtml, hasContent: hasContent, mount: mount };
});
