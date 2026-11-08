(function (root, factory) {
  'use strict';

  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) root.MissionSummaryWidget = api;

  if (root && root.document) {
    if (root.document.readyState === 'loading') {
      root.document.addEventListener('DOMContentLoaded', api.mount, { once: true });
    } else {
      api.mount();
    }
  }
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';

  function escapeHtml(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#039;');
  }

  function icon(name) {
    return '<span class="material-symbols-outlined" aria-hidden="true">' + name + '</span>';
  }

  function number(value) {
    var result = Number(value);
    return Number.isFinite(result) && result >= 0 ? result : 0;
  }

  function percentage(done, target) {
    if (!target) return 0;
    return Math.max(0, Math.min(100, Math.round((done / target) * 100)));
  }

  function renderBar(label, done, target) {
    var percent = percentage(done, target);
    var complete = done >= target;
    return '<div class="ms-goal__row">' +
      '<div class="ms-goal__top"><span>' + escapeHtml(label) + '</span>' +
      '<strong>' + done + '/' + target + '</strong></div>' +
      '<div class="ms-bar" aria-hidden="true">' +
      '<span class="' + (complete ? 'is-done' : '') + '" style="width:' + percent + '%"></span></div></div>';
  }

  function renderCard(data) {
    data = data || {};
    var daily = data.dailyGoal || {};
    var completed = daily.completed === true;
    return '<section class="ms-card" aria-label="Mục tiêu và điểm thưởng hôm nay">' +
      '<header class="ms-card__head">' + icon('local_fire_department') +
        '<h2>Mục tiêu &amp; điểm thưởng hôm nay</h2></header>' +
      '<div class="ms-stats">' +
        '<div class="ms-stat"><span class="ms-stat__value">' + icon('local_fire_department') +
          number(data.currentStreak) + '</span>' +
          '<span class="ms-stat__label">Chuỗi ngày học · dài nhất ' + number(data.longestStreak) + '</span></div>' +
        '<div class="ms-stat ms-stat--credit"><span class="ms-stat__value">' + icon('stars') +
          number(data.creditBalance) + '</span>' +
          '<span class="ms-stat__label">Điểm thưởng</span></div>' +
      '</div>' +
      '<div class="ms-goal">' +
        renderBar('Từ vựng', number(daily.vocabularyDone), number(daily.vocabularyTarget)) +
        renderBar('Quiz', number(daily.quizDone), number(daily.quizTarget)) +
        renderBar('Phút học', number(daily.minutesDone), number(daily.minutesTarget)) +
      '</div>' +
      (completed ? '<div class="ms-completed">' + icon('celebration') +
        '<span>Đã hoàn thành mục tiêu hôm nay!</span></div>' : '') +
      '</section>';
  }

  function mount() {
    var root = document.getElementById('missionSummaryRoot');
    if (!root || !window.JlasAuth || !window.JlasAuth.requireLogin()) return;

    root.innerHTML = '<div class="ms-state">Đang tải mục tiêu…</div>';
    fetch('/api/mission/summary', { headers: window.JlasAuth.authHeaders() })
      .then(function (response) {
        return response.json().catch(function () { return {}; });
      })
      .then(function (body) {
        if (!body || body.success === false || !body.data) {
          root.innerHTML = '';
          return;
        }
        root.innerHTML = renderCard(body.data);
      })
      .catch(function () {
        root.innerHTML = '';
      });
  }

  return {
    renderCard: renderCard,
    mount: mount
  };
});
