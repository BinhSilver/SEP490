/**
 * Trang Game Arena (/games): quay lại phòng đang ở, vé ghép trận nhanh đang chờ, vào phòng bạn bè bằng mã.
 */
(function () {
  'use strict';

  var arena = window.JlasArena;
  var LOBBY_REFRESH_MS = 6000;

  var resume = document.querySelector('[data-resume]');
  var quickStatus = document.querySelector('[data-quick-status]');
  var joinForm = document.querySelector('[data-join-form]');
  var joinError = document.querySelector('[data-join-error]');
  var refreshTimer = null;
  var resumeRoomId = null;
  arena.phoneMedia.addEventListener('change', function () {
    resumeRoomId = null;
    loadLobby();
  });

  var mobileLayout = window.matchMedia('(max-width: 900px)');
  function updateJoinPlaceholder() {
    joinForm.elements.code.placeholder = mobileLayout.matches ? 'Mã phòng' : '123456';
  }
  updateJoinPlaceholder();
  mobileLayout.addEventListener('change', updateJoinPlaceholder);

  function renderQuick(ticket) {
    var searching = ticket && ticket.status === 'SEARCHING' && arena.canPlayGame(ticket.gameType);
    quickStatus.hidden = !searching;
    if (!searching) return;
    quickStatus.querySelector('[data-quick-game]').textContent = ticket.gameName;
    quickStatus.querySelector('[data-quick-deck]').textContent = ticket.setTitle;
    quickStatus.querySelector('[data-quick-open]').href = '/games/lobby?mode=QUICK&game='
      + encodeURIComponent(ticket.gameType) + '&setId=' + encodeURIComponent(ticket.setId);
  }

  function loadLobby() {
    arena.api.lobby().then(function (lobby) {
      renderQuick(lobby.quickMatch);
      showResume(lobby.myRoomId);
    }).catch(function () {
      renderQuick(null);
    });
  }

  function showResume(roomId) {
    if (!roomId) {
      resume.hidden = true;
      resumeRoomId = null;
      return;
    }
    if (roomId === resumeRoomId) return;
    resumeRoomId = roomId;
    arena.api.room(roomId).then(function (room) {
      if (!arena.canPlayGame(room.gameType)) { resume.hidden = true; return; }
      resume.querySelector('[data-resume-code]').textContent = room.quickMatch ? room.gameName : room.code;
      resume.querySelector('[data-resume-link]').href = '/games/room/' + encodeURIComponent(room.roomId);
      resume.hidden = false;
    }).catch(function () {
      resume.hidden = true;
    });
  }

  quickStatus.querySelector('[data-quick-cancel]').addEventListener('click', function () {
    arena.api.cancelQuickMatch().then(loadLobby).catch(function (error) {
      if (window.JlasUI) window.JlasUI.toast(error.message, 'error');
    });
  });

  joinForm.addEventListener('submit', function (event) {
    event.preventDefault();
    var input = joinForm.elements.code;
    var code = String(input.value || '').trim();
    joinError.hidden = true;
    if (!/^[0-9]{6}$/.test(code)) {
      joinError.textContent = 'Mã phòng gồm 6 chữ số, ví dụ 123456.';
      joinError.hidden = false;
      input.focus();
      return;
    }
    var button = joinForm.querySelector('button[type="submit"]');
    button.dataset.loading = 'true';
    button.disabled = true;
    arena.api.joinByCode(code).then(function (room) {
      window.location.href = '/games/room/' + encodeURIComponent(room.roomId);
    }).catch(function (error) {
      joinError.textContent = error.message;
      joinError.hidden = false;
      button.dataset.loading = 'false';
      button.disabled = false;
    });
  });

  joinForm.elements.code.addEventListener('input', function (event) {
    event.target.value = event.target.value.replace(/[^0-9]/g, '').slice(0, 6);
  });

  arena.live.connect();
  // Đang tìm trận ở tab khác mà ghép được: đưa thẳng vào phòng.
  arena.live.on('QUICK_MATCH_FOUND', function (payload) {
    if (payload && payload.roomId) window.location.href = '/games/room/' + encodeURIComponent(payload.roomId);
  });

  function scheduleRefresh() {
    window.clearInterval(refreshTimer);
    refreshTimer = window.setInterval(function () {
      if (document.visibilityState === 'visible') loadLobby();
    }, LOBBY_REFRESH_MS);
  }

  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState === 'visible') loadLobby();
  });

  loadLobby();
  scheduleRefresh();
})();
