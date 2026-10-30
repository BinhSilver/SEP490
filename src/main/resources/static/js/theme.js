(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.JlasTheme = api;
  if (root && root.document) {
    if (root.document.readyState === 'loading') {
      root.document.addEventListener('DOMContentLoaded', api.init, { once: true });
    } else api.init();
  }
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';
  var STORAGE_KEY = 'jlas-theme';
  var DARK = 'dark';
  var LIGHT = 'light';
  var mountedSwitch = null;

  function systemPrefersDark() {
    try {
      return !!(window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
    } catch (error) { return false; }
  }
  function storedTheme() {
    try {
      var value = window.localStorage.getItem(STORAGE_KEY);
      return value === DARK || value === LIGHT ? value : null;
    } catch (error) { return null; }
  }
  function resolveTheme() {
    return storedTheme() || (systemPrefersDark() ? DARK : LIGHT);
  }
  function updateSwitch(theme) {
    if (!mountedSwitch) return;
    mountedSwitch.setAttribute('aria-checked', String(theme === DARK));
    mountedSwitch.setAttribute('title', theme === DARK ? 'Chuyển sang chế độ sáng' : 'Chuyển sang chế độ tối');
  }
  function applyTheme(theme, persist, animate) {
    if (theme !== DARK && theme !== LIGHT) theme = resolveTheme();
    document.documentElement.setAttribute('data-theme', theme);
    document.documentElement.classList.remove(LIGHT, DARK);
    document.documentElement.classList.add(theme);
    if (persist !== false) {
      try { window.localStorage.setItem(STORAGE_KEY, theme); } catch (error) { /* Storage may be disabled. */ }
    }
    updateSwitch(theme);
    if (window.JlasHomeScene) window.JlasHomeScene.sync(theme, animate === true);
    return theme;
  }
  function toggleTheme() {
    var current = document.documentElement.getAttribute('data-theme') === DARK ? DARK : LIGHT;
    return applyTheme(current === DARK ? LIGHT : DARK, true, true);
  }
  function decoration(className, parent) {
    var span = document.createElement('span');
    span.className = className;
    span.setAttribute('aria-hidden', 'true');
    parent.appendChild(span);
    return span;
  }
  function placeSwitch(button) {
    var actions = document.getElementById('jlabBaseActions');
    var parent = actions || document.body;
    if (button.parentNode !== parent || (actions && parent.firstChild !== button)) {
      if (actions) parent.insertBefore(button, parent.firstChild);
      else parent.appendChild(button);
    }
  }
  function mountSwitch() {
    var button = document.createElement('button');
    button.id = 'jlasThemeSwitch';
    button.className = 'theme-switch';
    button.setAttribute('type', 'button');
    button.setAttribute('role', 'switch');
    button.setAttribute('aria-label', 'Bật tắt chế độ sáng tối');
    var sky = decoration('theme-switch__sky', button);
    decoration('theme-switch__stars', sky);
    decoration('theme-switch__clouds', sky);
    var thumb = decoration('theme-switch__thumb', button);
    thumb.setAttribute('data-theme-switch-anchor', '');
    decoration('theme-switch__sun', thumb);
    decoration('theme-switch__moon', thumb);
    decoration('theme-switch__mist', thumb);
    // Native button activation already handles pointer, Enter, Space and assistive clicks.
    button.addEventListener('click', toggleTheme);
    placeSwitch(button);
    return button;
  }
  function init() {
    if (!document.body) return;
    if (mountedSwitch) { placeSwitch(mountedSwitch); return; }
    var theme = applyTheme(resolveTheme(), false);
    mountedSwitch = mountSwitch();
    updateSwitch(theme);
    window.addEventListener('storage', function (event) {
      if (event.key === STORAGE_KEY || event.key === null) applyTheme(resolveTheme(), false);
    });
    if (window.matchMedia) {
      var system = window.matchMedia('(prefers-color-scheme: dark)');
      if (system.addEventListener) system.addEventListener('change', function () {
        if (!storedTheme()) applyTheme(resolveTheme(), false);
      });
    }
  }
  return { resolveTheme: resolveTheme, applyTheme: applyTheme, toggleTheme: toggleTheme, init: init };
});
