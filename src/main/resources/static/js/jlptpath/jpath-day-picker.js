/* [JLAS-W0108] jpath-day-picker.js — weekly skill days (exactly 2) and rest days (0–2) of the JLPT path.
 *
 * window.JpathDayPicker:
 *   DAYS                         ['MON', … 'SUN']
 *   toggle(state, day, kind)     kind 'skill'|'rest' → new {skillDays, restDays, message}; never mutates state.
 *                                A 3rd skill day drops the earliest picked one; a 3rd rest day and a rest day on a
 *                                skill day (or the reverse) are refused with a message.
 *   mount(el, state, onChange)   draws 2 rows of 7 chips (T2…CN, aria-pressed) into el; onChange(state) after each
 *                                accepted click. Returns {getState(), setState(state)}.
 */
(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JpathDayPicker = api;
})(typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : null), function () {
  'use strict';

  var DAYS = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'];
  var LABELS = { MON: 'T2', TUE: 'T3', WED: 'T4', THU: 'T5', FRI: 'T6', SAT: 'T7', SUN: 'CN' };
  var FULL_NAMES = { MON: 'Thứ Hai', TUE: 'Thứ Ba', WED: 'Thứ Tư', THU: 'Thứ Năm', FRI: 'Thứ Sáu', SAT: 'Thứ Bảy',
    SUN: 'Chủ nhật' };
  var MAX_SKILL = 2;
  var MAX_REST = 2;
  var MSG_OVERLAP = 'Ngày nghỉ không được trùng ngày kỹ năng.';
  var MSG_REST_MAX = 'Chỉ chọn tối đa 2 ngày nghỉ.';

  function copy(state) {
    var s = state || {};
    return {
      skillDays: Array.isArray(s.skillDays) ? s.skillDays.slice() : [],
      restDays: Array.isArray(s.restDays) ? s.restDays.slice() : [],
      message: null
    };
  }

  function without(list, day) {
    return list.filter(function (d) { return d !== day; });
  }

  function toggle(state, day, kind) {
    var next = copy(state);
    if (DAYS.indexOf(day) < 0 || (kind !== 'skill' && kind !== 'rest')) return next;
    if (kind === 'skill') {
      if (next.skillDays.indexOf(day) >= 0) { next.skillDays = without(next.skillDays, day); return next; }
      if (next.restDays.indexOf(day) >= 0) { next.message = MSG_OVERLAP; return next; }
      next.skillDays.push(day);
      while (next.skillDays.length > MAX_SKILL) next.skillDays.shift();
      return next;
    }
    if (next.restDays.indexOf(day) >= 0) { next.restDays = without(next.restDays, day); return next; }
    if (next.skillDays.indexOf(day) >= 0) { next.message = MSG_OVERLAP; return next; }
    if (next.restDays.length >= MAX_REST) { next.message = MSG_REST_MAX; return next; }
    next.restDays.push(day);
    return next;
  }

  function mount(el, state, onChange) {
    var doc = el.ownerDocument;
    var current = copy(state);
    var message = null;

    function chip(day, kind, pressed) {
      var button = doc.createElement('button');
      button.type = 'button';
      button.className = 'jpath-day-chip jpath-day-chip--' + kind;
      button.textContent = LABELS[day];
      button.setAttribute('aria-pressed', pressed ? 'true' : 'false');
      button.setAttribute('aria-label', FULL_NAMES[day]);
      button.dataset.day = day;
      button.dataset.kind = kind;
      return button;
    }

    function row(kind, title, selected) {
      var group = doc.createElement('div');
      group.className = 'jpath-day-row';
      group.setAttribute('role', 'group');
      var heading = doc.createElement('p');
      heading.className = 'jpath-day-row__title';
      heading.textContent = title;
      group.setAttribute('aria-label', title);
      var chips = doc.createElement('div');
      chips.className = 'jpath-day-row__chips';
      DAYS.forEach(function (day) { chips.appendChild(chip(day, kind, selected.indexOf(day) >= 0)); });
      group.appendChild(heading);
      group.appendChild(chips);
      return group;
    }

    function draw() {
      el.textContent = '';
      el.classList.add('jpath-day-picker');
      el.appendChild(row('skill', 'Ngày kỹ năng (chọn 2)', current.skillDays));
      el.appendChild(row('rest', 'Ngày nghỉ (0–2)', current.restDays));
      var note = doc.createElement('p');
      note.className = 'jpath-day-picker__message';
      note.setAttribute('role', 'status');
      note.textContent = message || '';
      note.hidden = !message;
      el.appendChild(note);
    }

    el.addEventListener('click', function (event) {
      var target = event.target && event.target.closest ? event.target.closest('button[data-day]') : null;
      if (!target || !el.contains(target)) return;
      var next = toggle(current, target.dataset.day, target.dataset.kind);
      message = next.message;
      if (!message) current = { skillDays: next.skillDays, restDays: next.restDays, message: null };
      draw();
      var again = el.querySelector('button[data-day="' + target.dataset.day + '"][data-kind="' + target.dataset.kind + '"]');
      if (again) again.focus();
      if (!message && typeof onChange === 'function') onChange(copy(current));
    });

    draw();
    return {
      getState: function () { return copy(current); },
      setState: function (value) { current = copy(value); message = null; draw(); }
    };
  }

  return { DAYS: DAYS, toggle: toggle, mount: mount };
});
