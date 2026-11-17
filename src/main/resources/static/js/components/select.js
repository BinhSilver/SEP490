/*
 * JlasSelect: draws a J-LAS styled dropdown over a real <select>.
 *
 * The <select> stays in the page, hidden, and remains the source of truth, so form submit, `name`, `required`,
 * existing `change` listeners and `select.value = x` from page scripts keep working with no other change.
 *
 * Use: add `data-jl-select` to a <select> (selects added later by scripts are picked up too), or call
 * JlasSelect.enhance(selectOrContainer).
 *
 * On the <select>, all optional:
 *   data-variant="filter"   compact pill for filter bars; the box takes the width of its text
 *   data-search="on|off"    search box in the list; by default it appears from 12 options up
 *   data-icon="fa-solid …"  leading icon (an `.input-wrap__icon` placed right before the select is used too)
 *   data-placeholder="…"    text shown while the value is empty
 * On each <option>, all optional:
 *   data-tag="N5"           short chip before the text
 *   data-hint="…"           second, quieter line in the list
 *   data-label="…"          text to show instead of the option text (the option text stays for no-JS)
 */
(function () {
  'use strict';

  if (window.JlasSelect) return;

  var SHEET_QUERY = '(max-width: 560px)';
  var SEARCH_AUTO_MIN = 12;
  var PAGE_STEP = 8;
  var GAP = 6;
  var EDGE = 8;

  var nativeValue = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value');
  var nativeIndex = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'selectedIndex');
  var instances = new WeakMap();
  var uid = 0;
  var current = null;
  var backdrop = null;

  var ICONS = {
    chevron: '<svg viewBox="0 0 20 20" aria-hidden="true"><path d="M5.5 7.75 10 12.25l4.5-4.5" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>',
    check: '<svg viewBox="0 0 20 20" aria-hidden="true"><path d="m4.75 10.5 3.5 3.5 7-8" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>',
    search: '<svg viewBox="0 0 20 20" aria-hidden="true"><circle cx="8.75" cy="8.75" r="5.25" fill="none" stroke="currentColor" stroke-width="1.7"/><path d="m12.75 12.75 3.75 3.75" stroke="currentColor" stroke-width="1.7" stroke-linecap="round"/></svg>',
    close: '<svg viewBox="0 0 20 20" aria-hidden="true"><path d="m5.5 5.5 9 9m0-9-9 9" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>'
  };

  /* Vietnamese-friendly matching: "han tu" finds "Hán tự", "do" finds "Độ". */
  function fold(text) {
    return String(text || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '')
      .replace(/[đĐ]/g, 'd').toLowerCase().replace(/\s+/g, ' ').trim();
  }

  function el(tag, className, attrs) {
    var node = document.createElement(tag);
    if (className) node.className = className;
    if (attrs) Object.keys(attrs).forEach(function (key) { node.setAttribute(key, attrs[key]); });
    return node;
  }

  function optionLabel(option) {
    return (option.dataset.label || option.label || option.textContent || '').trim();
  }

  function isPlaceholder(option) {
    return option.value === '' && option.disabled;
  }

  /* A label may wrap the select ("Hỏi bằng <select>"); keep only the label's own words. */
  function labelText(label) {
    var copy = label.cloneNode(true);
    Array.prototype.forEach.call(copy.querySelectorAll('select, .jl-select, [aria-hidden="true"]'), function (node) {
      node.remove();
    });
    return (copy.textContent || '').replace(/\s+/g, ' ').trim();
  }

  /* A modal <dialog> sits in the top layer, so the list must live inside it to show and take clicks. */
  function hostFor(node) {
    return node.closest('dialog[open], [popover]') || document.body;
  }

  function sheetMode() {
    return window.matchMedia(SHEET_QUERY).matches;
  }

  function Select(select) {
    this.select = select;
    this.id = 'jls-' + (++uid);
    this.items = [];
    this.active = -1;
    this.typed = '';
    this.typedTimer = 0;
    this.closeTimer = 0;
    this.syncFrame = 0;
    this.scrollFrame = 0;
    this.nativeInvalid = false;
    this.mode = 'popover';
    this.build();
    this.patchValue();
    this.observe();
    this.render();
  }

  Select.prototype.build = function () {
    var select = this.select;
    var self = this;
    var wrap = el('div', 'jl-select');
    if (select.dataset.variant === 'filter') wrap.classList.add('jl-select--filter');
    /* "overlay": an invisible box laid over an icon button the page draws itself. */
    if (select.dataset.variant === 'overlay') wrap.classList.add('jl-select--overlay');
    var hadFocus = document.activeElement === select;

    var trigger = el('button', 'jl-select__trigger', {
      type: 'button',
      role: 'combobox',
      'aria-haspopup': 'listbox',
      'aria-expanded': 'false',
      'aria-controls': this.id + '-list',
      'aria-autocomplete': 'none'
    });

    var iconClass = select.dataset.icon;
    var siblingIcon = select.previousElementSibling;
    if (!iconClass && siblingIcon && siblingIcon.matches('.input-wrap__icon')) {
      iconClass = siblingIcon.className.replace(/\binput-wrap__icon\b/, '').trim();
      siblingIcon.remove();
    }
    if (iconClass) {
      var icon = el('i', 'jl-select__icon ' + iconClass, { 'aria-hidden': 'true' });
      trigger.appendChild(icon);
      wrap.classList.add('jl-select--icon');
    }
    var value = el('span', 'jl-select__value', { id: this.id + '-value' });
    var chevron = el('span', 'jl-select__chevron');
    chevron.innerHTML = ICONS.chevron;
    trigger.appendChild(value);
    trigger.appendChild(chevron);

    select.parentNode.insertBefore(wrap, select);
    wrap.appendChild(select);
    wrap.appendChild(trigger);
    select.classList.add('jl-select__native');
    select.tabIndex = -1;
    select.setAttribute('aria-hidden', 'true');
    /* A page that focused the select right after drawing it keeps focus on the new box. */
    if (hadFocus) trigger.focus({ preventScroll: true });

    var labelIds = [];
    Array.prototype.forEach.call(select.labels || [], function (label, i) {
      if (!label.id) label.id = self.id + '-label' + (i || '');
      labelIds.push(label.id);
      label.addEventListener('click', function (event) {
        event.preventDefault();
        if (!trigger.disabled) trigger.focus();
      });
    });
    if (labelIds.length) {
      trigger.setAttribute('aria-labelledby', labelIds.join(' ') + ' ' + value.id);
    } else if (select.getAttribute('aria-label')) {
      trigger.setAttribute('aria-label', select.getAttribute('aria-label'));
    }
    var describedBy = select.getAttribute('aria-describedby');
    if (describedBy) trigger.setAttribute('aria-describedby', describedBy);
    this.title = labelIds.length
      ? labelText(select.labels[0])
      : (select.getAttribute('aria-label') || '');

    /* The panel lives on <body> (or the open <dialog>) so cards with overflow: hidden cannot clip it. */
    var panel = el('div', 'jl-select-panel', { id: this.id + '-panel' });
    panel.hidden = true;
    var head = el('div', 'jl-select-sheet-head');
    var headTitle = el('span', 'jl-select-sheet-head__title');
    headTitle.textContent = this.title || 'Chọn';
    var closeBtn = el('button', 'jl-select-sheet-head__close', { type: 'button', 'aria-label': 'Đóng' });
    closeBtn.innerHTML = ICONS.close;
    head.appendChild(headTitle);
    head.appendChild(closeBtn);
    panel.appendChild(head);

    var searchMode = select.dataset.search || 'auto';
    this.searchWanted = searchMode;
    var searchWrap = el('div', 'jl-select-search');
    searchWrap.innerHTML = ICONS.search;
    var search = el('input', 'jl-select-search__input', {
      type: 'text',
      role: 'combobox',
      'aria-expanded': 'true',
      'aria-controls': this.id + '-list',
      'aria-autocomplete': 'list',
      autocomplete: 'off',
      spellcheck: 'false',
      placeholder: 'Gõ để tìm',
      'aria-label': 'Tìm trong ' + (this.title || 'danh sách').toLowerCase()
    });
    searchWrap.appendChild(search);
    panel.appendChild(searchWrap);

    var list = el('ul', 'jl-select-list', { id: this.id + '-list', role: 'listbox', tabindex: '-1' });
    if (labelIds.length) list.setAttribute('aria-labelledby', labelIds.join(' '));
    else if (this.title) list.setAttribute('aria-label', this.title);
    panel.appendChild(list);
    var empty = el('p', 'jl-select-empty', { role: 'status' });
    empty.hidden = true;
    panel.appendChild(empty);
    document.body.appendChild(panel);

    this.wrap = wrap;
    this.trigger = trigger;
    this.valueEl = value;
    this.panel = panel;
    this.searchWrap = searchWrap;
    this.search = search;
    this.list = list;
    this.empty = empty;

    trigger.addEventListener('click', function () {
      if (self.isOpen()) self.close(true); else self.open();
    });
    /* Keys typed on the box belong to it, as with a real <select>: page shortcuts (Enter = next
       question, 1–4 = answer…) must not fire too. Tab, a closing Escape and Ctrl/Alt combos pass on. */
    function keepKey(event) {
      if (event.key === 'Tab' || event.key === 'Escape' || event.ctrlKey || event.metaKey || event.altKey) return;
      event.stopPropagation();
    }
    trigger.addEventListener('keydown', function (event) { self.onKey(event, false); keepKey(event); });
    search.addEventListener('keydown', function (event) { self.onKey(event, true); keepKey(event); });
    search.addEventListener('input', function () { self.filter(search.value); });
    closeBtn.addEventListener('click', function () { self.close(true); });
    /* Keep focus on the trigger or search box while the pointer picks an option. */
    panel.addEventListener('mousedown', function (event) {
      if (event.target !== search && !closeBtn.contains(event.target)) event.preventDefault();
    });
    list.addEventListener('pointermove', function (event) {
      var row = event.target.closest('.jl-select-option');
      if (row && !row.hidden) self.setActive(Number(row.dataset.index), false);
    });
    list.addEventListener('click', function (event) {
      var row = event.target.closest('.jl-select-option');
      if (row) self.choose(Number(row.dataset.index));
    });
    select.addEventListener('focus', function () { if (!trigger.disabled) trigger.focus(); });
    select.addEventListener('change', function () { self.render(); });
    select.addEventListener('invalid', function () { self.nativeInvalid = true; self.render(); });
    if (select.form) {
      select.form.addEventListener('reset', function () { setTimeout(function () { self.render(); }, 0); });
    }
  };

  /* Page scripts that set select.value or selectedIndex directly still update what the box shows. */
  Select.prototype.patchValue = function () {
    var self = this;
    var select = this.select;
    Object.defineProperty(select, 'value', {
      configurable: true,
      get: function () { return nativeValue.get.call(select); },
      set: function (v) { nativeValue.set.call(select, v); self.render(); }
    });
    Object.defineProperty(select, 'selectedIndex', {
      configurable: true,
      get: function () { return nativeIndex.get.call(select); },
      set: function (v) { nativeIndex.set.call(select, v); self.render(); }
    });
  };

  Select.prototype.observe = function () {
    var self = this;
    this.observer = new MutationObserver(function () {
      cancelAnimationFrame(self.syncFrame);
      self.syncFrame = requestAnimationFrame(function () {
        self.render();
        if (self.isOpen()) self.rebuild();
      });
    });
    this.observer.observe(this.select, {
      childList: true, subtree: true, characterData: true,
      attributes: true,
      attributeFilter: ['disabled', 'hidden', 'style', 'label', 'selected', 'aria-invalid', 'data-tag', 'data-hint', 'data-label']
    });
  };

  Select.prototype.render = function () {
    var select = this.select;
    var option = select.options[nativeIndex.get.call(select)];
    var placeholder = select.dataset.placeholder;
    var showPlaceholder = !option || (option.value === '' && (placeholder || isPlaceholder(option)));
    this.valueEl.textContent = '';
    if (showPlaceholder) {
      var text = el('span', 'jl-select__text');
      text.textContent = placeholder || (option ? optionLabel(option) : '');
      this.valueEl.appendChild(text);
    } else {
      if (option.dataset.tag) {
        var tag = el('span', 'jl-select-tag');
        tag.textContent = option.dataset.tag;
        this.valueEl.appendChild(tag);
      }
      var label = el('span', 'jl-select__text');
      label.textContent = optionLabel(option);
      this.valueEl.appendChild(label);
    }
    this.wrap.classList.toggle('is-placeholder', !!showPlaceholder);
    /* "Set" means moved off the option the page started with (e.g. a filter that is now on). */
    var defaultIndex = 0;
    for (var i = 0; i < select.options.length; i++) {
      if (select.options[i].defaultSelected) { defaultIndex = i; break; }
    }
    this.wrap.classList.toggle('is-set', !!option && option.index !== defaultIndex);
    this.trigger.disabled = select.disabled;
    this.wrap.classList.toggle('is-disabled', select.disabled);
    /* Page scripts that hide the <select> hide the whole box. */
    var hidden = select.hidden || select.style.display === 'none';
    if (this.wrap.hidden !== hidden) this.wrap.hidden = hidden;
    if (hidden && this.isOpen()) this.close(false);
    if (this.nativeInvalid || select.getAttribute('aria-invalid') === 'true') this.trigger.setAttribute('aria-invalid', 'true');
    else this.trigger.removeAttribute('aria-invalid');
  };

  /* Rebuild the list from the <select> each time it opens, so options added by page scripts always show. */
  Select.prototype.rebuild = function () {
    var self = this;
    var list = this.list;
    list.textContent = '';
    this.items = [];

    function addOption(option, parent) {
      if (isPlaceholder(option)) return;
      var index = option.index;
      var row = el('li', 'jl-select-option', {
        id: self.id + '-opt-' + index,
        role: 'option',
        'aria-selected': option.selected ? 'true' : 'false'
      });
      row.dataset.index = String(index);
      if (option.disabled) row.setAttribute('aria-disabled', 'true');
      if (option.dataset.tag) {
        var tag = el('span', 'jl-select-tag');
        tag.textContent = option.dataset.tag;
        row.appendChild(tag);
      }
      var text = el('span', 'jl-select-option__text');
      var label = el('span', 'jl-select-option__label');
      label.textContent = optionLabel(option);
      text.appendChild(label);
      if (option.dataset.hint) {
        var hint = el('span', 'jl-select-option__hint');
        hint.textContent = option.dataset.hint;
        text.appendChild(hint);
      }
      row.appendChild(text);
      var check = el('span', 'jl-select-option__check');
      check.innerHTML = ICONS.check;
      row.appendChild(check);
      parent.appendChild(row);
      self.items.push({
        index: index,
        row: row,
        disabled: option.disabled,
        key: fold(optionLabel(option)),
        haystack: fold([option.dataset.tag, optionLabel(option), option.textContent, option.dataset.hint].join(' '))
      });
    }

    Array.prototype.forEach.call(this.select.children, function (child, g) {
      if (child.tagName === 'OPTGROUP') {
        var holder = el('li', 'jl-select-group', { role: 'presentation' });
        var title = el('div', 'jl-select-group__label', { id: self.id + '-group-' + g });
        title.textContent = child.label;
        var group = el('ul', 'jl-select-group__list', { role: 'group', 'aria-labelledby': title.id });
        holder.appendChild(title);
        holder.appendChild(group);
        list.appendChild(holder);
        Array.prototype.forEach.call(child.children, function (option) {
          addOption(option, group);
          if (child.disabled) {
            var last = self.items[self.items.length - 1];
            if (last && last.index === option.index) { last.disabled = true; last.row.setAttribute('aria-disabled', 'true'); }
          }
        });
      } else if (child.tagName === 'OPTION') {
        addOption(child, list);
      }
    });

    var wantSearch = this.searchWanted === 'on' ||
      (this.searchWanted !== 'off' && this.items.length >= SEARCH_AUTO_MIN);
    this.searchWrap.hidden = !wantSearch;
    this.panel.classList.toggle('has-search', wantSearch);
    this.search.value = '';
    this.empty.hidden = true;
  };

  Select.prototype.visibleItems = function () {
    return this.items.filter(function (item) { return !item.row.hidden && !item.disabled; });
  };

  Select.prototype.filter = function (query) {
    /* Every typed word must appear, in any order: "bai 3 han" finds "Bài 3 (Hán tự)". */
    var words = fold(query).split(' ').filter(Boolean);
    var shown = 0;
    this.items.forEach(function (item) {
      var match = words.every(function (word) { return item.haystack.indexOf(word) !== -1; });
      item.row.hidden = !match;
      if (match) shown++;
    });
    Array.prototype.forEach.call(this.list.querySelectorAll('.jl-select-group'), function (group) {
      group.hidden = !group.querySelector('.jl-select-option:not([hidden])');
    });
    this.empty.hidden = shown > 0;
    if (!shown) this.empty.textContent = 'Không có mục nào khớp “' + query.trim() + '”.';
    var first = this.visibleItems()[0];
    this.setActive(first ? first.index : -1, true);
  };

  Select.prototype.setActive = function (index, scroll) {
    if (this.active === index) return;
    var previous = this.itemAt(this.active);
    if (previous) previous.row.classList.remove('is-active');
    this.active = index;
    var item = this.itemAt(index);
    /* Focus is on the trigger, or on the search box when there is one; both point at the same option. */
    var owners = [this.trigger, this.search];
    if (!item) {
      owners.forEach(function (owner) { owner.removeAttribute('aria-activedescendant'); });
      return;
    }
    item.row.classList.add('is-active');
    owners.forEach(function (owner) { owner.setAttribute('aria-activedescendant', item.row.id); });
    if (scroll) item.row.scrollIntoView({ block: 'nearest' });
  };

  Select.prototype.itemAt = function (index) {
    for (var i = 0; i < this.items.length; i++) if (this.items[i].index === index) return this.items[i];
    return null;
  };

  Select.prototype.move = function (step) {
    var items = this.visibleItems();
    if (!items.length) return;
    var pos = -1;
    for (var i = 0; i < items.length; i++) if (items[i].index === this.active) pos = i;
    var next;
    if (step === 'first') next = 0;
    else if (step === 'last') next = items.length - 1;
    else next = pos === -1 ? (step > 0 ? 0 : items.length - 1) : Math.max(0, Math.min(items.length - 1, pos + step));
    this.setActive(items[next].index, true);
  };

  Select.prototype.typeAhead = function (char) {
    var self = this;
    clearTimeout(this.typedTimer);
    this.typed += fold(char) || ' ';
    this.typedTimer = setTimeout(function () { self.typed = ''; }, 600);
    var items = this.visibleItems();
    if (!items.length) return;
    var start = 0;
    for (var i = 0; i < items.length; i++) if (items[i].index === this.active) start = i;
    /* Repeating one letter cycles through the options that start with it, like the native list. */
    var needle = /^(.)\1+$/.test(this.typed) ? this.typed[0] : this.typed;
    var offset = needle.length === 1 ? 1 : 0;
    for (var n = 0; n < items.length; n++) {
      var item = items[(start + offset + n) % items.length];
      if (item.key.indexOf(needle) === 0) { this.setActive(item.index, true); return; }
    }
  };

  Select.prototype.isOpen = function () {
    return current === this;
  };

  Select.prototype.open = function (focusEdge) {
    if (this.select.disabled || this.isOpen()) return;
    if (current) current.close(false);
    current = this;
    clearTimeout(this.closeTimer);
    this.rebuild();
    this.mode = sheetMode() ? 'sheet' : 'popover';
    var panel = this.panel;
    var host = hostFor(this.wrap);
    if (panel.parentNode !== host) host.appendChild(panel);
    panel.classList.toggle('is-sheet', this.mode === 'sheet');
    panel.hidden = false;
    this.wrap.classList.add('is-open');
    this.trigger.setAttribute('aria-expanded', 'true');

    if (this.mode === 'sheet') {
      panel.style.cssText = '';
      delete panel.dataset.side;
      showBackdrop(true, host);
      document.documentElement.classList.add('jl-select-locked');
    } else {
      this.place(true);
    }

    var selected = this.itemAt(nativeIndex.get.call(this.select));
    this.active = -1;
    if (focusEdge === 'first' || focusEdge === 'last') this.move(focusEdge);
    else if (selected && !selected.disabled) this.setActive(selected.index, false);
    else this.move('first');
    var activeItem = this.itemAt(this.active);
    /* Scroll only as far as needed to show the chosen option, so short lists open at the top. */
    this.list.scrollTop = 0;
    if (activeItem) {
      var overflow = activeItem.row.offsetTop + activeItem.row.offsetHeight + 4 - this.list.clientHeight;
      if (overflow > 0) this.list.scrollTop = overflow;
    }

    /* On phones the keyboard would cover the sheet, so the search box waits for a tap. */
    if (this.panel.classList.contains('has-search') && this.mode === 'popover') {
      this.search.focus({ preventScroll: true });
    }

    void panel.offsetWidth;
    panel.classList.add('is-open');
  };

  Select.prototype.close = function (restoreFocus) {
    if (!this.isOpen()) return;
    var self = this;
    var panel = this.panel;
    current = null;
    clearTimeout(this.typedTimer);
    this.typed = '';
    panel.classList.remove('is-open');
    this.wrap.classList.remove('is-open');
    this.trigger.setAttribute('aria-expanded', 'false');
    this.trigger.removeAttribute('aria-activedescendant');
    this.search.removeAttribute('aria-activedescendant');
    if (this.mode === 'sheet') {
      showBackdrop(false);
      document.documentElement.classList.remove('jl-select-locked');
    }
    if (restoreFocus) this.trigger.focus({ preventScroll: true });
    this.closeTimer = setTimeout(function () {
      if (!self.isOpen()) panel.hidden = true;
    }, this.mode === 'sheet' ? 280 : 180);
  };

  Select.prototype.choose = function (index) {
    var item = this.itemAt(index);
    if (!item || item.disabled) return;
    var changed = nativeIndex.get.call(this.select) !== index;
    nativeIndex.set.call(this.select, index);
    if (changed) this.nativeInvalid = false;
    this.render();
    this.close(true);
    if (changed) {
      this.select.dispatchEvent(new Event('input', { bubbles: true }));
      this.select.dispatchEvent(new Event('change', { bubbles: true }));
    }
  };

  /* Opens below the box, or above when there is clearly more room there; never wider than the screen. */
  Select.prototype.place = function (allowScroll) {
    if (this.mode !== 'popover') return;
    var panel = this.panel;
    var rect = this.trigger.getBoundingClientRect();
    var vw = document.documentElement.clientWidth;
    var vh = window.innerHeight;
    /* A full-size box gets a list exactly as wide; a small filter pill may grow to fit its options. */
    var boxWidth = Math.round(rect.width);
    panel.style.minWidth = boxWidth + 'px';
    panel.style.maxWidth = (boxWidth >= 240 ? boxWidth : Math.min(420, vw - EDGE * 2)) + 'px';
    panel.style.width = boxWidth >= 240 ? boxWidth + 'px' : '';
    panel.style.maxHeight = '';
    panel.style.left = '0px';
    panel.style.top = '0px';
    panel.style.bottom = 'auto';

    var natural = Math.min(panel.scrollHeight, 380);
    var below = vh - rect.bottom - GAP - EDGE;
    var above = rect.top - GAP - EDGE;
    /* Neither side has room for a useful list (short window, box near an edge): scroll the page up
       once, like the browser's own dropdown, instead of opening a list two rows tall. */
    var useful = Math.min(natural, 260);
    if (below < useful && above < useful && allowScroll) {
      var room = document.documentElement.scrollHeight - window.innerHeight - window.scrollY;
      var shift = Math.min(useful - below, room);
      if (shift > 0) {
        window.scrollBy({ top: shift, behavior: 'instant' });
        rect = this.trigger.getBoundingClientRect();
        below = vh - rect.bottom - GAP - EDGE;
        above = rect.top - GAP - EDGE;
      }
    }
    var up = below < useful && above > below;
    panel.dataset.side = up ? 'top' : 'bottom';
    panel.style.maxHeight = Math.max(Math.min(natural, 200), Math.min(380, up ? above : below)) + 'px';
    /* Inside a transformed modal, `fixed` is measured from that modal instead of the screen. */
    var origin = containingBlock(panel) || { x: 0, y: 0, bottom: vh };
    if (up) {
      panel.style.top = 'auto';
      panel.style.bottom = Math.round(origin.bottom - rect.top + GAP) + 'px';
    } else {
      panel.style.top = Math.round(rect.bottom + GAP - origin.y) + 'px';
    }
    var width = panel.offsetWidth;
    var left = Math.max(EDGE, Math.min(rect.left, vw - width - EDGE));
    panel.style.left = Math.round(left - origin.x) + 'px';
  };

  function containingBlock(panel) {
    for (var node = panel.parentElement; node && node !== document.documentElement; node = node.parentElement) {
      var cs = getComputedStyle(node);
      if (cs.transform !== 'none' || cs.filter !== 'none' || cs.perspective !== 'none' ||
          (cs.backdropFilter && cs.backdropFilter !== 'none') || /paint|layout|strict|content/.test(cs.contain || '')) {
        var r = node.getBoundingClientRect();
        return { x: r.left + node.clientLeft, y: r.top + node.clientTop, bottom: r.top + node.clientTop + node.clientHeight };
      }
    }
    return null;
  }

  Select.prototype.onKey = function (event, fromSearch) {
    var open = this.isOpen();
    var key = event.key;
    if (!open) {
      if (key === 'ArrowDown' || key === 'ArrowUp' || key === 'Enter' || key === ' ') {
        event.preventDefault();
        this.open();
      } else if (key === 'Home' || key === 'End') {
        event.preventDefault();
        this.open(key === 'Home' ? 'first' : 'last');
      } else if (key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey && key !== ' ') {
        this.open();
        if (!this.panel.classList.contains('has-search')) this.typeAhead(key);
        else { this.search.value = key; this.filter(key); event.preventDefault(); }
      }
      return;
    }
    switch (key) {
      case 'ArrowDown': event.preventDefault(); if (event.altKey) break; this.move(1); break;
      case 'ArrowUp':
        event.preventDefault();
        if (event.altKey) { this.choose(this.active); break; }
        this.move(-1); break;
      case 'PageDown': event.preventDefault(); this.move(PAGE_STEP); break;
      case 'PageUp': event.preventDefault(); this.move(-PAGE_STEP); break;
      case 'Home': if (!fromSearch) { event.preventDefault(); this.move('first'); } break;
      case 'End': if (!fromSearch) { event.preventDefault(); this.move('last'); } break;
      case 'Enter': event.preventDefault(); this.choose(this.active); break;
      case ' ':
        if (fromSearch) break;
        event.preventDefault();
        if (this.typed) this.typeAhead(' '); else this.choose(this.active);
        break;
      case 'Escape': event.preventDefault(); event.stopPropagation(); this.close(true); break;
      case 'Tab': this.close(false); break;
      default:
        if (!fromSearch && key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey) this.typeAhead(key);
    }
  };

  function showBackdrop(show, host) {
    if (!backdrop) {
      backdrop = el('div', 'jl-select-backdrop');
      backdrop.hidden = true;
      backdrop.addEventListener('click', function () { if (current) current.close(true); });
    }
    if (host && backdrop.parentNode !== host) host.insertBefore(backdrop, current ? current.panel : null);
    clearTimeout(backdrop._timer);
    if (show) {
      backdrop.hidden = false;
      void backdrop.offsetWidth;
      backdrop.classList.add('is-open');
    } else {
      backdrop.classList.remove('is-open');
      backdrop._timer = setTimeout(function () { backdrop.hidden = true; }, 260);
    }
  }

  document.addEventListener('pointerdown', function (event) {
    if (!current) return;
    if (current.wrap.contains(event.target) || current.panel.contains(event.target)) return;
    if (backdrop && backdrop.contains(event.target)) return;
    current.close(false);
  }, true);

  document.addEventListener('focusin', function (event) {
    if (!current) return;
    if (current.wrap.contains(event.target) || current.panel.contains(event.target)) return;
    current.close(false);
  });

  window.addEventListener('resize', function () {
    if (!current) return;
    if ((current.mode === 'sheet') !== sheetMode()) current.close(false);
    else current.place();
  });

  /* Follow the box while the page or a scrolling container under it moves. */
  document.addEventListener('scroll', function (event) {
    if (!current || current.mode !== 'popover') return;
    if (current.panel.contains(event.target)) return;
    var open = current;
    cancelAnimationFrame(open.scrollFrame);
    open.scrollFrame = requestAnimationFrame(function () {
      var rect = open.trigger.getBoundingClientRect();
      if (rect.bottom < 0 || rect.top > window.innerHeight) open.close(false);
      else open.place();
    });
  }, true);

  function enhance(target) {
    var root = target || document;
    var selects = root.tagName === 'SELECT' ? [root]
      : Array.prototype.slice.call(root.querySelectorAll('select[data-jl-select]'));
    selects.forEach(function (select) {
      if (instances.has(select) || select.multiple || select.size > 1) return;
      instances.set(select, new Select(select));
    });
  }

  function refresh(select) {
    var instance = instances.get(select);
    if (instance) { instance.render(); if (instance.isOpen()) instance.rebuild(); }
  }

  /* Pages that redraw a block with innerHTML drop the old box; drop its list too. */
  function release(node) {
    var selects = node.matches('select.jl-select__native') ? [node]
      : Array.prototype.slice.call(node.querySelectorAll ? node.querySelectorAll('select.jl-select__native') : []);
    if (!selects.length) return;
    /* Wait a beat: a script that only moves the block re-attaches it before this runs. */
    setTimeout(function () {
      selects.forEach(function (select) {
        var instance = instances.get(select);
        if (!instance || select.isConnected) return;
        if (instance.isOpen()) instance.close(false);
        instance.observer.disconnect();
        instance.panel.remove();
        instances.delete(select);
      });
    }, 0);
  }

  window.JlasSelect = {
    enhance: enhance,
    refresh: refresh,
    close: function () { if (current) current.close(false); }
  };

  function start() {
    enhance(document);
    new MutationObserver(function (records) {
      records.forEach(function (record) {
        Array.prototype.forEach.call(record.removedNodes, function (node) {
          if (node.nodeType === 1) release(node);
        });
        Array.prototype.forEach.call(record.addedNodes, function (node) {
          if (node.nodeType !== 1) return;
          if (node.matches('select[data-jl-select]')) enhance(node);
          else if (node.querySelector && node.querySelector('select[data-jl-select]')) enhance(node);
        });
      });
    }).observe(document.body, { childList: true, subtree: true });
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
  else start();
})();
