(function (root, factory) {
  'use strict';

  var api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  if (root) root.JlasPageNavigation = api;
  if (root && root.document) api.mount(root.document, root);
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';

  var EDGE_DISTANCE = 120;

  function resolveBackAction(referrer, origin, fallbackHref) {
    try {
      if (referrer && new URL(referrer).origin === origin) return { type: 'history' };
    } catch (ignored) {
      // Treat an invalid or unavailable referrer as a direct visit.
    }
    return { type: 'fallback', href: fallbackHref };
  }

  function getScrollVisibility(scrollY, scrollHeight, viewportHeight) {
    var maximum = Math.max(0, scrollHeight - viewportHeight);
    return {
      up: scrollY > EDGE_DISTANCE,
      down: maximum - scrollY > EDGE_DISTANCE
    };
  }

  function fallbackFor(main) {
    if (main.classList.contains('ilp-study-page')) {
      var pathLink = main.querySelector('.ilp-breadcrumb a[href]');
      return pathLink ? pathLink.getAttribute('href') : '/learning/path/v2';
    }
    if (main.classList.contains('ilp-page')) return '/learning';
    return '/';
  }

  function makeButton(document, className, label, symbol) {
    var button = document.createElement('button');
    button.type = 'button';
    button.className = className;
    button.setAttribute('aria-label', label);
    button.title = label;

    var icon = document.createElement('span');
    icon.className = 'lp-page-nav__icon';
    icon.setAttribute('aria-hidden', 'true');
    icon.textContent = symbol;
    button.appendChild(icon);

    var text = document.createElement('span');
    text.className = 'lp-page-nav__label';
    text.textContent = label;
    button.appendChild(text);
    return button;
  }

  function mount(document, window) {
    var main = document.getElementById('jlabBaseContent');
    if (!main || (!main.classList.contains('ilp-page') && !main.classList.contains('survey-page'))) return;
    if (document.querySelector('.lp-page-nav__dock')) return;

    var breadcrumb = main.querySelector('.ilp-breadcrumb');
    var top = document.createElement('div');
    top.className = 'lp-page-nav__top';
    var back = makeButton(document, 'lp-page-nav__back', 'Quay lại', '←');
    back.addEventListener('click', function () {
      var action = resolveBackAction(document.referrer, window.location.origin, fallbackFor(main));
      if (action.type === 'history') window.history.back();
      else window.location.assign(action.href);
    });
    top.appendChild(back);
    if (breadcrumb) top.appendChild(breadcrumb);
    main.insertBefore(top, main.firstChild);

    var dock = document.createElement('nav');
    dock.className = 'lp-page-nav__dock';
    dock.setAttribute('aria-label', 'Điều hướng trang');
    var up = makeButton(document, 'lp-page-nav__scroll', 'Lên đầu', '↑');
    var down = makeButton(document, 'lp-page-nav__scroll', 'Xuống cuối', '↓');
    up.addEventListener('click', function () {
      window.scrollTo({ top: 0, behavior: prefersReducedMotion(window) ? 'auto' : 'smooth' });
    });
    down.addEventListener('click', function () {
      window.scrollTo({ top: Math.max(0, document.documentElement.scrollHeight - window.innerHeight), behavior: prefersReducedMotion(window) ? 'auto' : 'smooth' });
    });
    dock.appendChild(up);
    dock.appendChild(down);
    document.body.appendChild(dock);

    function update() {
      var visible = getScrollVisibility(window.scrollY || window.pageYOffset || 0,
        document.documentElement.scrollHeight, window.innerHeight);
      up.hidden = !visible.up;
      down.hidden = !visible.down;
      dock.hidden = !visible.up && !visible.down;
    }
    window.addEventListener('scroll', update, { passive: true });
    window.addEventListener('resize', update);
    if (typeof window.ResizeObserver === 'function') {
      var resizeObserver = new window.ResizeObserver(update);
      resizeObserver.observe(main);
    }
    update();
  }

  function prefersReducedMotion(window) {
    return window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  }

  return { resolveBackAction: resolveBackAction, getScrollVisibility: getScrollVisibility, mount: mount };
});
