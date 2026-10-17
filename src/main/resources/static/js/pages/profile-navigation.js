(function () {
  'use strict';
  var mobile = window.matchMedia('(max-width: 767px)');
  var premium = document.getElementById('panel-premium');
  var details = document.getElementById('profileDetailsCard');
  var premiumHome = document.createComment('Desktop premium panel');
  if (premium) premium.before(premiumHome);
  var closers = [];

  function syncPremium() {
    if (!premium || !details) return;
    if (mobile.matches) details.after(premium);
    else premiumHome.after(premium);
    premium.classList.toggle('is-profile-inline', mobile.matches);
  }
  window.ProfileNavigation = {
    isPremiumInline: function () { return mobile.matches; },
    resolveTab: function (tab) { return mobile.matches && tab === 'premium' ? 'overview' : tab; }
  };
  syncPremium();

  document.querySelectorAll('.profile-security-flyout').forEach(function (flyout) {
    var trigger = flyout.querySelector('.profile-security-flyout__trigger');
    var menu = flyout.querySelector('.profile-security-flyout__menu');
    var timer, ignoreFocus = false;
    if (!trigger || !menu) return;
    var items = Array.from(menu.querySelectorAll('[role="menuitem"]'));
    function placeMenu() {
      if (!mobile.matches) return;
      var desiredTop = trigger.getBoundingClientRect().bottom + 8;
      var bottomSpace = 88;
      var latestTop = window.innerHeight - menu.offsetHeight - bottomSpace;
      menu.style.setProperty('--profile-menu-top', Math.max(80, Math.min(desiredTop, latestTop)) + 'px');
    }
    function setOpen(open, restoreFocus) {
      window.clearTimeout(timer);
      flyout.classList.toggle('is-open', open);
      trigger.setAttribute('aria-expanded', String(open));
      menu.inert = !open;
      menu.setAttribute('aria-hidden', String(!open));
      if (open) placeMenu();
      if (restoreFocus) {
        ignoreFocus = true;
        trigger.focus({ preventScroll: true });
        ignoreFocus = false;
      }
    }
    closers.push(function () { setOpen(false); });
    trigger.addEventListener('click', function () {
      setOpen(mobile.matches ? !flyout.classList.contains('is-open') : true);
    });
    trigger.addEventListener('keydown', function (event) {
      if (!['ArrowDown', 'ArrowUp'].includes(event.key)) return;
      event.preventDefault();
      setOpen(true);
      var item = event.key === 'ArrowUp' ? items[items.length - 1] : items[0];
      if (item) item.focus({ preventScroll: true });
    });
    flyout.addEventListener('pointerenter', function (event) {
      if (!mobile.matches && event.pointerType === 'mouse') setOpen(true);
    });
    flyout.addEventListener('pointerleave', function (event) {
      if (!mobile.matches && event.pointerType === 'mouse') {
        timer = window.setTimeout(function () { setOpen(false); }, 180);
      }
    });
    menu.addEventListener('pointerenter', function () { window.clearTimeout(timer); });
    menu.addEventListener('pointerleave', function (event) {
      if (!mobile.matches && event.pointerType === 'mouse') {
        timer = window.setTimeout(function () { setOpen(false); }, 180);
      }
    });
    flyout.addEventListener('focusin', function () {
      if (!mobile.matches && !ignoreFocus) setOpen(true);
    });
    flyout.addEventListener('focusout', function () {
      window.setTimeout(function () {
        if (!flyout.contains(document.activeElement)) setOpen(false);
      }, 0);
    });
    menu.addEventListener('keydown', function (event) {
      var index = items.indexOf(document.activeElement), next;
      if (event.key === 'ArrowDown') next = (index + 1) % items.length;
      if (event.key === 'ArrowUp') next = (index - 1 + items.length) % items.length;
      if (event.key === 'Home') next = 0;
      if (event.key === 'End') next = items.length - 1;
      if (next === undefined || !items[next]) return;
      event.preventDefault();
      items[next].focus({ preventScroll: true });
    });
    document.addEventListener('pointerdown', function (event) {
      if (!flyout.contains(event.target)) setOpen(false);
    });
    document.addEventListener('keydown', function (event) {
      if (event.key === 'Escape' && flyout.classList.contains('is-open')) {
        event.preventDefault();
        setOpen(false, true);
      }
    });
    document.addEventListener('scroll', function (event) {
      if (mobile.matches && event.target !== menu && !menu.contains(event.target)) setOpen(false);
    }, true);
  });
  mobile.addEventListener('change', function () {
    closers.forEach(function (close) { close(); });
    syncPremium();
    if (window.switchProfileTab) {
      window.switchProfileTab(new URLSearchParams(location.search).get('tab') || window.INITIAL_TAB || 'overview');
    }
  });
})();
