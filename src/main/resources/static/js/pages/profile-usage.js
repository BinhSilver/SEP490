/*
 * Popup "Mức sử dụng hôm nay" trong trang hồ sơ (GET /api/profile/usage), mở từ nút "Mức sử dụng" ở menu hồ sơ:
 * AI Call (tính theo lượt), chấm phát âm, tra từ nhanh và tạo video Shadowing, theo hạn mức của gói Free / Pro.
 */
(function (root, factory) {
  'use strict';
  var api = factory();
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  if (root) {
    root.JlasProfileUsage = api;
    if (root.document) {
      if (root.document.readyState === 'loading') root.document.addEventListener('DOMContentLoaded', api.mount, { once: true });
      else api.mount();
    }
  }
})(typeof window !== 'undefined' ? window : null, function () {
  'use strict';

  var UPGRADE_URL = '/premium';

  function escapeHtml(value) {
    return String(value == null ? '' : value).replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#039;');
  }

  /** Quota minutes as AI Call turns: 0.6 minutes at 0.2 per turn → 3 lượt. */
  function turns(minutesValue, minutesPerTurn) {
    var perTurn = Number(minutesPerTurn) || 0.2;
    return Math.round((Number(minutesValue) || 0) / perTurn);
  }

  function percent(used, limit) {
    if (!limit || limit <= 0) return 0;
    return Math.max(0, Math.min(100, Math.round((used / limit) * 100)));
  }

  /** One row per allowance: what the learner sees, as plain data so it can be tested without a page. */
  function rows(usage) {
    var call = usage.aiCall || {};
    var checks = usage.pronunciation || {};
    var lookups = usage.quickLookup || {};
    var list = [];

    if (call.unlimited) {
      list.push({ key: 'aiCall', label: 'AI Call hôm nay', value: 'Không giới hạn' });
    } else {
      var usedToday = turns(call.minutesToday, call.minutesPerTurn);
      var dailyTurns = turns(call.dailyLimitMinutes, call.minutesPerTurn);
      // Chỉ báo giá khi đã hết lượt miễn phí; còn lượt thì thanh và số "x/y" đã đủ.
      list.push({
        key: 'aiCall', label: 'AI Call hôm nay',
        value: usedToday + '/' + dailyTurns + ' lượt',
        percent: percent(usedToday, dailyTurns),
        detail: call.turnsLeft > 0 ? '' : 'Hết lượt miễn phí, mỗi lượt ' + call.creditPerTurn + ' CP',
        full: !(call.turnsLeft > 0)
      });
    }

    var left = Math.max(0, (checks.dailyLimit || 0) - (checks.usedToday || 0));
    list.push({
      key: 'pronunciation', label: 'Chấm phát âm hôm nay',
      value: (checks.usedToday || 0) + '/' + (checks.dailyLimit || 0) + ' lần',
      percent: percent(checks.usedToday, checks.dailyLimit),
      detail: left > 0 ? '' : 'Hết lượt miễn phí, mỗi lần ' + checks.creditPrice + ' CP',
      full: left === 0
    });

    list.push(lookups.unlimited
      ? { key: 'quickLookup', label: 'Tra từ nhanh', value: 'Không giới hạn' }
      : {
        key: 'quickLookup', label: 'Tra từ nhanh', value: 'Còn ' + (lookups.remaining || 0) + ' lượt',
        detail: lookups.packPrice + ' CP / ' + lookups.packLookups + ' lượt',
        full: !(lookups.remaining > 0)
      });

    var videos = usage.shadowingCreate;
    if (videos && videos.unlimited) {
      list.push({ key: 'shadowingCreate', label: 'Tạo video Shadowing tuần này', value: 'Không giới hạn' });
    } else if (videos) {
      var minutesLeft = Math.max(0, (videos.weeklyMinutes || 0) - (videos.usedMinutes || 0));
      list.push({
        key: 'shadowingCreate', label: 'Tạo video Shadowing tuần này',
        value: (videos.usedMinutes || 0) + '/' + (videos.weeklyMinutes || 0) + ' phút',
        percent: percent(videos.usedMinutes, videos.weeklyMinutes),
        detail: minutesLeft > 0 ? '' : 'Hết phút miễn phí, mỗi phút vượt ' + videos.creditPerMinute + ' CP',
        full: minutesLeft === 0
      });
    }
    return list;
  }

  /** "Không hỏi lại hôm nay" đang bật cho dòng này: số CP đã tự dùng và nút tắt. */
  function autoPayMarkup(row) {
    var auto = typeof window !== 'undefined' ? window.JlasCpAutoPay : null;
    var paid = auto ? auto.state(row.key) : null;
    if (!paid || !paid.on) return '';
    return '<p class="profile-usage__detail">Tự dùng CP hôm nay: ' + paid.spent + '/' + paid.cap + ' CP '
      + '<button type="button" class="profile-usage__auto-off" data-usage-auto-off="' + escapeHtml(row.key) + '">Hỏi lại khi tốn CP</button></p>';
  }

  function rowMarkup(row) {
    var bar = row.percent == null ? ''
      : '<div class="profile-usage__bar" aria-hidden="true"><div class="profile-usage__fill'
        + (row.full ? ' is-full' : '') + '" style="width:' + row.percent + '%"></div></div>';
    return '<div class="profile-usage__row" data-usage="' + escapeHtml(row.key) + '">'
      + '<div class="profile-usage__line"><span>' + escapeHtml(row.label) + '</span><strong>' + escapeHtml(row.value) + '</strong></div>'
      + bar
      + (row.detail ? '<p class="profile-usage__detail">' + escapeHtml(row.detail) + '</p>' : '')
      + autoPayMarkup(row)
      + '</div>';
  }

  function markup(usage) {
    var plan = usage.pro
      ? '<span class="status-pill status-pill--ok">Gói Pro</span>'
      : '<span class="status-pill status-pill--muted">Gói Miễn phí</span>';
    return '<div class="profile-usage__summary">' + plan + '<span class="profile-usage__balance">Số dư: <strong>'
      + (Number(usage.creditBalance) || 0).toLocaleString('vi-VN') + ' CP</strong></span></div>'
      + rows(usage).map(rowMarkup).join('')
      + (usage.pro ? '' : '<a class="btn btn--primary btn--sm profile-usage__upgrade" href="' + UPGRADE_URL
        + '">Nâng cấp Pro để dùng nhiều hơn</a>');
  }

  /** Fills the popup body; called every time the popup opens so the numbers are current. */
  async function load() {
    var body = document.querySelector('[data-usage-body]');
    if (!body) return;
    body.innerHTML = '<p class="profile-empty">Đang tải…</p>';
    try {
      // JlasAuth has no authFetch: send its Bearer header when it is loaded; the login cookie also signs the request.
      var headers = window.JlasAuth && typeof window.JlasAuth.authHeaders === 'function' ? window.JlasAuth.authHeaders() : {};
      // "Không hỏi lại hôm nay" may have been changed on another device: read the account's choices first.
      var synced = window.JlasCpAutoPay && typeof window.JlasCpAutoPay.sync === 'function'
        ? window.JlasCpAutoPay.sync() : null;
      var response = await fetch('/api/profile/usage', { headers: headers, credentials: 'same-origin' });
      var payload = await response.json().catch(function () { return {}; });
      await synced;
      if (!response.ok || !payload.success) throw new Error(payload.message || 'Không tải được mức sử dụng.');
      body.innerHTML = markup(payload.data);
    } catch (error) {
      body.innerHTML = '<p class="profile-empty">' + escapeHtml(error.message || 'Không tải được mức sử dụng.') + '</p>';
    }
  }

  /** Opens from any [data-usage-open] button; closes on the close button, the backdrop or Escape. */
  function mount() {
    var modal = document.getElementById('profileUsageModal');
    if (!modal) return;
    var opener = null;
    function close() {
      modal.hidden = true;
      document.removeEventListener('keydown', onKey);
      if (opener && typeof opener.focus === 'function') opener.focus();
    }
    function onKey(event) { if (event.key === 'Escape') close(); }
    document.querySelectorAll('[data-usage-open]').forEach(function (button) {
      button.addEventListener('click', function () {
        opener = button;
        modal.hidden = false;
        document.addEventListener('keydown', onKey);
        var closeButton = modal.querySelector('.profile-modal__close');
        if (closeButton) closeButton.focus();
        load();
      });
    });
    modal.querySelectorAll('[data-usage-close]').forEach(function (element) {
      element.addEventListener('click', close);
    });
    modal.addEventListener('click', function (event) {
      var off = event.target.closest && event.target.closest('[data-usage-auto-off]');
      if (!off || !window.JlasCpAutoPay) return;
      window.JlasCpAutoPay.disable(off.getAttribute('data-usage-auto-off'));
      load();
    });
  }

  return { turns: turns, rows: rows, markup: markup, load: load, mount: mount };
});
