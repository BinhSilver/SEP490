/**
 * Scroll-wheel date picker (day / month / year columns): swipe on touch, wheel or drag with a mouse, arrow keys.
 * The original <input type="date"> stays hidden as the source of truth, so forms keep reading input.value and
 * listening for "change". Every column starts on its label row and the value stays empty until all three are
 * chosen: a preselected date could be confirmed by accident, and a birth date can only be set once.
 */
(function (root, factory) {
  'use strict';
  const api = factory(root);
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.DateWheel = api;
}(typeof window !== 'undefined' ? window : globalThis, function (root) {
  'use strict';
  const PAGE_STEP = 5;
  // The year column opens with its label just above this year, so most learners scroll only a few rows.
  const OPENING_YEAR = 2005;

  const pad = value => String(value).padStart(2, '0');

  /** Month is 1-12. An unknown year counts as a leap year, so 29 Feb stays selectable until the year is chosen. */
  function daysInMonth(year, month) {
    return new Date(year || 2000, month || 1, 0).getDate();
  }

  function toIso(day, month, year) {
    return day && month && year ? `${year}-${pad(month)}-${pad(day)}` : '';
  }

  function displayText(day, month, year) {
    return `${day ? pad(day) : 'dd'}/${month ? pad(month) : 'mm'}/${year || 'yyyy'}`;
  }

  function range(from, to, step = 1) {
    const values = [];
    for (let value = from; step > 0 ? value <= to : value >= to; value += step) values.push(value);
    return values;
  }

  /** The column opens on its label row (labelAt); scrolling away from it chooses a value, scrolling back clears it. */
  function createColumn(document, label, values, format, onChange, labelAt = 0) {
    const el = document.createElement('div');
    el.className = 'date-wheel__col';
    el.tabIndex = 0;
    el.setAttribute('role', 'spinbutton');
    el.setAttribute('aria-label', label);
    // 0 marks the label row: nothing chosen in this column.
    const rowValues = [...values.slice(0, labelAt), 0, ...values.slice(labelAt)];
    const rows = rowValues.map(value => {
      const row = document.createElement('div');
      row.className = 'date-wheel__row' + (value ? '' : ' date-wheel__row--label');
      row.textContent = value ? format(value) : label;
      el.appendChild(row);
      return row;
    });
    let index = labelAt;
    let lastIndex = rows.length - 1;
    let dragged = false;

    const rowHeight = () => rows[0].offsetHeight || 36;
    const selected = () => rowValues[index];

    function paint() {
      rows.forEach((row, position) => row.classList.toggle('is-selected', position === index));
      el.setAttribute('aria-valuetext', rows[index].textContent);
    }

    function select(next) {
      if (next === index) return;
      index = next;
      paint();
      onChange();
    }

    function scrollToIndex(next, behavior) {
      el.scrollTo({ top: next * rowHeight(), behavior });
    }

    el.addEventListener('scroll', () => {
      if (el.offsetHeight) select(Math.min(lastIndex, Math.max(0, Math.round(el.scrollTop / rowHeight()))));
    }, { passive: true });

    // Touch swipes scroll natively; a mouse has to drag the column itself.
    el.addEventListener('pointerdown', event => {
      if (event.pointerType !== 'mouse' || event.button !== 0) return;
      dragged = false;
      const startY = event.clientY;
      const startTop = el.scrollTop;
      const move = moveEvent => {
        const distance = moveEvent.clientY - startY;
        if (!dragged && Math.abs(distance) < 4) return;
        dragged = true;
        el.classList.add('is-dragging');
        el.scrollTop = startTop - distance;
      };
      const up = () => {
        root.removeEventListener('pointermove', move);
        root.removeEventListener('pointerup', up);
        el.classList.remove('is-dragging');
        if (dragged) scrollToIndex(index, 'smooth');
      };
      root.addEventListener('pointermove', move);
      root.addEventListener('pointerup', up);
    });

    el.addEventListener('click', event => {
      const row = event.target.closest('.date-wheel__row');
      if (!dragged && row) scrollToIndex(rows.indexOf(row), 'smooth');
      dragged = false;
    });

    el.addEventListener('keydown', event => {
      const targets = { ArrowUp: index - 1, ArrowDown: index + 1, PageUp: index - PAGE_STEP, PageDown: index + PAGE_STEP, Home: 0, End: lastIndex };
      if (!(event.key in targets)) return;
      event.preventDefault();
      scrollToIndex(Math.min(lastIndex, Math.max(0, targets[event.key])), 'auto');
    });

    paint();
    return {
      el,
      selected,
      /** A closed panel forgets its scroll position; put the column back on its row when it opens. */
      sync: () => scrollToIndex(index, 'auto'),
      setValue(value) {
        const position = rowValues.indexOf(value);
        index = position < 0 ? labelAt : position;
        paint();
        scrollToIndex(index, 'auto');
      },
      /** Hides the rows past the last valid value (days of a shorter month) and steps back if one was selected. */
      limit(count) {
        lastIndex = count;
        rows.forEach((row, position) => { row.hidden = position > count; });
        if (index > count) {
          index = count;
          paint();
          scrollToIndex(index, 'auto');
        }
      }
    };
  }

  /** options.popover: a compact field that opens the wheels over the form; otherwise the wheels show in place. */
  function attach(input, options = {}) {
    const document = input.ownerDocument;
    const firstYear = Number((input.min || '1900').slice(0, 4));
    const years = range(new Date().getFullYear(), firstYear, -1);
    let trigger = null;
    let setOpen = () => {};

    const update = () => {
      const [day, month, year] = columns.map(column => column.selected());
      columns[0].limit(daysInMonth(year, month));
      const chosenDay = columns[0].selected();
      if (trigger) {
        trigger.textContent = displayText(chosenDay, month, year);
        trigger.classList.toggle('is-empty', !chosenDay && !month && !year);
      }
      const iso = toIso(chosenDay, month, year);
      if (iso !== input.value) {
        input.value = iso;
        input.dispatchEvent(new Event('change', { bubbles: true }));
      }
    };
    const columns = [
      createColumn(document, 'Ngày', range(1, 31), pad, () => update()),
      createColumn(document, 'Tháng', range(1, 12), pad, () => update()),
      createColumn(document, 'Năm', years, String, () => update(), Math.max(0, years.findIndex(year => year <= OPENING_YEAR)))
    ];

    const wheel = document.createElement('div');
    wheel.className = 'date-wheel';
    wheel.setAttribute('role', 'group');
    columns.forEach(column => wheel.appendChild(column.el));

    input.style.display = 'none';
    input.tabIndex = -1;
    const label = input.id ? document.querySelector(`label[for="${input.id}"]`) : null;

    if (options.popover) {
      trigger = document.createElement('button');
      trigger.type = 'button';
      trigger.id = input.id + 'Wheel';
      trigger.className = input.className + ' date-wheel-trigger is-empty';
      trigger.textContent = displayText(0, 0, 0);
      trigger.setAttribute('aria-expanded', 'false');
      if (input.hasAttribute('aria-describedby')) trigger.setAttribute('aria-describedby', input.getAttribute('aria-describedby'));
      if (label) label.htmlFor = trigger.id;
      wheel.classList.add('date-wheel--popover');
      wheel.hidden = true;
      input.parentElement.classList.add('date-wheel-host');
      input.after(trigger, wheel);

      setOpen = open => {
        wheel.hidden = !open;
        trigger.setAttribute('aria-expanded', String(open));
        if (open) columns.forEach(column => column.sync());
      };
      trigger.addEventListener('click', () => setOpen(wheel.hidden));
      document.addEventListener('pointerdown', event => {
        if (!wheel.hidden && !wheel.contains(event.target) && event.target !== trigger) setOpen(false);
      });
      input.parentElement.addEventListener('keydown', event => {
        if (event.key !== 'Escape' || wheel.hidden) return;
        setOpen(false);
        trigger.focus();
      });
      input.focus = () => trigger.focus();
    } else {
      if (label) {
        label.id = label.id || input.id + 'Label';
        wheel.setAttribute('aria-labelledby', label.id);
        label.addEventListener('click', () => columns[0].el.focus());
      }
      input.after(wheel);
      input.focus = () => columns[0].el.focus();
    }

    return {
      /** For wheels that show in place: call once they are visible, a hidden column cannot scroll to its row. */
      sync: () => columns.forEach(column => column.sync()),
      /** Shows a stored date (yyyy-mm-dd, or empty); locked turns a compact field read-only. */
      set(iso, locked) {
        const [year, month, day] = (iso || '').split('-').map(Number);
        input.value = iso || '';
        columns[2].setValue(year || 0);
        columns[1].setValue(month || 0);
        columns[0].limit(daysInMonth(year, month));
        columns[0].setValue(day || 0);
        update();
        if (trigger) {
          trigger.disabled = !!locked;
          if (locked) setOpen(false);
        }
      },
      /** Some columns chosen but not all, so input.value is still empty. */
      isPartial() {
        const chosen = columns.filter(column => column.selected()).length;
        return chosen > 0 && chosen < columns.length;
      }
    };
  }

  return { attach, daysInMonth, toIso, displayText };
}));
