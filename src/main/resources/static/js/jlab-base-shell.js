/**
 * JLAS shared feature shell.
 *
 * This is the single markup and behaviour source for pages that cannot share
 * a Thymeleaf fragment (the application also serves standalone HTML files).
 * It intentionally uses IDs only so legacy module classes cannot capture it.
 *
 * Navigation contract: docs/navigation-contract.md.
 * /learning/path is the visual and behaviour reference. Page scripts must not
 * bind duplicate handlers to any jlabBase* element.
 */
(function () {
  'use strict';

  if (window.JLabBaseShell) {
    window.JLabBaseShell.mount();
    return;
  }

  var state = {
    mounted: false,
    eventsBound: false,
    noticeTimer: null
  };

  var ids = {
    document: 'jlabBaseDocument',
    mount: 'jlabBaseShellMount',
    page: 'jlabBasePage',
    background: 'jlabBaseBackground',
    header: 'jlabBaseHeader',
    headerInner: 'jlabBaseHeaderInner',
    brand: 'jlabBaseBrand',
    brandLogo: 'jlabBaseBrandLogo',
    primaryNav: 'jlabBasePrimaryNav',
    homeLink: 'jlabBaseHomeLink',
    homeIcon: 'jlabBaseHomeIcon',
    homeLabel: 'jlabBaseHomeLabel',
    learningPathLink: 'jlabBaseLearningPathLink',
    learningPathIcon: 'jlabBaseLearningPathIcon',
    learningPathLabel: 'jlabBaseLearningPathLabel',
    lookupLink: 'jlabBaseLookupLink',
    lookupIcon: 'jlabBaseLookupIcon',
    lookupLabel: 'jlabBaseLookupLabel',
    gameLink: 'jlabBaseGameLink',
    gameIcon: 'jlabBaseGameIcon',
    gameLabel: 'jlabBaseGameLabel',
    toolsWrapper: 'jlabBaseToolsWrapper',
    toolsButton: 'jlabBaseToolsButton',
    toolsIcon: 'jlabBaseToolsIcon',
    toolsLabel: 'jlabBaseToolsLabel',
    toolsChevron: 'jlabBaseToolsChevron',
    toolsMenu: 'jlabBaseToolsMenu',
    aiCallLink: 'jlabBaseAiCallLink',
    aiCallIcon: 'jlabBaseAiCallIcon',
    aiCallLabel: 'jlabBaseAiCallLabel',
    flashcardLink: 'jlabBaseFlashcardLink',
    flashcardIcon: 'jlabBaseFlashcardIcon',
    flashcardLabel: 'jlabBaseFlashcardLabel',
    shadowingLink: 'jlabBaseShadowingLink',
    shadowingIcon: 'jlabBaseShadowingIcon',
    shadowingLabel: 'jlabBaseShadowingLabel',
    aboutLink: 'jlabBaseAboutLink',
    aboutLabel: 'jlabBaseAboutLabel',
    actions: 'jlabBaseActions',
    guestActions: 'jlabBaseGuestActions',
    loginLink: 'jlabBaseLoginLink',
    registerLink: 'jlabBaseRegisterLink',
    userActions: 'jlabBaseUserActions',
    notificationWrapper: 'jlabBaseNotificationWrapper',
    notificationButton: 'jlabBaseNotificationButton',
    notificationIcon: 'jlabBaseNotificationIcon',
    notificationBadge: 'jlabBaseNotificationBadge',
    notificationPanel: 'jlabBaseNotificationPanel',
    notificationPanelHeader: 'jlabBaseNotificationPanelHeader',
    notificationTitle: 'jlabBaseNotificationTitle',
    notificationList: 'jlabBaseNotificationList',
    notificationEmpty: 'jlabBaseNotificationEmpty',
    notificationEmptyIcon: 'jlabBaseNotificationEmptyIcon',
    notificationEmptyTitle: 'jlabBaseNotificationEmptyTitle',
    notificationEmptyText: 'jlabBaseNotificationEmptyText',
    accountWrapper: 'jlabBaseAccountWrapper',
    avatarButton: 'jlabBaseAvatarButton',
    avatarButtonImage: 'jlabBaseAvatarButtonImage',
    avatarButtonInitials: 'jlabBaseAvatarButtonInitials',
    accountMenu: 'jlabBaseAccountMenu',
    accountSummary: 'jlabBaseAccountSummary',
    accountAvatar: 'jlabBaseAccountAvatar',
    accountAvatarImage: 'jlabBaseAccountAvatarImage',
    accountAvatarInitials: 'jlabBaseAccountAvatarInitials',
    accountIdentity: 'jlabBaseAccountIdentity',
    accountName: 'jlabBaseAccountName',
    accountEmail: 'jlabBaseAccountEmail',
    profileLink: 'jlabBaseProfileLink',
    profileIcon: 'jlabBaseProfileIcon',
    profileLabel: 'jlabBaseProfileLabel',
    streakLink: 'jlabBaseStreakLink',
    streakIcon: 'jlabBaseStreakIcon',
    streakLabel: 'jlabBaseStreakLabel',
    accountDivider: 'jlabBaseAccountDivider',
    logoutButton: 'jlabBaseLogoutButton',
    logoutIcon: 'jlabBaseLogoutIcon',
    logoutLabel: 'jlabBaseLogoutLabel',
    notice: 'jlabBaseNotice',
    noticeText: 'jlabBaseNoticeText'
  };

  function byId(id) {
    return document.getElementById(id);
  }

  // The base shell appears on standalone pages too. Keep its icons inline so
  // navigation does not depend on a CDN-hosted icon font being available.
  function icon(id, name, extraClass) {
    var paths = {
      home: '<path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1Z"></path><path d="M9 21v-6h6v6"></path>',
      learning: '<path d="m2 10 10-5 10 5-10 5Z"></path><path d="M6 12v5c3 2 9 2 12 0v-5"></path>',
      search: '<circle cx="11" cy="11" r="6"></circle><path d="m20 20-4.2-4.2"></path>',
      game: '<path d="M6 9h12a4 4 0 0 1 3.9 4.9l-1 4A3 3 0 0 1 18 20H6a3 3 0 0 1-2.9-2.1l-1-4A4 4 0 0 1 6 9Z"></path><path d="M8 14h4M10 12v4M16 14h.01M18 16h.01"></path>',
      grid: '<rect x="4" y="4" width="6" height="6" rx="1"></rect><rect x="14" y="4" width="6" height="6" rx="1"></rect><rect x="4" y="14" width="6" height="6" rx="1"></rect><rect x="14" y="14" width="6" height="6" rx="1"></rect>',
      chevron: '<path d="m6 9 6 6 6-6"></path>',
      message: '<path d="M21 11.5a8.4 8.4 0 0 1-9 8.5 9.8 9.8 0 0 1-4.2-1L3 20l1.3-4A8 8 0 1 1 21 11.5Z"></path>',
      layers: '<path d="m12 3 9 5-9 5-9-5 9-5Z"></path><path d="m3 12 9 5 9-5"></path><path d="m3 16 9 5 9-5"></path>',
      headphones: '<path d="M4 14v-2a8 8 0 0 1 16 0v2"></path><path d="M4 14h2a2 2 0 0 1 2 2v2a2 2 0 0 1-2 2H4Z"></path><path d="M20 14h-2a2 2 0 0 0-2 2v2a2 2 0 0 0 2 2h2Z"></path>',
      bell: '<path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9"></path><path d="M10 21h4"></path>',
      bellOff: '<path d="m3 3 18 18"></path><path d="M17 17H3c0-2 3-2 3-9a6 6 0 0 1 9.5-4.9"></path><path d="M18 8c0 3.2.6 5 1.4 6.1"></path><path d="M10 21h4"></path>',
      user: '<circle cx="12" cy="8" r="4"></circle><path d="M4 21a8 8 0 0 1 16 0"></path>',
      flame: '<path d="M12 22c4 0 7-2.7 7-6.7 0-3.8-2.5-6.1-5.3-9.3.2 2.8-1.1 4.4-3.2 5.8.1-2.8-1.1-4.9-2.9-6.8C6.6 7.8 5 10.2 5 13.2 5 18.1 8 22 12 22Z"></path>',
      shield: '<path d="M12 3 20 7v5c0 5-3.4 8-8 9-4.6-1-8-4-8-9V7Z"></path><rect x="9" y="11" width="6" height="5" rx="1"></rect><path d="M10.5 11V9.5a1.5 1.5 0 0 1 3 0V11"></path>',
      logout: '<path d="M10 17l5-5-5-5"></path><path d="M15 12H3"></path><path d="M21 3v18"></path>'
    };
    return '<svg' + (id ? ' id="' + id + '"' : '') + ' class="jlab-base-icon' + (extraClass ? ' ' + extraClass : '') + '" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">' + paths[name] + '</svg>';
  }

  function replaceIcon(id, name, extraClass) {
    var element = byId(id);
    if (element) element.outerHTML = icon(id, name, extraClass);
  }

  function replaceShellIcons() {
    replaceIcon(ids.homeIcon, 'home');
    replaceIcon(ids.learningPathIcon, 'learning');
    replaceIcon(ids.lookupIcon, 'search');
    replaceIcon(ids.gameIcon, 'game');
    replaceIcon(ids.toolsIcon, 'grid');
    replaceIcon(ids.toolsChevron, 'chevron', 'jlab-base-icon--chevron');
    replaceIcon(ids.aiCallIcon, 'message');
    replaceIcon(ids.flashcardIcon, 'layers');
    replaceIcon(ids.shadowingIcon, 'headphones');
    replaceIcon(ids.notificationIcon, 'bell');
    replaceIcon(ids.profileIcon, 'user');
    replaceIcon(ids.streakIcon, 'flame');
    replaceIcon(ids.logoutIcon, 'logout');

    var emptyNotificationIcon = byId(ids.notificationEmptyIcon);
    if (emptyNotificationIcon) emptyNotificationIcon.innerHTML = icon('', 'bellOff');
  }

  function shellMarkup() {
    return '' +
      '<div id="' + ids.background + '" aria-hidden="true"></div>' +
      '<header id="' + ids.header + '" class="site-header">' +
        '<div id="' + ids.headerInner + '" class="site-header__inner">' +
          '<a id="' + ids.brand + '" class="brand-logo" href="/" title="JLAS" aria-label="Trang chủ JLAS">' +
            '<img id="' + ids.brandLogo + '" class="brand-logo__img" src="/images/jlab.png?v=20260901-logo2" alt="" width="42" height="42">' +
          '</a>' +
          '<nav id="' + ids.primaryNav + '" class="site-header__nav jlas-platform-nav__links" aria-label="Điều hướng chính">' +
            '<a id="' + ids.homeLink + '" href="/" title="Trang chủ JLAS">' +
              '<i id="' + ids.homeIcon + '" aria-hidden="true"></i><span id="' + ids.homeLabel + '">Trang ch\u1ee7</span>' +
            '</a>' +
            '<a id="' + ids.learningPathLink + '" href="/learning/path" title="Lộ trình Hán Việt  On">' +
              '<i id="' + ids.learningPathIcon + '" aria-hidden="true"></i><span id="' + ids.learningPathLabel + '">Onyomi</span>' +
            '</a>' +
            '<a id="' + ids.gameLink + '" href="/games" title="Chơi game luyện tiếng Nhật">' +
              '<i id="' + ids.gameIcon + '" aria-hidden="true"></i><span id="' + ids.gameLabel + '">Game</span>' +
            '</a>' +
            '<div id="' + ids.toolsWrapper + '" class="dropdown nav-tools-dropdown">' +
              '<button id="' + ids.toolsButton + '" class="nav-tools-trigger" type="button" aria-haspopup="menu" aria-expanded="false" aria-controls="' + ids.toolsMenu + '">' +
                '<i id="' + ids.toolsIcon + '" aria-hidden="true"></i><span id="' + ids.toolsLabel + '">Công cụ</span><i id="' + ids.toolsChevron + '" aria-hidden="true"></i>' +
              '</button>' +
              '<div id="' + ids.toolsMenu + '" class="dropdown__menu nav-tools-menu" role="menu" hidden>' +
                '<a id="' + ids.aiCallLink + '" class="dropdown__item" href="/ai-call" role="menuitem"><i id="' + ids.aiCallIcon + '" aria-hidden="true"></i><span id="' + ids.aiCallLabel + '">AI Call</span></a>' +
                '<a id="' + ids.flashcardLink + '" class="dropdown__item" href="/flashcard/library.html" role="menuitem"><i id="' + ids.flashcardIcon + '" aria-hidden="true"></i><span id="' + ids.flashcardLabel + '">Flashcard</span></a>' +
                '<a id="' + ids.shadowingLink + '" class="dropdown__item" href="/shadowing" role="menuitem"><i id="' + ids.shadowingIcon + '" aria-hidden="true"></i><span id="' + ids.shadowingLabel + '">Shadowing</span></a>' +
              '</div>' +
            '</div>' +
            '<a id="' + ids.aboutLink + '" href="/#about"><span id="' + ids.aboutLabel + '">Giới thiệu</span></a>' +
          '</nav>' +
          '<div id="' + ids.actions + '" class="site-header__actions">' +
            '<div id="' + ids.guestActions + '" class="user-actions" data-auth-guest>' +
              '<a id="' + ids.loginLink + '" href="/login">\u0110\u0103ng nh\u1eadp</a>' +
              '<a id="' + ids.registerLink + '" href="/register">\u0110\u0103ng k\u00fd</a>' +
            '</div>' +
            '<div id="' + ids.userActions + '" class="user-actions" data-auth-user hidden>' +
              '<div id="' + ids.notificationWrapper + '" class="dropdown notification-center">' +
                '<button id="' + ids.notificationButton + '" class="icon-btn notification-center__trigger" type="button" data-dropdown-toggle aria-label="Mở thông báo" aria-haspopup="dialog" aria-expanded="false" aria-controls="' + ids.notificationPanel + '">' +
                  '<i id="' + ids.notificationIcon + '" class="fa-solid fa-bell" aria-hidden="true"></i>' +
                  '<span id="' + ids.notificationBadge + '" class="notification-center__badge" hidden>0</span>' +
                '</button>' +
                '<section id="' + ids.notificationPanel + '" class="dropdown__menu notification-popover" role="dialog" aria-label="Thông báo" hidden>' +
                  '<div id="' + ids.notificationList + '" class="notification-popover__list" hidden></div>' +
                  '<div id="' + ids.notificationEmpty + '" class="notification-popover__empty">' +
                    '<span id="' + ids.notificationEmptyIcon + '" class="notification-popover__empty-icon" aria-hidden="true"><i class="fa-regular fa-bell-slash"></i></span>' +
                    '<strong id="' + ids.notificationEmptyTitle + '">Bạn chưa có thông báo</strong>' +
                    '<p id="' + ids.notificationEmptyText + '">Các cập nhật mới sẽ xuất hiện tại đây.</p>' +
                  '</div>' +
                '</section>' +
              '</div>' +
              '<div id="' + ids.accountWrapper + '" class="dropdown">' +
                '<button id="' + ids.avatarButton + '" class="user-avatar" type="button" aria-label="Mở menu tài khoản" aria-haspopup="menu" aria-expanded="false" aria-controls="' + ids.accountMenu + '">' +
                  '<img id="' + ids.avatarButtonImage + '" class="user-avatar__img" alt="" hidden>' +
                  '<span id="' + ids.avatarButtonInitials + '" class="user-avatar__initials">?</span>' +
                '</button>' +
                '<div id="' + ids.accountMenu + '" class="dropdown__menu profile-menu" role="menu" hidden>' +
                  '<div id="' + ids.accountSummary + '" class="profile-menu__header">' +
                    '<div id="' + ids.accountAvatar + '" class="user-avatar">' +
                      '<img id="' + ids.accountAvatarImage + '" class="user-avatar__img" alt="" hidden>' +
                      '<span id="' + ids.accountAvatarInitials + '" class="user-avatar__initials">?</span>' +
                    '</div>' +
                    '<div id="' + ids.accountIdentity + '">' +
                      '<strong id="' + ids.accountName + '">Người dùng</strong>' +
                      '<span id="' + ids.accountEmail + '"></span>' +
                    '</div>' +
                  '</div>' +
                  '<a id="' + ids.profileLink + '" class="dropdown__item" href="/profile" role="menuitem"><span id="' + ids.profileIcon + '" class="material-symbols-outlined" aria-hidden="true">person</span><span id="' + ids.profileLabel + '">Hồ sơ và cài đặt</span></a>' +
                  '<a id="' + ids.streakLink + '" class="dropdown__item" href="/goals" role="menuitem"><span id="' + ids.streakIcon + '" class="material-symbols-outlined" aria-hidden="true">local_fire_department</span><span id="' + ids.streakLabel + '">Mục tiêu &amp; chuỗi ngày</span></a>' +
                  '<div id="' + ids.accountDivider + '" class="dropdown__divider" aria-hidden="true"></div>' +
                  '<button id="' + ids.logoutButton + '" class="dropdown__item" type="button" role="menuitem"><span id="' + ids.logoutIcon + '" class="material-symbols-outlined" aria-hidden="true">logout</span><span id="' + ids.logoutLabel + '">Đăng xuất</span></button>' +
                '</div>' +
              '</div>' +
            '</div>' +
          '</div>' +
        '</div>' +
      '</header>' +
      '<div id="' + ids.notice + '" role="status" hidden><span id="' + ids.noticeText + '"></span></div>';
  }

  function normalizePath(value) {
    var path = String(value || '/').toLowerCase().split('?')[0];
    path = path.replace(/\/$/, '').replace(/\.html$/, '');
    return path || '/';
  }

  function markCurrent(id, current) {
    var element = byId(id);
    if (!element) return;
    if (current) element.setAttribute('aria-current', 'page');
    else element.removeAttribute('aria-current');
  }

  function syncActiveLink() {
    var path = normalizePath(window.location.pathname);
    var isHome = path === '/' || path === '/index';
    var isLearningPath = path === '/learning/path' ||
      path.indexOf('/learning/lesson/') === 0 ||
      path.indexOf('/learning/quiz/') === 0 ||
      path.indexOf('/learning/rules') === 0 ||
      path.indexOf('/learning/flashcards') === 0;
    var isGame = path === '/games' || path.indexOf('/games/') === 0;
    var isAiCall = path === '/ai-call' ||
      path.indexOf('/ai-call/') === 0 ||
      path.indexOf('/category/') === 0 ||
      path.indexOf('/scenario/') === 0 ||
      path.indexOf('/conversation/') === 0;
    var isFlashcard = path === '/flashcard/library' ||
      path === '/flashcard/index' ||
      path === '/flashcard/flashcard' ||
      path === '/flashcard/editor' ||
      path === '/flashcard/set' ||
      path === '/flashcard/study' ||
      path === '/flashcard/quiz' ||
      path.indexOf('/flashcard/speed-recall') === 0 ||
      path.indexOf('/flashcard/editor/') === 0 ||
      path.indexOf('/flashcard/set/') === 0 ||
      path.indexOf('/flashcard/study/') === 0;
    var isShadowing = path === '/shadowing' || path.indexOf('/shadowing/') === 0;
    var isGoals = path === '/goals' || path === '/learning/goals' || path === '/streak';

    markCurrent(ids.homeLink, isHome);
    markCurrent(ids.learningPathLink, isLearningPath);
    markCurrent(ids.gameLink, isGame);
    markCurrent(ids.aiCallLink, isAiCall);
    markCurrent(ids.flashcardLink, isFlashcard);
    markCurrent(ids.shadowingLink, isShadowing);
    markCurrent(ids.streakLink, isGoals);

    var toolsButton = byId(ids.toolsButton);
    if (toolsButton) {
      if (isAiCall || isFlashcard || isShadowing) {
        toolsButton.setAttribute('aria-current', 'page');
      } else {
        toolsButton.removeAttribute('aria-current');
      }
    }
  }

  function fallbackUser() {
    try {
      var stored = localStorage.getItem('user');
      return stored ? JSON.parse(stored) : null;
    } catch (error) {
      return null;
    }
  }

  function authState() {
    var auth = window.JlasAuth;
    var loggedIn = false;
    var user = null;

    try {
      loggedIn = auth && typeof auth.isLoggedIn === 'function'
        ? auth.isLoggedIn()
        : !!localStorage.getItem('accessToken');
      user = auth && typeof auth.getUser === 'function' ? auth.getUser() : fallbackUser();
    } catch (error) {
      loggedIn = false;
      user = null;
    }

    return { loggedIn: loggedIn, user: user };
  }

  function displayName(user) {
    if (window.JlasAuth && typeof window.JlasAuth.displayName === 'function') {
      return window.JlasAuth.displayName(user);
    }
    return user && (user.fullName || user.email) || 'Người dùng';
  }

  function initials(user) {
    if (window.JlasAuth && typeof window.JlasAuth.initials === 'function') {
      return window.JlasAuth.initials(user);
    }
    var name = displayName(user).trim();
    var parts = name.split(/\s+/);
    return (parts.length > 1 ? parts[0][0] + parts[1][0] : name.slice(0, 2)).toUpperCase();
  }

  function avatarUrl(user) {
    if (window.JlasAuth && typeof window.JlasAuth.avatarUrl === 'function') {
      return window.JlasAuth.avatarUrl(user);
    }
    return user && (user.avatar || user.avatarUrl || user.picture || user.photoUrl || user.imageUrl) || '';
  }

  function renderAvatar(imageId, initialsId, url, label) {
    var image = byId(imageId);
    var text = byId(initialsId);
    if (!image || !text) return;

    text.textContent = label || '?';
    image.onerror = function () {
      image.removeAttribute('src');
      image.hidden = true;
      text.hidden = false;
    };

    if (url) {
      image.referrerPolicy = 'no-referrer';
      image.src = url;
      image.hidden = false;
      text.hidden = true;
    } else {
      image.removeAttribute('src');
      image.hidden = true;
      text.textContent = label;
      text.hidden = false;
    }
  }

  function syncAuth() {
    if (!state.mounted) return;

    var current = authState();
    var guest = byId(ids.guestActions);
    var account = byId(ids.userActions);
    if (guest) guest.hidden = current.loggedIn;
    if (account) account.hidden = !current.loggedIn;

    if (!current.loggedIn) {
      closeMenu(ids.accountMenu, ids.avatarButton);
      closeMenu(ids.notificationPanel, ids.notificationButton);
      renderNotifications({ items: [], unreadCount: 0 });
      return;
    }

    var user = current.user || {};
    var name = displayName(user);
    var label = initials(user);
    var avatar = avatarUrl(user);
    var nameNode = byId(ids.accountName);
    var emailNode = byId(ids.accountEmail);
    if (nameNode) nameNode.textContent = name;
    if (emailNode) emailNode.textContent = user.email || '';
    renderAvatar(ids.avatarButtonImage, ids.avatarButtonInitials, avatar, label);
    renderAvatar(ids.accountAvatarImage, ids.accountAvatarInitials, avatar, label);
    loadNotifications();
  }

  function notificationHeaders() {
    if (window.JlasAuth && typeof window.JlasAuth.authHeaders === 'function') {
      return window.JlasAuth.authHeaders();
    }
    var token = localStorage.getItem('accessToken');
    return token ? { Authorization: 'Bearer ' + token } : {};
  }

  async function loadNotifications() {
    var current = authState();
    if (!current.loggedIn) return;
    try {
      var response = await fetch('/api/notifications', { headers: notificationHeaders() });
      if (!response.ok) return;
      var body = await response.json();
      renderNotifications(body.data || body);
    } catch (error) {
      // The bell remains usable even if the optional feed is unavailable.
    }
  }

  function renderNotifications(feed) {
    feed = feed || {};
    var items = Array.isArray(feed.items) ? feed.items : [];
    var list = byId(ids.notificationList);
    var empty = byId(ids.notificationEmpty);
    var badge = byId(ids.notificationBadge);
    if (!list || !empty || !badge) return;
    list.replaceChildren();
    var unread = Number(feed.unreadCount) || 0;
    badge.textContent = unread > 99 ? '99+' : String(unread);
    badge.hidden = unread === 0;
    list.hidden = items.length === 0;
    empty.hidden = items.length > 0;
    items.forEach(function (item) {
      var link = document.createElement('a');
      link.className = 'notification-popover__item' + (item.read ? '' : ' is-unread');
      link.href = item.targetUrl || '#';
      var title = document.createElement('strong');
      title.textContent = item.title || 'Thông báo';
      var message = document.createElement('span');
      message.textContent = item.message || '';
      link.append(title, message);
      link.addEventListener('click', function () { markNotificationRead(item.id); });
      list.appendChild(link);
    });
  }

  function markNotificationRead(id) {
    if (!id) return;
    fetch('/api/notifications/read', {
      method: 'POST', headers: Object.assign({ 'Content-Type': 'application/json' }, notificationHeaders()),
      body: JSON.stringify({ id: id }), keepalive: true
    }).then(loadNotifications).catch(function () {});
  }

  function closeMenu(menuId, buttonId, restoreFocus) {
    var menu = byId(menuId);
    var button = byId(buttonId);
    var wrapperId = menuId === ids.toolsMenu ? ids.toolsWrapper
      : menuId === ids.accountMenu ? ids.accountWrapper
      : menuId === ids.notificationPanel ? ids.notificationWrapper : null;
    var wrapper = wrapperId ? byId(wrapperId) : null;
    if (menu) menu.hidden = true;
    if (wrapper) wrapper.classList.remove('is-open');
    if (button) {
      button.setAttribute('aria-expanded', 'false');
      if (restoreFocus) button.focus();
    }
  }

  function closeAllMenus(restoreFocusId) {
    closeMenu(ids.toolsMenu, ids.toolsButton, restoreFocusId === ids.toolsButton);
    closeMenu(ids.accountMenu, ids.avatarButton, restoreFocusId === ids.avatarButton);
    closeMenu(ids.notificationPanel, ids.notificationButton, restoreFocusId === ids.notificationButton);
  }

  function toggleMenu(menuId, buttonId, otherMenuId, otherButtonId, firstItemId) {
    var menu = byId(menuId);
    var button = byId(buttonId);
    var wrapperId = menuId === ids.toolsMenu ? ids.toolsWrapper
      : menuId === ids.accountMenu ? ids.accountWrapper : null;
    var wrapper = wrapperId ? byId(wrapperId) : null;
    if (!menu || !button) return;

    var willOpen = menu.hidden;
    closeMenu(otherMenuId, otherButtonId);
    menu.hidden = !willOpen;
    button.setAttribute('aria-expanded', willOpen ? 'true' : 'false');
    if (wrapper) wrapper.classList.toggle('is-open', willOpen);
    if (willOpen) {
      var firstItem = byId(firstItemId);
      if (firstItem) firstItem.focus();
    }
  }

  async function logout() {
    if (window.JlasAuth && typeof window.JlasAuth.logout === 'function') {
      await window.JlasAuth.logout();
      return;
    }
    localStorage.removeItem('accessToken');
    localStorage.removeItem('refreshToken');
    localStorage.removeItem('user');
    document.cookie = 'accessToken=; path=/; max-age=0';
    window.location.href = '/';
  }

  function showNotice(message) {
    var notice = byId(ids.notice);
    var noticeText = byId(ids.noticeText);
    if (!notice || !noticeText) return;
    noticeText.textContent = message;
    notice.hidden = false;
    clearTimeout(state.noticeTimer);
    state.noticeTimer = setTimeout(function () {
      notice.hidden = true;
    }, 3200);
  }

  function redirectToolGuestToLogin(link) {
    var destination = link && link.getAttribute('href');
    if (!destination || destination.charAt(0) !== '/') destination = '/';
    window.location.href = '/login?redirect=' + encodeURIComponent(destination);
  }

  function guardToolNavigation(event) {
    if (authState().loggedIn) return;

    event.preventDefault();
    closeAllMenus();
    redirectToolGuestToLogin(event.currentTarget);
  }

  function bindEvents() {
    if (state.eventsBound) return;
    state.eventsBound = true;

    byId(ids.toolsButton).addEventListener('click', function () {
      closeMenu(ids.notificationPanel, ids.notificationButton);
      toggleMenu(ids.toolsMenu, ids.toolsButton, ids.accountMenu, ids.avatarButton, ids.aiCallLink);
    });

    [ids.aiCallLink, ids.flashcardLink, ids.shadowingLink].forEach(function (linkId) {
      var toolLink = byId(linkId);
      if (toolLink) toolLink.addEventListener('click', guardToolNavigation);
    });

    byId(ids.avatarButton).addEventListener('click', function () {
      closeMenu(ids.notificationPanel, ids.notificationButton);
      toggleMenu(ids.accountMenu, ids.avatarButton, ids.toolsMenu, ids.toolsButton, ids.profileLink);
    });

    byId(ids.notificationButton).addEventListener('click', function (event) {
      event.stopPropagation();
      var panel = byId(ids.notificationPanel);
      var button = byId(ids.notificationButton);
      var wrapper = byId(ids.notificationWrapper);
      if (!panel || !button) return;
      var willOpen = panel.hidden;
      closeMenu(ids.toolsMenu, ids.toolsButton);
      closeMenu(ids.accountMenu, ids.avatarButton);
      panel.hidden = !willOpen;
      button.setAttribute('aria-expanded', willOpen ? 'true' : 'false');
      if (wrapper) wrapper.classList.toggle('is-open', willOpen);
      if (willOpen) loadNotifications();
    });

    byId(ids.logoutButton).addEventListener('click', function () {
      closeAllMenus();
      logout();
    });

    document.addEventListener('click', function (event) {
      var header = byId(ids.header);
      if (header && !header.contains(event.target)) closeAllMenus();
    });

    document.addEventListener('keydown', function (event) {
      if (event.key !== 'Escape') return;
      var toolsOpen = byId(ids.toolsMenu) && !byId(ids.toolsMenu).hidden;
      var accountOpen = byId(ids.accountMenu) && !byId(ids.accountMenu).hidden;
      var notificationsOpen = byId(ids.notificationPanel) && !byId(ids.notificationPanel).hidden;
      closeAllMenus(toolsOpen ? ids.toolsButton
        : accountOpen ? ids.avatarButton
        : notificationsOpen ? ids.notificationButton : null);
    });

    window.addEventListener('popstate', syncActiveLink);
    window.addEventListener('storage', function (event) {
      if (event.key === 'accessToken' || event.key === 'user') syncAuth();
    });
  }

  function mount() {
    if (state.mounted) return true;
    var mountPoint = byId(ids.mount);
    if (!mountPoint || !document.body) return false;

    if (!document.documentElement.id) document.documentElement.id = ids.document;
    if (document.documentElement.id !== ids.document) {
      console.warn('JLabBaseShell: html must use id="' + ids.document + '" for isolated viewport styles.');
    }

    if (!document.body.id) document.body.id = ids.page;
    if (document.body.id !== ids.page) {
      console.warn('JLabBaseShell: body must use id="' + ids.page + '" for isolated background styles.');
    }

    mountPoint.innerHTML = shellMarkup();
    replaceShellIcons();
    state.mounted = true;
    bindEvents();
    syncActiveLink();
    syncAuth();
    return true;
  }

  window.JLabBaseShell = {
    ids: ids,
    mount: mount,
    syncAuth: syncAuth,
    syncActiveLink: syncActiveLink,
    closeMenus: closeAllMenus
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', mount, { once: true });
  } else {
    mount();
  }
})();
