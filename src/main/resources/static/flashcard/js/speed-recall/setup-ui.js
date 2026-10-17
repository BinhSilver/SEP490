/** Enhance the existing filter without changing the value used to start a session. */
export function bindSetupUi(signal) {
  const select = document.getElementById('filter');
  const form = document.getElementById('setup-form');
  const heading = document.querySelector('.sr-setup__heading');
  if (!select || !form || !heading) return;

  const mobile = matchMedia('(max-width: 640px)');
  const placeHeading = () => {
    if (mobile.matches) form.prepend(heading);
    else form.before(heading);
  };
  placeHeading();
  mobile.addEventListener('change', placeHeading, { signal });

  const wrapper = select.parentElement;
  wrapper.classList.add('sr-filter');
  const trigger = document.createElement('button');
  trigger.type = 'button';
  trigger.id = 'filter-trigger';
  document.getElementById('filter-label').htmlFor = trigger.id;
  trigger.className = 'sr-filter__trigger';
  trigger.setAttribute('role', 'combobox');
  trigger.setAttribute('aria-labelledby', 'filter-label filter-value');
  trigger.setAttribute('aria-haspopup', 'listbox');
  trigger.setAttribute('aria-controls', 'filter-options');
  trigger.setAttribute('aria-expanded', 'false');
  const value = document.createElement('span');
  value.id = 'filter-value';
  const arrow = document.createElement('i');
  arrow.className = 'fa-solid fa-chevron-down';
  arrow.setAttribute('aria-hidden', 'true');
  trigger.append(value, arrow);
  const menu = document.createElement('div');
  menu.id = 'filter-options';
  menu.className = 'sr-filter__options';
  menu.setAttribute('role', 'listbox');
  menu.setAttribute('aria-labelledby', 'filter-label');
  menu.hidden = true;
  const buttons = Array.from(select.options, (option, index) => {
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'sr-filter__option';
    button.id = 'filter-option-' + index;
    button.setAttribute('role', 'option');
    button.tabIndex = -1;
    button.textContent = option.textContent;
    button.addEventListener('click', () => {
      select.value = option.value;
      sync();
      close();
      select.dispatchEvent(new Event('change', { bubbles: true }));
    }, { signal });
    menu.append(button);
    return button;
  });
  let active = 0;
  let search = '', searchedAt = 0;
  const sync = () => {
    value.textContent = select.selectedOptions[0]?.textContent || '';
    buttons.forEach((button, index) => {
      button.setAttribute('aria-selected', String(index === select.selectedIndex));
      button.disabled = select.options[index].disabled;
    });
    trigger.disabled = select.disabled;
  };
  const focus = index => {
    active = (index + buttons.length) % buttons.length;
    buttons.forEach((button, i) => button.classList.toggle('is-focused', i === active));
    trigger.setAttribute('aria-activedescendant', buttons[active].id);
    buttons[active].scrollIntoView({ block: 'nearest' });
  };
  const open = () => {
    sync();
    menu.hidden = false;
    trigger.setAttribute('aria-expanded', 'true');
    focus(Math.max(0, select.selectedIndex));
  };
  const close = () => {
    menu.hidden = true;
    trigger.setAttribute('aria-expanded', 'false');
    trigger.removeAttribute('aria-activedescendant');
  };
  trigger.addEventListener('click', () => menu.hidden ? open() : close(), { signal });
  trigger.addEventListener('keydown', event => {
    const { key } = event;
    if (key === 'Tab' || key === 'Escape') { close(); return; }
    if (['ArrowDown', 'ArrowUp', 'Home', 'End', 'Enter', ' '].includes(key)) {
      event.preventDefault();
      if (menu.hidden) { open(); return; }
      if (key === 'Enter' || key === ' ') buttons[active].click();
      else focus(key === 'Home' ? 0 : key === 'End' ? buttons.length - 1 : active + (key === 'ArrowDown' ? 1 : -1));
    } else if (key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey) {
      event.preventDefault();
      search = (Date.now() - searchedAt > 700 ? '' : search) + key.toLocaleLowerCase('vi');
      searchedAt = Date.now();
      if (menu.hidden) open();
      const index = buttons.findIndex(button => !button.disabled && button.textContent.toLocaleLowerCase('vi').startsWith(search));
      if (index >= 0) focus(index);
    }
  }, { signal });
  // Keep focus on the combobox so keyboard selection and touch selection agree.
  menu.addEventListener('pointerdown', event => event.preventDefault(), { signal });
  document.addEventListener('pointerdown', event => { if (!wrapper.contains(event.target)) close(); }, { signal });
  wrapper.addEventListener('focusout', event => { if (!wrapper.contains(event.relatedTarget)) close(); }, { signal });
  select.addEventListener('change', sync, { signal });
  select.hidden = true;
  select.tabIndex = -1;
  wrapper.append(trigger, menu);
  sync();
}
